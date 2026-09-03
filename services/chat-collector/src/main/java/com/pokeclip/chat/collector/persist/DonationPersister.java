package com.pokeclip.chat.collector.persist;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 후원 바구니를 비워 {@code chat_donations}에 배치 INSERT 한다.
 * <b>{@link ChatPersister}의 축소판이다</b> — 전용 스레드 하나, 1초 flush, 실패하면
 * 앞으로 되돌리고 재시도.
 *
 * <p><b>없는 것 둘을 적어 둔다</b>(있는 것보다 없는 것이 헷갈린다):
 * <ul>
 *   <li><b>{@code ON CONFLICT}가 없다.</b> 후원에는 지문 UNIQUE가 없다 — 치지직 세션이
 *       같은 후원을 두 번 주지 않고(채팅과 달리 재연결 백필도 없다), 지문을 만들 재료
 *       (고유 ID·시각)도 없다. 넣으면 「같은 사람이 같은 금액을 두 번」이 한 건으로 접힌다</li>
 *   <li><b>포이즌 단건 격리가 없다.</b> 격리가 막는 것은 NUL 본문인데 그것은
 *       {@link PersistableDonation}의 compact 생성자가 생성 지점에서 지운다.
 *       그 밖의 영구 오류는 실측된 적이 없어 「모르면 보존」쪽에 둔다 — 되돌리고 재시도한다.
 *       실제로 영구 오류가 오면 바구니가 차서 {@code droppedCount}가 오르므로 조용하지 않다</li>
 * </ul>
 */
@Component
public class DonationPersister {

    private static final int DRAIN_MAX = 500;

    private static final String INSERT = """
            INSERT INTO chat_donations
              (stream_id, channel_id, donator_channel_id, donator_nickname,
               donation_type, pay_amount, donation_text, received_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final Logger log = LoggerFactory.getLogger(DonationPersister.class);

    private static final Duration FLUSH_PERIOD = Duration.ofSeconds(1);

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "chzzk-donation-persist");
                t.setDaemon(true);
                return t;
            });

    private final JdbcTemplate jdbc;
    private final DonationBuffer buffer;
    private final AtomicLong persisted = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    public DonationPersister(JdbcTemplate jdbc, DonationBuffer buffer) {
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
                // 예외가 여기로 새면 scheduleAtFixedRate가 조용히 멈춰 적재가 영영 끊긴다.
                log.warn("chat.donation.flush_failed causeType={}", e.getClass().getSimpleName());
            }
        }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * <b>public인 이유</b>: 다른 패키지의 배선 검사({@code session.SessionRegistryTest})가
     * 「소켓 → 바구니 → 표」를 관통해 재려면 1초 틱을 기다리는 대신 여기서 밀어야 한다.
     * private으로 되돌리면 그 검사가 폴링으로 바뀌고 틱 주기에 매인다.
     */
    public void flushBacklog() {
        while (flushOnce() > 0) {
            // 소비가 있는 동안 계속 비운다
        }
    }

    public int flushOnce() {
        List<PersistableDonation> batch = buffer.drain(DRAIN_MAX);
        if (batch.isEmpty()) {
            return 0;
        }
        try {
            jdbc.batchUpdate(INSERT, batch.stream().map(DonationPersister::toRow).toList());
            persisted.addAndGet(batch.size());
            return batch.size();
        } catch (DataAccessException e) {
            // 본문·닉네임을 로그에 싣지 않는다. 건수와 타입만 — 채팅 쪽과 같은 규칙이다.
            buffer.restoreFront(batch);
            log.warn("chat.donation.persist_failed size={} causeType={}",
                    batch.size(), e.getClass().getSimpleName());
            return 0;
        }
    }

    public long persistedCount() {
        return persisted.get();
    }

    /**
     * 마지막 flush를 스케줄러 스레드에 제출하고 기다린다 — drain하는 스레드가 언제나
     * 하나라는 불변식이 여기서도 유지된다. 예산 5초는 {@link ChatPersister#close()}와
     * 같은 값이고, 그 둘은 <b>나란히가 아니라 차례로</b> 돈다
     * ({@code CollectorRunner.closeSinks}) — 종료 예산 산수는 그쪽 주석에 있다.
     */
    @PreDestroy
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            Future<?> lastFlush = scheduler.submit(this::flushBacklog);
            scheduler.shutdown();
            lastFlush.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("chat.donation.close_interrupted");
        } catch (Exception e) {
            log.warn("chat.donation.close_timeout causeType={}", e.getClass().getSimpleName());
        }
        if (buffer.size() > 0) {
            log.warn("chat.donation.shutdown_left size={}", buffer.size());
        }
    }

    private static Object[] toRow(PersistableDonation donation) {
        return new Object[] {
                donation.streamId(), donation.channelId(), donation.donatorChannelId(),
                donation.donatorNickname(), donation.donationType(), donation.payAmount(),
                donation.donationText(),
                Timestamp.from(Instant.ofEpochMilli(donation.receivedAtMillis()))
        };
    }
}
