package com.pokeclip.auth.chzzk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 갱신 후보만 읽고 회원마다 {@link ChzzkTokenRefresher}를 부른다. 판정·저장은 모른다.
 *
 * <p>여기에 {@code @Transactional}을 붙이지 않는다 — refresher가 트랜잭션 최상단이어야 한다.
 * 후보는 id만 뽑는다(락 전 엔티티 읽기 금지). 후보 선별과 실제 갱신 사이에 다른 진입점(resolve)이
 * 먼저 갱신했으면 refresher가 락 뒤 재읽기로 SKIPPED_FRESH를 낸다.
 *
 * <p>{@code matchIfMissing = true} — 운영에서 프로퍼티를 빠뜨려도 조용히 꺼지지 않는다.
 * 꺼짐은 명시적으로만(테스트 프로파일).
 */
@Component
@ConditionalOnProperty(prefix = "pokeclip.chzzk.refresh", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ChzzkTokenRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChzzkTokenRefreshScheduler.class);

    private final ChzzkChannelLinkRepository links;
    private final ChzzkTokenRefresher refresher;
    private final ChzzkProperties properties;

    public ChzzkTokenRefreshScheduler(ChzzkChannelLinkRepository links, ChzzkTokenRefresher refresher,
                                      ChzzkProperties properties) {
        this.links = links;
        this.refresher = refresher;
        this.properties = properties;
    }

    /** initialDelay도 준다 — 부팅 직후 첫 틱이 컨텍스트 로딩과 겹치지 않게. */
    @Scheduled(fixedDelayString = "${pokeclip.chzzk.refresh.interval}",
            initialDelayString = "${pokeclip.chzzk.refresh.interval}")
    public void tick() {
        List<Long> candidates = links.findUserIdsExpiringBefore(Instant.now().plus(properties.refreshAhead()));
        for (Long userId : candidates) {
            try {
                refresher.refreshIfExpiringWithin(userId, properties.refreshAhead());
            } catch (RuntimeException e) {
                // 한 회원의 예외가 다음 회원을 막지 않는다. 원인은 타입 이름만.
                log.warn("auth.chzzk.link.refresh_tick_failed userId={} causeType={}",
                        userId, e.getClass().getSimpleName());
            }
        }
    }
}
