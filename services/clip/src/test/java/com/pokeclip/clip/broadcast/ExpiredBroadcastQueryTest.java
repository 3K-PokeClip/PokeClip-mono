package com.pokeclip.clip.broadcast;

import com.pokeclip.clip.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 만료 목록 질의(POK-335)가 색인을 타는지 실행 계획으로 잰다. 방송 줄은 만료 뒤에도 남아 끝없이 커지므로, 표 전체를 훑으면
 * 1번이 10분마다 부를 때마다 표 크기에 비례해 느려진다. 첫 장(만료 목록 부분 색인)과 줄마다 앞 · 뒤 방송 찾기(물리 키 · 자리 색인)
 * 둘 다 본다.
 */
class ExpiredBroadcastQueryTest extends IntegrationTestSupport {

    private final JdbcTemplate jdbc;

    ExpiredBroadcastQueryTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @BeforeEach
    @AfterEach
    void 비운다() {
        방송과_카드를_비운다(jdbc);
    }

    @Test
    void 만료_목록과_이웃_찾기가_색인을_탄다() {
        // 물리 키 2,000개 × 방송 10개. 절반은 기한이 지났고 절반은 안 끝났다(기한 없음)
        jdbc.update("""
                INSERT INTO broadcasts (stream_id, streamer_id, status, started_at, ended_at, vod_expires_at, ingest_stream_id, last_sequence)
                SELECT 'S-' || g, 'u', CASE WHEN g % 2 = 0 THEN 'ended' ELSE 'live' END,
                       TIMESTAMPTZ '2026-01-01 00:00:00+00' + (g || ' minutes')::interval,
                       CASE WHEN g % 2 = 0 THEN TIMESTAMPTZ '2026-01-01 00:30:00+00' + (g || ' minutes')::interval END,
                       CASE WHEN g % 2 = 0 THEN TIMESTAMPTZ '2026-03-01 00:00:00+00' + (g || ' minutes')::interval END,
                       'key-' || (g % 2000), 1
                  FROM generate_series(1, 20000) g""");
        jdbc.execute("ANALYZE broadcasts");

        String 계획 = 실행계획();
        assertThat(계획).contains("idx_broadcasts_vod_expires_at_id").contains("idx_broadcasts_ingest_key_position");
        assertThat(계획).doesNotContain("Seq Scan on broadcasts");
    }

    /** 이어받기(둘째 장)도 같은 색인으로 범위를 건너뛴다 */
    private String 실행계획() {
        String sql = ExpiredBroadcastReader.SQL;
        for (String value : List.of("TIMESTAMPTZ '2026-10-01 00:00:00+00'", "0", "0",
                String.valueOf(ExpiredBroadcastService.MAX_ROWS + 1))) {
            sql = sql.replaceFirst("\\?", value);
        }
        List<String> lines = jdbc.queryForList("EXPLAIN (ANALYZE) " + sql)
                .stream().map(row -> String.valueOf(row.get("QUERY PLAN"))).toList();
        int planningAt = lines.indexOf("Planning:");
        return String.join("\n", planningAt < 0 ? lines : lines.subList(0, planningAt));
    }
}
