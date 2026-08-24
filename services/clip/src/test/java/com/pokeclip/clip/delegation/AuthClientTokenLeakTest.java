package com.pokeclip.clip.delegation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import com.pokeclip.clip.config.InternalApiProperties;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 🔴 <b>{@code X-Internal-Token}이 로그로 새는지 잰다.</b> 그 토큰은 auth·clip·chat-collector
 * 셋의 {@code /internal/**}을 여는 열쇠 하나다 — 한 번 로그에 남으면 세 서버가 같이 열린다.
 *
 * <p><b>단위 검사가 아니라 통합 검사인 이유가 여기 있다.</b> 이 검사의 표적은 우리 코드가
 * 아니라 <b>{@code application.yml}의 {@code logging.level} 두 줄</b>이다. 그 줄은
 * 스프링이 부팅하며 로깅 시스템에 먹이는 값이라, 컨텍스트 없이는 걸리지 않는다.
 * 그래서 진짜 컨텍스트의 {@code RestClient.Builder}(= 운영과 같은 Apache HC5 스택)로 태운다.
 *
 * <p><b>양성 대조는 결함 주입으로 확인했다</b> — {@code logging.level}의 두 줄을 지우면
 * 이 검사가 빨간불이 된다(계획 검증이 같은 상태에서 유출 2건을 실측했고, 구현 때 다시 쟀다).
 * 그 확인이 없으면 이 검사는 「로그가 원래 안 나오는 것」을 재는 자동 참이다.
 *
 * <p>{@code chat-collector}의 {@code ChzzkLinkClientTest.토큰이_로그에_안_남는다}와 같은
 * 모양이다 — 한쪽을 고치면 다른 쪽도 본다.
 *
 * <p><b>탐지기가 여기 하나다.</b> {@code SegmentApiTokenLeakTest}(들어오는 HTTP)가 아래
 * {@code public static} 넷을 그대로 쓴다 — 복사하면 두 탐지기가 갈리고, <b>느슨해진 쪽이
 * 조용히 초록이 된다</b>(POK-118에서 {@code SseReader}로 실제로 밟았다).
 * 자기검사({@code 탐지기는_예외_안에_숨은_바늘도_잡는다})도 그래서 하나면 된다.
 * {@code chat-collector}의 {@code ChatLogLeakTest}가 같은 자리에서 같은 모양이다.
 */
public class AuthClientTokenLeakTest extends IntegrationTestSupport {

    private final RestClient.Builder restClientBuilder;

    private FakeAuth auth;

    AuthClientTokenLeakTest(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    @BeforeEach
    void startFakeAuth() {
        auth = FakeAuth.start();
    }

    @AfterEach
    void stopFakeAuth() {
        auth.close();
    }

    @Test
    void 내부_토큰이_로그에_안_남는다() {
        String internalToken = needle("internal-token");

        try (LogCaptor captor = new LogCaptor()) {
            // DEBUG·TRACE로 새는 구현은 INFO 캡터에서 조용히 초록이 된다.
            // LogCaptor가 close에서 원래 레벨로 되돌린다.
            setRootLevel(Level.TRACE);

            // 이 검사가 무엇을 태우고 있는지 못박는다. 클래스패스에 httpclient5가 있어
            // (awssdk:sqs가 끌어온다) JDK가 아니라 Apache HC5가 뽑히고, 그 스택의
            // wire·headers 로거가 DEBUG에서 나가는 헤더를 통째로 찍는다.
            // 스택을 JDK로 고정하는 날 이 줄이 먼저 빨간불이 된다 — 그때는
            // application.yml의 로거 고정 두 줄이 아직 필요한지 같이 본다.
            assertThat(ClientHttpRequestFactoryBuilder.detect().build())
                    .as("이 클래스패스에서 뽑히는 팩토리가 바뀌었다 — 이 검사가 재는 대상도 바뀐다")
                    .isInstanceOf(org.springframework.http.client.HttpComponentsClientHttpRequestFactory.class);

            auth.respondWith(200, "{\"relation\":\"OWNER\"}");
            ResolveResult owner = clientFor(auth.baseUrl(), internalToken).resolve(42L, 7L);
            assertThat(owner)
                    .as("호출이 성립하지 않았으면 샐 것도 없다")
                    .isEqualTo(ResolveResult.OWNER);

            auth.respondWith(500, "{\"error\":\"boom\"}");
            clientFor(auth.baseUrl(), internalToken).resolve(43L, 7L);

            clientFor("http://127.0.0.1:1", internalToken).resolve(44L, 7L);

            // 양성 대조 — 우리 로그가 실제로 나갔다. 이것이 없으면 아래 「안 샜다」는 공허하다.
            // 그리고 성공 경로가 아무것도 안 찍는 것까지 여기서 잰다(둘을 합쳐 정확히 2건).
            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("clip.delegation."))
                    .as("5xx와 못 닿음 둘만 남아야 한다 — 성공 경로는 안 찍는다")
                    .hasSize(2)
                    .allMatch(m -> m.startsWith("clip.delegation.resolve_unavailable"));

            assertNoSecretsIn(captor, List.of(internalToken));
        }
    }

    /**
     * 탐지기 자기검사. 위 검사의 {@code doesNotContain}은 <b>아무것도 안 담긴 haystack에
     * 자동으로 통과</b>하고, 특히 {@code log.warn("...", e)}로 새는 값은 포맷된 한 줄에
     * 안 보인다 — ThrowableProxy 안에 있다. {@code RestClientResponseException}의 메시지에는
     * <b>응답 본문이 딸려 온다</b>는 것이 이 갈래가 지키는 위험이다.
     */
    @Test
    void 탐지기는_예외_안에_숨은_바늘도_잡는다() {
        String hidden = needle("in-exception");

        try (LogCaptor captor = new LogCaptor()) {
            LoggerFactory.getLogger(AuthClientTokenLeakTest.class)
                    .warn("clip.delegation.resolve_unavailable userId=42", new IllegalStateException(hidden));

            assertThatThrownBy(() -> assertNoSecretsIn(captor, List.of(hidden)))
                    .isInstanceOf(AssertionError.class);
        }
    }

    private DelegationResolveClient clientFor(String baseUrl, String internalToken) {
        return new DelegationResolveClient(
                restClientBuilder, new AuthClientProperties(baseUrl),
                new InternalApiProperties(internalToken));
    }

    /** 바늘은 매 실행 무작위다. 짧은 상수는 다른 로그와 우연히 겹쳐 통과할 수 있다. */
    private static String needle(String label) {
        return "LEAK-" + label + "-" + UUID.randomUUID();
    }

    private static void setRootLevel(Level level) {
        setLevel(Logger.ROOT_LOGGER_NAME, level);
    }

    public static void setLevel(String loggerName, Level level) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loggerName)).setLevel(level);
    }

    /** 이 로거에 <b>직접 박힌</b> 레벨. 상속받은 값은 안 센다 — {@code null}이면 안 박힌 것이다. */
    public static Level levelOf(String loggerName) {
        return ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loggerName)).getLevel();
    }

    /**
     * 포맷된 한 줄만 보면 {@code log.warn("...", e)}로 새는 값을 못 잡는다 — 그건
     * ThrowableProxy 안에 있다. 그래서 예외 사슬까지 펼쳐 본다.
     */
    public static void assertNoSecretsIn(LogCaptor captor, List<String> secrets) {
        String haystack = captor.events().stream()
                .map(AuthClientTokenLeakTest::renderFully)
                .collect(Collectors.joining("\n"));
        for (String secret : secrets) {
            assertThat(secret).as("빈 바늘은 어디서나 발견돼 검사를 무력화한다").isNotEmpty();
            assertThat(haystack).as("비밀이 남았다: " + secret).doesNotContain(secret);
        }
    }

    public static String renderFully(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getFormattedMessage());
        appendThrowable(text, event.getThrowableProxy());
        return text.toString();
    }

    private static void appendThrowable(StringBuilder text, IThrowableProxy throwable) {
        if (throwable == null) {
            return;
        }
        text.append('\n').append(throwable.getClassName()).append(": ").append(throwable.getMessage());
        for (StackTraceElementProxy frame : throwable.getStackTraceElementProxyArray()) {
            text.append('\n').append(frame.getSTEAsString());
        }
        for (IThrowableProxy suppressed : throwable.getSuppressed()) {
            appendThrowable(text, suppressed);
        }
        appendThrowable(text, throwable.getCause());
    }
}
