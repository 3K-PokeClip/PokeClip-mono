package com.pokeclip.auth.chzzk;

import java.time.Instant;

/**
 * 서비스의 resolve 결과. StreamKeyService.ResolveResult와 단순 이름이 겹치지 않게 접두어를 붙였다.
 * {@code {}}에 넣지 않는다 — accessToken 원문·channelId. SecretLeakTest가 "ChzzkResolveResult["를 금지한다.
 *
 * <p>reason: NOT_LINKED(행 없음) · UNLINKED(사용자 해제) · BROKEN(갱신 거부) · REFRESH_UNAVAILABLE(즉석 갱신 일시 실패 — 임박 토큰은 안 준다).
 */
public record ChzzkResolveResult(boolean valid, String channelId, String accessToken, Instant expiresAt, String reason) {

    static ChzzkResolveResult rejected(String reason) {
        return new ChzzkResolveResult(false, null, null, null, reason);
    }
}
