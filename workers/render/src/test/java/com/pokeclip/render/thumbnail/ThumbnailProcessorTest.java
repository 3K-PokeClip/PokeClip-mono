package com.pokeclip.render.thumbnail;

import com.pokeclip.render.RenderProperties;
import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.media.ProcessRunner;
import com.pokeclip.render.storage.S3Store;
import com.pokeclip.render.support.Ffmpeg;
import com.pokeclip.render.support.Fixtures;
import com.pokeclip.render.work.Disposition;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 사진 주문 하나(POK-277). 원본은 진짜 ffmpeg로 만든 짧은 영상이고(없으면 건너뛴다), 창고는 가짜, clip 보고 문은 이 시험의 작은 서버다.
 * 재는 것: 받은 키에서 받아 받은 키에 jpg가 올라가는가, 보고에 무엇이 실리는가, 실패마다 메시지를 지우는가 두는가.
 */
class ThumbnailProcessorTest {

    @TempDir
    Path work;

    private HttpServer clip;
    private final List<JsonNode> reports = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private S3Store store;
    private Path uploaded;
    private Path video;

    @BeforeEach
    void setUp() throws IOException {
        clip = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        clip.createContext("/internal/thumbnails", exchange -> {
            reports.add(Fixtures.MAPPER.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] body = "{\"saved\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        clip.start();
        store = mock(S3Store.class);
        uploaded = work.resolve("uploaded.jpg");
    }

    @AfterEach
    void tearDown() {
        clip.stop(0);
    }

    private ThumbnailProcessor processor() {
        RenderProperties props = new RenderProperties(null, null, null, false, "ap-northeast-2",
                "http://127.0.0.1:" + clip.getAddress().getPort(), "secret-token", work, "ffmpeg", "ffprobe",
                Duration.ofMinutes(1), Duration.ofSeconds(600), Duration.ofSeconds(1), List.of(), null);
        return new ThumbnailProcessor(Fixtures.MAPPER, store, new ProcessRunner(), new ThumbnailReporter(props),
                "ffmpeg", work.resolve("thumbnails"), Duration.ofSeconds(30), Clock.systemUTC());
    }

    /** 2초짜리 1280x720 시험 영상을 만들고, 창고 받기가 그 파일을 주고 올리기가 결과를 옮겨 두게 한다. */
    private void 원본이_있다() throws IOException, InterruptedException {
        assumeTrue(Ffmpeg.available(), "ffmpeg가 없다");
        video = work.resolve("src.mp4");
        Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-nostdin", "-y", "-f", "lavfi", "-i",
                "testsrc=size=1280x720:rate=30:duration=2", "-pix_fmt", "yuv420p", video.toString())
                .redirectErrorStream(true).redirectOutput(work.resolve("ffmpeg.log").toFile()).start();
        assertThat(p.waitFor()).isZero();
        doAnswer(inv -> {
            Files.copy(video, inv.<Path>getArgument(2));
            return null;
        }).when(store).download(eq("segments-test"), eq("seg/1.ts"), any(), any());
        doAnswer(inv -> {
            Files.copy(inv.<Path>getArgument(2), uploaded);
            return null;
        }).when(store).upload(eq("clips-test"), eq("thumbnails/card/7.jpg"), any(), eq("image/jpeg"), any());
    }

    private static String 주문서(long offsetMs) {
        ObjectNode root = Fixtures.MAPPER.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("kind", "card");
        root.put("targetId", "7");
        root.put("capturedAt", "2026-10-04T10:00:00Z");
        root.putObject("source").put("bucket", "segments-test").put("s3Key", "seg/1.ts").put("offsetMs", offsetMs);
        root.putObject("output").put("bucket", "clips-test").put("s3Key", "thumbnails/card/7.jpg");
        return Fixtures.MAPPER.writeValueAsString(root);
    }

    @Test
    void 장면을_jpg로_뽑아_받은_키에_올리고_알린다() throws Exception {
        원본이_있다();

        Disposition d = processor().process(주문서(1_000));

        assertThat(d).isEqualTo(Disposition.DELETE);
        byte[] jpg = Files.readAllBytes(uploaded);
        assertThat(jpg[0]).isEqualTo((byte) 0xFF);   // JPEG 시작 표식 FF D8
        assertThat(jpg[1]).isEqualTo((byte) 0xD8);
        String size = Ffmpeg.probe("-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height",
                "-of", "csv=p=0", uploaded.toString());
        assertThat(size.trim()).isEqualTo("640,360");  // 가로 640 상한, 비율 유지
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).path("kind").asString()).isEqualTo("card");
        assertThat(reports.get(0).path("targetId").asString()).isEqualTo("7");
        assertThat(reports.get(0).path("capturedAt").asString()).isEqualTo("2026-10-04T10:00:00Z");
        assertThat(reports.get(0).has("s3Key")).as("키는 clip이 정한다. 보고에 안 싣는다").isFalse();
    }

    /** 자리가 원본 끝을 넘으면(조각 길이가 장부와 다르다) 첫 장면으로 다시 뽑는다. 빈손으로 끝내지 않는다. */
    @Test
    void 자리가_끝을_넘으면_첫_장면을_뽑는다() throws Exception {
        원본이_있다();

        Disposition d = processor().process(주문서(9_000));

        assertThat(d).isEqualTo(Disposition.DELETE);
        assertThat(Files.size(uploaded)).isPositive();
        assertThat(reports).hasSize(1);
    }

    @Test
    void 주문서_모양이_틀리면_지우고_아무것도_안_한다() {
        assertThat(processor().process("{\"schemaVersion\":1,\"kind\":\"poster\"}")).isEqualTo(Disposition.DELETE);
        assertThat(processor().process(주문서(1_000).replace("thumbnails/card/7.jpg", "clips/7/o1.jpg")))
                .as("사진 자리 밖 키에는 안 올린다").isEqualTo(Disposition.DELETE);
        assertThat(processor().process("깨진 글")).isEqualTo(Disposition.DELETE);
        verify(store, never()).download(any(), any(), any(), any());
        assertThat(reports).isEmpty();
    }

    /** 원본이 창고에 없으면(지워진 조각) 다시 해도 같다. 지운다. */
    @Test
    void 원본이_없으면_지운다() {
        doThrow(RenderFailure.permanent(ErrorCode.SOURCE_EXPIRED, "없다")).when(store).download(any(), any(), any(), any());

        assertThat(processor().process(주문서(1_000))).isEqualTo(Disposition.DELETE);
        assertThat(reports).isEmpty();
    }

    /** 창고가 잠깐 안 되면 메시지를 두고 숨김 시간 뒤 다시 한다. */
    @Test
    void 창고가_잠깐_안_되면_둔다() {
        doThrow(RenderFailure.transientFailure("잠깐", null)).when(store).download(any(), any(), any(), any());

        assertThat(processor().process(주문서(1_000))).isEqualTo(Disposition.LEAVE);
    }

    /** 보고가 5xx면 두고(다음에 통째로 다시), 4xx면 지운다(clip이 확정 거절했다). */
    @Test
    void 보고_응답에_따라_두거나_지운다() throws Exception {
        원본이_있다();

        status.set(503);
        assertThat(processor().process(주문서(1_000))).isEqualTo(Disposition.LEAVE);

        Files.deleteIfExists(uploaded);
        status.set(400);
        assertThat(processor().process(주문서(1_000))).isEqualTo(Disposition.DELETE);
    }
}
