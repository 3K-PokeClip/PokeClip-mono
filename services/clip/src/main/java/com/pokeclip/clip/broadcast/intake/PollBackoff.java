package com.pokeclip.clip.broadcast.intake;

import java.time.Duration;

/**
 * 폴링이 실패했을 때 얼마나 쉴지. <b>순수 판정이라 시간과 소켓 없이 검사한다</b>
 * (chat-collector {@code ReconnectPolicy}와 같은 모양 — 모듈이 달라 import는 못 한다).
 *
 * <p>백오프가 없으면 큐에 못 닿는 동안 루프가 쉬지 않고 돈다. 롱폴링 20초는
 * <b>연결이 성립한 뒤의 이야기</b>라, IAM 권한이 빠졌거나 없는 큐를 가리키면
 * {@code receiveMessage}가 즉시 던지고 곧바로 다음 회차가 시작된다 —
 * 코어 하나가 100%로 돌고 로그가 초당 수백 줄 쌓인다.
 */
final class PollBackoff {

    private final Duration firstDelay;
    private final Duration maxDelay;

    PollBackoff(Duration firstDelay, Duration maxDelay) {
        this.firstDelay = firstDelay;
        this.maxDelay = maxDelay;
    }

    /**
     * @param consecutiveFailures 1부터 센다
     * @return 두 배씩 늘어나되 상한에서 멈춘다. <b>상한이 없으면 오래 끊긴 뒤
     *         복구가 몇 시간 뒤가 된다</b>
     */
    Duration delayFor(int consecutiveFailures) {
        // 시프트로 하면 31을 넘길 때 음수가 되어 대기가 통째로 사라진다.
        // 곱하면서 상한에 닿는 즉시 멈춘다.
        Duration delay = firstDelay;
        for (int i = 1; i < consecutiveFailures && delay.compareTo(maxDelay) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }
}
