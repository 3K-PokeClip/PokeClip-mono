package com.pokeclip.render.work;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pokeclip.render.job.EnvelopeParser;
import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.JobEnvelope;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.media.MediaProbe;
import com.pokeclip.render.media.RenderCommands;
import com.pokeclip.render.media.SegmentTiming;
import com.pokeclip.render.recipe.Recipe.AudioTrack;
import com.pokeclip.render.storage.S3Store;
import com.pokeclip.render.support.DirectSegments;
import com.pokeclip.render.support.DirectSegments.Recording;
import com.pokeclip.render.support.Ffmpeg;
import com.pokeclip.render.support.Fixtures;
import com.pokeclip.render.support.PlanOrder;
import com.pokeclip.render.support.RecordingRunner;
import com.pokeclip.render.support.SyncCorrection;
import com.pokeclip.render.support.SyncCorrection.Judged;
import com.pokeclip.render.work.RenderTimeline.VideoCut;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 새 꼴 주문을 이 기계의 실제 ffmpeg 로 A′ 전 경로(조각 단계 → 이음 목록 → 소리 묶음 → 최종 단계)까지 돌린다(PDT 설계 r7 7.5 ·
 * 13.5). S3 만 가짜다. 자료는 시험 안에서 바로 쓴 합성 조각({@link DirectSegments} · 생성 경로 「바로 씀」 — 오라클 보정 없음)이다.
 *
 * <p>판정선(설계 13.5 확정 문장): 영상 = 계획 틱과 0틱 차 · 결과 영상 time_base 1/90000. 소리 = 계획 자리에서 0.1ms 안. 소리
 * 오라클은 AAC 재인코딩 전 PCM 이다 — 최종 단계 명령의 필터 그래프 그대로에서 트랙마다 묶음 이음({@code [acatN]})을 뽑아, 그
 * 녹화 연속 fMP4 를 한 번에 디코드한 기준과 맞댄다. 기대 자리는 영상 계획(조각 Δ)에서 내고 기준 자리를 정수로 반올림하지 않는다.
 */
class JoinedRendererTest {

    private static final long OUT_RATE = 48_000;
    /** AAC 패킷 하나(표본). */
    private static final long AAC_FRAME = 1_024;
    /** 꾸밈 그림 출력 마감 — 정상이면 몇 초, 끝나지 않으면 이 안에 시한 초과로 실패한다. */
    private static final Duration DECORATED_DEADLINE = Duration.ofSeconds(60);

    @TempDir
    static Path fixtures;
    private static Recording ra;
    private static Recording rb;
    private static final Map<Long, Path> FILES = new HashMap<>();
    private static final Map<Long, Recording> OWNER = new HashMap<>();

    @TempDir
    Path dir;

    private final RecordingRunner runner = new RecordingRunner();
    private final S3Store store = mock(S3Store.class);
    private ListAppender<ILoggingEvent> logs;

    @BeforeAll
    static void segments() {
        assumeThat(Ffmpeg.available()).as("ffmpeg 필요").isTrue();
        // ra: 12초 · 150번째 프레임(5.0초 · 조각 11 안)부터 +90틱 → 조각 10 · 11 · 12. rb: 8초 · 격자 그대로 → 조각 20 · 21
        ra = DirectSegments.write(fixtures, "ra", "testsrc2=size=160x90:rate=30", 12, 150, 11, 4);
        rb = DirectSegments.write(fixtures, "rb", "testsrc=size=160x90:rate=30", 8, -1, 31, 4);
        for (int i = 0; i < ra.pieces().size(); i++) {
            FILES.put(10L + i, ra.pieces().get(i));
            OWNER.put(10L + i, ra);
        }
        for (int i = 0; i < rb.pieces().size(); i++) {
            FILES.put(20L + i, rb.pieces().get(i));
            OWNER.put(20L + i, rb);
        }
    }

    @BeforeEach
    void setUp() {
        doAnswer(inv -> {
            String key = inv.getArgument(1);
            long seq = Long.parseLong(key.substring(key.lastIndexOf('_') + 1, key.lastIndexOf('.')));
            Files.copy(FILES.get(seq), inv.<Path>getArgument(2));
            return null;
        }).when(store).download(anyString(), anyString(), any(), any());
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(JoinedRenderer.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(JoinedRenderer.class)).detachAppender(logs);
    }

    /**
     * 같은 녹화로 이어진 조각 셋(한 묶음). 조각 11 의 5.0초부터 프레임이 1/30 초 격자에서 90틱 어긋나 있다 — 최종 단계에
     * {@code -enc_time_base:v} 가 없으면 이 프레임들이 격자로 반올림돼 1.000ms 앞선다(설계 16.2 P32 · 계획 C6).
     */
    @Test
    void 한_묶음_결과의_프레임은_계획_틱에_소리는_계획_자리에_놓인다() {
        String body = order().segment(10, false, false).segment(11, false, true).segment(12, false, true)
                .in(10, 3_000_000).out(12, 1_000_000).body();

        Run run = render(body);

        assertThat(run.timeline().audioRuns()).hasSize(1);
        assertDirectFixture(run);
        assertVideoOnPlan(run);
        assertAudioOnPlan(run);
        assertJoinEvidence(run);
        assertFinalAudio(run);
        assertCommandShape(run);
        assertThat(run.plannedResultTicks()).as("픽스처에 격자에서 어긋난 계획 틱이 있다")
                .anySatisfy(t -> assertThat(t % DirectSegments.FRAME_TICKS).isNotZero());
        assertThat(finalGraph()).as("픽스처 소리(일정한 세기의 분홍 잡음)는 잰 LRA 가 0 — 평준화가 동적 모드로 떨어진다")
                .contains(":measured_LRA=0.00:", ":linear=true");
    }

    /** 끊긴 이웃 뒤 둘째 묶음. 소리 구간마다 0 에서 시작해야 둘째 묶음이 계획 자리에 놓인다(설계 16.2 P25). */
    @Test
    void 끊긴_이웃_뒤_둘째_묶음도_계획_자리에_놓인다() {
        String body = order().segment(12, false, false).segment(20, true, true)
                .in(12, 2_000_000).out(20, 3_000_000).body();

        Run run = render(body);

        assertThat(run.timeline().audioRuns()).hasSize(2);
        assertThat(run.timeline().audioRuns().get(1).resultStartSample()).isPositive();
        assertDirectFixture(run);
        assertVideoOnPlan(run);
        assertAudioOnPlan(run);
        assertJoinEvidence(run);
        assertFinalAudio(run);
        assertCommandShape(run);
    }

    /**
     * 꾸밈 그림(둥근 모서리 · 그림자 · 테두리 · 흐린 바탕 · 구분선)이 든 출력. 그림은 {@code -loop 1} 로 끝없이 이어지고 새 꼴 최종
     * 단계에는 출력 {@code -t} 가 없다 — 그림을 쓰는 합성이 원본에서 끝나지 않으면 마감까지 인코딩하다 시한 초과로 실패한다.
     * 마감을 짧게 걸어 끝나지 않는 꼴이 곧 실패가 되게 한다.
     */
    @Test
    void 꾸밈_그림_출력도_원본_끝에서_끝나고_프레임과_소리는_계획대로다() {
        ObjectNode envelope = order().segment(10, false, false).segment(11, false, true)
                .in(10, 3_000_000).out(11, 1_000_000).json();
        envelope.set("recipe", Fixtures.recipeV2("s1"));

        Run run = render(Fixtures.MAPPER.writeValueAsString(envelope), Instant.now().plus(DECORATED_DEADLINE));

        List<String> last = finalCommand();
        assertThat(last).containsSequence("-loop", "1", "-i", "o1_l1_mask.png");
        assertThat(last).contains("o1_l1_shadow.png", "o1_l1_ring.png");
        assertThat(finalGraph()).contains("alphamerge=shortest=1", "gblur=", "drawbox=");
        assertVideoOnPlan(run);
        assertFinalAudio(run);
    }

    /** 디코더 준비 조각이 실리면 묶음 머리 [0, 640) 표본이 연속 디코드 기준과 같다(설계 16.2 P24 ② · 7.4 소리 묶음 7). */
    @Test
    void 준비_조각이_있으면_묶음_머리도_연속_디코드와_같다() {
        String body = order().prime(10, 11).segment(11, false, false).in(11, 0).out(11, 2_000_000).body();

        Run run = render(body);

        assertThat(run.timeline().audioRuns().getFirst().primeSeq()).isEqualTo(10L);
        assertThat(headDiff(run)).as("머리 640표본 최대 차").isLessThanOrEqualTo(1e-4);
        assertAudioOnPlan(run);
    }

    /** 준비 조각 길이가 장부와 맞지 않으면 준비 없이 진행하고 열화로 남긴다 — 그러면 머리가 연속 디코드와 달라진다(오라클 검출력). */
    @Test
    void 판독이_어긋난_준비_조각은_빼고_열화로_남긴다() {
        ObjectNode envelope = order().prime(10, 11).segment(11, false, false).in(11, 0).out(11, 2_000_000).json();
        ((ObjectNode) envelope.get("sourceKeys").get(0)).put("durationMs", 9_000);

        Run run = render(Fixtures.MAPPER.writeValueAsString(envelope));

        assertThat(run.timeline().audioRuns().getFirst().primeSeq()).isNull();
        assertThat(messages()).anySatisfy(m -> assertThat(m).startsWith("render.prime_skipped jobId=")
                .contains("seq=10 ownerSeq=11 reason=mismatch degraded=true"));
        assertThat(headDiff(run)).as("준비 없이 시작한 디코더의 머리").isGreaterThan(1e-3);
    }

    @Test
    void 저장소에_없는_준비_조각은_빼고_열화로_남긴다() {
        doAnswer(inv -> {
            throw RenderFailure.permanent(ErrorCode.SOURCE_EXPIRED, "영상 조각이 저장소에 없다");
        }).when(store).download(anyString(), org.mockito.ArgumentMatchers.endsWith("seg_10.m4s"), any(), any());
        String body = order().prime(10, 11).segment(11, false, false).in(11, 0).out(11, 2_000_000).body();

        Run run = render(body);

        assertThat(run.timeline().audioRuns().getFirst().primeSeq()).isNull();
        assertThat(messages()).anySatisfy(m -> assertThat(m)
                .contains("render.prime_skipped").contains("seq=10 ownerSeq=11 reason=missing degraded=true"));
    }

    /** 결과 조각을 못 받으면 준비 조각과 달리 그대로 실패다. */
    @Test
    void 저장소에_없는_결과_조각은_실패다() {
        doAnswer(inv -> {
            throw RenderFailure.permanent(ErrorCode.SOURCE_EXPIRED, "영상 조각이 저장소에 없다");
        }).when(store).download(anyString(), org.mockito.ArgumentMatchers.endsWith("seg_11.m4s"), any(), any());
        String body = order().segment(10, false, false).segment(11, false, true).in(10, 0).out(11, 0).body();
        JobEnvelope job = new EnvelopeParser(Fixtures.MAPPER).parse(body);

        assertThatThrownBy(() -> renderer().render(job, dir, deadline(), (p, s) -> { }))
                .isInstanceOfSatisfying(RenderFailure.class,
                        f -> assertThat(f.code()).isEqualTo(ErrorCode.SOURCE_EXPIRED));
    }

    /** 신호(설계 15절): 계획 신호에 jobId 를 붙이고, 틱 선택 · 규모 · 길이 차를 남긴다. */
    @Test
    void 신호를_jobId_와_함께_남긴다() {
        String body = order().segment(10, false, false).segment(11, false, true)
                .in(10, 3_000_000).out(11, 1_000_000).lengthMs(2_000).body();

        Run run = render(body);

        String id = run.job().jobId().toString();
        assertThat(messages()).anySatisfy(m -> assertThat(m).startsWith("render.audio_run jobId=" + id + " run=0"));
        assertThat(messages()).anySatisfy(m -> assertThat(m).startsWith("render.frame_pick jobId=" + id
                + " edge=in seq=10 offsetUs=3000000 origin=FILE_HEAD pts=270000"));
        assertThat(messages()).anySatisfy(m -> assertThat(m)
                .startsWith("render.frame_pick jobId=" + id + " edge=out seq=11 offsetUs=1000000"));
        assertThat(messages()).anySatisfy(m -> assertThat(m)
                .startsWith("render.graph_size jobId=" + id + " videoInputs=2 audioRuns=1 chains=2 outputs=1 ")
                .containsPattern("midStageMs=\\d+ finalStageMs=\\d+ midBytes=[1-9]\\d*$"));
        assertThat(messages()).contains("render.length_drift jobId=" + id + " plannedMs=2000 actualMs=2000");
    }

    // ---------------------------------------------------------------- 실행

    record Run(JobEnvelope job, ClipRenderer.Rendered rendered, RenderTimeline timeline,
               Map<Long, SegmentTiming> timings) {

        Path video() {
            return rendered.files().getFirst().file();
        }

        List<Long> plannedResultTicks() {
            List<Long> ticks = new ArrayList<>();
            for (VideoCut c : timeline.videoCuts()) {
                for (long p : timings.get(c.seq()).videoPtsSorted()) {
                    if (p >= c.startPts() && p < c.endPts()) {
                        ticks.add(p + c.deltaTicks());
                    }
                }
            }
            return ticks;
        }
    }

    private PlanOrder order() {
        return PlanOrder.of(UUID.randomUUID());
    }

    private Run render(String body) {
        return render(body, deadline());
    }

    private Run render(String body, Instant deadline) {
        ObjectNode envelope = (ObjectNode) Fixtures.MAPPER.readTree(body);
        ((ObjectNode) envelope.get("recipe")).putNull("subtitles");
        JobEnvelope job = new EnvelopeParser(Fixtures.MAPPER).parse(Fixtures.MAPPER.writeValueAsString(envelope));
        ClipRenderer.Rendered rendered = renderer().render(job, dir, deadline, (p, s) -> { });
        // 판정 기대값은 같은 판독 · 같은 계획에서 낸다(계획이 맞는지는 RenderTimeline 단위 시험 몫 — 여기는 그래프가 계획대로인가)
        MediaProbe probe = new MediaProbe(new RecordingRunner(), Fixtures.MAPPER, "ffprobe");
        Map<Long, SegmentTiming> timings = new LinkedHashMap<>();
        for (var s : job.sources()) {
            Path file = dir.resolve(JoinedRenderer.sourceFile(s.seq()));
            if (Files.exists(file)) {
                timings.put(s.seq(), probe.timing(file, dir, deadline()));
            }
        }
        if (messages().stream().anyMatch(m -> m.startsWith("render.prime_skipped"))) {
            job.sources().stream().filter(s -> s.ref().ownerSeq() != null).forEach(s -> timings.remove(s.seq()));
        }
        List<Integer> trackIds = job.recipe().tracks().stream().map(AudioTrack::trackId).toList();
        RenderTimeline timeline = RenderTimeline.of(job.plan(), job.sources(), timings, trackIds);
        return new Run(job, rendered, timeline, timings);
    }

    private ClipRenderer renderer() {
        MediaProbe probe = new MediaProbe(runner, Fixtures.MAPPER, "ffprobe");
        return new ClipRenderer(store, probe, runner, Fixtures.MAPPER, "ffmpeg", null);
    }

    private static Instant deadline() {
        return Instant.now().plus(Duration.ofMinutes(3));
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    // ---------------------------------------------------------------- 판정

    /** 바로 쓴 합성 조각의 유효성(설계 13.5 보정식 — 이 꼴은 보정량 0 이어야 한다): 모든 τ_k = 0 · 소리 패킷 간격 1024. */
    private static void assertDirectFixture(Run run) {
        for (long seq : run.job().plan().mediaSeqs()) {
            assertThat(OWNER.get(seq).origin()).isEqualTo(DirectSegments.Origin.DIRECT);
            SegmentTiming t = run.timings().get(seq);
            assertThat(t.videoLengthTicks() - t.videoPacketTicks()).as("조각 %d τ", seq).isZero();
            String pts = Ffmpeg.probe("-v", "error", "-select_streams", "a:0", "-show_entries", "packet=pts",
                    "-of", "csv=p=0", FILES.get(seq).toString());
            List<Long> p = pts.lines().filter(l -> !l.isBlank()).map(l -> Long.parseLong(l.split(",")[0].strip()))
                    .toList();
            for (int i = 1; i < p.size(); i++) {
                assertThat(p.get(i) - p.get(i - 1)).as("조각 %d 소리 패킷 간격", seq).isEqualTo(1024);
            }
        }
    }

    /** 결과 영상 time_base = 1/90000 · 프레임 시각 = 계획 틱(0틱 차) · 첫 프레임 0 · 동영상 시간 단위 90000(P7 · P21 · P32). */
    private static void assertVideoOnPlan(Run run) {
        String tb = Ffmpeg.probe("-v", "error", "-select_streams", "v:0", "-show_entries", "stream=time_base",
                "-of", "csv=p=0", run.video().toString()).strip();
        assertThat(tb).as("결과 영상 time_base").isEqualTo("1/90000");
        String frames = Ffmpeg.probe("-v", "error", "-select_streams", "v:0", "-show_entries", "frame=pts",
                "-of", "csv=p=0", run.video().toString());
        List<Long> got = frames.lines().filter(l -> !l.isBlank()).map(l -> Long.parseLong(l.split(",")[0].strip()))
                .sorted().toList();
        assertThat(got).as("결과 프레임 시각(틱)").containsExactlyElementsOf(run.plannedResultTicks());
        assertThat(got.getFirst()).isZero();
        assertThat(DirectSegments.movieTimescale(run.video())).isEqualTo(90_000);
    }

    /**
     * 묶음마다 결과 PCM 이 그 녹화 연속 디코드 기준과 같은 내용이고, 그 자리가 영상 계획(묶음 Δ)이 낸 자리와 0.1ms 안이다. 자리는
     * 기대 자리 ±48표본 안에서 차가 가장 작은 어긋남으로 찾는다.
     */
    private void assertAudioOnPlan(Run run) {
        Map<Integer, float[]> out = graphPcm(run);
        for (AudioRun r : run.timeline().audioRuns()) {
            Recording rec = OWNER.get(r.seqs().getFirst());
            long delta = deltaOf(run, r);
            for (var e : out.entrySet()) {
                float[] ref = reference(rec, e.getKey());
                // 결과 표본 n ↔ 기준 표본 n − Δ·48000/90000(영상 계획에서 · 반올림 없음)
                double expectedLag = -delta * (double) OUT_RATE / DirectSegments.TIMESCALE;
                long from = r.resultStartSample() + 1_024;
                long to = Math.min(r.resultEndSample() - 1_024, from + 24_000);
                int lag = bestLag(e.getValue(), ref, from, to, (int) Math.round(expectedLag));
                // 원시 오차(ms · 정확값) = (lag − 기대) ÷ 48 = (15·lag + 8·Δ) ÷ 720 — 판정은 생성 경로 칸으로 보정 여부를 가른다
                BigDecimal rawMs = BigDecimal.valueOf(15L * lag + 8L * delta)
                        .divide(BigDecimal.valueOf(720), MathContext.DECIMAL64);
                List<Judged> judged = SyncCorrection.judge(rec.origin(), flow(run),
                        List.of(new SyncCorrection.Checkpoint(r.seqs().getFirst(), rawMs)));
                assertThat(SyncCorrection.verdict(judged)).as("묶음 %d 트랙 %d 소리 싱크(원시 %sms)", r.index(),
                        e.getKey(), rawMs).isEqualTo(SyncCorrection.Verdict.PASS);
                assertThat(maxDiff(e.getValue(), ref, from, r.resultEndSample() - 1_024, lag))
                        .as("묶음 %d 트랙 %d PCM", r.index(), e.getKey()).isLessThanOrEqualTo(1e-4);
            }
        }
    }

    /** 결과 조각 판독을 오라클 조각표로(바로 쓴 자료라 보정량은 쓰지 않지만 판정 경로는 같다). */
    private static List<SyncCorrection.Segment> flow(Run run) {
        List<SyncCorrection.Segment> flow = new ArrayList<>();
        for (AudioRun r : run.timeline().audioRuns()) {
            for (long seq : r.seqs()) {
                SegmentTiming t = run.timings().get(seq);
                flow.add(new SyncCorrection.Segment(seq, t.frameCount(), t.videoLengthTicks(), t.videoPacketTicks(),
                        t.frameIntervalTicks(), seq == r.seqs().getFirst(), false, 0));
            }
        }
        return flow;
    }

    /**
     * 구간 이음 증거(최종 출력 계약의 증거가 아니다 — 그것은 {@link #assertFinalAudio}): 최종 단계 명령에서 평준화와 그 뒤 끝 정리
     * 꼬리를 통째로 뺀 채 돌린 mp4 의 소리 패킷이 0 부터 빈틈 없이 1024표본씩 이어지고 끝이 결과 끝 표본과 같다. PCM 오라클은 표본
     * 줄만 보므로, 표본은 맞는데 시각만 어긋난 결함(구간이 0 에서 시작하지 않아 이음 뒤 시각이 밀림 — 설계 16.2 P25)은 여기서 잡는다.
     * 꼬리를 빼는 까닭: 끝 정리의 표본 수 시각 재설정({@code asetpts=N/SR/TB})이 이음 시각 결함을 가린다.
     */
    private void assertJoinEvidence(Run run) {
        List<String> cmd = new ArrayList<>(finalCommand());
        int at = cmd.indexOf("-filter_complex");
        String graph = cmd.get(at + 1);
        int tail = graph.lastIndexOf(",loudnorm=");
        assertThat(tail).as("평준화 꼬리 자리").isPositive();
        cmd.set(at + 1, graph.substring(0, tail) + "[aout]");
        cmd.set(cmd.size() - 1, "timeline.mp4");
        runner.proceed(cmd, dir, deadline());
        assertAudioPackets(dir.resolve("timeline.mp4"), run.timeline().resultEndSamples());
    }

    /**
     * 실제로 나간 최종 mp4(평준화 · 끝 정리 꼬리 포함)의 소리: 48kHz · 첫 패킷 −1024(인코더 앞 프레임) · 패킷 1024 균일 · 끝 = 결과
     * 끝 표본 · 소리 끝과 영상 끝이 한 패킷 안 · 싱크가 판정선(설계 13.5 — 바로 쓴 합성 소리 0.1ms) 안이다. 싱크는 최종 소리를
     * 디코드해 고른 트랙 연속 디코드 기준을 이득대로 섞은 것과 맞댄 정규화 상관의 최대 자리로 잰다(평준화 이득은 상관이 지운다).
     */
    private void assertFinalAudio(Run run) {
        Path file = run.video();
        long audioEnd = assertAudioPackets(file, run.timeline().resultEndSamples());
        long videoEndTicks = packets(file, "v:0").stream().mapToLong(p -> p[0] + p[1]).max().orElseThrow();
        // 소리 끝 − 영상 끝을 같은 단위(표본 × 90000 = 틱 × 48000)로
        long gap = Math.abs(audioEnd * DirectSegments.TIMESCALE - videoEndTicks * OUT_RATE);
        assertThat(gap).as("소리 끝 − 영상 끝(표본 %d · 틱 %d)", audioEnd, videoEndTicks)
                .isLessThanOrEqualTo(AAC_FRAME * DirectSegments.TIMESCALE);

        Path raw = dir.resolve("final.raw");
        runner.proceed(List.of("ffmpeg", "-hide_banner", "-nostdin", "-y", "-i", file.toString(), "-map", "0:a:0",
                "-c:a", "pcm_f32le", "-f", "f32le", raw.toString()), dir, deadline());
        float[] out = left(DirectSegments.pcm(raw));
        for (AudioRun r : run.timeline().audioRuns()) {
            Recording rec = OWNER.get(r.seqs().getFirst());
            long delta = deltaOf(run, r);
            double[] mix = mixedReference(run, rec);
            long from = r.resultStartSample() + AAC_FRAME;
            long to = Math.min(r.resultEndSample() - AAC_FRAME, from + 24_000);
            int expected = (int) Math.round(-delta * (double) OUT_RATE / DirectSegments.TIMESCALE);
            int lag = bestLagByCorrelation(out, mix, from, to, expected);
            BigDecimal rawMs = BigDecimal.valueOf(15L * lag + 8L * delta)
                    .divide(BigDecimal.valueOf(720), MathContext.DECIMAL64);
            List<Judged> judged = SyncCorrection.judge(rec.origin(), flow(run),
                    List.of(new SyncCorrection.Checkpoint(r.seqs().getFirst(), rawMs)));
            assertThat(SyncCorrection.verdict(judged)).as("묶음 %d 최종 소리 싱크(원시 %sms)", r.index(), rawMs)
                    .isEqualTo(SyncCorrection.Verdict.PASS);
        }
    }

    /**
     * mp4 소리 패킷: 48kHz · 첫 패킷 −1024 · 마지막 앞까지 길이 1024 · 시각 차 1024 · 끝 = {@code endSample}.
     *
     * @return 소리 끝(48kHz 표본)
     */
    private static long assertAudioPackets(Path file, long endSample) {
        assertThat(Ffmpeg.probe("-v", "error", "-select_streams", "a:0", "-show_entries", "stream=sample_rate",
                "-of", "csv=p=0", file.toString()).strip()).as("소리 표본율").isEqualTo("48000");
        List<long[]> packets = packets(file, "a:0");
        assertThat(packets.getFirst()[0]).as("첫 소리 패킷 시각(인코더 앞 프레임)").isEqualTo(-AAC_FRAME);
        for (int i = 1; i < packets.size(); i++) {
            assertThat(packets.get(i - 1)[1]).as("소리 패킷 %d 길이", i - 1).isEqualTo(AAC_FRAME);
            assertThat(packets.get(i)[0] - packets.get(i - 1)[0]).as("소리 패킷 %d 시각 차", i).isEqualTo(AAC_FRAME);
        }
        long end = packets.getLast()[0] + packets.getLast()[1];
        assertThat(end).as("소리 끝(48kHz 표본)").isEqualTo(endSample);
        return end;
    }

    /** 스트림 하나의 패킷 {pts, duration}(그 스트림 time_base). */
    private static List<long[]> packets(Path file, String stream) {
        return Ffmpeg.probe("-v", "error", "-select_streams", stream, "-show_entries", "packet=pts,duration",
                        "-of", "csv=p=0", file.toString()).lines().filter(l -> !l.isBlank())
                .map(l -> l.split(","))
                .map(f -> new long[] {Long.parseLong(f[0].strip()), Long.parseLong(f[1].strip())}).toList();
    }

    /** 고른 트랙의 연속 디코드 기준(왼쪽 채널)을 레시피 이득대로 더한 것 — 최종 소리의 섞기 앞 모양. */
    private double[] mixedReference(Run run, Recording rec) {
        double[] mix = null;
        for (AudioTrack t : run.job().recipe().tracks()) {
            float[] ref = reference(rec, t.trackId());
            if (mix == null) {
                mix = new double[ref.length];
            }
            for (int i = 0; i < Math.min(mix.length, ref.length); i++) {
                mix[i] += t.gain() * ref[i];
            }
        }
        return mix;
    }

    /** 결과 표본 n ↔ 기준 표본 n + lag 의 정규화 상관이 가장 큰 lag(기대 자리 ±48표본 안). */
    private static int bestLagByCorrelation(float[] out, double[] ref, long from, long to, int around) {
        int best = around;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int lag = around - 48; lag <= around + 48; lag++) {
            if (from + lag < 0 || to + lag > ref.length || to > out.length) {
                continue;
            }
            double dot = 0;
            double energy = 0;
            for (long n = from; n < to; n++) {
                double r = ref[(int) (n + lag)];
                dot += out[(int) n] * r;
                energy += r * r;
            }
            double score = dot / Math.sqrt(energy);
            if (score > bestScore) {
                bestScore = score;
                best = lag;
            }
        }
        assertThat(bestScore).as("상관 자리를 찾음").isGreaterThan(0);
        return best;
    }

    private List<String> finalCommand() {
        return runner.commands().stream().filter(RecordingRunner::isFinalStage).reduce((a, b) -> b).orElseThrow();
    }

    private String finalGraph() {
        List<String> last = finalCommand();
        return last.get(last.indexOf("-filter_complex") + 1);
    }

    /** 첫 묶음 머리 [0, 640) 표본의 연속 디코드 기준과의 최대 차. */
    private double headDiff(Run run) {
        AudioRun r = run.timeline().audioRuns().getFirst();
        Map<Integer, float[]> out = graphPcm(run);
        int track = out.keySet().iterator().next();
        float[] ref = reference(OWNER.get(r.seqs().getFirst()), track);
        long lag = Math.round(-deltaOf(run, r) * (double) OUT_RATE / DirectSegments.TIMESCALE);
        return maxDiff(out.get(track), ref, 0, 640, lag);
    }

    private static long deltaOf(Run run, AudioRun r) {
        return run.timeline().videoCuts().stream().filter(c -> r.seqs().contains(c.seq())).findFirst()
                .orElseThrow().deltaTicks();
    }

    /** 최종 단계 명령의 출력 꼴: 시간 창 없음 · 원본 조각을 영상으로 다시 읽지 않음 · enc1 · 인코더 시간 단위(P32 · P35). */
    private void assertCommandShape(Run run) {
        List<List<String>> commands = runner.commands();
        assertThat(commands).filteredOn(RecordingRunner::isCutPiece).hasSize(run.timeline().videoCuts().size());
        List<String> last = commands.stream().filter(RecordingRunner::isFinalStage).findFirst().orElseThrow();
        int outputSide = last.lastIndexOf("-filter_complex");
        List<String> tail = last.subList(outputSide, last.size());
        assertThat(String.join(" ", tail)).contains("-threads 1 -enc_time_base:v 1/90000")
                .contains("-x264-params threads=1:lookahead_threads=1");
        assertThat(commands).allSatisfy(c -> assertThat(c).doesNotContain("-ss", "-t", RenderCommands.SOURCE));
        assertThat(last.get(last.indexOf("-i") + 1)).isEqualTo("mid_list.ffconcat");
    }

    /** 최종 단계 그래프를 그대로 두고 섞기 앞 트랙별 묶음 이음 {@code [acatN]} 을 PCM 으로 뽑는다(AAC 재인코딩 전). */
    private Map<Integer, float[]> graphPcm(Run run) {
        List<String> last = runner.commands().stream().filter(RecordingRunner::isFinalStage).reduce((a, b) -> b)
                .orElseThrow();
        int at = last.indexOf("-filter_complex");
        String graph = last.get(at + 1);
        List<Integer> tracks = run.job().recipe().tracks().stream().map(AudioTrack::trackId).toList();
        String cut = graph.substring(0, graph.indexOf("[acat" + tracks.getFirst() + "]volume="));
        StringBuilder g = new StringBuilder(cut);
        List<String> cmd = new ArrayList<>(last.subList(0, at));
        for (int n : tracks) {
            g.append("[acat").append(n).append("]anull[pcm").append(n).append("];");
        }
        cmd.addAll(List.of("-filter_complex", g.substring(0, g.length() - 1), "-map", "[vout]", "-f", "null", "-"));
        for (int n : tracks) {
            cmd.addAll(List.of("-map", "[pcm" + n + "]", "-c:a", "pcm_f32le", "-f", "f32le", "pcm_" + n + ".raw"));
        }
        runner.proceed(cmd, dir, deadline());
        Map<Integer, float[]> out = new LinkedHashMap<>();
        for (int n : tracks) {
            out.put(n, left(DirectSegments.pcm(dir.resolve("pcm_" + n + ".raw"))));
        }
        return out;
    }

    /** 녹화 연속 fMP4 의 트랙 N 을 한 디코더로 끝까지 디코드한 기준(48kHz · 왼쪽 채널). */
    private float[] reference(Recording rec, int track) {
        Path raw = dir.resolve("ref_" + rec.name() + "_" + track + ".raw");
        if (!Files.exists(raw)) {
            runner.proceed(List.of("ffmpeg", "-hide_banner", "-nostdin", "-y", "-i", rec.continuous().toString(),
                    "-map", "0:a:" + track, "-af", "aresample=48000,aformat=sample_rates=48000:channel_layouts=stereo",
                    "-c:a", "pcm_f32le", "-f", "f32le", raw.toString()), dir, deadline());
        }
        return left(DirectSegments.pcm(raw));
    }

    private static float[] left(float[] stereo) {
        float[] out = new float[stereo.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = stereo[2 * i];
        }
        return out;
    }

    private static int bestLag(float[] out, float[] ref, long from, long to, int around) {
        int best = around;
        double bestErr = Double.MAX_VALUE;
        for (int lag = around - 48; lag <= around + 48; lag++) {
            double err = 0;
            for (long n = from; n < to; n++) {
                long k = n + lag;
                if (k < 0 || k >= ref.length || n >= out.length) {
                    err = Double.MAX_VALUE;
                    break;
                }
                double d = out[(int) n] - ref[(int) k];
                err += d * d;
            }
            if (err < bestErr) {
                bestErr = err;
                best = lag;
            }
        }
        return best;
    }

    private static double maxDiff(float[] out, float[] ref, long from, long to, long lag) {
        double worst = 0;
        for (long n = from; n < to && n < out.length; n++) {
            long k = n + lag;
            if (k < 0 || k >= ref.length) {
                return Double.MAX_VALUE;
            }
            worst = Math.max(worst, Math.abs(out[(int) n] - ref[(int) k]));
        }
        return worst;
    }
}
