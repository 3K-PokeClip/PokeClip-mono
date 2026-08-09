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
 * 전송이 끊긴 뒤에도 뒷정리를 <b>프로세스 종료까지 미루지 않는지</b>.
 *
 * <p>상태와 최종 판정만 갱신하고 나머지를 {@code @PreDestroy}에 맡기면, 프로세스가
 * 계속 살아 있는 동안 <b>죽은 세션에 ping을 계속 쏘고 요약도 계속 찍는다.</b>
 * {@code chat.session.ping_send_failed}가 쌓이고 실행기·세션 자원이 안 풀린다.
 * 종료 경로가 하나뿐이던 시절의 작업이 {@code stop()}만 보고 이쪽을 안 봤다.
 *
 * <p><b>정리를 두 경로가 공유하면 중복 호출이 실체가 된다</b>(POK-86 미결 경계).
 * 그래서 여기서 두 경로를 다 밟고, 판정 라인과 구독 반납이 각각 한 번인지 본다.
 */
@FakeChzzkTest
class TransportClosedCleanupTest {

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
    void 전송이_끊기면_하트비트와_요약을_멈추고_구독을_반납한다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            // 앞 테스트가 남긴 동명 스레드를 내 것으로 세지 않도록 차집합을 쓴다.
            List<Thread> before = liveWorkers();

            CollectionStatus status = new CollectionStatus();
            runner = new CollectorRunner(new ChzzkProperties(
                    true, "test-token", "http://localhost:" + port, Duration.ofSeconds(5),
                    Duration.ofMillis(50), Duration.ofSeconds(1)),
                    status, restClientBuilder);
            runner.start();
            long session = runner.lastSessionNo();
            assertThat(status.state())
                    .as("붙지도 않았다면 끊는 것에 아무 의미가 없다")
                    .isEqualTo(CollectionStatus.State.COLLECTING);

            List<Thread> mine = liveWorkers().stream().filter(t -> !before.contains(t)).toList();

            // 양성 대조. 실행기가 애초에 안 떴다면 "멈췄다"는 자동으로 참이 된다.
            assertThat(mine.stream().map(Thread::getName).toList())
                    .as("ping과 요약이 각자 실행기를 갖고 돌고 있어야 멈춤을 검사할 수 있다")
                    .containsExactlyInAnyOrder("chzzk-ping", "chzzk-summary");

            behavior.closeSession();
            awaitUntil(() -> status.state() == CollectionStatus.State.STOPPED);
            assertThat(status.reason()).isEqualTo(StopReason.TRANSPORT_CLOSED);

            // ① 구독 반납. stop()을 아무도 안 불렀는데도 와야 한다 —
            //    안 오면 세션 자원이 프로세스가 죽을 때까지 남는다.
            awaitUntil(() -> behavior.unsubscribeCallCount() == 1);
            assertThat(behavior.unsubscribeCallCount())
                    .as("전송이 끊겼는데 반납을 종료까지 미루면 세션이 상한 3개를 계속 먹는다")
                    .isEqualTo(1);
            // 반납 도착과 반납 결말 줄은 같은 시점이 아니다 — 줄은 왕복이 끝나야
            // 나간다. 도착만 보고 줄을 단언하면 그 왕복만큼의 창에서 간헐 실패한다.
            awaitUntil(() -> hasLine(captor, "chat.session.released"));
            assertThat(captor.messages()).anyMatch(m -> m.startsWith("chat.session.released"));

            // ② 실행기 둘. 살아 있으면 죽은 세션에 ping을 계속 쏘고 요약도 계속 찍는다.
            awaitUntil(() -> mine.stream().noneMatch(Thread::isAlive));
            assertThat(mine.stream().filter(Thread::isAlive).map(Thread::getName).toList())
                    .as("전송이 끊긴 뒤에도 도는 실행기는 죽은 세션에 대고 일하는 것이다")
                    .isEmpty();

            // ③ 두 번째 경로. 종료 훅이 stop()을 또 부른다.
            runner.stop();

            // 이 러너가 받은 번호로 좁혀 센다. 개수만 세면 LogCaptor가 JVM 전역 루트
            // 로거에 붙어 있어(web-support/LogCaptor.java:21-26) 앞 클래스의 낙오
            // 스레드가 늦게 찍은 줄이 이 수에 섞인다. 상수 1을 박아도 같다 —
            // 번호를 러너에서 받아 와야 남의 세션과 갈린다.
            assertThat(captor.messages().stream()
                    .filter(m -> m.startsWith("chat.session.verdict session=" + session + " ")).count())
                    .as("판정이 두 줄이면 어느 것이 진짜 끝인지 흐려진다")
                    .isEqualTo(1);
            assertThat(behavior.unsubscribeCallCount())
                    .as("정리를 두 경로가 공유하는데 멱등하지 않으면 반납이 두 번 간다")
                    .isEqualTo(1);
        }
    }

    private static List<Thread> liveWorkers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> WORKER_NAMES.contains(t.getName()))
                .filter(Thread::isAlive)
                .toList();
    }

    private static boolean hasLine(LogCaptor captor, String prefix) {
        return captor.messages().stream().anyMatch(m -> m.startsWith(prefix));
    }

    /** 조건이 설 때까지 기다린다. 안 서면 그대로 다음 단언이 사실을 말한다. */
    private static void awaitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }
}
