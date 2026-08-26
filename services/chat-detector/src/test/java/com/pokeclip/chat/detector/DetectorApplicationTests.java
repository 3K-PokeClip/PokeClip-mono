package com.pokeclip.chat.detector;

import com.pokeclip.chat.detector.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DetectorApplicationTests extends IntegrationTestSupport {

    private final JdbcTemplate jdbc;

    DetectorApplicationTests(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Test
    void 컨텍스트가_뜬다() {
    }

    /**
     * 공유 DB에 남이 먼저 표를 만든 상태(IntegrationTestSupport가 더미 표를 심는다)에서
     * 우리 Flyway가 뜬다는 것을 잰다. baseline-on-migrate를 지우면 여기가 빨간불이다.
     */
    @Test
    void 남이_먼저_뜬_공유DB에서도_마이그레이션이_돈다() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history_chat_detector WHERE success = true",
                Integer.class);
        assertThat(count).isGreaterThan(0);
    }

    /** 장부 이름이 수집 서버와 갈려 있어야 한다. 같으면 나중에 뜬 쪽이 남의 이력을 자기 것으로 읽는다. */
    @Test
    void 장부_이름이_수집_서버와_다르다() {
        Integer ours = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'flyway_schema_history_chat_detector'",
                Integer.class);
        Integer theirs = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'flyway_schema_history_chat'",
                Integer.class);
        assertThat(ours).isEqualTo(1);
        assertThat(theirs).isZero();
    }

    @Test
    void 집계_표가_생겼고_같은_창은_두_번_안_들어간다() {
        jdbc.update("""
                INSERT INTO chat_metrics (stream_id, window_size_ms, window_start_ms, message_count, chatter_count)
                VALUES ('s1', 5000, 1000000, 10, 5)
                """);
        assertThat(jdbc.update("""
                INSERT INTO chat_metrics (stream_id, window_size_ms, window_start_ms, message_count, chatter_count)
                VALUES ('s1', 5000, 1000000, 99, 99)
                ON CONFLICT (stream_id, window_size_ms, window_start_ms) DO NOTHING
                """)).isZero();
    }
}
