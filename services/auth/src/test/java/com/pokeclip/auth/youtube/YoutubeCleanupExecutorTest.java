package com.pokeclip.auth.youtube;

import ch.qos.logback.classic.Level;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
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
}
