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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

/**
 * 버퍼를 비워 chat_messages에 배치 INSERT 한다. <b>자기 스레드에서만 돈다</b>(태스크 5) —
 * 수신 스레드는 여기 안 들어온다.
 *
 * <p>멱등의 마지막 방어선은 코드가 아니라 표의 UNIQUE 제약이다. 같은 지문이
 * 두 번 오면 ON CONFLICT DO NOTHING이 둘째를 조용히 버린다 — 치지직이 메시지
 * 고유 ID를 안 줘서(공식 문서 확인) 지문은 누가+언제+본문해시다.
 */
@Component
public class ChatPersister {

    private static final int DRAIN_MAX = 2_000;   // 한 배치 상한. 폭주 시 여러 번 나눠 저장

    private static final String INSERT = """
            INSERT INTO chat_messages
              (channel_id, sender_channel_id, content, message_time, received_at, content_sha256)
            VALUES (?, ?, ?, ?, ?, ?)
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

    public ChatPersister(JdbcTemplate jdbc, ChatBuffer buffer) {
        this.jdbc = jdbc;
        this.buffer = buffer;
    }

    @PostConstruct
    public void start() {
        long periodMillis = FLUSH_PERIOD.toMillis();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                flushOnce();
            } catch (RuntimeException e) {
                // flushOnce가 DataAccessException은 삼키지만, 그 밖의 예외가 여기로
                // 새면 scheduleAtFixedRate가 조용히 멈춰 적재가 영영 끊긴다.
                log.warn("chat.persist.flush_failed causeType={}", e.getClass().getSimpleName());
            }
        }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void close() {
        scheduler.shutdown();      // 새 주기는 안 잡는다.
        try {
            // 진행 중인 주기 flush를 기다린다. 안 기다리면 그 flush가 배치를 든 동안
            // 아래 루프가 빈 큐를 보고 끝나고, 컨텍스트가 내려가 DataSource가 닫히면
            // 그 배치가 실패 → 재-offer → 무관측 유실이 된다 (reviewer round 2 사소 2).
            // 이중 저장은 없다 — drain이 소모적이고 UNIQUE가 최후 방어다.
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                log.warn("chat.persist.close_await_timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 종료 직전 마지막 저장 — 급사가 아닌 한 유실 창을 닫는다. 바닥날 때까지 도는데,
        // 탈출 조건은 저장 수가 아니라 <b>버퍼가 실제로 줄었는가</b>다: 전부 지문 충돌인
        // 배치는 처리되고도 저장 수 0을 반환하므로, 저장 수로 돌면 그 뒤 잔량이
        // 무관측 유실된다 (reviewer round 2 중대 1 — 테스트가 재현했다).
        // DB 장애면 재-offer로 버퍼가 안 줄어 기존처럼 즉시 탈출한다.
        int before;
        while ((before = buffer.size()) > 0) {
            flushOnce();
            if (buffer.size() >= before) {
                break;
            }
        }
        if (buffer.size() > 0) {
            // 유실은 수용하되 관측은 남긴다 — 건수만, 본문은 싣지 않는다.
            log.warn("chat.persist.shutdown_left size={}", buffer.size());
        }
    }

    public int flushOnce() {
        List<PersistableChat> batch = buffer.drain(DRAIN_MAX);
        if (batch.isEmpty()) {
            return 0;
        }
        try {
            List<Object[]> rows = batch.stream().map(ChatPersister::toRow).toList();
            int[] results = jdbc.batchUpdate(INSERT, rows);
            int saved = IntStream.of(results).sum();   // DO NOTHING으로 스킵된 행은 0
            persisted.addAndGet(saved);
            // 접힌 것 = 재연결 중복(정상) 또는 도배 병합(유실). 코드가 못 가르므로
            // 세서 요약에 드러낸다 — received = persisted + conflicts가 어긋나면
            // 그때 사람이 이 카운터부터 본다.
            conflicted.addAndGet(batch.size() - saved);
            return saved;
        } catch (DataAccessException e) {
            // DB가 죽어 있다. 꺼낸 것을 되돌리고 다음 주기에 다시 시도한다 —
            // 수집은 계속돼야 하므로 여기서 예외를 밖으로 내보내지 않는다.
            // 본문을 로그에 싣지 않는다. 타입 이름만.
            batch.forEach(buffer::offer);
            log.warn("chat.persist.failed size={} causeType={}",
                    batch.size(), e.getClass().getSimpleName());
            return 0;
        }
    }

    public long persistedCount() {
        return persisted.get();
    }

    public long conflictedCount() {
        return conflicted.get();
    }

    private static Object[] toRow(PersistableChat chat) {
        return new Object[] {
                chat.channelId(), chat.senderChannelId(), chat.content(),
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
