package com.pokeclip.chat.collector.persist;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import org.springframework.dao.DataAccessResourceFailureException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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

    @Test
    void 스케줄러가_1초_안에_버퍼를_비운다() throws Exception {
        ChatBuffer buffer = new ChatBuffer(100);
        ChatPersister persister = new ChatPersister(jdbc, buffer);
        persister.start();
        try {
            buffer.offer(chat("s-1", "안녕", 1723600000000L));
            // 주기 1초 + 여유. 시각 단언이 아니라 결과 단언이다 — 간격을 재지 않는다
            // (하트비트 검사에서 배운 것: 간격 단언은 CPU 포화에서 거짓 빨간불).
            long deadline = System.currentTimeMillis() + 5_000;
            while (count() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertThat(count()).isEqualTo(1);
        } finally {
            persister.close();
        }
    }

    @Test
    void DB가_죽어도_버퍼가_보존되고_복구되면_저장된다() {
        ChatBuffer buffer = new ChatBuffer(100);
        AtomicBoolean down = new AtomicBoolean(true);
        // JdbcTemplate은 클래스라 상속으로 실패를 주입한다. 프록시·목 프레임워크 불필요.
        JdbcTemplate flaky = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public int[] batchUpdate(String sql, List<Object[]> args) {
                if (down.get()) {
                    throw new DataAccessResourceFailureException("db down");
                }
                return super.batchUpdate(sql, args);
            }
        };
        ChatPersister persister = new ChatPersister(flaky, buffer);
        buffer.offer(chat("s-1", "안녕", 1723600000000L));

        assertThat(persister.flushOnce()).isZero();     // 실패
        assertThat(buffer.size()).isEqualTo(1);          // 되돌아왔다

        down.set(false);
        assertThat(persister.flushOnce()).isEqualTo(1);  // 복구 후 저장
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void close가_남은_버퍼를_저장하고_나간다() {
        ChatBuffer buffer = new ChatBuffer(100);
        ChatPersister persister = new ChatPersister(jdbc, buffer);
        persister.start();
        buffer.offer(chat("s-1", "안녕", 1723600000000L));

        persister.close();

        assertThat(count()).isEqualTo(1);
    }

    /**
     * reviewer round 2 중대 1의 재현. 배치 도중 절단 → 재-offer로 이미 커밋된
     * 2,000건(=DRAIN_MAX)이 큐 앞에 돌아온 상태에서 재배포되면, close의 첫
     * flush가 전부 지문 충돌로 접혀 저장 수 0을 반환한다 — 저장 수로 도는 루프는
     * 여기서 탈출해 뒤의 새 채팅이 저장도 드롭 카운트도 없이 사라진다.
     */
    @Test
    void 잔량이_전부_지문_충돌이어도_close가_그_뒤의_새_채팅을_저장한다() {
        ChatBuffer buffer = new ChatBuffer(3_000);
        ChatPersister persister = new ChatPersister(jdbc, buffer);
        for (int i = 0; i < 2_000; i++) {
            buffer.offer(chat("s-" + i, "m", 1_723_600_000_000L + i));
        }
        persister.flushOnce();   // 2,000건 저장 — 다음 배치를 전부 충돌로 만들 준비

        for (int i = 0; i < 2_000; i++) {
            buffer.offer(chat("s-" + i, "m", 1_723_600_000_000L + i));   // 전부 기존 지문
        }
        buffer.offer(chat("s-new", "새것", 1_800_000_000_000L));          // 2,001번째 — 새 지문

        persister.close();

        assertThat(count())
                .as("첫 배치가 전부 접혔다고 루프를 빠지면 그 뒤의 새 채팅이 무관측 유실된다")
                .isEqualTo(2_001);
    }

    /**
     * DB 장애 중 종료의 유실은 수용하되(계획: "급사가 아닌 한") <b>관측은 남긴다</b> —
     * 관측 없이 삼키는 것과 세면서 접는 것은 다르다. 이 로그가 없으면 재배포 때
     * 사라진 잔량이 어디에도 안 남는다. 실패 시 버퍼 불변 → 루프가 무한이 아니라
     * 즉시 탈출한다는 것도 이 테스트가 같이 잰다(무한이면 5초 시한 없이 매달린다).
     */
    @Test
    void DB가_죽은_채_close하면_잔량을_건수만_로그로_남기고_나간다() {
        ChatBuffer buffer = new ChatBuffer(100);
        JdbcTemplate broken = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public int[] batchUpdate(String sql, List<Object[]> args) {
                throw new DataAccessResourceFailureException("db down");
            }
        };
        ChatPersister persister = new ChatPersister(broken, buffer);
        buffer.offer(chat("s-1", "안녕", 1723600000000L));

        try (LogCaptor captor = new LogCaptor()) {
            persister.close();

            assertThat(captor.messages())
                    .as("잔량이 로그 한 줄 없이 사라지면 유실을 아무도 모른다")
                    .anyMatch(m -> m.startsWith("chat.persist.shutdown_left size=1"));
        }
        assertThat(count()).isZero();
    }

    private long count() {
        return jdbc.queryForObject("SELECT count(*) FROM chat_messages", Long.class);
    }
}
