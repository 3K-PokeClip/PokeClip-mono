package com.pokeclip.chat.collector.broadcast;

import com.pokeclip.chat.collector.StopReason;
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

    /**
     * <b>넘치면 조용히 삼켜진다.</b> {@code EndedStreamStore.rememberStopped}의 INSERT가 22001로
     * 거절되면 {@code StoppedStreamRecorder.record}의 {@code catch (Throwable)}가 경고 한 줄만
     * 남기고 지나간다 — 메모가 안 남아 창구가 그 방송에 {@code unknown}(배너 끔)을 답한다.
     * <b>컴파일도 다른 검사도 안 깨지므로 이름을 더하는 사람에게 알려 줄 것이 이 검사뿐이다.</b>
     *
     * <p>폭(32)을 여기 박지 않는다 — 칸을 넓히는 마이그레이션이 오면 이 검사가 따라와야 한다.
     */
    @Test
    void 포기_사유_이름이_전부_칸_폭_안에_들어간다() {
        Integer width = jdbc.queryForObject("""
                SELECT character_maximum_length
                  FROM information_schema.columns
                 WHERE table_name = 'chat_ended_streams' AND column_name = 'stop_reason'
                """, Integer.class);
        assertThat(width).as("칸이 없으면 아래 비교가 통째로 헛돈다").isNotNull();

        for (StopReason reason : StopReason.values()) {
            assertThat(reason.name().length())
                    .as("%s(%d자)가 stop_reason(%d자)을 넘는다 — 그 사유로 포기하면 메모가 조용히 안 남는다."
                            + " 칸을 넓히는 V3xx를 더해라(V304는 이미 적용돼 있어 못 고친다)",
                            reason, reason.name().length(), width)
                    .isLessThanOrEqualTo(width);
        }
    }

    @Test
    void V304가_chat_이력_테이블에_남아_있다() {
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history_chat WHERE version = '304' AND success",
                Integer.class);
        assertThat(rows).isEqualTo(1);
    }
}
