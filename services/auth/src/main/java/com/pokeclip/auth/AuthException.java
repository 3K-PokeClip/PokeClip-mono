package com.pokeclip.auth;

/**
 * 인증 실패. 기본은 401이고 이유를 클라이언트에 나누어 알리지 않는다.
 *
 * <p>예외가 하나 있다 — {@link AuthFailure#EMAIL_ALREADY_REGISTERED}는 409로 답하고
 * 이유도 알려 준다. 근거는 그 enum 상수의 주석에 있다.
 */
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
