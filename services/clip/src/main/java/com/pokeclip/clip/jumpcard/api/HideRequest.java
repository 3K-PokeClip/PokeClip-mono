package com.pokeclip.clip.jumpcard.api;

/**
 * 숨기기 본문(POK-338). 본문이 없거나 {@code reason}이 없거나 {@code null}이면 이유 없이 숨긴다: 옛 호출이 그대로
 * 동작해야 한다. 빈 문자열은 셋 밖의 값이라 400이다.
 */
public record HideRequest(String reason) {
}
