package com.pokeclip.auth.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * 10분마다 표 셋을 순서대로 지운다(POK-89). 판정·삭제는 {@link RetentionCleaner}가 하고 여기는 순서·로그만 안다.
 *
 * <p><b>여기에 {@code @Transactional}을 붙이지 않는다</b> — cleaner의 메서드가 각각 트랜잭션 최상단이어야 한다.
 * 붙이면 한 표의 예외가 트랜잭션을 rollback-only로 만들어 <b>catch해도 틱 전체가 마지막에 터지고, 앞서 성공한
 * 표의 삭제까지 사라진다.</b> {@code RetentionCleanupSchedulerIntegrationTest}가 그 자리를 잰다.
 *
 * <p>순서는 시도 기록 → 코드 → refresh다. 셋은 서로 독립이라 순서에 의미는 없고, 하나가 터져도 다음으로 간다.
 * 스케줄러 스레드가 하나라 이 틱과 치지직 갱신·유튜브 점검 틱이 같은 줄에 선다 — 표당 상한이 그 예산이다.
 *
 * <p><b>상한에 걸리면 같은 표를 같은 틱에서 이어 부른다</b>(사용자 결정 2026-09-03). 교환 창구는 로그인이 없고
 * 429도 행을 남겨 한 IP가 초당 수백 행을 만들 수 있다(리뷰 라운드 1 실측: advisory lock + INSERT + count 한 건
 * 0.31ms). 10분에 1,000행이면 폭주 중 표가 자란다. 바퀴 상한 {@link #MAX_ROUNDS}가 있어야 한 표가 이 스레드를
 * 독점하지 않는다: 표당 한 틱 최대 {@code MAX_ROUNDS × batch-limit}행. 바퀴 중간에 던지면 앞 바퀴의 삭제는
 * 이미 커밋됐으므로(바퀴마다 자기 트랜잭션) {@code failed} 줄이 그때까지의 실적({@code deleted}·{@code rounds})을
 * 같이 싣는다 — 안 실으면 운영자가 「청소가 통째로 안 돌았다」로 읽는다. 바퀴 반복이 없던 시절에는 예외 = 삭제 0이라
 * 그 오독이 참이었다(리뷰 라운드 2).
 *
 * <p>{@code matchIfMissing = true} — 운영에서 프로퍼티를 빠뜨려도 조용히 꺼지지 않는다. 꺼짐은 명시적으로만.
 */
@Component
@ConditionalOnProperty(prefix = "pokeclip.retention", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RetentionCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupScheduler.class);

    /** 한 틱에 한 표를 이어 부르는 최대 바퀴. 프로퍼티로 빼지 않았다(사용자 결정): 늘릴 값은 {@code batch-limit}이다. */
    static final int MAX_ROUNDS = 10;

    private final RetentionCleaner cleaner;

    public RetentionCleanupScheduler(RetentionCleaner cleaner) {
        this.cleaner = cleaner;
    }

    /** initialDelay도 준다 — 부팅 직후 첫 틱이 컨텍스트 로딩과 겹치지 않게. */
    @Scheduled(fixedDelayString = "${pokeclip.retention.interval}",
            initialDelayString = "${pokeclip.retention.interval}")
    public void tick() {
        Instant now = Instant.now();
        run("pairing_exchange_attempts", () -> cleaner.cleanPairingAttempts(now));
        run("pairing_codes", () -> cleaner.cleanPairingCodes(now));
        run("refresh_tokens", () -> cleaner.cleanRefreshTokens(now));
    }

    /**
     * 로그는 바퀴마다가 아니라 표당 한 줄(합계·바퀴 수·마지막 바퀴가 상한에 걸렸나). 0건이면 안 찍는다(10분마다
     * 빈 줄 셋을 안 남긴다). 실패 원인은 타입 이름만(값 유출 방지, 다른 스케줄러와 같다). 실패 줄에도
     * {@code deleted}·{@code rounds}를 싣는다 — 앞 바퀴의 삭제는 이미 커밋됐다.
     */
    private void run(String table, Supplier<RetentionCleaner.Result> job) {
        int deleted = 0;
        int rounds = 0;
        try {
            boolean capped;
            do {
                RetentionCleaner.Result result = job.get();
                deleted += result.deleted();
                rounds++;
                capped = result.capped();
            } while (capped && rounds < MAX_ROUNDS);
            if (deleted > 0) {
                log.info("auth.retention.cleaned table={} deleted={} rounds={} capped={}",
                        table, deleted, rounds, capped);
            }
        } catch (RuntimeException e) {
            log.warn("auth.retention.failed table={} deleted={} rounds={} causeType={}",
                    table, deleted, rounds, e.getClass().getSimpleName());
        }
    }
}
