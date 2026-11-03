package com.pokeclip.render.work;

import com.pokeclip.render.job.JobEnvelope;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.job.RenderPlan;
import com.pokeclip.render.job.SourceRole;
import com.pokeclip.render.job.SourceSegment;
import com.pokeclip.render.media.Composition;
import com.pokeclip.render.media.Loudness;
import com.pokeclip.render.media.MediaProbe;
import com.pokeclip.render.media.ProcessRunner;
import com.pokeclip.render.media.RenderCommands;
import com.pokeclip.render.media.RenderCommands.AudioRunInput;
import com.pokeclip.render.media.RenderCommands.VideoPiece;
import com.pokeclip.render.media.SegmentTiming;
import com.pokeclip.render.media.SegmentTiming.LengthMatch;
import com.pokeclip.render.media.SegmentTiming.TrackRange;
import com.pokeclip.render.recipe.Recipe;
import com.pokeclip.render.recipe.Recipe.AudioTrack;
import com.pokeclip.render.recipe.Recipe.Output;
import com.pokeclip.render.report.ClipReporter.PlacementItem;
import com.pokeclip.render.report.ClipReporter.ResultLayout;
import com.pokeclip.render.storage.S3Store;
import com.pokeclip.render.work.ClipRenderer.Produced;
import com.pokeclip.render.work.ClipRenderer.Progress;
import com.pokeclip.render.work.ClipRenderer.Rendered;
import com.pokeclip.render.work.RenderTimeline.Note;
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
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;

/**
 * 새 꼴 주문(렌더 계획이 있음)을 A′ 두 단계로 만든다(PDT 설계 r7 7.5 · 13.5). 받기 → 판독 · 길이 대조 → 계획 계산
 * ({@link RenderTimeline}) → 조각 단계(영상이 남는 조각마다 무손실 중간 파일) → 이음 목록 · 소리 묶음 목록 → 소리 크기 재기 →
 * 출력마다 최종 단계.
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
        Set<Long> fetched = fetch(job, dir, deadline);
        progress.report(10, "download");

        Map<Long, SegmentTiming> timings = read(job, fetched, dir, deadline);
        List<Integer> trackIds = recipe.tracks().stream().map(AudioTrack::trackId).toList();
        RenderTimeline timeline = RenderTimeline.of(job.plan(), job.sources(), timings, trackIds);
        timeline.notes().forEach(note -> logNote(jobId, note));
        logFramePick(jobId, job.plan(), timeline);
        long ts = timings.get(job.plan().mediaSeqs().getFirst()).videoTimescale();

        long midBegan = System.nanoTime();
        List<VideoPiece> pieces = cut(timeline, ts, dir, deadline);
        long midStageMs = millisSince(midBegan);
        long midBytes = sizeOf(dir, pieces.size());
        progress.report(20, "cut");

        List<AudioRunInput> runs = runInputs(timeline, dir);
        ProcessRunner.Result measured = runner.run(
                RenderCommands.measureLoudnessJoined(ffmpeg, runs, recipe.tracks()), dir, deadline);
        String loudnessFix = Loudness.correctFilter(measured.stderr(), mapper);
        if (loudnessFix == null) {
            log.info("render.loudness_skipped jobId={} reason=silent", jobId);
        }
        progress.report(30, "loudness");

        SegmentTiming frame = timings.get(job.plan().mediaSeqs().getFirst());
        long finalBegan = System.nanoTime();
        List<Produced> produced = encode(recipe, frame, ts, runs, loudnessFix, dir, deadline, progress);
        long finalStageMs = millisSince(finalBegan);

        log.info("render.graph_size jobId={} videoInputs={} audioRuns={} chains={} outputs={} elapsedMs={} "
                        + "midStageMs={} finalStageMs={} midBytes={}", jobId, pieces.size(), runs.size(),
                runs.size() * trackIds.size(), recipe.outputs().size(), millisSince(began), midStageMs, finalStageMs,
                midBytes);
        logLengthDrift(jobId, job.plan().lengthMs(), timeline.lengthUs());
        return new Rendered(produced, layout(timeline));
    }

    // ── 받기 · 판독(설계 7.2 · 7.4 소리 묶음 7) ──

    /**
     * 주문서 조각을 모두 받는다. 결과 조각 · 확보 조각을 못 받으면 그대로 실패다. 준비 조각을 저장소에서 못 찾으면(다시 해도 같은
     * 실패) 준비 없이 진행하고 {@code render.prime_skipped reason=missing} 을 남긴다 — 잃는 것은 그 묶음 머리 21ms 안 일그러짐이다.
     * 일시 실패는 준비 조각이어도 그대로 올린다(다시 하면 받을 수 있다).
     *
     * @return 받은 조각 seq
     */
    private Set<Long> fetch(JobEnvelope job, Path dir, Instant deadline) {
        Set<Long> fetched = new HashSet<>();
        for (SourceSegment s : job.sources()) {
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
     * 준비 조각은 어긋나면 빼고 {@code render.prime_skipped reason=mismatch} 를 남긴다(실패로 만들지 않음). 확보 조각은 판독만 한다.
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
            } else if (s.ref().role() == SourceRole.AUDIO_CAPTURE) {
                timings.put(s.seq(), probe.timing(dir.resolve(sourceFile(s.seq())), dir, deadline));
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

    static boolean primeMatches(SegmentTiming prime, long ledgerMs, SegmentTiming owner, List<Integer> trackIds) {
        if (prime.lengthMatch(ledgerMs) == LengthMatch.NONE) {
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
    private List<VideoPiece> cut(RenderTimeline timeline, long ts, Path dir, Instant deadline) {
        List<VideoPiece> pieces = new ArrayList<>();
        for (VideoCut c : timeline.videoCuts()) {
            pieces.add(new VideoPiece(sourceFile(c.seq()), c.startPts(), c.endPts(), c.resultTicks()));
        }
        for (int j = 0; j < pieces.size(); j++) {
            runner.run(RenderCommands.cutPiece(ffmpeg, pieces.get(j), j, ts), dir, deadline);
        }
        write(dir.resolve(RenderCommands.JOIN_LIST), RenderCommands.joinList(pieces, ts));
        return pieces;
    }

    /** 소리 묶음마다 concat demuxer 목록을 쓰고 최종 단계 입력으로 옮긴다. */
    private static List<AudioRunInput> runInputs(RenderTimeline timeline, Path dir) {
        List<AudioRunInput> runs = new ArrayList<>();
        for (AudioRun run : timeline.audioRuns()) {
            Set<Integer> silent = new HashSet<>();
            run.tracks().stream().filter(AudioRun.TrackStart::silent).forEach(t -> silent.add(t.trackId()));
            AudioRunInput input = new AudioRunInput(
                    run.primeSeq() == null ? null : sourceFile(run.primeSeq()),
                    run.seqs().stream().map(JoinedRenderer::sourceFile).toList(),
                    run.captureSeq() == null ? null : sourceFile(run.captureSeq()),
                    run.concatDurationsUs(), run.fromSample(), run.toSample(), run.lengthSamples(), silent);
            write(dir.resolve(RenderCommands.runListFile(runs.size())), RenderCommands.runList(input));
            runs.add(input);
        }
        return runs;
    }

    // ── 최종 단계(설계 7.5 (3)) ──

    private List<Produced> encode(Recipe recipe, SegmentTiming frame, long ts, List<AudioRunInput> runs,
                                  String loudnessFix, Path dir, Instant deadline, Progress progress) {
        List<Produced> produced = new ArrayList<>();
        List<Output> outputs = recipe.outputs();
        for (int i = 0; i < outputs.size(); i++) {
            Output output = outputs.get(i);
            Composition.Plan video = Composition.plan(output, frame.width(), frame.height(), output.outputId() + "_",
                    1 + runs.size());
            for (Composition.Image image : video.images()) {
                writeImage(dir.resolve(image.name()), image.image());
            }
            String file = output.outputId() + ".mp4";
            runner.run(RenderCommands.renderJoined(ffmpeg, ts, video, runs, recipe.tracks(), loudnessFix, null,
                    fontsDir, file), dir, deadline);
            produced.add(new Produced(output.outputId(), "video", dir.resolve(file)));
            progress.report(30 + 60 * (i + 1) / outputs.size(), "encode");
        }
        return produced;
    }

    private static ResultLayout layout(RenderTimeline timeline) {
        List<PlacementItem> items = new ArrayList<>();
        for (Placement p : timeline.placements()) {
            items.add(new PlacementItem(p.seq(), p.zeroAtUs(), p.fromUs(), p.toUs(), p.headLeadUs()));
        }
        return new ResultLayout(items, timeline.lengthUs());
    }

    // ── 신호(설계 15절) ──

    /** {@link RenderTimeline} 이 낸 신호에 jobId 를 붙여 남긴다. 결과 길이 상한만 WARN 이다. */
    static void logNote(UUID jobId, Note note) {
        if ("render.length_capped".equals(note.event())) {
            log.warn("{} jobId={} {}", note.event(), jobId, note.fields());
        } else {
            log.info("{} jobId={} {}", note.event(), jobId, note.fields());
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
