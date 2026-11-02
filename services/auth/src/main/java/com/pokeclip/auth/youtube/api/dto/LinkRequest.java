package com.pokeclip.auth.youtube.api.dto;

import jakarta.validation.constraints.NotBlank;

/** @Size·@Pattern을 걸지 않는다 — 바인딩 실패 리포트가 거부된 code·state를 평문으로 찍는다. */
public record LinkRequest(@NotBlank String code, @NotBlank String state) {
}
