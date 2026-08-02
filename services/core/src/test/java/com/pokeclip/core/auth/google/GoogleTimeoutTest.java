package com.pokeclip.core.auth.google;

import com.pokeclip.core.auth.AuthException;
import com.pokeclip.core.support.FakeHttpServer;
import com.pokeclip.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 지연 서버를 JDK HttpServer로 띄운다(FakeHttpServer, 태스크 5에서 만들었다).
 * MockRestServiceServer는 실제 소켓을 쓰지 않아 지연을 만들 수 없고,
 * bindTo(builder)가 요청 팩토리를 갈아치워 타임아웃 설정 자체를 무력화한다.
 *
 * <p>타임아웃은 자동설정된 RestClient.Builder에 실린다. RestClient.builder()로
 * 맨손으로 만들면 안 실리므로 스프링 컨텍스트에서 받아야 한다.
 *
 * <p>지연 서버를 둘로 나눈 것은 취향이 아니다. FakeHttpServer가 setExecutor를
 * 부르지 않아 핸들러가 디스패처 스레드에서 돈다 — 한 서버에 지연 경로 둘을 걸면
 * 뒤 요청이 앞 요청의 지연까지 기다려 경과 시간 단언이 엉킨다.
 */
class GoogleTimeoutTest extends IntegrationTestSupport {

    private static final Duration RESPONSE_DELAY = Duration.ofSeconds(4);

    /**
     * 이 테스트에만 주는 짧은 값이다. application-test.yml을 건드리면 다른 테스트의
     * 타임아웃까지 같이 바뀐다.
     *
     * <p>값을 <b>일부러 크게</b> 잡았다. 아래 두 테스트의 하한 단언이 이 값에
     * 걸리는데, 스프링 시큐리티가 JWK 조회에 쓰는 자기 기본 타임아웃이 0.6초쯤이라
     * 그보다 확실히 커야 "우리 설정이 지배한다"가 구분된다.
     */
    private static final Duration READ_TIMEOUT = Duration.ofMillis(1500);

    // @DynamicPropertySource가 포트를 읽을 때 이미 떠 있어야 한다.
    private static final FakeHttpServer SLOW_TOKEN = FakeHttpServer.respondingWith(
            "/token", 200, "{\"id_token\":\"too-late\"}", RESPONSE_DELAY);

    private static final FakeHttpServer SLOW_JWKS = FakeHttpServer.respondingWith(
            "/jwks", 200, "{\"keys\":[]}", RESPONSE_DELAY);

    private final GoogleTokenClient googleTokenClient;
    private final GoogleIdTokenVerifier googleIdTokenVerifier;
    private final HttpClientSettings httpClientSettings;

    GoogleTimeoutTest(GoogleTokenClient googleTokenClient,
                      GoogleIdTokenVerifier googleIdTokenVerifier,
                      HttpClientSettings httpClientSettings) {
        this.googleTokenClient = googleTokenClient;
        this.googleIdTokenVerifier = googleIdTokenVerifier;
        this.httpClientSettings = httpClientSettings;
    }

    @AfterAll
    static void stopServers() {
        SLOW_TOKEN.close();
        SLOW_JWKS.close();
    }

    /**
     * read-timeout만 덮는다. connect-timeout은 application.yml의 2초가 그대로 남아
     * 아래 테스트가 프로덕션 값을 본다.
     */
    @DynamicPropertySource
    static void slowGoogleProperties(DynamicPropertyRegistry registry) {
        registry.add("pokeclip.google.token-uri", () -> SLOW_TOKEN.url("/token"));
        registry.add("pokeclip.google.jwk-set-uri", () -> SLOW_JWKS.url("/jwks"));
        registry.add("spring.http.clients.read-timeout", READ_TIMEOUT::toString);
    }

    /**
     * 경과 시간을 위아래로 조인다. 예외 타입만 보면 <b>연결 거부로도 통과한다</b> —
     * 서버를 안 띄우거나 포트가 어긋나도 AuthException이 되므로, "읽기 타임아웃
     * 때문에 끊겼다"가 전혀 증명되지 않는다.
     */
    @Test
    void 구글_토큰_교환이_읽기_타임아웃에_끊긴다() {
        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> googleTokenClient.exchangeCodeForIdToken("any-code"))
                .isInstanceOf(AuthException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                .as("상한은 응답 지연(%s), 하한은 우리 read-timeout(%s)이다", RESPONSE_DELAY, READ_TIMEOUT)
                .isBetween(READ_TIMEOUT, RESPONSE_DELAY);
    }

    /**
     * JWK 조회도 로그인 요청 안에서 일어나는 외부 호출이다. 토큰 교환만 막으면
     * 막은 효과가 절반이라, GoogleAuthConfig가 RestTemplateBuilder로 만든
     * RestOperations를 디코더에 넘긴다.
     *
     * <p><b>하한 단언이 이 테스트의 전부다.</b> 상한만 보면 restOperations(...)를
     * 지워도 초록이다 — 스프링 시큐리티가 만드는 기본 RestTemplate에도 자체
     * 타임아웃이 있어서 0.6초쯤에 알아서 끊긴다. 실제로 확인했다. 그래서 "1초 안에
     * 끊겼다"는 아무것도 증명하지 못한다. 우리가 준 값까지 기다렸다는 것이
     * 증명해야 할 것이고, 그 한 줄이 지워지면 하한에서 걸린다.
     *
     * <p>토큰은 서명 검증까지 갈 필요가 없다. 형식이 맞는 JWS면 디코더가 키를
     * 찾으려고 JWK를 가지러 가고, 거기서 끊기는 것을 본다.
     */
    @Test
    void JWK_조회도_우리가_준_읽기_타임아웃에_끊긴다() {
        String wellFormedJws = "eyJhbGciOiJSUzI1NiIsImtpZCI6Im5vLXN1Y2gta2V5In0"
                + ".eyJzdWIiOiJzdWItMSJ9.c2lnbmF0dXJl";
        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> googleIdTokenVerifier.verify(wellFormedJws))
                .isInstanceOf(AuthException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                .as("%s보다 빨리 끊겼다면 시큐리티 기본 타임아웃이지 우리 설정이 아니다 "
                        + "— GoogleAuthConfig의 restOperations(...)를 확인해라", READ_TIMEOUT)
                .isBetween(READ_TIMEOUT, RESPONSE_DELAY);
    }

    /**
     * 연결 타임아웃은 동작으로 검증하지 않는다 — 실제로 일으키려면 패킷을 흘려버리는
     * 주소가 필요한데 환경마다 다르게 동작해 플래키해진다.
     *
     * <p>그렇다고 application.yml의 값을 읽어 단언하면 순환이다. "설정 파일에 이렇게
     * 적혀 있다"만 확인하게 된다. HttpClientSettings는 HttpClientAutoConfiguration이
     * HttpClientsProperties를 받아 만드는 빈이므로, 이걸 보면 <b>자동설정이 실제로
     * 바인딩했는지</b>가 검증된다. 프로퍼티 이름을 단수형으로 잘못 쓰면 여기서 null이
     * 나온다 — 실제로 그 상태를 먼저 확인하고 이 단언을 통과시켰다.
     *
     * <p>readTimeout도 같이 본다. @DynamicPropertySource로 준 값이 여기까지 흘렀다는
     * 증거이고, 그게 맞아야 위 지연 테스트가 의미를 갖는다.
     */
    @Test
    void 자동설정이_타임아웃_프로퍼티를_실제로_바인딩했다() {
        assertThat(httpClientSettings.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(httpClientSettings.readTimeout()).isEqualTo(READ_TIMEOUT);
    }
}
