package com.pokeclip.chat.collector.persist;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.TestPersistence;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.UncategorizedSQLException;

import java.sql.SQLException;
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
        int consumed = persister.flushOnce();

        // flushOnce는 저장 수가 아니라 소비 수를 준다 — 접힌 것도 큐에서는 나갔다.
        // 저장이 안 늘었다는 것은 persistedCount로 잰다.
        assertThat(consumed).isEqualTo(1);
        assertThat(persister.persistedCount()).isEqualTo(1);
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
     * PR #52 P2 ⑤. 틱 핸들러가 flushOnce 1회만 부르면 밀린 1만 건이 주기당
     * 2,000건씩 5초에 걸쳐 빠진다 — 밀림이 밀림을 부른다. 한 틱이 버퍼가
     * 비거나(또는 실패로 진전이 없거나) 할 때까지 반복해야 한다.
     */
    @Test
    void 한_틱이_DRAIN_MAX를_넘는_잔량도_전부_저장한다() {
        ChatBuffer buffer = new ChatBuffer(3_000);
        ChatPersister persister = new ChatPersister(jdbc, buffer);
        for (int i = 0; i < 2_500; i++) {
            buffer.offer(chat("s-" + i, "m" + i, 1_723_600_000_000L + i));
        }

        persister.flushBacklog();   // 스케줄 틱이 부르는 바로 그 경로

        assertThat(count()).isEqualTo(2_500);
        assertThat(buffer.size()).isZero();
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
     * code-review A. 연결 장애는 <b>몇 번을 반복돼도</b> 데이터 오류가 아니다 —
     * 횟수 휴리스틱으로 격리(단건 폐기)에 넘기면 DB가 3초만 죽어 있어도
     * 멀쩡한 채팅이 버려진다. 예외 타입이 연결 장애류면 무조건 보존·재시도다.
     */
    @Test
    void 연결_장애는_반복돼도_버리지_않고_보존한다() {
        ChatBuffer buffer = new ChatBuffer(100);
        JdbcTemplate down = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public int[] batchUpdate(String sql, List<Object[]> args) {
                throw new DataAccessResourceFailureException("db down");
            }
        };
        ChatPersister persister = new ChatPersister(down, buffer);
        buffer.offer(chat("s-1", "안녕", 1723600000000L));

        for (int i = 0; i < 30; i++) {
            persister.flushOnce();
        }

        assertThat(buffer.size())
                .as("연결 장애를 격리로 넘기면 DB가 잠깐 죽은 사이 멀쩡한 채팅이 버려진다")
                .isEqualTo(1);
        assertThat(buffer.droppedCount()).isZero();
        assertThat(count()).isZero();
    }

    /**
     * 격리 기계의 검증. NUL은 이제 생성 지점에서 제거돼 실데이터로는 22류를 만들
     * 수 없으므로 — 그게 ⑼의 목적이다 — 본문 "독"인 행만 SQLSTATE 22021로
     * 거부하는 가짜 JdbcTemplate로 "배치에 포이즌 한 건"을 재현한다.
     */
    @Test
    void 포이즌_행만_버려지고_나머지는_같은_flush에서_저장된다() {
        ChatBuffer buffer = new ChatBuffer(100);
        ChatPersister persister = new ChatPersister(
                TestPersistence.rejecting22(jdbc.getDataSource(), "독"), buffer);
        buffer.offer(chat("s-1", "정상1", 1723600000000L));
        buffer.offer(chat("s-2", "독", 1723600000001L));
        buffer.offer(chat("s-3", "정상2", 1723600000002L));

        int consumed = persister.flushOnce();   // 22류 — 즉시 단건 모드로 가른다

        assertThat(consumed)
                .as("통째로 복원만 하면 이 배치는 영원히 못 나간다")
                .isEqualTo(3);
        assertThat(count()).isEqualTo(2);
        assertThat(buffer.size()).isZero();
        assertThat(persister.persistedCount()).isEqualTo(2);
        // 포이즌은 조용히 사라지면 안 된다 — 자기 카운터로 센다.
        // dropped는 버퍼 상한 초과 전용이라 여기서는 0이어야 한다.
        assertThat(persister.poisonedCount()).isEqualTo(1);
        assertThat(buffer.droppedCount()).isZero();
    }


    /**
     * code-review 라운드 3 ⑼ (사용자 결정). PG TEXT가 거부하는 것은 사실상 NUL뿐 —
     * 생성 지점에서 제거해 포이즌을 원천 소멸시키고 채팅은 보존한다. 해시와 저장이
     * <b>같은 정규화된 본문</b>을 쓰는 불변식이 이 정규화 위치의 근거다 — 저장 직전에만
     * 지우면 지문과 실제 저장 본문이 갈려 중복 판정이 어긋난다.
     */
    @Test
    void NUL은_생성_지점에서_제거되고_해시와_저장이_같은_본문을_쓴다() {
        PersistableChat chat = new PersistableChat("ch", "s", "a\0b", 1L, 2L);

        assertThat(chat.content())
                .as("생성 지점에서 안 지우면 해시 본문과 저장 본문이 갈릴 수 있다")
                .isEqualTo("ab");
    }

    /** ⑼의 결과 — NUL 채팅은 격리가 아니라 정상 저장이다(제거된 본문으로). */
    @Test
    void NUL_본문_채팅은_제거된_본문으로_정상_저장된다() {
        ChatBuffer buffer = new ChatBuffer(100);
        ChatPersister persister = new ChatPersister(jdbc, buffer);
        buffer.offer(chat("s-1", "안\0녕", 1723600000000L));

        int consumed = persister.flushOnce();

        assertThat(consumed).isEqualTo(1);
        assertThat(persister.persistedCount()).isEqualTo(1);
        assertThat(persister.poisonedCount())
                .as("NUL이 격리로 갔다면 원천 소멸이 안 된 것이다")
                .isZero();
        assertThat(jdbc.queryForObject("SELECT content FROM chat_messages", String.class))
                .isEqualTo("안녕");
    }

    /**
     * code-review 라운드 3 ⑶. 격리 판정은 예외 타입이 아니라 <b>SQLSTATE 클래스
     * "22"(data exception)</b>다 — DataIntegrityViolationException이라도 22류가
     * 아니면(FK 위반 23503 등) 데이터가 아니라 참조 상태의 문제라 보존한다.
     */
    @Test
    void 무결성_위반이라도_SQLSTATE_22류가_아니면_보존한다() {
        ChatBuffer buffer = new ChatBuffer(100);
        JdbcTemplate fkViolating = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public int[] batchUpdate(String sql, List<Object[]> args) {
                throw new org.springframework.dao.DataIntegrityViolationException(
                        "fk", new SQLException("fk violation", "23503"));
            }
        };
        ChatPersister persister = new ChatPersister(fkViolating, buffer);
        buffer.offer(chat("s-1", "안녕", 1723600000000L));

        for (int i = 0; i < 3; i++) {
            persister.flushOnce();
        }

        assertThat(buffer.size())
                .as("23류를 격리로 넘기면 FK가 붙는 날 멀쩡한 채팅이 버려진다")
                .isEqualTo(1);
        assertThat(persister.poisonedCount()).isZero();
    }

    /**
     * code-review 라운드 2 ②. 격리(폐기) 대상은 실측된 무결성 위반 타입뿐이고,
     * <b>목록 밖 미분류는 전부 보존·재시도다</b> — 채팅 유실이 유일한 치명 실패라
     * 모르는 실패를 버리는 쪽이 더 나쁘다. 드라이버가 예외를 뭉뚱그리는 경우
     * (UncategorizedSQLException)가 정확히 이 자리다.
     */
    @Test
    void 분류_밖_실패는_보존한다() {
        ChatBuffer buffer = new ChatBuffer(100);
        JdbcTemplate odd = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public int[] batchUpdate(String sql, List<Object[]> args) {
                throw new UncategorizedSQLException("t", sql, new SQLException("모르는 실패"));
            }
        };
        ChatPersister persister = new ChatPersister(odd, buffer);
        buffer.offer(chat("s-1", "안녕", 1723600000000L));

        for (int i = 0; i < 5; i++) {
            persister.flushOnce();
        }

        assertThat(buffer.size())
                .as("분류 못 한 실패를 격리로 넘기면 모르는 장애마다 채팅이 버려진다")
                .isEqualTo(1);
        assertThat(persister.poisonedCount()).isZero();
        assertThat(count()).isZero();
    }

    /**
     * code-review 라운드 2 ①·라운드 3 ⑧. 둘째 호출자는 대개 스프링 파괴
     * (@PreDestroy)다 — 진입만 막히고 즉시 돌아가면 컨텍스트가 flush 도중
     * DataSource를 닫아 마지막 배치가 실패로 끝난다. 완료-대기가 그 파괴를
     * flush 완료 뒤로 지연시킨다.
     */
    @Test
    void 둘째_close는_첫_close의_flush_완료를_기다린다() throws Exception {
        ChatBuffer buffer = new ChatBuffer(100);
        JdbcTemplate slow = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public int[] batchUpdate(String sql, List<Object[]> args) {
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.batchUpdate(sql, args);
            }
        };
        ChatPersister persister = new ChatPersister(slow, buffer);
        buffer.offer(chat("s-1", "안녕", 1723600000000L));
        Thread first = new Thread(persister::close, "first-closer");
        first.start();
        Thread.sleep(200);   // 첫 close가 flush를 들고 있는 창

        persister.close();   // 둘째 — 기다리지 않으면 저장 전에 돌아온다

        assertThat(persister.persistedCount())
                .as("둘째 close가 즉시 돌아오면 컨텍스트 파괴가 flush 도중 DataSource를 닫는다")
                .isEqualTo(1);
        first.join(5_000);
    }

    /**
     * PR #52 P1 ③의 재현. 진행 중인 주기 flush가 배치를 든 채 오래 매달리면,
     * close의 짧은 대기가 타임아웃된 뒤 drain 루프는 빈 큐를 보고 끝난다 —
     * 그 뒤 매달렸던 flush가 실패해 배치를 되돌려도 아무도 안 줍는다.
     */
    @Test
    void 진행_중_flush가_오래_매달려도_close가_기다렸다가_잔량을_줍는다() throws Exception {
        ChatBuffer buffer = new ChatBuffer(100);
        AtomicBoolean slowOnce = new AtomicBoolean(true);
        JdbcTemplate slow = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public int[] batchUpdate(String sql, List<Object[]> args) {
                if (slowOnce.compareAndSet(true, false)) {
                    try {
                        Thread.sleep(3_000);   // close의 5초 예산 안 — 기다리면 줍는다
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new DataAccessResourceFailureException("slow then fail");
                }
                return super.batchUpdate(sql, args);
            }
        };
        ChatPersister persister = new ChatPersister(slow, buffer);
        persister.start();
        buffer.offer(chat("s-1", "안녕", 1723600000000L));
        Thread.sleep(1_200);   // 첫 틱(1초)이 배치를 들고 매달리는 중이다

        persister.close();

        assertThat(count())
                .as("타임아웃 후 루프를 접으면 늦게 되돌아온 배치가 무관측 유실된다")
                .isEqualTo(1);
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

    /**
     * <b>실물 22류의 격리 검증.</b> NUL이 생성 지점에서 소멸된 뒤에도 격리 기계가
     * 실제 PG에서 도는 근거를 저장소 안에 남긴다 — TIMESTAMPTZ 범위 밖
     * (epoch ms Long.MAX_VALUE, 서기 2억 9천만 년대)은 <b>SQLSTATE 22008</b>
     * (datetime field overflow)로 온다. 2026-08-15 실측:
     * {@code PSQLException state=22008} → {@code DataIntegrityViolationException}.
     */
    @Test
    void 실물_22류_timestamp_오버플로는_격리로_버려진다() {
        ChatBuffer buffer = new ChatBuffer(100);
        ChatPersister persister = new ChatPersister(jdbc, buffer);
        buffer.offer(chat("s-1", "정상", 1723600000000L));
        buffer.offer(new PersistableChat("ch-1", "s-2", "시각오버플로", Long.MAX_VALUE, Long.MAX_VALUE));

        int consumed = persister.flushOnce();

        assertThat(consumed).isEqualTo(2);
        assertThat(count()).isEqualTo(1);
        assertThat(persister.poisonedCount()).isEqualTo(1);
        assertThat(buffer.size()).isZero();
    }

    /**
     * code-review 라운드 4 ⑴. 둘째 close가 DataSource 생존의 마지막 회수 기회인데
     * 한 배치만 주우면 DRAIN_MAX 초과 잔량이 무관측 유실된다 — 첫 경로와 대칭으로
     * backlog 전량을 줍는다(스케줄러가 완전히 끝난 뒤에만 — 병행 drain 창 제거).
     * 같은 스레드에서 close를 연달아 부르므로 첫 close 반환 직후 — 워커가 아직
     * TERMINATED가 아닌 창 — 을 밟는다. 종료 판정을 isTerminated() 한 번 읽기로
     * 하면 이 검사가 조건에 따라 흔들린다(awaitTermination으로 판정하는 이유).
     */
    @Test
    void 둘째_close도_스케줄러가_끝났으면_잔량을_전부_줍는다() {
        ChatBuffer buffer = new ChatBuffer(3_000);
        AtomicBoolean down = new AtomicBoolean(true);
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
        for (int i = 0; i < 2_500; i++) {
            buffer.offer(chat("s-" + i, "m" + i, 1_723_600_000_000L + i));
        }

        persister.close();          // 첫 close — DB가 죽어 전량 보존된 채 끝난다
        down.set(false);
        persister.close();          // 둘째 — DataSource가 살아 돌아온 마지막 회수 기회

        assertThat(count())
                .as("한 배치만 주우면 DRAIN_MAX 초과 잔량이 무관측 유실된다")
                .isEqualTo(2_500);
        assertThat(buffer.size()).isZero();
    }

    private long count() {
        return jdbc.queryForObject("SELECT count(*) FROM chat_messages", Long.class);
    }
}
