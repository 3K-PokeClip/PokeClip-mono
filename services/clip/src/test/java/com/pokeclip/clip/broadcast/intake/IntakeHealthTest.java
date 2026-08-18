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

    /**
     * <b>UP인 동안에도 진행 중인 실패가 응답에 보여야 한다.</b> 마지막 성공에서 2분이
     * 지나기 전까지는 UP이 맞지만(그 사이는 일시적 실패와 구분이 안 된다), 그동안
     * 실패 사유가 응답에 없으면 운영자가 시각을 직접 빼서 유추해야 한다 —
     * "12:01:30인데 마지막 성공이 12:00:00이네"를 사람이 계산하게 만드는 응답이다.
     */
    @Test
    void 켜져_있고_UP이어도_진행_중인_실패_사유가_상세에_보인다() {
        IntakeStatus status = new IntakeStatus(true);
        status.pollSucceeded(Instant.now());
        status.pollFailed("SdkClientException");

        Health health = new IntakeHealth(status, STALE_AFTER).health();

        assertThat(health.getStatus()).as("2분이 아직 안 지났으므로 UP이 맞다").isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .as("실패가 진행 중인데 응답에 아무 신호가 없다")
                .containsEntry("lastFailureReason", "SdkClientException");
    }

    /** 실패한 적이 없으면 그 칸을 만들지 않는다 — 빈 값이 있으면 노이즈가 된다. */
    @Test
    void 실패한_적이_없으면_실패_사유_칸이_아예_없다() {
        IntakeStatus status = new IntakeStatus(true);
        status.pollSucceeded(Instant.now());

        assertThat(new IntakeHealth(status, STALE_AFTER).health().getDetails())
                .doesNotContainKey("lastFailureReason");
    }

    @Test
    void 켜져_있는데_한동안_성공이_없으면_DOWN이다() {
        IntakeStatus status = new IntakeStatus(true);
        status.pollSucceeded(Instant.now().minus(Duration.ofMinutes(10)));

        Health health = new IntakeHealth(status, STALE_AFTER).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("lastPollSucceededAt");
    }

    /**
     * <b>부팅 직후 첫 회차가 끝나기 전은 실패가 아니다.</b> 롱폴링이 20초라
     * {@code enabled=true}로 뜨면 그동안 {@code lastPollSucceededAt}이 null인데,
     * 그것만 보고 DOWN을 내면 배포의 기동 판정이 첫 20초를 실패로 읽는다 —
     * 오케스트레이터가 컨테이너를 재시작하고 다시 20초 DOWN이 되어
     * <b>코드는 멀쩡한데 기동이 영영 안 끝나는 재시작 루프</b>가 된다.
     *
     * <p>가르는 기준은 "아직 한 번도 못 돌았다"와 "돌다가 멈췄다"의 구분이고,
     * 그 재료가 루프 시작 시각이다.
     */
    @Test
    void 켜졌고_아직_첫_회차_중이면_기동_중이라_UP이다() {
        IntakeStatus status = new IntakeStatus(true);
        status.loopStarted(Instant.now());

        Health health = new IntakeHealth(status, STALE_AFTER).health();

        assertThat(health.getStatus())
                .as("첫 회차가 끝나기 전을 DOWN으로 두면 기동 판정이 재시작 루프에 빠진다")
                .isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("status", "starting");
    }

    /**
     * 반대쪽. 기동 유예가 무한이면 "켜졌는데 영영 안 도는" 상태를 못 잡는다 —
     * 이 카드가 막으려는 실패가 바로 그것이다.
     */
    @Test
    void 켜졌고_시작했는데도_오래_첫_성공이_없으면_DOWN이다() {
        IntakeStatus status = new IntakeStatus(true);
        status.loopStarted(Instant.now().minus(Duration.ofMinutes(10)));

        Health health = new IntakeHealth(status, STALE_AFTER).health();

        assertThat(health.getStatus())
                .as("시작하고 한참인데 한 번도 못 돌았으면 진짜 문제다")
                .isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("lastPollSucceededAt", "never");
    }

    /**
     * 켜졌다고 했는데 루프가 아예 시작하지 않은 경우다 — 배선이 끊긴 상태이고,
     * 이 카드가 처음부터 잡으려던 결함(#4)의 증상이 정확히 이것이다. UP으로 두면
     * 폴링이 영원히 안 도는데 헬스체크만 초록이 된다.
     */
    @Test
    void 켜졌는데_루프가_시작조차_안_했으면_DOWN이다() {
        Health health = new IntakeHealth(new IntakeStatus(true), STALE_AFTER).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void 켜졌는데_한_번도_성공한_적이_없으면_DOWN이다() {
        Health health = new IntakeHealth(new IntakeStatus(true), STALE_AFTER).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("lastPollSucceededAt", "never");
    }
}
