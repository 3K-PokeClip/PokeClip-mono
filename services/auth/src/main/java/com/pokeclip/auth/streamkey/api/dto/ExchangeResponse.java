package com.pokeclip.auth.streamkey.api.dto;

import com.pokeclip.auth.streamkey.StreamKeyMaterial;

/**
 * 플러그인이 받는 최종 자격증명. <b>{}에 통째로 넣지 않는다.</b>
 * SecretLeakTest가 "ExchangeResponse["를 금지한다.
 */
public record ExchangeResponse(String streamid, String passphrase) {

    public static ExchangeResponse from(StreamKeyMaterial material) {
        return new ExchangeResponse(material.streamId().toSrtFormat(), material.passphrase());
    }
}
