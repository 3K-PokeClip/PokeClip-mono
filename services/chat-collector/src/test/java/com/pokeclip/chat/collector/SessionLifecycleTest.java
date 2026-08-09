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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T10. 세션이 살아 있는 동안 오는 SYSTEM 이벤트가 남는지.
 *
 * <p>동의 철회(revoked)는 <b>대응이 POK-93</b>이다. 여기서는 온 사실이 남는지만 본다 —
 * 안 남으면 채팅이 끊긴 뒤 원인을 되짚을 근거가 아무 데도 없다.
 */
@FakeChzzkTest
class SessionLifecycleTest {

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
    void revoked를_받으면_로그와_요약에_남는다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            CollectionStatus status = new CollectionStatus();
            runner = new CollectorRunner(new ChzzkProperties(
                    true, "test-token", "http://localhost:" + port, Duration.ofSeconds(5),
                    Duration.ofMillis(50), Duration.ofSeconds(1)), status, restClientBuilder);
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
                Duration.ofMillis(50), Duration.ofSeconds(1)), status, restClientBuilder);
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
                Duration.ofMillis(50), Duration.ofSeconds(1)), status, restClientBuilder);
        runner.start();

        assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);
        assertThat(runner.metrics().snapshot().systemEvents())
                .containsKeys("connected", "subscribed");
    }
}
