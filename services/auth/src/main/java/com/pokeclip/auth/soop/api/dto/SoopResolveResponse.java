package com.pokeclip.auth.soop.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pokeclip.auth.soop.SoopResolveResult;

import java.time.Instant;

/**
 * 수집기용 resolve 응답. NON_NULL이라 거절 응답에는 토큰·아이디 칸이 아예 없다. SOOP 아이디는 이 내부 응답에만 싣는다
 * (수집기가 채팅방을 가리키는 데 필요하다. 공개 API에는 없다). {@code {}}에 통째로 넣지 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SoopResolveResponse(boolean valid, String soopUserId, String accessToken, Instant expiresAt, String reason) {

    public static SoopResolveResponse from(SoopResolveResult r) {
        return new SoopResolveResponse(r.valid(), r.soopUserId(), r.accessToken(), r.expiresAt(), r.reason());
    }
}
