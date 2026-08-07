package com.pokeclip.chat.collector;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 수집이 멈춘 것을 헬스체크에 드러낸다.
 *
 * <p><b>수집이 죽었는데 health가 UP인 상태를 만들지 않는다.</b> 그러면 배포도
 * 헬스체크도 통과하는데 채팅만 안 들어오고, 원인을 가리키는 신호가 아무 데도
 * 안 남는다 — 이 서비스의 유일한 치명적 실패다.
 *
 * <p>꺼져 있는 것은 실패가 아니라 설정이라 UP이다. 다만 상세에 적어 둔다 —
 * "왜 채팅이 안 들어오지"의 첫 번째 답이 대개 이것이다.
 */
@Component
public class CollectorHealth implements HealthIndicator {

    private final CollectionStatus status;

    public CollectorHealth(CollectionStatus status) {
        this.status = status;
    }

    @Override
    public Health health() {
        return switch (status.state()) {
            case DISABLED -> Health.up().withDetail("status", "disabled").build();
            case ESTABLISHING -> Health.up().withDetail("status", "establishing").build();
            case COLLECTING -> Health.up().withDetail("status", "collecting").build();
            case STOPPED -> Health.down()
                    .withDetail("status", "stopped")
                    .withDetail("reason", status.reason() == null ? "UNKNOWN" : status.reason().name())
                    .build();
        };
    }
}
