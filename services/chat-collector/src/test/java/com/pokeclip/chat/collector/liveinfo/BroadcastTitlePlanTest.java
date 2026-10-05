package com.pokeclip.chat.collector.liveinfo;

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
 * 여러 방송의 마지막 제목 질의({@link BroadcastInfoStore#LATEST_TITLES})가 색인을 타는지 잰다(POK-259). 방송 정보는 1분마다 한 줄이라 하루 방송이면
 * 1,440줄, clip 방송 목록 한 장이 최대 100개 방송이다. 색인을 안 타면 목록을 열 때마다 표 전체를 훑는다. 행 몇 개짜리 기능 시험으로는
 * 이 차이가 안 보여 따로 잰다(clip {@code TimelineOriginPlanTest}와 같은 이유).
 *
 * <p><b>방송 번호 접두는 {@code plan-bt-}다.</b> 컨테이너는 JVM에 하나뿐이라 앞 검사의 줄이 섞인다.
 */
@SpringBootTest
@ActiveProfiles("test")
class BroadcastTitlePlanTest extends IntegrationTestSupport {

    private static final String STREAM = "plan-bt-1";
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");

    private final JdbcTemplate jdbc;

    BroadcastTitlePlanTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 관측이_긴_방송을_심는다() {
        치운다();
        // 한 방송에 1분 관측 2만 줄(약 2주), 다른 방송 2만 줄. 운영처럼 거의 모든 관측에 제목이 있고 백 줄에 하나만 빈다.
        // 🔴 제목 있는 줄을 한 줄만 두면 플래너가 「제목 조건으로 거르면 1줄」로 보고 표 전체 훑기를 고른다(처음 이 시험이 그렇게
        // 빨간불이었다). 운영 분포가 아니라서 시드를 운영 쪽으로 맞췄다. 제목이 거의 다 빈 방송이 생기면 그 방송만 느려진다
        jdbc.update("""
                INSERT INTO broadcast_info (stream_id, channel_id, observed_at, live_title, category)
                SELECT CASE WHEN g % 2 = 0 THEN ? ELSE 'plan-bt-other' END, 'ch', ?::timestamptz + make_interval(mins => g),
                       CASE WHEN g % 100 = 0 THEN NULL ELSE '제목 ' || g END, 'Talk'
                  FROM generate_series(0, 39999) g""", STREAM, Timestamp.from(START));
        jdbc.update("""
                INSERT INTO broadcast_info (stream_id, channel_id, observed_at, live_title, category)
                VALUES (?, 'ch', ?, '마지막 제목', 'Talk')""", STREAM, Timestamp.from(START.plusSeconds(40_000L * 60)));
        jdbc.execute("ANALYZE broadcast_info");
    }

    @AfterEach
    void 치운다() {
        jdbc.update("DELETE FROM broadcast_info WHERE stream_id LIKE 'plan-bt-%'");
    }

    @Test
    void 방송_제목은_색인으로_최신부터_찾는다() {
        List<String> plan = new ArrayList<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement("EXPLAIN (ANALYZE, BUFFERS) " + BroadcastInfoStore.LATEST_TITLES);
            ps.setArray(1, con.createArrayOf("text", new String[] {STREAM}));
            return ps;
        }, rs -> {
            plan.add(rs.getString(1));
        });
        String text = String.join("\n", plan);

        assertThat(text).as(text).contains("idx_broadcast_info_stream_observed").contains("Index Cond");
        assertThat(text).as(text).doesNotContain("Seq Scan on broadcast_info");
        // 필터로 버린 행이 이력 크기만큼이면 색인을 범위로 안 쓰고 훑은 것이다
        assertThat(text).as(text).doesNotContain("Rows Removed by Filter: 19").doesNotContain("Rows Removed by Filter: 2");
    }
}
