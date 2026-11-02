package com.pokeclip.auth.youtube;

import java.time.Instant;

/**
 * 서비스의 resolve 결과. {@code {}}에 넣지 않는다 — accessToken 원문·channelId.
 *
 * <p>reason: NOT_LINKED(행 없음) · UNLINKED(사용자 해제) · BROKEN(갱신 거부) ·
 * REFRESH_UNAVAILABLE(즉석 갱신 일시 실패 — <b>임박한 토큰은 주지 않는다</b>).
 */
public record YoutubeResolveResult(boolean valid, String channelId, String accessToken, Instant expiresAt,
                                   String reason) {

    static YoutubeResolveResult rejected(String reason) {
        return new YoutubeResolveResult(false, null, null, null, reason);
    }
}
