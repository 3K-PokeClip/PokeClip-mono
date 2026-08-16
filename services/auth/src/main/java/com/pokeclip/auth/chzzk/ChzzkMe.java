package com.pokeclip.auth.chzzk;

/** users/me 응답. {@code {}}에 통째로 넣지 않는다 — channelId는 로그에 찍지 않는다. */
public record ChzzkMe(String channelId, String channelName) {
}
