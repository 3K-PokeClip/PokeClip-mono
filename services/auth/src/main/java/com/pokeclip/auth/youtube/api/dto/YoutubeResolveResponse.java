package com.pokeclip.auth.youtube.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pokeclip.auth.youtube.YoutubeResolveResult;

import java.time.Instant;

/**
 * 업로드 워커용 resolve 응답. NON_NULL이라 거절 응답에는 accessToken 필드가 아예 나타나지 않는다 —
 * 「키는 있는데 null」이면 호출자가 문자열 "null"을 실어 보내는 자리가 생긴다.
 *
 * <p><b>{}에 통째로 넣지 않는다.</b>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record YoutubeResolveResponse(boolean valid, String channelId, String accessToken, Instant expiresAt,
                                     String reason) {

    public static YoutubeResolveResponse from(YoutubeResolveResult r) {
        return new YoutubeResolveResponse(r.valid(), r.channelId(), r.accessToken(), r.expiresAt(), r.reason());
    }
}
