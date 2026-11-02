package com.pokeclip.chat.collector.persist;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V313~V315(POK-397 PR2): 수집기 표 넷에 플랫폼 칸을 더하고, 채널로 찾는 색인과 지문 제약의 앞에 플랫폼을 둔다.
 *
 * <p><b>빈 스키마 하나에 V312까지만 올리고 옛 줄을 심은 뒤</b> 나머지를 올린다. 공용 스키마는 다른 시험 컨텍스트가 이미 최신으로
 * 올려 「칸이 생기기 전에 있던 줄」을 만들 수 없다(auth {@code LinkChannelBackfillMigrationTest}와 같은 골격). 스프링 컨텍스트를
 * 띄우지 않는다.
 *
 * <p>🔴 기본값이 {@code 'CHZZK'}라 「기존 줄이 CHZZK」만 재면 칸이 있기만 해도 참이다. 그래서 CHECK·지문·PK는
 * <b>SOOP 줄을 직접 넣어</b> 잰다.
 */
class PlatformMigrationTest extends IntegrationTestSupport {

    private static final String SCHEMA = "pok397_platform";

    private final DriverManagerDataSource dataSource =
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    private long donationSeq;

    @AfterEach
    void 스키마를_지운다() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    void 칸이_생기기_전에_있던_줄은_표_넷_모두_CHZZK다() {
        flyway("312").migrate();
        seedOldRows();

        flyway(null).migrate();

        for (String table : List.of("chat_messages", "chat_donations", "broadcast_info", "purged_channels")) {
            assertThat(jdbc.queryForList("SELECT DISTINCT platform FROM " + SCHEMA + "." + table, String.class))
                    .as(table + ": 옛 줄의 플랫폼").containsExactly("CHZZK");
        }
    }

    @Test
    void CHECK가_소문자와_빈_값을_표_넷_모두에서_거부한다() {
        flyway(null).migrate();

        for (String bad : List.of("soop", "")) {
            assertCheckRejects(() -> insertChat(bad, "ch", "s", "같은 말"), "chat_messages", bad);
            assertCheckRejects(() -> insertDonation(bad, "ch"), "chat_donations", bad);
            assertCheckRejects(() -> insertBroadcastInfo(bad, "ch"), "broadcast_info", bad);
            assertCheckRejects(() -> insertPurged(bad, "ch"), "purged_channels", bad);
        }
        // 대문자·숫자·밑줄은 받는다. 다음 플랫폼이 enum에 더해질 때 마이그레이션이 필요 없게 하려는 폭이다.
        insertChat("SOOP", "ch", "s", "받는다");
        insertDonation("SOOP_2", "ch");
    }

    @Test
    void 같은_지문은_두_플랫폼에서_두_줄이고_같은_플랫폼에서는_한_줄이다() {
        flyway(null).migrate();

        insertChat("CHZZK", "same-id", "sender", "같은 말");
        insertChat("SOOP", "same-id", "sender", "같은 말");
        assertThatThrownBy(() -> insertChat("SOOP", "same-id", "sender", "같은 말"))
                .as("같은 플랫폼의 같은 지문은 지문 제약이 막아야 한다")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_chat_messages_fingerprint");

        assertThat(count("chat_messages")).isEqualTo(2);
    }

    /**
     * 🔴 배포 겹침: 옛 수집기는 플랫폼 없이 넣고 {@code ON CONFLICT ON CONSTRAINT uq_chat_messages_fingerprint}로 중복을
     * 버린다. V315가 제약 이름을 그대로 두므로 그 문장이 계속 돌고, 플랫폼이 없으면 CHZZK로 들어가 SOOP 줄과 부딪히지 않는다.
     */
    @Test
    void 플랫폼_없이_넣는_옛_문장이_제약_이름으로_중복을_버린다() {
        flyway(null).migrate();
        insertChat("SOOP", "same-id", "sender", "같은 말");

        assertThat(insertChatLegacy("same-id", "sender", "같은 말")).isEqualTo(1);
        assertThat(insertChatLegacy("same-id", "sender", "같은 말")).isZero();

        assertThat(jdbc.queryForList("SELECT platform FROM " + SCHEMA + ".chat_messages ORDER BY platform",
                String.class)).containsExactly("CHZZK", "SOOP");
    }

    @Test
    void 탈퇴_명부는_같은_아이디를_두_플랫폼에_따로_적는다() {
        flyway(null).migrate();

        insertPurged("CHZZK", "same-id");
        insertPurged("SOOP", "same-id");
        assertThatThrownBy(() -> insertPurged("SOOP", "same-id"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count("purged_channels")).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
                 WHERE c.conrelid = '%s.purged_channels'::regclass AND c.contype = 'p'""".formatted(SCHEMA),
                String.class)).isEqualTo("PRIMARY KEY (platform, channel_id)");
    }

    @Test
    void 옛_채널_색인은_없고_플랫폼을_앞에_둔_색인과_제약이_있다() {
        flyway(null).migrate();

        Map<String, String> indexes = indexDefs();
        assertThat(indexes)
                .as("옛 채널 색인은 새 색인과 앞 칸이 겹쳐 쓰기 비용만 늘린다")
                .doesNotContainKeys("idx_chat_messages_channel_received", "idx_chat_donations_channel_received",
                        "idx_broadcast_info_channel_observed", "uq_chat_messages_platform_fingerprint");
        assertThat(indexes.get("idx_chat_messages_platform_channel_received"))
                .contains("(platform, channel_id, received_at)");
        assertThat(indexes.get("idx_chat_donations_platform_channel_received"))
                .contains("(platform, channel_id, received_at)");
        assertThat(indexes.get("idx_broadcast_info_platform_channel_observed"))
                .contains("(platform, channel_id, observed_at)");

        assertThat(fingerprintDef())
                .as("제약 이름은 그대로, 정의는 플랫폼부터")
                .isEqualTo("UNIQUE (platform, channel_id, sender_channel_id, message_time, content_sha256)");
        assertThat(invalidIndexes()).isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT conname FROM pg_constraint
                 WHERE connamespace = '%s'::regnamespace AND conname LIKE 'ck_%%_platform' AND convalidated"""
                .formatted(SCHEMA), String.class))
                .as("CHECK 넷은 V315에서 검증까지 끝난다")
                .containsExactlyInAnyOrder("ck_chat_messages_platform", "ck_chat_donations_platform",
                        "ck_broadcast_info_platform", "ck_purged_channels_platform");
    }

    /**
     * V315는 트랜잭션 밖이라 중간에 끊기면 앞부분만 적용된 채 다시 돈다. Flyway는 적용된 버전을 다시 안 돌리므로 파일 본문을
     * 직접 두 번 실행해 잰다(계획 검증 F7).
     */
    @Test
    void V315_본문을_두_번_돌려도_같은_모양이다() throws Exception {
        flyway("314").migrate();

        runScript(v315());
        Map<String, String> once = indexDefs();
        String onceDef = fingerprintDef();
        runScript(v315());

        assertThat(indexDefs()).isEqualTo(once);
        assertThat(fingerprintDef()).isEqualTo(onceDef)
                .isEqualTo("UNIQUE (platform, channel_id, sender_channel_id, message_time, content_sha256)");
    }

    /**
     * 🔴 V313은 표 넷을 강한 잠금으로 차례로 잡고 트랜잭션 끝까지 쥔다. 채팅 표를 먼저 잡으면 뒤 표의 잠금 대기(각 최대 5초)
     * 동안 라이브 채팅 적재가 함께 멈춘다. 그래서 채팅 표를 마지막에 잡는다(로컬 리뷰 1라운드). 문장 순서를 소스에서 잰다.
     */
    @Test
    void V313은_채팅_표를_마지막에_잠근다() throws IOException {
        String sql;
        try (var in = getClass().getResourceAsStream("/db/migration/V313__add_platform_to_chat_tables.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int chat = sql.indexOf("ALTER TABLE chat_messages");
        for (String other : List.of("purged_channels", "broadcast_info", "chat_donations")) {
            assertThat(sql.lastIndexOf("ALTER TABLE " + other))
                    .as("%s 를 chat_messages 보다 먼저 잠가야 한다", other)
                    .isPositive().isLessThan(chat);
        }
    }

    /**
     * 🔴 새 지문 색인이 없는데(V314가 INVALID로 남아 사람이 지운 경우) 조용히 건너뛰면 옛 제약이 남은 채 V315가 성공으로
     * 적힌다. 그러면 SOOP 채팅이 같은 아이디 치지직 채팅과 지문이 같을 때 버려진다. 크게 실패해야 한다(계획 검증 F6).
     */
    @Test
    void 새_지문_색인이_없으면_V315가_실패한다() throws Exception {
        flyway("314").migrate();
        jdbc.execute("DROP INDEX " + SCHEMA + ".uq_chat_messages_platform_fingerprint");

        assertThatThrownBy(() -> runScript(v315()))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_chat_messages_platform_fingerprint");
        assertThat(fingerprintDef())
                .isEqualTo("UNIQUE (channel_id, sender_channel_id, message_time, content_sha256)");
    }

    /**
     * 🔴 V315의 지문 교체는 V313과 같은 강한 잠금이다. 앞에 오래 도는 읽기가 있으면 그 ALTER가 기다리는 동안 뒤의 채팅 INSERT가
     * 줄을 선다. 그래서 DO 블록이 잠금 대기를 5초로 자른다(감사 L3). 다른 연결이 표를 읽는 잠금을 쥔 채 놓지 않으면 V315가
     * 잠금 시한(55P03)으로 5초 남짓에 실패해야 한다. 시한이 빠지면 안전판 statement_timeout(20초, 57014)에 끊겨 빨갛다.
     */
    @Test
    void V315_지문_교체는_잠금을_5초까지만_기다린다() throws Exception {
        flyway("314").migrate();

        try (Connection holder = dataSource.getConnection(); Statement hold = holder.createStatement()) {
            holder.setAutoCommit(false);
            hold.execute("LOCK TABLE " + SCHEMA + ".chat_messages IN ACCESS SHARE MODE");
            long started = System.nanoTime();

            assertThatThrownBy(() -> runScript(v315()))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).as("잠금 시한이 아닌 다른 이유로 끊겼다")
                            .isEqualTo("55P03"));
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(15));
            holder.rollback();
        }
        assertThat(fingerprintDef()).as("실패했으면 옛 제약이 그대로다")
                .isEqualTo("UNIQUE (channel_id, sender_channel_id, message_time, content_sha256)");
    }

    // ── 도구 ────────────────────────────────────────────────────────────

    private Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .table("flyway_schema_history_chat")
                .locations("classpath:db/migration")
                // 운영 설정과 같다(application.yml). 트랜잭션 잠금이면 CONCURRENTLY가 그 트랜잭션을 기다린다.
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"));
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private void seedOldRows() {
        jdbc.update("INSERT INTO " + SCHEMA + ".chat_messages (channel_id, sender_channel_id, content, message_time,"
                + " received_at, content_sha256) VALUES ('old', 's', '옛 말', now(), now(), 'h')");
        jdbc.update("INSERT INTO " + SCHEMA + ".chat_donations (stream_id, channel_id, donator_channel_id,"
                + " donation_type, donation_text, received_at, received_seq) VALUES ('st', 'old', 'd', 'CHAT', '',"
                + " now(), 1)");
        jdbc.update("INSERT INTO " + SCHEMA + ".broadcast_info (stream_id, channel_id, observed_at)"
                + " VALUES ('st', 'old', now())");
        jdbc.update("INSERT INTO " + SCHEMA + ".purged_channels (channel_id, requested_at) VALUES ('old', now())");
    }

    private void insertChat(String platform, String channel, String sender, String content) {
        jdbc.update("INSERT INTO " + SCHEMA + ".chat_messages (platform, channel_id, sender_channel_id, content,"
                        + " message_time, received_at, content_sha256)"
                        + " VALUES (?, ?, ?, ?, '2026-10-23T00:00:00Z', now(), md5(?))",
                platform, channel, sender, content, content);
    }

    /** 플랫폼 칸을 모르는 옛 {@code ChatPersister}의 문장 모양. */
    private int insertChatLegacy(String channel, String sender, String content) {
        return jdbc.update("INSERT INTO " + SCHEMA + ".chat_messages (channel_id, sender_channel_id, content,"
                        + " message_time, received_at, content_sha256)"
                        + " VALUES (?, ?, ?, '2026-10-23T00:00:00Z', now(), md5(?))"
                        + " ON CONFLICT ON CONSTRAINT uq_chat_messages_fingerprint DO NOTHING",
                channel, sender, content, content);
    }

    private void insertDonation(String platform, String channel) {
        jdbc.update("INSERT INTO " + SCHEMA + ".chat_donations (platform, stream_id, channel_id, donator_channel_id,"
                + " donation_type, donation_text, received_at, received_seq) VALUES (?, 'st', ?, 'd', 'CHAT', '',"
                + " now(), ?)", platform, channel, ++donationSeq);
    }

    private void insertBroadcastInfo(String platform, String channel) {
        jdbc.update("INSERT INTO " + SCHEMA + ".broadcast_info (platform, stream_id, channel_id, observed_at)"
                + " VALUES (?, 'st', ?, now())", platform, channel);
    }

    private void insertPurged(String platform, String channel) {
        jdbc.update("INSERT INTO " + SCHEMA + ".purged_channels (platform, channel_id, requested_at)"
                + " VALUES (?, ?, now())", platform, channel);
    }

    private void assertCheckRejects(Runnable insert, String table, String value) {
        assertThatThrownBy(insert::run)
                .as(table + ": 플랫폼 「" + value + "」")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_" + table + "_platform");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + "." + table, Integer.class);
    }

    private Map<String, String> indexDefs() {
        Map<String, String> defs = new java.util.TreeMap<>();
        jdbc.query("SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = ?",
                rs -> {
                    defs.put(rs.getString(1), rs.getString(2));
                }, SCHEMA);
        return defs;
    }

    private String fingerprintDef() {
        return jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                 WHERE conrelid = '%s.chat_messages'::regclass AND conname = 'uq_chat_messages_fingerprint'"""
                .formatted(SCHEMA), String.class);
    }

    private List<String> invalidIndexes() {
        return jdbc.queryForList("""
                SELECT i.indexrelid::regclass::text FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                 WHERE c.relnamespace = '%s'::regnamespace AND NOT i.indisvalid""".formatted(SCHEMA), String.class);
    }

    private String v315() throws IOException {
        try (var in = getClass().getResourceAsStream("/db/migration/V315__chat_platform_swap_and_validate.sql")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 문장마다 따로 자동 커밋으로 돌린다. 한 번에 넘기면 드라이버가 한 트랜잭션으로 묶어 {@code CONCURRENTLY}가 거부된다.
     * {@code $$ ... $$} 안의 세미콜론은 문장 끝이 아니다.
     */
    private void runScript(String sql) throws SQLException {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inDollar = false;
        for (String line : sql.lines().toList()) {
            if (!inDollar && line.stripLeading().startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            int dollars = line.split("\\$\\$", -1).length - 1;
            if (dollars % 2 == 1) {
                inDollar = !inDollar;
            }
            if (!inDollar && line.stripTrailing().endsWith(";")) {
                statements.add(current.toString());
                current.setLength(0);
            }
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + SCHEMA);
            // 안전판. 잠금 시한이 빠지면 막힌 문장이 여기서 끊긴다(57014). 잠금 시한 시험은 그 코드와 55P03을 가른다.
            statement.execute("SET statement_timeout = '20s'");
            for (String each : statements) {
                statement.execute(each);
            }
        }
    }
}
