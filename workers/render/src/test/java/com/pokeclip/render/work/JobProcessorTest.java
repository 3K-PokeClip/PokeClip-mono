package com.pokeclip.render.work;

import com.pokeclip.render.RenderProperties;
import com.pokeclip.render.job.EnvelopeParser;
import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.report.ClipReporter;
import com.pokeclip.render.storage.S3Store;
import com.pokeclip.render.support.FakeClip;
import com.pokeclip.render.support.Fixtures;
import com.pokeclip.render.support.PlanOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigInteger;
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
@ExtendWith(OutputCaptureExtension.class)
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
                .thenReturn(new ClipRenderer.Rendered(List.of(new ClipRenderer.Produced("o1", "video", video)), null));
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
        assertThat(done.body().has("placements")).as("옛 꼴 SUCCEEDED 본문은 예전 그대로").isFalse();
        assertThat(done.body().has("lengthUs")).isFalse();
    }

    /** 새 꼴 주문은 SUCCEEDED 본문 맨 위에 {@code result} 형제 칸으로 배치표와 결과 길이를 싣는다(설계 r7 3.3 · 7.6). */
    @Test
    void 새_꼴_성공은_배치표와_결과_길이를_result_옆에_싣는다() throws Exception {
        Path video = Files.writeString(work.resolve("o1.mp4"), "v");
        ClipReporter.ResultLayout layout = new ClipReporter.ResultLayout(List.of(
                new ClipReporter.PlacementItem(101, 0, -1_000_000, 3_966_667, 21_333),
                new ClipReporter.PlacementItem(102, 4_000_000, 0, 1_500_000, 0)), 5_500_000);
        when(renderer.render(any(), any(), any(), any())).thenReturn(
                new ClipRenderer.Rendered(List.of(new ClipRenderer.Produced("o1", "video", video)), layout));
        String body = PlanOrder.of(jobId).segment(101, false, false).segment(102, false, true)
                .in(101, 1_000_000).out(102, 1_500_000).body();

        assertThat(processor.process(body)).isEqualTo(Disposition.DELETE);

        tools.jackson.databind.JsonNode done = clip.last("SUCCEEDED").body();
        assertThat(done.path("result").get(0).path("outputId").asString()).isEqualTo("o1");
        assertThat(done.path("lengthUs").asLong()).isEqualTo(5_500_000);
        assertThat(done.path("placements")).hasSize(2);
        tools.jackson.databind.JsonNode first = done.path("placements").get(0);
        assertThat(first.path("seq").asLong()).isEqualTo(101);
        assertThat(first.path("zeroAtUs").asLong()).isZero();
        assertThat(first.path("fromUs").asLong()).isEqualTo(-1_000_000);
        assertThat(first.path("toUs").asLong()).isEqualTo(3_966_667);
        assertThat(first.path("headLeadUs").asLong()).isEqualTo(21_333);
        assertThat(done.path("placements").get(1).path("zeroAtUs").asLong()).isEqualTo(4_000_000);
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

    /** long 에 담기지 않는 계획 정수 칸 — 큐 루프 오류로 새어 재수신 · 실패 큐로 가지 않고 preflight 종결된다. */
    @Test
    void 정수_칸이_범위_밖인_새_꼴_주문은_무토큰_TERMINAL_FAILED_뒤_지운다() {
        ObjectNode envelope = PlanOrder.of(jobId).segment(101, false, false).segment(102, false, true)
                .in(101, 0).out(102, 0).json();
        ((ObjectNode) envelope.get("plan")).put("lengthMs",
                BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));

        Disposition d = processor.process(Fixtures.MAPPER.writeValueAsString(envelope));

        assertThat(d).isEqualTo(Disposition.DELETE);
        assertThat(clip.types()).containsExactly("TERMINAL_FAILED");
        FakeClip.Received failed = clip.last("TERMINAL_FAILED");
        assertThat(failed.body().has("executionToken")).isFalse();
        assertThat(failed.body().path("error").path("code").asString()).isEqualTo("ENVELOPE_VALIDATION");
        verify(renderer, never()).render(any(), any(), any(), any());
    }

    /** 거절 보고도 다른 보고처럼 clip 이 끝내 답을 안 하면 메시지를 그대로 둔다(큐 루프 오류로 새지 않는다). */
    @Test
    void 거절_보고를_clip이_끝내_못_받으면_메시지를_둔다() {
        clip.answer("TERMINAL_FAILED", 503, "{}").answer("TERMINAL_FAILED", 502, "{}")
                .answer("TERMINAL_FAILED", 500, "{}");
        ObjectNode envelope = Fixtures.envelope(jobId, Fixtures.recipe("s1"));
        ((ObjectNode) envelope.get("recipe")).put("title", "x");

        assertThat(processor.process(Fixtures.MAPPER.writeValueAsString(envelope))).isEqualTo(Disposition.LEAVE);
        assertThat(clip.types()).containsExactly("TERMINAL_FAILED", "TERMINAL_FAILED", "TERMINAL_FAILED");
    }

    /** 레시피 값에 줄 나눔 · 위조 줄을 넣어도 거절 문장은 고정 글자다 — 보고에도 로그에도 입력이 실리지 않는다. */
    @Test
    void 레시피_거절_문장은_입력_값을_보고에도_로그에도_싣지_않는다(CapturedOutput output) {
        String evil = "X\nrender.forged jobId=0\r\u2028" + "가".repeat(600);
        ObjectNode envelope = Fixtures.envelope(jobId, Fixtures.recipe("s1"));
        ((ObjectNode) envelope.get("recipe").get("subtitles")).put("mode", evil);

        assertThat(processor.process(Fixtures.MAPPER.writeValueAsString(envelope))).isEqualTo(Disposition.DELETE);

        String message = clip.last("TERMINAL_FAILED").body().path("error").path("message").asString();
        assertThat(message).isEqualTo("subtitles.mode은 지원하지 않는다");
        assertThat(output.getOut()).contains("render.preflight_rejected jobId=" + jobId)
                .doesNotContain("render.forged").doesNotContain("가가");
    }

    /**
     * 실패 문장에 제어 · 줄 나눔(U+2028) · 서식(U+202E) 문자와 짝 없는 서로게이트가 섞여 와도 빈칸으로 바꿔 보고하고,
     * 길이는 clip 이 자르는 UTF-16 단위 512 안이다(보조 평면 글자 쌍을 가르지 않는다).
     */
    @Test
    void 실패_문장은_다듬어서_UTF16_512_단위_안으로_보고한다() {
        String smile = "\uD83D\uDE00";
        String evil = "X\nrender.forged\r\u2028\u202E\uD800" + smile.repeat(600);
        when(renderer.render(any(), any(), any(), any()))
                .thenThrow(RenderFailure.permanent(ErrorCode.VALIDATION, evil));

        assertThat(processor.process(body())).isEqualTo(Disposition.DELETE);

        String message = clip.last("TERMINAL_FAILED").body().path("error").path("message").asString();
        String head = "X render.forged    ";
        assertThat(message).isEqualTo(head + smile.repeat((512 - head.length()) / 2));
        assertThat(message.length()).isLessThanOrEqualTo(512);
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

    /** 렌더하는 사이 탈퇴로 주문이 지워졌다(POK-256). clip의 지우기는 끝나 이 파일을 모르니 일꾼이 치운다. */
    @Test
    void 성공_보고가_404면_올린_파일을_치우고_지운다() {
        clip.answer("SUCCEEDED", 404, "{}");

        assertThat(processor.process(body())).isEqualTo(Disposition.DELETE);

        verify(store).deleteQuietly("clips", List.of("clips/42/" + FakeClip.TOKEN + "/o1.mp4"));
    }

    @Test
    void 진행_보고가_404면_렌더를_멈추고_올린_게_없으면_치울_것도_없다() {
        when(renderer.render(any(), any(), any(), any())).thenAnswer(inv -> {
            ClipRenderer.Progress progress = inv.getArgument(3);
            progress.report(10, "download");
            throw new AssertionError("404 뒤에도 계속 일했다");
        });
        clip.answer("PROGRESS", 404, "{}");

        assertThat(processor.process(body())).isEqualTo(Disposition.DELETE);
        assertThat(clip.types()).containsExactly("STARTED", "PROGRESS");
        verify(store, never()).upload(any(), any(), any(), any(), any());
        verify(store, never()).deleteQuietly(any(), any());
    }

    @Test
    void 성공하면_올린_파일을_안_치운다() {
        processor.process(body());
        verify(store, never()).deleteQuietly(any(), any());
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
