package com.pokeclip.auth.youtube;

import java.time.Duration;

/**
 * 구글 토큰 응답. {@code {}}에 통째로 넣지 않는다 — record의 toString이 토큰 원문을 찍는다
 * (SecretLeakTest가 {@code YoutubeTokens[} 접두어를 금지한다).
 *
 * <p>{@code refreshToken}은 <b>갱신 응답에서 보통 없다</b>(null) — 구글은 기존 refresh를 계속 쓰게 한다.
 * 교환 응답에서 없으면 그것은 실패다(갱신할 수 없는 반쪽 연동).
 */
public record YoutubeTokens(String accessToken, String refreshToken, Duration expiresIn, String scope) {
}
