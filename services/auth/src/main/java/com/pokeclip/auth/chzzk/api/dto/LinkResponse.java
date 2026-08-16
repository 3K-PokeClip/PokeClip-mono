package com.pokeclip.auth.chzzk.api.dto;

import java.time.Instant;

/** {@code {}}에 넣지 않는다 — channelId. */
public record LinkResponse(String channelId, String channelName, Instant linkedAt) {
}
