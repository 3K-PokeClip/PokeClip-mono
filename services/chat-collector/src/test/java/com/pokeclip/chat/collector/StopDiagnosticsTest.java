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
class StopDiagnosticsTest {

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

    private void start() {
        runner = new CollectorRunner(new ChzzkProperties(true, "test-token",
                "http://localhost:" + port, Duration.ofSeconds(5),
                Duration.ofMillis(50), Duration.ofSeconds(1)),
                new CollectionStatus(), restClientBuilder);
        runner.start();
    }

    private static String stoppedLine(LogCaptor captor) {
        return captor.messages().stream()
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
