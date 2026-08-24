package com.pokeclip.auth.youtube;

/** channels.list 항목 하나. {@code {}}에 통째로 넣지 않는다 — channelId는 로그에 찍지 않는다. */
public record YoutubeChannel(String channelId, String channelName) {
}
