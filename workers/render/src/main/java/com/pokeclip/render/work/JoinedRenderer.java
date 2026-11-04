package com.pokeclip.render.work;

import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.JobEnvelope;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.job.RenderPlan;
import com.pokeclip.render.job.SourceRole;
import com.pokeclip.render.job.SourceSegment;
import com.pokeclip.render.media.AssWriter;
import com.pokeclip.render.media.Composition;
import com.pokeclip.render.media.Loudness;
import com.pokeclip.render.media.MediaProbe;
import com.pokeclip.render.media.ProcessRunner;
import com.pokeclip.render.media.RenderCommands;
import com.pokeclip.render.media.RenderCommands.AudioRunInput;
import com.pokeclip.render.media.RenderCommands.TrackHead;
import com.pokeclip.render.media.RenderCommands.VideoPiece;
import com.pokeclip.render.media.SegmentTiming;
import com.pokeclip.render.media.SegmentTiming.LengthMatch;
import com.pokeclip.render.media.SegmentTiming.TrackRange;
import com.pokeclip.render.media.SrtWriter;
import com.pokeclip.render.recipe.Recipe;
import com.pokeclip.render.recipe.Recipe.AudioTrack;
import com.pokeclip.render.recipe.Recipe.Output;
import com.pokeclip.render.recipe.Recipe.SubtitleSegment;
import com.pokeclip.render.recipe.Recipe.Subtitles;
import com.pokeclip.render.report.ClipReporter.PlacementItem;
import com.pokeclip.render.report.ClipReporter.ResultLayout;
import com.pokeclip.render.storage.S3Store;
import com.pokeclip.render.work.ClipRenderer.Produced;
import com.pokeclip.render.work.ClipRenderer.Progress;
import com.pokeclip.render.work.ClipRenderer.Rendered;
import com.pokeclip.render.work.RenderTimeline.Note;
import com.pokeclip.render.work.RenderTimeline.PlacedSubtitle;
import com.pokeclip.render.work.RenderTimeline.Placement;
import com.pokeclip.render.work.RenderTimeline.VideoCut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.stream.LongStream;
import javax.imageio.ImageIO;

/**
 * 새 꼴 주문(렌더 계획이 있음)을 A′ 두 단계로 만든다(PDT 설계 r7 7.5 · 13.5). 받기 → 판독 · 길이 대조 → 계획 계산
 * ({@link RenderTimeline}) → 조각 단계(영상이 남는 조각마다 무손실 중간 파일) → 이음 목록 · 소리 묶음 목록 → 소리 크기 재기 →
 * 출력마다 최종 단계(번인 자막 · CC 파일 포함).
 *
 * <p>모든 파일(받은 조각 · 중간 파일 · 목록 · 그림 · 결과)은 부른 쪽이 준 작업 폴더 {@code dir} 안에 바로 쓴다. 그 폴더는 주문마다
 * 만드는 {@link Workspace} 라 성공 · 실패 · 재시도 모두에서 통째로 지워진다(설계 16.2 P33). 자를 틱 · 놓을 자리 · 소리 값은
 * {@link RenderTimeline} 이 한 번 낸 것만 쓴다 — 여기서 시각을 따로 셈하지 않는다.
 */
final class JoinedRenderer {

    private static final Logger log = LoggerFactory.getLogger(JoinedRenderer.class);

    /** {@code render.length_drift} 를 WARN 으로 올리는 차(ms · 설계 15절). */
    static final long DRIFT_WARN_MS = 1_000;

    private final S3Store store;
    private final MediaProbe probe;
    private final ProcessRunner runner;
    private final ObjectMapper mapper;
    private final String ffmpeg;
    private final String fontsDir;

    JoinedRenderer(S3Store store, MediaProbe probe, ProcessRunner runner, ObjectMapper mapper, String ffmpeg,
                   String fontsDir) {
        this.store = store;
        this.probe = probe;
        this.runner = runner;
        this.mapper = mapper;
        this.ffmpeg = ffmpeg;
        this.fontsDir = fontsDir;
    }

    /** 조각 파일 이름(작업 폴더 안 · seq 로). */
    static String sourceFile(long seq) {
        return "seg_" + seq + ".m4s";
    }

    Rendered render(JobEnvelope job, Path dir, Instant deadline, Progress progress) {
        long began = System.nanoTime();
        UUID jobId = job.jobId();
        Recipe recipe = job.recipe();
        requireDistinctSeqs(job.sources());
        Set<Long> fetched = fetch(job, dir, deadline);
        Map<Long, SegmentTiming> timings = read(job, fetched, dir, deadline);
        List<Integer> trackIds = recipe.tracks().stream().map(AudioTrack::trackId).toList();
        RenderTimeline.Draft draft = RenderTimeline.draft(job.plan(), job.sources(), timings, trackIds);
        SegmentTiming capture = readCapture(draft, timings, dir, deadline);
        progress.report(10, "download");

        RenderTimeline timeline = draft.finish(capture);
        timeline.notes().forEach(note -> logNote(jobId, note));
        logFramePick(jobId, job.plan(), timeline);
        long ts = timings.get(job.plan().mediaSeqs().getFirst()).videoTimescale();
        long[] frameTicks = resultFrameTicks(timeline, timings);
        Captions captions = captions(jobId, recipe.subtitles(), timeline, frameTicks, ts);
        requireAssFrames(jobId, recipe.subtitles(), captions, timeline, timings, ts);

        PeakRss peak = new PeakRss();
        long midBegan = System.nanoTime();
        List<VideoPiece> pieces = cut(timeline, ts, dir, deadline, peak);
        long midStageMs = millisSince(midBegan);
        long midBytes = sizeOf(dir, pieces.size());
        progress.report(20, "cut");

        List<AudioRunInput> runs = runInputs(timeline, timings, dir);
        ProcessRunner.Result measured = peak.see(runner.run(
                RenderCommands.measureLoudnessJoined(ffmpeg, runs, recipe.tracks()), dir, deadline));
        String loudnessFix = Loudness.correctFilter(measured.stderr(), mapper);
        if (loudnessFix == null) {
            log.info("render.loudness_skipped jobId={} reason=silent", jobId);
        }
        progress.report(30, "loudness");

        SegmentTiming frame = timings.get(job.plan().mediaSeqs().getFirst());
        long finalBegan = System.nanoTime();
        FinalStage stage = new FinalStage(frame, ts, runs, recipe.tracks(), loudnessFix);
        List<Produced> produced = encode(recipe, captions, stage, dir, deadline, progress, peak);
        long finalStageMs = millisSince(finalBegan);

        log.info("render.graph_size jobId={} videoInputs={} audioRuns={} chains={} outputs={} elapsedMs={} "
                        + "maxRssMb={} midStageMs={} finalStageMs={} midBytes={}", jobId, pieces.size(), runs.size(),
                runs.size() * trackIds.size(), recipe.outputs().size(), millisSince(began), peak.mb(), midStageMs,
                finalStageMs, midBytes);
        logLengthDrift(jobId, job.plan().lengthMs(), timeline.lengthUs());
        return new Rendered(produced, layout(timeline));
    }

    // ── 받기 · 판독(설계 7.2 · 7.4 소리 묶음 7 · 8) ──

    /**
     * 조각 파일 이름은 seq 로 정하므로 같은 seq 가 둘이면 한 파일을 덮어쓴다. 계획 검사(설계 5.3 2 · 3)가 이미 막지만, 받기
     * 전에 한 번 더 거절한다.
     */
    private static void requireDistinctSeqs(List<SourceSegment> sources) {
        Set<Long> seen = new HashSet<>();
        for (SourceSegment s : sources) {
            if (!seen.add(s.seq())) {
                throw RenderFailure.permanent(ErrorCode.ENVELOPE_VALIDATION, "sourceKeys 의 seq " + s.seq() + "가 겹친다");
            }
        }
    }

    /**
     * 확보 조각 밖의 조각을 모두 받는다(확보 조각은 결과가 쓸 때만 {@link #readCapture}). 결과 조각을 못 받으면 그대로 실패다. 준비 조각을
     * 저장소에서 못 찾으면(다시 해도 같은 실패) 준비 없이 진행하고 {@code render.prime_skipped reason=missing} 을 남긴다 — 잃는
     * 것은 그 묶음 머리 21ms 안 일그러짐이다. 일시 실패는 준비 조각이어도 그대로 올린다(다시 하면 받을 수 있다).
     *
     * @return 받은 조각 seq
     */
    private Set<Long> fetch(JobEnvelope job, Path dir, Instant deadline) {
        Set<Long> fetched = new HashSet<>();
        for (SourceSegment s : job.sources()) {
            if (s.ref().role() == SourceRole.AUDIO_CAPTURE) {
                continue;
            }
            try {
                store.download(s.bucket(), s.s3Key(), dir.resolve(sourceFile(s.seq())), deadline);
                fetched.add(s.seq());
            } catch (RenderFailure f) {
                if (s.ref().role() != SourceRole.AUDIO_PRIME || f.retryable()) {
                    throw f;
                }
                logPrimeSkipped(job.jobId(), s, "missing");
            }
        }
        return fetched;
    }

    /**
     * 받은 조각을 판독한다. 결과 조각은 길이 대조(어긋나면 {@code SOURCE_MISMATCH})와 화면 · 시간 단위 균일 검사를 지나야 한다.
     * 준비 조각은 어긋나면 빼고 {@code render.prime_skipped reason=mismatch} 를 남긴다(실패로 만들지 않음).
     */
    private Map<Long, SegmentTiming> read(JobEnvelope job, Set<Long> fetched, Path dir, Instant deadline) {
        Map<Long, SegmentTiming> timings = new LinkedHashMap<>();
        List<SegmentTiming> media = new ArrayList<>();
        for (SourceSegment s : job.sources()) {
            if (s.ref().role() == SourceRole.SEGMENT) {
                SegmentTiming t = probe.timing(dir.resolve(sourceFile(s.seq())), dir, deadline);
                MediaProbe.requireLength(job.jobId().toString(), s.seq(), s.durationMs(), t);
                timings.put(s.seq(), t);
                media.add(t);
            }
        }
        MediaProbe.requireUniform(media);
        List<Integer> trackIds = job.recipe().tracks().stream().map(AudioTrack::trackId).toList();
        for (SourceSegment s : job.sources()) {
            if (s.ref().role() == SourceRole.AUDIO_PRIME && fetched.contains(s.seq())) {
                primeTiming(job.jobId(), s, timings.get(s.ref().ownerSeq()), trackIds, dir, deadline)
                        .ifPresent(t -> timings.put(s.seq(), t));
            }
        }
        return timings;
    }

    /**
     * 준비 조각 판독 · 대조(설계 7.4 소리 묶음 7). 판독 꼴 어긋남 · 길이 대조 실패 · 고른 트랙 패킷 0 · 고른 트랙 소리 설정이 묶음
     * 머리와 다름 가운데 하나라도 있으면 쓰지 않는다. 머리가 결과에 없으면 대조하지 않고 넘긴다 — 쓸지는
     * {@link RenderTimeline} 이 {@code owner_dropped} 로 정한다.
     */
    private Optional<SegmentTiming> primeTiming(UUID jobId, SourceSegment prime, SegmentTiming owner,
                                                List<Integer> trackIds, Path dir, Instant deadline) {
        SegmentTiming t;
        try {
            t = probe.timing(dir.resolve(sourceFile(prime.seq())), dir, deadline);
        } catch (RenderFailure f) {
            logPrimeSkipped(jobId, prime, "mismatch");
            return Optional.empty();
        }
        if (owner != null && !primeMatches(t, prime.durationMs(), owner, trackIds)) {
            logPrimeSkipped(jobId, prime, "mismatch");
            return Optional.empty();
        }
        return Optional.of(t);
    }

    /**
     * 결과가 쓸 확보 조각만 받고 판독한다 — 쓸지는 결과 끝 소유를 마친 계획이 이미 정했다({@link RenderTimeline.Draft#capture} ·
     * 설계 7.4 소리 묶음 8 · 1). 쓸 조각이면 받기 · 판독 실패(시한 초과 포함)를 그대로 올리고, 쓰지 않을 조각은 손대지 않는다.
     *
     * @return 그 판독(받은 조각 판독 표에도 넣는다). 쓰지 않으면 null
     */
    private SegmentTiming readCapture(RenderTimeline.Draft draft, Map<Long, SegmentTiming> timings, Path dir,
                                      Instant deadline) {
        Optional<SourceSegment> used = draft.capture();
        if (used.isEmpty()) {
            return null;
        }
        SourceSegment capture = used.get();
        Path file = dir.resolve(sourceFile(capture.seq()));
        store.download(capture.bucket(), capture.s3Key(), file, deadline);
        SegmentTiming timing = probe.timing(file, dir, deadline);
        timings.put(capture.seq(), timing);
        return timing;
    }

    /**
     * 준비 조각을 쓸 수 있나({@link #primeTiming} 의 대조). 길이 대조 정수식이 넘치면(장부 길이가 터무니없음) 대조 실패로 친다 —
     * 다시 받아도 같은 값이라 일시 실패로 올리지 않고, 다른 대조 실패처럼 준비 없이 간다.
     */
    static boolean primeMatches(SegmentTiming prime, long ledgerMs, SegmentTiming owner, List<Integer> trackIds) {
        try {
            if (prime.lengthMatch(ledgerMs) == LengthMatch.NONE) {
                return false;
            }
        } catch (ArithmeticException e) {
            return false;
        }
        for (int n : trackIds) {
            if (n >= prime.audio().size() || n >= owner.audio().size()) {
                return false;
            }
            TrackRange mine = prime.audio().get(n);
            if (mine.packets() == 0 || !mine.sameConfig(owner.audio().get(n))) {
                return false;
            }
        }
        return true;
    }

    // ── 조각 단계 · 목록(설계 7.5 (1) · (2)) ──

    /** 영상이 남는 조각마다 차례로 중간 파일을 쓰고, 이음 목록을 쓴다. */
    private List<VideoPiece> cut(RenderTimeline timeline, long ts, Path dir, Instant deadline, PeakRss peak) {
        List<VideoPiece> pieces = new ArrayList<>();
        for (VideoCut c : timeline.videoCuts()) {
            pieces.add(new VideoPiece(sourceFile(c.seq()), c.startPts(), c.endPts(), c.resultTicks()));
        }
        for (int j = 0; j < pieces.size(); j++) {
            peak.see(runner.run(RenderCommands.cutPiece(ffmpeg, pieces.get(j), j, ts), dir, deadline));
        }
        write(dir.resolve(RenderCommands.JOIN_LIST), RenderCommands.joinList(pieces, ts));
        return pieces;
    }

    /**
     * 소리 묶음마다 concat demuxer 목록을 쓰고 최종 단계 입력으로 옮긴다. 흐름에서 읽는 트랙마다 앞 무음(계획 값)과 입력 표본율을
     * 싣는다 — 표본율은 묶음 첫 파일 판독 값이다(concat demuxer 가 첫 파일 설정으로 흐름 전체를 디코드한다).
     */
    private static List<AudioRunInput> runInputs(RenderTimeline timeline, Map<Long, SegmentTiming> timings,
                                                 Path dir) {
        List<AudioRunInput> runs = new ArrayList<>();
        for (AudioRun run : timeline.audioRuns()) {
            SegmentTiming first = timings.get(run.fileSeqs().getFirst());
            Set<Integer> silent = new HashSet<>();
            Map<Integer, TrackHead> heads = new LinkedHashMap<>();
            for (AudioRun.TrackStart t : run.tracks()) {
                if (t.silent()) {
                    silent.add(t.trackId());
                } else {
                    heads.put(t.trackId(), new TrackHead(inputSampleRate(first, t.trackId()),
                            t.frontSilenceSamples()));
                }
            }
            AudioRunInput input = new AudioRunInput(
                    run.primeSeq() == null ? null : sourceFile(run.primeSeq()),
                    run.seqs().stream().map(JoinedRenderer::sourceFile).toList(),
                    run.captureSeq() == null ? null : sourceFile(run.captureSeq()),
                    run.concatDurationsUs(), run.fromSample(), run.toSample(), run.lengthSamples(), silent, heads);
            write(dir.resolve(RenderCommands.runListFile(runs.size())), RenderCommands.runList(input));
            runs.add(input);
        }
        return runs;
    }

    /** 판독한 소리 표본율. 읽지 못했으면(0) 흐름 안 채움 문턱을 정할 수 없어 실패다. */
    private static int inputSampleRate(SegmentTiming first, int trackId) {
        int rate = trackId < first.audio().size() ? first.audio().get(trackId).sampleRate() : 0;
        if (rate <= 0) {
            throw RenderFailure.permanent(ErrorCode.SOURCE_MISMATCH, "소리 트랙 " + trackId + " 표본율을 읽지 못했다");
        }
        return rate;
    }

    // ── 자막(설계 7.6) ──

    /**
     * 결과 축 자막 두 벌. 같은 계획 자막(결과 µs — {@link RenderTimeline} 이 배치표에서 낸 값)에서 나온다.
     *
     * @param cues   CC 의 SRT · 자리가 없는 번인(SRT)에 쓰는 ms 목록({@link SrtWriter#onResult})
     * @param framed 자리가 있는 번인(ASS)에 쓰는 목록 — 결과 프레임 위 10ms 격자 시각({@link AssWriter#onFrames})
     */
    record Captions(List<SubtitleSegment> cues, List<SubtitleSegment> framed) {

        static final Captions NONE = new Captions(List.of(), List.of());

        Captions {
            cues = List.copyOf(cues);
            framed = List.copyOf(framed);
        }
    }

    /**
     * 계획 자막에 레시피 글자를 붙여 결과 축 두 벌로 옮긴다. 레시피에 자막이 없으면 빈 두 벌이다(계획 검사 5.3 5 가 그때 계획
     * 자막도 비게 한다). 결과 µs 길이는 있는데 ms 로 내려 길이 0 이 된 자막은 CC 에서 빠지므로
     * {@code render.subtitle_dropped reason=empty_ms} 를 남긴다 — µs 단계의 {@code reason=empty} 는 {@link RenderTimeline} 이
     * 낸다. 글자가 비어 빠지는 자막은 옛 꼴처럼 남기지 않는다.
     *
     * @param frameTicks {@link #resultFrameTicks}
     * @param timescale  그 틱의 단위
     */
    static Captions captions(UUID jobId, Subtitles recipe, RenderTimeline timeline, long[] frameTicks,
                             long timescale) {
        if (recipe == null) {
            return Captions.NONE;
        }
        List<SubtitleSegment> cues = new ArrayList<>();
        List<SubtitleSegment> framed = new ArrayList<>();
        for (PlacedSubtitle p : timeline.subtitles()) {
            String text = recipe.segments().get(p.index()).text();
            Optional<SubtitleSegment> cue = SrtWriter.onResult(p.startUs(), p.endUs(), text);
            cue.ifPresent(cues::add);
            if (cue.isEmpty() && !text.isBlank()) {
                log.info("render.subtitle_dropped jobId={} index={} reason=empty_ms", jobId, p.index());
            }
            AssWriter.onFrames(p.startUs(), p.endUs(), text, frameTicks, timescale).ifPresent(framed::add);
        }
        return new Captions(cues, framed);
    }

    /**
     * 자리가 있는 번인(ASS)을 태울 주문이면 결과 프레임이 ASS 시각으로 갈리는지 본다(D59 · kty 2026-11-04 「90fps 초과 원본」).
     * 조각(영상 자르기 하나) 안 원본 프레임 간격의 최솟값이 {@value AssWriter#MIN_FRAME_GAP_MS}ms 보다 짧으면 ASS 10ms 격자로는
     * 계획 프레임만 덮을 수 없어 주문을 거절한다. 원본 성질이라 다시 해도 같으므로 종결({@link ErrorCode#VALIDATION} — 원본을 본
     * 뒤에야 가를 수 있는 레시피 거절은 {@code CropGeometry} 처럼 이 코드다)이고, 문장은 입력 값 · 잰 간격을 싣지 않는 고정
     * 글자다(11ms 는 코드 상수). 잰 최소 간격은 신호에만 남긴다. 자막 없음 · CC 만 · 자리 없는 번인(SRT)은 ASS 를 쓰지 않으므로
     * 그대로 간다.
     *
     * <p>조각 이음 자리 간격은 거절 사유가 아니다. 재접속 이음은 두 녹화의 첫 프레임 시차(c)가 달라 30fps 끼리도 이음 간격이
     * 2ms 까지 줄 수 있다(실제 OBS 판독 c 6000 · 3180 → 180틱 — D59 1라운드 cx H). 그 두 프레임은 같은 10ms 칸에 들 수 있어,
     * 자막 경계가 정확히 그 사이에 걸리면 이웃 프레임 하나를 더 덮거나 덜 덮는 한계가 남는다.
     */
    static void requireAssFrames(UUID jobId, Subtitles spec, Captions captions, RenderTimeline timeline,
                                 Map<Long, SegmentTiming> timings, long timescale) {
        requireAssFrames(jobId, spec, captions, cutFrameTicks(timeline, timings), timescale);
    }

    /**
     * {@link #requireAssFrames(UUID, Subtitles, Captions, RenderTimeline, Map, long)} 의 판정 — 조각마다 따로 잰다.
     *
     * @param pieces 조각마다 결과 프레임 시각({@link #cutFrameTicks})
     */
    static void requireAssFrames(UUID jobId, Subtitles spec, Captions captions, List<long[]> pieces,
                                 long timescale) {
        if (!burns(spec, captions) || spec.position() == null) {
            return;
        }
        OptionalLong gap = OptionalLong.empty();
        for (long[] piece : pieces) {
            OptionalLong mine = AssWriter.denseGap(piece, timescale);
            if (mine.isPresent() && (gap.isEmpty() || mine.getAsLong() < gap.getAsLong())) {
                gap = mine;
            }
        }
        if (gap.isEmpty()) {
            return;
        }
        log.warn("render.burn_rejected jobId={} minGapUs={}", jobId,
                Math.floorDiv(Math.multiplyExact(gap.getAsLong(), 1_000_000L), timescale));
        throw RenderFailure.permanent(ErrorCode.VALIDATION,
                "프레임 간격이 " + AssWriter.MIN_FRAME_GAP_MS + "ms 보다 짧은 원본에는 자리가 있는 번인 자막을 넣을 수 없다");
    }

    /** 남은 자막을 태우나(옛 꼴과 같은 갈래 — 남은 자막이 없으면 태우지 않는다 · 빈 파일 금지 · 계약1 3절). */
    private static boolean burns(Subtitles spec, Captions captions) {
        return spec != null && spec.mode().burns() && !captions.cues().isEmpty();
    }

    /**
     * 결과 영상 프레임 시각(결과 축 틱 · 오름차순) — 조각 단계가 남기는 프레임(PTS ∈ [startPts, endPts))에 그 조각 이동량을
     * 더한 값이다. 최종 단계가 이 틱 그대로 내보낸다(설계 16.2 P7 · P32).
     */
    static long[] resultFrameTicks(RenderTimeline timeline, Map<Long, SegmentTiming> timings) {
        return cutFrameTicks(timeline, timings).stream().flatMapToLong(LongStream::of).sorted().toArray();
    }

    /** {@link #resultFrameTicks} 를 조각({@link VideoCut})마다 나눈 것(조각 차례 · 조각 안 오름차순). */
    static List<long[]> cutFrameTicks(RenderTimeline timeline, Map<Long, SegmentTiming> timings) {
        List<long[]> pieces = new ArrayList<>();
        for (VideoCut c : timeline.videoCuts()) {
            pieces.add(LongStream.of(timings.get(c.seq()).videoPtsSorted())
                    .filter(pts -> pts >= c.startPts() && pts < c.endPts())
                    .map(pts -> pts + c.deltaTicks()).sorted().toArray());
        }
        return pieces;
    }

    // ── 최종 단계(설계 7.5 (3)) ──

    /**
     * 최종 단계 입력 가운데 출력마다 같은 것.
     *
     * @param frame 화면 크기를 읽는 첫 결과 조각 판독
     * @param ts    영상 원래 틱
     */
    private record FinalStage(SegmentTiming frame, long ts, List<AudioRunInput> runs, List<AudioTrack> tracks,
                              String loudnessFix) {
    }

    /**
     * 출력마다 최종 단계를 돌린다. 자막은 옛 꼴과 같은 갈래다 — 남은 자막이 있을 때만 태우고(자리가 없으면 예전 모양 SRT 하나 ·
     * 자리가 있으면 출력 해상도마다 ASS) CC 면 출력마다 SRT 를 낸다(빈 파일 금지 · 계약1 3절).
     */
    private List<Produced> encode(Recipe recipe, Captions captions, FinalStage stage, Path dir, Instant deadline,
                                  Progress progress, PeakRss peak) {
        Subtitles spec = recipe.subtitles();
        boolean burn = burns(spec, captions);
        boolean cc = spec != null && spec.mode().cc() && !captions.cues().isEmpty();
        String srt = SrtWriter.render(captions.cues());
        if (burn && spec.position() == null) {
            write(dir.resolve(RenderCommands.BURN_SRT), srt);
        }
        List<Produced> produced = new ArrayList<>();
        List<Output> outputs = recipe.outputs();
        for (int i = 0; i < outputs.size(); i++) {
            Output output = outputs.get(i);
            String burnFile = burn ? burnFile(spec, captions.framed(), output, dir) : null;
            produced.add(encodeVideo(output, burnFile, stage, dir, deadline, peak));
            if (cc) {
                produced.add(writeCc(output, srt, dir));
            }
            progress.report(30 + 60 * (i + 1) / outputs.size(), "encode");
        }
        return produced;
    }

    /** 출력 하나의 영상 — 꾸밈 그림을 쓰고 최종 단계를 돌린다. 그림 입력 번호는 소리 묶음 뒤부터다. */
    private Produced encodeVideo(Output output, String burnFile, FinalStage stage, Path dir, Instant deadline,
                                 PeakRss peak) {
        Composition.Plan video = Composition.plan(output, stage.frame().width(), stage.frame().height(),
                output.outputId() + "_", 1 + stage.runs().size());
        for (Composition.Image image : video.images()) {
            writeImage(dir.resolve(image.name()), image.image());
        }
        String file = output.outputId() + ".mp4";
        peak.see(runner.run(RenderCommands.renderJoined(ffmpeg, stage.ts(), video, stage.runs(), stage.tracks(),
                stage.loudnessFix(), burnFile, fontsDir, file), dir, deadline));
        return new Produced(output.outputId(), "video", dir.resolve(file));
    }

    /** 출력 하나의 CC 파일(모든 출력이 같은 SRT 를 쓴다). */
    private static Produced writeCc(Output output, String srt, Path dir) {
        String srtFile = output.outputId() + ".srt";
        write(dir.resolve(srtFile), srt);
        return new Produced(output.outputId(), "srt", dir.resolve(srtFile));
    }

    /**
     * 태울 자막 파일 이름. 자리가 있는 자막(v2)은 출력 해상도가 출력마다 달라 ASS 를 출력마다 쓴다. 시각은 결과 프레임 위 격자
     * 값이라, 이웃 프레임 간격이 {@value AssWriter#MIN_FRAME_GAP_MS}ms 이상이면 계획이 고른 프레임과 정확히 같은 프레임을
     * 덮는다({@link AssWriter#onFrames}). 조각 안 원본 간격이 그보다 촘촘하면 여기 오기 전에 {@link #requireAssFrames} 가
     * 주문을 거절한다. 조각 이음 자리 두 프레임이 그보다 가까우면(재접속) 자막 경계가 정확히 그 사이일 때만 이웃 프레임
     * 하나가 어긋날 수 있다.
     */
    private static String burnFile(Subtitles spec, List<SubtitleSegment> framed, Output output, Path dir) {
        if (spec.position() == null) {
            return RenderCommands.BURN_SRT;
        }
        String name = output.outputId() + ".ass";
        write(dir.resolve(name), AssWriter.renderOnFrames(framed, spec.position(), output.aspect()));
        return name;
    }

    private static ResultLayout layout(RenderTimeline timeline) {
        List<PlacementItem> items = new ArrayList<>();
        for (Placement p : timeline.placements()) {
            items.add(new PlacementItem(p.seq(), p.zeroAtUs(), p.fromUs(), p.toUs(), p.headLeadUs()));
        }
        return new ResultLayout(items, timeline.lengthUs());
    }

    // ── 신호(설계 15절) ──

    /**
     * {@link RenderTimeline} 이 낸 신호에 jobId 를 붙여 남긴다. 결과 길이 상한과 신호 산식 실패로 생략한 줄은 WARN · 앞 · 끝
     * 무음은 DEBUG 다(설계 15절).
     */
    static void logNote(UUID jobId, Note note) {
        switch (note.event()) {
            case "render.length_capped", "render.signal_skipped" ->
                    log.warn("{} jobId={} {}", note.event(), jobId, note.fields());
            case "render.audio_pad" -> log.debug("{} jobId={} {}", note.event(), jobId, note.fields());
            default -> log.info("{} jobId={} {}", note.event(), jobId, note.fields());
        }
    }

    private static void logPrimeSkipped(UUID jobId, SourceSegment prime, String reason) {
        log.info("render.prime_skipped jobId={} seq={} ownerSeq={} reason={} degraded=true", jobId, prime.seq(),
                prime.ref().ownerSeq(), reason);
    }

    /** 시작 · 끝 틱 선택. 끝 조각에 영상이 남지 않으면(소리만) {@code audioOnly=true}. */
    private static void logFramePick(UUID jobId, RenderPlan plan, RenderTimeline timeline) {
        VideoCut first = timeline.videoCuts().getFirst();
        VideoCut last = timeline.videoCuts().getLast();
        long lastSeq = timeline.placements().getLast().seq();
        log.info("render.frame_pick jobId={} edge=in seq={} offsetUs={} origin={} pts={} endPts={} audioOnly=false",
                jobId, first.seq(), plan.in().offsetUs(), plan.offsetOrigin(), first.startPts(), first.endPts());
        log.info("render.frame_pick jobId={} edge=out seq={} offsetUs={} origin={} pts={} endPts={} audioOnly={}",
                jobId, lastSeq, plan.out().toSegmentEnd() ? "toSegmentEnd" : plan.out().offsetUs(),
                plan.offsetOrigin(), last.startPts(), last.endPts(), lastSeq != last.seq());
    }

    /** 계획 길이와 결과 길이의 차(렌더 때 하한 없음 · 설계 9.3). */
    static void logLengthDrift(UUID jobId, long plannedMs, long lengthUs) {
        long actualMs = Math.floorDiv(lengthUs + 500, 1_000);
        if (Math.abs(actualMs - plannedMs) > DRIFT_WARN_MS) {
            log.warn("render.length_drift jobId={} plannedMs={} actualMs={}", jobId, plannedMs, actualMs);
        } else {
            log.info("render.length_drift jobId={} plannedMs={} actualMs={}", jobId, plannedMs, actualMs);
        }
    }

    /**
     * 한 주문에서 돈 새 꼴 ffmpeg 들의 최대 RSS — {@code render.graph_size maxRssMb}(설계 15절 · 13.5 enc1 결정과 N8 이 기대는
     * 관측). 주문 하나 안에서만 쓴다.
     */
    private static final class PeakRss {

        private long kib = -1;

        /** 그 실행의 {@code -benchmark} 줄을 읽어 넣고 결과를 그대로 돌려준다. */
        ProcessRunner.Result see(ProcessRunner.Result result) {
            RenderCommands.maxRssKib(result.stderr()).ifPresent(k -> kib = Math.max(kib, k));
            return result;
        }

        /** MiB(내림). 줄을 하나도 읽지 못했으면 {@code unknown}. */
        String mb() {
            return kib < 0 ? "unknown" : Long.toString(kib / 1_024);
        }
    }

    // ── 파일 ──

    private static long sizeOf(Path dir, int pieces) {
        long total = 0;
        for (int j = 0; j < pieces; j++) {
            try {
                total += Files.size(dir.resolve(RenderCommands.midFile(j)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return total;
    }

    private static long millisSince(long nanos) {
        return Duration.ofNanos(System.nanoTime() - nanos).toMillis();
    }

    private static void writeImage(Path file, BufferedImage image) {
        try {
            ImageIO.write(image, "png", file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, String text) {
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
