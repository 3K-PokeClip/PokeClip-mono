package com.pokeclip.clip.jumpcard.api;

/** 숨기기 본문(POK-338). 본문이 없거나 {@code reason}이 비어도 된다 — 옛 호출이 그대로 동작해야 한다. */
public record HideRequest(String reason) {
}
