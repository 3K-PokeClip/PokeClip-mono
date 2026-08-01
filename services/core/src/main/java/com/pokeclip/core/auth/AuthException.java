package com.pokeclip.core.auth;

/** 인증 실패. 전부 401로 응답한다. 실패 이유를 클라이언트에 나누어 알리지 않는다. */
public class AuthException extends RuntimeException {

    private final AuthFailure failure;

    public AuthException(AuthFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public AuthException(AuthFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    public AuthFailure failure() {
        return failure;
    }
}
