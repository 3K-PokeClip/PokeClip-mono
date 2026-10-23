package com.pokeclip.auth.soop;

import com.pokeclip.web.RequestIdFilter;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code youtube/YoutubeCleanupExecutor}의 복제다(그쪽이 {@code chzzk/ChzzkCleanupExecutor}의 복제다).
 * 한쪽을 고치면 나란히 놓고 비교한다. 다른 것은 스레드 이름과 로그 이벤트 접두어뿐이다.
 *
 * <p>커밋 뒤 정리(옛 secrets 삭제 2 · 순서 로그)를 <b>요청 스레드가 아닌 전용 스레드</b>에서 돈다.
 * afterCommit 안에서 {@code secretStore.delete}(REQUIRES_NEW)를 직접 부르면 원 커넥션을 쥔 채 두 번째를
 * 요구해 풀 데드락이 된다(치지직 감사 실측). afterCommit 안에서는 <b>제출만</b> 한다.
 *
 * <p>대가와 그 처리는 유튜브와 같다: 큐가 차면 {@code auth.soop.cleanup.rejected} WARN · {@code @PreDestroy}에서
 * 짧게 기다리고 넘기면 인터럽트 · requestId를 값으로 옮긴다. 프로세스가 죽으면 고아 secret이 남는다.
 */
@Component
public class SoopCleanupExecutor {

    private static final Logger log = LoggerFactory.getLogger(SoopCleanupExecutor.class);

    static final int THREADS = 2;
    static final int QUEUE_CAPACITY = 1000;
    /**
     * 유튜브와 같은 3초다. SOOP 정리 잡은 {@code secretStore.delete} 둘(DB)뿐이고 외부 HTTP가 아예 없다
     * (SOOP에는 폐기 API가 없다). 스프링이 {@code @PreDestroy}를 순차로 부르므로 합이 예산이다:
     * {@code YoutubeShutdownBudgetTest}가 네 풀을 더해 문서화한 종료 유예와 대조한다.
     */
    static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(3);
    /** 인터럽트한 뒤 워커가 빠져나올 때까지만. DB 삭제 하나 끝내는 시간이면 충분하다. */
    static final Duration FORCED_STOP_WAIT = Duration.ofSeconds(1);

    private final ThreadPoolExecutor pool;
    /** 제출·완료 카운터. awaitIdle이 큐·활성 수 대신 이것을 본다: TPE의 take()~실행 사이 창에서 둘 다 0으로 보인다. */
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong finished = new AtomicLong();

    public SoopCleanupExecutor() {
        AtomicInteger seq = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(THREADS, THREADS, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "soop-cleanup-" + seq.incrementAndGet());
                    t.setDaemon(false);   // 종료 유예 동안 대기 중 삭제를 끝내려면 데몬이 아니어야 한다
                    return t;
                },
                (r, executor) -> {
                    log.warn("auth.soop.cleanup.rejected userId={} reason={}",
                            r instanceof Job job ? job.userId : null,
                            executor.isShutdown() ? "shutdown" : "queue_full");
                    finished.incrementAndGet();   // 거부도 "끝"이다: 안 세면 awaitIdle이 영영 기다린다
                });
    }

    /**
     * 현재 트랜잭션이 커밋되면 정리 action을 전용 스레드에 제출한다. 트랜잭션이 없으면
     * IllegalStateException: 그게 맞다(자기 호출로 프록시를 안 탄 것을 잡아 준다).
     *
     * <p>로그의 자리: {@code relinked}·{@code unlinked}·{@code refresh_rejected}는 정리 잡 안에서
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
     * 테스트 전용 진입점: 운영은 {@link #afterCommit}만 쓴다. 커밋 전에 제출하면 REQUIRES_NEW delete가
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

    /**
     * 종료. 유예 안에 안 끝나면 <b>인터럽트한다</b>({@code shutdownNow}).
     *
     * <p>🔴 예전에는 로그만 찍고 반환했는데, 워커가 <b>비데몬</b>이라 JVM이 큐가 빌 때까지 안 죽었다:
     * 실측: {@code shutdown()}이 10초 만에 반환한 뒤에도 잡이 인터럽트 없이 끝까지 돌았다(봇 3판 P2-2).
     * 우리가 README·인프라에 적어 둔 <b>종료 유예 15초를 넘길 수 있다</b>는 뜻이고, 그러면 오케스트레이터가
     * SIGKILL로 끊어 <b>어차피 유실되면서 배포만 느려진다.</b>
     *
     * <p>대가는 <b>대기 중이던 secrets 삭제가 유실되는 것</b>이다: 다만 그것은 이 클래스가 처음부터
     * 문서화한 성질이다(위 「프로세스가 죽으면 고아 secret이 남는다」). 유실 건수를 로그로 남겨
     * 나중에 찾을 수 있게 한다.
     *
     * <p>🔴 <b>버려진 잡의 회원 번호를 한 줄씩 남긴다</b>(PR #148 codex C5, 감사 재현).
     * 예전에는 {@code shutdownNow()}의 반환값을 <b>버렸다.</b> 큐에서 뽑혀 나온 잡은 {@code run()}을
     * 못 하므로 {@code started}가 한 줄도 안 찍히고, 거부 핸들러도 안 탄다({@code shutdown()} 뒤에
     * <b>새로 들어온 것</b>만 잡는다): 이 클래스가 약속한 회복법(「{@code started}는 있는데
     * {@code completed}가 없는 회원을 찾는다」)이 <b>이 갈래에서만 성립하지 않았다.</b>
     *
     * <p>건수도 갈랐다. 옛 {@code pending}은 <b>돌던 것과 안 돈 것을 뭉친 숫자</b>라 「몇 명이 복구
     * 불가인가」조차 못 말했다: 돌던 것은 {@code started}가 찍혀 짝으로 찾을 수 있고, 큐에 있던 것은
     * 이 줄이 유일한 실마리다.
     *
     * <p>줄 수는 최악 큐 상한({@value #QUEUE_CAPACITY})만큼이다. 종료 시점에 한 번뿐이고,
     * <b>한 줄로 뭉치면 로그 시스템이 자르는 순간 뒷부분이 통째로 사라진다.</b>
     */
    @PreDestroy
    void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                int interrupted = pool.getActiveCount();
                // 비데몬 워커를 깨워 JVM이 종료 유예 안에 죽게 한다. 반환값은 큐에서 뽑혀 나온 잡들이고
                // 그것들은 run()을 못 하므로 여기서 안 남기면 회원 번호가 어디에도 안 남는다.
                List<Runnable> dropped = pool.shutdownNow();
                log.warn("auth.soop.cleanup.shutdown_timeout dropped={} interrupted={}",
                        dropped.size(), interrupted);
                for (Runnable job : dropped) {
                    log.warn("auth.soop.cleanup.dropped userId={}", job instanceof Job j ? j.userId : null);
                }
                if (!pool.awaitTermination(FORCED_STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.warn("auth.soop.cleanup.shutdown_forced dropped={} interrupted={}",
                            dropped.size(), interrupted);
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
                // 원인은 타입 이름만: 메시지에 응답 본문·ref가 붙을 수 있다.
                log.warn("auth.soop.cleanup.failed userId={} causeType={}", userId, e.getClass().getSimpleName());
            } finally {
                MDC.remove(RequestIdFilter.MDC_KEY);
                finished.incrementAndGet();
            }
        }
    }
}
