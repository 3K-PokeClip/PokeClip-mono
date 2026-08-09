package com.pokeclip.chat.collector.reconnect;

import com.pokeclip.chat.collector.StopReason;

import java.time.Duration;

/**
 * 다시 붙어도 되는지, 얼마를 기다릴지. <b>순수 판정이라 시간과 소켓 없이 검사한다.</b>
 *
 * <p><b>연결 상한 초과는 재시도 불가에 넣지 않는다.</b> 영구 실패가 아니고, 그 증상이
 * connected가 안 오는 것이라 핸드셰이크 실패와 구분되지 않는다 —
 * 구분 못 하는 것을 분기 조건으로 쓸 수 없다. 백오프에 맡긴다.
 */
public final class ReconnectPolicy {

    private final Duration firstDelay;
    private final Duration maxDelay;

    public ReconnectPolicy(Duration firstDelay, Duration maxDelay) {
        this.firstDelay = firstDelay;
        this.maxDelay = maxDelay;
    }

    /**
     * 재시도해도 언젠가 풀릴 사유인가.
     *
     * <p><b>허용 목록이 아니라 거부 목록이다.</b> 사유가 새로 늘 때 기본값이
     * "재시도한다"여야 한다 — 채팅 유실이 유일한 치명 실패라, 모르는 사유를 붙잡고
     * 멈추는 쪽이 더 나쁘다.
     */
    public static boolean retriable(StopReason reason) {
        return switch (reason) {
            // 토큰이 거부됐거나 권한이 회수됐다. 다시 걸어도 영원히 같다
            case SESSION_AUTH_REJECTED, REVOKED -> false;
            // 우리가 잘못 쓴 것이다. 재연결하면 버그가 자동 복구에 덮인다
            case SEND_MISUSE -> false;
            default -> true;
        };
    }

    /**
     * @param attempt 1부터 센다
     * @return 두 배씩 늘어나되 상한에서 멈춘다. <b>상한이 없으면 오래 끊긴 뒤
     *         복구가 몇 시간 뒤가 된다</b>
     */
    public Duration delayFor(int attempt) {
        // 시프트로 하면 31을 넘길 때 음수가 되어 대기가 통째로 사라진다.
        // 곱하면서 상한에 닿는 즉시 멈춘다.
        Duration delay = firstDelay;
        for (int i = 1; i < attempt && delay.compareTo(maxDelay) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }
}
