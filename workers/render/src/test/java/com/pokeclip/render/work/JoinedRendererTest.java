package com.pokeclip.render.work;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pokeclip.render.job.EnvelopeParser;
import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.JobEnvelope;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.job.SourceSegment;
import com.pokeclip.render.media.MediaProbe;
import com.pokeclip.render.media.ProcessRunner;
import com.pokeclip.render.media.RenderCommands;
import com.pokeclip.render.media.SegmentTiming;
import com.pokeclip.render.recipe.Recipe.AudioTrack;
import com.pokeclip.render.report.ClipReporter;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
    /** 최종 소리 상관 싱크 창 하나의 길이(표본 · 0.5초). */
    private static final long SYNC_WINDOW = 24_000;
    /** 녹화기 실물 조각 seg_0 · seg_1 · seg_2(리소스 segments/ · 한 녹화로 이어짐)의 첫 seq — 50 · 51 · 52. */
    private static final long RECORDER_FIRST_SEQ = 50;

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
    static void segments() throws IOException {
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
        // 바로 쓴 조각은 영상 첫 DTS 가 파일 머리라 m = 0 이다. m ≠ 0 인 조각은 녹화기 실물에서 가져온다(OWNER 밖)
        for (int i = 0; i < 3; i++) {
            Path file = fixtures.resolve("recorder_" + i + ".m4s");
            try (InputStream in = JoinedRendererTest.class.getResourceAsStream("/segments/seg_" + i + ".m4s")) {
                Files.copy(in, file);
            }
            FILES.put(RECORDER_FIRST_SEQ + i, file);
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

    /** 결과 끝이 원래 out 조각에 남으면 확보 조각을 마지막 묶음 끝에 싣고, 그 조각을 못 받으면 실패다(설계 7.4 소리 묶음 8). */
    @Test
    void 결과_끝에_쓰일_확보_조각은_싣고_못_받으면_실패다() {
        String body = order().segment(10, false, false).segment(11, false, true).capture(12, false, true)
                .in(10, 3_000_000).out(11, 1_000_000).body();

        Run run = render(body);

        assertThat(run.timeline().audioRuns().getLast().captureSeq()).isEqualTo(12L);
        assertAudioOnPlan(run);

        doAnswer(inv -> {
            throw RenderFailure.permanent(ErrorCode.SOURCE_EXPIRED, "영상 조각이 저장소에 없다");
        }).when(store).download(anyString(), org.mockito.ArgumentMatchers.endsWith("seg_12.m4s"), any(), any());
        JobEnvelope job = new EnvelopeParser(Fixtures.MAPPER).parse(body);
        assertThatThrownBy(() -> renderer().render(job, newDir(), deadline(), (p, s) -> { }))
                .isInstanceOfSatisfying(RenderFailure.class,
                        f -> assertThat(f.code()).isEqualTo(ErrorCode.SOURCE_EXPIRED));
    }

    /**
     * 끝 소유로 빠질 확보 조각은 받지도 판독하지도 않는다(설계 7.4 소리 묶음 8 · 1). 끝 조각 20 은 끊긴 이웃 뒤이고 끝이 파일
     * 머리라 남는 프레임이 없어 빠진다 — 그러면 그 조각의 같은 녹화 이웃인 확보 조각 21 은 쓰지 않는다. 저장소에 없거나 판독이
     * 시한을 넘길 조각이어도 주문은 그 앞에서 결정되므로 걸리지 않는다.
     */
    @Test
    void 끝_소유로_빠질_확보_조각은_받지_않고_주문을_마친다() {
        String body = order().segment(12, false, false).segment(20, true, true).capture(21, false, true)
                .in(12, 2_000_000).out(20, 0).body();

        Run run = render(body);

        String id = run.job().jobId().toString();
        assertThat(run.timeline().placements()).extracting(RenderTimeline.Placement::seq).containsExactly(12L);
        assertThat(messages()).contains("render.out_retreated jobId=" + id + " fromSeq=20 toSeq=12",
                "render.capture_dropped jobId=" + id + " seq=21 reason=out_retreated");
        verify(store, never()).download(anyString(), endsWith("seg_21.m4s"), any(), any());
        assertThat(runner.commands()).noneMatch(c -> String.join(" ", c).contains("seg_21.m4s"));
        assertVideoOnPlan(run);
    }

    /** 결과 끝에 쓰일 확보 조각의 판독이 시한을 넘기면 그대로 주문 실패다(삼키지 않는다). */
    @Test
    void 결과_끝에_쓰일_확보_조각의_판독_시한_초과는_주문_실패다() {
        runner.intercept((command, workDir, deadline, r) -> {
            if (command.getFirst().equals("ffprobe") && command.getLast().endsWith("seg_12.m4s")) {
                throw new ProcessRunner.Timeout("ffprobe");
            }
            return null;
        });
        String body = order().segment(10, false, false).segment(11, false, true).capture(12, false, true)
                .in(10, 3_000_000).out(11, 1_000_000).body();
        JobEnvelope job = new EnvelopeParser(Fixtures.MAPPER).parse(body);

        assertThatThrownBy(() -> renderer().render(job, dir, deadline(), (p, s) -> { }))
                .isInstanceOf(ProcessRunner.Timeout.class);
        assertThat(runner.commands()).noneMatch(RecordingRunner::isCutPiece);
    }

    /** 결과 끝에 쓰일 확보 조각을 판독하지 못하면(받은 파일이 영상이 아님) 주문 실패다. */
    @Test
    void 결과_끝에_쓰일_확보_조각을_판독하지_못하면_주문_실패다() {
        doAnswer(inv -> {
            Files.writeString(inv.<Path>getArgument(2), "not a segment");
            return null;
        }).when(store).download(anyString(), endsWith("seg_12.m4s"), any(), any());
        String body = order().segment(10, false, false).segment(11, false, true).capture(12, false, true)
                .in(10, 3_000_000).out(11, 1_000_000).body();
        JobEnvelope job = new EnvelopeParser(Fixtures.MAPPER).parse(body);

        assertThatThrownBy(() -> renderer().render(job, dir, deadline(), (p, s) -> { }))
                .isInstanceOfAny(ProcessRunner.Failed.class, RenderFailure.class);
        assertThat(runner.commands()).noneMatch(RecordingRunner::isCutPiece);
    }

    /** 조각 파일 이름은 seq 로 정해지므로, 계획 검사를 빠져나온 seq 겹침은 받기 전에 거절한다(같은 파일을 덮어쓰지 않게). */
    @Test
    void 조각_seq_가_겹치면_받기_전에_거절한다() {
        String body = order().segment(10, false, false).segment(11, false, true).in(10, 0).out(11, 0).body();
        JobEnvelope parsed = new EnvelopeParser(Fixtures.MAPPER).parse(body);
        List<SourceSegment> doubled = new ArrayList<>(parsed.sources());
        doubled.add(parsed.sources().getLast());
        JobEnvelope job = new JobEnvelope(parsed.jobId(), parsed.clipId(), parsed.streamId(), parsed.recipeVersion(),
                parsed.outputBucket(), parsed.outputKeyPrefix(), doubled, parsed.recipe(), parsed.plan());

        assertThatThrownBy(() -> renderer().render(job, dir, deadline(), (p, s) -> { }))
                .isInstanceOfSatisfying(RenderFailure.class,
                        f -> assertThat(f.code()).isEqualTo(ErrorCode.ENVELOPE_VALIDATION));
        verify(store, never()).download(anyString(), anyString(), any(), any());
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
                .containsPattern("elapsedMs=\\d+ maxRssMb=[1-9]\\d* midStageMs=\\d+ finalStageMs=\\d+ "
                        + "midBytes=[1-9]\\d*$"));
        assertThat(messages()).contains("render.length_drift jobId=" + id + " plannedMs=2000 actualMs=2000");
    }

    /**
     * 신호 수준(설계 15절): 결과 길이 상한과 신호 산식 실패로 생략한 줄은 WARN · 앞 · 끝 무음은 DEBUG · 나머지 계획 신호는
     * INFO.
     */
    @Test
    void 계획_신호는_설계_15절_수준으로_남긴다() {
        Logger logger = (Logger) LoggerFactory.getLogger(JoinedRenderer.class);
        Level before = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            UUID jobId = UUID.randomUUID();
            JoinedRenderer.logNote(jobId,
                    new RenderTimeline.Note("render.audio_pad", "run=0 track=1 frontMs=3 backMs=0"));
            JoinedRenderer.logNote(jobId, new RenderTimeline.Note("render.length_capped", "actualMs=180000"));
            JoinedRenderer.logNote(jobId, new RenderTimeline.Note("render.audio_run", "run=0"));
            JoinedRenderer.logNote(jobId, new RenderTimeline.Note("render.signal_skipped",
                    "signal=render.audio_pad run=0 track=0 reason=overflow"));
        } finally {
            logger.setLevel(before);
        }

        assertThat(logs.list).extracting(e -> e.getFormattedMessage().split(" ")[0], ILoggingEvent::getLevel)
                .containsExactly(tuple("render.audio_pad", Level.DEBUG), tuple("render.length_capped", Level.WARN),
                        tuple("render.audio_run", Level.INFO), tuple("render.signal_skipped", Level.WARN));
    }

    /**
     * 준비 조각 길이 대조의 정수식이 넘치면(장부 길이가 터무니없음) 그 준비 조각을 쓰지 않는다 — 넘침이 일시 실패(재시도)로 새지
     * 않고, 다른 대조 실패처럼 준비 없이 간다(설계 7.4 소리 묶음 7).
     */
    @Test
    void 준비_조각_길이_대조가_넘치면_쓰지_않는다() {
        SegmentTiming t = new MediaProbe(new RecordingRunner(), Fixtures.MAPPER, "ffprobe")
                .timing(FILES.get(10L), fixtures, deadline());

        assertThat(JoinedRenderer.primeMatches(t, PlanOrder.DURATION_MS, t, List.of(1))).as("정상 길이").isTrue();
        assertThat(JoinedRenderer.primeMatches(t, Long.MAX_VALUE / 1_000, t, List.of(1))).as("넘치는 장부 길이")
                .isFalse();
    }

    /**
     * 성공 보고의 배치표(설계 7.6)는 계획 배치를 칸마다 그대로 옮긴다. 녹화기 실물 조각 셋(50 · 51 · 52)을 이어 쓰면 뒤 두 조각의
     * m 이 0 이 아니라 한 줄 안 다섯 칸(seq · zeroAtUs · fromUs · toUs · headLeadUs)이 모두 다른 값이다 — 칸을 바꿔 넣거나 0 으로
     * 비우면 걸린다.
     *
     * <p>기대값(판독 값에서 손으로 낸다 · 시작 · 끝 오프셋은 파일 머리 기준이라 x′ = 오프셋 − m):
     * <ul>
     *   <li>50: m = 0 · V = 359,999틱(3,999,988.9µs → 3,999,989). 시작 x′ = 1,000,000 이 결과 0 → zeroAtUs = −1,000,000 ·
     *       fromUs = 1,000,000 · toUs = 51 의 zeroAtUs − 50 의 zeroAtUs = 3,999,989.
     *   <li>51: m = 53,333 · V = 360,000틱(4,000,000µs). zeroAtUs = −1,000,000 + 3,999,989 = 2,999,989 · 이어진 조각 몫은
     *       파일 머리부터라 fromUs = −m = −53,333 · toUs = 52 의 zeroAtUs − 51 의 zeroAtUs = 4,000,000.
     *   <li>52: m = 64,000. zeroAtUs = 2,999,989 + 4,000,000 = 6,999,989 · fromUs = −64,000 · 끝 x′ = 1,000,000 − m =
     *       936,000 = toUs.
     *   <li>lengthUs = 52 의 zeroAtUs + toUs = 7,935,989.
     * </ul>
     */
    @Test
    void 성공_보고의_배치표는_계획_배치를_칸마다_옮긴다() {
        String body = order().segment(50, false, false).segment(51, false, true).segment(52, false, true)
                .in(50, 1_000_000).out(52, 1_000_000).body();

        Run run = render(body);

        assertThat(run.timings().get(51L).headLeadUs()).as("픽스처 조각 51 m").isEqualTo(53_333);
        assertThat(run.timings().get(52L).headLeadUs()).as("픽스처 조각 52 m").isEqualTo(64_000);
        assertThat(run.rendered().layout()).isEqualTo(new ClipReporter.ResultLayout(List.of(
                new ClipReporter.PlacementItem(50, -1_000_000, 1_000_000, 3_999_989, 0),
                new ClipReporter.PlacementItem(51, 2_999_989, -53_333, 4_000_000, 53_333),
                new ClipReporter.PlacementItem(52, 6_999_989, -64_000, 936_000, 64_000)), 7_935_989));
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

    /** 같은 시험 안 두 번째 주문의 작업 폴더(첫 주문의 파일과 섞이지 않게). */
    private Path newDir() {
        try {
            return Files.createTempDirectory(dir, "second");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
            long first = r.resultStartSample() + AAC_FRAME;
            long last = r.resultEndSample() - AAC_FRAME;
            int expected = (int) Math.round(-delta * (double) OUT_RATE / DirectSegments.TIMESCALE);
            // 묶음 머리 창과 끝 창 — 머리만 보면 묶음 뒤쪽에서 생긴 시각 뜀(평준화 꼬리 · 흐름 안 채움)을 상관이 놓친다
            long[][] windows = {
                {first, Math.min(last, first + SYNC_WINDOW)}, {Math.max(first, last - SYNC_WINDOW), last}};
            for (long[] w : windows) {
                int lag = bestLagByCorrelation(out, mix, w[0], w[1], expected);
                BigDecimal rawMs = BigDecimal.valueOf(15L * lag + 8L * delta)
                        .divide(BigDecimal.valueOf(720), MathContext.DECIMAL64);
                List<Judged> judged = SyncCorrection.judge(rec.origin(), flow(run),
                        List.of(new SyncCorrection.Checkpoint(r.seqs().getFirst(), rawMs)));
                assertThat(SyncCorrection.verdict(judged)).as("묶음 %d 최종 소리 싱크 창 %d–%d(원시 %sms)", r.index(),
                        w[0], w[1], rawMs).isEqualTo(SyncCorrection.Verdict.PASS);
            }
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
