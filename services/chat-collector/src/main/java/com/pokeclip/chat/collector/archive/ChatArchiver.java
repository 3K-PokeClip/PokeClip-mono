package com.pokeclip.chat.collector.archive;

import com.pokeclip.chat.collector.reconnect.ReconnectPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * <p><b>이 클래스의 catch는 셋 다 {@code Error}까지 잡는다</b>(uploadOne · safeTick · finalFlush).
 * RuntimeException만 잡으면 둘이 난다 — ① scheduleAtFixedRate는 태스크가 한 번이라도 던지면 예약을
 * 조용히 접어 아카이브가 영영 끊긴다 ② 업로드에서 안 잡으면 백오프가 안 걸려 같은 파일을 매 틱
 * 두드리고 경고가 초당 한 줄 쌓인다. 현실의 후보는 SDK 초기화 실패(NoClassDefFoundError)와 OOM이다.
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
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch closeDone = new CountDownLatch(1);
    /** 시한 초과 WARN은 프로세스 생애 한 번 — 러너가 closeSinks를 두 자리에서 지나 둘째가 awaitClosed(0)을 부른다. */
    private final AtomicBoolean closeTimeoutLogged = new AtomicBoolean();
    /** 연속 실패 횟수 — 0이면 정상. 백오프 간격은 이 값으로 policy.delayFor(n). 아카이브 스레드만 쓴다. */
    private int consecutiveFailures;
    /**
     * 다음 업로드 시도 시각. <b>실패했을 때만</b> 쓴다 — 0(=한 번도 실패 안 함)이면 언제나 시도한다.
     * 회복해도 0으로 안 돌아간다: 성공 경로에 남는 값은 <b>언제나 그 틱의 now 이하</b>라(문 {@code now >=
     * nextAttemptAtMillis}를 통과해야 업로드에 왔다) 뒤 틱을 못 막기 때문이다. 그래서 "0이 아니다"가
     * "백오프 중이다"를 뜻하지 않는다 — 이 필드로 백오프 여부를 판정하지 마라.
     */
    private long nextAttemptAtMillis;

    public ChatArchiver(ArchiveBuffer buffer, MinuteBatcher batcher, PendingUploads pending,
                        ArchiveUploader uploader, ReconnectPolicy backoff, LongSupplier clock) {
        this.buffer = buffer;
        this.batcher = batcher;
        this.pending = pending;
        this.uploader = uploader;
        this.backoff = backoff;
        this.clock = clock;
    }

    public void start() {
        long period = TICK.toMillis();
        scheduler.scheduleAtFixedRate(this::safeTick, period, period, TimeUnit.MILLISECONDS);
    }

    /**
     * 스케줄러가 부르는 틱 — <b>무엇도</b>(Error까지) 밖으로 안 낸다(클래스 주석). 업로더의 실패는 uploadOne이
     * 실패 1건으로 처리하므로 여기까지 오는 것은 그 밖(퍼가기·창·시계)의 결함뿐 — 최후 방어선이다.
     *
     * <p>여기에는 <b>도배 방지를 안 둔다</b>(upload_failed·encode_failed와 다르다). 매 틱 되풀이될 만한 결함이
     * 사실상 힙 압박(OOM)뿐이고, 그 상황이라면 초당 한 줄이 오히려 단서다 — 연속 실패를 세는 상태를 하나 더
     * 들여 얻을 것이 없다. 테스트가 직접 부른다.
     */
    void safeTick() {
        try {
            tick();
        } catch (Throwable e) {
            log.warn("chat.archive.tick_failed causeType={}", e.getClass().getSimpleName());
        }
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

    /**
     * ①② 퍼가기·창 닫기 — 업로드 상태와 무관하게 항상.
     *
     * <p>{@code archived}는 <b>창에 넣기 전에</b> 더한다. 그래서 인코드가 실패해 버려지는 건도 여기 실린다 —
     * 그 경로가 살아나면 "파일 줄 수 합 = archived"가 어긋난다. 지금은 도달 불가라 등식이 성립한다:
     * raw는 null이 아니고(JsonLinesEncoder가 거부) Jackson 3은 lone surrogate까지 이스케이프한다(실측).
     * 인코더를 바꿀 때 이 전제를 같이 본다.
     */
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

    /**
     * @return true면 올렸다(대기 줄에서 뺐다). false면 실패 — 백오프를 걸었다.
     *         업로더가 던진 <b>무엇이든</b>(Error까지) 실패 1건이다 — 이유는 클래스 주석.
     */
    private boolean uploadOne(ArchiveObject object) {
        try {
            uploader.upload(object);
        } catch (Throwable e) {
            consecutiveFailures++;
            nextAttemptAtMillis = clock.getAsLong() + backoff.delayFor(consecutiveFailures).toMillis();
            if (consecutiveFailures == 1) {
                // 연속 실패의 첫 건만 WARN(도배 방지), 회복에 INFO. 키·본문은 안 싣는다 — 원인 타입과 대기 수만.
                Throwable cause = e instanceof ArchiveUploadException && e.getCause() != null ? e.getCause() : e;
                log.warn("chat.archive.upload_failed causeType={} pending={}", cause.getClass().getSimpleName(), pending.size());
            }
            return false;
        }
        pending.markHeadUploaded();
        if (consecutiveFailures > 0) {
            log.info("chat.archive.upload_recovered afterFailures={} pending={}", consecutiveFailures, pending.size());
        }
        consecutiveFailures = 0;
        return true;
    }

    // ── ChatArchive: close ──

    /**
     * 첫 호출자가 마지막 flush를 <b>스케줄러 스레드에 제출</b>하고 즉시 돌아온다 — drain·창·대기
     * 줄을 만지는 스레드가 언제나 하나(ChatPersister.close와 같은 이유). 둘째부터는 아무것도
     * 안 한다. 러너는 이것을 부른 뒤 persister.close()를 부르고, 그 다음 awaitClosed()로 온다 —
     * 그래서 두 close가 나란히 돈다.
     */
    @Override
    public void beginClose() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.submit(this::finalFlush);   // 거부될 길이 없다 — shutdown은 아래 한 줄뿐이고 CAS 뒤라 한 번만 온다
        scheduler.shutdown();                 // 이미 제출된 것은 실행된다. 새 주기만 거부.
    }

    /** 마지막 flush 완료를 시한부로 기다린다. 못 기다리면 남은 수를 경고로 남긴다(한 번만 — closeTimeoutLogged). */
    @Override
    public void awaitClosed(Duration budget) {
        if (!closed.get()) {
            return;                       // beginClose를 안 불렀다
        }
        try {
            if (!closeDone.await(budget.toMillis(), TimeUnit.MILLISECONDS) && closeTimeoutLogged.compareAndSet(false, true)) {
                log.warn("chat.archive.close_timeout pending={} bufferSize={}", pending.size(), buffer.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("chat.archive.close_interrupted");
        }
    }

    @Override
    public ArchiveCounters counters() {
        return this;
    }

    /**
     * 종료 flush — 바구니를 전부 창에 넣고, 열린 창을 전부 닫아 대기 줄에 세운 뒤, 대기 줄을
     * <b>앞에서부터 한 번씩</b> 올린다. 백오프 시각을 보지 않는다(종료는 마지막 기회다).
     * <b>한 번 실패하면 재시도 없이</b> 남은 것을 전부 버리고 센다 — 창고가 죽어 있을 때 파일마다
     * 시한(4초)을 쓰면 유예 20초를 넘긴다. 성공이 이어지면 끝까지 올린다.
     *
     * <p><b>알려진 한계(사용자 지적, 계획 수락 시):</b> "성공이 이어지면 끝까지"라서 대기 줄이 수십 개
     * 남은 채 창고가 살아 있으면(장애 직후 회복된 순간 종료) 파일 수 × 왕복 시간이 awaitClosed 5초를
     * 넘길 수 있다 — 그때 awaitClosed는 시한에 돌아오고(close_timeout 로그) 나머지는 이 스레드가
     * 데몬으로 계속 올리다 프로세스가 내려가며 잃는다. 동작은 그대로 둔다(예산 안에서 최대한 올리는
     * 것이 목표다). README·CLAUDE.md 알려진 한계에 적는다.
     *
     * <p><b>퍼가기 while에 상한이 없는 이유</b>: 이 메서드로 오는 두 경로(러너 stop의 게이트 내림 뒤 ·
     * 영구 정지의 세션 정리 뒤) 모두 <b>새 채팅이 더 들어올 수 없는 상태</b>다 — 바구니는 줄기만 하므로
     * 반드시 빈다. 그 전제는 이 파일이 아니라 호출자(CollectorRunner)에 있다. 수신이 살아 있는 채로
     * 이 메서드를 부르게 바꾸면 폭주 중에는 루프가 안 끝난다.
     */
    private void finalFlush() {
        try {
            long now = clock.getAsLong();
            List<ArchivableChat> rest;
            while (!(rest = buffer.drain(DRAIN_MAX)).isEmpty()) {
                archived.addAndGet(rest.size());
                for (ArchiveObject c : batcher.accept(rest, now)) {
                    pending.enqueue(c);
                }
            }
            for (ArchiveObject c : batcher.closeAll()) {
                pending.enqueue(c);
            }
            ArchiveObject head;
            while ((head = pending.peek()) != null) {
                if (!uploadOne(head)) {
                    dropRemainingOnClose();
                    return;
                }
            }
        } catch (Throwable e) {
            // 업로더의 실패는 위 uploadOne이 삼키므로 여기까지 오는 것은 그 밖(퍼가기·창·시계)의 결함이다 —
            // Error까지 잡는다(클래스 주석). 안 잡으면 latch만 풀리고 로그 0줄·대기 줄은 pending에 남은 채 반쪽
            // 종료다. 대기 줄에 선 것은 못 올린 것이 확실하니 버리고 센다(비어 있으면 부르지 않는다 — 안 그러면
            // 아무것도 안 버렸는데 close_dropped objects=0이 남아 알람이 헛운다).
            //
            // 여기서 잃는 것 둘 중 **바구니**만 단서가 있다(bufferSize) — 아직 안 퍼갔으니 archived에도 안 실려
            // received 등식이 그만큼 안 닫힌다. **열린 창은 단서가 없다**: 그 채팅은 퍼갈 때 이미 archived에
            // 더해졌으므로(drainAndClose) 두 등식이 다 성립하는데 S3 파일은 안 생긴다. 알려진 한계다.
            int left = pending.size();   // 로그와 아래 가드가 같은 값을 봐야 한다 — 두 번 읽지 않는다
            log.warn("chat.archive.close_flush_failed causeType={} pending={} bufferSize={}",
                    e.getClass().getSimpleName(), left, buffer.size());
            if (left > 0) {
                dropRemainingOnClose();
            }
        } finally {
            closeDone.countDown();
        }
    }

    /** 종료에서 못 올린 것 — 대기 줄이 비우며 세고 드롭 카운터에 더한다. 여기는 로그만. */
    private void dropRemainingOnClose() {
        PendingUploads.Dropped dropped = pending.dropAll();
        log.warn("chat.archive.close_dropped objects={} messages={}", dropped.objects(), dropped.messages());
    }

    // ── ArchiveCounters ──
    @Override public long archivedCount() { return archived.get(); }
    @Override public long archiveBufferDroppedCount() { return buffer.droppedCount(); }
    @Override public long uploadedCount() { return pending.uploaded(); }
    @Override public long pendingCount() { return pending.size(); }
    @Override public long droppedObjectsCount() { return pending.droppedObjects(); }
    @Override public long droppedMessagesCount() { return pending.droppedMessages(); }
    @Override public String runId() { return batcher.runId(); }
}
