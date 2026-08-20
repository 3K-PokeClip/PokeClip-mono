package com.pokeclip.chat.collector.persist;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V302가 실제 PostgreSQL에서 돌았는가 — 방송 번호 컬럼과 그 부분 인덱스가
 * 적어 둔 모양 그대로 서 있는지 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
class StreamIdColumnTest extends IntegrationTestSupport {

    private final JdbcTemplate jdbc;

    StreamIdColumnTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 🔴 초안은 EXPLAIN 문자열에 인덱스 이름이 있는지 봤다. 계획 검증에서 중대로 잡혔다 —
     * <b>같은 인덱스인데 행 수와 통계 갱신 여부로 결과가 뒤집힌다</b>(실측):
     * rows=0 → Index Scan(우연히 통과) · rows=1/10/100 ANALYZE 후 → Seq Scan(실패)
     * · rows≥1000 → 통과. chat_messages는 컨테이너를 모듈 전체가 공유하고 다른 검사들이
     * 수백 건을 넣으므로 <b>실행 순서에 따라 빨간불</b>이다. 즉 인덱스가 아니라 옵티마이저
     * 통계를 재는 검사가 된다. 정의 자체를 단언한다 — 부분 인덱스 조건까지 문자열로 나온다.
     */
    // 문항 2: 인덱스가 없으면 queryForObject가 행 0으로 던진다 — 자동으로 참이 되지 않는다.
    // 문항 5: V302에서 WHERE 절을 빼면 조건 문자열이 없어 빨간불 — 확인함(주입 O).
    @Test
    void 방송_번호_인덱스가_부분_인덱스로_만들어져_있다() {
        String def = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_chat_messages_stream_received'",
                String.class);

        assertThat(def)
                .contains("(stream_id, received_at)")
                .contains("WHERE (stream_id IS NOT NULL)");
    }

    /**
     * 폭을 <b>선언이 아니라 실제 INSERT로</b> 잰다. 127로 잘못 잡거나 어딘가에서 길이를
     * {@code >=}로 거르면 <b>딱 128자짜리 멀쩡한 값이 조용히 버려지는데</b>, 짧은 값만 넣는
     * 양성 대조는 그것을 통과시킨다(태스크 6B에서 밟은 함정).
     *
     * <p>폭이 좁으면 PostgreSQL이 22001(string data right truncation)로 거절하고,
     * 그것은 SQLSTATE 22류라 격리 폐기로 간다 — <b>채팅이 사라지고 로그 한 줄만 남는다.</b>
     * 그래서 저장 여부만이 아니라 격리 수도 같이 본다.
     */
    // 문항 5: V302의 폭을 VARCHAR(127)로 되돌리면 poisoned=1로 빨간불 — 확인함(주입 P).
    @Test
    void 딱_128자_방송_번호도_잘리지_않고_저장된다() {
        String streamId = "s".repeat(128);
        ChatBuffer buffer = new ChatBuffer(10);
        buffer.offer(new PersistableChat(streamId, "ch-128", "u-128", "긴번호",
                1723600500000L, 1723600500175L));
        ChatPersister persister = new ChatPersister(jdbc, buffer);

        persister.flushOnce();

        assertThat(persister.poisonedCount())
                .as("폭이 좁으면 22001로 격리돼 그 채팅이 조용히 버려진다")
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT stream_id FROM chat_messages WHERE channel_id = 'ch-128'", String.class))
                .isEqualTo(streamId);
    }
}
