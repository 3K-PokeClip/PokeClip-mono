package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전송 전용 스레드의 스케줄링만 잰다. 스프링을 안 띄운다.
 *
 * <p><b>{@code completeWithError()}가 불렸는지는 여기서 못 잰다.</b> 서블릿 밖에서 만든
 * {@code SseEmitter}는 {@code handler == null}이라 {@code complete()}·{@code completeWithError()}·
 * 타임아웃이 <b>콜백을 안 부른다</b>(plan-critic 소스 확인 + 실측). 「연결이 정리된다」는
 * 태스크 9의 실 HTTP 시험이 잰다.
 */
class CardStreamExecutorTest {

    /**
     * 안 읽는 구독자가 남을 막지 않는다는 것이 스트라이프 격리의 존재 이유다.
     *
     * <p>느린 작업이 <b>시작된 것을 확인한 뒤에</b> 빠른 작업을 넣는다 — 시작조차 안 했으면
     * 빠른 작업이 도는 것은 당연해서 아무것도 안 잰다(async-test-reality 문항 4-다).
     */
    @Test
    void 다른_스트라이프의_작업은_느린_작업에_막히지_않는다() throws Exception {
        CardStreamExecutor executor = new CardStreamExecutor(4, 100);
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch fastDone = new CountDownLatch(1);
        try {
            executor.submit(0, new SseEmitter(), () -> {
                slowStarted.countDown();
                release.await();
            });
            assertThat(slowStarted.await(1, SECONDS))
                    .as("느린 작업이 시작조차 안 했으면 뒤 단언이 무의미하다").isTrue();

            executor.submit(1, new SseEmitter(), fastDone::countDown);

            assertThat(fastDone.await(1, SECONDS)).as("스트라이프가 하나면 여기서 영영 기다린다").isTrue();
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    /** 같은 연결의 작업이 뒤바뀌면 화면이 「집음 → 놓음」을 거꾸로 받는다. */
    @Test
    void 같은_스트라이프의_작업은_순서대로_돈다() throws Exception {
        CardStreamExecutor executor = new CardStreamExecutor(4, 100);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(50);
        SseEmitter emitter = new SseEmitter();
        try {
            for (int i = 0; i < 50; i++) {
                int n = i;
                executor.submit(7, emitter, () -> {
                    order.add(n);
                    done.countDown();
                });
            }
            assertThat(done.await(5, SECONDS)).isTrue();
            assertThat(order).containsExactlyElementsOf(IntStream.range(0, 50).boxed().toList());
        } finally {
            executor.shutdown();
        }
    }

    /** 넘치면 조용히 사라지지 않는다 — 메우는 것은 카드 목록 문이고(POK-174), 원인은 남아야 한다. */
    @Test
    void 큐가_차면_버리고_경고를_남긴다() throws Exception {
        CardStreamExecutor executor = new CardStreamExecutor(1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (LogCaptor captor = new LogCaptor()) {
            executor.submit(0, new SseEmitter(), () -> {
                started.countDown();
                release.await();
            });
            assertThat(started.await(1, SECONDS)).isTrue();

            executor.submit(0, new SseEmitter(), () -> { });   // 큐에 한 자리
            executor.submit(0, new SseEmitter(), () -> { });   // 넘침 → 거부

            assertThat(captor.messages()).anyMatch(m -> m.startsWith("jumpcard.stream.rejected"));
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void 작업이_던지면_그_연결만_끊고_다음_작업은_계속_돈다() throws Exception {
        CardStreamExecutor executor = new CardStreamExecutor(1, 100);
        CountDownLatch next = new CountDownLatch(1);
        try {
            executor.submit(0, new SseEmitter(), () -> {
                throw new IOException("끊긴 연결");
            });
            executor.submit(0, new SseEmitter(), next::countDown);

            assertThat(next.await(2, SECONDS)).as("던진 작업이 스레드를 죽이면 여기서 멈춘다").isTrue();
        } finally {
            executor.shutdown();
        }
    }
}
