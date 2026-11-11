package com.pokeclip.clip.segment;

import com.pokeclip.clip.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 소리 주소 표지 갈래의 파일명 시각 축 읽기({@link StreamSegmentReader} — POK-196 · README 「{@code EXPLAIN} 검사」 관례)가 색인
 * 범위를 타는지 잰다. 한 키에 그 스트리머의 조각 이력이 다 쌓이므로, 회차 세션 · 시간창으로 범위를 못 자르면 요청마다 이력을
 * 훑는다. 장부 바로 앞 행 찾기는 행마다 PK 역순 한 행이다.
 *
 * <p>색인은 media 가 운영 장부에 만드는 것과 같은 모양({@code media/internal/index/ddl.go})이다 — 시험 시드 표에는 PK 뿐이라
 * 여기서 만든다.
 */
class WallAxisReadPlanTest extends IntegrationTestSupport {

    private static final String KEY = "s-wallplan";
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");

    private final JdbcTemplate jdbc;

    WallAxisReadPlanTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 이력이_긴_스트림키를_심는다() {
        방송과_카드를_비운다(jdbc);
        jdbc.execute("CREATE INDEX IF NOT EXISTS stream_segments_session_idx "
                + "ON public.stream_segments (stream_id, session_id, seq)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS stream_segments_wall_idx "
                + "ON public.stream_segments (stream_id, start_wall_utc DESC)");
        // 지난 세션 스무 개(각 1,000조각) 뒤 이번 회차 세션(450조각) — 최근 방송은 맨 끝이다
        jdbc.update("""
                INSERT INTO public.stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, playback_pdt, duration_ms,
                                                    s3_key, upload_state, session_id)
                SELECT ?, g, g * 4000, ?::timestamptz + g * 4000 * interval '1 millisecond',
                       ?::timestamptz + g * 4000 * interval '1 millisecond', 4000, 'k' || g, 'uploaded',
                       CASE WHEN g < 20000 THEN 'old-' || (g / 1000) ELSE 'now-1' END
                  FROM generate_series(0, 20449) g""", KEY, Timestamp.from(START), Timestamp.from(START));
        jdbc.execute("ANALYZE public.stream_segments");
    }

    @AfterEach
    void 치운다() {
        jdbc.update("DELETE FROM public.stream_segments WHERE stream_id = ?", KEY);
        // 시험 시드 표에는 PK 뿐이다 — 여기서 만든 색인이 남으면 뒤 시험의 실행 계획이 달라진다.
        jdbc.execute("DROP INDEX IF EXISTS public.stream_segments_session_idx");
        jdbc.execute("DROP INDEX IF EXISTS public.stream_segments_wall_idx");
    }

    @Test
    void 회차_창_읽기는_세션_색인과_PK_역순으로_읽고_이력을_훑지_않는다() {
        Timestamp from = Timestamp.from(START.plusSeconds(20_100L * 4));
        Timestamp to = Timestamp.from(START.plusSeconds(20_100L * 4 + 60));
        String text = plan(StreamSegmentReader.WALL_MEMBER_ROWS, ps -> {
            ps.setString(1, KEY);
            ps.setArray(2, ps.getConnection().createArrayOf("text", new String[] {"now-1"}));
            ps.setTimestamp(3, to);
            ps.setTimestamp(4, from);
        });

        assertThat(text).as(text).contains("stream_segments_session_idx").contains("stream_segments_pkey");
        assertThat(text).as(text).doesNotContain("Seq Scan");
    }

    @Test
    void 옛_방송_시간창_읽기는_벽시계_색인_범위다() {
        Timestamp lo = Timestamp.from(START.plusSeconds(20_100L * 4 - 120));
        String text = plan(StreamSegmentReader.WALL_WINDOW_ROWS, ps -> {
            ps.setString(1, KEY);
            ps.setTimestamp(2, lo);
            ps.setTimestamp(3, null);
            ps.setTimestamp(4, Timestamp.from(START.plusSeconds(20_100L * 4 + 60)));
            ps.setTimestamp(5, Timestamp.from(START.plusSeconds(20_100L * 4)));
        });

        assertThat(text).as(text).contains("stream_segments_wall_idx").contains("Index Cond");
        assertThat(text).as(text).doesNotContain("Seq Scan");
        assertThat(text).as(text).doesNotContain("Rows Removed by Filter: 2");
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
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
