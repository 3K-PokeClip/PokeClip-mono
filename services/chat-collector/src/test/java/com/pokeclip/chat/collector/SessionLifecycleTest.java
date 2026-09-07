package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.TestPersistence;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T10. 세션이 살아 있는 동안 오는 SYSTEM 이벤트가 남는지.
 *
 * <p>동의 철회(revoked)로 <b>수집을 멈추는지</b>는 여기서 안 본다 —
 * {@code ReconnectTest.동의가_철회되면_재시도하지_않고_멈춘다}가 본다.
 * 이 클래스가 지키는 것은 <b>흔적</b>이다: 멈춘 뒤에 원인을 되짚으려면
 * 로그 한 줄과 요약의 건수가 남아 있어야 한다.
 */
@FakeChzzkTest
class SessionLifecycleTest extends IntegrationTestSupport {

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;
    @Autowired RestClient.Builder restClientBuilder;

    private CollectorRunner runner;

    @AfterEach
    void tearDown() {
        if (runner != null) runner.stop();
        behavior.reset();
    }

    /**
     * 옛 경로({@code CHZZK_ENABLED})는 방송 번호가 없다. {@code chat_donations.stream_id}는
     * NOT NULL이라 담으면 INSERT가 <b>영구히</b> 실패하면서 되돌리기를 무한 반복하고,
     * 바구니가 차서 멀쩡한 후원까지 밀려난다 — 그래서 이 경로의 후원은 아예 안 받는다.
     * 채팅은 그 칸이 NULL 허용이라 「모른다」로 남길 수 있지만 여기는 그 길이 없다.
     *
     * <p>세는 자리가 가드 <b>뒤</b>라, 가드를 지우면 이 수가 1이 되어 빨간불이다.
     */
    @Test
    void 옛_경로의_후원은_세지도_담지도_않는다() throws Exception {
        CollectionStatus status = new CollectionStatus();
        runner = new CollectorRunner(new ChzzkProperties(
                true, "test-token", "http://localhost:" + port, Duration.ofSeconds(5),
                Duration.ofMillis(50), Duration.ofSeconds(1)), status, restClientBuilder,
                        TestPersistence.unusedBuffer(), TestPersistence.disabledPersister());
        runner.start();
        assertThat(status.state())
                .as("붙지도 않았다면 후원을 받을 길이 없다")
                .isEqualTo(CollectionStatus.State.COLLECTING);

        // 🔴 <b>후원을 먼저, 채팅을 나중에 쏜다</b>(감사 라운드 2 C1). 순서가 반대면 아래
        // 기다림이 「후원이 처리됐다」를 보증하지 못한다 — 채팅 도착만 기다린 뒤 곧장
        // donations()를 보므로, 부하가 있으면 후원 프레임이 아직 처리 전이라 0이 나온다.
        // 그래서 가드를 지워도 <b>모듈 전체 실행에서는 초록</b>이었다(단독 실행은 빨강).
        // WebSocket 프레임은 순서대로 오고 handleFrame은 수신 스레드 하나에서 도므로,
        // <b>뒤에 쏜 채팅이 도착했다는 것이 곧 앞의 후원이 이미 처리됐다는 것</b>이다.
        behavior.emitDonation("{\"donationType\":\"CHAT\",\"channelId\":\"legacy-don\","
                + "\"donatorChannelId\":\"D\",\"donatorNickname\":\"n\","
                + "\"payAmount\":\"1000\",\"donationText\":\"t\"}");
        behavior.emitChat("{\"channelId\":\"legacy-don\",\"senderChannelId\":\"s\","
                + "\"content\":\"x\",\"messageTime\":1754300000000}");

        // 양성 대조 — 채팅이 도착한 것으로 「프레임이 실제로 지나갔다」를 못박는다.
        // 이것이 없으면 아래 isZero()는 소켓이 아무것도 안 나른 경우에도 참이다.
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (runner.metrics().totalReceived() < 1 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(runner.metrics().totalReceived()).isEqualTo(1);

        assertThat(runner.metrics().verdict().donations())
                .as("방송 번호가 없는 후원을 담으면 INSERT가 영구 실패하며 바구니를 채운다")
                .isZero();
    }

    @Test
    void revoked를_받으면_로그와_요약에_남는다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            CollectionStatus status = new CollectionStatus();
            runner = new CollectorRunner(new ChzzkProperties(
                    true, "test-token", "http://localhost:" + port, Duration.ofSeconds(5),
                    Duration.ofMillis(50), Duration.ofSeconds(1)), status, restClientBuilder,
                            TestPersistence.unusedBuffer(), TestPersistence.disabledPersister());
            runner.start();
            assertThat(status.state())
                    .as("붙지도 않았다면 revoked를 받을 길이 없다")
                    .isEqualTo(CollectionStatus.State.COLLECTING);

            behavior.emitSystem("{\"type\":\"revoked\",\"data\":{\"eventType\":\"CHAT\"}}");

            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!captor.messages().stream().anyMatch(m -> m.startsWith("chat.session.revoked"))
                    && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }

            assertThat(captor.messages()).anyMatch(m -> m.startsWith("chat.session.revoked"));
            assertThat(runner.metrics().snapshot().systemEvents())
                    .as("요약에 안 실리면 30초 줄만 보는 사람은 철회를 영영 모른다")
                    .containsKey("revoked");
        }
    }

    /**
     * SYSTEM은 connected·subscribed·unsubscribed·revoked 넷뿐이다.
     * unsubscribed도 "연결은 살아 있는데 채팅만 안 오는" 상태를 만드는데,
     * 안 세면 revoked와 구분이 안 돼 원인을 되짚을 수 없다.
     */
    @Test
    void unsubscribed도_요약에_남는다() throws Exception {
        CollectionStatus status = new CollectionStatus();
        runner = new CollectorRunner(new ChzzkProperties(
                true, "test-token", "http://localhost:" + port, Duration.ofSeconds(5),
                Duration.ofMillis(50), Duration.ofSeconds(1)), status, restClientBuilder,
                        TestPersistence.unusedBuffer(), TestPersistence.disabledPersister());
        runner.start();
        assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);

        behavior.emitSystem("{\"type\":\"unsubscribed\",\"data\":{\"eventType\":\"CHAT\"}}");

        // systemEvents는 누적이라 snapshot()을 반복해도 값이 사라지지 않는다.
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!runner.metrics().snapshot().systemEvents().containsKey("unsubscribed")
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(runner.metrics().snapshot().systemEvents()).containsKey("unsubscribed");
    }

    /**
     * 수립 과정의 SYSTEM 둘도 요약에 남아야 한다. revoked만 세고 있으면
     * "구독이 된 적은 있나"를 되짚을 수 없다.
     */
    @Test
    void 수립_과정의_connected와_subscribed가_요약에_남는다() {
        CollectionStatus status = new CollectionStatus();
        runner = new CollectorRunner(new ChzzkProperties(
                true, "test-token", "http://localhost:" + port, Duration.ofSeconds(5),
                Duration.ofMillis(50), Duration.ofSeconds(1)), status, restClientBuilder,
                        TestPersistence.unusedBuffer(), TestPersistence.disabledPersister());
        runner.start();

        assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);
        assertThat(runner.metrics().snapshot().systemEvents())
                .containsKeys("connected", "subscribed");
    }
}
