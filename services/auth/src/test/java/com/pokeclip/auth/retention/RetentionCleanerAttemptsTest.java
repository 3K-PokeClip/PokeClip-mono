package com.pokeclip.auth.retention;

import com.pokeclip.auth.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「지워지는가」가 요점인 카드라 <b>빈 표에 대고 지우면 단언이 저절로 참이 된다</b>(카드 8/27 코멘트).
 * 그래서 경계 안·밖에 행을 심고 셋 다 센다 — 밖은 사라지고, 안은 남고, 합이 맞아야 한다.
 * 「심은 뒤 개수」를 따로 안 세는 이유: {@code deleted == 심은 밖 행 수}가 INSERT 수를 증명한다 — INSERT가 0행이면
 * {@code deleted}가 0이라 빨간불이다({@code retention-test-reality} 문항 2를 이 모양으로 지킨다).
 *
 * <p>시계를 하나로 — 행을 심는 시각도 청소의 기준도 시험이 만든 {@code base} 하나다. 도커 VM 시계가 호스트와
 * 어긋나도 경계가 안 흔들린다.
 */
class RetentionCleanerAttemptsTest extends IntegrationTestSupport {

    private final RetentionCleaner cleaner;
    private final RetentionProperties properties;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    RetentionCleanerAttemptsTest(RetentionCleaner cleaner, RetentionProperties properties, JdbcTemplate jdbc,
                                 TransactionTemplate tx) {
        this.cleaner = cleaner;
        this.properties = properties;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** 이 표는 자식이 없다. 그래도 남기면 다른 시험의 rate limit 판정(같은 IP 해시)이 흔들린다. */
    @BeforeEach
    @AfterEach
    void clear() {
        jdbc.update("DELETE FROM pairing_exchange_attempts");
    }

    @Test
    void 보관_기간이_지난_행만_사라진다() {
        Instant base = Instant.now();
        seedAttempts("stale", 3, base.minus(Duration.ofMinutes(61)));
        seedAttempts("fresh", 2, base.minus(Duration.ofMinutes(59)));

        RetentionCleaner.Result result = cleaner.cleanPairingAttempts(base);

        assertThat(result.deleted()).isEqualTo(3);
        assertThat(result.capped()).isFalse();
        assertThat(count("stale")).isZero();
        assertThat(count("fresh")).as("경계 안 행이 지워졌다 — rate limit 판정 창이 먹힌다").isEqualTo(2);
    }

    /** 상한 1,000은 프로퍼티다. 여기서는 그 값을 읽어 「상한 + 500」을 심는다 — 숫자를 두 곳에 안 적는다. */
    @Test
    void 한_번에_상한만큼만_지우고_나머지는_다음_호출이_이어서_지운다() {
        Instant base = Instant.now();
        int limit = properties.batchLimit();
        seedAttempts("stale", limit + 500, base.minus(Duration.ofMinutes(61)));

        RetentionCleaner.Result first = cleaner.cleanPairingAttempts(base);
        RetentionCleaner.Result second = cleaner.cleanPairingAttempts(base);
        RetentionCleaner.Result third = cleaner.cleanPairingAttempts(base);

        assertThat(first.deleted()).isEqualTo(limit);
        assertThat(first.capped()).as("상한에 걸렸으면 같은 틱이 이어서 부른다는 표시가 있어야 한다").isTrue();
        assertThat(second.deleted()).isEqualTo(500);
        assertThat(second.capped()).isFalse();
        assertThat(third.deleted()).isZero();
        assertThat(count("stale")).isZero();
    }

    /**
     * CI에서 위 시험이 「1,500」으로 흔들린 자리(POK-413). 고르는 질의가 바깥 행마다 다시 도는 계획을 강제해도 한 번에
     * 상한만큼만 지워야 한다. 계획은 그때의 표 통계가 고르므로 위 시험만으로는 이 갈래가 가끔만 재어진다.
     */
    @Test
    void 고르는_질의를_다시_도는_계획에서도_상한만큼만_지운다() {
        Instant base = Instant.now();
        int limit = properties.batchLimit();
        seedAttempts("stale", limit + 500, base.minus(Duration.ofMinutes(61)));

        RetentionCleaner.Result first = RescanPlan.forcing(tx, jdbc, () -> cleaner.cleanPairingAttempts(base));

        assertThat(first.deleted()).isEqualTo(limit);
        assertThat(count("stale")).isEqualTo(500);
    }

    /**
     * 대조: 옛 모양({@code IN} 안의 하위 질의)은 같은 강제 아래에서 상한을 넘긴다. 이것이 초록이어야 위 시험이 계획을
     * 실제로 바꿔서 재고 있다는 뜻이다(설정이 안 먹는 판에서는 위 시험이 저절로 초록이 된다).
     */
    @Test
    void 대조_옛_모양은_다시_도는_계획에서_상한을_넘긴다() {
        Instant base = Instant.now();
        int limit = properties.batchLimit();
        seedAttempts("stale", limit + 500, base.minus(Duration.ofMinutes(61)));
        Timestamp cutoff = Timestamp.from(base.minus(properties.pairingAttemptsKeepFor()));

        int deleted = RescanPlan.forcing(tx, jdbc, () -> jdbc.update("""
                DELETE FROM pairing_exchange_attempts WHERE id IN (
                    SELECT id FROM pairing_exchange_attempts WHERE attempted_at < ? ORDER BY id LIMIT ?
                    FOR UPDATE SKIP LOCKED)
                """, cutoff, limit));

        assertThat(deleted)
                .as("강제가 안 먹었다(PostgreSQL 판이 바뀌었나): 그러면 위 시험이 계획을 안 바꾸고 저절로 초록이다. RescanPlan을 다시 맞춘다")
                .isEqualTo(limit + 500);
    }

    @Test
    void 지울_것이_없으면_0건이고_capped가_아니다() {
        RetentionCleaner.Result result = cleaner.cleanPairingAttempts(Instant.now());

        assertThat(result.deleted()).isZero();
        assertThat(result.capped()).isFalse();
    }

    /** IP 해시 자리에 태그를 넣어 갈래를 가른다 — 실제 값은 SHA-256 hex 64자지만 이 표는 길이만 본다. */
    private void seedAttempts(String tag, int rows, Instant attemptedAt) {
        jdbc.update("INSERT INTO pairing_exchange_attempts (client_ip_hash, attempted_at) "
                        + "SELECT ?, ? FROM generate_series(1, ?)",
                tag, Timestamp.from(attemptedAt), rows);
    }

    private int count(String tag) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM pairing_exchange_attempts WHERE client_ip_hash = ?", Integer.class, tag);
    }
}
