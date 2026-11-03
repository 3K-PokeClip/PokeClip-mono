package com.pokeclip.clip.segment;

import com.pokeclip.clip.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회차 행 · 회차 기준점 질의가 <b>세션 색인 범위</b>를 타는지 잰다(POK-196 · cc rev8 부수 6 · README 「{@code EXPLAIN} 검사」
 * 관례). 장부의 {@code stream_id}는 물리 키(스트림키)라 한 키에 그 스트리머의 조각 이력이 전부 쌓인다 — 회차 세션으로 색인
 * 범위를 못 자르면 카드 목록 · 마크마다 그 이력을 훑는다. 행 몇 개짜리 기능 시험으로는 이 차이가 안 보여 따로 잰다.
 *
 * <p>색인은 media 가 운영 장부에 만드는 것과 같은 모양({@code media/internal/index/ddl.go}
 * {@code stream_segments_session_idx})이다 — 시험 시드 표에는 PK뿐이라 여기서 같은 것을 만든다.
 */
class RoundRowsPlanTest extends IntegrationTestSupport {

    private static final String KEY = "s-roundplan";
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");

    private final JdbcTemplate jdbc;

    RoundRowsPlanTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 이력이_긴_스트림키를_심는다() {
        방송과_카드를_비운다(jdbc);
        jdbc.execute("CREATE INDEX IF NOT EXISTS stream_segments_session_idx "
                + "ON public.stream_segments (stream_id, session_id, seq)");
        // 한 키에 지난 세션 스무 개(각 1,000조각) 뒤 이번 회차 세션 둘(각 450조각) — 최근 방송은 맨 끝이다
        jdbc.update("""
                INSERT INTO public.stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, playback_pdt, duration_ms,
                                                    s3_key, upload_state, session_id)
                SELECT ?, g, g * 4000, ?::timestamptz + g * 4000 * interval '1 millisecond',
                       ?::timestamptz + g * 4000 * interval '1 millisecond', 4000, 'k' || g, 'uploaded',
                       CASE WHEN g < 20000 THEN 'old-' || (g / 1000) WHEN g < 20450 THEN 'now-1' ELSE 'now-2' END
                  FROM generate_series(0, 20899) g""", KEY, Timestamp.from(START), Timestamp.from(START));
        jdbc.execute("ANALYZE public.stream_segments");
    }

    @AfterEach
    void 치운다() {
        jdbc.update("DELETE FROM public.stream_segments WHERE stream_id = ?", KEY);
        // 시험 시드 표에는 PK 뿐이다 — 여기서 만든 색인이 남으면 뒤 시험의 실행 계획이 달라진다.
        jdbc.execute("DROP INDEX IF EXISTS public.stream_segments_session_idx");
    }

    @Test
    void 회차_행은_세션_색인_범위로_읽고_이력을_훑지_않는다() {
        String text = plan(RoundRows.MEMBER_ROWS, ps -> {
            ps.setString(1, KEY);
            ps.setArray(2, ps.getConnection().createArrayOf("text", new String[] {"now-1", "now-2"}));
        });

        assertThat(text).as(text).contains("stream_segments_session_idx").contains("Index Cond");
        assertThat(text).as(text).doesNotContain("Seq Scan");
        assertThat(text).as(text).doesNotContain("Rows Removed by Filter: 2");
    }

    @Test
    void 회차_기준점은_세션_색인_범위로_첫_행을_찾는다() {
        String text = plan(TimelineOriginReader.ORIGINS_BY_ROUND, ps -> {
            var con = ps.getConnection();
            ps.setArray(1, con.createArrayOf("text", new String[] {"S-now"}));
            ps.setArray(2, con.createArrayOf("text", new String[] {KEY}));
            ps.setArray(3, con.createArrayOf("text", new String[] {"S-now", "S-now"}));
            ps.setArray(4, con.createArrayOf("text", new String[] {"now-1", "now-2"}));
        });

        assertThat(text).as(text).contains("stream_segments_session_idx").contains("Index Cond");
        assertThat(text).as(text).doesNotContain("Seq Scan on stream_segments");
        // 세션 목록을 거르개로 붙이면 이력 행 수만큼 버린다(PK 를 처음부터 훑은 모양)
        assertThat(text).as(text).doesNotContain("Rows Removed by Join Filter");
    }

    private interface Binder {
        void bind(java.sql.PreparedStatement ps) throws java.sql.SQLException;
    }

    private String plan(String sql, Binder binder) {
        List<String> plan = new ArrayList<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement("EXPLAIN (ANALYZE, BUFFERS) " + sql);
            binder.bind(ps);
            return ps;
        }, rs -> {
            plan.add(rs.getString(1));
        });
        return String.join("\n", plan);
    }
}
