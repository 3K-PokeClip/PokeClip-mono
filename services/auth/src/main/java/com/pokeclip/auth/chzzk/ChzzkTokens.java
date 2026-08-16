package com.pokeclip.auth.chzzk;

import java.time.Duration;

/**
 * 치지직 토큰 응답. {@code {}}에 통째로 넣지 않는다 — record의 toString이 토큰 원문을 찍는다
 * (SecretLeakTest가 {@code ChzzkTokens[} 접두어를 금지한다). scope는 발급·갱신 응답 둘 다에 실려 온다(실측, 없으면 null).
 */
public record ChzzkTokens(String accessToken, String refreshToken, Duration expiresIn, String scope) {
}
