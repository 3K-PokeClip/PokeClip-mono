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
 * 시각 기준점 질의({@link TimelineOriginReader#ORIGINS})가 <b>색인 범위 스캔</b>을 타는지 잰다(POK-255 로컬 리뷰 1·2라운드).
 * 장부의 {@code stream_id}는 스트림키라 한 키에 그 스트리머의 조각 이력이 전부 쌓인다. 범위 조건이 색인 조건이 못 되면 방송 목록을 열
 * 때마다 그 이력 전체를 훑는다(30만 줄 실측 241ms). 행 몇 개짜리 기능 시험으로는 이 차이가 안 보여 따로 잰다.
 *
 * <p>색인은 media가 운영 장부에 만드는 것과 같은 모양({@code media/internal/index/ddl.go} {@code stream_segments_wall_idx})이다 —
 * 시험 시드 표에는 PK뿐이라 여기서 같은 것을 만든다.
 */
class TimelineOriginPlanTest extends IntegrationTestSupport {

    private static final String KEY = "s-plan";
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");

    private final JdbcTemplate jdbc;

    TimelineOriginPlanTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 이력이_긴_스트림키를_심는다() {
        방송과_카드를_비운다(jdbc);
        jdbc.execute("CREATE INDEX IF NOT EXISTS stream_segments_wall_idx ON stream_segments (stream_id, start_wall_utc DESC)");
        // 한 키에 4초 조각 2만 개(약 22시간) — 최근 방송은 맨 끝이다
        jdbc.update("""
                INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, playback_pdt, duration_ms, s3_key, upload_state)
                SELECT ?, g, g * 4000, ?::timestamptz + make_interval(secs => g * 4), ?::timestamptz + make_interval(secs => g * 4),
                       4000, 'k' || g, 'uploaded'
                  FROM generate_series(0, 19999) g""", KEY, Timestamp.from(START), Timestamp.from(START));
        jdbc.execute("ANALYZE stream_segments");
    }

    @AfterEach
    void 치운다() {
        jdbc.update("DELETE FROM stream_segments WHERE stream_id = ?", KEY);
    }

    @Test
    void 최근_방송의_기준점은_색인_범위로_찾고_이력을_훑지_않는다() {
        // 라이브(끝 없음) 방송 — 시작이 이력 맨 끝 쪽이다
        Timestamp lo = Timestamp.from(START.plusSeconds(19_900L * 4 - 120));
        List<String> plan = new ArrayList<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement("EXPLAIN (ANALYZE, BUFFERS) " + TimelineOriginReader.ORIGINS);
            ps.setArray(1, con.createArrayOf("text", new String[] {"S-plan-1"}));
            ps.setArray(2, con.createArrayOf("text", new String[] {KEY}));
            ps.setArray(3, con.createArrayOf("timestamptz", new Timestamp[] {lo}));
            ps.setArray(4, con.createArrayOf("timestamptz", new Timestamp[] {null}));
            return ps;
        }, rs -> {
            plan.add(rs.getString(1));
        });
        String text = String.join("\n", plan);

        assertThat(text).as(text).contains("stream_segments_wall_idx").contains("Index Cond");
        // 필터로 버린 행이 이력 크기만큼이면 색인이 범위 조건으로 안 쓰인 것이다
        assertThat(text).as(text).doesNotContain("Rows Removed by Filter: 19");
    }
}
