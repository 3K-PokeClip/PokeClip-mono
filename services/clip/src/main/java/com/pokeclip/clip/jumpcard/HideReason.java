package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.jumpcard.JumpCardErrors.InvalidHighlightException;

import java.util.Arrays;

/**
 * 카드를 숨긴 이유(POK-338, 2026-10-15 결정). 거짓양성률 = 자동 카드 중 {@link #NOT_HIGHLIGHT}로 숨긴 비율이다.
 *
 * <p>값은 표({@code ck_jump_cards_hidden_reason})·행동 기록({@code ck_jump_card_actions_reason})의 CHECK와 같다.
 * 하나를 더하면 셋을 같이 고친다.
 */
public enum HideReason {
    NOT_HIGHLIGHT("not_highlight"),
    DUPLICATE("duplicate"),
    OTHER("other");

    private final String dbValue;

    HideReason(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /**
     * @param value 요청 본문의 {@code reason}. {@code null}이면 이유 없이 숨긴다(옛 호출)
     * @throws InvalidHighlightException 셋 밖의 값 (400 {@code field:"reason"}). 값은 응답·로그에 안 싣는다
     */
    public static HideReason parseNullable(String value) {
        if (value == null) {
            return null;
        }
        return Arrays.stream(values()).filter(r -> r.dbValue.equals(value)).findFirst()
                .orElseThrow(() -> new InvalidHighlightException("reason"));
    }
}
