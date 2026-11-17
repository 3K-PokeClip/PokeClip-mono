package com.pokeclip.clip.render;

import com.pokeclip.clip.support.IntegrationTestSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V224({@code clips.placements} — 렌더 결과 배치표, POK-196 S-c5)와 <b>빈 번호 V223</b>(POK-525 kty 결정으로 폐기 · 영구 빈 번호).
 * clip 은 {@code out-of-order} · {@code ignore-migration-patterns} 를 따로 두지 않는다(계획 r7 2.8). 그래서 두 길을 다 잰다 —
 * 빈 DB 에 처음부터 끝까지, 그리고 V222 까지 적용된 DB(develop 의 dev · prod 모양)에 V224 만. 두 길 모두 검증 오류가 없고, 이력에
 * 223 행이 없고, 표 모양이 같아야 한다. 표 모양이 같으면 Hibernate 검증(부팅의 나머지 몫)도 같은 답을 낸다 — 빈 DB 길의 부팅은
 * 이 시험 클래스가 뜨는 스프링 컨텍스트 자체가 잰다.
 *
 * <p>시험용 DB 를 새로 만들어 앱과 같은 스키마 · 이력 표 이름으로 올린다(같은 컨테이너, 끝나면 지운다).
 */
class ClipsPlacementsMigrationTest extends IntegrationTestSupport {

    private static final String SCHEMA = "app_clip";
    private static final String HISTORY = "flyway_schema_history_clip";

    private final List<String> databases = new ArrayList<>();

    @BeforeEach
    void 빈_DB_둘을_만든다() throws SQLException {
        for (int i = 0; i < 2; i++) {
            String name = "mig224_" + UUID.randomUUID().toString().replace("-", "");
            try (Connection c = admin(); Statement s = c.createStatement()) {
                s.execute("CREATE DATABASE " + name);
            }
            databases.add(name);
        }
    }

    @AfterEach
    void DB를_지운다() throws SQLException {
        try (Connection c = admin(); Statement s = c.createStatement()) {
            for (String name : databases) {
                s.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
            }
        }
        databases.clear();
    }

    @Test
    void 빈_DB에_처음부터_올려도_V222_DB에_V224만_올려도_검증이_통과하고_223_행이_없다() throws SQLException {
        String fresh = databases.get(0);
        String upgraded = databases.get(1);

        flyway(fresh, null).migrate();
        flyway(upgraded, "222").migrate();
        try (Connection c = target(upgraded); Statement s = c.createStatement()) {
            s.execute("INSERT INTO broadcasts (stream_id, streamer_id, status, last_sequence) VALUES ('s-old', '7', 'live', 1)");
            s.execute("""
                    INSERT INTO recipes (stream_id, creator_id, schema_version, recipe_version, cut_in_at_ms, cut_out_at_ms,
                                         outputs, audio, updated_at)
                    VALUES ('s-old', '4180', 1, 1, 0, 1000, '[]'::jsonb, '{}'::jsonb, now())""");
            s.execute("""
                    INSERT INTO clips (stream_id, recipe_id, recipe_version, requested_by, status, updated_at)
                    SELECT 's-old', id, 1, '4180', 'rendered', now() FROM recipes""");
        }
        var applied = flyway(upgraded, null).migrate();

        assertThat(applied.migrations).extracting(m -> m.version).as("V222 다음은 V224 하나").containsExactly("224");
        for (String db : databases) {
            ValidateResult validated = flyway(db, null).validateWithResult();
            assertThat(validated.validationSuccessful).as(db + " 검증 오류: " + validated.invalidMigrations).isTrue();
            try (Connection c = target(db); Statement s = c.createStatement()) {
                assertThat(versions(s)).as("빈 번호 223 이 이력에 생기면 안 된다").doesNotContain("223").contains("222", "224");
            }
        }
        try (Connection f = target(fresh); Statement fs = f.createStatement();
             Connection u = target(upgraded); Statement us = u.createStatement()) {
            assertThat(columns(us)).as("두 길의 clips 표 모양").isEqualTo(columns(fs));
            assertThat(columns(fs)).contains("placements:jsonb:YES");
            try (ResultSet rs = us.executeQuery("SELECT count(*) FROM clips WHERE placements IS NULL")) {
                rs.next();
                assertThat(rs.getInt(1)).as("옛 줄은 비어 있다(옛 코드가 읽지 않는 널 허용 칸)").isOne();
            }
        }
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private Flyway flyway(String db, String target) {
        var config = Flyway.configure()
                .dataSource(url(db), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas(SCHEMA)
                .table(HISTORY);
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static List<String> versions(Statement s) throws SQLException {
        List<String> versions = new ArrayList<>();
        try (ResultSet rs = s.executeQuery("SELECT version FROM " + SCHEMA + "." + HISTORY
                + " WHERE version IS NOT NULL ORDER BY installed_rank")) {
            while (rs.next()) {
                versions.add(rs.getString(1));
            }
        }
        return versions;
    }

    /** clips 칸 「이름:형:널 허용」, 칸 순서대로. */
    private static List<String> columns(Statement s) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (ResultSet rs = s.executeQuery("""
                SELECT column_name || ':' || data_type || ':' || is_nullable FROM information_schema.columns
                 WHERE table_schema = '%s' AND table_name = 'clips' ORDER BY ordinal_position""".formatted(SCHEMA))) {
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
        }
        return columns;
    }

    private static Connection target(String db) throws SQLException {
        Connection c = DriverManager.getConnection(url(db), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Statement s = c.createStatement()) {
            s.execute("SET search_path TO " + SCHEMA);
        }
        return c;
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** 컨테이너 주소의 DB 이름만 바꾼다. */
    private static String url(String db) {
        String base = POSTGRES.getJdbcUrl();
        int q = base.indexOf('?');
        String head = q < 0 ? base : base.substring(0, q);
        String tail = q < 0 ? "" : base.substring(q);
        return head.substring(0, head.lastIndexOf('/') + 1) + db + tail;
    }
}
