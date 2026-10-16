package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code services/README.md}「시범 운영 측정 재료」 절의 집계 SQL 둘을 <b>README에서 그대로 꺼내</b> 돌린다(POK-338).
 * 시험 안에 SQL을 베껴 두면 README가 낡아도 초록이다: 그래서 문서를 읽는다.
 */
class CardMetricsQueryTest extends IntegrationTestSupport {

    private static final Instant T = Instant.parse("2026-10-20T10:00:00Z");

    private final JdbcTemplate jdbc;

    CardMetricsQueryTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 비운다() {
        방송과_카드를_비운다(jdbc);
        jdbc.update("INSERT INTO broadcasts (stream_id, streamer_id, status, started_at, last_sequence) "
                + "VALUES ('s-m', ?, 'live', ?, 1)", TestIds.STREAMER, Timestamp.from(T));
    }

    /** 편집본·영상이 남으면 broadcasts를 직접 지우는 다른 시험이 FK로 죽는다(RecipeControllerTest와 같은 이유). */
    @AfterEach
    void 내_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
    }

    @Test
    void 거짓양성률은_열어_본_자동_카드_중_하이라이트_아님의_비율이다() {
        long 열고_부적절 = 카드("auto", 1);
        long 열고_그대로 = 카드("auto", 2);
        long 안_열고_부적절 = 카드("auto", 3);
        long 핫키 = 카드("hotkey", 4);
        long 기간_밖에만_열고_부적절 = 카드("auto", 5);
        long 열고_중복 = 카드("auto", 6);
        행동(열고_부적절, "claim", "1201", null, T.plusSeconds(10));
        행동(열고_부적절, "claim", "1202", null, T.plusSeconds(20)); // 두 번 열어도 한 장이다
        행동(열고_그대로, "claim", "1201", null, T.plusSeconds(30));
        행동(핫키, "claim", "1201", null, T.plusSeconds(40));
        행동(열고_중복, "claim", "1201", null, T.plusSeconds(50));
        행동(기간_밖에만_열고_부적절, "claim", "1201", null, T.minusSeconds(86_400)); // 기간 밖에서만 열었다
        숨김(열고_부적절, "not_highlight");
        숨김(안_열고_부적절, "not_highlight");
        숨김(핫키, "not_highlight");
        숨김(기간_밖에만_열고_부적절, "not_highlight");
        숨김(열고_중복, "duplicate"); // 숨겼지만 부적절이 아니다

        Map<String, Object> row = jdbc.queryForMap(기간을_넣는다(readmeSql("-- POK-338 거짓양성률")));

        assertThat(((Number) row.get("opened")).longValue()).isEqualTo(3);
        assertThat(((Number) row.get("not_highlight")).longValue()).isEqualTo(1);
        assertThat(((Number) row.get("false_positive_pct")).doubleValue()).isEqualTo(33.3);
    }

    @Test
    void 편집자별로_카드를_연_뒤_주문과_업로드까지의_중앙값이_나온다() {
        long card = 카드("auto", 1);
        행동(card, "claim", "1201", null, T.minusSeconds(600)); // 첫 저장 직전 마지막 집기가 시작점이다
        행동(card, "claim", "1201", null, T);
        행동(card, "claim", "1202", null, T.plusSeconds(30));  // 다른 편집자의 집기는 시작점이 아니다
        행동(card, "claim", "1201", null, T.plusSeconds(120)); // 저장 뒤 다시 연 것도 시작점이 아니다
        long recipe = jdbc.queryForObject("""
                INSERT INTO recipes (stream_id, creator_id, source_card_id, schema_version, recipe_version,
                                     outputs, audio, created_at, updated_at)
                VALUES ('s-m', '1201', ?, 1, 1, '[]'::jsonb, '{}'::jsonb, ?, ?) RETURNING id""",
                Long.class, card, Timestamp.from(T.plusSeconds(60)), Timestamp.from(T.plusSeconds(60)));
        long clip = jdbc.queryForObject("""
                INSERT INTO clips (stream_id, recipe_id, recipe_version, requested_by, status, outputs, created_at, updated_at)
                VALUES ('s-m', ?, 1, '1201', 'rendered', '[]'::jsonb, ?, ?) RETURNING id""",
                Long.class, recipe, Timestamp.from(T.plusSeconds(180)), Timestamp.from(T.plusSeconds(240)));
        jdbc.update("""
                INSERT INTO clip_uploads (clip_id, output_id, requested_by, channel_owner, title, status, payload,
                                          youtube_video_id, updated_at)
                VALUES (?, 'vert', '1201', '1201', '제목', 'uploaded', '{}'::jsonb, 'yt-1', ?)""",
                clip, Timestamp.from(T.plusSeconds(300)));
        // 실패한 업로드는 완료가 아니다(다른 출력이라 유일 색인과 안 부딪힌다)
        jdbc.update("""
                INSERT INTO clip_uploads (clip_id, output_id, requested_by, channel_owner, title, status, payload, updated_at)
                VALUES (?, 'horiz', '1201', '1201', '제목', 'failed', '{}'::jsonb, ?)""",
                clip, Timestamp.from(T.plusSeconds(200)));
        // 기간 밖(하루 전)에 처음 저장한 편집본은 안 센다
        long 어제_카드 = 카드("auto", 2);
        행동(어제_카드, "claim", "1201", null, T.minusSeconds(86_400 + 60));
        jdbc.update("""
                INSERT INTO recipes (stream_id, creator_id, source_card_id, schema_version, recipe_version,
                                     outputs, audio, created_at, updated_at)
                VALUES ('s-m', '1201', ?, 1, 1, '[]'::jsonb, '{}'::jsonb, ?, ?)""",
                어제_카드, Timestamp.from(T.minusSeconds(86_400)), Timestamp.from(T.minusSeconds(86_400)));
        // 카드 없이 만든 편집본은 안 센다
        jdbc.update("""
                INSERT INTO recipes (stream_id, creator_id, schema_version, recipe_version, outputs, audio, created_at, updated_at)
                VALUES ('s-m', '1201', 1, 1, '[]'::jsonb, '{}'::jsonb, ?, ?)""",
                Timestamp.from(T.plusSeconds(70)), Timestamp.from(T.plusSeconds(70)));

        // DB 시계가 앱 시계보다 2초 앞서 집기가 저장 「뒤」로 찍혔다: 시계 차 안이면 시작점으로 센다(PR #254 codex)
        long 다른_카드 = 카드("auto", 3);
        행동(다른_카드, "claim", "1301", null, T.plusSeconds(62));
        long 시계차_편집본 = jdbc.queryForObject("""
                INSERT INTO recipes (stream_id, creator_id, source_card_id, schema_version, recipe_version,
                                     outputs, audio, created_at, updated_at)
                VALUES ('s-m', '1301', ?, 1, 1, '[]'::jsonb, '{}'::jsonb, ?, ?) RETURNING id""",
                Long.class, 다른_카드, Timestamp.from(T.plusSeconds(60)), Timestamp.from(T.plusSeconds(60)));
        jdbc.update("""
                INSERT INTO clips (stream_id, recipe_id, recipe_version, requested_by, status, outputs, created_at, updated_at)
                VALUES ('s-m', ?, 1, '1301', 'rendered', '[]'::jsonb, ?, ?)""",
                시계차_편집본, Timestamp.from(T.plusSeconds(92)), Timestamp.from(T.plusSeconds(92)));

        List<Map<String, Object>> rows = jdbc.queryForList(기간을_넣는다(readmeSql("-- POK-338 카드에서 영상까지")));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).get("editor")).isEqualTo("1301");
        assertThat(((Number) rows.get(1).get("median_open_to_order_sec")).doubleValue()).isEqualTo(30.0);
        assertThat(rows.get(0).get("editor")).isEqualTo("1201");
        assertThat(((Number) rows.get(0).get("recipes")).longValue()).isEqualTo(1);
        assertThat(((Number) rows.get(0).get("median_open_to_order_sec")).doubleValue()).isEqualTo(180.0);
        assertThat(((Number) rows.get(0).get("median_open_to_upload_sec")).doubleValue()).isEqualTo(300.0);
    }

    /** README의 ```sql 덩어리 중 첫 줄이 {@code marker}인 것. */
    private static String readmeSql(String marker) {
        try {
            String readme = Files.readString(Path.of("..", "README.md"));
            int start = readme.indexOf("```sql\n" + marker + "\n");
            assertThat(start).as("README에 「%s」 SQL이 없다", marker).isNotNegative();
            int body = start + "```sql\n".length();
            return readme.substring(body, readme.indexOf("```", body)).trim().replaceAll(";$", "");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 기간은 시험 시각 앞뒤 하루. 문서의 자리표시({@code :from}·{@code :to})를 값으로 바꾼다. */
    private static String 기간을_넣는다(String sql) {
        return sql.replace(":from", "TIMESTAMPTZ '2026-10-20T00:00:00Z'")
                .replace(":to", "TIMESTAMPTZ '2026-10-21T00:00:00Z'");
    }

    private long 카드(String source, int n) {
        return jdbc.queryForObject("""
                INSERT INTO jump_cards (stream_id, source, stream_timestamp_ms, window_start_ms, window_end_ms, event_seq)
                VALUES ('s-m', ?, ?, ?, ?, 0) RETURNING id""", Long.class, source, n * 10_000L + 500, n * 10_000L, n * 10_000L + 1_000);
    }

    private void 행동(long cardId, String action, String actor, String reason, Instant at) {
        jdbc.update("INSERT INTO jump_card_actions (card_id, action, actor, reason, at) VALUES (?, ?, ?, ?, ?)",
                cardId, action, actor, reason, Timestamp.from(at));
    }

    private void 숨김(long cardId, String reason) {
        jdbc.update("UPDATE jump_cards SET hidden_at = now(), hidden_by = '1201', hidden_reason = ? WHERE id = ?",
                reason, cardId);
    }
}
