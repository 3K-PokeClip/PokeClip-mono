package com.pokeclip.core.auth.api.dto;

import jakarta.validation.constraints.NotBlank;

public record GoogleLoginRequest(@NotBlank String code) {
}
