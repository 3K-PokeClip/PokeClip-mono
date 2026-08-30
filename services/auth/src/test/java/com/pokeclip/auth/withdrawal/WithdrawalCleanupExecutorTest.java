package com.pokeclip.auth.withdrawal;

import ch.qos.logback.classic.Level;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code YoutubeCleanupExecutorTest}의 복제다 — 한쪽을 고치면 나란히 놓고 비교한다.
 * 다른 것은 클래스 이름과 로그 이벤트 접두어뿐이다.
 */
class WithdrawalCleanupExecutorTest {

    /** 큐가 차서 거부된 것도 "끝"으로 센다 — 안 세면 awaitIdle이 영영 기다리고, 조용히 사라지지 않고 WARN이 남는다. */
    @Test
    void 큐가_차면_거부를_WARN으로_남기고_awaitIdle은_끝난다() throws Exception {
        WithdrawalCleanupExecutor executor = new WithdrawalCleanupExecutor();
        try {
            CountDownLatch block = new CountDownLatch(1);
            AtomicInteger ran = new AtomicInteger();
            Runnable blocking = () -> {
                try {
                    block.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ran.incrementAndGet();
            };
            try (LogCaptor logs = new LogCaptor()) {
                try {
                    // 스레드 2개를 막고 큐 상한(1000)까지 채운 뒤 하나 더 — 그 하나가 거부된다.
                    for (int i = 0; i < WithdrawalCleanupExecutor.THREADS + WithdrawalCleanupExecutor.QUEUE_CAPACITY; i++) {
                        executor.submit(executor.new Job(1L, null, blocking));
                    }
                    executor.submit(executor.new Job(99L, null, ran::incrementAndGet));
                    assertThat(logs.messages())
                            .anyMatch(m -> m.equals("auth.withdrawal.cleanup.rejected userId=99 reason=queue_full"));
                    assertThat(logs.levelOf("auth.withdrawal.cleanup.rejected")).isEqualTo(Level.WARN);
                    assertThat(executor.awaitIdle(Duration.ofMillis(200))).as("막힌 잡이 남아 아직 안 끝났다").isFalse();
                } finally {
                    block.countDown();   // 단언이 실패해도 워커를 풀어 준다 — 안 그러면 shutdown이 5초 매달린다
                }
                assertThat(executor.awaitIdle(Duration.ofSeconds(10))).as("거부까지 세어 끝난다").isTrue();
                assertThat(ran.get()).isEqualTo(WithdrawalCleanupExecutor.THREADS + WithdrawalCleanupExecutor.QUEUE_CAPACITY);
            }
        } finally {
            executor.shutdown();
        }
    }

    /**
     * 🔴 유예 안에 안 끝나는 잡은 <b>인터럽트한다</b>. 워커가 비데몬이라 안 그러면 JVM이 큐가 빌 때까지
     * 안 죽고 종료 유예(20초)를 넘긴다.
     *
     * <p>🔴 <b>치지직({@code ChzzkCleanupExecutor})을 베끼면 이 검사가 빨간불이다</b> — 그쪽은
     * {@code shutdownNow()}가 없어 시한이 지나도 잡이 인터럽트 없이 끝까지 돈다(계획 검증 실측).
     * 그래서 이 파일의 원본은 유튜브 쪽이다.
     */
    @Test
    void 유예를_넘긴_잡은_인터럽트하고_종료한다() throws Exception {
        WithdrawalCleanupExecutor executor = new WithdrawalCleanupExecutor();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        executor.submit(executor.new Job(1L, null, () -> {
            started.countDown();
            try {
                Thread.sleep(WithdrawalCleanupExecutor.SHUTDOWN_WAIT.plusSeconds(30).toMillis());
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        long startedAt = System.nanoTime();
        executor.shutdown();
        long elapsed = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertThat(interrupted).as("잡이 인터럽트되지 않았다 — 비데몬 워커가 JVM을 붙잡는다").isTrue();
        assertThat(elapsed).as("종료 유예 20초 안에 끝나야 한다")
                .isLessThan(WithdrawalCleanupExecutor.SHUTDOWN_WAIT
                        .plus(WithdrawalCleanupExecutor.FORCED_STOP_WAIT).plusSeconds(2).toMillis());
    }

    /** 정상 갈래 — 유예 안에 끝나는 잡은 인터럽트 없이 완주한다. 위 검사가 「무조건 끊는다」로 바뀌면 여기서 걸린다. */
    @Test
    void 유예_안에_끝나는_잡은_그대로_완주한다() throws Exception {
        WithdrawalCleanupExecutor executor = new WithdrawalCleanupExecutor();
        AtomicBoolean finished = new AtomicBoolean();
        executor.submit(executor.new Job(1L, null, () -> {
            try {
                Thread.sleep(200);
                finished.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        executor.shutdown();

        assertThat(finished).as("유예 안에 끝나는 잡을 끊었다").isTrue();
    }
}
