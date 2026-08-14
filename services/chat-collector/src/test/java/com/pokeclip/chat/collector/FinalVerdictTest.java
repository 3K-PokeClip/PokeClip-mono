package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 최종 판정 라인. <b>30초 요약은 창 값이라 이 줄이 없으면 판정하려고 20줄을 뒤져야 한다.</b>
 *
 * <p>이 줄이 통째로 없는 채로 계획 4라운드·CP 5개·검토 5회·verifier 2회를 전부
 * 통과했다. 종료할 때만 나오는 것이라 <b>아무도 테스트를 안 썼고 아무도 실제로
 * 종료해 보지 않았다.</b>
 */
@FakeChzzkTest
class FinalVerdictTest extends IntegrationTestSupport {

    /** PRD 「판정 항목」이 최종 라인에 요구하는 것 전부. */
    private static final List<String> REQUIRED = List.of(
            // 재연결이 붙으면 판정 줄이 여러 번 나간다. 몇 번째 세션의 판정인지가
            // 없으면 운영자가 N번째와 N+1번째를 못 가른다.
            "session=",
            "received=", "collectedFor=", "lastReceivedAt=", "maxReceiveGap=",
            "maxPingGap=", "maxPongGap=", "orderViolations=",
            "delayMin=", "delayMedian=", "delayMax=", "delaySamples=",
            "system=", "decodeFailures=",
            "sendFailures=", "callbackFailures=", "sinkFailures=",
            // 끊겼다 붙은 흔적. 없으면 얼마나 놓쳤는지가 어디에도 안 남고,
            // 시각 둘이 없으면 "언제 놓쳤나"에 못 답해 영상과 대조할 수 없다.
            "reconnects=", "outage=", "lastOutageFrom=", "lastOutageTo=",
            // 없으면 "정상 종료"와 "조용히 끊겼다"가 같은 줄이 된다.
            "reason=");

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
    void 수집을_멈추면_판정_라인이_나가고_항목이_전부_있다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            CollectionStatus status = start();
            assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);

            behavior.emitChat("{\"content\":\"x\",\"messageTime\":1754300000000}");
            behavior.emitChat("{\"content\":\"y\",\"messageTime\":1754300001000}");
            awaitReceived(2);

            runner.stop();

            String verdict = verdictLine(captor);
            for (String key : REQUIRED) {
                assertThat(verdict).as("판정 라인에 " + key + "가 없으면 그 항목을 아무도 못 본다")
                        .contains(key);
            }
        }
    }

    /**
     * 창을 비워도 남아야 한다. 요약이 몇 번 돌았든 판정 라인은 세션 전체를 말한다 —
     * 창 값을 그대로 쓰면 마지막 30초만 보고 "0건 수신"이라고 판정하게 된다.
     */
    @Test
    void 판정_라인은_창을_비워도_세션_전체를_센다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            start();

            behavior.emitChat("{\"content\":\"x\",\"messageTime\":1754300000000}");
            behavior.emitChat("{\"content\":\"y\",\"messageTime\":1754300001000}");
            awaitReceived(2);

            // 요약이 한 번 돈 것과 같은 효과. 창이 비워진다.
            assertThat(runner.metrics().snapshot().received()).isEqualTo(2);
            assertThat(runner.metrics().snapshot().received()).isZero();

            runner.stop();

            assertThat(verdictLine(captor))
                    .as("창을 비운 뒤에도 세션 전체 건수를 말해야 한다")
                    .contains("received=2")
                    .contains("delaySamples=2");
        }
    }

    /**
     * 종료 경로가 둘이다. 둘 다 지나가면 두 줄이 나가고 어느 것이 끝인지 흐려진다.
     *
     * <p><b>절단 자체는 더 이상 판정을 내지 않는다.</b> 그래서 여기서 보는 두 경로는
     * "절단 + 종료"가 아니라 <b>"종료 + 또 한 번의 종료"</b>다 — 절단으로 끝나던 시절의
     * 두 경로 중 하나가 사라진 것이 아니라, 절단이 판정을 내지 않게 된 것이다.
     * 재시도 간격을 크게 줘서 다시 붙지 않은 채로 종료를 밟는다.
     */
    @Test
    void 전송이_끊긴_뒤_종료해도_판정_라인은_한_줄뿐이다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            CollectionStatus status = start(NO_RETRY_WITHIN_TEST);
            assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);

            long session = runner.lastSessionNo();
            behavior.closeSession();
            // 이 세션의 뒷정리가 끝난 것을 보고 나간다. 상태는 자리를 비우기 전에
            // 찍히므로, 상태만 보고 앞지르면 종료가 뒷정리와 겹친다.
            awaitEnded(captor, session);
            assertThat(captor.messages().stream()
                    .filter(m -> m.startsWith("chat.session.verdict")).toList())
                    .as("절단은 끝이 아니다. 거기서 판정을 내면 최종이 아닌 최종 판정이 쌓인다")
                    .isEmpty();

            runner.stop();                       // 판정이 나가는 자리
            runner.stop();                       // 두 번째 종료 경로

            assertThat(captor.messages().stream()
                    .filter(m -> m.startsWith("chat.session.verdict session=" + session + " ")))
                    .hasSize(1);
            assertThat(verdictLine(captor))
                    .as("왜 끝났는지가 없으면 정상 종료와 구분이 안 된다")
                    .contains("reason=TRANSPORT_CLOSED");
            assertThat(captor.messages()).anyMatch(m -> m.startsWith("chat.session.closed"));
        }
    }

    /** PRD 「상태」 표: 실패로 중지 → 실패 한 줄 <b>+ 최종 판정 라인</b>. */
    @Test
    void 수립에_실패해도_판정_라인이_나간다() throws Exception {
        behavior.authStatus = 401;

        try (LogCaptor captor = new LogCaptor()) {
            CollectionStatus status = start();
            // 영구 정지와 그 판정은 재연결 스레드가 낸다. 안 기다리면 아직 창 밖이다.
            awaitState(status, CollectionStatus.State.STOPPED);
            assertThat(status.state()).isEqualTo(CollectionStatus.State.STOPPED);

            assertThat(captor.messages()).anyMatch(m -> m.startsWith("chat.session.stopped"));
            assertThat(verdictLine(captor))
                    .contains("received=0")
                    .contains("reason=SESSION_AUTH_REJECTED");

            // 수립에 실패했으니 반납할 세션 키가 없다. 이 갈래를 밟는 테스트는
            // 여기뿐인데 결말을 아무도 안 봤다 — RETURNED·SKIPPED 라벨을 서로
            // 뒤바꿔도 전체 89건이 초록이었다. failed와 갈라 놓은 이유가
            // "보냈는데 터졌다"와 "보낼 것이 없었다"를 구분하는 것이므로,
            // 그 값을 실제로 보는 줄이 있어야 한다.
            assertThat(captor.messages())
                    .as("반납할 키가 없었던 결말이 skipped로 안 나가면 반납 실패와 구분이 안 된다")
                    .contains("chat.session.released session=" + runner.lastSessionNo()
                            + " subscription=skipped");
        }
    }

    /** 꺼져 있으면 수집한 적이 없다. 판정할 것이 없는데 줄이 나가면 잡음이다. */
    @Test
    void 꺼져_있으면_판정_라인이_안_나간다() {
        try (LogCaptor captor = new LogCaptor()) {
            CollectionStatus status = new CollectionStatus();
            runner = new CollectorRunner(new ChzzkProperties(
                    false, "test-token", "http://localhost:" + port, Duration.ofSeconds(5),
                    Duration.ofMillis(50), Duration.ofSeconds(1)), status, restClientBuilder);
            runner.start();
            runner.stop();

            assertThat(status.state()).isEqualTo(CollectionStatus.State.DISABLED);
            assertThat(captor.messages()).noneMatch(m -> m.startsWith("chat.session.verdict"));
        }
    }

    /**
     * 재연결이 이 검사 안에서는 안 돌게 하는 간격. <b>임의로 줄이지 않는다</b> —
     * 줄이면 절단 뒤에 새 세션이 서서 "무엇을 수집했는지"의 경계가 흐려진다.
     */
    private static final Duration NO_RETRY_WITHIN_TEST = Duration.ofSeconds(30);

    private CollectionStatus start() {
        return start(Duration.ofMillis(50));
    }

    /** <b>{@code run()}으로 띄운다.</b> {@code start()}는 수립 실패를 밖으로 던진다. */
    private CollectionStatus start(Duration reconnectFirstDelay) {
        CollectionStatus status = new CollectionStatus();
        runner = new CollectorRunner(new ChzzkProperties(
                true, "test-token", "http://localhost:" + port, Duration.ofSeconds(5),
                reconnectFirstDelay, Duration.ofSeconds(60)), status, restClientBuilder);
        runner.run(null);
        return status;
    }

    private static void awaitState(CollectionStatus status, CollectionStatus.State state)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (status.state() != state && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    /**
     * <b>이 러너가 받은 번호로 좁힌다.</b> {@code LogCaptor}는 JVM 전역 루트 로거에
     * 붙어 있어 남의 러너가 늦게 찍은 판정 줄까지 담는다. 접두사만 보면 그 낙오가
     * 내 줄인 척 집히고, 내 판정이 통째로 없어도 초록이 된다.
     */
    private String verdictLine(LogCaptor captor) {
        long session = runner.lastSessionNo();
        return captor.messages().stream()
                .filter(m -> m.startsWith("chat.session.verdict session=" + session + " "))
                .findFirst()
                .orElseThrow(() -> new AssertionError("session=" + session + " 판정 라인이 안 나갔다"));
    }

    private void awaitReceived(long count) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (runner.metrics().totalReceived() < count && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    /**
     * <b>상태가 아니라 우리가 단언할 그 줄을 기다린다.</b>
     *
     * <p>상태는 뒷정리보다 <b>먼저</b> 찍힌다. 상태만 보고 앞지르면 재연결 스레드가
     * 세션 종료 줄에 닿기 전에 단언이 서고, 그때 {@code stop()}은 이미 소모된
     * 뒷정리 가드에 걸려 즉시 돌아와 아무 줄도 안 남긴다 — 간헐 실패한다.
     * 창을 넓히는 것으로는 없앨 수 없고(느린 기계에서는 상시 빨강이다)
     * 기다리는 대상을 옮겨야 없어진다.
     *
     * <p>기다리는 대상이 판정 줄이 아니라 <b>세션 종료 줄</b>이다 — 절단은 이제
     * 판정을 안 내므로 판정 줄은 이 시점에 존재하지 않는다.
     */
    private static void awaitEnded(LogCaptor captor, long session) throws Exception {
        String prefix = "chat.session.ended session=" + session + " ";
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (captor.messages().stream().noneMatch(m -> m.startsWith(prefix))
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }
}
