package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.engineio.Handshake;
import com.pokeclip.chat.collector.engineio.PingFailure;
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
 * <b>하트비트가 알리는 두 사건이 운영 로그에 실제로 나가는지.</b>
 *
 * <p>태스크 5·6이 운영에 붙는 지점은 러너의 {@code HeartbeatListener} 하나뿐인데,
 * <b>그 두 메서드를 빈 몸통으로 바꿔도 전 테스트가 초록이었다</b>(CP2 실측).
 * 좀비 판정도 송신 실패 분류도 통째로 사라질 수 있고 아무도 모르는 상태였다.
 * 재연결(태스크 9)이 이 두 줄에 매달리므로 여기가 비면 그 위가 전부 뜬다.
 *
 * <p><b>자를 두 개 쓰는 이유가 있다.</b> 좀비는 가짜 서버가 pong을 끊으면 러너를
 * 통째로 지나 재현되지만, 송신 실패는 루프백으로 못 만든다 — 전송이 끊기면
 * 우리 뒷정리가 하트비트를 <b>먼저</b> 닫으므로, "쓰기가 실패하는데 읽기 통지는
 * 아직 안 왔다"는 실제 망 사고의 순서가 같은 기계 안에서는 만들어지지 않는다.
 * 그래서 그쪽은 리스너를 직접 부른다.
 */
@FakeChzzkTest
class HeartbeatSignalTest extends IntegrationTestSupport {

    /** 실측 비율을 유지한 압축값. 파생: 송신 80ms · pong 임계 200ms. */
    private static final Handshake COMPRESSED =
            new Handshake("s", Duration.ofMillis(100), Duration.ofMillis(240));

    private static final Duration AWAIT = Duration.ofSeconds(5);

    /**
     * 세션이 안 선 러너에 신호를 넣는다. 리스너는 자기 세션 번호를 들고 다니는데
     * (낡은 신호가 새 세션을 헐지 못하게 하는 세대 표식이다) 여기서는 붙은 세션이
     * 없으므로 "아무 세션도 아님"을 넣는다 — 이 검사가 보는 것은 로그 한 줄이다.
     */
    private static final long NO_SESSION = 0L;

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
     * 소켓은 살아 있고 ping도 나가는데 pong만 안 온다. <b>onClose가 안 오므로
     * 이 줄이 없으면 좀비 연결을 영영 못 알아챈다</b> — health는 UP인데 채팅만
     * 안 들어오는 상태가 그대로 남는다.
     */
    @Test
    void 좀비가_되면_러너가_pong_timeout을_남긴다() throws Exception {
        behavior.pingIntervalMillis = COMPRESSED.pingInterval().toMillis();
        behavior.pingTimeoutMillis = COMPRESSED.pingTimeout().toMillis();
        behavior.answerPong = false;                  // 좀비를 만든다
        behavior.disconnectWhenPingMissing = false;   // ping은 제때 나가므로 끊길 이유가 없다

        try (LogCaptor captor = new LogCaptor()) {
            CollectionStatus status = new CollectionStatus();
            runner = runnerFor(status);
            runner.start();

            // 양성 대조. 안 붙었으면 하트비트가 아예 없어 이 검사는 아무것도 안 본다.
            assertThat(status.state())
                    .as("붙지도 않았다면 좀비가 될 연결이 없다")
                    .isEqualTo(CollectionStatus.State.COLLECTING);

            awaitUntil(() -> line(captor) != null);

            String reported = line(captor);
            assertThat(reported)
                    .as("좀비를 안 알리면 재연결은 이 절단을 영영 못 본다")
                    .isNotNull();
            // 값까지 본다. 상수를 찍는 구현이면 임계 아래가 나온다.
            assertThat(gapMillis(reported))
                    .as("임계 아래 값이 실렸다면 이 줄은 좀비를 잰 것이 아니다")
                    .isGreaterThanOrEqualTo(COMPRESSED.pongThreshold().toMillis());
        }
    }

    /**
     * <b>원인이 그대로 실려야 한다.</b> 태스크 9가 {@code MISUSE}만 재연결 대상에서
     * 빼는데, 이 줄이 원인을 뭉개면 우리 버그가 자동 복구에 덮여 영영 안 보이면서
     * 연결 상한만 태운다 — {@code PingFailure}가 원인을 가르는 이유 그 자체다.
     */
    @Test
    void 송신이_실패하면_러너가_원인을_실어_ping_send_failed를_남긴다() {
        try (LogCaptor captor = new LogCaptor()) {
            runner = runnerFor(new CollectionStatus());

            runner.heartbeatListener(NO_SESSION).onSendFailed(PingFailure.Cause.MISUSE);
            runner.heartbeatListener(NO_SESSION).onSendFailed(PingFailure.Cause.CONNECTION_DEAD);

            assertThat(captor.messages())
                    .as("이 줄이 안 나가면 ping이 안 나가고 있다는 사실이 어디에도 안 남는다")
                    .contains("chat.session.ping_send_failed cause=MISUSE",
                            "chat.session.ping_send_failed cause=CONNECTION_DEAD");
        }
    }

    /**
     * 재시도 간격을 크게 준다. 여기서 보는 것은 <b>하트비트가 알리는 줄</b>이고,
     * 그 신호를 받아 다시 붙는 것은 {@code ReconnectTest}가 본다 — 짧은 간격이면
     * 좀비 세션이 계속 갈려서 어느 세션의 줄을 읽는지 흐려진다.
     */
    private CollectorRunner runnerFor(CollectionStatus status) {
        return new CollectorRunner(new ChzzkProperties(true, "test-token",
                "http://localhost:" + port, Duration.ofSeconds(5),
                Duration.ofSeconds(30), Duration.ofSeconds(60)),
                status, restClientBuilder,
                        TestPersistence.unusedBuffer(), TestPersistence.disabledPersister());
    }

    private static String line(LogCaptor captor) {
        return captor.messages().stream()
                .filter(m -> m.startsWith("chat.session.pong_timeout"))
                .findFirst()
                .orElse(null);
    }

    /** 줄에 실린 밀리초. 형식이 바뀌면 파싱이 터져 그대로 빨간불이 된다. */
    private static long gapMillis(String reported) {
        return Long.parseLong(reported.substring(reported.indexOf("gapMs=") + "gapMs=".length()).trim());
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }
}
