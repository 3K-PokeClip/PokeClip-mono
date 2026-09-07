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
}
