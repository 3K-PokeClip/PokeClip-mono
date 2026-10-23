package com.pokeclip.auth.soop.api.dto;

import java.time.Instant;

/** SOOP 아이디는 없다. 닉네임만 화면에 보인다. */
public record LinkResponse(String nickname, Instant linkedAt) {
}
