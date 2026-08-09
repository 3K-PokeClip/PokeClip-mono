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

    /**
     * 구독 401·403. <b>재시도해도 영원히 안 풀린다.</b>
     *
     * <p>발급 쪽과 값을 나눈 이유는 <b>단계가 다른 것을 말해 주기</b> 때문이다 —
     * 발급이 200인데 구독만 거부됐다면 토큰 자체는 살아 있고 <b>채팅 Scope나
     * 동의</b>가 빠진 것이다. 뭉치면 로그가 "토큰이 죽었다"고만 말해 사람이
     * 토큰부터 다시 발급받는다.
     *
     * <p><b>거부 사유를 더 쪼개지는 않는다</b> — 만료·철회·Scope 부족이 두
     * 엔드포인트에서 똑같이 {@code {"code":401,"message":"INVALID_TOKEN"}}이다.
     */
    SUBSCRIBE_REJECTED,
    /** 5xx·네트워크 등 그 밖의 구독 실패. 다시 걸면 풀릴 수 있다. */
    SUBSCRIBE_FAILED,

    /** ping이 안 나간다. 소켓이 죽었다 */
    PING_SEND_FAILED,
    /** ping 송신을 우리가 잘못 썼다. <b>재연결하지 않는다</b> */
    SEND_MISUSE,
    /** ping은 나가는데 pong이 임계를 넘도록 안 온다 — 좀비 연결 */
    PONG_TIMEOUT,

    /**
     * 동의 철회·Scope 변경. <b>재시도해도 영원히 안 풀린다</b> —
     * 다시 붙어도 서버가 구독을 또 취소한다.
     */
    REVOKED,
    TRANSPORT_CLOSED
}
