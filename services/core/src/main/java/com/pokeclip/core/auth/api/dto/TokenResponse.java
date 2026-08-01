package com.pokeclip.core.auth.api.dto;

import com.pokeclip.core.auth.token.TokenPair;

public record TokenResponse(String accessToken, String refreshToken) {

    public static TokenResponse from(TokenPair pair) {
        return new TokenResponse(pair.accessToken(), pair.refreshToken());
    }
}
