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
 * 뒷정리와 판정이 <b>세션마다</b> 도는지. 재연결이 붙기 전에 이것부터 푼다 —
 * 프로세스 1회 전제 위에 루프를 얹으면 두 번째 세션의 반납이 통째로 새고,
 * 연결 상한이 3개라 몇 번 만에 못 붙게 된다.
 */
@FakeChzzkTest
class SessionBoundaryTest {

    private static final Duration AWAIT = Duration.ofSeconds(5);

    /** 이 이름으로 도는 스레드가 곧 "아직 일하고 있다"의 증거다. */
    private static final Set<String> WORKER_NAMES = Set.of("chzzk-ping", "chzzk-summary");

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;
    @Autowired RestClient.Builder restClientBuilder;

    private CollectorRunner runner;
    /** 필드로 둔다 — 태스크 8·9a가 같은 클래스에 검사를 더하면서 이걸 읽는다. */
    private CollectionStatus status;

    @AfterEach
    void tearDown() {
        if (runner != null) runner.stop();
        behavior.reset();
    }

    /** 러너 생성을 한 곳으로 모은다. 태스크 8·9a가 그대로 부른다. */
    private void startRunner() {
        status = new CollectionStatus();
        runner = new CollectorRunner(new ChzzkProperties(
                true, "test-token", "http://localhost:" + port,
                Duration.ofSeconds(5), Duration.ofMillis(50), Duration.ofSeconds(1)),
                status, restClientBuilder);
        runner.start();
    }

    @Test
    void 두_번째_세션도_반납하고_판정을_남긴다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            startRunner();
            assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);
            behavior.closeSession();
            awaitUntil(() -> behavior.unsubscribeCallCount() == 1);

            // 두 번째 세션. 루프는 아직 없으므로 테스트가 직접 연다.
            runner.start();
            assertThat(status.state())
                    .as("한 번만 도는 가드가 남아 있으면 여기서 못 올라온다")
                    .isEqualTo(CollectionStatus.State.COLLECTING);
            behavior.closeSession();

            awaitUntil(() -> behavior.unsubscribeCallCount() == 2);
            assertThat(behavior.unsubscribeCallCount())
                    .as("두 번째 세션의 반납이 안 나가면 자리가 서버에 남아 상한 3개를 먹는다")
                    .isEqualTo(2);
            assertThat(captor.messages().stream()
                    .filter(m -> m.startsWith("chat.session.verdict")).count())
                    .as("세션이 둘이면 판정도 둘이다. 한 번만 도는 가드면 두 번째가 사라진다")
                    .isEqualTo(2);
        }
    }

    /**
     * <b>앞 세션의 뒷정리가 반납 왕복에 갇힌 사이에</b> 다음 세션이 시작되는 창.
     *
     * <p>반납은 실서버에서 약 1초 걸린다(CLAUDE.md 실측). 그 동안 뒷정리 스레드는
     * 아직 자기 일이 안 끝났고, 깨어나서 마지막에 "세션 자리"를 지운다. 그 자리에
     * 이미 다음 세션이 들어와 있으면 <b>다음 세션이 통째로 지워진다</b> — 이후
     * 그 세션이 끊겨도 구독 반납도 소켓 닫기도 안 나가고, 상한이 3개라 금방 막힌다.
     *
     * <p>지연 300ms는 실측 1초보다 보수적인 값이다. 임의로 늘리거나 줄이지 않는다 —
     * 늘리면 이 테스트가 느려지기만 하고, 줄이면 재현이 다시 우연에 맡겨진다.
     *
     * <p><b>이 테스트를 실제로 지키는 것은 아래 `COLLECTING` 단언이다.</b> 자리를 늦게
     * 놓도록 되돌리면 그 줄에서 먼저 죽고, 마지막 「반납 == 2」에는 닿지도 않는다.
     * 즉 <b>지금 「반납 == 2」를 단독으로 빨갛게 만드는 변이는 없다</b>(CP1b가 찾지 못했다).
     * 그 줄이 무가치하다는 뜻이 아니라, <b>"창이 실제로 검사된다"의 근거로 그 줄을 들면
     * 안 된다</b>는 뜻이다 — CLAUDE.md의 T13(①②가 시한을 삼켜 본 단언에 안 닿는다)과
     * 같은 모양이다. 근거를 대야 할 때는 `COLLECTING` 쪽을 든다.
     */
    @Test
    void 앞_세션_반납이_왕복하는_사이에_시작한_세션도_반납된다() throws Exception {
        behavior.unsubscribeDelay = Duration.ofMillis(300);

        try (LogCaptor captor = new LogCaptor()) {
            startRunner();
            assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);

            behavior.closeSession();
            // 반납이 서버에 도착한 시점 = 뒷정리 스레드가 왕복에 갇힌 시점이다.
            awaitUntil(() -> behavior.unsubscribeCallCount() == 1);

            // 갇혀 있는 동안 자리를 잡는다. 이 순서가 이 테스트의 전부다.
            runner.start();
            assertThat(status.state())
                    .as("앞 세션 뒷정리가 반납에 갇혀 있어도 새 세션은 서야 한다")
                    .isEqualTo(CollectionStatus.State.COLLECTING);

            // <b>앞 뒷정리가 끝나는 것을 보고서야</b> 끊는다. 안 기다리면 새 세션의
            // 뒷정리가 앞 뒷정리보다 먼저 지나가 버려, 자리를 지우는 그 마지막
            // 한 줄을 지나기 전에 테스트가 끝난다 — 결함이 있어도 초록이 된다.
            awaitUntil(() -> releasedLines(captor) == 1);
            assertThat(releasedLines(captor))
                    .as("앞 세션 뒷정리가 끝나야 그 마지막 한 줄이 새 세션을 지울 기회를 갖는다")
                    .isEqualTo(1);

            behavior.closeSession();
            awaitUntil(() -> behavior.unsubscribeCallCount() == 2);
            assertThat(behavior.unsubscribeCallCount())
                    .as("앞 세션 정리가 새 세션 자리를 지우면 그 세션의 반납이 통째로 사라진다")
                    .isEqualTo(2);
        }
    }

    private static long releasedLines(LogCaptor captor) {
        return captor.messages().stream().filter(m -> m.startsWith("chat.session.released")).count();
    }

    /**
     * <b>사유가 이미 찍힌 뒤에 같은 절단의 {@code onClose}가 도착하는 경우.</b>
     *
     * <p>재연결 루프가 붙으면 한 번의 절단에 신호가 둘 들어온다 — pong 임계로 좀비를
     * 판정하는 쪽과 전송 절단 콜백. 먼저 온 쪽이 사유를 찍었으면 뒤에 온 쪽은 그 사유를
     * 덮지 않아야 한다. <b>그런데 사유를 안 덮는 것과 뒷정리를 안 하는 것은 다른 일이다.</b>
     * 둘을 한 번에 건너뛰면 그 세션이 자리를 문 채 남아 다음 세션이 <b>영영</b> 못 선다 —
     * 「루프가 둘 돈다」가 아니라 「루프가 영영 안 돈다」다. 구독은 서버에 남고
     * 상한이 3개라 금방 못 붙게 된다.
     *
     * <p>판정 줄을 세는 필터에 {@code reason=REVOKED}를 건다. 개수만 세면
     * <b>사유를 덮어쓰는 쪽으로 고쳐도 그대로 초록이다</b> — 그러면 진짜 원인이 사라진다.
     */
    @Test
    void 사유가_이미_찍혀_있어도_절단은_자리를_풀고_반납한다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            startRunner();
            assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);

            // 신호①. 이 자리에 올 것은 태스크 6·7이고 아직 없으므로 테스트가 직접 찍는다.
            status.stopped(StopReason.REVOKED);
            // 신호②. 같은 절단의 onClose가 뒤따른다.
            behavior.closeSession();

            awaitUntil(() -> behavior.unsubscribeCallCount() == 1);
            assertThat(behavior.unsubscribeCallCount())
                    .as("사유가 이미 있다고 뒷정리까지 건너뛰면 구독이 서버에 남아 상한 3개를 먹는다")
                    .isEqualTo(1);
            assertThat(captor.messages().stream()
                    .filter(m -> m.startsWith("chat.session.verdict"))
                    .filter(m -> m.contains("reason=" + StopReason.REVOKED))
                    .count())
                    .as("판정이 통째로 사라지거나, 뒤에 온 신호가 첫 사유를 덮어 원인이 바뀐다")
                    .isEqualTo(1);

            // 자리가 풀렸는가. 이것이 이 테스트의 본체다.
            runner.start();
            assertThat(status.state())
                    .as("앞 세션이 자리를 문 채 남으면 start_skipped만 반복하고 영영 못 붙는다")
                    .isEqualTo(CollectionStatus.State.COLLECTING);
            assertThat(behavior.authCallCount())
                    .as("자리가 안 풀리면 세션 발급에 닿지도 못한다")
                    .isEqualTo(2);
        }
    }

    /**
     * <b>절단 없이</b> 또 시작하는 경우. 재연결 루프가 붙으면 신호 둘(절단 콜백 ·
     * pong 임계)이 겹치는 순간 이 길이 열린다.
     *
     * <p>앞 세션 위에 그냥 덮어쓰면 <b>앞 소켓·스케줄러를 아무도 닫지 않는다</b> —
     * 그 ping 스케줄러는 아무도 닫지 않을 소켓에 계속 쏘고, 앞 구독은 서버에 남아
     * 상한 3개를 먹는다. 그래서 자리를 못 잡으면 아무것도 하지 않고 <b>왜 안 했는지를
     * 남긴다</b> — 조용히 돌아가면 루프가 그 사실을 알 길이 없다.
     */
    @Test
    void 앞_세션이_살아_있으면_다시_시작해도_덮어쓰지_않는다() {
        List<Thread> before = liveWorkers();

        try (LogCaptor captor = new LogCaptor()) {
            startRunner();
            assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);

            // 양성 대조. 실행기가 애초에 안 떴다면 "하나뿐이다"가 자동으로 참이 된다.
            assertThat(names(mineAmong(before)))
                    .as("앞 세션의 실행기가 돌고 있어야 덮어쓰기를 검사할 수 있다")
                    .containsExactlyInAnyOrder("chzzk-ping", "chzzk-summary");

            runner.start();

            assertThat(behavior.authCallCount())
                    .as("앞 세션이 살아 있는데 새 세션을 열면 앞 소켓·스케줄러를 아무도 안 닫는다")
                    .isEqualTo(1);
            assertThat(names(mineAmong(before)))
                    .as("덮어쓴 세션의 ping 스케줄러는 아무도 닫지 않을 소켓에 계속 쏜다")
                    .containsExactlyInAnyOrder("chzzk-ping", "chzzk-summary");
            assertThat(status.state())
                    .as("거부된 start()가 상태를 먼저 건드리면 살아서 수집 중인 세션이 health에서 사라진다")
                    .isEqualTo(CollectionStatus.State.COLLECTING);
            assertThat(captor.messages())
                    .as("아무것도 안 하고 조용히 돌아가면 재연결 루프가 그 사실을 못 본다")
                    .anyMatch(m -> m.startsWith("chat.session.start_skipped"));
        }

        runner.stop();
        // 개수를 박지 않고 발급과 짝지운다. "하나다"는 덮어쓰기 결함에서도 그대로 참이다 —
        // 덮어쓰면 앞 세션이 고아가 되어 반납이 아예 안 나가므로 개수는 역시 1이다.
        // 발급한 만큼 반납했는가로 물으면 그 결함에서 1 != 2로 갈린다.
        assertThat(behavior.unsubscribeCallCount())
                .as("발급한 세션 수만큼 반납이 안 나가면 그 차이가 서버에 남아 상한 3개를 먹는다")
                .isEqualTo(behavior.authCallCount());
    }

    /** 앞 테스트가 남긴 동명 스레드를 내 것으로 세지 않도록 차집합을 쓴다. */
    private static List<Thread> mineAmong(List<Thread> before) {
        return liveWorkers().stream().filter(t -> !before.contains(t)).toList();
    }

    private static List<String> names(List<Thread> threads) {
        return threads.stream().map(Thread::getName).toList();
    }

    private static List<Thread> liveWorkers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> WORKER_NAMES.contains(t.getName()))
                .filter(Thread::isAlive)
                .toList();
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }
}
