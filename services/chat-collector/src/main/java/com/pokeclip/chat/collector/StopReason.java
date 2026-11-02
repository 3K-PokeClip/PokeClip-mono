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
    TRANSPORT_CLOSED,

    /**
     * <b>치지직 연동이 없거나 끊겨 세션을 열어 보지도 못했다.</b> 위 값들과 달리 이것은
     * <b>세션 단계가 아니라 그 앞</b>이다 — auth가 열쇠를 영구히 거절해 발급 REST를 한 번도
     * 안 쳤다. <b>재시도해도 영원히 안 풀린다.</b>
     *
     * <p><b>auth 거절 사유 넷을 여기 뭉친다</b> — {@code UNLINKED}(스트리머가 해제) ·
     * {@code BROKEN}(치지직이 갱신을 거부, 재동의해야 풀린다) · {@code NOT_LINKED}(연동한 적 없음) ·
     * 계약 위반({@code valid:true}인데 채널·토큰·만료 중 하나가 빔). 스트리머 관점에서 넷 다
     * 「수집이 안 되고 치지직 연동을 손봐야 한다」로 같고, 그것이 창구의 {@code needsRelink}가
     * 뜻하는 바다. 가르려면 {@code LinkResolution}이 사유를 들고 와야 하는데({@code boolean
     * retryable} 하나뿐이다) 그것은 auth 계약(POK-93)을 건드리는 일이라 이 카드의 범위를 넘는다.
     * 계약 위반은 health 카운터({@code unreadableStreamerIds})가 이미 따로 드러낸다.
     *
     * <p><b>{@code NOT_LINKED}까지 넣어 「연동한 적 없는 정상 트래픽」에도 배너가 뜨는 것은
     * 감수한 것이다</b> — 안 뜨면 그 스트리머는 왜 채팅이 안 걷히는지 영영 모른다. 배너 문구가
     * 「다시」를 전제하면 그 사람에겐 어색해진다는 것을 {@code services/README.md}
     * 「2번이 알아야 할 것」에 적어 뒀다.
     */
    LINK_UNAVAILABLE
}
