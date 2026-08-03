package com.pokeclip.auth.google;

public record GoogleUser(String sub, String email, String name, String profileImageUrl) {
}
