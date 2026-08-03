package com.pokeclip.auth.streamkey.api.dto;

import com.pokeclip.auth.streamkey.PairingCodeService.IssuedCode;

import java.time.Instant;

/** <b>{}에 통째로 넣지 않는다.</b> SecretLeakTest가 "PairingCodeResponse["를 금지한다. */
public record PairingCodeResponse(String code, Instant expiresAt) {

    public static PairingCodeResponse from(IssuedCode issued) {
        return new PairingCodeResponse(issued.code(), issued.expiresAt());
    }
}
