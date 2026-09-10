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

    /**
     * 🔴 <b>시각 색인은 CONCURRENTLY 로 만든다 — 배포가 채팅을 버리지 않게</b>(봇 codex P1).
     *
     * <p>처음엔 {@code V306} 안에서 보통 {@code CREATE INDEX} 로 만들었고 근거는
     * 「조건이 {@code stream_id IS NOT NULL} 이라 쓸 엔트리가 0개」였다. <b>그 근거가 낡았다</b> —
     * {@code V302}(stream_id 칸)가 이미 배포돼 그 뒤 수집이 채운 행이 쌓여 있다.
     * 보통 {@code CREATE INDEX} 는 그 표에 잠금을 걸어 INSERT 를 막고, 빌드가 길어지면
     * 적재가 멈춰 바구니가 차고 <b>라이브 채팅이 버려진다.</b> 채팅은 되돌릴 수 없다.
     *
     * <p><b>파일 내용으로 잰다.</b> 다 돌고 난 DB 에는 색인이 「어떻게 만들어졌는지」가 안 남아,
     * 스키마를 조회해서는 CONCURRENTLY 였는지 알 수 없다. 그리고 이것은 <b>운영 배포의 성질</b>이라
     * Testcontainers 의 빈 DB 에서는 애초에 재현되지 않는다.
     */
    @Test
    void 시각_색인은_트랜잭션_밖에서_CONCURRENTLY_로_만든다() throws Exception {
        String sql = new String(getClass().getResourceAsStream(
                "/db/migration/V309__chat_messages_stream_time_index_concurrently.sql")
                .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(sql)
                .as("보통 CREATE INDEX 는 배포 중 채팅 적재를 막는다")
                .contains("CREATE INDEX CONCURRENTLY");
        assertThat(sql)
                .as("이 줄이 빠지면 Flyway 가 트랜잭션으로 감싸고 CONCURRENTLY 가 통째로 실패한다")
                .contains("executeInTransaction=false");
        assertThat(sql)
                .as("CONCURRENTLY 는 Flyway 락 밖이라 인스턴스 둘이 겹치면 진 쪽이 부팅 실패한다")
                .contains("IF NOT EXISTS");

        // 🔴 <b>주석을 걷어내고 본다.</b> V306 은 「여기 있던 CREATE INDEX 를 V309 로 옮겼다」를
        // 주석으로 설명하므로, 글자만 찾으면 그 설명에 걸려 아무것도 안 재게 된다.
        String v306Ddl = new String(getClass().getResourceAsStream(
                "/db/migration/V306__chat_messages_nickname_role.sql")
                .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .lines().filter(line -> !line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(v306Ddl)
                .as("색인이 V306 으로 돌아가면 트랜잭션 안에서 잠금을 건다")
                .doesNotContain("CREATE INDEX");
    }
}
