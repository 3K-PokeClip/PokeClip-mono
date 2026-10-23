package com.pokeclip.auth.withdrawal.purge;

import com.pokeclip.auth.support.IntegrationTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V118(POK-397): 탈퇴 장부에 플랫폼 칸. 공용 스키마는 이미 최신이라 「칸 전의 줄」을 못 만든다. 빈 스키마에 V117까지 올리고
 * 옛 줄을 심은 뒤 나머지를 올린다({@code LinkChannelBackfillMigrationTest}와 같은 골격). 스프링 컨텍스트를 안 띄운다.
 */
class PurgePlatformMigrationTest extends IntegrationTestSupport {

    private static final String SCHEMA = "pok397_purge_platform";

    private final DriverManagerDataSource dataSource =
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    @AfterEach
    void 스키마를_지운다() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    void 칸_전의_수집기_줄은_CHZZK로_채우고_clip_줄은_비워_둔다() {
        flyway("117").migrate();
        long user = user("old");
        jdbc.update("INSERT INTO " + SCHEMA + ".withdrawal_purge_jobs (user_id, target, created_at, next_attempt_at)"
                + " VALUES (?, 'CLIP', now(), now())", user);
        jdbc.update("INSERT INTO " + SCHEMA + ".withdrawal_purge_jobs (user_id, target, channel_id, created_at, next_attempt_at)"
                + " VALUES (?, 'COLLECTOR', 'chan-old', now(), now())", user);

        flyway(null).migrate();

        assertThat(jdbc.queryForList("SELECT target || ':' || COALESCE(platform, '-') FROM " + SCHEMA
                + ".withdrawal_purge_jobs ORDER BY target", String.class)).containsExactly("CLIP:-", "COLLECTOR:CHZZK");
    }

    /**
     * 🔴 배포 겹침: 옛 auth는 플랫폼 없이 수집기 줄을 적는다. CHECK가 그것을 막으면 탈퇴가 500이다(F1). clip 줄의 플랫폼만 막는다.
     * 같은 아이디가 두 플랫폼이면 두 줄이고, 같은 플랫폼이면 한 줄이다.
     */
    @Test
    void 옛_auth의_플랫폼_없는_수집기_줄은_받고_clip_줄의_플랫폼은_막고_열쇠에_플랫폼이_든다() {
        flyway(null).migrate();
        long user = user("new");

        insertCollector(user, null, "chan-a");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + SCHEMA + ".withdrawal_purge_jobs (user_id, target, platform,"
                + " created_at, next_attempt_at) VALUES (?, 'CLIP', 'SOOP', now(), now())", user))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_withdrawal_purge_platform");

        insertCollector(user, "CHZZK", "same");
        insertCollector(user, "SOOP", "same");
        assertThatThrownBy(() -> insertCollector(user, "SOOP", "same"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_withdrawal_purge_jobs");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".withdrawal_purge_jobs WHERE channel_id = 'same'",
                Integer.class)).isEqualTo(2);
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

    private long user(String name) {
        return jdbc.queryForObject("INSERT INTO " + SCHEMA + ".users"
                        + " (google_sub, email, name, created_at, updated_at, deleted_at)"
                        + " VALUES (?, ?, ?, now(), now(), now()) RETURNING id",
                Long.class, "sub-" + name, name + "@example.com", name);
    }

    private void insertCollector(long user, String platform, String channel) {
        jdbc.update("INSERT INTO " + SCHEMA + ".withdrawal_purge_jobs (user_id, target, platform, channel_id, created_at,"
                + " next_attempt_at) VALUES (?, 'COLLECTOR', ?, ?, now(), now())", user, platform, channel);
    }
}
