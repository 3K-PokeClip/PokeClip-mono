package com.pokeclip.auth.withdrawal;

import ch.qos.logback.classic.Level;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
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

    /**
     * 🔴 <b>큐에서 뽑혀 나온 잡은 「누구였는지」가 어디에도 안 남았다</b>(PR #148 codex C5, 감사 재현).
     *
     * <p>{@code shutdownNow()}의 반환값을 버리고 있었다. 그 잡들은 {@code run()}을 못 하므로
     * {@code started}가 <b>한 줄도 안 찍힌다</b> — 이 클래스가 약속한 회복법
     * (「{@code started}는 있는데 {@code completed}가 없는 회원을 찾는다」)이 <b>이 갈래에서만 성립하지 않는다.</b>
     * 거부 핸들러도 안 탄다({@code shutdown()} 뒤에 <b>새로 들어온 것</b>만 잡는다).
     *
     * <p>🔴 <b>건수 하나로도 부족했다.</b> 옛 {@code pending}은 <b>돌던 것과 안 돈 것을 뭉친 숫자</b>라
     * 「몇 명이 복구 불가인가」조차 못 말했다 — 돌던 것은 {@code started}가 찍혀 짝으로 찾을 수 있고
     * 큐에 있던 것은 그렇지 않다. 둘을 갈라 센다.
     *
     * <p>로그 줄이 최악 큐 상한(1000)만큼 난다. 종료 시점에 한 번뿐이고, <b>회원 번호를 되찾을
     * 다른 방법이 없다</b> — 한 줄로 뭉치면 로그 시스템이 자르는 순간 뒷부분이 통째로 사라진다.
     */
    @Test
    void 종료에_잘려_버려진_잡은_회원_번호를_남긴다() throws Exception {
        WithdrawalCleanupExecutor executor = new WithdrawalCleanupExecutor();
        CountDownLatch 워커가_잡혔다 = new CountDownLatch(WithdrawalCleanupExecutor.THREADS);
        CountDownLatch 놓아준다 = new CountDownLatch(1);
        try (LogCaptor logs = new LogCaptor()) {
            try {
                // 워커 둘을 유예보다 오래 붙잡는다 — 그래야 뒤에 넣는 것이 큐에 쌓인 채 종료를 맞는다.
                for (int i = 0; i < WithdrawalCleanupExecutor.THREADS; i++) {
                    executor.submit(executor.new Job(1L, null, () -> {
                        워커가_잡혔다.countDown();
                        await(놓아준다);
                    }));
                }
                assertThat(워커가_잡혔다.await(5, TimeUnit.SECONDS))
                        .as("워커가 안 잡혔다 — 아래 잡들이 큐에 안 쌓이므로 아무것도 안 잰다").isTrue();

                for (long userId : List.of(11L, 22L, 33L)) {
                    executor.submit(executor.new Job(userId, null, () -> { }));
                }

                executor.shutdown();

                assertThat(logs.messages())
                        .as("🔴 큐에서 버려진 잡의 회원 번호가 로그에 없다 — started/completed 짝으로도 "
                                + "못 찾으므로 「사진이 안 지워진 회원」이 영영 안 보인다")
                        .contains("auth.withdrawal.cleanup.dropped userId=11",
                                "auth.withdrawal.cleanup.dropped userId=22",
                                "auth.withdrawal.cleanup.dropped userId=33");
                assertThat(logs.levelOf("auth.withdrawal.cleanup.dropped")).isEqualTo(Level.WARN);
                assertThat(logs.messages())
                        .as("돌던 것과 안 돈 것을 뭉치면 「몇 명이 복구 불가인가」를 못 말한다")
                        .anyMatch(m -> m.startsWith("auth.withdrawal.cleanup.shutdown_timeout dropped=3 "));
            } finally {
                놓아준다.countDown();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
