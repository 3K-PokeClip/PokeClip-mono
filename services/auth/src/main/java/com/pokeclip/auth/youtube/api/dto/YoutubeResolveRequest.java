package com.pokeclip.auth.youtube.api.dto;

import jakarta.validation.constraints.NotNull;

/** clip·워커가 회원 번호만 보낸다. */
public record YoutubeResolveRequest(@NotNull Long userId) {
}
