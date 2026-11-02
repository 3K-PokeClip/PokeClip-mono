package com.pokeclip.auth.youtube;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 철회 점검. 「오래 확인 안 한 살아있는 연동」을 골라 갱신을 한 번 시도한다 — 사용자가 구글 쪽에서
 * 권한을 끊었거나 테스트 모드 refresh(7일)가 죽은 것을 <b>방송·업로드 직전이 아니라 미리</b> 드러내려고.
 *
 * <p>치지직({@code ChzzkTokenRefreshScheduler})과 <b>축이 다르다</b>. 치지직은 「만료 임박 선갱신」이었는데
 * 구글 access는 1시간짜리라 그 기준으로는 살아있는 행이 <b>늘 전부</b> 걸린다. 그래서 기준을
 * 「마지막 갱신 시각」으로 바꿨고, 요구 수명은 {@link Duration#ZERO}다 — 24시간 넘게 확인 안 한 행의
 * access는 이미 만료됐으므로 ZERO로도 실제 갱신이 일어나고, 철회됐으면 그 자리에서 BROKEN이 된다.
 * 별도 「강제 갱신」 경로를 만들지 않는 이유다(계획 2절 결정 5).
 *
 * <p><b>여기에 {@code @Transactional}을 붙이지 않는다</b> — {@link YoutubeTokenRefresher}가 트랜잭션
 * 최상단이어야 한다. 붙이면 ① 한 회원의 예외가 트랜잭션을 rollback-only로 만들어 <b>catch해도 틱 전체가
 * 마지막에 터지고</b> ② 거부 정리가 상위 롤백에 딸려가며 ③ 구글 HTTP가 상위 트랜잭션 수명에 묶인다.
 * 후보를 id만 뽑는 것도 같은 이유다(락 전 엔티티 읽기 금지).
 *
 * <p>{@code matchIfMissing = true} — 운영에서 프로퍼티를 빠뜨려도 조용히 꺼지지 않는다.
 * 꺼짐은 명시적으로만(테스트 프로파일).
 */
@Component
@ConditionalOnProperty(prefix = "pokeclip.youtube.check", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class YoutubeRevocationCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(YoutubeRevocationCheckScheduler.class);

    /**
     * 🔴 틱 하나가 처리하는 후보 상한. <b>스프링 기본 스케줄러는 스레드가 하나</b>다(실측: corePoolSize=1) —
     * 이 틱이 오래 돌면 <b>치지직 갱신 틱까지 밀린다</b>. 구글 장애로 회원마다 read-timeout(5s)을 먹으면
     * 후보 100명에 500초이고, 치지직 틱 주기가 10분이라 한 틱이 통째로 사라진다(봇 3판 P2-1).
     *
     * <p>25는 <b>최악 125초</b>(25 × 5s)로 치지직 10분 틱 안에 넉넉히 들어가는 값이다. 남은 후보는 다음 틱이
     * 가져간다 — 선별이 {@code lastRefreshedAt} 오름차순이라 오래된 것부터 순서대로 소진되고,
     * 틱이 1시간마다 도므로 회원 100명이면 하루 안에 전부 돈다(우리 전제는 동시 100명이다).
     *
     * <p>전용 executor 대신 상한을 고른 이유: 스레드를 늘리면 구글 호출이 <b>동시에</b> 나가 할당량을 더 빨리
     * 태우고, 이 작업은 급하지 않다(하루 한 번 확인이 목적이다).
     */
    static final int BATCH_LIMIT = 25;

    private final YoutubeChannelLinkRepository links;
    private final YoutubeTokenRefresher refresher;
    private final YoutubeProperties properties;

    public YoutubeRevocationCheckScheduler(YoutubeChannelLinkRepository links, YoutubeTokenRefresher refresher,
                                           YoutubeProperties properties) {
        this.links = links;
        this.refresher = refresher;
        this.properties = properties;
    }

    /** initialDelay도 준다 — 부팅 직후 첫 틱이 컨텍스트 로딩과 겹치지 않게. */
    @Scheduled(fixedDelayString = "${pokeclip.youtube.check.interval}",
            initialDelayString = "${pokeclip.youtube.check.interval}")
    public void tick() {
        List<Long> candidates = links.findUserIdsNotRefreshedSince(
                Instant.now().minus(properties.check().staleness()));
        if (candidates.size() > BATCH_LIMIT) {
            log.info("auth.youtube.link.check_batch_capped candidates={} limit={}", candidates.size(), BATCH_LIMIT);
            candidates = candidates.subList(0, BATCH_LIMIT);
        }
        for (Long userId : candidates) {
            try {
                refresher.refreshIfExpiringWithin(userId, Duration.ZERO);
            } catch (RuntimeException e) {
                // 한 회원의 예외가 다음 회원을 막지 않는다. 원인은 타입 이름만.
                log.warn("auth.youtube.link.check_tick_failed userId={} causeType={}",
                        userId, e.getClass().getSimpleName());
            }
        }
    }
}
