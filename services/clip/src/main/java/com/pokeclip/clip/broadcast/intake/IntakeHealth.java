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
        Instant last = now.lastPollSucceededAt();
        if (last == null || last.isBefore(Instant.now().minus(staleAfter))) {
            return Health.down()
                    .withDetail("status", "stalled")
                    .withDetail("lastPollSucceededAt", last == null ? "never" : last.toString())
                    .withDetail("lastFailureReason",
                            now.lastFailureReason() == null ? "UNKNOWN" : now.lastFailureReason())
                    .build();
        }
        return Health.up()
                .withDetail("status", "polling")
                .withDetail("lastPollSucceededAt", last.toString())
                .build();
    }
}
