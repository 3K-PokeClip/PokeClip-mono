package com.pokeclip.auth.streamkey.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pokeclip.auth.streamkey.StreamKey;

import java.time.Instant;

/**
 * 긴 비밀이 절대 실리지 않는다. streamid 원문은 저장하지 않아 줄 수도 없고,
 * passphrase는 페어링 코드 교환으로만 나간다(ADR-019).
 *
 * <p>{@code issued}는 <b>키 발급 여부이지 연결 여부가 아니다</b> — 키는 첫 코드 발급 때 생긴다.
 * 플러그인에 코드를 넣었는지는 {@code lastPairedAt}이 말한다(POK-607). 한 번도 안 넣었으면 칸이 없다.
 * 그 시각도 「지금 연결돼 있다」는 아니다({@code StreamKey.lastPairedAt}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamKeyStatusResponse(boolean issued, Instant createdAt, Instant lastPairedAt) {

    public static StreamKeyStatusResponse of(StreamKey key) {
        return new StreamKeyStatusResponse(true, key.getCreatedAt(), key.getLastPairedAt());
    }

    public static StreamKeyStatusResponse none() {
        return new StreamKeyStatusResponse(false, null, null);
    }
}
