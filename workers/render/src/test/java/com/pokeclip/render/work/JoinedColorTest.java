package com.pokeclip.render.work;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pokeclip.render.job.EnvelopeParser;
import com.pokeclip.render.job.JobEnvelope;
import com.pokeclip.render.media.MediaProbe;
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
    /** 미지정 칸을 ffprobe 기본 출력은 unknown 으로 낸다. */
    private static final String UNKNOWN = "unknown|unknown|unknown|unknown";
    private static final String SOURCE = "testsrc2=size=160x90:rate=30";
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
        ((ObjectNode) envelope.get("recipe")).putNull("subtitles");
        JobEnvelope job = new EnvelopeParser(Fixtures.MAPPER).parse(Fixtures.MAPPER.writeValueAsString(envelope));
        MediaProbe probe = new MediaProbe(runner, Fixtures.MAPPER, "ffprobe");
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
