package com.pokeclip.chat.collector.broadcast;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** V304가 실제 DB에 남긴 모양을 본다 — 파일이 있다고 적용된 것은 아니다. */
@SpringBootTest
@ActiveProfiles("test")
class StopReasonColumnTest extends IntegrationTestSupport {

    private final JdbcTemplate jdbc;

    StopReasonColumnTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Test
    void 포기_사유_칸이_NULL_허용_VARCHAR_32로_있다() {
        Map<String, Object> column = jdbc.queryForMap("""
                SELECT is_nullable, data_type, character_maximum_length
                  FROM information_schema.columns
                 WHERE table_name = 'chat_ended_streams' AND column_name = 'stop_reason'
                """);
        assertThat(column.get("is_nullable")).isEqualTo("YES");
        assertThat(column.get("data_type")).isEqualTo("character varying");
        assertThat(column.get("character_maximum_length")).isEqualTo(32);
    }

    @Test
    void V304가_chat_이력_테이블에_남아_있다() {
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history_chat WHERE version = '304' AND success",
                Integer.class);
        assertThat(rows).isEqualTo(1);
    }
}
