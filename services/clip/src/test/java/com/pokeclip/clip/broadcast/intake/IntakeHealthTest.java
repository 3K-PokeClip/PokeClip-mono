package com.pokeclip.clip.broadcast.intake;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class IntakeHealthTest {

    private static final Duration STALE_AFTER = Duration.ofMinutes(2);

    @Test
    void 꺼둔_상태는_UP이고_상세에_꺼져_있음이_보인다() {
        Health health = new IntakeHealth(new IntakeStatus(false), STALE_AFTER).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("status", "disabled");
    }

    @Test
    void 켜져_있고_최근에_성공했으면_UP이다() {
        IntakeStatus status = new IntakeStatus(true);
        status.pollSucceeded(Instant.now());

        assertThat(new IntakeHealth(status, STALE_AFTER).health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void 켜져_있는데_한동안_성공이_없으면_DOWN이다() {
        IntakeStatus status = new IntakeStatus(true);
        status.pollSucceeded(Instant.now().minus(Duration.ofMinutes(10)));

        Health health = new IntakeHealth(status, STALE_AFTER).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("lastPollSucceededAt");
    }

    @Test
    void 켜졌는데_한_번도_성공한_적이_없으면_DOWN이다() {
        Health health = new IntakeHealth(new IntakeStatus(true), STALE_AFTER).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("lastPollSucceededAt", "never");
    }
}
