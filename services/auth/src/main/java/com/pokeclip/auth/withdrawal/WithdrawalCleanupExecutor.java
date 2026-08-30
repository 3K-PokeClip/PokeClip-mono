package com.pokeclip.auth.withdrawal;

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
 * {@code youtube/YoutubeCleanupExecutor}의 복제다 — 한쪽을 고치면 나란히 놓고 비교한다.
 * 다른 것은 스레드 이름·로그 접두어와 {@link #SHUTDOWN_WAIT}뿐이다.
 *
 * <p>🔴 <b>치지직({@code ChzzkCleanupExecutor})이 아니라 유튜브 쪽을 베꼈다</b>(계획 검증 실측).
 * 치지직 것은 {@code shutdown()}이 {@code awaitTermination} → WARN뿐이고 <b>{@code shutdownNow()}가
 * 없어서</b> 시한이 지나도 잡이 끝까지 돈다(워커가 비데몬이라 더 그렇다). 그것을 베꼈으면
 * 아래 「5초에서 끊는다」가 거짓이 되고 — 실제로 남는 이유가 우리가 끊어서가 아니라 컨테이너가
 * SIGKILL로 죽여서가 된다 — 종료 예산 산수도 안 맞는다.
 *
 * <p>커밋 뒤 정리(스트림키 비밀값 삭제 · 사진 파일 삭제 · 순서 로그)를 <b>요청 스레드가 아닌
 * 전용 스레드</b>에서 돈다.
 *
 * <p>왜 afterCommit 안에서 직접 하지 않나 — JpaTransactionManager는 afterCommit 동기화를 다 돌린 뒤에야
 * 커넥션을 풀에 돌려준다. 그 안에서 {@code PostgresSecretStore.delete}(REQUIRES_NEW)를 부르면 원 커넥션을
 * 쥔 채 두 번째 커넥션을 요구해, 풀 크기(운영 10)만큼의 동시 요청에서 풀 데드락이 된다
 * (「알려진 구멍」 9 — 풀 10·동시 25에서 21/25 실패·30초 마비 실측). afterCommit 안에서는 <b>제출만</b> 한다.
 * 사진 창고는 그 위에 외부 HTTP(최대 8초)까지 얹는다.
 */
@Component
public class WithdrawalCleanupExecutor {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalCleanupExecutor.class);

    static final int THREADS = 2;
    static final int QUEUE_CAPACITY = 1000;
    /**
     * 🔴 <b>치지직 10초·유튜브 3초인데 여기는 5초다 — 셋 다 근거가 다르다.</b>
     *
     * <p>이 스레드가 기다리는 <b>가장 긴 것이 사진 창고 호출(최대 8초)</b>이다. 그런데 스프링이
     * {@code @PreDestroy}를 <b>순차로</b> 부르므로 세 풀의 대기가 <b>합산</b>된다 —
     * 8초를 그대로 잡으면 10 + 4 + 9 = <b>23초</b>가 되어 종료 유예를 그만큼 늘려야 한다.
     * 5초로 자르면 10 + 4 + 6 = <b>20초</b>이고 그것이 README에 적은 값이다.
     * {@code YoutubeShutdownBudgetTest}가 셋과 문서를 대조한다.
     *
     * <p>🔴 <b>이 값이 감수하는 것</b> — 창고가 <b>죽었으면</b> 더 기다려도 결과가 같지만,
     * <b>느리면</b>(6~8초) 8초를 기다렸을 때 지워졌을 사진이 안 지워진다. 그때 <b>표는 이미
     * 바뀌어 있어</b> 아무도 그 파일을 안 가리킨다. 그래서 로그를 짝으로 남긴다 —
     * {@code started}는 있는데 {@code completed}가 없는 회원 번호가 <b>남은 파일의 주인</b>이다.
     * {@code shutdown_timeout}은 「그런 일이 있었다」만 말하고 누구인지는 안 알려준다.
     *
     * <p>바꾸면 {@code services/README.md}의 「종료 유예 N초 이상」도 같이 고친다.
     */
    static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(5);
    /** 인터럽트한 뒤 워커가 빠져나올 때까지만. DB 삭제 하나 끝내는 시간이면 충분하다. */
    static final Duration FORCED_STOP_WAIT = Duration.ofSeconds(1);

    private final ThreadPoolExecutor pool;
    /** 제출·완료 카운터. awaitIdle이 큐·활성 수 대신 이것을 본다 — TPE의 take()~실행 사이 창에서 둘 다 0으로 보인다. */
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong finished = new AtomicLong();

    public WithdrawalCleanupExecutor() {
        AtomicInteger seq = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(THREADS, THREADS, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "withdrawal-cleanup-" + seq.incrementAndGet());
                    t.setDaemon(false);   // 종료 유예 동안 대기 중 삭제를 끝내려면 데몬이 아니어야 한다
                    return t;
                },
                (r, executor) -> {
                    log.warn("auth.withdrawal.cleanup.rejected userId={} reason={}",
                            r instanceof Job job ? job.userId : null,
                            executor.isShutdown() ? "shutdown" : "queue_full");
                    finished.incrementAndGet();   // 거부도 "끝"이다 — 안 세면 awaitIdle이 영영 기다린다
                });
    }

    /**
     * 현재 트랜잭션이 커밋되면 정리 action을 전용 스레드에 제출한다. 트랜잭션이 없으면
     * IllegalStateException — 그게 맞다(자기 호출로 프록시를 안 탄 것을 잡아 준다).
     *
     * <p>큐가 거부되면 정리가 통째로 사라지고 {@code cleanup.rejected} WARN만 남는다.
     * requestId는 잡이 값으로 옮긴다 — 전용 스레드에는 MDC가 없다.
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
     * 테스트 전용 진입점 — 운영은 {@link #afterCommit}만 쓴다. 커밋 전에 제출하면 아직 롤백될 수 있는
     * 탈퇴의 비밀값·사진을 먼저 지우게 된다.
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

    /**
     * 종료. 유예 안에 안 끝나면 <b>인터럽트한다</b>({@code shutdownNow}).
     *
     * <p>워커가 <b>비데몬</b>이라 인터럽트하지 않으면 JVM이 큐가 빌 때까지 안 죽는다 —
     * 우리가 문서화한 종료 유예를 넘기면 오케스트레이터가 SIGKILL로 끊어 <b>어차피 유실되면서
     * 배포만 느려진다</b>(유튜브 쪽에서 봇 3판이 실측으로 잡은 것과 같은 자리).
     *
     * <p>대가는 대기 중이던 삭제가 유실되는 것이다. 건수를 로그로 남겨 나중에 찾을 수 있게 한다 —
     * 다만 누구인지는 {@code started}/{@code completed} 짝으로만 알 수 있다.
     */
    @PreDestroy
    void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                int dropped = pool.getQueue().size() + pool.getActiveCount();
                log.warn("auth.withdrawal.cleanup.shutdown_timeout pending={}", dropped);
                pool.shutdownNow();   // 비데몬 워커를 깨워 JVM이 종료 유예 안에 죽게 한다
                if (!pool.awaitTermination(FORCED_STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.warn("auth.withdrawal.cleanup.shutdown_forced pending={}", dropped);
                }
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
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
                // 원인은 타입 이름만 — 메시지에 창고 응답 본문·비밀값 참조가 붙을 수 있다.
                log.warn("auth.withdrawal.cleanup.failed userId={} causeType={}", userId, e.getClass().getSimpleName());
            } finally {
                MDC.remove(RequestIdFilter.MDC_KEY);
                finished.incrementAndGet();
            }
        }
    }
}
