package com.pokeclip.chat.collector;

import ch.qos.logback.classic.spi.ILoggingEvent;
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
 * <b>막힌 자리를 사람이 가를 수 있는가.</b>
 *
 * <p>3층 CLAUDE.md가 POK-86으로 넘긴 것 중 하나가 "연결 실패 사유가 한 줄로
 * 뭉쳐 있다"이다. 열거값은 태스크 4가 갈랐지만 <b>그 아래 한 단계가 여전히
 * 뭉쳐 있었다</b> — {@code SESSION_AUTH_FAILED}는 500인지 타임아웃인지를 안 말하고,
 * {@code CONNECT_REFUSED}는 DNS인지 TLS인지 연결 거부인지를 안 말한다.
 *
 * <p>가르는 문자열은 예외 안에 <b>이미 만들어져 있었고 그대로 버려졌다</b>.
 * 재연결이 반복 실패할 때 이 한 항이 없으면 사람은 같은 줄만 보고 엉뚱한 곳을 판다.
 *
 * <p><b>합격선은 그대로다.</b> 여기 실리는 것은 상태 코드·예외 단순 이름·단계
 * 이름뿐이라 본문·닉네임·토큰이 실릴 자리가 없다. 그 사실은 {@code ChatLogLeakTest}가
 * 같은 401 경로를 지나며 강제한다.
 */
@FakeChzzkTest
class StopDiagnosticsTest extends IntegrationTestSupport {

    /** 아무도 안 듣는 포트. 루프백이라 즉시 거부가 돌아와 결정적으로 실패한다. */
    private static final int DEAD_PORT = 1;

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
     * 401·403(거부)이 아닌 실패는 전부 {@code SESSION_AUTH_FAILED} 한 줄이다.
     * 상태 코드가 없으면 "서버가 아프다"와 "우리가 잘못 불렀다"가 같은 줄이 된다.
     */
    @Test
    void 세션_발급이_막히면_상태_코드가_stopped_줄에_실린다() {
        behavior.authStatus = 500;

        try (LogCaptor captor = new LogCaptor()) {
            start();

            assertThat(stoppedLine(captor))
                    .as("실패 경로를 안 태웠다면 검사할 줄이 애초에 없다")
                    .isNotNull()
                    .contains("reason=" + StopReason.SESSION_AUTH_FAILED)
                    .contains("detail=status=500");
        }
    }

    /**
     * {@code CONNECT_REFUSED}는 <b>시한 초과가 아닌 모든 I/O 실패</b>가 모이는
     * 자리다 — DNS 실패도 TLS 실패도 연결 거부도 같은 값이다. 그것을 가르는
     * 유일한 단서가 예외의 단순 이름이고, 그 이름은 여기 안 실리면 사라진다.
     */
    @Test
    void 접속이_막히면_예외_종류가_stopped_줄에_실린다() {
        behavior.sessionUrlPort = DEAD_PORT;

        try (LogCaptor captor = new LogCaptor()) {
            start();

            String line = stoppedLine(captor);
            assertThat(line)
                    .as("실패 경로를 안 태웠다면 검사할 줄이 애초에 없다")
                    .isNotNull()
                    .contains("reason=" + StopReason.CONNECT_REFUSED);
            assertThat(detailOf(line))
                    .as("예외 이름이 비어 있으면 DNS·TLS·연결 거부가 여전히 같은 줄이다")
                    .matches("cause=\\w+Exception");
        }
    }

    /**
     * <b>실패한 재시도는 자기가 막힌 자리로 정리돼야 한다.</b>
     *
     * <p>재시도 중에는 상태가 늘 {@code RECONNECTING}이고 <b>그 사유는 앞 시도의
     * 것</b>이다. 세션 종료 줄이 상태에서 사유를 읽으면, 절단 뒤 첫 재시도가 세션
     * 발급 5xx로 죽었는데도 {@code chat.session.ended ... reason=TRANSPORT_CLOSED}로
     * 나간다 — 이 줄은 "몇 번째 세션이 어디서 막혔나"를 드는 유일한 자리라,
     * 여기가 앞 시도를 가리키면 재연결이 반복 실패할 때 사람이 엉뚱한 곳을 판다.
     *
     * <p><b>절단 사유가 무의미해진 것이 아니다.</b> 같은 세션에 실제로 절단이 온
     * 경우(수립을 마치는 사이에 끊기는 창)에는 그것이 원인이고 수립 예외는 그
     * 결과다. 그래서 <b>이 세션에 온 절단</b>과 <b>앞 시도가 남긴 상태</b>를 가른다.
     *
     * <p>재시도 간격을 크게 준다 — 짧으면 같은 줄이 여러 번 쌓여 어느 시도를
     * 읽었는지 흐려진다.
     */
    @Test
    void 실패한_재시도는_앞_시도의_사유로_정리되지_않는다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            CollectionStatus status = new CollectionStatus();
            runner = new CollectorRunner(new ChzzkProperties(true, "test-token",
                    "http://localhost:" + port, Duration.ofSeconds(5),
                    Duration.ofSeconds(1), Duration.ofSeconds(60)),
                    status, restClientBuilder,
                            TestPersistence.unusedBuffer(), TestPersistence.disabledPersister());
            runner.run(null);
            assertThat(status.state())
                    .as("붙지도 않았다면 절단도 재시도도 없어 검사할 줄이 없다")
                    .isEqualTo(CollectionStatus.State.COLLECTING);
            long first = runner.lastSessionNo();

            behavior.authStatus = 500;      // 다음 시도는 ①에서 죽는다
            behavior.closeSession();

            // 재시도가 자리를 잡은 것을 보고 나간다. 번호를 상수로 박지 않는다 —
            // SESSION_SEQ는 프로세스 전역이라 앞 클래스가 이미 쓴 번호가 있다.
            awaitUntil(() -> runner.lastSessionNo() > first);
            long retried = runner.lastSessionNo();
            assertThat(retried)
                    .as("재시도가 자리를 못 잡았다면 그 시도의 종료 줄이 애초에 없다")
                    .isGreaterThan(first);
            awaitUntil(() -> endedLine(captor, retried) != null);

            assertThat(endedLine(captor, first))
                    .as("절단으로 끝난 세션은 그대로 절단이 사유다")
                    .isNotNull()
                    .contains("reason=" + StopReason.TRANSPORT_CLOSED);
            assertThat(endedLine(captor, retried))
                    .as("앞 시도의 사유를 물려받으면 어느 단계에서 막혔는지가 뒤바뀐다")
                    .isNotNull()
                    .contains("reason=" + StopReason.SESSION_AUTH_FAILED);
        }
    }

    private static String endedLine(LogCaptor captor, long session) {
        return captor.messages().stream()
                .filter(m -> m.startsWith("chat.session.ended session=" + session + " "))
                .findFirst()
                .orElse(null);
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    /**
     * <b>{@code run()}으로 띄운다.</b> {@code start()}는 수립 실패를 밖으로 던지므로
     * 직접 부르면 예외가 테스트 메서드를 뚫고 나가 단언에 못 닿는다.
     *
     * <p>재시도 간격을 크게 준다. 이 검사는 <b>한 번의 실패가 남기는 줄</b>을 보는
     * 것이라, 짧은 간격이면 같은 줄이 계속 쌓여 무엇을 읽는지 흐려진다.
     */
    private void start() {
        runner = new CollectorRunner(new ChzzkProperties(true, "test-token",
                "http://localhost:" + port, Duration.ofSeconds(5),
                Duration.ofSeconds(30), Duration.ofSeconds(60)),
                new CollectionStatus(), restClientBuilder,
                        TestPersistence.unusedBuffer(), TestPersistence.disabledPersister());
        runner.run(null);
    }

    /**
     * <b>이 검사 스레드가 찍은 줄만 고른다.</b> {@code LogCaptor}는 root 로거에 붙는
     * <b>JVM 전역</b>이라, 캐시된 남의 컨텍스트가 재연결하며 찍는 {@code stopped} 줄이
     * 이 창에 들어온다 — 「첫 줄」로 집으면 그것을 자기 줄로 읽고 간헐 빨간불이 난다
     * (2026-08-19 재현: {@code CollectorHealthEndpointTest.수집이_멈췄을_때}의 컨텍스트가
     * 1초마다 {@code detail=cause=ResourceAccessException}을 찍었고, 창을 1.5초 열어 두면
     * 100% 그 줄이 먼저다).
     *
     * <p><b>스레드가 열쇠인 이유</b> — {@code stopped} 줄에는 세션 번호가 없어
     * {@code endedLine}처럼 번호로 못 가른다. 대신 {@code start()}가 부르는
     * {@code run() → openFromBoot() → open()}이 <b>전부 동기</b>라 그 줄은 이 검사
     * 스레드에서 나오고, 남의 컨텍스트 것은 재연결 스레드에서 나온다.
     *
     * <p>그 시끄러움 자체는 저쪽에서 없앴다. <b>여기는 다음에 시끄러운 컨텍스트가
     * 생겨도 이 검사가 안 흔들리게 하는 두 번째 겹이다.</b>
     */
    private static String stoppedLine(LogCaptor captor) {
        String self = Thread.currentThread().getName();
        return captor.events().stream()
                .filter(e -> self.equals(e.getThreadName()))
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("chat.session.stopped"))
                .findFirst()
                .orElse(null);
    }

    /** 줄 끝의 {@code detail=} 뒤 전부. 항이 없으면 빈 문자열이라 그대로 빨간불이 된다. */
    private static String detailOf(String line) {
        int at = line.indexOf("detail=");
        return at < 0 ? "" : line.substring(at + "detail=".length()).trim();
    }
}
