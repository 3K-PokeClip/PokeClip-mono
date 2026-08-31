package com.pokeclip.chat.collector.broadcast.attach;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「같은 줄은 하나씩, 다른 줄은 겹쳐서」를 잰다.
 *
 * <p>🔴 <b>문항 4 — 직렬 검사가 「너무 빨라서」 통과하는가.</b> {@code maxConcurrent == 1}은
 * 작업이 즉시 끝나면 <b>항상 참</b>이라 줄이 아예 안 돌아도 통과한다. 그래서
 * {@code 같은_줄의_작업은_절대_겹치지_않는다}의 작업 안에 {@code Thread.sleep(2)}로
 * <b>겹칠 기회</b>를 만들어 뒀고, 그 기회가 충분한지를 <b>반대 방향으로 증명했다</b> —
 * {@code submit}의 {@code running} 검사를 지워 직렬화를 걷어내면(= 가상 스레드 실행기에
 * 그대로 던지는 것) 같은 검사가 <b>{@code maxConcurrent}=50으로 빨간불</b>이 된다
 * — 50건이 <b>전부</b> 겹쳤다(2026-08-31 실측).
 * 즉 이 검사는 직렬일 때와 병렬일 때가 실제로 갈린다.
 *
 * <p><b>문항 3 — 의도한 동시성이 환경에 막히지 않는가.</b> 이 부품은 가상 스레드만 쓰고
 * DB·커넥션 풀을 안 탄다. {@code 다른_줄의_작업은_실제로_겹친다}가 {@code CyclicBarrier(3)}인
 * 이유가 이것이다 — 셋이 <b>다 도착해야</b> 풀리므로 겹침의 증거가 된다.
 * {@code CountDownLatch}로 출발선만 맞춘 것은 증거가 아니다(한쪽이 먼저 끝나면 안 겹친다).
 *
 * <p><b>문항 5 — 비동기 시험이 「안 기다려서」 통과하는가.</b> 상한·대기 버림 검사는
 * {@code running} latch로 <b>첫 작업이 실제로 도는 것을 확인한 뒤에</b> 센다.
 * 안 그러면 「아직 제출만 된 상태」와 「돌고 있는 상태」가 섞여 무엇을 재는지가 흐려진다.
 */
class StreamerSerialExecutorTest {

    @Test
    void 같은_줄의_작업은_절대_겹치지_않는다() throws Exception {
        StreamerSerialExecutor executor = new StreamerSerialExecutor(100);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(50);
        for (int i = 0; i < 50; i++) {
            executor.submit("streamer-1", () -> {
                int now = concurrent.incrementAndGet();
                maxConcurrent.updateAndGet(prev -> Math.max(prev, now));
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                concurrent.decrementAndGet();
                done.countDown();
            });
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(maxConcurrent.get()).isEqualTo(1);
        executor.close();
    }

    @Test
    void 같은_줄의_작업은_넣은_순서대로_돈다() throws Exception {
        StreamerSerialExecutor executor = new StreamerSerialExecutor(100);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(30);
        for (int i = 0; i < 30; i++) {
            int n = i;
            executor.submit("streamer-1", () -> {
                order.add(n);
                done.countDown();
            });
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(order).containsExactlyElementsOf(IntStream.range(0, 30).boxed().toList());
        executor.close();
    }

    @Test
    void 다른_줄의_작업은_실제로_겹친다() throws Exception {
        StreamerSerialExecutor executor = new StreamerSerialExecutor(100);
        // 셋이 서로를 기다린다 — 직렬이면 영원히 안 풀려 시한에 걸린다.
        CyclicBarrier barrier = new CyclicBarrier(3);
        CountDownLatch done = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            executor.submit("streamer-" + i, () -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                done.countDown();
            });
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        executor.close();
    }

    @Test
    void 진행_중인_것이_상한에_닿으면_거절한다() throws Exception {
        // 🔴 상한은 「대기」가 아니라 「돌고 있는 것 + 대기」다. 계획 검증이 실측으로
        // 잡았다 — queued는 run()의 finally에서만 내려가는데 첫 작업이 hold에 붙들려
        // 있으므로 그것도 자리를 차지한 채다. 이름이 maxInFlight인 이유가 그것이다.
        //
        // 이 뜻이 백프레셔에 맞다: 돌고 있는 붙이기도 메모리와 auth 커넥션을 쓴다.
        StreamerSerialExecutor executor = new StreamerSerialExecutor(2);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        executor.submit("s", () -> {
            running.countDown();
            try {
                hold.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        // 첫째가 실제로 돌기 시작한 것을 확인한 뒤에 센다 — 안 그러면 「아직 제출만 된
        // 상태」와 「돌고 있는 상태」가 섞여 무엇을 재는지가 흐려진다.
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(executor.submit("s", () -> { })).isTrue();    // 1 + 1 = 2, 상한까지
        assertThat(executor.submit("s", () -> { })).isFalse();   // 넘는다
        assertThat(executor.saturated()).isTrue();

        hold.countDown();
        assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(executor.saturated()).isFalse();              // 비면 다시 받는다
        executor.close();
    }

    @Test
    void 대기를_버려도_돌고_있는_작업은_안_건드린다() throws Exception {
        StreamerSerialExecutor executor = new StreamerSerialExecutor(100);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        AtomicInteger ranAfter = new AtomicInteger();
        executor.submit("s", () -> {
            running.countDown();
            try {
                hold.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        executor.submit("s", ranAfter::incrementAndGet);
        executor.submit("s", ranAfter::incrementAndGet);

        executor.dropPending("s");
        hold.countDown();
        assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(ranAfter.get()).isZero();
        executor.close();
    }

    @Test
    void 작업이_던져도_그_줄의_다음_작업이_돈다() throws Exception {
        StreamerSerialExecutor executor = new StreamerSerialExecutor(100);
        CountDownLatch after = new CountDownLatch(1);
        executor.submit("s", () -> {
            throw new IllegalStateException("일부러");
        });
        executor.submit("s", after::countDown);
        assertThat(after.await(5, TimeUnit.SECONDS)).isTrue();
        executor.close();
    }

    @Test
    void 다_끝나면_줄이_맵에서_사라진다() throws Exception {
        // 줄이 안 치워지면 스트리머 수만큼 영원히 자란다.
        //
        // 🔴 awaitIdle이 lanes.isEmpty()를 보면 이 단언이 자동으로 참이 된다(계획 검증 I3).
        // awaitIdle은 inFlight == 0만 보고, 「줄이 남았나」는 여기서만 잰다.
        StreamerSerialExecutor executor = new StreamerSerialExecutor(100);
        for (int i = 0; i < 100; i++) {
            executor.submit("streamer-" + i, () -> { });
        }
        assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(awaitLaneCountZero(executor, Duration.ofSeconds(2))).isZero();
        executor.close();
    }

    /**
     * 🔴 <b>닫힌 뒤에 받으면 자리도 카운터도 안 남긴다.</b>
     *
     * <p>고치기 전에는 {@code workers.execute}가 {@code RejectedExecutionException}을 던지는데
     * <b>그 시점에 줄은 이미 {@code running=true}로 맵에 들어가 있어</b> 아무도
     * {@code nextOrRelease}를 안 불렀다 — 그 스트리머가 영구히 막혔다. 게다가
     * {@code inFlight}가 안 내려가 {@code saturated()}가 영구 true가 되면
     * <b>모든 스트리머의 알림 꺼내기가 멈춘다.</b>
     *
     * <p>{@code run}의 {@code catch (Throwable)}는 이것을 못 막는다 — 그 방어선은
     * <b>작업이 던지는 것</b>만 받고, {@code execute}가 던지는 것은 그보다 <b>앞</b>이라
     * 통째로 우회한다. 감사자가 스스로 고른 주입으로 찾았다.
     */
    @Test
    void 닫힌_뒤_받으면_거절하고_자리를_안_남긴다() {
        StreamerSerialExecutor executor = new StreamerSerialExecutor(100);
        executor.close();

        assertThat(executor.submit("s", () -> { })).isFalse();
        assertThat(executor.inFlight()).isZero();
        assertThat(executor.laneCount()).isZero();
        assertThat(executor.saturated()).isFalse();
    }

    /**
     * 거절이 <b>쌓여도</b> 회계가 안 샌다. 위 검사가 한 번만 보므로 이것을 따로 둔다 —
     * 고치기 전에는 {@code inFlight}가 거절마다 하나씩 올라 상한 2에서 <b>두 번</b>이면
     * {@code saturated()}가 영구 true였다.
     *
     * <p>문항 6: {@code isFalse()}만 보면 「거절해서 false」와 「상한에 걸려 false」가
     * 구분되지 않는다. 그래서 {@code inFlight}가 <b>0</b>인 것을 같이 본다 —
     * 상한 때문이라면 2로 남아 있다.
     */
    @Test
    void 닫힌_뒤_거절이_쌓여도_포화되지_않는다() {
        StreamerSerialExecutor executor = new StreamerSerialExecutor(2);
        executor.close();

        assertThat(executor.submit("s", () -> { })).isFalse();
        assertThat(executor.submit("s", () -> { })).isFalse();
        assertThat(executor.submit("other", () -> { })).isFalse();

        assertThat(executor.inFlight()).isZero();
        assertThat(executor.saturated()).isFalse();
        assertThat(executor.laneCount()).isZero();
    }

    /**
     * 상한이 0이면 <b>모든</b> {@code submit}이 false라 수집이 통째로 멈춘다 —
     * 이 서버가 반복해 데인 「켰는데 조용히 안 돈다」다. 값이 설정으로 뚫리는 날을 대비해
     * 만들 때 잡는다.
     */
    @Test
    void 상한이_0이하면_만들_때_죽는다() {
        assertThatThrownBy(() -> new StreamerSerialExecutor(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxInFlight");
        assertThatThrownBy(() -> new StreamerSerialExecutor(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * {@code laneCount()}가 0이 될 때까지 짧은 상한으로 폴링해 <b>0이 아니면 마지막 값</b>을 준다.
     *
     * <p>🔴 <b>왜 그냥 단언하지 않나</b>: {@code inFlight}는 {@code run}의 {@code finally}에서
     * 내려가고 줄 지우기는 <b>그 뒤</b>다. 즉 {@code awaitIdle}이 true를 주는 순간 그 줄은
     * 아직 맵에 있을 수 있다 — 창이 몇 인스트럭션이라 평소엔 안 걸리지만 <b>결함이 없어도
     * 빨개질 수 있다</b>(감사가 그 창에 200ms를 주입해 결정적으로 재현했다).
     *
     * <p><b>이것이 단언을 무르게 만들지 않는다</b>: 줄이 정말 새면 상한 안에 0이 안 되어
     * 그대로 빨간불이다({@code nextOrRelease}가 줄을 안 지우는 주입으로 확인 — 100이 유지된다).
     * {@code awaitIdle}에 {@code lanes.isEmpty()}를 넣는 것과는 다르다. 그러면 <b>구현</b>이
     * 그것을 기다려 단언이 자동으로 참이 되고, 누수가 있을 때 종료가 예산을 다 쓴다
     * (계획 검증 I3). <b>기다리는 쪽과 재는 쪽은 그대로 갈라져 있다.</b>
     */
    private static int awaitLaneCountZero(StreamerSerialExecutor executor, Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        int last = executor.laneCount();
        while (last != 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
            last = executor.laneCount();
        }
        return last;
    }
}
