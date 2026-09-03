package com.pokeclip.chat.collector.persist;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V301이 실제 PostgreSQL에서 돌고, 멱등의 마지막 방어선인 지문 UNIQUE 제약이
 * 표에 서 있는지를 본다. 이력 테이블이 chat 전용인 것도 여기서 못박는다 —
 * 네 서버가 DB 하나를 공유하므로 기본 이름을 쓰면 나중에 뜬 쪽이 부팅에 실패한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ChatSchemaTest extends IntegrationTestSupport {

    private final JdbcTemplate jdbc;

    ChatSchemaTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Test
    void 마이그레이션_이력이_chat_전용_테이블에_남는다() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history_chat WHERE version = '301'",
                Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    /**
     * 창구(POK-234)가 「누가 말했나」를 화면에 띄우려면 칸 둘이 표에 있어야 하고,
     * 시각 범위 조회가 Seq Scan으로 떨어지지 않으려면 색인이 있어야 한다.
     * 색인 조건이 message_time인 이유는 보정값의 기준이 치지직 시계이기 때문이다.
     */
    @Test
    void 닉네임_역할_칸과_message_time_색인이_있다() {
        List<String> cols = jdbc.queryForList("""
            SELECT column_name FROM information_schema.columns
             WHERE table_name='chat_messages' AND column_name IN ('nickname','user_role')""",
                String.class);
        assertThat(cols).containsExactlyInAnyOrder("nickname", "user_role");

        String def = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname='idx_chat_messages_stream_message_time'",
                String.class);
        assertThat(def).contains("(stream_id, message_time)").contains("WHERE (stream_id IS NOT NULL)");
    }

    @Test
    void chat_messages_표와_지문_UNIQUE_제약이_있다() {
        Integer constraints = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints "
                        + "WHERE table_name = 'chat_messages' "
                        + "AND constraint_name = 'uq_chat_messages_fingerprint' "
                        + "AND constraint_type = 'UNIQUE'",
                Integer.class);
        assertThat(constraints).isEqualTo(1);
    }
}
