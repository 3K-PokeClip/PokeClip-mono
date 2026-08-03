package com.pokeclip.auth.streamkey.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pokeclip.auth.streamkey.StreamKey;

import java.time.Instant;

/**
 * 긴 비밀이 절대 실리지 않는다. streamid 원문은 저장하지 않아 줄 수도 없고,
 * passphrase는 페어링 코드 교환으로만 나간다(ADR-019).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamKeyStatusResponse(boolean issued, Instant createdAt) {

    public static StreamKeyStatusResponse of(StreamKey key) {
        return new StreamKeyStatusResponse(true, key.getCreatedAt());
    }

    public static StreamKeyStatusResponse none() {
        return new StreamKeyStatusResponse(false, null);
    }
}
