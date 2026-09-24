package com.pokeclip.render.work;

import com.pokeclip.render.RenderProperties;
import com.pokeclip.render.job.EnvelopeParser;
import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.report.ClipReporter;
import com.pokeclip.render.storage.S3Store;
import com.pokeclip.render.support.FakeClip;
import com.pokeclip.render.support.Fixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 계약1 4절 「워커 거동」 표를 한 줄씩 잰다. 렌더·S3는 가짜, clip 보고 문은 실제 HTTP 가짜다.
 */
class JobProcessorTest {

    @TempDir
    Path work;

    private FakeClip clip;
    private ClipRenderer renderer;
    private S3Store store;
    private JobProcessor processor;
    private final UUID jobId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        clip = new FakeClip();
        renderer = mock(ClipRenderer.class);
        store = mock(S3Store.class);
        Path video = Files.writeString(work.resolve("o1.mp4"), "v");
        when(renderer.render(any(), any(), any(), any()))
                .thenReturn(List.of(new ClipRenderer.Produced("o1", "video", video)));
        RenderProperties props = new RenderProperties(null, null, null, false, "ap-northeast-2", clip.baseUrl(),
                "secret-token", work, "ffmpeg", "ffprobe", Duration.ofMinutes(1), Duration.ofSeconds(600),
                Duration.ofSeconds(1), List.of(Duration.ZERO, Duration.ZERO), null);
        ClipReporter reporter = new ClipReporter(props, Fixtures.MAPPER, d -> { });
        processor = new JobProcessor(new EnvelopeParser(Fixtures.MAPPER), reporter, renderer, store, work,
                Duration.ofMinutes(1), Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        clip.close();
    }

    @Test
    void 성공하면_토큰_아래에_올리고_SUCCEEDED_뒤_지운다() {
        Disposition d = processor.process(body());

        assertThat(d).isEqualTo(Disposition.DELETE);
        assertThat(clip.types()).containsExactly("STARTED", "PROGRESS", "SUCCEEDED");
        String key = "clips/42/" + FakeClip.TOKEN + "/o1.mp4";
        verify(store).upload(eq("clips"), eq(key), any(), eq("video/mp4"), any());
        FakeClip.Received done = clip.last("SUCCEEDED");
        assertThat(done.body().path("executionToken").asString()).isEqualTo(FakeClip.TOKEN);
        assertThat(done.body().path("result").get(0).path("s3Key").asString()).isEqualTo(key);
        assertThat(done.internalToken()).isEqualTo("secret-token");
        assertThat(done.idempotencyKey()).isEqualTo(done.body().path("eventId").asString());
        assertThat(clip.last("STARTED").body().has("executionToken")).as("STARTED는 무토큰").isFalse();
    }

    @Test
    void proceed_false면_일하지_않고_지운다() {
        clip.answer("STARTED", 200, "{\"proceed\":false}");
        assertThat(processor.process(body())).isEqualTo(Disposition.DELETE);
        verify(renderer, never()).render(any(), any(), any(), any());
    }

    @Test
    void preflight_실패는_무토큰_TERMINAL_FAILED() {
        ObjectNode envelope = Fixtures.envelope(jobId, Fixtures.recipe("s1"));
        ((ObjectNode) envelope.get("recipe")).put("title", "x");
        Disposition d = processor.process(Fixtures.MAPPER.writeValueAsString(envelope));

        assertThat(d).isEqualTo(Disposition.DELETE);
        assertThat(clip.types()).containsExactly("TERMINAL_FAILED");
        FakeClip.Received failed = clip.last("TERMINAL_FAILED");
        assertThat(failed.body().has("executionToken")).isFalse();
        assertThat(failed.body().path("error").path("code").asString()).isEqualTo("VALIDATION");
    }

    @Test
    void jobId를_못_읽으면_보고_없이_지운다() {
        assertThat(processor.process("{\"schemaVersion\":1}")).isEqualTo(Disposition.DELETE);
        assertThat(clip.received()).isEmpty();
    }

    @Test
    void 다시_해도_같은_실패는_바로_종결한다() {
        when(renderer.render(any(), any(), any(), any()))
                .thenThrow(RenderFailure.permanent(ErrorCode.SOURCE_EXPIRED, "영상 조각이 저장소에 없다"));
        assertThat(processor.process(body())).isEqualTo(Disposition.DELETE);
        FakeClip.Received failed = clip.last("TERMINAL_FAILED");
        assertThat(failed.body().path("error").path("code").asString()).isEqualTo("SOURCE_EXPIRED");
        assertThat(failed.body().path("executionToken").asString()).isEqualTo(FakeClip.TOKEN);
        assertThat(clip.types()).doesNotContain("RETRY_SCHEDULED");
    }

    @Test
    void 일시_실패는_RETRY_SCHEDULED와_60에서_120초_뒤_다시() {
        doThrow(RenderFailure.transientFailure("S3 500", null)).when(store).upload(anyString(), anyString(), any(), anyString(), any());
        Disposition d = processor.process(body());
        assertThat(d.kind()).isEqualTo(Disposition.Kind.DELAY);
        assertThat(d.delay()).isBetween(Duration.ofSeconds(60), Duration.ofSeconds(120));
        assertThat(clip.last("RETRY_SCHEDULED").body().path("error").path("code").asString()).isEqualTo("INTERNAL");
        assertThat(clip.types()).doesNotContain("TERMINAL_FAILED");
    }

    @Test
    void 마지막_시도의_일시_실패는_종결한다() {
        clip.finalAttempt();
        doThrow(RenderFailure.transientFailure("S3 500", null)).when(store).upload(anyString(), anyString(), any(), anyString(), any());
        assertThat(processor.process(body())).isEqualTo(Disposition.DELETE);
        assertThat(clip.types()).doesNotContain("RETRY_SCHEDULED").contains("TERMINAL_FAILED");
    }

    @Test
    void SUPERSEDED면_메시지에_손대지_않는다() {
        clip.answer("SUCCEEDED", 409, "{\"reason\":\"SUPERSEDED\"}");
        assertThat(processor.process(body())).isEqualTo(Disposition.LEAVE);
    }

    @Test
    void TERMINAL이면_지운다() {
        clip.answer("SUCCEEDED", 409, "{\"reason\":\"TERMINAL\"}");
        assertThat(processor.process(body())).isEqualTo(Disposition.DELETE);
    }

    @Test
    void 진행_보고가_409면_렌더를_멈춘다() {
        when(renderer.render(any(), any(), any(), any())).thenAnswer(inv -> {
            ClipRenderer.Progress progress = inv.getArgument(3);
            progress.report(10, "download");
            throw new AssertionError("409 뒤에도 계속 일했다");
        });
        clip.answer("PROGRESS", 409, "{\"reason\":\"SUPERSEDED\"}");
        assertThat(processor.process(body())).isEqualTo(Disposition.LEAVE);
        assertThat(clip.types()).containsExactly("STARTED", "PROGRESS");
    }

    @Test
    void 결과가_주문과_안_맞으면_RESULT_VALIDATION으로_종결한다() {
        clip.answer("SUCCEEDED", 400, "{\"reason\":\"INVALID_RESULT\"}");
        assertThat(processor.process(body())).isEqualTo(Disposition.DELETE);
        assertThat(clip.last("TERMINAL_FAILED").body().path("error").path("code").asString())
                .isEqualTo("RESULT_VALIDATION");
    }

    @Test
    void 모르는_잡이면_지운다() {
        clip.answer("STARTED", 404, "{}");
        assertThat(processor.process(body())).isEqualTo(Disposition.DELETE);
    }

    @Test
    void clip이_5xx면_같은_eventId로_다시_보내고_끝내_안되면_메시지를_둔다() {
        clip.answer("STARTED", 503, "{}").answer("STARTED", 502, "{}").answer("STARTED", 500, "{}");
        assertThat(processor.process(body())).isEqualTo(Disposition.LEAVE);
        assertThat(clip.types()).containsExactly("STARTED", "STARTED", "STARTED");
        assertThat(clip.received()).extracting(r -> r.body().path("eventId").asString()).containsOnly(
                clip.received().getFirst().body().path("eventId").asString());
        verify(renderer, never()).render(any(), any(), any(), any());
    }

    @Test
    void 주문_폴더는_끝나면_지운다() throws Exception {
        processor.process(body());
        try (var files = Files.list(work)) {
            assertThat(files.filter(p -> p.getFileName().toString().startsWith("job-"))).isEmpty();
        }
    }

    private String body() {
        return Fixtures.MAPPER.writeValueAsString(Fixtures.envelope(jobId, Fixtures.recipe("s1")));
    }
}
