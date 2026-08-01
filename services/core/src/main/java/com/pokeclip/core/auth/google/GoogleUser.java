package com.pokeclip.core.auth.google;

public record GoogleUser(String sub, String email, String name, String profileImageUrl) {
}
