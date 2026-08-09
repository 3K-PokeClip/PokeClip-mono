package com.pokeclip.chat.collector.engineio;

/**
 * ping 송신 실패. <b>원인을 가르는 것이 이 클래스의 존재 이유다.</b>
 *
 * <p>가르지 않으면 <b>우리 코드 버그로 재연결이 돌고, 그러면 버그는 영영 안 보이는데
 * 연결 상한만 태운다.</b> 자동 복구가 원인을 덮는 구조다.
 */
public final class PingFailure extends RuntimeException {

    public enum Cause {
        /** 소켓이 죽었다. 재연결한다 */
        CONNECTION_DEAD,
        /** 우리가 잘못 썼다(이전 송신이 끝나기 전 다음 송신). <b>재연결하지 않는다</b> */
        MISUSE
    }

    private final Cause cause;

    public PingFailure(Cause cause, Throwable actual) {
        // 메시지에 URI를 담지 않는다 — 쿼리에 auth 토큰이 있다.
        super("ping 송신 실패 cause=" + cause + " type=" + actual.getClass().getSimpleName(), actual);
        this.cause = cause;
    }

    public Cause cause() { return cause; }
}
