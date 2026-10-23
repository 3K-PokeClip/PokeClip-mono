package com.pokeclip.auth.soop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 갱신 후보만 읽고 회원마다 {@link SoopTokenRefresher#refreshForCheck}를 부른다. 판정·저장은 모른다.
 * 후보 = 만료 임박이거나 마지막 확인이 staleness를 넘은 살아 있는 행(SOOP은 철회를 알려 주지 않아 갱신으로 확인한다).
 *
 * <p>여기에 {@code @Transactional}을 붙이지 않는다: refresher가 트랜잭션 최상단이어야 한다. 후보는 회원 번호만
 * 뽑는다(락 전 엔티티 읽기 금지).
 *
 * <p>🔴 <b>연속 거절 브레이크.</b> 한 틱에서 거절(REJECTED)이 연달아 {@code rejectBrake}번 나면 그 틱을 멈추고
 * <b>프로세스가 사는 동안 다시 틱을 서지 않는다</b>({@code refresh_reject_burst} ERROR 한 줄). 브레이크 상태는
 * {@link SoopRefreshBrake}에 있어 갱신기도 본다: 선 뒤에는 resolve의 즉석 갱신도 파기하지 않는다(감사 2회차 중대). 치지직·유튜브는 앱 설정
 * 오류(시크릿 오타)를 응답의 오류 코드로 가려 일시로 돌리는데, SOOP은 토큰 주소의 오류 꼴이 문서에 없어 그럴 수 없다.
 * 브레이크가 없으면 비밀값 하나가 틀린 배포가 틱 한 번에 전 회원 연동을 BROKEN으로 닫고 토큰까지 지운다(되돌릴 수 없다).
 * 대가: 브레이크 전에 거절된 {@code rejectBrake}명은 이미 닫혔다. 운영자가 원인을 고치고 재배포하면 풀린다.
 * 성공(REFRESHED·SKIPPED_FRESH)이 끼면 0으로 돌아가고, 일시 장애(UNAVAILABLE)·예외는 연속 수를 그대로 둔다
 * (설정 오류면 5xx가 섞여도 멈춰야 한다, 계획 검증 M9). 연속 수는 틱마다 새로 센다.
 *
 * <p>{@code matchIfMissing = true}: 운영에서 프로퍼티를 빠뜨려도 조용히 꺼지지 않는다. 꺼짐은 명시적으로만(시험 프로파일).
 */
@Component
@ConditionalOnProperty(prefix = "pokeclip.soop.check", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SoopTokenRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(SoopTokenRefreshScheduler.class);

    private final SoopChannelLinkRepository links;
    private final SoopTokenRefresher refresher;
    private final SoopProperties properties;
    private final SoopRefreshBrake brake;

    public SoopTokenRefreshScheduler(SoopChannelLinkRepository links, SoopTokenRefresher refresher,
                                     SoopProperties properties, SoopRefreshBrake brake) {
        this.links = links;
        this.refresher = refresher;
        this.properties = properties;
        this.brake = brake;
    }

    /** initialDelay도 준다: 부팅 직후 첫 틱이 컨텍스트 로딩과 겹치지 않게. */
    @Scheduled(fixedDelayString = "${pokeclip.soop.check.interval}",
            initialDelayString = "${pokeclip.soop.check.interval}")
    public void tick() {
        if (brake.engaged()) {
            return;
        }
        Instant now = Instant.now();
        List<Long> candidates = links.findIdsExpiringOrStale(now.plus(properties.refreshAhead()),
                now.minus(properties.check().staleness()), Pageable.unpaged());
        int consecutiveRejects = 0;
        for (Long userId : candidates) {
            RefreshOutcome outcome;
            try {
                outcome = refresher.refreshForCheck(userId).outcome();
            } catch (RuntimeException e) {
                // 한 회원의 예외가 다음 회원을 막지 않는다. 원인은 타입 이름만. 연속 수는 그대로 둔다.
                log.warn("auth.soop.link.refresh_tick_failed userId={} causeType={}", userId, e.getClass().getSimpleName());
                continue;
            }
            if (outcome == RefreshOutcome.REJECTED) {
                consecutiveRejects++;
                if (consecutiveRejects >= properties.check().rejectBrake()) {
                    brake.engage();
                    // 조치를 로그에 같이 둔다(값은 안 찍는다). 이 줄 하나가 운영자가 받는 신호다.
                    log.error("auth.soop.link.refresh_reject_burst consecutive={} lastUserId={} "
                                    + "action=check_SOOP_CLIENT_ID_SOOP_CLIENT_SECRET_SOOP_REDIRECT_URI_then_redeploy",
                            consecutiveRejects, userId);
                    return;
                }
            } else if (outcome == RefreshOutcome.REFRESHED || outcome == RefreshOutcome.SKIPPED_FRESH) {
                consecutiveRejects = 0;
            }
        }
    }
}
