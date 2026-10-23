package com.pokeclip.auth.soop.api.dto;

import jakarta.validation.constraints.NotNull;

/** 수집기가 회원 번호만 보낸다. */
public record SoopResolveRequest(@NotNull Long userId) {
}
