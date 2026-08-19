package com.pokeclip.chat.collector.persist;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 버퍼를 비워 chat_messages에 배치 INSERT 한다. <b>drain은 자기 스케줄러 스레드에서만
 * 돈다</b> — 수신 스레드는 여기 안 들어오고, close의 마지막 flush도 같은 스레드에
 * 제출된다(두 스레드가 동시에 drain하는 경합이 구조적으로 없다). 유일한 예외는
 * 둘째 close의 잔량 회수인데, 그쪽은 스케줄러의 <b>완전 종료를 확인한 뒤에만</b>
 * 호출 스레드가 직접 drain하므로 같은 불변식이 유지된다.
 *
 * <p>멱등의 마지막 방어선은 코드가 아니라 표의 UNIQUE 제약이다. 같은 지문이
 * 두 번 오면 ON CONFLICT DO NOTHING이 둘째를 조용히 버린다 — 치지직이 메시지
 * 고유 ID를 안 줘서(공식 문서 확인) 지문은 누가+언제+본문해시다.
 *
 * <p><b>실패는 예외 타입으로 가른다.</b> 연결 장애류(기다리면 풀릴 수 있는 것)는
 * 몇 번을 반복돼도 보존·재시도하고, 비일시 데이터 오류(NUL 본문 등 포이즌)만
 * 단건 격리로 범인을 골라 버린다 — 횟수 휴리스틱으로 가르면 DB가 몇 초만 죽어
 * 있어도 멀쩡한 채팅이 격리로 넘어가 버려진다.
 */
@Component
public class ChatPersister implements PersistCounters {

    private static final int DRAIN_MAX = 2_000;   // 한 배치 상한. 폭주 시 여러 번 나눠 저장

    private static final String INSERT = """
            INSERT INTO chat_messages
              (stream_id, channel_id, sender_channel_id, content, message_time, received_at,
               content_sha256)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT ON CONSTRAINT uq_chat_messages_fingerprint DO NOTHING
            """;

    private static final Logger log = LoggerFactory.getLogger(ChatPersister.class);

    private static final Duration FLUSH_PERIOD = Duration.ofSeconds(1);

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "chzzk-persist");   // ping·요약과 다른 스레드
                t.setDaemon(true);
                return t;
            });

    private final JdbcTemplate jdbc;
    private final ChatBuffer buffer;
    private final AtomicLong persisted = new AtomicLong();
    private final AtomicLong conflicted = new AtomicLong();
    private final AtomicLong poisoned = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** 첫 close 호출자가 마지막 flush를 끝냈다는 신호 — 둘째부터는 이것을 기다린다. */
    private final CountDownLatch closeDone = new CountDownLatch(1);

    public ChatPersister(JdbcTemplate jdbc, ChatBuffer buffer) {
        this.jdbc = jdbc;
        this.buffer = buffer;
    }

    @PostConstruct
    public void start() {
        long periodMillis = FLUSH_PERIOD.toMillis();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                flushBacklog();
            } catch (RuntimeException e) {
                // flushOnce가 DataAccessException은 삼키지만, 그 밖의 예외가 여기로
                // 새면 scheduleAtFixedRate가 조용히 멈춰 적재가 영영 끊긴다.
                log.warn("chat.persist.flush_failed causeType={}", e.getClass().getSimpleName());
            }
        }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 버퍼가 비거나 진전이 없을 때까지 flush를 반복한다 — 한 틱이 DRAIN_MAX
     * 한 배치만 비우면 밀린 1만 건이 5초에 걸쳐 빠지고, 밀림이 밀림을 부른다.
     * flushOnce가 <b>소비 건수</b>를 반환하므로 0이면 진전이 없는 것이다
     * (실패 복원이거나 빈 큐).
     */
    void flushBacklog() {
        while (flushOnce() > 0) {
            // 소비가 있는 동안 계속 비운다
        }
    }

    /**
     * 멱등이되 <b>완료-대기</b>다 — 첫 호출자가 마지막 flush를 맡고, 둘째부터는
     * 그 완료를 시한부로 기다린 뒤 돌아온다. 판정 경로는 verdictLogged CAS 승자
     * 한 번뿐이라 판정 정확성 때문이 아니다 — 둘째 호출자는 대개 스프링 파괴
     * (@PreDestroy)이고, <b>기다리지 않고 돌아가면 컨텍스트가 flush 도중
     * DataSource를 닫아</b> 마지막 배치가 실패로 끝난다. 이 대기가 그 파괴를
     * flush 완료 뒤로 지연시킨다.
     *
     * <p>예산 산수 (정직하게): 러너 stop() 최악 대기 9초(기본 2초 + 반납 REST가
     * 나가 있으면 +7초) + 이 close 5초 = <b>최악 14초 + flush 실행분</b>.
     * stop의 선점 close가 <b>둘째</b>가 되는 경합(재연결 스레드가 판정 close를
     * 이미 진행 중)이면 둘째 대기 5초 + 스케줄러 종료 확인 1초로 <b>최악 15초 +
     * flush 실행분</b>이다. 반납 REST 최악(접속 2초+읽기 5초)까지 겹치면 더 늘 수
     * 있어, "최악 합산 ≤ 유예 20초 − 여유 2초"에 들도록 값들을 잡았다 — 유예
     * 권고는 services/README.md에 있다. "flush 실행분"은 반개방 스톨이면 JDBC
     * socketTimeout 10초까지 늘어나는데, 그때는 5초 대기가 먼저 끊겨 예산은 지켜지고
     * 그 배치를 잃는다 — 시한을 대기 안에 넣지 않은 이유는 위 산수다.
     */
    @PreDestroy
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            try {
                // 첫 호출자의 예산(get 5초)과 같은 5초 — 대부분 그 완료 직후 풀린다.
                // 10초로 늘리면 stop()의 선점 close가 둘째가 되는 경합(재연결 스레드가
                // 이미 판정 close를 진행 중)에서 종료 예산이 유예 20초를 넘는다:
                // stop 앞단 최악 9초 + 이 대기 + 스케줄러 종료 확인 1초 + 뒷정리.
                // 5초면 최악 합산이 15~16초 + flush 실행분으로 유예 20초 − 여유 2초
                // 안에 든다.
                // 트레이드오프: flush 실행분이 5초를 넘기면 미완인 채 돌아올 수 있다.
                if (!closeDone.await(5, TimeUnit.SECONDS)) {
                    log.warn("chat.persist.close_wait_timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("chat.persist.close_interrupted");
            }
            if (buffer.size() > 0 && awaitSchedulerTerminated()) {
                // DataSource가 아직 살아 있는 마지막 회수 기회다 — 첫 경로와 대칭으로
                // backlog 전량을 줍는다. 단, <b>스케줄러가 완전히 끝났을 때만</b>
                // 이 스레드가 직접 drain한다 — 첫 close가 타임아웃으로 끝나 태스크가
                // 아직 도는 중이면 drain 스레드가 둘이 되므로 생략하고 로그만 남긴다.
                flushBacklog();
            }
            if (buffer.size() > 0) {
                log.warn("chat.persist.shutdown_left size={}", buffer.size());
            }
            return;
        }
        try {
            // 마지막 flush도 스케줄러 스레드에 제출한다 — drain하는 스레드가 언제나
            // 하나라서, 진행 중이던 주기 flush와 이 flush가 겹치는 경합이 구조적으로
            // 없다(단일 스레드 직렬). 진행 중 flush가 있으면 그 뒤에 줄을 선다.
            Future<?> lastFlush = scheduler.submit(this::flushBacklog);
            scheduler.shutdown();   // 이미 제출된 것은 실행된다. 새 주기만 거부.
            // 5초 = 이 종료 단계의 예산. 러너 stop()의 최악 대기 9초와 합쳐
            // 14초 + flush 실행분 — 운영 종료 유예(20초 요청해 둠) 안에 든다.
            // INSERT 하나가 5초를 넘겨 매달리면 이 대기가 끊기고 그 배치는 못
            // 줍는다 — 유실 창을 없앴다고 주장하지 않는다, 좁혔을 뿐이다.
            //
            // <b>반개방 스톨은 정직하게 이 예산 밖이다.</b> DB가 연결만 받고 응답을
            // 안 하면 배치는 JDBC socketTimeout 10초(application.yml)에야 예외로
            // 끝나므로 이 5초 안에는 못 돌아온다 — 그 배치(최대 DRAIN_MAX)는 이
            // 종료에서 잃는다(close_timeout 로그가 단서). 예산을 10초로 늘리면
            // 둘째 close 경로가 유예 20초를 넘긴다(위 산수). 시한이 없던 때는
            // 종료가 아니라 <b>운영 중 저장이 통째로 멈추는</b> 것이 문제였고,
            // 그쪽을 socketTimeout이 막는다 — DatasourceTimeoutTest.
            lastFlush.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // 타임아웃 경로에서는 진행 중 배치가 어느 카운터에도 안 잡힌다 —
            // bufferSize는 큐에 남은 것만 세고, 스케줄러 스레드가 든 배치(최대
            // DRAIN_MAX)는 미관측이다. 상한을 값으로 드러내 정직하게 남긴다.
            log.warn("chat.persist.close_timeout bufferSize={} inFlightMax={}",
                    buffer.size(), DRAIN_MAX);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 무단서로 삼키면 "close가 그냥 빨리 끝났다"와 구분이 안 된다 —
            // stop()의 shutdownNow가 영구 정지 close 중인 재연결 스레드를
            // 인터럽트하는 경합이 정확히 이 자리로 온다.
            log.warn("chat.persist.close_interrupted");
        } catch (ExecutionException e) {
            // DataAccessException은 flushOnce가 삼키므로 여기 오는 것은 설정 오류류다.
            // 종료 경로에서 새어 나가면 러너의 stop()이 끊겨 판정 줄까지 잃는다 —
            // 잔량은 아래 shutdown_left가 세서 남긴다.
            log.warn("chat.persist.close_flush_failed causeType={}",
                    e.getCause().getClass().getSimpleName());
        } finally {
            closeDone.countDown();
        }
        if (buffer.size() > 0) {
            // 유실은 수용하되 관측은 남긴다 — 건수만, 본문은 싣지 않는다.
            log.warn("chat.persist.shutdown_left size={}", buffer.size());
        }
    }

    /**
     * 둘째 close가 직접 drain해도 되는지 — 스케줄러 스레드가 <b>완전히 끝났는지</b>를
     * 판정한다. {@code isTerminated()} 한 번 읽기로는 안 된다: 첫 close의
     * {@code lastFlush.get()}은 FutureTask.run()의 set(result)→finishCompletion()에서
     * 깨어나는데, 워커가 TERMINATED로 가는 것은 <b>그 뒤</b>(runWorker 탈출 →
     * processWorkerExit → tryTerminate)다. 즉 첫 close가 정상 반환한 직후에는
     * 언제나 "태스크는 끝났는데 아직 TERMINATED가 아닌" 창이 있고, 그 창에서
     * 둘째 close가 isTerminated()를 읽으면 false라 마지막 회수를 건너뛴다 — 이
     * 분기가 의도한 시나리오(DB 장애 복구 후 잔량 줍기)에서 정확히 유실이다.
     * awaitTermination은 그 뒷정리(마이크로초 단위)를 기다려 주므로 창이 닫힌다.
     * 첫 close가 타임아웃으로 끝나 태스크가 아직 도는 중이면 1초를 다 쓰고 false다.
     *
     * <p>창의 크기는 재지 못했다 — 기존 시나리오(첫 close DB 장애 → 둘째 close)를
     * 무압력 200회·스피너 40개 압력 2,000회 반복해도 isTerminated()가 false를 읽는
     * 회차가 0이었다(2026-08-15). 재현이 안 됐다는 뜻이지 창이 없다는 뜻이 아니다 —
     * 순서는 위 JDK 코드로 확정된다.
     *
     * @return true면 종료 확인. false는 시한 초과 또는 인터럽트(안전한 쪽 — drain 안 함).
     */
    private boolean awaitSchedulerTerminated() {
        try {
            // 1초 = 이 판정의 예산. 종료 예산 산수(둘째 경로)에 더해져 있다.
            return scheduler.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("chat.persist.close_interrupted");
            return false;
        }
    }

    /**
     * @return 큐에서 <b>소비를 확정한</b> 건수 — 저장·지문 충돌·격리 폐기 전부
     *         포함한다. 실패로 전량 되돌렸거나 큐가 비어 있으면 0.
     *         저장된 행 수가 필요하면 {@link #persistedCount()}의 차분을 본다.
     */
    public int flushOnce() {
        List<PersistableChat> batch = buffer.drain(DRAIN_MAX);
        if (batch.isEmpty()) {
            return 0;
        }
        try {
            List<Object[]> rows = batch.stream().map(ChatPersister::toRow).toList();
            int[] results = jdbc.batchUpdate(INSERT, rows);
            recordOutcome(batch.size(), savedRows(results));
            return batch.size();
        } catch (DataAccessException e) {
            if (isPoison(e)) {
                // 실측된 영구 데이터 오류 — 기다려도 안 풀리므로 단건으로 갈라
                // 범인만 버리고 나머지를 살린다.
                return insertOneByOne(batch);
            }
            // 격리 목록 밖은 전부 보존·재시도다("모르면 보존") — 연결 장애든
            // 드라이버가 뭉뚱그린 미분류든, 버리는 쪽이 유일한 치명 실패(유실)다.
            // 꺼낸 것을 <b>앞으로</b> 되돌린다 — 뒤로 되돌리면 시각 순서가 뒤집혀
            // 상한 초과 드롭이 더 새로운 채팅을 버린다.
            // 본문을 로그에 싣지 않는다. 타입 이름만.
            buffer.restoreFront(batch);
            log.warn("chat.persist.failed size={} causeType={}",
                    batch.size(), e.getClass().getSimpleName());
            return 0;
        }
    }

    /**
     * 배치 결과에서 저장된 행 수를 합한다. 음수({@code SUCCESS_NO_INFO} -2 등)가 하나라도
     * 있으면 <b>계상하지 않고 던진다</b> — 행별 정보가 없으면 충돌을 저장과 못 가르고,
     * 어느 쪽으로 세든 등식 received = persisted + conflicts + dropped가 조용히 거짓이
     * 된다(PR #56 P2 — 예전엔 전량 저장으로 계상해 충돌이 저장으로 둔갑했다). 이 값이
     * 오는 유일한 알려진 원인은 pgjdbc {@code reWriteBatchedInserts=true}이고 그것은
     * {@link RewriteBatchedInsertsGuard}가 부팅에서 거부한다 — 여기는 그 검사가 못
     * 본 DataSource(Hikari가 아닌 것)에 대한 마지막 방어선이다.
     *
     * <p>던지면 그 배치는 이미 표에 들어갔고 버퍼에서도 나갔다 — 유실이 아니라
     * 계상 불능이다. 예외는 틱의 catch로 올라가 {@code chat.persist.flush_failed
     * causeType=IllegalStateException}이 매초 남으므로 조용히 틀리지는 않는다.
     */
    private static int savedRows(int[] results) {
        int saved = 0;
        for (int count : results) {
            if (count < 0) {
                throw new IllegalStateException("배치 결과에 행 수가 없다(" + count + ") — "
                        + RewriteBatchedInsertsGuard.PROPERTY + "류 옵션이 켜져 있다. "
                        + "행별 결과 없이는 persisted/conflicts를 계상할 수 없다.");
            }
            saved += count;
        }
        return saved;
    }

    /**
     * 격리(폐기) 대상 — 원인 체인의 <b>SQLSTATE 클래스 "22"(data exception)</b>만.
     * NUL은 생성 지점(PersistableChat)에서 원천 소멸됐으므로 이 갈래는 <b>남는
     * 미지의 22류에 대한 심층 방어</b>다 — 실물 재현 근거는 timestamp 오버플로
     * (22008, 2026-08-15 실측) 테스트가 저장소 안에 든다. 스프링 예외 타입이
     * 아니라 SQLSTATE로 가르는 이유:
     * {@code DataIntegrityViolationException}은 FK 위반(23503) 같은 <b>참조 상태</b>
     * 문제도 덮는데, 그쪽은 데이터 잘못이 아니라 기다리면 풀릴 수 있다 — FK가
     * 붙는 날 23류가 여기 오면 보존된다. 목록 밖 미분류는 전부 보존·재시도다
     * ("모르면 보존").
     */
    private static boolean isPoison(DataAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.startsWith("22")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 격리 경로 — 배치가 포이즌으로 깨졌을 때 한 건씩 넣어 범인만 버린다.
     * 단건 실패가 포이즌이 아니면(장애가 격리 도중 시작) 그 행부터 전부 되돌리고
     * 즉시 복귀한다 — 그래서 이 루프가 배치 끝까지 도는 것은 실측된 포이즌을
     * 버릴 때뿐이고, 개별 왕복이 낭비될 조기 탈출 장치는 필요 없다.
     *
     * @return 소비를 확정한 건수(저장 + 충돌 + 폐기)
     */
    private int insertOneByOne(List<PersistableChat> batch) {
        int consumed = 0;
        int poisonedInBatch = 0;
        String firstCauseType = null;
        for (int i = 0; i < batch.size(); i++) {
            try {
                recordOutcome(1, jdbc.update(INSERT, toRow(batch.get(i))));
                consumed++;
            } catch (DataAccessException e) {
                if (!isPoison(e)) {
                    // 격리 도중 다른 실패가 시작됐다 — 이 행부터 전부 보존하고 복귀.
                    buffer.restoreFront(batch.subList(i, batch.size()));
                    log.warn("chat.persist.failed size={} causeType={}",
                            batch.size() - i, e.getClass().getSimpleName());
                    break;
                }
                poisoned.incrementAndGet();
                consumed++;
                poisonedInBatch++;
                if (firstCauseType == null) {
                    firstCauseType = e.getClass().getSimpleName();
                }
            }
        }
        if (poisonedInBatch > 0) {
            // 건당이 아니라 배치당 한 줄 — 도배성 포이즌이 로그를 도배하지 않게.
            // 본문을 싣지 않는다 — 포이즌 본문이 곧 유출 대상이다. 건수와 타입만.
            log.warn("chat.persist.poisoned count={} causeType={}", poisonedInBatch, firstCauseType);
        }
        return consumed;
    }

    /** 배치·단건 두 경로의 계상이 여기 한 곳이다 — DO NOTHING으로 스킵된 행이 충돌. */
    private void recordOutcome(int size, int saved) {
        persisted.addAndGet(saved);
        // 접힌 것 = 재연결 중복(정상) 또는 도배 병합(유실). 코드가 못 가르므로
        // 세서 요약에 드러낸다 — received = persisted + conflicts가 어긋나면
        // 그때 사람이 이 카운터부터 본다.
        conflicted.addAndGet(size - saved);
    }

    @Override
    public long persistedCount() {
        return persisted.get();
    }

    @Override
    public long conflictedCount() {
        return conflicted.get();
    }

    @Override
    public long poisonedCount() {
        return poisoned.get();
    }

    /** 순서는 record·INSERT와 같다 — 셋이 갈리면 값이 남의 칸에 조용히 들어간다. */
    private static Object[] toRow(PersistableChat chat) {
        return new Object[] {
                chat.streamId(), chat.channelId(), chat.senderChannelId(), chat.content(),
                Timestamp.from(Instant.ofEpochMilli(chat.messageTimeMillis())),
                Timestamp.from(Instant.ofEpochMilli(chat.receivedAtMillis())),
                sha256Hex(chat.content())
        };
    }

    static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256은 모든 JVM에 있다", e);
        }
    }
}
