package com.pokeclip.chat.collector.link;

import java.time.Instant;

/**
 * auth에게 물어본 결과. 열쇠를 받았거나, 못 받았거나 — 못 받았으면 <b>다시 물어볼 값어치가
 * 있는지</b>가 같이 온다.
 *
 * <p>이 구분이 시작 편지의 운명을 정한다. {@code retryable}이 거짓이면 편지를 지우고(연동을
 * 안 한 스트리머라 몇 번을 물어도 답이 같다), 참이면 남긴다.
 */
public record LinkResolution(boolean usable, String channelId, String accessToken,
                             Instant expiresAt, boolean retryable) {

    static LinkResolution granted(String channelId, String accessToken, Instant expiresAt) {
        return new LinkResolution(true, channelId, accessToken, expiresAt, false);
    }

    static LinkResolution refused(boolean retryable) {
        return new LinkResolution(false, null, null, null, retryable);
    }
}
