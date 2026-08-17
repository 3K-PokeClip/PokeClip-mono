package com.pokeclip.auth.chzzk.api.dto;

import jakarta.validation.constraints.NotNull;

/** 수집기(chat-collector)가 회원 번호만 보낸다. */
public record ChzzkResolveRequest(@NotNull Long userId) {
}
