package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.TestPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class CollectorConfigTest extends IntegrationTestSupport {

    @Autowired CollectorHealth health;
    @Autowired CollectionStatus status;
    @Autowired Environment environment;

    /**
     * T8. 기본이 켜짐이면 CI·남의 로컬이 뜰 때마다 붙으려 하고
     * Access Token당 연결 상한 3개를 말없이 먹는다.
     */
    @Test
    void 기본값은_꺼짐이고_아무_데도_붙지_않는다() {
        assertThat(environment.getProperty("pokeclip.chzzk.enabled", Boolean.class, false)).isFalse();
        assertThat(status.state()).isEqualTo(CollectionStatus.State.DISABLED);
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
        assertThat(health.health().getDetails()).containsEntry("status", "disabled");
    }

    /**
     * T14. 단수형 spring.http.client.*는 4.0.0부터 아무것도 바인딩하지 않는다 —
     * 오타를 내면 에러 없이 타임아웃이 사라지고, 세션 발급이 영영 매달린다.
     * auth의 GoogleTimeoutTest와 같은 이유로 바인딩 결과를 직접 본다.
     */
    @Test
    void HTTP_타임아웃이_실제로_바인딩됐다() {
        assertThat(environment.getProperty("spring.http.clients.connect-timeout"))
                .as("복수형 clients가 맞다. 단수형은 조용히 무시된다")
                .isNotNull();
        assertThat(environment.getProperty("spring.http.clients.read-timeout")).isNotNull();
        assertThat(environment.getProperty("spring.http.client.connect-timeout"))
                .as("단수형이 적혀 있으면 그쪽이 무시되는 줄이다")
                .isNull();
    }

    /**
     * `enabled` 기본값이 false라 `CollectorRunner`는 아무도 안 도는 코드로 남기
     * 쉽다. 실제로 켰을 때 도는지, 실패했을 때 사유가 남고 health가 DOWN이
     * 되는지를 여기서 못박는다 — <b>그 상태가 이 카드가 막으려는 실패 양식이다.</b>
     *
     * <p>부팅 순서 때문에 `@SpringBootTest`로는 가짜 서버 포트를 프로퍼티에 못
     * 넣는다. 그래서 러너를 직접 조립해 부른다.
     */
    @Nested
    @FakeChzzkTest
    class 러너 {

        @LocalServerPort int port;
        @Autowired FakeChzzkBehavior behavior;
        /** 자동 설정된 빌더다. 손으로 만들면 타임아웃 설정을 우회하게 된다. */
        @Autowired RestClient.Builder restClientBuilder;

        private CollectorRunner runner;

        /** 앞 테스트가 먹은 auth 호출이 남으면 authCallCount 단언이 뒤집힌다. */
        @AfterEach
        void 각_테스트마다_정리한다() {
            if (runner != null) runner.stop();
            behavior.reset();
        }

        @Test
        void 켜면_수집이_시작되고_health가_UP이다() {
            CollectionStatus status = new CollectionStatus();
            runner = runnerFor(status, true);

            runner.start();

            assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);
            assertThat(new CollectorHealth(status).health().getStatus()).isEqualTo(Status.UP);

            // 양성 대조. 상태만 COLLECTING으로 바꾸고 아무 데도 안 붙는 러너도
            // 위 두 줄을 통과한다. 수립 절차를 실제로 탔다는 증거가 필요하다.
            assertThat(behavior.authCallCount())
                    .as("세션 발급을 안 불렀다면 붙은 적이 없고 COLLECTING은 거짓말이다")
                    .isEqualTo(1);
            assertThat(behavior.handshakeQuery()).contains("EIO=3");
        }

        /**
         * <b>{@code run()}으로 띄운다.</b> {@code start()}는 수립 실패를 밖으로 던지고,
         * 재시도할지는 그 사유를 받은 쪽이 정한다 — 운영 경로가 {@code run()}이다.
         */
        @Test
        void 세션_발급이_401이면_STOPPED에_사유가_남고_health가_DOWN이다() throws Exception {
            behavior.authStatus = 401;
            CollectionStatus status = new CollectionStatus();

            runner = runnerFor(status, true);
            runner.run(null);

            // 영구 정지는 재연결 스레드가 찍는다. 안 기다리면 아직 RECONNECTING이다.
            awaitState(status, CollectionStatus.State.STOPPED);
            assertThat(status.state()).isEqualTo(CollectionStatus.State.STOPPED);
            assertThat(status.reason()).isEqualTo(StopReason.SESSION_AUTH_REJECTED);

            var health = new CollectorHealth(status).health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .as("밖에서 보이는 이름이 바로 이 값이다. 거부와 일시 실패가 같은 문자열이면 "
                            + "운영자가 기다리면 풀릴 일인지 토큰을 갈아야 할 일인지 모른다")
                    .containsEntry("reason", "SESSION_AUTH_REJECTED");

            // 401은 거부다. 재연결이 붙어도 다시 걸지 않는다.
            assertThat(behavior.authCallCount()).isEqualTo(1);
        }

        /**
         * 서버가 조용히 끊었을 때가 2026-08-01의 마지막 장면이다. 그때 상태가
         * COLLECTING으로 남아 health가 UP이면, 수집이 죽었는데 배포도 헬스체크도
         * 통과하는 상태가 된다 — 이 카드가 막으려는 바로 그 실패다.
         */
        @Test
        void 전송이_끊기면_RECONNECTING에_사유가_남고_health가_DOWN이_된다() throws Exception {
            CollectionStatus status = new CollectionStatus();
            runner = runnerFor(status, true);
            runner.start();
            assertThat(status.state())
                    .as("붙지도 않았다면 아래에서 끊는 것은 아무 의미가 없다")
                    .isEqualTo(CollectionStatus.State.COLLECTING);

            behavior.closeSession();

            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (status.state() != CollectionStatus.State.RECONNECTING
                    && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }

            // STOPPED가 아니라 RECONNECTING이다. 상태 이름은 갈렸지만 <b>밖에서 보이는
            // 것은 그대로 DOWN이어야 한다</b> — 재연결 중에도 채팅은 안 들어온다.
            assertThat(status.state()).isEqualTo(CollectionStatus.State.RECONNECTING);
            assertThat(status.reason()).isEqualTo(StopReason.TRANSPORT_CLOSED);
            assertThat(new CollectorHealth(status).health().getStatus()).isEqualTo(Status.DOWN);
        }

        /** 꺼져 있으면 붙지 않는다. 상태 문자열만 맞추는 것으로는 부족하다. */
        @Test
        void 꺼져_있으면_세션_발급조차_부르지_않는다() {
            CollectionStatus status = new CollectionStatus();
            runner = runnerFor(status, false);

            runner.start();

            assertThat(status.state()).isEqualTo(CollectionStatus.State.DISABLED);
            assertThat(behavior.authCallCount()).isZero();
        }

        /**
         * 재시도 간격을 크게 준다. 이 클래스가 보는 것은 <b>어느 실패가 어느 상태와
         * 사유로 나타나는가</b>이고, 짧은 간격이면 그 상태가 재시도로 계속 갈려
         * 무엇을 읽었는지 흐려진다. 다시 붙는 것은 {@code ReconnectTest}가 본다.
         */
        private CollectorRunner runnerFor(CollectionStatus status, boolean enabled) {
            var props = new ChzzkProperties(enabled, "test-token",
                    "http://localhost:" + port, Duration.ofSeconds(5),
                    Duration.ofSeconds(30), Duration.ofSeconds(60));
            return new CollectorRunner(props, status, restClientBuilder,
                    TestPersistence.unusedBuffer(), TestPersistence.disabledPersister());
        }

        private static void awaitState(CollectionStatus status, CollectionStatus.State state)
                throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (status.state() != state && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
        }
    }

    /**
     * auth에서 물려받은 예방책이다. <b>이 서버에서 DEBUG 유출을 재현하지는 못했다</b>
     * (2026-08-05 실측).
     *
     * <p>root·{@code org.springframework.web}·{@code .client}·{@code org.springframework.http}·
     * {@code jdk.internal.httpclient}를 전부 DEBUG로 올리고 수집 경로 전체를 태워
     * DEBUG 이벤트 168건을 모았는데 토큰도 본문도 안 나왔다. auth가 재현한 유출은
     * <b>나가는 폼 본문</b>인데 우리는 나가는 본문이 아예 없고(GET 발급 · 쿼리 POST),
     * Authorization 헤더는 스프링이 기본으로 마스킹한다
     * ({@code enableLoggingRequestDetails='false'}). 운영 코드에 log.debug/log.trace도 0건이다.
     *
     * <p>그래서 auth의 "DEBUG로 켜면 샌다" 단언을 그대로 옮기지 않았다 —
     * 여기서는 그게 거짓이라 빨간불이 난다. 이 줄은 <b>지금 새는 것을 막는 것이
     * 아니라 앞으로 새지 않게 두는 것</b>이고, 그 사실을 모르면 다음 사람이
     * "안 새는데 왜 있지" 하고 지운다.
     *
     * <p>Boot를 올리는 날 다시 잰다.
     */
    @Test
    void 스프링_web_로거_레벨이_설정에_박혀_있다() {
        String level = environment.getProperty("logging.level.org.springframework.web");
        assertThat(level).as("root를 내려도 버티려면 구체 로거에 박혀 있어야 한다").isNotNull();
        assertThat(level).isEqualToIgnoringCase("info");
    }
}
