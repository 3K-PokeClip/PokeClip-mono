package com.pokeclip.chat.collector.purge;

import com.pokeclip.chat.collector.purge.ChatPurgeStore.Table;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 탈퇴 정리의 조회가 플랫폼을 앞에 둔 채널 색인(V314)을 타는지 잰다(POK-397). V315가 옛 채널 색인(채널이 앞)을 지웠으므로
 * 조건에서 플랫폼이 빠지면 색인 앞 칸을 못 써 표를 다 훑는다. 채팅 표는 방송 하나에 수십만 줄이라 그 훑기가 정리 묶음마다 반복된다.
 * 행 몇 개짜리 기능 시험으로는 이 차이가 안 보여 따로 잰다({@code BroadcastTitlePlanTest}와 같은 골격).
 *
 * <p><b>EXPLAIN만 한다(ANALYZE 없이).</b> 재는 문장이 DELETE라 ANALYZE를 붙이면 실제로 지운다. 채널 접두는 {@code plan-pg-}다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ChatPurgePlanTest extends IntegrationTestSupport {

    private static final Instant T = Instant.parse("2026-10-01T00:00:00Z");

    private final JdbcTemplate jdbc;

    ChatPurgePlanTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 채널이_많은_표를_심는다() {
        치운다();
        // 채널 200개 × 두 플랫폼에 고르게. 한 채널이 표의 작은 부분이어야 플래너가 색인을 고른다(운영 분포).
        jdbc.update("""
                INSERT INTO chat_messages (platform, channel_id, sender_channel_id, content, message_time, received_at,
                                           content_sha256, stream_id)
                SELECT CASE WHEN g % 2 = 0 THEN 'CHZZK' ELSE 'SOOP' END, 'plan-pg-' || (g % 200), 'v', 'm', ?,
                       ?::timestamptz + make_interval(secs => g), md5(g::text), 'plan-pg-s' || (g % 200)
                  FROM generate_series(1, 40000) g""", Timestamp.from(T), Timestamp.from(T));
        jdbc.update("""
                INSERT INTO chat_donations (platform, stream_id, channel_id, donator_channel_id, donation_type,
                                            donation_text, received_at, received_seq)
                SELECT CASE WHEN g % 2 = 0 THEN 'CHZZK' ELSE 'SOOP' END, 'plan-pg-s' || (g % 200), 'plan-pg-' || (g % 200),
                       'd', 'CHAT', '', ?::timestamptz + make_interval(secs => g), g
                  FROM generate_series(1, 20000) g""", Timestamp.from(T));
        jdbc.update("""
                INSERT INTO broadcast_info (platform, stream_id, channel_id, observed_at)
                SELECT CASE WHEN g % 2 = 0 THEN 'CHZZK' ELSE 'SOOP' END, 'plan-pg-s' || (g % 200), 'plan-pg-' || (g % 200),
                       ?::timestamptz + make_interval(secs => g)
                  FROM generate_series(1, 20000) g""", Timestamp.from(T));
        jdbc.execute("ANALYZE chat_messages");
        jdbc.execute("ANALYZE chat_donations");
        jdbc.execute("ANALYZE broadcast_info");
    }

    @AfterEach
    void 치운다() {
        for (String table : List.of("chat_messages", "chat_donations", "broadcast_info")) {
            jdbc.update("DELETE FROM " + table + " WHERE channel_id LIKE 'plan-pg-%'");
        }
    }

    /**
     * 색인 이름이 아니라 「색인 조건에 플랫폼과 채널이 같이 있다」로 잰다. 채팅 표는 플랫폼이 앞인 색인이 둘(채널 색인 · 지문 제약)이라
     * 플래너가 둘 중 어느 것을 골라도 맞다(실측으로 지문 쪽을 고르기도 한다).
     */
    @Test
    void 표_셋의_채널_묶음_지우기가_플랫폼과_채널로_색인을_탄다() {
        for (Table table : Table.values()) {
            String plan = explain(ChatPurgeStore.channelBatchSql(table), "SOOP", "plan-pg-7", ts(), 1000);
            assertThat(plan).as(plan).doesNotContain("Seq Scan on " + table.name);
            assertThat(indexConds(plan)).as(plan).hasSize(1)
                    .allSatisfy(cond -> assertThat(cond).contains("platform").contains("channel_id"));
        }
    }

    @Test
    void 짝_지우기의_세_갈래가_모두_플랫폼과_채널로_색인을_탄다() {
        String plan = explain(ChatPurgeStore.INGEST_KEYS_OF_CHANNEL, "SOOP", "plan-pg-7", ts(), "SOOP", "plan-pg-7", ts(),
                "SOOP", "plan-pg-7", ts());

        assertThat(indexConds(plan)).as(plan)
                .filteredOn(cond -> cond.contains("platform") && cond.contains("channel_id")).hasSize(3);
        assertThat(plan).as(plan)
                .doesNotContain("Seq Scan on chat_messages")
                .doesNotContain("Seq Scan on chat_donations")
                .doesNotContain("Seq Scan on broadcast_info");
    }

    /** 표를 읽는 색인 조건 줄(기본 키로 지울 줄을 찾는 {@code id =} 조건은 뺀다). */
    private static List<String> indexConds(String plan) {
        return plan.lines().filter(line -> line.contains("Index Cond:") && !line.contains("ANY_subquery"))
                .filter(line -> !line.contains("(stream_id = "))
                .toList();
    }

    private Timestamp ts() {
        return Timestamp.from(T.plusSeconds(100_000));
    }

    private String explain(String sql, Object... args) {
        List<String> lines = new ArrayList<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement("EXPLAIN " + sql);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, rs -> {
            lines.add(rs.getString(1));
        });
        return String.join("\n", lines);
    }
}
