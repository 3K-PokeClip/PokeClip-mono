package com.pokeclip.chat.collector;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T7. 채팅 본문·작성자 식별자·닉네임·토큰이 <b>어느 경로에서도</b> 로그에 안 남는지.
 *
 * <p>개별 메시지 로그는 0줄이 원칙이라 정상 경로만 보면 검사가 싱겁다.
 * 새는 자리는 대개 실패 경로다 — 디코딩 실패 로그에 본문을 붙이거나,
 * 수립 실패 로그에 응답 본문을 붙이거나, 예외 메시지에 쿼리의 auth=가 딸려 온다.
 *
 * <p>탐지기는 {@code auth}의 SecretLeakTest를 그대로 베꼈다. 포맷된 한 줄만
 * 보면 {@code log.error("...", e)}로 새는 값을 못 잡는다 — 그건 ThrowableProxy
 * 안에 있다.
 */
@FakeChzzkTest
class ChatLogLeakTest {

    private static final Logger log = LoggerFactory.getLogger(ChatLogLeakTest.class);

    private static final String CONTENT = needle("chat-content");
    private static final String SENDER = needle("sender-channel-id");
    private static final String NICKNAME = needle("nickname");
    private static final String TOKEN = needle("access-token");

    private static final List<String> SECRETS = List.of(CONTENT, SENDER, NICKNAME, TOKEN);

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;

    private CollectorRunner runner;

    @AfterEach
    void tearDown() {
        if (runner != null) runner.stop();
        behavior.reset();
    }

    @Test
    void 정상_수집에서_본문과_작성자와_닉네임이_로그에_안_남는다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            CollectionStatus status = start();
            assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);

            for (int i = 0; i < 20; i++) {
                behavior.emitChat(chatWithNeedles());
            }
            awaitReceived(20);

            // 양성 대조. 한 건도 안 흘렀으면 바늘이 코드 안을 지나간 적이 없어
            // 아래 단언들이 아무것도 검사하지 않은 채 초록이 된다.
            assertThat(runner.metrics().totalReceived())
                    .as("채팅이 코드 안을 지나가지 않았다면 유출 검사는 아무것도 안 본 것이다")
                    .isGreaterThanOrEqualTo(20);

            assertNoSecretsIn(captor, SECRETS);

            // 통째로 찍는 코드가 들어온 것 자체를 잡는다. record 기본 toString이
            // content를 그대로 뱉으므로, 객체를 로그에 넘기는 순간 평문이 나간다.
            assertThat(renderAll(captor))
                    .doesNotContain("ChatMessage[")
                    .doesNotContain("SystemEvent[")
                    .doesNotContain("Established[");
        }
    }

    /** 깨진 JSON이 곧 채팅 본문이다. 실패 로그에 본문을 붙이면 그대로 샌다. */
    @Test
    void 디코딩이_깨져도_본문이_로그에_안_남는다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            start();

            behavior.emitChat("{\"content\":\"" + CONTENT + "\" 깨진다");
            behavior.emitChat(chatWithNeedles());   // 순서 보장용 — 이게 오면 앞은 처리됐다
            awaitReceived(1);

            assertThat(runner.metrics().snapshot().decodeFailures())
                    .as("깨진 본문이 디코더를 지나가지 않았다면 이 검사는 무의미하다")
                    .isGreaterThanOrEqualTo(1);

            assertNoSecretsIn(captor, SECRETS);
        }
    }

    /** 수립 실패 로그·예외 메시지에 응답 본문이나 URL이 붙으면 토큰이 되비친다. */
    @Test
    void 세션_발급이_401이어도_토큰이_로그에_안_남는다() {
        behavior.authStatus = 401;

        try (LogCaptor captor = new LogCaptor()) {
            CollectionStatus status = start();

            assertThat(status.state())
                    .as("실패 경로를 안 태웠다면 검사할 로그가 애초에 없다")
                    .isEqualTo(CollectionStatus.State.STOPPED);
            assertThat(status.reason()).isEqualTo(StopReason.SESSION_AUTH_FAILED);
            assertThat(renderAll(captor)).as("실패가 로그에 남기는 남아야 한다").contains("chat.session.stopped");

            assertNoSecretsIn(captor, SECRETS);
        }
    }

    /** 서버가 조용히 끊는 경로. 예외 메시지에 쿼리의 auth=가 딸려 오기 쉬운 자리다. */
    @Test
    void 전송이_끊겨도_토큰이_로그에_안_남는다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            CollectionStatus status = start();
            assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);

            behavior.closeSession();
            awaitStopped(status);

            assertThat(status.reason()).isEqualTo(StopReason.TRANSPORT_CLOSED);
            assertNoSecretsIn(captor, SECRETS);
        }
    }

    // ── 탐지기 자기검사 셋. 이게 없으면 위 검사 전체가 초록불 장식이다 ────────

    @Test
    void 탐지기는_메시지에_있는_본문을_잡는다() {
        String planted = needle("planted-in-message");

        try (LogCaptor captor = new LogCaptor()) {
            log.info("chat-leak-self-check {}", planted);

            assertThatThrownBy(() -> assertNoSecretsIn(captor, List.of(planted)))
                    .isInstanceOf(AssertionError.class);
        }
    }

    /**
     * 이 자기검사가 가장 중요하다. {@code log.error("...", e)}로 새는 값은
     * getFormattedMessage()에 없고 ThrowableProxy 안에 있다. 탐지기가 메시지만
     * 보면 위 검사 전부가 아무것도 안 보면서 초록이 된다.
     */
    @Test
    void 탐지기는_예외의_cause와_suppressed에_숨은_본문도_잡는다() {
        String inCause = needle("planted-in-cause");
        String inSuppressed = needle("planted-in-suppressed");

        RuntimeException thrown =
                new RuntimeException("겉면은 깨끗하다", new IllegalStateException(inCause));
        thrown.addSuppressed(new IllegalStateException(inSuppressed));

        try (LogCaptor captor = new LogCaptor()) {
            log.error("chat-leak-self-check", thrown);

            assertThat(captor.messages())
                    .as("메시지만 보는 탐지기가 왜 부족한지가 이 단언에 걸려 있다")
                    .noneMatch(m -> m.contains(inCause) || m.contains(inSuppressed));
            assertThatThrownBy(() -> assertNoSecretsIn(captor, List.of(inCause)))
                    .isInstanceOf(AssertionError.class);
            assertThatThrownBy(() -> assertNoSecretsIn(captor, List.of(inSuppressed)))
                    .isInstanceOf(AssertionError.class);
        }
    }

    /**
     * 반대 방향. 항상 걸리는 탐지기는 검사를 통과시키지 못하니 덜 위험하지만,
     * 그때는 유출 검사가 <b>영원히 빨간불</b>이라 사람이 곧 무시하게 된다.
     * 무작위 바늘을 쓰는 이유가 여기 있다 — 고정 문자열이면 로그에 우연히
     * 들어 있는 흔한 낱말과 겹쳐 오탐이 난다.
     */
    @Test
    void 탐지기는_바늘이_없으면_통과시킨다() {
        try (LogCaptor captor = new LogCaptor()) {
            log.info("chat-leak-self-check 깨끗한 줄이다");

            assertNoSecretsIn(captor, List.of(needle("never-logged")));
        }
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private CollectionStatus start() {
        CollectionStatus status = new CollectionStatus();
        runner = new CollectorRunner(
                new ChzzkProperties(true, TOKEN, "http://localhost:" + port, Duration.ofSeconds(5)),
                status);
        runner.start();
        return status;
    }

    private static String chatWithNeedles() {
        return "{\"senderChannelId\":\"" + SENDER + "\""
                + ",\"content\":\"" + CONTENT + "\""
                + ",\"profile\":{\"nickname\":\"" + NICKNAME + "\"}"
                // messageTime이 없으면 디코더가 통째로 버려 바늘이 코드 안을
                // 지나가지 않는다. 그러면 유출 검사가 자동으로 초록이 된다.
                + ",\"messageTime\":1754300000000}";
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

    /**
     * 바늘은 매 실행 무작위다. 짧은 상수는 다른 로그와 우연히 겹쳐
     * "안 샜다"가 아니라 "겹친 것을 못 봤다"로 통과할 수 있다.
     */
    private static String needle(String label) {
        return "LEAK-" + label + "-" + UUID.randomUUID();
    }

    private static void assertNoSecretsIn(LogCaptor captor, List<String> secrets) {
        assertNoSecretsIn(renderAll(captor), secrets);
    }

    private static void assertNoSecretsIn(String haystack, List<String> secrets) {
        for (String secret : secrets) {
            assertThat(secret).as("빈 바늘은 어디서나 발견돼 검사를 무력화한다").isNotEmpty();
            assertThat(haystack).as("비밀이 남았다: " + secret).doesNotContain(secret);
        }
    }

    /** 모인 로그 전부를 한 덩어리로. 딸려 붙은 예외까지 포함한다. */
    private static String renderAll(LogCaptor captor) {
        return captor.events().stream()
                .map(ChatLogLeakTest::renderFully)
                .collect(Collectors.joining("\n"));
    }

    private static String renderFully(ILoggingEvent event) {
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
