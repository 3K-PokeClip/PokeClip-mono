package com.pokeclip.auth.profile;

/** 메시지는 한국어라 로그에도 본문에도 넣지 않는다. 접근자는 손으로 — YoutubeLinkException과 같은 모양. */
public class ProfileUpdateException extends RuntimeException {

    private final ProfileUpdateFailure failure;

    public ProfileUpdateException(ProfileUpdateFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public ProfileUpdateFailure failure() {
        return failure;
    }
}
