package com.pokeclip.auth.chzzk.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pokeclip.auth.chzzk.ChzzkResolveResult;

import java.time.Instant;

/**
 * 수집기용 resolve 응답. NON_NULL이라 거절 응답에는 accessToken 필드가 아예 나타나지 않는다.
 *
 * <p><b>{}에 통째로 넣지 않는다.</b> SecretLeakTest가 "ChzzkResolveResponse["를 금지한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChzzkResolveResponse(boolean valid, String channelId, String accessToken, Instant expiresAt, String reason) {

    public static ChzzkResolveResponse from(ChzzkResolveResult r) {
        return new ChzzkResolveResponse(r.valid(), r.channelId(), r.accessToken(), r.expiresAt(), r.reason());
    }
}
