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
 * {@code services/README.md}「시범 운영 측정 재료」 절의 집계 SQL 둘을 <b>README에서 그대로 꺼내</b> 돌린다(POK-338, POK-393에서 편집기 열기 기준으로).
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

    /**
     * 분모는 기간에 편집기로 연 자동 카드(카드 수), 분자는 그중 렌더 주문(clips)이 하나도 없는 카드.
     * 🔴 <b>편집본만 있고 렌더 주문이 없는 카드를 꼭 심는다</b>: 없으면 SQL이 recipes만 봐도 초록이다.
     */
    @Test
    void 열어_본_자동_카드_중_렌더_주문이_없는_비율() {
        long 열고_만든 = 카드("auto", 1);
        long 열고_편집본만 = 카드("auto", 2);
        long 열고_안_만든 = 카드("auto", 3);
        long 안_열고_만든 = 카드("auto", 4);
        long 핫키 = 카드("hotkey", 5);
        long 기간_밖에만_연 = 카드("auto", 6);
        long 옛_집기만 = 카드("auto", 7);
        long 열고_만들다_실패 = 카드("auto", 8);
        행동(열고_만든, "open", "1201", null, T.plusSeconds(10));
        행동(열고_만든, "open", "1202", null, T.plusSeconds(20)); // 두 사람이 열어도 한 장이다
        행동(열고_편집본만, "open", "1201", null, T.plusSeconds(30));
        행동(열고_안_만든, "open", "1201", null, T.plusSeconds(40));
        행동(핫키, "open", "1201", null, T.plusSeconds(50));
        행동(기간_밖에만_연, "open", "1201", null, T.minusSeconds(86_400));
        행동(옛_집기만, "claim", "1201", null, T.plusSeconds(60)); // 옛 서버가 남긴 집기는 열기가 아니다
        행동(열고_만들다_실패, "open", "1201", null, T.plusSeconds(70));
        렌더_주문(편집본(열고_만든, "1201", T.plusSeconds(100)), T.plusSeconds(200));
        편집본(열고_편집본만, "1201", T.plusSeconds(110));
        렌더_주문(편집본(안_열고_만든, "1201", T.plusSeconds(120)), T.plusSeconds(220));
        // 렌더가 실패해도 주문은 주문이다: 「나왔다」로 센다(README 한계 「렌더 주문은 결과를 안 본다」)
        렌더_주문(편집본(열고_만들다_실패, "1201", T.plusSeconds(140)), T.plusSeconds(240), "failed");
        편집본(핫키, "1201", T.plusSeconds(130));

        Map<String, Object> row = jdbc.queryForMap(기간을_넣는다(readmeSql("-- POK-393 클립 안 나온 카드 비율")));

        assertThat(((Number) row.get("opened")).longValue()).isEqualTo(4);
        assertThat(((Number) row.get("no_clip")).longValue()).isEqualTo(2);
        assertThat(((Number) row.get("no_clip_pct")).doubleValue()).isEqualTo(50.0);
    }

    @Test
    void 기간_밖에만_연_카드는_세지_않고_비율은_비어_있다() {
        long card = 카드("auto", 1);
        행동(card, "open", "1201", null, T.minusSeconds(86_400));

        Map<String, Object> row = jdbc.queryForMap(기간을_넣는다(readmeSql("-- POK-393 클립 안 나온 카드 비율")));

        assertThat(((Number) row.get("opened")).longValue()).isZero();
        assertThat(row.get("no_clip_pct")).as("0으로 나누지 않고 비운다").isNull();
    }

    @Test
    void 편집자별로_마지막_열기부터_렌더_주문과_업로드까지의_중앙값이_나온다() {
        long card = 카드("auto", 1);
        행동(card, "open", "1201", null, T.minusSeconds(600)); // 첫 저장 직전 마지막 열기가 시작점이다
        행동(card, "open", "1201", null, T);
        행동(card, "open", "1202", null, T.plusSeconds(30));  // 다른 편집자의 열기는 시작점이 아니다
        행동(card, "open", "1201", null, T.plusSeconds(120)); // 저장 뒤 다시 연 것도 시작점이 아니다
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
        행동(어제_카드, "open", "1201", null, T.minusSeconds(86_400 + 60));
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

        // 옛 서버의 집기 줄만 있고 열기가 없는 편집본은 시작점이 없어 빠진다
        long 집기만_한_카드 = 카드("auto", 4);
        행동(집기만_한_카드, "claim", "1401", null, T.plusSeconds(50));
        렌더_주문(편집본(집기만_한_카드, "1401", T.plusSeconds(60)), T.plusSeconds(90));

        // DB 시계가 앱 시계보다 2초 앞서 열기가 저장 「뒤」로 찍혔다: 시계 차 안이면 시작점으로 센다(PR #254 codex)
        long 다른_카드 = 카드("auto", 3);
        행동(다른_카드, "open", "1301", null, T.plusSeconds(62));
        long 시계차_편집본 = jdbc.queryForObject("""
                INSERT INTO recipes (stream_id, creator_id, source_card_id, schema_version, recipe_version,
                                     outputs, audio, created_at, updated_at)
                VALUES ('s-m', '1301', ?, 1, 1, '[]'::jsonb, '{}'::jsonb, ?, ?) RETURNING id""",
                Long.class, 다른_카드, Timestamp.from(T.plusSeconds(60)), Timestamp.from(T.plusSeconds(60)));
        jdbc.update("""
                INSERT INTO clips (stream_id, recipe_id, recipe_version, requested_by, status, outputs, created_at, updated_at)
                VALUES ('s-m', ?, 1, '1301', 'rendered', '[]'::jsonb, ?, ?)""",
                시계차_편집본, Timestamp.from(T.plusSeconds(92)), Timestamp.from(T.plusSeconds(92)));

        List<Map<String, Object>> rows = jdbc.queryForList(기간을_넣는다(readmeSql("-- POK-393 카드에서 영상까지")));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).get("editor")).isEqualTo("1301");
        assertThat(((Number) rows.get(1).get("median_open_to_order_sec")).doubleValue()).isEqualTo(30.0);
        assertThat(rows.get(0).get("editor")).isEqualTo("1201");
        assertThat(((Number) rows.get(0).get("recipes")).longValue()).isEqualTo(1);
        assertThat(((Number) rows.get(0).get("median_open_to_order_sec")).doubleValue()).isEqualTo(180.0);
        assertThat(((Number) rows.get(0).get("median_open_to_upload_sec")).doubleValue()).isEqualTo(300.0);
    }

    /**
     * 웹은 열기 기록을 기다리지 않고 보내고 서버는 자격 판정(auth 최대 7초) 뒤에 적는다(PR #326 codex). 바로 저장하면 열기 줄이
     * 저장보다 몇 초 늦게 찍힌다: 그 편집본이 시작점을 잃고 ②에서 빠지면 안 된다. 15초를 넘게 늦은 열기는 시작점이 아니다.
     */
    @Test
    void 열기_기록이_자격_판정만큼_저장보다_늦게_찍혀도_시작점이다() {
        long 늦게_적힌 = 카드("auto", 1);
        행동(늦게_적힌, "open", "1501", null, T.plusSeconds(68)); // 저장(60초) 8초 뒤
        렌더_주문(편집본(늦게_적힌, "1501", T.plusSeconds(60)), T.plusSeconds(90));
        long 너무_늦은 = 카드("auto", 2);
        행동(너무_늦은, "open", "1502", null, T.plusSeconds(76)); // 저장 16초 뒤: 다시 연 것으로 본다
        렌더_주문(편집본(너무_늦은, "1502", T.plusSeconds(60)), T.plusSeconds(90));

        List<Map<String, Object>> rows = jdbc.queryForList(기간을_넣는다(readmeSql("-- POK-393 카드에서 영상까지")));

        assertThat(rows).extracting(r -> r.get("editor")).containsExactly("1501");
        assertThat(((Number) rows.get(0).get("median_open_to_order_sec")).doubleValue()).isEqualTo(22.0);
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

    private long 편집본(long cardId, String creator, Instant at) {
        return jdbc.queryForObject("""
                INSERT INTO recipes (stream_id, creator_id, source_card_id, schema_version, recipe_version,
                                     outputs, audio, created_at, updated_at)
                VALUES ('s-m', ?, ?, 1, 1, '[]'::jsonb, '{}'::jsonb, ?, ?) RETURNING id""",
                Long.class, creator, cardId, Timestamp.from(at), Timestamp.from(at));
    }

    private void 렌더_주문(long recipeId, Instant at) {
        렌더_주문(recipeId, at, "queued");
    }

    private void 렌더_주문(long recipeId, Instant at, String status) {
        jdbc.update("""
                INSERT INTO clips (stream_id, recipe_id, recipe_version, requested_by, status, outputs, created_at, updated_at)
                VALUES ('s-m', ?, 1, '1201', ?, '[]'::jsonb, ?, ?)""",
                recipeId, status, Timestamp.from(at), Timestamp.from(at));
    }
}
