package com.pokeclip.chat.collector;

/**
 * 수집이 멈춘 이유. 로그와 health에 이 이름 그대로 찍힌다.
 *
 * <p>영어로 두는 이유는 검색·집계 때문이다(auth의 AuthFailure와 같은 규칙).
 */
public enum StopReason {
    SESSION_AUTH_FAILED,
    CONNECT_FAILED,
    ESTABLISH_TIMEOUT,
    SUBSCRIBE_FAILED,
    /** 동의 철회·Scope 변경. 대응은 POK-93이고 여기서는 사실만 남긴다. */
    REVOKED,
    TRANSPORT_CLOSED
}
