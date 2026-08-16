package com.pokeclip.auth.chzzk;

/** 메시지는 한국어라 로그에 넣지 않는다. 접근자는 손으로 — StreamKeyException과 같은 모양. */
public class ChzzkLinkException extends RuntimeException {

    private final ChzzkLinkFailure failure;

    public ChzzkLinkException(ChzzkLinkFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public ChzzkLinkFailure failure() {
        return failure;
    }
}
