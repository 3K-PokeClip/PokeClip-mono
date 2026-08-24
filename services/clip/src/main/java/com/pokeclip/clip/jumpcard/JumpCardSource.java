package com.pokeclip.clip.jumpcard;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 카드를 누가 만들었나. 값 집합은 표의 {@code ck_jump_cards_source}와 같아야 한다.
 *
 * <p>{@code @JsonValue}는 Jackson 3에서도 {@code com.fasterxml.jackson.annotation} 좌표다
 * (jackson-annotations는 2.x 좌표를 유지한다). 이게 없으면 JSON에 {@code "AUTO"}로 나가
 * 2번과의 계약(소문자)이 깨진다.
 */
public enum JumpCardSource {
    AUTO("auto"),
    /** 스트리머가 직접 찍은 지점(POK-119). 판별기 이벤트 번호가 없다. */
    HOTKEY("hotkey");

    private final String dbValue;

    JumpCardSource(String dbValue) {
        this.dbValue = dbValue;
    }

    @JsonValue
    public String dbValue() {
        return dbValue;
    }

    public static JumpCardSource fromDbValue(String value) {
        for (JumpCardSource source : values()) {
            if (source.dbValue.equals(value)) {
                return source;
            }
        }
        throw new IllegalArgumentException("모르는 카드 출처: " + value);
    }
}
