package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>수립을 마치는 사이에 전송이 끊겼을 때</b> 부팅 스레드가 그 위에 아무것도
 * 올리지 않는지.
 *
 * <p>수립 직후의 짧은 구간에서 WS 스레드가 절단을 처리하면, 정리는 이미 끝났는데
 * 부팅 스레드는 그 사실을 모르고 스케줄러 둘을 띄우고 상태를 COLLECTING으로
 * 되돌린다. 결과는 <b>정리 완료 + health UP + 수집 없음</b>이다 —
 * {@code CollectorHealth}와 {@code CollectorRunner}가 "이 서비스의 유일한 치명적
 * 실패"라고 못박은 상태 그 자체다. 게다가 정리 가드가 이미 소모돼
 * {@code @PreDestroy}의 {@code stop()}도 아무것도 못 한다.
 *
 * <p><b>순서를 우연에 맡기지 않는다.</b> 가짜 서버가 구독 REST 응답을 붙들고 있는
 * 동안 끊고, 클라이언트의 구독 반납이 도착한 것을 보고서야 응답한다. 그 시점에
 * 부팅 스레드는 아직 구독 HTTP 호출 안이라, 수립 마무리는 반드시 절단 처리
 * 다음이다.
 */
@FakeChzzkTest
class EstablishCutCleanupTest {

    /** 이 이름으로 도는 스레드가 곧 "아직 일하고 있다"의 증거다. */
    private static final Set<String> WORKER_NAMES = Set.of("chzzk-ping", "chzzk-summary");

    private static final Duration AWAIT = Duration.ofSeconds(5);

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;
    @Autowired RestClient.Builder restClientBuilder;

    private CollectorRunner runner;

    @AfterEach
    void tearDown() {
        if (runner != null) runner.stop();
        behavior.reset();
    }

    @Test
    void 수립을_마치는_사이에_끊기면_COLLECTING으로_덮지_않는다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            // 앞 테스트가 남긴 동명 스레드를 내 것으로 세지 않도록 차집합을 쓴다.
            List<Thread> before = liveWorkers();

            behavior.closeAfterSubscribed = true;

            CollectionStatus status = new CollectionStatus();
            runner = start(status, Duration.ofSeconds(5));

            assertThat(status.state())
                    .as("정리가 이미 끝났는데 COLLECTING이면 health는 UP인 채로 수집만 죽는다")
                    .isEqualTo(CollectionStatus.State.STOPPED);
            assertThat(status.reason()).isEqualTo(StopReason.TRANSPORT_CLOSED);
            assertThat(new CollectorHealth(status).health().getStatus().getCode())
                    .as("수집이 죽었는데 health가 UP이면 밖에서는 아무 신호도 없다")
                    .isEqualTo("DOWN");

            List<Thread> mine = liveWorkers().stream().filter(t -> !before.contains(t)).toList();
            awaitUntil(() -> mine.stream().noneMatch(Thread::isAlive));
            assertThat(mine.stream().filter(Thread::isAlive).map(Thread::getName).toList())
                    .as("이미 닫힌 소켓 위에 올린 실행기는 ping_send_failed와 요약만 계속 뱉는다")
                    .isEmpty();

            List<String> verdicts = captor.messages().stream()
                    .filter(m -> m.startsWith("chat.session.verdict")).toList();
            assertThat(verdicts)
                    .as("판정이 두 줄이면 어느 것이 진짜 끝인지 흐려진다")
                    .hasSize(1);
            assertThat(verdicts.get(0)).contains("reason=" + StopReason.TRANSPORT_CLOSED);
        }
    }

    /**
     * 같은 뿌리의 변종. 절단이 {@code open()} 진행 중에 오면 부팅 스레드는 시한
     * 만료로 떨어지는데, 그 사유로 이미 찍힌 {@code TRANSPORT_CLOSED}를 덮으면
     * <b>결과가 원인을 지운다</b> — 왜 끊겼는지가 어디에도 안 남는다.
     */
    @Test
    void 절단이_먼저면_수립_시한_만료가_그_사유를_덮지_않는다() {
        behavior.sendSubscribed = false;        // ⑤가 영영 안 온다
        behavior.closeAfterSubscribed = true;   // 그 전에 이미 끊겼다

        CollectionStatus status = new CollectionStatus();
        runner = start(status, Duration.ofSeconds(1));

        assertThat(status.state()).isEqualTo(CollectionStatus.State.STOPPED);
        assertThat(status.reason())
                .as("절단이 원인이고 시한 만료는 그 결과다. 결과가 원인을 덮으면 추적이 끊긴다")
                .isEqualTo(StopReason.TRANSPORT_CLOSED);
    }

    private CollectorRunner start(CollectionStatus status, Duration establishTimeout) {
        CollectorRunner created = new CollectorRunner(new ChzzkProperties(
                true, "test-token", "http://localhost:" + port, establishTimeout),
                status, restClientBuilder);
        created.start();
        return created;
    }

    private static List<Thread> liveWorkers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> WORKER_NAMES.contains(t.getName()))
                .filter(Thread::isAlive)
                .toList();
    }

    /** 조건이 설 때까지 기다린다. 안 서면 그대로 다음 단언이 사실을 말한다. */
    private static void awaitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }
}
