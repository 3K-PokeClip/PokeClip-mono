package com.pokeclip.auth.youtube;

import ch.qos.logback.classic.Level;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class YoutubeCleanupExecutorTest {

    /** 큐가 차서 거부된 것도 "끝"으로 센다 — 안 세면 awaitIdle이 영영 기다리고, 조용히 사라지지 않고 WARN이 남는다. */
    @Test
    void 큐가_차면_거부를_WARN으로_남기고_awaitIdle은_끝난다() throws Exception {
        YoutubeCleanupExecutor executor = new YoutubeCleanupExecutor();
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
                    for (int i = 0; i < YoutubeCleanupExecutor.THREADS + YoutubeCleanupExecutor.QUEUE_CAPACITY; i++) {
                        executor.submit(executor.new Job(1L, null, blocking));
                    }
                    executor.submit(executor.new Job(99L, null, ran::incrementAndGet));
                    assertThat(logs.messages())
                            .anyMatch(m -> m.equals("auth.youtube.cleanup.rejected userId=99 reason=queue_full"));
                    assertThat(logs.levelOf("auth.youtube.cleanup.rejected")).isEqualTo(Level.WARN);
                    assertThat(executor.awaitIdle(Duration.ofMillis(200))).as("막힌 잡이 남아 아직 안 끝났다").isFalse();
                } finally {
                    block.countDown();   // 단언이 실패해도 워커를 풀어 준다 — 안 그러면 shutdown이 10초 매달린다
                }
                assertThat(executor.awaitIdle(Duration.ofSeconds(10))).as("거부까지 세어 끝난다").isTrue();
                assertThat(ran.get()).isEqualTo(YoutubeCleanupExecutor.THREADS + YoutubeCleanupExecutor.QUEUE_CAPACITY);
            }
        } finally {
            executor.shutdown();
        }
    }

    /**
     * 🔴 유예 안에 안 끝나는 잡은 <b>인터럽트한다</b>. 워커가 비데몬이라 안 그러면 JVM이 큐가 빌 때까지
     * 안 죽고 종료 유예(15초)를 넘긴다(봇 3판 P2-2 실측: 로그만 찍고 반환한 뒤에도 잡이 끝까지 돌았다).
     */
    @Test
    void 유예를_넘긴_잡은_인터럽트하고_종료한다() throws Exception {
        YoutubeCleanupExecutor executor = new YoutubeCleanupExecutor();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        executor.submit(executor.new Job(1L, null, () -> {
            started.countDown();
            try {
                Thread.sleep(YoutubeCleanupExecutor.SHUTDOWN_WAIT.plusSeconds(30).toMillis());
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
        assertThat(elapsed).as("종료 유예 15초 안에 끝나야 한다")
                .isLessThan(YoutubeCleanupExecutor.SHUTDOWN_WAIT
                        .plus(YoutubeCleanupExecutor.FORCED_STOP_WAIT).plusSeconds(2).toMillis());
    }

    /** 정상 갈래 — 유예 안에 끝나는 잡은 인터럽트 없이 완주한다. 위 검사가 「무조건 끊는다」로 바뀌면 여기서 걸린다. */
    @Test
    void 유예_안에_끝나는_잡은_그대로_완주한다() throws Exception {
        YoutubeCleanupExecutor executor = new YoutubeCleanupExecutor();
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
