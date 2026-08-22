package com.pokeclip.auth.delegation.api.dto;

import jakarta.validation.constraints.NotNull;

/** clip이 보낸다. 숫자 회원 번호(users.id). */
public record AccessibleStreamersRequest(@NotNull Long userId) {
}
