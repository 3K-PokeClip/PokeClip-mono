package com.pokeclip.core.auth;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import com.jayway.jsonpath.JsonPath;
import com.pokeclip.core.auth.api.dto.GoogleLoginRequest;
import com.pokeclip.core.auth.api.dto.RefreshRequest;
import com.pokeclip.core.auth.token.RefreshTokenRepository;
import com.pokeclip.core.auth.token.TokenPair;
import com.pokeclip.core.auth.token.TokenService;
import com.pokeclip.core.auth.user.UserRepository;
import com.pokeclip.core.auth.user.UserService;
import com.pokeclip.core.support.FakeHttpServer;
import com.pokeclip.core.support.IntegrationTestSupport;
import com.pokeclip.core.support.LogCaptor;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import java.lang.annotation.Annotation;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 토큰·구글 code·client_secret·JWT 서명키는 어떤 레벨에서도 로그에 남지 않아야 한다.
 *
 * <p>성공 경로만 보면 놓친다 — 값을 찍는 사고는 실패 경로에서 훨씬 흔하다.
 * 그래서 <b>HTTP 계층으로</b> 부른다. 서비스를 직접 부르면 AuthExceptionHandler가
 * 실행되지 않아, 핸들러가 원인 예외 본문을 통째로 찍게 바뀌어도 이 테스트가 통과한다.
 *
 * <p>구글 토큰 교환도 목을 쓰지 않는다. 진짜 GoogleTokenClient가 client_secret을
 * 폼에 실어 아래 가짜 서버로 보내고, 서버는 400을 돌려 실패 경로를 만든다.
 * 목을 쓰면 client_secret이 로그로 갈 길 자체가 없어 검사가 무의미해진다.
 *
 * <p><b>검사 기준선은 INFO다</b>(LogCaptor의 기본값). DEBUG로 내려 봤고, 그때
 * 새는 것은 우리 코드가 아니라 프레임워크였다 — 스프링이 역직렬화한 요청 DTO와
 * 바깥으로 나가는 폼 본문을, 하이버네이트가 엔티티 toString을 통째로 찍는다.
 * 우리가 고칠 수 있는 것이 아니라 켜지 말아야 할 스위치다. 그래서 기준선은
 * 운영이 실제로 도는 INFO로 두고, DEBUG 쪽은
 * {@link #스프링_web을_DEBUG로_켜면_새므로_설정에서_켜지_않는다()}가 재현과 함께
 * 못박는다. TRACE는 더 볼 것이 없다 — 거기서 늘어나는 것은 바인딩 값인데
 * 네 바늘 중 SQL에 닿는 것이 없다(refresh 토큰은 SHA-256 해시로만 저장된다).
 */
@AutoConfigureMockMvc
class SecretLeakTest extends IntegrationTestSupport {

    private static final Logger log = LoggerFactory.getLogger(SecretLeakTest.class);

    private static final String SPRING_WEB_LOGGER = "org.springframework.web";

    private static final String GOOGLE_CODE = needle("google-code");

    /** application-test.yml 대신 아래 @DynamicPropertySource로 주입한다. */
    private static final String CLIENT_SECRET = needle("google-client-secret");

    /** 60자를 넘어 HS256의 32바이트 하한(JwtConfig.MIN_SECRET_BYTES)을 만족한다. */
    private static final String JWT_SECRET = needle("jwt-signing-secret");

    private static final List<String> SECRETS = List.of(GOOGLE_CODE, CLIENT_SECRET, JWT_SECRET);

    /**
     * 400 본문에 code와 client_secret을 일부러 싣는다. RestClientResponseException의
     * 메시지에는 응답 본문이 들어가므로, 핸들러가 원인 메시지나 스택트레이스를 찍도록
     * 바뀌면 이 바늘이 로그에 나타난다. 본문이 깨끗하면 그런 변경도 이 테스트를
     * 통과한다 — 찾을 것이 애초에 없기 때문이다.
     *
     * <p>@DynamicPropertySource가 포트를 읽을 때 이미 떠 있어야 해서 static 초기화다.
     */
    private static final FakeHttpServer GOOGLE = FakeHttpServer.respondingWith(
            "/token", 400,
            "{\"error\":\"invalid_grant\",\"request\":\"code=" + GOOGLE_CODE
                    + "&client_secret=" + CLIENT_SECRET + "\"}",
            Duration.ZERO);

    private final MockMvc mockMvc;
    private final TokenService tokenService;
    private final UserService userService;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;

    SecretLeakTest(MockMvc mockMvc, TokenService tokenService, UserService userService,
                   UserRepository userRepository,
                   RefreshTokenRepository refreshTokenRepository,
                   JdbcTemplate jdbcTemplate, Environment environment) {
        this.mockMvc = mockMvc;
        this.tokenService = tokenService;
        this.userService = userService;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
    }

    /**
     * 시크릿을 yml에서 읽어 상수로 복사하지 않는다. 복사하면 yml이 바뀌는 순간
     * 상수와 어긋나고 — 아무도 못 알아채는 채로 — 존재하지 않는 값을 찾게 돼
     * 검사가 조용히 무의미해진다. 여기서 넣은 값이 곧 찾을 값이다.
     */
    @DynamicPropertySource
    static void fakeGoogleAndSecrets(DynamicPropertyRegistry registry) {
        registry.add("pokeclip.google.token-uri", () -> GOOGLE.url("/token"));
        registry.add("pokeclip.google.client-secret", () -> CLIENT_SECRET);
        registry.add("pokeclip.jwt.secret", () -> JWT_SECRET);
    }

    @AfterAll
    static void stopFakeGoogle() {
        GOOGLE.close();
    }

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    /** core/CLAUDE.md의 FK 함정. 자식 행을 남기면 다른 테스트의 부모 정리를 막는다. */
    @AfterEach
    void tearDown() {
        refreshTokenRepository.deleteAll();
    }

    /**
     * 실패 경로를 HTTP로 태운다. 구글 토큰 교환이 400으로 깨지므로
     * GoogleTokenClient → AuthException(GOOGLE_TOKEN_EXCHANGE_FAILED) →
     * AuthExceptionHandler 순으로 실제로 흐른다.
     */
    @Test
    void 구글_토큰_교환_실패에_code와_client_secret이_남지_않는다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            String body = mockMvc.perform(post("/api/auth/google")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"" + GOOGLE_CODE + "\"}"))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            assertThat(captor.messages())
                    .as("실패 경로가 아예 안 돌았다. 그러면 아무것도 검사하지 않은 것이다")
                    .anyMatch(m -> m.startsWith("auth.failed reason=GOOGLE_TOKEN_EXCHANGE_FAILED"));
            assertNoSecretsIn(captor, SECRETS);
            assertNoSecretsIn(body, SECRETS);
        }
    }

    /**
     * 토큰을 손에 쥔 채 실패하는 경로들. 지금은 이 경로에 로그가 없지만, "누가
     * 잘못된 토큰을 던지는지 보자"는 요구는 반드시 나온다 — 그때 원문을 함께
     * 찍는 변경이 여기서 걸린다.
     */
    @Test
    void 잘못된_토큰을_보내도_토큰_원문이_로그에_남지_않는다() throws Exception {
        TokenPair issued = issueFor(needle("google-sub"));
        String garbageBearer = needle("bearer-garbage");
        // 형식이 맞아 Nimbus가 끝까지 파싱한 뒤 서명에서 떨어진다.
        // 파싱 전에 튕기는 쓰레기 문자열과는 다른 코드 경로다.
        String tamperedBearer = tamperSignature(issued.accessToken());
        String unknownRefresh = needle("unknown-refresh");

        try (LogCaptor captor = new LogCaptor()) {
            mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + garbageBearer))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tamperedBearer))
                    .andExpect(status().isUnauthorized());
            refresh(unknownRefresh, status().isUnauthorized());

            assertThat(captor.messages())
                    .as("실패 경로가 아예 안 돌았다")
                    .anyMatch(m -> m.startsWith("auth.failed reason=REFRESH_TOKEN_UNKNOWN"));
            assertNoSecretsIn(captor, List.of(
                    garbageBearer, tamperedBearer, unknownRefresh, issued.accessToken(), JWT_SECRET));
        }
    }

    /**
     * 토큰이 오가는 성공 경로 전부. 여기는 실제 구글이 필요 없으므로 직접 발급해
     * 만든다. 회전·재사용감지·로그아웃·me가 전부 HTTP를 지난다.
     */
    @Test
    void 토큰_경로를_돌려도_토큰_원문이_로그에_남지_않는다() throws Exception {
        TokenPair issued = issueFor(needle("google-sub"));

        try (LogCaptor captor = new LogCaptor()) {
            mockMvc.perform(get("/api/auth/me")
                            .header("Authorization", "Bearer " + issued.accessToken()))
                    .andExpect(status().isOk());

            String rotated = refresh(issued.refreshToken(), status().isOk());
            assertThat(rotated).as("회전이 안 됐다. 빈 바늘로는 아무것도 못 찾는다").isNotBlank();

            // 유예 창(10초)을 넘겨 진짜 재사용 감지 경로로 보낸다.
            jdbcTemplate.update("""
                    UPDATE refresh_tokens SET revoked_at = revoked_at - INTERVAL '1 hour'
                    WHERE revoked_at IS NOT NULL
                    """);
            refresh(issued.refreshToken(), status().isUnauthorized());

            mockMvc.perform(post("/api/auth/logout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"" + rotated + "\"}"))
                    .andExpect(status().isNoContent());

            assertThat(captor.messages())
                    .as("재사용 감지가 안 돌았다. 회전만 보고 끝낸 것이다")
                    .anyMatch(m -> m.startsWith("auth.token.reuse_detected"));
            assertNoSecretsIn(captor,
                    List.of(issued.accessToken(), issued.refreshToken(), rotated, JWT_SECRET));

            // TokenPair·GoogleUser는 record라 toString()이 전 필드를 찍는다.
            // 이번에 흐른 값이 우연히 안 걸리더라도, 통째로 찍는 코드가 들어온
            // 것 자체를 잡는다.
            assertThat(String.join("\n", captor.messages()))
                    .doesNotContain("TokenPair[")
                    .doesNotContain("GoogleUser[");
        }
    }

    /**
     * 본문이 컨트롤러에 닿기 전에 깨지는 경로. 여기서 예외 메시지를 찍기 시작하면
     * Jackson의 파싱 오류가 물고 온 입력 조각이 그대로 로그로 나간다.
     *
     * <p>이 400들이 auth.failed로 찍히지 않는 것도 함께 못박는다. /api/auth/refresh는
     * permitAll이라 외부인이 쓰레기 본문을 반복해 보낼 수 있다 — 그것이 인증 실패
     * WARN으로 집계되면 알람을 채우는 데 인증이 필요 없어진다.
     */
    @Test
    void 깨진_JSON과_본문_검증_실패에도_원문이_남지_않는다() throws Exception {
        String broken = needle("broken-json-code");

        try (LogCaptor captor = new LogCaptor()) {
            // 닫히지 않은 JSON.
            String parseFailure = mockMvc.perform(post("/api/auth/google")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"" + broken + "\""))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            // @NotBlank 위반. 거부된 값이 빈 문자열이라 지금은 찍혀도 새지 않는다 —
            // 그 상태를 시크릿을_받는_DTO에는_NotBlank_말고는_걸지_않는다()가 못박는다.
            String validationFailure = mockMvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            assertThat(captor.messages())
                    .as("400을 인증 실패로 집계하면 미인증 트래픽이 알람을 만든다")
                    .noneMatch(m -> m.startsWith("auth.failed"));
            assertNoSecretsIn(captor, List.of(broken));
            assertNoSecretsIn(parseFailure, List.of(broken));
            assertNoSecretsIn(validationFailure, List.of(broken));
        }
    }

    /**
     * core/CLAUDE.md의 함정: 시크릿 필드에 @Size·@Pattern을 걸면 바인딩 실패
     * 리포트가 "rejected value [원문]"으로 값을 평문으로 찍는다. @NotBlank는
     * 거부되는 값이 빈 문자열·null뿐이라 안전하다. 지금 상태를 못박아, 나중에
     * 제약을 더하는 변경이 여기서 걸리게 한다.
     */
    @Test
    void 시크릿을_받는_DTO에는_NotBlank_말고는_걸지_않는다() throws Exception {
        assertThat(constraintsOn(GoogleLoginRequest.class, "code")).containsExactly(NotBlank.class);
        assertThat(constraintsOn(RefreshRequest.class, "refreshToken")).containsExactly(NotBlank.class);
    }

    /**
     * MockMvc에는 컨테이너의 ERROR 디스패치가 없어 /error 본문을 여기서 직접 볼 수
     * 없다(실제 톰캣은 ErrorDispatchTest가 띄운다). /error는 permitAll이라 미인증
     * 호출자가 그 본문을 읽는다 — include-message가 켜지면 예외 메시지가,
     * include-stacktrace가 켜지면 원인 체인 전체가, include-binding-errors가 켜지면
     * 거부된 값이 응답으로 나간다. 세 스위치가 꺼진 상태를 못박는다.
     */
    @Test
    void 오류_응답은_예외_메시지도_스택트레이스도_거부된_값도_담지_않는다() {
        assertThat(errorAttribute("include-message")).isEqualTo("never");
        assertThat(errorAttribute("include-stacktrace")).isEqualTo("never");
        assertThat(errorAttribute("include-binding-errors")).isEqualTo("never");
    }

    /**
     * 왜 검사 기준선이 INFO인지를 증거로 남긴다.
     *
     * <p>org.springframework.web을 DEBUG로 켜면 스프링이 역직렬화한 요청 DTO와
     * 바깥으로 나가는 폼 본문을 그대로 찍는다 — {@code GoogleLoginRequest[code=...]}와
     * {@code client_secret=[...]}이 한 줄씩 남는다(LogFormatUtils가 100자에서
     * 자를 뿐이다). 하이버네이트를 DEBUG로 켜면 User 엔티티 toString이 email·name·
     * google_sub를 통째로 찍는다. 우리 코드가 아무리 조심해도 막을 수 없다.
     *
     * <p>그래서 이것은 코드가 아니라 <b>배포 규칙</b>이다: 운영에서 로그 레벨을
     * DEBUG로 내리지 않는다. 규칙이 지켜지는지를 설정으로 못박고, 규칙의 근거를
     * 아래에서 실제로 재현한다 — 주석으로만 적으면 스프링이 바뀌었을 때 아무도
     * 모른다. 이 단언이 깨지면 규칙을 다시 볼 때다.
     */
    @Test
    void 스프링_web을_DEBUG로_켜면_새므로_설정에서_켜지_않는다() throws Exception {
        assertThat(environment.getProperty("logging.level.root", "info")).isEqualToIgnoringCase("info");
        assertThat(environment.getProperty("logging.level." + SPRING_WEB_LOGGER, "info"))
                .isEqualToIgnoringCase("info");

        try (LogCaptor captor = new LogCaptor()) {
            setLevel(SPRING_WEB_LOGGER, Level.DEBUG);
            try {
                mockMvc.perform(post("/api/auth/google")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"code\":\"" + GOOGLE_CODE + "\"}"))
                        .andExpect(status().isUnauthorized());
            } finally {
                // null이면 상위 레벨을 다시 물려받는다.
                setLevel(SPRING_WEB_LOGGER, null);
            }

            assertThatThrownBy(() -> assertNoSecretsIn(captor, SECRETS))
                    .as("스프링이 더는 요청 본문을 찍지 않는다면 배포 규칙을 다시 볼 때다")
                    .isInstanceOf(AssertionError.class);
        }
    }

    @Test
    void 탐지기는_메시지에_있는_비밀을_잡는다() {
        String planted = needle("planted-in-message");

        try (LogCaptor captor = new LogCaptor()) {
            log.info("secret-leak-self-check {}", planted);

            assertThatThrownBy(() -> assertNoSecretsIn(captor, List.of(planted)))
                    .isInstanceOf(AssertionError.class);
        }
    }

    /**
     * 이 자기검사가 가장 중요하다. {@code log.error("...", e)}로 새는 값은
     * getFormattedMessage()에 없고 ThrowableProxy 안에 있다. 탐지기가 메시지만
     * 보면 위 테스트 전부가 아무것도 검사하지 않는 채로 초록불이 된다.
     *
     * <p>프로덕션 코드에 일부러 leak을 넣었다 되돌리는 방식은 쓰지 않는다 —
     * 되돌리기를 한 번만 빠뜨리면 인가 코드가 로그로 나가는 코드가 머지된다.
     */
    @Test
    void 탐지기는_예외의_cause와_suppressed에_숨은_비밀도_잡는다() {
        String inCause = needle("planted-in-cause");
        String inSuppressed = needle("planted-in-suppressed");

        RuntimeException thrown =
                new RuntimeException("겉면은 깨끗하다", new IllegalStateException(inCause));
        thrown.addSuppressed(new IllegalStateException(inSuppressed));

        try (LogCaptor captor = new LogCaptor()) {
            log.error("secret-leak-self-check", thrown);

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
     * 바늘은 매 실행 무작위다. code-1·sub-1 같은 짧은 상수는 다른 로그와 우연히
     * 겹쳐 "안 샜다"가 아니라 "겹친 것을 못 봤다"로 통과할 수 있다.
     */
    private static String needle(String label) {
        return "LEAK-" + label + "-" + UUID.randomUUID();
    }

    private TokenPair issueFor(String googleSub) {
        return tokenService.issue(
                userService.findOrCreate(googleSub, "a@example.com", "김태현", null));
    }

    /** 마지막 한 글자만 바꿔 서명을 깨뜨린다. */
    private static String tamperSignature(String jwt) {
        char last = jwt.charAt(jwt.length() - 1);
        return jwt.substring(0, jwt.length() - 1) + (last == 'A' ? 'B' : 'A');
    }

    private String refresh(String refreshToken, ResultMatcher expected) throws Exception {
        String body = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(expected)
                .andReturn().getResponse().getContentAsString();

        return body.contains("\"refreshToken\"") ? JsonPath.read(body, "$.refreshToken") : "";
    }

    /** 기본값이 never다. 안 적혀 있는 것과 never로 적힌 것은 같다. */
    private String errorAttribute(String name) {
        return environment.getProperty("server.error." + name, "never");
    }

    private static List<Class<? extends Annotation>> constraintsOn(Class<?> dto, String field)
            throws NoSuchFieldException {
        return Arrays.stream(dto.getDeclaredField(field).getAnnotations())
                .map(Annotation::annotationType)
                .toList();
    }

    private static void setLevel(String loggerName, Level level) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loggerName)).setLevel(level);
    }

    private static void assertNoSecretsIn(LogCaptor captor, List<String> secrets) {
        assertNoSecretsIn(captor.events().stream()
                .map(SecretLeakTest::renderFully)
                .collect(Collectors.joining("\n")), secrets);
    }

    private static void assertNoSecretsIn(String haystack, List<String> secrets) {
        for (String secret : secrets) {
            assertThat(secret).as("빈 바늘은 어디서나 발견돼 검사를 무력화한다").isNotEmpty();
            assertThat(haystack).as("비밀이 남았다: " + secret).doesNotContain(secret);
        }
    }

    /**
     * 포맷된 한 줄 <b>과</b> 딸려 붙은 예외 전체. LogCaptor.messages()는
     * getFormattedMessage()만 주는데, log.error("...", e)로 새는 값은 거기 없다.
     */
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
