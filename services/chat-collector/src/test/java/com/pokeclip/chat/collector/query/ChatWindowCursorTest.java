package com.pokeclip.chat.collector.query;

import com.pokeclip.chat.collector.query.ChatWindowCursor.Cursor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 커서는 <b>우리가 만든 것만</b> 받는다. 남이 지어낸 문자열을 그대로 SQL 파라미터로 넘기면
 * 「받은 값을 되비추지 않는다」는 창구 규칙이 커서 경로로 새고, 무엇보다 <b>순서가 깨진
 * 커서로 페이징이 순환한다</b>(F1).
 */
class ChatWindowCursorTest {

    private static String base64(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void 왕복한다() {
        Cursor c = ChatWindowCursor.decodeOrFirstPage(
                ChatWindowCursor.encode(1_754_300_000_000L, "d", 42));

        assertThat(c).isEqualTo(new Cursor(1_754_300_000_000L, "d", 42));
    }

    /** 문항 2 — 「예외가 안 난다」로만 재면 아무 값이나 첫 장으로 접는 구현도 초록이다. */
    @Test
    void 빈_문자열은_첫_장이다() {
        assertThat(ChatWindowCursor.decodeOrFirstPage("  ")).isNull();
        assertThat(ChatWindowCursor.decodeOrFirstPage(null)).isNull();
    }

    /**
     * 태그 {@code h}는 <b>우리 모양이라는 표식</b>이다. 없으면 clip이나 다른 창구의 커서를
     * 여기에 넣어도 숫자만 맞으면 통과한다.
     */
    @Test
    void 남의_모양은_거절한다() {
        assertThatThrownBy(() -> ChatWindowCursor.decodeOrFirstPage(base64("b:1")))
                .isInstanceOf(InvalidCursorException.class);
    }

    /** 종류는 {@code c}·{@code d} 둘뿐이다. 다른 글자면 {@code afterFor}의 네 갈래가 안 선다. */
    @Test
    void 모르는_종류와_망가진_값은_거절한다() {
        for (String raw : new String[] {
                "h:1:x:1",              // 종류가 c도 d도 아니다
                "h:1:c",                // 칸이 셋
                "h:1:c:1:2",            // 칸이 다섯
                "h:abc:c:1",            // 시각이 숫자가 아니다
                "h:1:c:abc"}) {         // id가 숫자가 아니다
            assertThatThrownBy(() -> ChatWindowCursor.decodeOrFirstPage(base64(raw)))
                    .as(raw)
                    .isInstanceOf(InvalidCursorException.class);
        }
        assertThatThrownBy(() -> ChatWindowCursor.decodeOrFirstPage("!!!not-base64!!!"))
                .isInstanceOf(InvalidCursorException.class);
    }

    /**
     * 🔴 <b>표에 안 들어가는 시각은 400이다</b>(봇 codex P2). {@code long}에는 들어가는데
     * PostgreSQL {@code timestamptz}에는 안 들어가는 값을 그대로 넘기면 질의가 터져
     * <b>500</b>이 나간다 — 이 창구에서 500은 「DB가 죽었다」로 계약된 신호라, 부르는 쪽
     * 입력 오류를 거기 실으면 <b>없는 장애를 쫓게 만든다.</b>
     *
     * <p>범위는 {@code WindowRequest}와 <b>같은 값을 가져다 쓴다</b> — 같은 표의 같은 칸을
     * 가리키는 값이 창구마다 다른 범위를 갖는 것이 이상하고, 복제하면 한쪽만 낡는다.
     */
    @Test
    void 표에_안_들어가는_시각의_커서는_거절한다() {
        String 너무_먼_미래 = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("h:9000000000000000:c:1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String 너무_이른_과거 = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("h:-1:c:1".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> ChatWindowCursor.decodeOrFirstPage(너무_먼_미래))
                .isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> ChatWindowCursor.decodeOrFirstPage(너무_이른_과거))
                .isInstanceOf(InvalidCursorException.class);

        String 경계_안 = ChatWindowCursor.encode(
                java.time.Instant.parse("2026-09-09T00:00:00Z").toEpochMilli(), "c", 1L);
        assertThat(ChatWindowCursor.decodeOrFirstPage(경계_안))
                .as("멀쩡한 커서까지 막으면 페이징이 통째로 멎는다").isNotNull();
    }
}
