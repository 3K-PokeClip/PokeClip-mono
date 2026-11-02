package com.pokeclip.clip.broadcast.intake;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 순수 계산이라 시간과 소켓 없이 잰다(chat-collector ReconnectPolicyTest와 같은 방식). */
class PollBackoffTest {

    private final PollBackoff backoff = new PollBackoff(Duration.ofSeconds(1), Duration.ofSeconds(60));

    @Test
    void 실패가_이어지면_두_배씩_늘어난다() {
        assertThat(backoff.delayFor(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(backoff.delayFor(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(backoff.delayFor(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(backoff.delayFor(4)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void 상한에서_멈춘다() {
        assertThat(backoff.delayFor(10)).isEqualTo(Duration.ofSeconds(60));
    }

    /**
     * 상한이 없으면 오래 끊긴 뒤 복구가 몇 시간 뒤가 된다. 시프트로 계산하면 31을
     * 넘길 때 음수가 되어 대기가 통째로 사라지므로, 아주 큰 횟수에서도 상한인지 본다.
     */
    @Test
    void 아주_오래_실패해도_상한을_넘거나_음수가_되지_않는다() {
        assertThat(backoff.delayFor(1000)).isEqualTo(Duration.ofSeconds(60));
        assertThat(backoff.delayFor(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(60));
    }
}
