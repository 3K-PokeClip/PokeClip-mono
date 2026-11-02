package com.pokeclip.auth.withdrawal;

import com.pokeclip.auth.support.IntegrationTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>V115 배포 전에 이미 탈퇴한 회원의 채널 이름·번호도 비운다</b>(POK-341, PR #249 codex P1).
 * 그 회원은 다시 탈퇴할 수 없어(이미 탈퇴했으면 {@code WithdrawalService.withdraw}가 바로 돌아간다)
 * 새 비우기 코드에 영영 안 닿는다. 그래서 마이그레이션이 한 번 비운다.
 *
 * <p>공용 스키마는 이미 최신이라 V114 시점을 못 만든다. <b>빈 스키마 하나에 V114까지만 올리고</b> 옛 행을 심은 뒤
 * 나머지를 올린다. 스키마는 끝나면 지운다.
 */
class LinkChannelBackfillMigrationTest extends IntegrationTestSupport {

    private static final String SCHEMA = "pok341_backfill";

    private final DriverManagerDataSource dataSource =
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    @AfterEach
    void 스키마를_지운다() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    void 장부_있는_옛_탈퇴자의_닫힌_행만_비우고_장부_없는_탈퇴자와_안_탈퇴한_회원은_그대로_둔다() {
        flyway("114").migrate();
        long withdrawn = user("withdrawn", true);
        purgeJob(withdrawn);
        long beforeLedger = user("before-ledger", true);
        long member = user("member", false);
        for (String table : new String[] {"chzzk_channel_links", "youtube_channel_links"}) {
            link(table, withdrawn, "chan-withdrawn-old", true);
            // V114(10-08) 전 탈퇴자: 정리 장부가 없어 채널 번호가 수집기 정리의 마지막 단서다.
            link(table, beforeLedger, "chan-before-ledger", true);
            // 재연동·해제로 닫힌 행. 탈퇴가 아니라 연동 해제라 남아야 한다(POK-341 범위 밖, POK-336).
            link(table, member, "chan-member-old", true);
            link(table, member, "chan-member", false);
        }

        flyway(null).migrate();

        for (String table : new String[] {"chzzk_channel_links", "youtube_channel_links"}) {
            assertThat(rowsWithChannel(table, withdrawn))
                    .as("🔴 " + table + ": V115 전에 탈퇴한 회원의 채널 이름·번호가 남았다").isZero();
            assertThat(rowsWithChannel(table, beforeLedger))
                    .as("🔴 " + table + ": 정리 장부가 없는 탈퇴자의 채널 번호를 비웠다. 수집기 정리 단서가 사라진다")
                    .isEqualTo(1);
            assertThat(rowsWithChannel(table, member))
                    .as("🔴 " + table + ": 탈퇴 안 한 회원의 닫힌 행이나 살아 있는 행까지 비웠다").isEqualTo(2);
        }
    }

    // ── 도구 ────────────────────────────────────────────────────────────

    private Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .table("flyway_schema_history_auth")
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private long user(String name, boolean withdrawn) {
        return jdbc.queryForObject("INSERT INTO " + SCHEMA + ".users"
                        + " (google_sub, email, name, created_at, updated_at, deleted_at)"
                        + " VALUES (?, ?, ?, now(), now(), " + (withdrawn ? "now()" : "NULL") + ") RETURNING id",
                Long.class, "sub-" + name, name + "@example.com", name);
    }

    /** 탈퇴 처리가 적는 clip 줄. V114 이후 탈퇴자는 모두 이 줄이 있다. */
    private void purgeJob(long userId) {
        jdbc.update("INSERT INTO " + SCHEMA + ".withdrawal_purge_jobs (user_id, target, created_at, next_attempt_at)"
                + " VALUES (?, 'CLIP', now(), now())", userId);
    }

    private void link(String table, long userId, String channelId, boolean revoked) {
        jdbc.update("INSERT INTO " + SCHEMA + "." + table
                        + " (user_id, channel_id, channel_name, access_token_ref, refresh_token_ref,"
                        + " access_expires_at, last_refreshed_at, revoked_at, revoke_reason, created_at)"
                        + " VALUES (?, ?, '채널', 'ref-a', 'ref-r', now(), now(), "
                        + (revoked ? "now(), 'USER_UNLINKED'" : "NULL, NULL") + ", now())",
                userId, channelId);
    }

    private int rowsWithChannel(String table, long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + "." + table
                        + " WHERE user_id = ? AND (channel_id IS NOT NULL OR channel_name IS NOT NULL)",
                Integer.class, userId);
    }
}
