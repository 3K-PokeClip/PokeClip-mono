package com.pokeclip.clip.broadcast.intake;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class IntakeHealthTest {

    private static final Duration STALE_AFTER = Duration.ofMinutes(2);

    /**
     * <b>운영 기본값을 재는 유일한 갈래다.</b> 아래 검사들은 시한을 인자로 넘기는데,
     * 넘기는 그 이유로 {@code STALE_AFTER}가 아무 검증도 안 받았다 —
     * 2분을 10000일로 바꿔도 38개가 전부 통과했다(감사 2차 지적).
     * 그러면 폴링이 멈춰도 health가 영원히 UP인데 시험은 조용하고,
     * PRD 성공 기준 「켜졌는데 멈추면 DOWN」이 <b>운영에서만</b> 깨진다.
     *
     * <p>그래서 이 갈래만 시한을 안 넘기고 {@code @Autowired} 생성자로 만든다 —
     * 스프링이 실제로 쓰는 그 인스턴스다. 경계 양쪽을 다 재야 한다:
     * 위만 재면 시한을 0으로 줄여도 통과하고, 아래만 재면 무한대로 늘려도 통과한다.
     */
    @Test
    void 운영_기본_시한_2분의_양쪽에서_갈린다() {
        IntakeStatus justPolled = new IntakeStatus(true);
        justPolled.pollSucceeded(Instant.now().minus(Duration.ofSeconds(90)));

        assertThat(new IntakeHealth(justPolled).health().getStatus())
                .as("2분 안에 성공했는데 DOWN이면 시한이 너무 짧다")
                .isEqualTo(Status.UP);

        IntakeStatus stalled = new IntakeStatus(true);
        stalled.pollSucceeded(Instant.now().minus(Duration.ofMinutes(3)));

        assertThat(new IntakeHealth(stalled).health().getStatus())
                .as("2분 넘게 성공이 없는데 UP이면 멈춘 것을 영영 못 드러낸다")
                .isEqualTo(Status.DOWN);
    }

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
