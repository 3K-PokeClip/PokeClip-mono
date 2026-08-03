package com.pokeclip.auth.api.dto;

import com.pokeclip.auth.user.User;

public record MeResponse(Long id, String email, String name, String profileImageUrl) {

    public static MeResponse from(User user) {
        return new MeResponse(user.getId(), user.getEmail(), user.getName(), user.getProfileImageUrl());
    }
}
