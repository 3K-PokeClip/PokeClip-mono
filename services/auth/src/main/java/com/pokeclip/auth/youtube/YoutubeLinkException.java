package com.pokeclip.auth.youtube;

/** 메시지는 한국어라 로그에 넣지 않는다. 접근자는 손으로 — ChzzkLinkException과 같은 모양. */
public class YoutubeLinkException extends RuntimeException {

    private final YoutubeLinkFailure failure;

    public YoutubeLinkException(YoutubeLinkFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public YoutubeLinkFailure failure() {
        return failure;
    }
}
