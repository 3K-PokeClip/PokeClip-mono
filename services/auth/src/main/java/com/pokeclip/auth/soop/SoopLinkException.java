package com.pokeclip.auth.soop;

/** 메시지는 한국어라 로그에 넣지 않는다. 치지직 {@code ChzzkLinkException}과 같은 모양. */
public class SoopLinkException extends RuntimeException {

    private final SoopLinkFailure failure;

    public SoopLinkException(SoopLinkFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public SoopLinkFailure failure() {
        return failure;
    }
}
