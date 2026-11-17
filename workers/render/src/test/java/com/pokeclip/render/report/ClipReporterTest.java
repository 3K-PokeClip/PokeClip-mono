package com.pokeclip.render.report;

import com.pokeclip.render.RenderProperties;
import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.support.FakeClip;
import com.pokeclip.render.support.Fixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보고 문장 다듬기({@link ClipReporter#safeMessage}). 문장은 clip {@code clips.error_message} 로 가서 사용자 화면에
 * 보인다. 받는 쪽이 UTF-16 단위로 자르고(clip {@code Clip.failed}) DB 칸은 글자로 센다(VARCHAR(512)) — 더 엄한 UTF-16
 * 512 단위 안에서, 쌍을 가르지 않고 자르는지 잰다.
 */
class ClipReporterTest {

    private static final String SMILE = "\uD83D\uDE00";

    private FakeClip clip;
    private ClipReporter reporter;

    @BeforeEach
    void setUp() {
        clip = new FakeClip();
        RenderProperties props = new RenderProperties(null, null, null, false, "ap-northeast-2", clip.baseUrl(),
                "secret-token", null, "ffmpeg", "ffprobe", Duration.ofMinutes(1), Duration.ofSeconds(600),
                Duration.ofSeconds(1), List.of(), null);
        reporter = new ClipReporter(props, Fixtures.MAPPER, d -> { });
    }

    @AfterEach
    void tearDown() {
        clip.close();
    }

    @Test
    void 보조_평면_글자는_그대로_두고_UTF16_512_단위_안에서_자른다() {
        String safe = ClipReporter.safeMessage(SMILE.repeat(600));

        assertThat(safe).hasSize(512);
        assertThat(safe.codePointCount(0, safe.length())).isEqualTo(256);
        assertThat(safe).isEqualTo(SMILE.repeat(256));
    }

    @Test
    void 자르는_자리가_쌍_가운데면_쌍을_통째로_뺀다() {
        String safe = ClipReporter.safeMessage("a" + SMILE.repeat(600));

        assertThat(safe).hasSize(511).isEqualTo("a" + SMILE.repeat(255));
        assertThat(Character.isHighSurrogate(safe.charAt(safe.length() - 1))).isFalse();
    }

    @Test
    void 기본_평면_글자는_512자에서_자른다() {
        assertThat(ClipReporter.safeMessage("가".repeat(600))).isEqualTo("가".repeat(512));
        assertThat(ClipReporter.safeMessage("가".repeat(512))).isEqualTo("가".repeat(512));
    }

    @Test
    void 서식_문자는_빈칸으로_바꾼다() {
        // 방향 덮어쓰기 · 폭 0 빈칸 · BOM · 방향 격리 · 언어 꼬리표(보조 평면 Cf — 두 단위가 빈칸 하나가 된다)
        String safe = ClipReporter.safeMessage("a\u202Eb\u200Bc\uFEFFd\u2066e\uDB40\uDC01f");

        assertThat(safe).isEqualTo("a b c d e f");
    }

    @Test
    void 짝_없는_서로게이트는_빈칸으로_바꾸고_쌍은_둔다() {
        String safe = ClipReporter.safeMessage("x\uD800y\uDC00z" + SMILE + "\uD83D");

        assertThat(safe).isEqualTo("x y z" + SMILE + " ");
    }

    @Test
    void 제어_문자와_줄_나눔_문자는_여전히_빈칸이다() {
        assertThat(ClipReporter.safeMessage("a\nb\rc\u0000d\u2028e\u2029f\u0085g")).isEqualTo("a b c d e f g");
        assertThat(ClipReporter.safeMessage(null)).isNull();
    }

    /**
     * 다듬지 않은 짝 없는 서로게이트가 보고 길에서 무엇이 되는지 못 박는다. 보고 본문은 문자열로 직렬화된 뒤 UTF-8 로
     * 나가는데, UTF-8 은 짝 없는 서로게이트를 담지 못해 {@code ?} 로 조용히 바뀐다(예외도 경고도 없다).
     */
    @Test
    void 다듬지_않은_짝_없는_서로게이트는_UTF8_로_나갈_때_물음표가_된다() {
        ObjectNode body = Fixtures.MAPPER.createObjectNode().put("message", "a\uD800b");

        String json = Fixtures.MAPPER.writeValueAsString(body);
        String wire = new String(json.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        assertThat(json).contains("a\uD800b");
        assertThat(wire).contains("\"a?b\"");
    }

    @Test
    void 보고에는_짝_없는_서로게이트와_서식_문자가_빈칸으로_실려_간다() {
        UUID jobId = UUID.randomUUID();

        reporter.terminalFailed(jobId, null, ErrorCode.VALIDATION, "a\uD800b\u202Ec" + SMILE, false);

        String message = clip.last("TERMINAL_FAILED").body().path("error").path("message").asString();
        assertThat(message).isEqualTo("a b c" + SMILE);
    }

    @Test
    void 보고_문장은_받는_쪽_UTF16_512_단위를_넘지_않는다() {
        UUID jobId = UUID.randomUUID();

        reporter.retryScheduled(jobId, FakeClip.TOKEN, ErrorCode.VALIDATION, "a" + SMILE.repeat(600));

        String message = clip.last("RETRY_SCHEDULED").body().path("error").path("message").asString();
        assertThat(message).hasSize(511).isEqualTo("a" + SMILE.repeat(255));
    }
}
