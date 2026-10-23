package com.pokeclip.auth.soop;

import java.time.Instant;

/**
 * 서비스의 resolve 결과. {@code {}}에 넣지 않는다: access 원문·SOOP 아이디.
 *
 * <p>reason: NOT_LINKED(행 없음) · UNLINKED(사용자 해제·재연동·탈퇴) · BROKEN(갱신 거부) ·
 * REFRESH_UNAVAILABLE(즉석 갱신 일시 실패, 임박 토큰은 안 준다).
 */
public record SoopResolveResult(boolean valid, String soopUserId, String accessToken, Instant expiresAt, String reason) {

    static SoopResolveResult rejected(String reason) {
        return new SoopResolveResult(false, null, null, null, reason);
    }
}
