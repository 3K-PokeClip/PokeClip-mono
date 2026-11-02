package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.support.IntegrationTestSupport;
import org.flywaydb.core.Flyway;
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
 * POK-391 V219(수동 카드 eventId 부분 유일 색인)가 <b>옛 줄이 있는 DB</b>에서도 적용되는가(PR #338 codex P1).
 *
 * <p>V219 전에는 2A가 {@code hotkey}도 받았고 eventId는 추적용이라 유일하지 않았다. 같은 eventId의 hotkey 줄이 둘 이상이면
 * 색인 생성이 실패해 <b>부팅이 막힌다</b>. 그래서 V219가 색인 앞에서 정리한다: 같은 eventId의 hotkey 줄 중 id가 가장 작은 것만
 * 그 값을 지키고 나머지는 event_id를 비운다(줄은 안 지운다: 편집본·행동 기록이 그 카드를 가리킬 수 있다).
 *
 * <p>시험용 DB를 하나 새로 만들어 Flyway로 <b>V218까지</b> 올리고, 옛 줄을 심은 뒤 끝까지 올린다(같은 컨테이너, 끝나면 지운다).
 */
class HotkeyEventIndexMigrationTest extends IntegrationTestSupport {

    private String database;

    @BeforeEach
    void 빈_DB를_만든다() throws SQLException {
        database = "mig391_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + database);
        }
    }

    @AfterEach
    void DB를_지운다() throws SQLException {
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    @Test
    void 같은_eventId의_옛_hotkey_줄이_여럿이어도_V219가_적용되고_가장_앞_줄만_값을_지킨다() throws SQLException {
        flyway("218").migrate();
        long first;
        long second;
        long third;
        long auto;
        long unique;
        try (Connection c = target(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO broadcasts (stream_id, streamer_id, status, last_sequence) VALUES ('s-old', '7', 'live', 1)");
            first = card(s, "hotkey", "dup", 0);
            second = card(s, "hotkey", "dup", 1_000);
            third = card(s, "hotkey", "dup", 2_000);
            auto = card(s, "auto", "dup", 0);
            unique = card(s, "hotkey", "only", 3_000);
            card(s, "hotkey", null, 4_000);
            card(s, "hotkey", null, 5_000);
        }

        flyway(null).migrate();

        try (Connection c = target(); Statement s = c.createStatement()) {
            assertThat(eventIdOf(s, first)).as("가장 앞 줄은 값을 지킨다").isEqualTo("dup");
            assertThat(eventIdOf(s, second)).isNull();
            assertThat(eventIdOf(s, third)).isNull();
            assertThat(eventIdOf(s, auto)).as("자동 카드의 eventId는 추적용이라 손대지 않는다").isEqualTo("dup");
            assertThat(eventIdOf(s, unique)).isEqualTo("only");
            assertThat(count(s, "SELECT count(*) FROM jump_cards")).as("줄은 안 지운다").isEqualTo(7);
            assertThat(indexes(s)).contains("uq_jump_cards_hotkey_event", "uq_jump_cards_auto_window");
        }
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(url(database), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .table("flyway_schema_history_clip");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static long card(Statement s, String source, String eventId, long start) throws SQLException {
        String id = eventId == null ? "NULL" : "'" + eventId + "'";
        try (ResultSet rs = s.executeQuery("""
                INSERT INTO jump_cards (stream_id, source, event_id, stream_timestamp_ms, window_start_ms, window_end_ms, event_seq)
                VALUES ('s-old', '%s', %s, %d, %d, %d, 0) RETURNING id""".formatted(source, id, start, start, start + 1_000))) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String eventIdOf(Statement s, long id) throws SQLException {
        try (ResultSet rs = s.executeQuery("SELECT event_id FROM jump_cards WHERE id = " + id)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static long count(Statement s, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static List<String> indexes(Statement s) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet rs = s.executeQuery("SELECT indexname FROM pg_indexes WHERE tablename = 'jump_cards'")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private Connection target() throws SQLException {
        return DriverManager.getConnection(url(database), POSTGRES.getUsername(), POSTGRES.getPassword());
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
