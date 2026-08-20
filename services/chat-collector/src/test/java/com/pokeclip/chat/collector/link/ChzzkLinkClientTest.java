package com.pokeclip.chat.collector.link;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import com.pokeclip.web.support.LogCaptor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * auth의 {@code POST /internal/chzzk-link/resolve}를 부르는 쪽. 계약 정본은 auth의
 * {@code ChzzkLinkResolveController}와 {@code services/README.md}「치지직 채널 연동」이다.
 *
 * <p><b>가짜 auth는 진짜로 듣는 소켓이어야 한다.</b> MockRestServiceServer는 요청 팩토리를
 * 갈아치우므로, 이 파일의 표적인 「주입받은 빌더를 쓰는가」를 통째로 무력화한다
 * (auth {@code FakeHttpServer} 주석과 같은 이유). 지연도 만들 수 없다.
 *
 * <p><b>유출 탐지기를 {@code ChatLogLeakTest}에서 가져오지 않았다.</b> 그 클래스는
 * {@code IntegrationTestSupport}를 상속하고, static 메서드 하나만 불러도 상위 클래스
 * 초기화가 먼저 돌아 <b>Testcontainers Postgres가 뜬다</b>. 이 검사는 DB가 필요 없다.
 * 아래 {@code assertNoSecretsIn}은 그것의 축소판이다 — 한쪽을 고치면 다른 쪽도 본다.
 */
class ChzzkLinkClientTest {

    private static final String INTERNAL_TOKEN = "internal-token-for-test";

    private static final String VALID_BODY = """
            {"valid":true,"channelId":"c1","accessToken":"tok","expiresAt":"2026-08-19T10:00:00Z"}""";

    /** 아무도 안 듣는 포트. 연결 자체가 거부된다 — 5xx와 다른 갈래다. */
    private static final String UNREACHABLE = "http://127.0.0.1:1";

    /**
     * 가짜 auth가 붙들고 있는 시간. 아래 READ_TIMEOUT보다 훨씬 길다.
     *
     * <p><b>6초에서 20초로 올렸다</b>(POK-127 최종 감사). 아래 임계가 {@code READ_TIMEOUT×3}인데
     * 시한만 1→3초로 늘리면 임계 9초가 옛 지연 6초를 넘어서 <b>판별력이 통째로 사라진다</b> —
     * 시한을 안 거는 구현도 6초에 끝나므로 초록이다. 둘을 같이 벌린다.
     */
    private static final Duration AUTH_DELAY = Duration.ofSeconds(20);

    /**
     * 이 값이 실제로 걸리는지가 시한 검사의 표적이다. 운영값은 application.yml에 있다.
     *
     * <p><b>1초에서 3초로 올렸다 — 간헐 실패의 원인이었다.</b> 전수 실행에서 다른 스프링
     * 컨텍스트들이 같이 돌 때 <b>요청이 서버에 닿기도 전에</b> 1초가 지나가, 가짜 auth의
     * 핸들러가 한 번도 안 불린 채({@code callCount()==0}) 검사가 끝났다. 재현률은 전수
     * <b>3회 중 1회</b>이고 그 클래스 단독 3회·CPU 스피너 12개에서는 0회다 —
     * <b>부하가 아니라 「같이 도는 컨텍스트 수」가 조건이다.</b>
     *
     * <p>그때 빨간불을 낸 것은 아래 {@code callCount()} 양성 대조다. <b>그 줄이 없었으면
     * 조용히 초록이었다</b> — {@code sinceFirstRequest()}가 {@code Duration.ZERO}를 주고
     * 나머지 단언 둘이 그대로 통과한다. 검사가 「나는 아무것도 못 쟀다」를 정직하게 알린 것이라
     * <b>그 대조를 지우지 마라.</b>
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private FakeAuth auth;

    @BeforeEach
    void startFakeAuth() {
        auth = FakeAuth.start();
    }

    @AfterEach
    void stopFakeAuth() {
        auth.close();
    }

    @Test
    void 연동이_되어_있으면_토큰을_준다() {
        givenAuthResponds(200, VALID_BODY);

        LinkResolution r = client().resolve(42L);

        assertThat(r.usable()).isTrue();
        assertThat(r.accessToken()).isEqualTo("tok");
        assertThat(r.channelId()).isEqualTo("c1");
        assertThat(r.expiresAt()).isEqualTo(Instant.parse("2026-08-19T10:00:00Z"));
        // 문항 4 — 늘 재시도 대상이라고 답하는 구현을 성공 쪽에서도 막는다.
        // 열쇠를 받았는데 편지를 남기면 같은 방송을 또 열려 든다.
        assertThat(r.retryable()).isFalse();
    }

    /**
     * 문항 2 — 위 검사는 <b>auth를 아예 안 부르고 상수를 돌려주는 구현에도 초록</b>이다.
     * 요청이 실제로 나갔는지, 계약대로 생겼는지는 상대 쪽에서 센다.
     *
     * <p>헤더를 빠뜨리면 운영에서 <b>전부 401</b>이 되고, 401은 다섯째 갈래(재시도)로
     * 분류되므로 편지가 큐에서 영원히 돈다 — 서버는 UP이고 로그만 쌓인다.
     */
    @Test
    void 요청에_내부_토큰과_회원_번호가_실린다() {
        givenAuthResponds(200, VALID_BODY);

        client().resolve(42L);

        // <b>값을 실어 둔다.</b> 전수 실행에서 5회 중 1회 빨간불이 났는데 단언이 숫자를
        // 안 남겨 「0(요청이 서버에 닿기 전에 끝났다)」인지 「2 이상(재시도)」인지 구분이
        // 안 됐다. 둘은 처방이 완전히 다르다.
        assertThat(auth.callCount())
                .as("요청이 실제로 나갔는가 — 0이면 서버에 닿기 전에 끝난 것, 2 이상이면 재시도")
                .isEqualTo(1);
        assertThat(auth.lastPath()).isEqualTo("/internal/chzzk-link/resolve");
        assertThat(auth.lastToken()).isEqualTo(INTERNAL_TOKEN);
        assertThat(auth.lastBody()).contains("\"userId\":42");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_LINKED", "UNLINKED", "BROKEN"})
    void 다시_물어도_안_바뀌는_거절은_재시도_대상이_아니다(String reason) {
        givenAuthResponds(200, "{\"valid\":false,\"reason\":\"" + reason + "\"}");

        LinkResolution r = client().resolve(42L);

        assertThat(r.usable()).isFalse();
        assertThat(r.retryable()).isFalse();
    }

    /**
     * 🔴 <b>허락인데 필수 칸이 빠지면 재시도로 안 낫는다.</b>
     *
     * <p>{@code valid:true}만 보고 칸을 {@code asString("")}로 읽으면 <b>빈 토큰으로
     * 치지직에 붙으러 간다.</b> 수립이 실패하고 {@code LinkedSessionStarter}가
     * {@code RETRY_LATER}를 돌려주는데, auth는 매번 같은 응답을 주므로
     * <b>그 FIFO 그룹이 영원히 막힌다.</b>
     *
     * <p>이것은 「auth가 잠깐 아프다」가 아니라 <b>계약 위반</b>이다. 시간이 지나도 안
     * 낫는 것을 재시도 대상에 두면 큐가 막히고, 막힌 이유는 어디에도 안 남는다.
     *
     * <p>문항 2: 셋 중 하나만 재면 나머지 둘이 뚫려 있어도 통과한다 — 그래서 셋을 다 돈다.
     * <p>문항 4: 「늘 영구 거부」인 구현도 이 단언을 통과한다 — 아래 대조군이 그것을 막는다
     * (온전한 응답은 지금도 쓸 수 있다고 나온다).
     */
    @ParameterizedTest(name = "{0}이 비었다")
    @ValueSource(strings = {"channelId", "accessToken", "expiresAt"})
    void 허락인데_필수_칸이_비면_재시도_대상이_아니다(String missing) {
        givenAuthResponds(200, grantedJsonWithout(missing));

        LinkResolution r = client().resolve(42L);

        assertThat(r.usable()).as("빈 칸으로 붙으러 가면 안 된다").isFalse();
        assertThat(r.retryable()).as("재시도로 안 낫는 계약 위반이다").isFalse();
    }

    /** 칸은 있는데 만료 시각의 <b>형식</b>이 틀린 경우도 같은 갈래다. */
    @Test
    void 허락인데_만료_시각_형식이_틀리면_재시도_대상이_아니다() {
        givenAuthResponds(200,
                "{\"valid\":true,\"channelId\":\"c1\",\"accessToken\":\"tok\","
                + "\"expiresAt\":\"2026년 8월 19일\"}");

        LinkResolution r = client().resolve(42L);

        assertThat(r.usable()).isFalse();
        assertThat(r.retryable()).isFalse();
    }

    /** 셋 중 하나를 뺀 허락 응답. 뺀 칸만 없고 나머지는 온전하다. */
    private static String grantedJsonWithout(String missing) {
        String channelId = "channelId".equals(missing) ? "" : "c1";
        String accessToken = "accessToken".equals(missing) ? "" : "tok";
        String expiresAt = "expiresAt".equals(missing) ? "" : "2026-08-19T10:00:00Z";
        return """
                {"valid":true,"channelId":"%s","accessToken":"%s","expiresAt":"%s"}"""
                .formatted(channelId, accessToken, expiresAt);
    }

    @Test
    void 일시적_갱신_실패는_재시도_대상이다() {
        givenAuthResponds(200, "{\"valid\":false,\"reason\":\"REFRESH_UNAVAILABLE\"}");

        assertThat(client().resolve(42L).retryable()).isTrue();
    }

    /**
     * auth가 사유를 하나 더 만들면 우리는 그 이름을 모른다. 「모르니 지운다」로 떨어지면
     * 그 방송의 채팅이 통째로 사라지고, 「모르니 남긴다」면 편지가 큐에 남아 눈에 띈다.
     * 다섯째 갈래와 같은 이유로 남기는 쪽이다.
     */
    @Test
    void 모르는_사유가_와도_재시도_대상이다() {
        givenAuthResponds(200, "{\"valid\":false,\"reason\":\"SUSPENDED\"}");

        LinkResolution r = client().resolve(42L);

        assertThat(r.usable()).isFalse();
        assertThat(r.retryable()).isTrue();
    }

    /**
     * <b>다섯째 갈래다.</b> 거절 4종은 auth가 살아서 판단을 준 경우다. 못 닿으면 사유가
     * 아예 안 온다 — 여기서 「지운다」로 떨어지면 auth 재배포 몇 초 사이에 온 시작 편지가
     * 사라지고, 그 스트리머는 방송 내내 채팅이 안 걷힌다.
     *
     * <p>양성 대조는 같은 파일의 {@code 다시_물어도_안_바뀌는_거절은_재시도_대상이_아니다}
     * 셋이다 — 늘 true를 돌려주는 구현은 그쪽에서 빨간불이 난다(문항 5).
     */
    @Test
    void auth가_죽어_있으면_재시도_대상이다() {
        givenAuthResponds(503, "");

        LinkResolution r = client().resolve(42L);

        assertThat(r.usable()).isFalse();
        assertThat(r.retryable()).isTrue();
    }

    @Test
    void auth에_아예_못_닿아도_재시도_대상이다() {
        LinkResolution r = clientFor(UNREACHABLE, INTERNAL_TOKEN).resolve(42L);

        assertThat(r.usable()).isFalse();
        assertThat(r.retryable()).isTrue();
    }

    /**
     * 문항 3 — 「주입받은 {@code RestClient.Builder}를 쓴다」를 값이 아니라 <b>행동</b>으로 잰다.
     * {@code RestClient.create()}로 되돌리면 시한이 어디에도 안 걸려 6초를 다 기다린다.
     *
     * <p>이 서버가 이미 한 번 데인 자리다({@code CLAUDE.md} — 설정 파일은 완벽한데 타임아웃이
     * 어디에도 안 걸렸고 검토 일곱 바퀴가 못 잡았다). 편지 처리 스레드가 auth 하나 때문에
     * 무기한 매달리면 그동안 <b>다른 방송의 시작 편지도 안 처리된다.</b>
     *
     * <p>시간은 <b>가짜 서버가 요청을 받은 시각</b>부터 잰다 — 클라이언트 조립 시간이 섞이면
     * 무엇을 쟀는지 흐려진다(POK-84 선례: 6.375초 → 0.696초).
     */
    @Test
    void auth가_답을_안_주면_주입받은_빌더의_시한에서_끊는다() {
        givenAuthResponds(200, VALID_BODY);
        auth.holdFor(AUTH_DELAY);
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(READ_TIMEOUT);

        LinkResolution r = new ChzzkLinkClient(
                RestClient.builder().requestFactory(factory),
                new LinkProperties(auth.baseUrl(), INTERNAL_TOKEN)).resolve(42L);

        Duration heldFor = auth.sinceFirstRequest();
        // 양성 대조. 요청이 아예 안 나갔다면 위 시간은 아무것도 안 잰 것이다.
        assertThat(auth.callCount())
                .as("요청을 안 보냈으면 시한을 잰 것이 아니다")
                .isEqualTo(1);
        assertThat(heldFor)
                .as("가짜 auth가 붙들고 있는 " + AUTH_DELAY.toSeconds()
                        + "초를 다 기다렸다면 주입받은 빌더를 안 쓴 것이다")
                .isLessThan(READ_TIMEOUT.multipliedBy(3));
        assertThat(r.retryable())
                .as("시한 초과는 다시 걸면 풀릴 수 있다")
                .isTrue();
    }

    /**
     * 문항 2가 가장 위험한 검사다 — <b>아무 로그도 안 찍는 구현에 자동으로 초록</b>이다.
     * 그래서 로그가 실제로 나가는 갈래 넷(성공·거절·5xx·못 닿음)을 다 태우고,
     * 그 줄들이 실제로 찍혔는지를 먼저 단언한 뒤 유출을 본다.
     *
     * <p>바늘 셋은 성격이 다르다 — 스트리머 토큰(auth가 준 것) · 채널 ID(누구인지) ·
     * 내부 토큰(우리가 보내는 것). 세 번째는 요청 헤더로만 나가므로,
     * 나가는 요청을 통째로 찍는 구현에서만 샌다.
     */
    @Test
    void 토큰이_로그에_안_남는다() {
        String accessToken = needle("access-token");
        String channelId = needle("channel-id");
        String internalToken = needle("internal-token");

        try (LogCaptor captor = new LogCaptor()) {
            // DEBUG·TRACE로 새는 구현은 INFO 캡터에서 조용히 초록이 된다.
            // LogCaptor가 close에서 원래 레벨로 되돌린다.
            setRootLevel(Level.TRACE);

            givenAuthResponds(200, """
                    {"valid":true,"channelId":"%s","accessToken":"%s","expiresAt":"2026-08-19T10:00:00Z"}"""
                    .formatted(channelId, accessToken));
            LinkResolution granted = clientFor(auth.baseUrl(), internalToken).resolve(42L);
            assertThat(granted.accessToken())
                    .as("토큰을 못 받아 왔으면 샐 것도 없다")
                    .isEqualTo(accessToken);

            givenAuthResponds(200, "{\"valid\":false,\"reason\":\"BROKEN\"}");
            clientFor(auth.baseUrl(), internalToken).resolve(43L);

            givenAuthResponds(503, "");
            clientFor(auth.baseUrl(), internalToken).resolve(44L);

            clientFor(UNREACHABLE, internalToken).resolve(45L);

            // 양성 대조 — 로그가 실제로 나갔다. 이 셋이 없으면 아래 「안 샜다」는 공허하다.
            assertThat(captor.messages())
                    .as("성공 갈래가 아무것도 안 찍으면 유출 검사가 재는 것이 없다")
                    .anyMatch(m -> m.startsWith("chat.link.resolved"));
            assertThat(captor.messages())
                    .as("거절 사유가 안 남으면 왜 안 걷혔는지를 나중에 알 수 없다")
                    .anyMatch(m -> m.startsWith("chat.link.rejected"));
            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("chat.link.unavailable"))
                    .as("5xx와 못 닿음 둘 다 남아야 한다")
                    .hasSize(2);

            assertNoSecretsIn(captor, List.of(accessToken, channelId, internalToken));
        }
    }

    /**
     * 탐지기 자기검사. 위 검사의 {@code doesNotContain}은 <b>아무것도 안 담긴 haystack에
     * 자동으로 통과</b>하고, 특히 {@code log.warn("...", e)}로 새는 값은 포맷된 한 줄에
     * 안 보인다 — ThrowableProxy 안에 있다. 그 갈래에서 실제로 빨간불이 되는지 본다.
     */
    @Test
    void 탐지기는_예외_안에_숨은_바늘도_잡는다() {
        String hidden = needle("in-exception");

        try (LogCaptor captor = new LogCaptor()) {
            LoggerFactory.getLogger(ChzzkLinkClientTest.class)
                    .warn("chat.link.unavailable userId=42", new IllegalStateException(hidden));

            assertThatThrownBy(() -> assertNoSecretsIn(captor, List.of(hidden)))
                    .isInstanceOf(AssertionError.class);
        }
    }

    /**
     * 기본값을 안 주면 리터럴 {@code "${INTERNAL_API_TOKEN}"}이 바인딩돼 <b>서버는 뜨고
     * 토큰 조회만 전부 401</b>이 된다({@code services/CLAUDE.md}의 {@code ${VAR:}} + 검증 규칙).
     *
     * <p>「부팅이 실패한다」를 실제로 만드는 것은 마지막 줄이다 — 클라이언트를 만들 때
     * 검증이 돌므로, 이 클라이언트를 빈으로 올리는 순간(태스크 10) 부팅이 죽는다.
     */
    @Test
    void 내부_토큰이_비면_부팅이_실패한다() {
        assertThatThrownBy(() -> new LinkProperties("http://auth", "").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pokeclip.link.internal-token");

        assertThatThrownBy(() -> new LinkProperties("", "tok").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pokeclip.link.auth-base-url");

        assertThatThrownBy(() -> clientFor("http://auth", ""))
                .as("클라이언트가 만들어질 때 검증이 돌아야 부팅에서 죽는다")
                .isInstanceOf(IllegalStateException.class);
    }

    private void givenAuthResponds(int status, String body) {
        auth.respondWith(status, body);
    }

    private ChzzkLinkClient client() {
        return clientFor(auth.baseUrl(), INTERNAL_TOKEN);
    }

    /**
     * <b>요청 팩토리를 JDK로 못박는다 — 운영과 같은 스택이다</b>
     * ({@code spring.http.clients.imperative.factory=jdk}, {@code CollectorConfigTest}가 단언).
     *
     * <p>아무것도 안 고르면 Apache 5가 잡힌다 — AWS SDK가 httpclient5를 클래스패스에 올려
     * 두기 때문이다. 그 스택의 wire 로거는 TRACE에서 <b>요청 헤더와 응답 본문을 통째로</b>
     * 찍는다(2026-08-19 이 검사가 실제로 빨간불로 잡았다: 내부 토큰과 스트리머 토큰이 둘 다
     * 로그에 그대로 나왔다). 운영은 그 길을 둘로 막는다 — 팩토리가 JDK이고,
     * {@code application.yml}이 {@code org.apache.hc.client5.http}를 INFO로 박아 뒀다
     * ({@code ArchiveLogLeakTest}가 그 줄을 잰다). 이 검사가 재려는 것은 <b>우리 코드</b>다.
     */
    private ChzzkLinkClient clientFor(String baseUrl, String internalToken) {
        return new ChzzkLinkClient(
                RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()),
                new LinkProperties(baseUrl, internalToken));
    }

    /** 바늘은 매 실행 무작위다. 짧은 상수는 다른 로그와 우연히 겹쳐 통과할 수 있다. */
    private static String needle(String label) {
        return "LEAK-" + label + "-" + UUID.randomUUID();
    }

    private static void setRootLevel(Level level) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(level);
    }

    /**
     * 포맷된 한 줄만 보면 {@code log.warn("...", e)}로 새는 값을 못 잡는다 — 그건
     * ThrowableProxy 안에 있고, {@code RestClientResponseException}의 메시지에는
     * <b>응답 본문이 딸려 온다.</b> 그래서 예외 사슬까지 펼쳐 본다.
     */
    private static void assertNoSecretsIn(LogCaptor captor, List<String> secrets) {
        String haystack = captor.events().stream()
                .map(ChzzkLinkClientTest::renderFully)
                .collect(Collectors.joining("\n"));
        for (String secret : secrets) {
            assertThat(secret).as("빈 바늘은 어디서나 발견돼 검사를 무력화한다").isNotEmpty();
            assertThat(haystack).as("비밀이 남았다: " + secret).doesNotContain(secret);
        }
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

    /**
     * 가짜 auth. 응답과 지연을 갈아 끼우고, 받은 요청을 센다.
     *
     * <p>전용 실행기를 준다 — 기본값(null)은 디스패처 스레드에서 핸들러를 돌리므로
     * 지연을 걸면 서버 전체가 멈추고, 그 스레드는 데몬이 아니라 JVM 종료도 늦춘다.
     */
    private static final class FakeAuth implements AutoCloseable {

        private final HttpServer server;
        private final ExecutorService threads;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<String> lastToken = new AtomicReference<>();
        private final AtomicReference<String> lastBody = new AtomicReference<>();
        private final AtomicReference<String> lastPath = new AtomicReference<>();
        private final AtomicReference<Instant> firstRequestAt = new AtomicReference<>();

        private volatile int status = 200;
        private volatile String body = "";
        private volatile Duration delay = Duration.ZERO;

        private FakeAuth(HttpServer server, ExecutorService threads) {
            this.server = server;
            this.threads = threads;
        }

        static FakeAuth start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
                ExecutorService threads = Executors.newCachedThreadPool(runnable -> {
                    Thread thread = new Thread(runnable, "fake-auth");
                    thread.setDaemon(true);
                    return thread;
                });
                server.setExecutor(threads);
                FakeAuth fake = new FakeAuth(server, threads);
                server.createContext("/", fake::handle);
                server.start();
                return fake;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        void respondWith(int status, String body) {
            this.status = status;
            this.body = body;
        }

        void holdFor(Duration delay) {
            this.delay = delay;
        }

        String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        int callCount() {
            return calls.get();
        }

        String lastToken() {
            return lastToken.get();
        }

        String lastBody() {
            return lastBody.get();
        }

        String lastPath() {
            return lastPath.get();
        }

        /** 첫 요청이 <b>서버에 도착한 시각</b>부터 잰다. 클라이언트 조립 시간이 안 섞인다. */
        Duration sinceFirstRequest() {
            Instant at = firstRequestAt.get();
            return at == null ? Duration.ZERO : Duration.between(at, Instant.now());
        }

        private void handle(HttpExchange exchange) throws IOException {
            calls.incrementAndGet();
            firstRequestAt.compareAndSet(null, Instant.now());
            lastPath.set(exchange.getRequestURI().getPath());
            lastToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (!delay.isZero()) {
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            // 0은 "길이를 모른다"는 뜻이라 청크 응답이 된다. 빈 본문은 -1이다.
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
            threads.shutdownNow();
        }
    }
}
