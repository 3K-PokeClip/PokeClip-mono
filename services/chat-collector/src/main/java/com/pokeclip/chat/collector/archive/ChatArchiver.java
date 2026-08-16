package com.pokeclip.chat.collector.archive;

import com.pokeclip.chat.collector.reconnect.ReconnectPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 아카이브 스레드 {@code chzzk-archive}. 1초 틱 = [바구니 퍼가기 → 창에 쌓기 → 닫힌 창을 대기
 * 줄에] <b>무조건</b> + [백오프 시각이 지났으면 대기 줄 앞에서부터 업로드, 틱 예산 안에서].
 *
 * <p><b>백오프는 잠들지 않는다</b> — "다음 시도 시각"만 기억한다. 잠들면 그동안 퍼가기가
 * 멈춰 바구니가 차고 창이 안 닫힌다(PRD 결정 ②). 업로드 한 번은 S3 클라이언트의 명시
 * 시한(apiCall 4초) 안에 끝나므로 최악의 틱도 그 안에서 돌아온다 — 그동안 바구니(상한 1만)가
 * 받아 준다.
 *
 * <p>틱 예산 500ms: 밀린 파일이 많을 때 성공이 이어지면 예산까지 계속 올리고, 넘으면 다음
 * 틱으로 넘긴다 — 한 틱이 수십 초를 붙들면 그것도 퍼가기를 막는다. 실패는 즉시 중단(백오프).
 *
 * <p>업로더가 던진 어떤 예외도 틱을 죽이지 않는다 — scheduleAtFixedRate는 한 번 새면
 * 조용히 멈추고 아카이브가 영영 끊긴다(ChatPersister.start()와 같은 이유).
 */
public class ChatArchiver implements ChatArchive, ArchiveCounters {

    private static final Logger log = LoggerFactory.getLogger(ChatArchiver.class);
    static final Duration TICK = Duration.ofSeconds(1);
    static final long UPLOAD_BUDGET_MS = 500;
    static final int DRAIN_MAX = 5_000;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "chzzk-archive");
        t.setDaemon(true);
        return t;
    });

    private final ArchiveBuffer buffer;
    private final MinuteBatcher batcher;
    private final PendingUploads pending;
    private final ArchiveUploader uploader;
    private final ReconnectPolicy backoff;
    private final LongSupplier clock;

    private final AtomicLong archived = new AtomicLong();
    private final AtomicLong uploaded = new AtomicLong();
    /** 연속 실패 횟수 — 0이면 정상. 백오프 간격은 이 값으로 policy.delayFor(n). 아카이브 스레드만 쓴다. */
    private int consecutiveFailures;
    private long nextAttemptAtMillis;
    private boolean failureLogged;   // 첫 실패에만 WARN, 회복에 INFO — 도배 방지

    public ChatArchiver(ArchiveBuffer buffer, MinuteBatcher batcher, PendingUploads pending,
                        ArchiveUploader uploader, ReconnectPolicy backoff, LongSupplier clock) {
        this.buffer = buffer;
        this.batcher = batcher;
        this.pending = pending;
        this.uploader = uploader;
        this.backoff = backoff;
        this.clock = clock;
        this.nextAttemptAtMillis = clock.getAsLong();
    }

    public void start() {
        long period = TICK.toMillis();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                tick();
            } catch (RuntimeException e) {
                log.warn("chat.archive.tick_failed causeType={}", e.getClass().getSimpleName());
            }
        }, period, period, TimeUnit.MILLISECONDS);
    }

    @Override
    public void offer(ArchivableChat chat) {
        buffer.offer(chat);
    }

    /** 한 틱. 테스트가 직접 부른다. 아카이브 스레드(또는 테스트 스레드) 하나만 부른다. */
    void tick() {
        long now = clock.getAsLong();
        drainAndClose(now);
        uploadWithinBudget(now);
    }

    /** ①② 퍼가기·창 닫기 — 업로드 상태와 무관하게 항상. */
    private void drainAndClose(long now) {
        List<ArchivableChat> drained = buffer.drain(DRAIN_MAX);
        archived.addAndGet(drained.size());
        for (ArchiveObject closed : batcher.accept(drained, now)) {
            pending.enqueue(closed);
        }
    }

    /** ③ 백오프 시각이 지났으면 대기 줄 앞에서부터 올린다. 성공이 이어지면 예산까지, 실패면 즉시 중단. */
    private void uploadWithinBudget(long now) {
        if (now < nextAttemptAtMillis) {
            return;
        }
        long deadline = System.nanoTime() + UPLOAD_BUDGET_MS * 1_000_000L;
        ArchiveObject head;
        while ((head = pending.peek()) != null) {
            if (!uploadOne(head)) {
                return;
            }
            if (System.nanoTime() >= deadline) {
                return;
            }
        }
    }

    /** @return true면 올렸다(대기 줄에서 뺐다). false면 실패 — 백오프를 걸었다. */
    private boolean uploadOne(ArchiveObject object) {
        try {
            uploader.upload(object);
        } catch (ArchiveUploadException | RuntimeException e) {
            consecutiveFailures++;
            nextAttemptAtMillis = clock.getAsLong() + backoff.delayFor(consecutiveFailures).toMillis();
            if (!failureLogged) {
                // 첫 실패만. 키·본문은 안 싣는다 — 원인 타입과 대기 수만.
                Throwable cause = e instanceof ArchiveUploadException && e.getCause() != null ? e.getCause() : e;
                log.warn("chat.archive.upload_failed causeType={} pending={}", cause.getClass().getSimpleName(), pending.size());
                failureLogged = true;
            }
            return false;
        }
        pending.removeHead();
        uploaded.incrementAndGet();
        if (consecutiveFailures > 0) {
            log.info("chat.archive.upload_recovered afterFailures={} pending={}", consecutiveFailures, pending.size());
        }
        consecutiveFailures = 0;
        failureLogged = false;
        nextAttemptAtMillis = clock.getAsLong();
        return true;
    }

    // ── ChatArchive: close는 태스크 7 ──
    @Override
    public void beginClose() { }

    @Override
    public void awaitClosed(Duration budget) { }

    @Override
    public ArchiveCounters counters() {
        return this;
    }

    // ── ArchiveCounters ──
    @Override public long archivedCount() { return archived.get(); }
    @Override public long archiveBufferDroppedCount() { return buffer.droppedCount(); }
    @Override public long uploadedCount() { return uploaded.get(); }
    @Override public long pendingCount() { return pending.size(); }
    @Override public long droppedObjectsCount() { return pending.droppedObjects(); }
    @Override public long droppedMessagesCount() { return pending.droppedMessages(); }
    @Override public String runId() { return batcher.runId(); }
}
