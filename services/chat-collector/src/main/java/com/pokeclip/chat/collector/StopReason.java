package com.pokeclip.chat.collector;

/**
 * 수집이 멈춘 이유. 로그와 health에 이 이름 그대로 찍힌다.
 *
 * <p>영어로 두는 이유는 검색·집계 때문이다(auth의 AuthFailure와 같은 규칙).
 */
public enum StopReason {
    /**
     * 401·403. <b>재시도해도 영원히 안 풀린다.</b>
     *
     * <p>만료·철회·Scope 부족을 더 쪼개지 않는다. 실서버가 셋 다
     * {@code {"code":401,"message":"INVALID_TOKEN"}}으로 똑같이 답한다(2026-08-08 실측).
     * 응답으로 구분할 수 없다는 것이 관측 결과다.
     */
    SESSION_AUTH_REJECTED,
    /** 5xx·네트워크 등 그 밖의 발급 실패. 다시 걸면 풀릴 수 있다. */
    SESSION_AUTH_FAILED,

    // --- 아래 셋은 진단용 구분이다. 재시도 판단에는 안 쓴다 ---
    /** WS 접속이 시한 안에 안 끝났다. */
    CONNECT_TIMEOUT,
    /** DNS·TLS·거부 등 접속 자체가 성립하지 않았다. */
    CONNECT_REFUSED,
    /** 위 둘로 못 가른 접속 실패. */
    CONNECT_FAILED,

    ESTABLISH_TIMEOUT,
    SUBSCRIBE_FAILED,

    /** ping이 안 나간다. 소켓이 죽었다 */
    PING_SEND_FAILED,
    /** ping 송신을 우리가 잘못 썼다. <b>재연결하지 않는다</b> */
    SEND_MISUSE,

    /** 동의 철회·Scope 변경. 대응은 POK-93이고 여기서는 사실만 남긴다. */
    REVOKED,
    TRANSPORT_CLOSED
}
