package com.pokeclip.auth.soop;

import java.time.Duration;

/**
 * SOOP 토큰 응답. {@code {}}에 통째로 넣지 않는다: record의 toString이 토큰 원문을 찍는다.
 * 갱신 응답에 refresh_token이 없으면 {@code refreshToken}이 null이고 호출부가 옛 값을 유지한다.
 * scope는 오면 담고 없으면 null.
 */
public record SoopTokens(String accessToken, String refreshToken, Duration expiresIn, String scope) {
}
