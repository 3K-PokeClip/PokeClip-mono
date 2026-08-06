package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

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
class FinalVerdictTest {

    /** PRD 「판정 항목」이 최종 라인에 요구하는 것 전부. */
    private static final List<String> REQUIRED = List.of(
            "received=", "collectedFor=", "lastReceivedAt=", "maxReceiveGap=",
            "maxPingGap=", "maxPongGap=", "orderViolations=",
            "delayMin=", "delayMedian=", "delayMax=", "delaySamples=",
            "system=", "decodeFailures=",
            "sendFailures=", "callbackFailures=", "sinkFailures=",
            // 없으면 "정상 종료"와 "조용히 끊겼다"가 같은 줄이 된다.
            "reason=");

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;

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

    /** 종료 경로가 둘이다. 둘 다 지나가면 두 줄이 나가고 어느 것이 끝인지 흐려진다. */
    @Test
    void 전송이_끊긴_뒤_종료해도_판정_라인은_한_줄뿐이다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            CollectionStatus status = start();
            assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);

            behavior.closeSession();
            awaitStopped(status);
            runner.stop();                       // 두 번째 종료 경로

            assertThat(captor.messages().stream().filter(m -> m.startsWith("chat.session.verdict")))
                    .hasSize(1);
            assertThat(verdictLine(captor))
                    .as("왜 끝났는지가 없으면 정상 종료와 구분이 안 된다")
                    .contains("reason=TRANSPORT_CLOSED");
            assertThat(captor.messages()).anyMatch(m -> m.startsWith("chat.session.closed"));
        }
    }

    /** PRD 「상태」 표: 실패로 중지 → 실패 한 줄 <b>+ 최종 판정 라인</b>. */
    @Test
    void 수립에_실패해도_판정_라인이_나간다() {
        behavior.authStatus = 401;

        try (LogCaptor captor = new LogCaptor()) {
            CollectionStatus status = start();
            assertThat(status.state()).isEqualTo(CollectionStatus.State.STOPPED);

            assertThat(captor.messages()).anyMatch(m -> m.startsWith("chat.session.stopped"));
            assertThat(verdictLine(captor))
                    .contains("received=0")
                    .contains("reason=SESSION_AUTH_FAILED");

            // 수립에 실패했으니 반납할 세션 키가 없다. 이 갈래를 밟는 테스트는
            // 여기뿐인데 결말을 아무도 안 봤다 — RETURNED·SKIPPED 라벨을 서로
            // 뒤바꿔도 전체 89건이 초록이었다. failed와 갈라 놓은 이유가
            // "보냈는데 터졌다"와 "보낼 것이 없었다"를 구분하는 것이므로,
            // 그 값을 실제로 보는 줄이 있어야 한다.
            assertThat(captor.messages())
                    .as("반납할 키가 없었던 결말이 skipped로 안 나가면 반납 실패와 구분이 안 된다")
                    .contains("chat.session.released subscription=skipped");
        }
    }

    /** 꺼져 있으면 수집한 적이 없다. 판정할 것이 없는데 줄이 나가면 잡음이다. */
    @Test
    void 꺼져_있으면_판정_라인이_안_나간다() {
        try (LogCaptor captor = new LogCaptor()) {
            CollectionStatus status = new CollectionStatus();
            runner = new CollectorRunner(new ChzzkProperties(
                    false, "test-token", "http://localhost:" + port, Duration.ofSeconds(5)), status);
            runner.start();
            runner.stop();

            assertThat(status.state()).isEqualTo(CollectionStatus.State.DISABLED);
            assertThat(captor.messages()).noneMatch(m -> m.startsWith("chat.session.verdict"));
        }
    }

    private CollectionStatus start() {
        CollectionStatus status = new CollectionStatus();
        runner = new CollectorRunner(new ChzzkProperties(
                true, "test-token", "http://localhost:" + port, Duration.ofSeconds(5)), status);
        runner.start();
        return status;
    }

    private static String verdictLine(LogCaptor captor) {
        return captor.messages().stream()
                .filter(m -> m.startsWith("chat.session.verdict"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("최종 판정 라인이 한 줄도 안 나갔다"));
    }

    private void awaitReceived(long count) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (runner.metrics().totalReceived() < count && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private void awaitStopped(CollectionStatus status) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (status.state() != CollectionStatus.State.STOPPED && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }
}
