package com.pokeclip.render.work;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pokeclip.render.job.EnvelopeParser;
import com.pokeclip.render.job.JobEnvelope;
import com.pokeclip.render.media.MediaProbe;
import com.pokeclip.render.media.ProcessRunner;
import com.pokeclip.render.media.RenderCommands;
import com.pokeclip.render.media.SegmentTiming;
import com.pokeclip.render.media.SegmentTiming.ColorTags;
import com.pokeclip.render.storage.S3Store;
import com.pokeclip.render.support.DirectSegments;
import com.pokeclip.render.support.DirectSegments.Recording;
import com.pokeclip.render.support.Ffmpeg;
import com.pokeclip.render.support.Fixtures;
import com.pokeclip.render.support.PlanOrder;
import com.pokeclip.render.support.RecordingRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 새 꼴 결과의 색 표지(PDT 설계 r7 13.5 「색 표지」 · 16.2 P34). FFV1 · NUT 중간 파일을 지나며 빠지는 색 칸 넷을 최종 단계가 원본
 * 판독에서 되살리는지, 이 기계의 실제 ffmpeg 로 A′ 전 경로를 돌려 결과 mp4 의 {@code ffprobe} 색 칸과 원본을 맞댄다. 원본은
 * 표지가 다른 둘(709 제한 범위 · 범위 전체 2020 HLG)과 표지 없는 하나다. S3 만 가짜다.
 */
class JoinedColorTest {

    private static final String COLOR_ENTRIES = "stream=color_range,color_space,color_transfer,color_primaries";
    private static final String TV_709 = "range=tv:colorspace=bt709:color_trc=bt709:color_primaries=bt709";
    private static final String PC_2020 = "range=pc:colorspace=bt2020nc:color_trc=arib-std-b67:color_primaries=bt2020";
    /** 709 제한 범위 원본에서 최종 단계 사슬 머리에 붙는 칸(차례는 {@code RenderCommands.withColor}). */
    private static final String TV_709_PARAMS = "range=tv:color_primaries=bt709:color_trc=bt709:colorspace=bt709";
    /** 미지정 칸을 ffprobe 기본 출력은 unknown 으로 낸다. */
    private static final String UNKNOWN = "unknown|unknown|unknown|unknown";
    private static final String SOURCE = "testsrc2=size=160x90:rate=30";
    private static final UUID JOB = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    /** 레시피가 트랙 1 · 3 을 섞는다. */
    private static final int TRACKS = 4;

    @TempDir
    static Path fixtures;
    private static final Map<Long, Path> FILES = new HashMap<>();

    @TempDir
    Path dir;

    private final RecordingRunner runner = new RecordingRunner();
    private final S3Store store = mock(S3Store.class);
    private ListAppender<ILoggingEvent> logs;

    @BeforeAll
    static void segments() {
        assumeThat(Ffmpeg.available()).as("ffmpeg 필요").isTrue();
        // 조각 30 · 31 = 709 제한 범위, 40 · 41 = 범위 전체 2020 HLG, 50 · 51 = 표지 없음(8초 · 조각 둘씩)
        put(30, DirectSegments.write(fixtures, "tv", SOURCE + ",setparams=" + TV_709, 8, -1, 11, TRACKS));
        put(40, DirectSegments.write(fixtures, "pc", SOURCE + ",setparams=" + PC_2020, 8, -1, 21, TRACKS));
        put(50, DirectSegments.write(fixtures, "none", SOURCE, 8, -1, 31, TRACKS));
    }

    private static void put(long firstSeq, Recording rec) {
        for (int i = 0; i < rec.pieces().size(); i++) {
            FILES.put(firstSeq + i, rec.pieces().get(i));
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

    @Test
    void 제한_범위_709_원본의_결과는_색_칸_넷이_원본과_같다() {
        Path video = render(twoPieces(30), false);

        assertThat(colorOf(FILES.get(30L))).isEqualTo("tv|bt709|bt709|bt709");
        assertThat(colorOf(video)).isEqualTo(colorOf(FILES.get(30L)));
    }

    @Test
    void 범위_전체_원본의_결과도_색_칸_넷이_원본과_같다() {
        Path video = render(twoPieces(40), false);

        assertThat(colorOf(FILES.get(40L))).isEqualTo("pc|bt2020nc|arib-std-b67|bt2020");
        assertThat(colorOf(video)).isEqualTo(colorOf(FILES.get(40L)));
    }

    @Test
    void 표지_없는_원본의_결과에는_색_칸이_없다() {
        Path video = render(twoPieces(50), false);

        assertThat(colorOf(FILES.get(50L))).isEqualTo(UNKNOWN);
        assertThat(colorOf(video)).isEqualTo(UNKNOWN);
        assertThat(finalGraph()).doesNotContain("setparams");
    }

    /** 꾸밈 그림 출력(분할 · 흐림 · 겹치기 · 그림 입력)도 사슬 머리에서 붙인 표지가 출력까지 간다. */
    @Test
    void 꾸밈_그림_출력도_색_칸_넷이_원본과_같다() {
        Path video = render(twoPieces(30), true);

        assertThat(colorOf(video)).isEqualTo(colorOf(FILES.get(30L)));
        assertThat(finalGraph()).startsWith("[0:v:0]setparams=range=tv:");
    }

    /**
     * 결과 조각 사이 색 표지가 다르면(재접속 사이에 송출 설정이 바뀐 꼴) 첫 결과 조각 값으로 되살리고 경고를 남긴다. 출력 표지는
     * 하나뿐이다 — 옛 꼴도 인코더가 첫 프레임 값으로 열린다. 화면 크기도 첫 결과 조각에서 읽는다.
     */
    @Test
    void 결과_조각_사이_색_표지가_다르면_첫_조각_값으로_되살리고_경고한다() {
        String body = order().segment(31, false, false).segment(40, true, true)
                .in(31, 1_000_000).out(40, 2_000_000).body();

        Path video = render(body, false);

        assertThat(colorOf(video)).isEqualTo(colorOf(FILES.get(31L)));
        assertThat(logs.list).filteredOn(e -> e.getFormattedMessage().startsWith("render.color_mixed"))
                .singleElement().satisfies(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.WARN);
                    assertThat(e.getFormattedMessage()).endsWith(" seq=40 color=pc/bt2020nc/arib-std-b67/bt2020 "
                            + "used=tv/bt709/bt709/bt709 usedSeq=31");
                });
    }

    @Test
    void 결과_조각의_색_표지가_같으면_경고하지_않는다() {
        render(twoPieces(30), false);

        assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().startsWith("render.color_mixed"));
    }

    /**
     * 계획 첫 조각(31 · 709)이 결과에서 빠지고({@code render.in_advanced}) 결과가 다음 조각(40 · 범위 전체 2020)부터 시작하면,
     * 결과 영상에는 40 의 프레임만 있다. 되살리는 표지와 섞임 비교는 결과에 실제로 든 조각 기준이라 40 의 표지이고, 빠진 31 과는
     * 비교하지 않는다.
     *
     * <p>빠지는 꼴은 {@code RenderTimelineTest} 「첫 조각에 남는 프레임이 없으면」 과 같다 — 31 판독의 영상 길이를 한 프레임
     * 줄여 끝 프레임이 영상 길이 끝에 놓이게 하면, 끊긴 이웃 40 이 그 자리 표시를 가져간다(표시 소유). 시작은 31 끝 프레임
     * 뒤(파일 머리 4.5초 — 조각 길이 4초 + 허용 1초 안)라 시작 프레임이 그 끝 프레임이고, 그 하나가 빠지면 31 은 빈다.
     */
    @Test
    void 첫_계획_조각이_결과에서_빠지면_남은_첫_결과_조각의_색으로_되살린다() {
        String body = order().segment(31, false, false).segment(40, true, true)
                .in(31, 4_500_000).out(40, 2_000_000).body();
        JobEnvelope job = new EnvelopeParser(Fixtures.MAPPER).parse(withoutSubtitles(body));

        Path video = renderJob(job, shortened(31));

        assertThat(messages()).anyMatch(m -> m.startsWith("render.in_advanced") && m.contains("fromSeq=31"));
        assertThat(colorOf(video)).isEqualTo(colorOf(FILES.get(40L)));
        assertThat(messages()).noneMatch(m -> m.startsWith("render.color_mixed"));
    }

    /**
     * 색 되살림과 자리 있는 번인(ASS)을 함께 거친 결과. 표지는 사슬 머리에서 붙어 번인 필터를 지나 출력까지 간다. libass 가 없는
     * 기계에서는 최종 단계를 돌리지 않고 그래프 순서(머리 {@code setparams} → 뒤에 {@code subtitles})만 본다.
     */
    @Test
    void 색_되살림과_자리_있는_번인을_함께_거친_결과도_표지가_원본과_같다() {
        ObjectNode envelope = subtitled();
        envelope.set("recipe", Fixtures.recipeV2("s1"));

        Path video = renderBurned(envelope);

        assertBurnedAfterColor("subtitles=o1.ass", video);
        assertThat(dir.resolve("o1.ass")).exists();
    }

    /** 색 되살림과 자리 없는 번인(SRT · 예전 모양)을 함께 거친 결과. libass 가 없으면 그래프 순서만 본다. */
    @Test
    void 색_되살림과_자리_없는_번인을_함께_거친_결과도_표지가_원본과_같다() {
        Path video = renderBurned(subtitled());

        assertBurnedAfterColor("subtitles=" + RenderCommands.BURN_SRT + ":force_style='", video);
        assertThat(dir.resolve(RenderCommands.BURN_SRT)).exists();
    }

    /**
     * 섞임 신호에 싣는 표지 글자는 받는 값 목록을 지난 것만이다. 목록 밖 글자(문법 글자 · 줄바꿈이 낀 것 · 예약)는 {@code other},
     * 미지정은 {@code unknown} 이다 — 원본 파일 머리 글자를 로그 줄에 그대로 싣지 않는다.
     */
    @Test
    void 색_섞임_신호는_받는_값_목록_밖_글자를_other_로_싣는다() {
        Map<Long, SegmentTiming> timings = Map.of(
                1L, timing(new ColorTags("tv", "bt709", "bt709", "bt709")),
                2L, timing(new ColorTags("pc\nrender.fake x=1", "reserved", null, "bt709,drawbox")));

        JoinedRenderer.logColorMixed(JOB, List.of(1L, 2L), timings);

        assertThat(messages()).filteredOn(m -> m.startsWith("render.color_mixed")).singleElement()
                .isEqualTo("render.color_mixed jobId=" + JOB + " seq=2 color=other/other/unknown/other "
                        + "used=tv/bt709/bt709/bt709 usedSeq=1");
    }

    /** 판독이 없는 조각은 섞임 비교에서 건너뛴다(앞 단계가 보장하지만 신호 하나로 렌더를 깨지 않는다). */
    @Test
    void 색_섞임_신호는_판독이_없는_조각을_건너뛴다() {
        Map<Long, SegmentTiming> timings = Map.of(1L, timing(new ColorTags("tv", "bt709", "bt709", "bt709")),
                3L, timing(new ColorTags("pc", "bt709", "bt709", "bt709")));

        JoinedRenderer.logColorMixed(JOB, List.of(1L, 2L, 3L), timings);
        JoinedRenderer.logColorMixed(JOB, List.of(9L, 1L), timings);

        assertThat(messages()).filteredOn(m -> m.startsWith("render.color_mixed")).singleElement()
                .isEqualTo("render.color_mixed jobId=" + JOB + " seq=3 color=pc/bt709/bt709/bt709 "
                        + "used=tv/bt709/bt709/bt709 usedSeq=1");
    }

    /**
     * 원본에 값이 있는데 받는 값 목록 밖이라 결과에 싣지 않는 칸이 있으면 DEBUG 한 줄을 남긴다. 값 글자는 싣지 않고 버린 칸의
     * {@code setparams} 옵션 이름(코드 상수)만 싣는다. 미지정 칸은 버린 것이 아니라 신호가 없다.
     */
    @Test
    void 받는_값_목록_밖_색_칸을_버리면_DEBUG_한_줄을_남긴다() {
        ((Logger) LoggerFactory.getLogger(JoinedRenderer.class)).setLevel(Level.DEBUG);
        try {
            JoinedRenderer.logColorDropped(JOB, 7, new ColorTags("tv", "vgamut", null, "reserved"));
            JoinedRenderer.logColorDropped(JOB, 8, new ColorTags("tv", "bt709", null, null));
        } finally {
            ((Logger) LoggerFactory.getLogger(JoinedRenderer.class)).setLevel(null);
        }

        assertThat(logs.list).filteredOn(e -> e.getFormattedMessage().startsWith("render.color_dropped"))
                .singleElement().satisfies(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.DEBUG);
                    assertThat(e.getFormattedMessage()).isEqualTo("render.color_dropped jobId=" + JOB
                            + " seq=7 options=color_primaries,colorspace");
                });
    }

    /**
     * 첫 결과 조각 판독에 받는 값 목록 밖 칸(ffprobe 가 낼 수 있으나 setparams 목록에 없는 이름)이 있으면 그 칸만 빼고 되살리고,
     * DEBUG 한 줄로 버린 칸을 남긴다. 판독기가 30 의 원색 칸만 목록 밖 이름으로 바꿔 낸다.
     */
    @Test
    void 첫_결과_조각의_목록_밖_색_칸은_빼고_되살리고_DEBUG_로_남긴다() {
        JobEnvelope job = new EnvelopeParser(Fixtures.MAPPER).parse(withoutSubtitles(twoPieces(30)));
        ((Logger) LoggerFactory.getLogger(JoinedRenderer.class)).setLevel(Level.DEBUG);
        try {
            renderJob(job, recolored(30, new ColorTags("tv", "bt709", "bt709", "vgamut")));
        } finally {
            ((Logger) LoggerFactory.getLogger(JoinedRenderer.class)).setLevel(null);
        }

        assertThat(finalGraph()).startsWith("[0:v:0]setparams=range=tv:color_trc=bt709:colorspace=bt709,");
        assertThat(messages()).filteredOn(m -> m.startsWith("render.color_dropped")).singleElement()
                .isEqualTo("render.color_dropped jobId=" + job.jobId() + " seq=30 options=color_primaries");
    }

    /** 실제 렌더에서 첫 결과 조각의 버린 칸을 남긴다 — 709 원본은 네 칸 모두 받는 값이라 줄이 없다. */
    @Test
    void 받는_값만_있는_원본은_버린_칸_신호가_없다() {
        ((Logger) LoggerFactory.getLogger(JoinedRenderer.class)).setLevel(Level.DEBUG);
        try {
            render(twoPieces(30), false);
        } finally {
            ((Logger) LoggerFactory.getLogger(JoinedRenderer.class)).setLevel(null);
        }

        assertThat(messages()).noneMatch(m -> m.startsWith("render.color_dropped"));
    }

    /** 709 원본 둘(30 · 31)에 자막 하나(0.5–1.5초 · 결과 기준)를 건 주문 — 번인 시험용. */
    private static ObjectNode subtitled() {
        return order().segment(30, false, false).segment(31, false, true)
                .in(30, 1_000_000).out(31, 2_000_000)
                .subtitleAtMedia(0, 30, 1_500_000, 30, 2_500_000).json();
    }

    /** 번인 주문을 돌린다. libass 가 없으면 최종 단계를 가로채 명령 · 번인 파일만 남긴다(돌리지 않음 → null). */
    private Path renderBurned(ObjectNode envelope) {
        boolean burn = Ffmpeg.canBurnSubtitles();
        if (!burn) {
            runner.intercept((cmd, workDir, deadline, r) ->
                    RecordingRunner.isFinalStage(cmd) ? new ProcessRunner.Result("", "") : null);
        }
        JobEnvelope job = new EnvelopeParser(Fixtures.MAPPER).parse(Fixtures.MAPPER.writeValueAsString(envelope));
        Path video = renderJob(job, new MediaProbe(runner, Fixtures.MAPPER, "ffprobe"));
        return burn ? video : null;
    }

    /** 사슬 머리가 709 표지를 붙이고 번인이 그 뒤에 온다. 결과를 돌렸으면(libass 있음) 결과 표지도 원본과 같다. */
    private void assertBurnedAfterColor(String burnFilter, Path video) {
        String graph = finalGraph();
        assertThat(graph).startsWith("[0:v:0]setparams=" + TV_709_PARAMS + ",");
        assertThat(graph.indexOf(burnFilter)).isGreaterThan(graph.indexOf("setparams="));
        assertThat(graph.indexOf("setparams=")).isEqualTo(graph.lastIndexOf("setparams="));
        if (video != null) {
            assertThat(colorOf(video)).isEqualTo(colorOf(FILES.get(30L)));
        }
    }

    private static SegmentTiming timing(ColorTags color) {
        return new SegmentTiming(90_000, 0, 0, new long[] {0}, 3_000, 33_333, List.of(), 0, 0, 3_000, 3_000, 160, 90,
                "yuv420p", color);
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static String twoPieces(long first) {
        return order().segment(first, false, false).segment(first + 1, false, true)
                .in(first, 1_000_000).out(first + 1, 2_000_000).body();
    }

    private static PlanOrder order() {
        return PlanOrder.of(UUID.randomUUID());
    }

    private Path render(String body, boolean decorated) {
        ObjectNode envelope = (ObjectNode) Fixtures.MAPPER.readTree(body);
        if (decorated) {
            envelope.set("recipe", Fixtures.recipeV2("s1"));
        }
        return renderJob(new EnvelopeParser(Fixtures.MAPPER).parse(withoutSubtitles(envelope)),
                new MediaProbe(runner, Fixtures.MAPPER, "ffprobe"));
    }

    private static String withoutSubtitles(String body) {
        return withoutSubtitles((ObjectNode) Fixtures.MAPPER.readTree(body));
    }

    private static String withoutSubtitles(ObjectNode envelope) {
        ((ObjectNode) envelope.get("recipe")).putNull("subtitles");
        return Fixtures.MAPPER.writeValueAsString(envelope);
    }

    /** 조각 {@code seq} 판독만 영상 길이(과 패킷 길이 합)를 한 프레임 줄여 내는 판독기 — 끝 프레임이 영상 길이 끝에 놓인다. */
    private MediaProbe shortened(long seq) {
        return changed(seq, t -> new SegmentTiming(t.videoTimescale(), t.videoFirstDts(), t.fileHeadUs(),
                t.videoPtsSorted(), t.videoLengthTicks() - t.frameIntervalTicks(), t.streamDurationUs(), t.audio(),
                t.formatStartUs(), t.headLeadUs(), t.videoPacketTicks() - t.frameIntervalTicks(),
                t.frameIntervalTicks(), t.width(), t.height(), t.pixelFormat(), t.color()));
    }

    /** 조각 {@code seq} 판독의 색 표지만 {@code color} 로 바꿔 내는 판독기. */
    private MediaProbe recolored(long seq, ColorTags color) {
        return changed(seq, t -> new SegmentTiming(t.videoTimescale(), t.videoFirstDts(), t.fileHeadUs(),
                t.videoPtsSorted(), t.videoLengthTicks(), t.streamDurationUs(), t.audio(), t.formatStartUs(),
                t.headLeadUs(), t.videoPacketTicks(), t.frameIntervalTicks(), t.width(), t.height(), t.pixelFormat(),
                color));
    }

    /** 실제 판독 뒤 조각 {@code seq} 판독만 {@code change} 로 바꾼다. */
    private MediaProbe changed(long seq, UnaryOperator<SegmentTiming> change) {
        return new MediaProbe(runner, Fixtures.MAPPER, "ffprobe") {
            @Override
            public SegmentTiming timing(Path file, Path workDir, Instant deadline) {
                SegmentTiming t = super.timing(file, workDir, deadline);
                return file.getFileName().toString().equals(JoinedRenderer.sourceFile(seq)) ? change.apply(t) : t;
            }
        };
    }

    private Path renderJob(JobEnvelope job, MediaProbe probe) {
        ClipRenderer renderer = new ClipRenderer(store, probe, runner, Fixtures.MAPPER, "ffmpeg", null);
        ClipRenderer.Rendered rendered = renderer.render(job, dir, Instant.now().plus(Duration.ofMinutes(3)),
                (p, s) -> { });
        return rendered.files().getFirst().file();
    }

    private String finalGraph() {
        List<String> last = runner.commands().stream().filter(RecordingRunner::isFinalStage).findFirst()
                .orElseThrow();
        return last.get(last.indexOf("-filter_complex") + 1);
    }

    /** ffprobe 가 내는 색 칸(범위 | 행렬 | 전달 | 원색). */
    private static String colorOf(Path file) {
        return Ffmpeg.probe("-v", "error", "-select_streams", "v:0", "-show_entries", COLOR_ENTRIES,
                "-of", "default=nw=1:nk=1", file.toString()).strip().replace('\n', '|');
    }
}
