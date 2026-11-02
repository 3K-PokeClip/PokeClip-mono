package com.pokeclip.auth.soop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
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
 * <p>🔴 <b>연속 거절 브레이크.</b> 세는 것은 갱신기와 {@link SoopRefreshBrake}다(스케줄러·resolve 두 경로 모두, PR #329 봇 지적).
 * 여기서는 브레이크가 서면 그 틱의 남은 회원을 안 부르고, 선 뒤에는 틱을 서지 않는다(프로세스 수명 동안).
 *
 * <p>한 틱은 오래 확인 안 한 순 앞 {@code batchLimit}(25)명만 본다: 스프링 기본 스케줄러는 스레드 하나라 치지직·유튜브·청소
 * 틱과 나눈다. 대가: SOOP 장애가 길면 앞 25가 매 틱 실패해(확인 시각이 안 바뀐다) 뒤쪽은 장애가 끝난 뒤 확인된다
 * (유튜브 철회 점검의 알려진 구멍과 같은 성질). 놓친 철회는 resolve 즉석 갱신이 발견한다.
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
                now.minus(properties.check().staleness()), PageRequest.of(0, properties.check().batchLimit()));
        for (Long userId : candidates) {
            try {
                refresher.refreshForCheck(userId);
            } catch (RuntimeException e) {
                // 한 회원의 예외가 다음 회원을 막지 않는다. 원인은 타입 이름만.
                log.warn("auth.soop.link.refresh_tick_failed userId={} causeType={}", userId, e.getClass().getSimpleName());
            }
            if (brake.engaged()) {
                return;   // 이 틱의 남은 회원도 안 부른다(브레이크를 세운 ERROR 줄은 SoopRefreshBrake가 남겼다)
            }
        }
    }
}
