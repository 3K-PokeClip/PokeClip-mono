package com.pokeclip.auth.youtube;

import com.pokeclip.web.RequestIdFilter;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code chzzk/ChzzkCleanupExecutor}의 복제다 — 한쪽을 고치면 나란히 놓고 비교한다.
 * 다른 것은 스레드 이름과 로그 이벤트 접두어뿐이다(계획 2절 결정 1).
 *
 * <p>커밋 뒤 정리(옛 secrets 삭제 2 · 순서 로그)를 <b>요청 스레드가 아닌 전용 스레드</b>에서 돈다.
 *
 * <p>왜 afterCommit 안에서 직접 하지 않나 — JpaTransactionManager는 afterCommit 동기화를 다 돌린 뒤에야
 * 커넥션을 풀에 돌려준다. 그 안에서 {@code PostgresSecretStore.delete}(REQUIRES_NEW)를 부르면 원 커넥션을
 * 쥔 채 두 번째 커넥션을 요구해, 풀 크기(운영 10)만큼의 동시 갱신·해제·재연동에서 풀 데드락이 된다
 * (치지직 감사 3회차 실측: 25건 중 21건 500, 30초 마비, 고아 secrets 42). afterCommit 안에서는 <b>제출만</b> 한다.
 *
 * <p>대가와 그 처리 — ① 큐가 차면 조용히 사라지지 않고 {@code auth.youtube.cleanup.rejected} WARN(고아 행의
 * 원인을 찾을 수 있게) ② SIGTERM에 즉시 죽으면 대기 중 삭제가 유실되므로 {@code @PreDestroy}에서 짧게 기다린다
 * ③ 뒤처리에 구글 revoke(외부 HTTP 최대 5초)가 들어갈 수 있어 스레드 2개 ④ 그 스레드엔 MDC가 없다 —
 * requestId를 값으로 들고 가서 다시 넣는다. 프로세스가 죽으면 고아 secret이 남는 것은 전과 같다.
 */
@Component
public class YoutubeCleanupExecutor {

    private static final Logger log = LoggerFactory.getLogger(YoutubeCleanupExecutor.class);

    static final int THREADS = 2;
    static final int QUEUE_CAPACITY = 1000;
    static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

    private final ThreadPoolExecutor pool;
    /** 제출·완료 카운터. awaitIdle이 큐·활성 수 대신 이것을 본다 — TPE의 take()~실행 사이 창에서 둘 다 0으로 보인다. */
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong finished = new AtomicLong();

    public YoutubeCleanupExecutor() {
        AtomicInteger seq = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(THREADS, THREADS, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "youtube-cleanup-" + seq.incrementAndGet());
                    t.setDaemon(false);   // 종료 유예 동안 대기 중 삭제를 끝내려면 데몬이 아니어야 한다
                    return t;
                },
                (r, executor) -> {
                    log.warn("auth.youtube.cleanup.rejected userId={} reason={}",
                            r instanceof Job job ? job.userId : null,
                            executor.isShutdown() ? "shutdown" : "queue_full");
                    finished.incrementAndGet();   // 거부도 "끝"이다 — 안 세면 awaitIdle이 영영 기다린다
                });
    }

    /**
     * 현재 트랜잭션이 커밋되면 정리 action을 전용 스레드에 제출한다. 트랜잭션이 없으면
     * IllegalStateException — 그게 맞다(자기 호출로 프록시를 안 탄 것을 잡아 준다).
     *
     * <p>로그의 자리 — {@code relinked}·{@code unlinked}·{@code refresh_rejected}는 정리 잡 안에서
     * secrets 삭제 뒤에 찍힌다("정리까지 끝났다"는 순서 로그). 큐가 거부되면 그 로그도 함께 사라지고,
     * 그때 {@code cleanup.rejected} WARN이 신호다. requestId는 잡이 값으로 옮긴다.
     */
    public void afterCommit(Long userId, Runnable action) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(new Job(userId, requestId, action));
            }
        });
    }

    /**
     * 테스트 전용 진입점 — 운영은 {@link #afterCommit}만 쓴다. 커밋 전에 제출하면 REQUIRES_NEW delete가
     * 롤백 불능 상태(행은 살았는데 secret 없음)를 만든다.
     */
    void submit(Job job) {
        submitted.incrementAndGet();
        pool.execute(job);
    }

    /** 제출한 것이 전부 끝날(거부 포함) 때까지 기다린다. 테스트가 "결국 지워진다"를 재는 데 쓴다. */
    public boolean awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (finished.get() < submitted.get()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("auth.youtube.cleanup.shutdown_timeout pending={}",
                        pool.getQueue().size() + pool.getActiveCount());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 한 건의 정리. package-private인 것은 테스트가 {@link #submit}으로 직접 넣기 위해서다. */
    final class Job implements Runnable {
        private final Long userId;
        private final String requestId;
        private final Runnable action;

        Job(Long userId, String requestId, Runnable action) {
            this.userId = userId;
            this.requestId = requestId;
            this.action = action;
        }

        @Override
        public void run() {
            if (requestId != null) {
                MDC.put(RequestIdFilter.MDC_KEY, requestId);
            }
            try {
                action.run();
            } catch (RuntimeException e) {
                // 원인은 타입 이름만 — 메시지에 응답 본문·ref가 붙을 수 있다.
                log.warn("auth.youtube.cleanup.failed userId={} causeType={}", userId, e.getClass().getSimpleName());
            } finally {
                MDC.remove(RequestIdFilter.MDC_KEY);
                finished.incrementAndGet();
            }
        }
    }
}
