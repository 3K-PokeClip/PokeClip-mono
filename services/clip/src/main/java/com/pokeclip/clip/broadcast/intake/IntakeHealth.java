package com.pokeclip.clip.broadcast.intake;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 수신이 멈춘 것을 헬스체크에 드러낸다. M2 클립 파이프라인 전체가 이 통로 위에
 * 서므로, 조용히 안 돌고 있으면 한참 뒤에야 발견된다. 시작 로그 한 줄은 운영에서
 * 묻힌다.
 *
 * <p><b>꺼져 있는 것은 실패가 아니라 설정이라 UP이다</b> — 로컬·CI의 기본 상태를
 * DOWN으로 두면 빨간불이 일상이 되어 신호가 무뎌진다. 다만 상세에 적는다.
 * chat-collector CollectorHealth와 같은 구분이다.
 *
 * <p>빈 이름을 못 박아 {@code /actuator/health}의 항목 이름을
 * {@code broadcastIntake}로 만든다 — 기본값(클래스 이름)은 {@code intakeHealth}라
 * 무엇을 재는 통로인지가 안 드러난다.
 */
@Component("broadcastIntake")
public class IntakeHealth implements HealthIndicator {

    /** 롱폴링이 20초라, 이보다 오래 성공이 없으면 도는 게 아니다. */
    static final Duration STALE_AFTER = Duration.ofMinutes(2);

    private final IntakeStatus status;
    private final Duration staleAfter;

    // 생성자가 둘이면 Spring은 가시성으로 가르지 않는다 — 후보를 못 정하면
    // 기본 생성자를 찾고, 없으면 "No default constructor found"로 컨텍스트가
    // 통째로 무너진다(plan-critic 실측: clip의 스프링 테스트 11건 실패).
    // 어느 것을 쓸지 애노테이션으로 못 박는다.
    @Autowired
    public IntakeHealth(IntakeStatus status) {
        this(status, STALE_AFTER);
    }

    IntakeHealth(IntakeStatus status, Duration staleAfter) {
        this.status = status;
        this.staleAfter = staleAfter;
    }

    @Override
    public Health health() {
        // 한 번만 읽는다 — 낱개로 이어 읽으면 갈래를 고른 뒤 값이 바뀐다.
        IntakeStatus.Snapshot now = status.snapshot();

        if (!now.enabled()) {
            return Health.up().withDetail("status", "disabled").build();
        }
        Instant deadline = Instant.now().minus(staleAfter);
        Instant last = now.lastPollSucceededAt();

        if (last != null && !last.isBefore(deadline)) {
            // UP인 동안에도 진행 중인 실패를 드러낸다. 마지막 성공에서 2분이 지나기
            // 전까지는 UP이 맞지만(그 사이는 일시적 실패와 구분이 안 된다), 사유까지
            // 감추면 운영자가 시각을 직접 빼서 유추해야 한다.
            return withFailureReason(Health.up()
                    .withDetail("status", "polling")
                    .withDetail("lastPollSucceededAt", last.toString()), now);
        }

        // 아직 한 번도 성공하지 못했다면 "기동 중"일 수 있다. 롱폴링이 20초라
        // enabled=true로 뜨면 첫 회차가 끝나기 전까지 성공 기록이 없는데, 그것만
        // 보고 DOWN을 내면 배포의 기동 판정이 첫 회차를 실패로 읽는다 —
        // 오케스트레이터가 재시작하고 다시 같은 창이 열려, 코드는 멀쩡한데 기동이
        // 영영 안 끝나는 재시작 루프가 된다.
        //
        // 루프가 시작조차 안 했으면(null) 유예를 주지 않는다. enabled=true인데
        // 루프가 없는 것은 배선이 끊긴 것이고, 그때 UP으로 두면 폴링이 영원히 안
        // 도는데 헬스체크만 초록이 된다 — 이 카드가 처음부터 막으려던 상태다.
        Instant startedAt = now.loopStartedAt();
        if (last == null && startedAt != null && !startedAt.isBefore(deadline)) {
            return withFailureReason(Health.up()
                    .withDetail("status", "starting")
                    .withDetail("loopStartedAt", startedAt.toString()), now);
        }

        return Health.down()
                .withDetail("status", "stalled")
                .withDetail("lastPollSucceededAt", last == null ? "never" : last.toString())
                .withDetail("lastFailureReason",
                        now.lastFailureReason() == null ? "UNKNOWN" : now.lastFailureReason())
                .build();
    }

    /** 실패한 적이 없으면 칸을 만들지 않는다 — 빈 값이 있으면 노이즈가 된다. */
    private Health withFailureReason(Health.Builder builder, IntakeStatus.Snapshot now) {
        if (now.lastFailureReason() != null) {
            builder.withDetail("lastFailureReason", now.lastFailureReason());
        }
        return builder.build();
    }
}
