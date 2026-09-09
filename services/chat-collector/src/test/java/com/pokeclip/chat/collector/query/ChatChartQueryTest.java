package com.pokeclip.chat.collector.query;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.sync.SyncProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 구간 집계를 <b>실 PostgreSQL</b>에서 잰다. 가짜로 못 재는 것이 셋이다 — {@code date_bin}의
 * 실제 구간 경계, {@code timestamptz}의 마이크로초 자르기(F10), 그리고 색인.
 *
 * <p><b>방송 번호 접두는 {@code chart-}다</b>(문항 7).
 */
@SpringBootTest
@ActiveProfiles("test")
class ChatChartQueryTest extends IntegrationTestSupport {

    private static final List<String> STREAMS = List.of("chart-1", "chart-off", "chart-nano", "chart-idx");

    private static final AtomicInteger SEQ = new AtomicInteger(700_000);

    private final JdbcTemplate jdbc;

    ChatChartQueryTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 내_방송만_비운다() {
        STREAMS.forEach(streamId -> {
            jdbc.update("DELETE FROM chat_messages WHERE stream_id = ?", streamId);
            jdbc.update("DELETE FROM chat_donations WHERE stream_id = ?", streamId);
        });
    }

    private ChatChartQuery queryWith(long offsetMs) {
        return new ChatChartQuery(jdbc, new SyncProperties(offsetMs, Map.of()));
    }

    /**
     * 빈 구간이 0으로 들어 있어야 프론트가 선을 그린다. {@code hasSize(3)}만 보면
     * 「빈 구간을 채워 3」과 「우연히 세 구간에 데이터가 있어 3」이 구분이 안 되므로
     * <b>값을 순서대로</b> 본다(문항 2).
     */
    @Test
    void 구간별_개수를_세고_빈_구간은_0이다() {
        Instant T = Instant.parse("2026-09-03T15:00:00Z");
        insertChat("chart-1", T.plusSeconds(1), "a");
        insertChat("chart-1", T.plusSeconds(2), "b");
        insertChat("chart-1", T.plusSeconds(25), "c");
        insertDonation("chart-1", T.plusSeconds(3), "d");

        ChatChartPage page = queryWith(0)
                .count("chart-1", null, new WindowRequest(T, T.plusSeconds(30)), 10);

        assertThat(page.buckets()).extracting(ChartBucket::chats).containsExactly(2L, 0L, 1L);
        assertThat(page.buckets()).extracting(ChartBucket::donations).containsExactly(1L, 0L, 0L);
        assertThat(page.buckets()).extracting(ChartBucket::start)
                .containsExactly(T, T.plusSeconds(10), T.plusSeconds(20));
        assertThat(page.bucketSeconds()).isEqualTo(10);
    }

    /**
     * 🔴 <b>계획 검증 F5.</b> 원래 계획은 차트 시험이 {@code queryWith(0)}뿐이라 주입 표의
     * 「응답 {@code start}에서 보정을 뺀다/안 뺀다」가 <b>무연산</b>이 되어 아무것도 안 쟀다.
     * 보정이 0이 아닌 갈래가 있어야 <b>축과 창 계산을 동시에</b> 잰다(문항 2-B).
     *
     * <p>심은 둘은 부호를 가른다 — T+1s는 보정 0에서만, T+4.5s는 보정 3900에서만 창에 든다.
     */
    @Test
    void 보정이_창을_옮기고_start는_표_축이다() {
        Instant T = Instant.parse("2026-09-03T15:00:00Z");
        insertChat("chart-off", T.plusMillis(1000), "이른것");
        insertChat("chart-off", T.plusMillis(4500), "반응");
        WindowRequest w = new WindowRequest(T, T.plusSeconds(2));

        ChatChartPage 보정0 = queryWith(0).count("chart-off", null, w, 5);
        ChatChartPage 보정3900 = queryWith(3900).count("chart-off", null, w, 5);

        assertThat(보정0.buckets()).singleElement().satisfies(b -> {
            assertThat(b.chats()).isEqualTo(1);
            assertThat(b.start()).as("보정 0이면 표 축 = 요청 그대로").isEqualTo(T);
        });
        assertThat(보정3900.buckets()).singleElement().satisfies(b -> {
            assertThat(b.chats()).isEqualTo(1);
            assertThat(b.start()).as("🔴 표 축이다. 화면 축이면 T가 나온다")
                    .isEqualTo(T.plusMillis(3900));
        });
        assertThat(보정3900.appliedOffsetMs()).isEqualTo(3900);
    }

    /**
     * 🔴 <b>계획 검증 F10.</b> {@code Instant.parse}는 나노초를 받는데 PostgreSQL은
     * 마이크로초까지만 저장한다 — 미리 채운 맵의 키와 {@code date_bin} 결과가 어긋나면
     * <b>NPE → 500</b>이다. 계획 검증은 「코드로 확인, 재현 못 함」이었고 여기서 실물로 밟는다.
     */
    @Test
    void 나노초가_실린_from도_500이_아니라_구간을_준다() {
        Instant T = Instant.parse("2026-09-03T15:00:00.000000123Z");
        insertChat("chart-nano", T.plusSeconds(1), "a");

        ChatChartPage page = queryWith(0)
                .count("chart-nano", null, new WindowRequest(T, T.plusSeconds(20)), 10);

        assertThat(page.buckets()).extracting(ChartBucket::chats).containsExactly(1L, 0L);
        assertThat(page.buckets().getFirst().start())
                .as("나노초는 잘려 나간다 — 밀리초가 이 서버 시각의 단위다")
                .isEqualTo(Instant.parse("2026-09-03T15:00:00Z"));
    }

    /**
     * 목록 쪽과 같은 규칙이다(문항 8) — <b>운영 상수를 그대로 {@code EXPLAIN} 한다.</b>
     * 집계는 구간 안의 행을 전부 읽으므로 색인을 못 타면 방송 전체를 훑는다.
     *
     * <p>심은 2만 행을 반드시 지운다(F15). 지운 뒤 {@code ANALYZE}도 다시 돌린다.
     */
    @Test
    void 집계가_색인을_탄다() {
        Instant base = Instant.parse("2026-09-03T00:00:00Z");
        try {
            seedManyChats("chart-idx", base, 20_000);
            jdbc.execute("ANALYZE chat_messages");

            String plan = String.join("\n", jdbc.queryForList(
                    "EXPLAIN " + ChatChartQuery.CHATS, String.class,
                    10, Timestamp.from(base.plusSeconds(300)), "chart-idx",
                    Timestamp.from(base.plusSeconds(300)), Timestamp.from(base.plusSeconds(360))));

            assertThat(plan).contains("idx_chat_messages_stream_message_time");
            assertThat(plan).doesNotContain("Seq Scan");
        } finally {
            jdbc.update("DELETE FROM chat_messages WHERE stream_id = ?", "chart-idx");
            jdbc.execute("ANALYZE chat_messages");
        }
    }

    private void insertChat(String streamId, Instant messageTime, String content) {
        int n = SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO chat_messages
                  (channel_id, sender_channel_id, content, message_time, received_at,
                   content_sha256, stream_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                "chart-ch", "chart-sender", content,
                Timestamp.from(messageTime), Timestamp.from(messageTime),
                String.format("%064d", n), streamId);
    }

    private void insertDonation(String streamId, Instant receivedAt, String text) {
        jdbc.update("""
                INSERT INTO chat_donations
                  (stream_id, channel_id, donator_channel_id, donator_nickname,
                   donation_type, pay_amount, donation_text, received_at, donation_sha256)
                -- 지문은 매번 유일한 값이면 된다. 이 픽스처가 재는 것은 창구 조회이지
                -- 지문 규칙이 아니다 — 그쪽은 DonationPersisterTest가 잰다. 운영 지문
                -- 계산을 여기 베끼면 사본만 맞고 운영 계산은 아무도 안 보게 된다.
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, md5(random()::text))
                """,
                streamId, "chart-ch", "chart-donator", "후원자", "CHAT", 1000L, text,
                Timestamp.from(receivedAt));
    }

    private void seedManyChats(String streamId, Instant base, int rows) {
        jdbc.update("""
                INSERT INTO chat_messages
                  (channel_id, sender_channel_id, content, message_time, received_at,
                   content_sha256, stream_id)
                SELECT 'cidx-ch', 'cidx-sender', 'x',
                       ?::timestamptz + (g * interval '50 milliseconds'),
                       ?::timestamptz + (g * interval '50 milliseconds'),
                       lpad(g::text, 64, 'e'), ?
                  FROM generate_series(0, ? - 1) g
                """,
                Timestamp.from(base), Timestamp.from(base), streamId, rows);
    }
}
