package com.pokeclip.chat.collector.persist;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 버퍼 → 표 경로와 멱등을 실제 PostgreSQL에서 잰다. 멱등의 마지막 방어선은
 * 코드가 아니라 표의 UNIQUE 제약이므로, 인메모리 대역으로는 이 검사가 성립하지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ChatPersisterTest extends IntegrationTestSupport {

    private final JdbcTemplate jdbc;

    ChatPersisterTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM chat_messages");
    }

    private static PersistableChat chat(String sender, String content, long time) {
        return new PersistableChat("ch-1", sender, content, time, time + 175);
    }

    @Test
    void 버퍼의_채팅이_표에_남는다() {
        ChatBuffer buffer = new ChatBuffer(100);
        buffer.offer(chat("s-1", "안녕", 1723600000000L));
        buffer.offer(chat("s-2", "ㅋㅋ", 1723600000001L));
        ChatPersister persister = new ChatPersister(jdbc, buffer);

        int saved = persister.flushOnce();

        assertThat(saved).isEqualTo(2);
        assertThat(count()).isEqualTo(2);
        assertThat(persister.persistedCount()).isEqualTo(2);
    }

    @Test
    void 같은_지문은_두_번_저장되지_않고_접힌_수가_남는다() {
        ChatBuffer buffer = new ChatBuffer(100);
        buffer.offer(chat("s-1", "안녕", 1723600000000L));
        ChatPersister persister = new ChatPersister(jdbc, buffer);
        persister.flushOnce();

        buffer.offer(chat("s-1", "안녕", 1723600000000L));   // 같은 사람·시각·본문
        int saved = persister.flushOnce();

        assertThat(saved).isZero();
        assertThat(count()).isEqualTo(1);
        // 접힌 것은 조용히 사라지면 안 된다 — 도배 병합이면 유실이므로 세서 드러낸다
        assertThat(persister.conflictedCount()).isEqualTo(1);
    }

    @Test
    void 한_배치_안의_중복도_한_건만_남는다() {
        ChatBuffer buffer = new ChatBuffer(100);
        buffer.offer(chat("s-1", "안녕", 1723600000000L));
        buffer.offer(chat("s-1", "안녕", 1723600000000L));
        new ChatPersister(jdbc, buffer).flushOnce();

        assertThat(count()).isEqualTo(1);
    }

    @Test
    void 시각이_UTC_기준으로_남는다() {
        ChatBuffer buffer = new ChatBuffer(100);
        buffer.offer(chat("s-1", "안녕", 1723600000000L));
        new ChatPersister(jdbc, buffer).flushOnce();

        OffsetDateTime messageTime = jdbc.queryForObject(
                "SELECT message_time AT TIME ZONE 'UTC' FROM chat_messages",
                (rs, i) -> rs.getObject(1, java.time.LocalDateTime.class).atOffset(ZoneOffset.UTC));
        assertThat(messageTime.toInstant().toEpochMilli()).isEqualTo(1723600000000L);
    }

    private long count() {
        return jdbc.queryForObject("SELECT count(*) FROM chat_messages", Long.class);
    }
}
