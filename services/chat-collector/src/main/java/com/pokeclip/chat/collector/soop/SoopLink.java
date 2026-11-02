package com.pokeclip.chat.collector.soop;

import java.time.Instant;

/**
 * auth가 준 SOOP 연동(POK-397). 받았거나, 못 받았거나. 못 받았으면 다시 물을 값어치가 있는지가 같이 온다
 * ({@code link.LinkResolution}과 같은 꼴).
 *
 * <p>🔴 {@link #toString()}을 덮는다. 기본 toString은 토큰 원문과 SOOP 아이디를 싣는데, record를 로그 {@code {}}에 넣는 순간 샌다.
 */
public record SoopLink(boolean usable, String soopUserId, String accessToken, Instant expiresAt, boolean retryable) {

    static SoopLink granted(String soopUserId, String accessToken, Instant expiresAt) {
        return new SoopLink(true, soopUserId, accessToken, expiresAt, false);
    }

    static SoopLink refused(boolean retryable) {
        return new SoopLink(false, null, null, null, retryable);
    }

    @Override
    public String toString() {
        return "SoopLink[usable=" + usable + ", retryable=" + retryable + ", expiresAt=" + expiresAt + "]";
    }
}
