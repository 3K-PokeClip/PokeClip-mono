package com.pokeclip.auth;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import com.jayway.jsonpath.JsonPath;
import com.pokeclip.auth.api.dto.GoogleLoginRequest;
import com.pokeclip.auth.api.dto.RefreshRequest;
import com.pokeclip.auth.chzzk.ChzzkCleanupExecutor;
import com.pokeclip.auth.chzzk.ChzzkLinkStateCodec;
import com.pokeclip.auth.chzzk.ChzzkLinkWriter;
import com.pokeclip.auth.chzzk.ChzzkMe;
import com.pokeclip.auth.chzzk.ChzzkTokens;
import com.pokeclip.auth.chzzk.api.dto.ChzzkResolveRequest;
import com.pokeclip.auth.chzzk.api.dto.LinkRequest;
import com.pokeclip.auth.streamkey.api.dto.ExchangeRequest;
import com.pokeclip.auth.streamkey.api.dto.ResolveRequest;
import com.pokeclip.auth.token.RefreshTokenRepository;
import com.pokeclip.auth.token.TokenPair;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.auth.support.FakeHttpServer;
import com.pokeclip.auth.support.IntegrationTestSupport;
import com.pokeclip.auth.youtube.YoutubeChannel;
import com.pokeclip.auth.youtube.YoutubeCleanupExecutor;
import com.pokeclip.auth.youtube.YoutubeLinkStateCodec;
import com.pokeclip.auth.youtube.YoutubeLinkWriter;
import com.pokeclip.auth.youtube.YoutubeTokens;
import com.pokeclip.auth.youtube.api.dto.YoutubeResolveRequest;
import com.pokeclip.web.RequestIdFilter;
import com.pokeclip.web.support.LogCaptor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

    /** 유니크 위반 메시지에 컬럼 값(채널 ID·이메일)이 실려 나오는 로거. application.yml이 error로 눌러 둔다. */
    private static final String HIBERNATE_JDBC_ERROR_LOGGER = "org.hibernate.orm.jdbc.error";

    private static final String GOOGLE_CODE = needle("google-code");

    /** application-test.yml 대신 아래 @DynamicPropertySource로 주입한다. */
    private static final String CLIENT_SECRET = needle("google-client-secret");

    /** 60자를 넘어 HS256의 32바이트 하한(JwtConfig.MIN_SECRET_BYTES)을 만족한다. */
    private static final String JWT_SECRET = needle("jwt-signing-secret");

    /**
     * application-test.yml의 값과 같아야 한다. 위의 셋과 달리
     * &#64;DynamicPropertySource로 주입하지 않는다 — 이 값은 시크릿이 아니라 테스트
     * 고정값이고, 주입하면 InternalSecurityConfig가 다른 프로퍼티로 뜨면서
     * 컨텍스트 캐시가 하나 더 생겨 전체 실행이 느려진다.
     */
    private static final String INTERNAL_TOKEN = "test-only-internal-token-32bytes-long!!";

    private static final List<String> SECRETS = List.of(GOOGLE_CODE, CLIENT_SECRET, JWT_SECRET);

    /** 치지직 바늘. 토큰 둘·채널은 가짜 서버가 돌려주고, code는 요청에, 시크릿은 설정(@DynamicPropertySource)에서 온다. */
    private static final String CHZZK_ACCESS = needle("chzzk-access");
    private static final String CHZZK_REFRESH = needle("chzzk-refresh");
    private static final String CHZZK_CODE = needle("chzzk-code");
    private static final String CHZZK_CLIENT_SECRET = needle("chzzk-client-secret");
    private static final String CHZZK_CHANNEL_ID = needle("chzzk-channel");

    /** 유튜브 바늘. 토큰 둘·채널은 가짜 구글이 돌려주고, code는 요청에, 시크릿은 설정(@DynamicPropertySource)에서 온다. */
    private static final String YT_ACCESS = needle("youtube-access");
    private static final String YT_REFRESH = needle("youtube-refresh");
    private static final String YT_CODE = needle("youtube-code");
    private static final String YT_CLIENT_SECRET = needle("youtube-client-secret");
    private static final String YT_CHANNEL_ID = needle("youtube-channel");

    /**
     * DEBUG에서 실제로 새는 것. 요청·응답 본문에 실려 다니는 둘뿐이다.
     * JWT 서명키는 본문에 실리지 않아 DEBUG에서도 안 샌다 — 그래서 여기 없다.
     */
    private static final List<String> LEAKS_AT_DEBUG = List.of(GOOGLE_CODE, CLIENT_SECRET);

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
    private final ChzzkLinkStateCodec stateCodec;
    private final ChzzkLinkWriter linkWriter;
    private final ChzzkCleanupExecutor cleanup;
    private final YoutubeLinkStateCodec youtubeCodec;
    private final YoutubeLinkWriter youtubeWriter;
    private final YoutubeCleanupExecutor youtubeCleanup;
    private final com.pokeclip.auth.youtube.YoutubeChannelLinkRepository youtubeLinks;

    SecretLeakTest(MockMvc mockMvc, TokenService tokenService, UserService userService,
                   UserRepository userRepository,
                   RefreshTokenRepository refreshTokenRepository,
                   JdbcTemplate jdbcTemplate, Environment environment,
                   ChzzkLinkStateCodec stateCodec, ChzzkLinkWriter linkWriter, ChzzkCleanupExecutor cleanup,
                   YoutubeLinkStateCodec youtubeCodec, YoutubeLinkWriter youtubeWriter,
                   YoutubeCleanupExecutor youtubeCleanup,
                   com.pokeclip.auth.youtube.YoutubeChannelLinkRepository youtubeLinks) {
        this.mockMvc = mockMvc;
        this.tokenService = tokenService;
        this.userService = userService;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
        this.stateCodec = stateCodec;
        this.linkWriter = linkWriter;
        this.cleanup = cleanup;
        this.youtubeCodec = youtubeCodec;
        this.youtubeWriter = youtubeWriter;
        this.youtubeCleanup = youtubeCleanup;
        this.youtubeLinks = youtubeLinks;
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
        registry.add("pokeclip.chzzk.app.client-secret", () -> CHZZK_CLIENT_SECRET);
        registry.add("pokeclip.youtube.app.client-secret", () -> YT_CLIENT_SECRET);
    }

    @AfterAll
    static void stopFakeGoogle() {
        GOOGLE.close();
    }

    @BeforeEach
    void setUp() {
        CHZZK.reset();   // 같은 static 가짜 서버를 다른 클래스와 나눠 쓴다. 여기서 심은 needle이 다음으로 새지 않게.
        YOUTUBE.reset();
        clearStreamKeyChildren();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    /** auth/CLAUDE.md의 FK 함정. 자식 행을 남기면 다른 테스트의 부모 정리를 막는다. */
    @AfterEach
    void tearDown() {
        cleanup.awaitIdle(Duration.ofSeconds(5));   // 전용 스레드의 정리가 다음 클래스의 CHZZK.reset() 뒤에 도착하지 않게
        youtubeCleanup.awaitIdle(Duration.ofSeconds(5));   // 유튜브 쪽도 같은 이유 — 안 걸면 정리 로그가 다음 클래스로 샌다
        clearStreamKeyChildren();
        refreshTokenRepository.deleteAll();
    }

    /** FK 함정. 자식 행을 남기면 다른 테스트의 부모 정리를 막는다. */
    private void clearStreamKeyChildren() {
        jdbcTemplate.update("DELETE FROM chzzk_channel_links");
        jdbcTemplate.update("DELETE FROM youtube_channel_links");
        jdbcTemplate.update("DELETE FROM pairing_exchange_attempts");
        jdbcTemplate.update("DELETE FROM pairing_codes");
        jdbcTemplate.update("DELETE FROM stream_keys");
        jdbcTemplate.update("DELETE FROM secrets");
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
     * 스트림키 경로 전체를 HTTP로 태운다. 서비스를 직접 부르면 예외 핸들러가
     * 안 돌아, 핸들러가 거부된 코드를 찍도록 바뀌어도 이 테스트가 통과한다.
     *
     * <p>바늘 넷이 다 다른 층에서 나온다 — passphrase는 SecretStore에서,
     * streamid 토큰은 그 안에 같이 들어 있고, 페어링 코드는 응답에만 있고,
     * 내부 API 토큰은 설정에서 온다.
     */
    @Test
    void 스트림키_경로를_돌려도_비밀이_로그에_남지_않는다() throws Exception {
        // 이 테스트만의 사용자. 다른 테스트의 users 정리와 겹치지 않게 한다.
        var user = userService.findOrCreate(needle("streamkey-sub"), "a@example.com", "김태현", null);
        String bearer = "Bearer " + tokenService.issue(user).accessToken();

        try (LogCaptor captor = new LogCaptor()) {
            String issued = mockMvc.perform(post("/api/stream-keys/pairing-codes")
                            .header("Authorization", bearer))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String code = JsonPath.read(issued, "$.code");

            String exchanged = mockMvc.perform(post("/api/stream-keys/pairing-codes/exchange")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"" + code + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String streamid = JsonPath.read(exchanged, "$.streamid");
            String passphrase = JsonPath.read(exchanged, "$.passphrase");

            mockMvc.perform(post("/internal/stream-keys/resolve")
                            .header("X-Internal-Token", INTERNAL_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"streamid\":\"" + streamid + "\"}"))
                    .andExpect(status().isOk());

            // 실패 경로도 태운다. 값을 찍는 사고는 여기서 훨씬 흔하다.
            mockMvc.perform(post("/api/stream-keys/pairing-codes/exchange")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"" + code + "\"}"))
                    .andExpect(status().isConflict());
            mockMvc.perform(post("/api/stream-keys/rotate").header("Authorization", bearer))
                    .andExpect(status().isOk());

            assertThat(captor.messages())
                    .as("경로가 아예 안 돌았다. 그러면 아무것도 검사하지 않은 것이다")
                    .anyMatch(m -> m.startsWith("auth.pairing.code_exchanged"));

            assertNoSecretsIn(captor,
                    List.of(code, code.replace("-", ""), streamid, passphrase, INTERNAL_TOKEN));

            // 통째로 찍는 코드가 들어온 것 자체를 잡는다. 이번에 흐른 값이
            // 우연히 안 걸리더라도 여기서 걸린다.
            assertThat(String.join("\n", captor.messages()))
                    .doesNotContain("StreamKeyMaterial[")
                    .doesNotContain("ResolveResult[")
                    .doesNotContain("ResolveResponse[")
                    .doesNotContain("ExchangeResponse[")
                    .doesNotContain("PairingCodeResponse[")
                    .doesNotContain("IssuedCode[");
        }
    }

    /**
     * 치지직 연동 경로 전체를 HTTP로 태운다 — 정상 연동·교환 거부·해제·resolve·즉석 갱신 거부.
     * 가짜 치지직이 바늘 토큰·바늘 채널을 돌려주고, 거부 본문에도 바늘을 싣는다
     * (RestClientResponseException 메시지에 본문이 붙으므로 그것을 옮기는 변경이 여기서 걸린다).
     *
     * <p>state는 서명이라 바늘을 페이로드에 못 심는다 — 실제 발급된 state 문자열 자체를 바늘로 쓴다.
     * client_secret은 매 요청 본문에 실려 나가고, resolve 응답에는 accessToken이 <b>있어야 한다</b>
     * (그게 목적) — 로그에만 없으면 된다.
     */
    @Test
    void 치지직_연동_경로를_돌려도_토큰_code_채널이_로그에_남지_않는다() throws Exception {
        var user = userService.findOrCreate(needle("chzzk-sub"), "a@example.com", "김태현", null);
        String bearer = "Bearer " + tokenService.issue(user).accessToken();
        String state = stateCodec.issue(user.getId(), Instant.now());
        CHZZK.tokenResponds(200, "{\"code\":200,\"content\":{\"accessToken\":\"" + CHZZK_ACCESS
                + "\",\"refreshToken\":\"" + CHZZK_REFRESH + "\",\"tokenType\":\"Bearer\",\"expiresIn\":\"86400\",\"scope\":\"chat\"}}");
        CHZZK.meResponds(200, "{\"code\":200,\"content\":{\"channelId\":\"" + CHZZK_CHANNEL_ID + "\",\"channelName\":\"채널\"}}");
        List<String> needles = List.of(CHZZK_ACCESS, CHZZK_REFRESH, CHZZK_CODE, CHZZK_CLIENT_SECRET, CHZZK_CHANNEL_ID, state, JWT_SECRET);

        try (LogCaptor captor = new LogCaptor()) {
            String startBody = mockMvc.perform(post("/api/chzzk-link/start").header("Authorization", bearer))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(startBody).as("동의 URL에는 시크릿이 없다").doesNotContain(CHZZK_CLIENT_SECRET);

            // ① 정상 연동. 응답에는 channelId만 있고 토큰은 없다.
            String linked = mockMvc.perform(post("/api/chzzk-link").header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"" + CHZZK_CODE + "\",\"state\":\"" + state + "\"}"))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            assertThat(linked).contains(CHZZK_CHANNEL_ID).doesNotContain(CHZZK_ACCESS).doesNotContain(CHZZK_REFRESH);
            assertThat(CHZZK.tokenRequests()).as("바늘이 실제로 치지직으로 나갔다").anySatisfy(r ->
                    assertThat(r).containsEntry("code", CHZZK_CODE).containsEntry("clientSecret", CHZZK_CLIENT_SECRET));

            // ④ resolve — 수집기에는 토큰을 줘야 한다. 로그에만 없으면 된다.
            String resolved = mockMvc.perform(post("/internal/chzzk-link/resolve")
                            .header("X-Internal-Token", INTERNAL_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":" + user.getId() + "}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(resolved).contains(CHZZK_ACCESS).contains(CHZZK_CHANNEL_ID);

            // REFRESHED 경로 — 임박 행을 만들고 resolve로 즉석 갱신. 새 토큰(바늘)이 응답에 실리고, refresh 원문이 요청에 실린다.
            // 갱신 로그는 같은 스레드 동기 afterCommit이라 요청의 상관 ID가 살아 있어야 한다(내부 API라 헤더로 값을 직접 준다).
            String refreshedAccess = needle("chzzk-access-refreshed");
            String refreshedRefresh = needle("chzzk-refresh-refreshed");
            String requestId = UUID.randomUUID().toString().replace("-", "");   // RequestIdFilter.SAFE: [A-Za-z0-9-]{1,32}
            mockMvc.perform(delete("/api/chzzk-link").header("Authorization", bearer)).andExpect(status().isNoContent());
            assertThat(cleanup.awaitIdle(Duration.ofSeconds(5))).isTrue();
            linkWriter.create(user.getId(), new ChzzkMe(CHZZK_CHANNEL_ID, "채널"),
                    new ChzzkTokens(CHZZK_ACCESS, CHZZK_REFRESH, Duration.ofHours(1), null));
            CHZZK.tokenResponds(200, "{\"code\":200,\"content\":{\"accessToken\":\"" + refreshedAccess
                    + "\",\"refreshToken\":\"" + refreshedRefresh + "\",\"tokenType\":\"Bearer\",\"expiresIn\":86400,\"scope\":\"chat\"}}");
            String refreshed = mockMvc.perform(post("/internal/chzzk-link/resolve")
                            .header("X-Internal-Token", INTERNAL_TOKEN)
                            .header("X-Request-Id", requestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":" + user.getId() + "}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(refreshed).contains(refreshedAccess).doesNotContain(refreshedRefresh);
            assertThat(CHZZK.tokenRequests()).anySatisfy(r -> assertThat(r).containsEntry("refreshToken", CHZZK_REFRESH));
            assertThat(captor.mdcOf("auth.chzzk.link.refreshed", RequestIdFilter.MDC_KEY))
                    .as("갱신 로그가 요청 스레드 동기 afterCommit이라 상관 ID가 붙는다").isEqualTo(requestId);
            assertThat(captor.events()).filteredOn(ev -> ev.getFormattedMessage().startsWith("auth.chzzk.link.refreshed"))
                    .as("'일어났다' 로그는 정리 큐(거부될 수 있다)가 아니라 요청 스레드에서 찍는다")
                    .allSatisfy(ev -> assertThat(ev.getThreadName()).doesNotStartWith("chzzk-cleanup-"));

            // 해제 — 옛 토큰 revoke 본문에 원문이 실린다. 로그에는 없어야 한다.
            mockMvc.perform(delete("/api/chzzk-link").header("Authorization", bearer)).andExpect(status().isNoContent());
            assertThat(cleanup.awaitIdle(Duration.ofSeconds(5))).isTrue();   // secrets 삭제·revoke는 커밋 뒤 전용 스레드
            assertThat(CHZZK.revokedTokens()).contains(refreshedAccess, refreshedRefresh);

            // ② 교환 400 — 본문에 바늘. 에러 응답에도 로그에도 없어야 한다.
            String state2 = stateCodec.issue(user.getId(), Instant.now());
            CHZZK.tokenResponds(400, "{\"code\":400,\"message\":\"bad code " + CHZZK_CODE + " secret " + CHZZK_CLIENT_SECRET + "\"}");
            String rejected = mockMvc.perform(post("/api/chzzk-link").header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"" + CHZZK_CODE + "\",\"state\":\"" + state2 + "\"}"))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertNoSecretsIn(rejected, needles);

            // ③ 즉석 갱신 4xx — refresh 원문이 요청 본문에 실리고, 거부 본문에 바늘.
            linkWriter.create(user.getId(), new ChzzkMe(CHZZK_CHANNEL_ID, "채널"),
                    new ChzzkTokens(CHZZK_ACCESS, CHZZK_REFRESH, Duration.ofHours(1), null));
            CHZZK.tokenResponds(401, "{\"code\":401,\"message\":\"INVALID_TOKEN " + CHZZK_REFRESH + "\"}");
            String broken = mockMvc.perform(post("/internal/chzzk-link/resolve")
                            .header("X-Internal-Token", INTERNAL_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":" + user.getId() + "}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(broken).contains("BROKEN");
            assertThat(CHZZK.tokenRequests()).anySatisfy(r -> assertThat(r).containsEntry("refreshToken", CHZZK_REFRESH));
            assertThat(cleanup.awaitIdle(Duration.ofSeconds(5))).isTrue();   // refresh_rejected 로그·삭제가 전용 스레드에서 끝난 뒤 검사

            assertThat(captor.messages())
                    .as("경로가 아예 안 돌았다. 그러면 아무것도 검사하지 않은 것이다")
                    .anyMatch(m -> m.startsWith("auth.chzzk.link.created"))
                    .anyMatch(m -> m.startsWith("auth.chzzk.link.rejected"))
                    .anyMatch(m -> m.startsWith("auth.chzzk.link.unlinked"))
                    .anyMatch(m -> m.startsWith("auth.chzzk.link.refresh_rejected"));
            assertNoSecretsIn(captor, needles);
            assertNoSecretsIn(captor, List.of(refreshedAccess, refreshedRefresh));
            assertNoSecretsIn(broken, List.of(CHZZK_ACCESS, CHZZK_REFRESH));

            // ⑥ 통째로 찍는 코드가 들어온 것 자체를 잡는다.
            assertThat(String.join("\n", captor.messages()))
                    .doesNotContain("ChzzkTokens[")
                    .doesNotContain("ChzzkMe[")
                    .doesNotContain("ChzzkResolveResult[")
                    .doesNotContain("ChzzkResolveResponse[")
                    .doesNotContain("LinkResponse[")
                    .doesNotContain("LinkResult[")
                    .doesNotContain("LinkSnapshot[")
                    .doesNotContain("RefreshResult[")
                    .doesNotContain("LinkStatusResponse[");
        }
    }

    /**
     * {@code org.hibernate.orm.jdbc.error}를 WARN으로 올리면 유니크 위반 메시지에 <b>컬럼 값이 그대로</b> 실린다
     * ({@code Key (channel_id)=(…) already exists}). 채널 ID는 로그에 안 찍는 값이므로 그 한 줄이 방어선이고,
     * 지금까지 어느 검사도 그것을 못박지 않았다(감사 1라운드 사소-D).
     *
     * <p>web 로거와 같은 모양으로 잰다 — 기본값 없는 {@code getProperty}(줄이 사라지면 빨간불) +
     * <b>양성 재현</b>(WARN에서 실제로 새는 것). 음성(기본 레벨에서 안 샘)은 같은 흐름에서 이어 잰다.
     */
    @Test
    void 하이버네이트_JDBC_오류를_WARN으로_켜면_채널ID가_새므로_설정에서_켜지_않는다() {
        String level = environment.getProperty("logging.level." + HIBERNATE_JDBC_ERROR_LOGGER);
        assertThat(level).as("application.yml에 이 로거 레벨이 박혀 있어야 root를 내려도 버틴다").isNotNull();
        assertThat(Level.toLevel(level, Level.DEBUG).toInt())
                .as("아래가 재현하는 유출이 이 레벨에서 열린다: " + level)
                .isGreaterThanOrEqualTo(Level.ERROR.toInt());
        Level levelBefore = levelOf(HIBERNATE_JDBC_ERROR_LOGGER);
        assertThat(levelBefore).isNotNull();

        var owner = userService.findOrCreate(needle("yt-dup-owner"), "dup1@example.com", "김태현", null);
        var other = userService.findOrCreate(needle("yt-dup-other"), "dup2@example.com", "김태현", null);
        youtubeWriter.create(owner.getId(), new YoutubeChannel(YT_CHANNEL_ID, "채널"),
                new YoutubeTokens(YT_ACCESS, YT_REFRESH, Duration.ofHours(1), null));

        // ① 기본 레벨(error) — 같은 위반을 내도 값이 안 샌다.
        try (LogCaptor quiet = new LogCaptor()) {
            assertThatThrownBy(() -> insertDuplicateChannel(other.getId()))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertNoSecretsIn(quiet, List.of(YT_CHANNEL_ID));
        }

        // ② WARN으로 올리면 — 같은 위반이 채널 ID를 통째로 찍는다. 이 재현이 ①의 근거다.
        try (LogCaptor loud = new LogCaptor()) {
            setLevel(HIBERNATE_JDBC_ERROR_LOGGER, Level.WARN);
            try {
                assertThatThrownBy(() -> insertDuplicateChannel(other.getId()))
                        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            } finally {
                setLevel(HIBERNATE_JDBC_ERROR_LOGGER, levelBefore);
            }
            assertThat(renderAll(loud))
                    .as("WARN에서 더는 안 샌다면 application.yml의 그 줄을 다시 볼 때다")
                    .contains(YT_CHANNEL_ID);
        }
    }

    /** 사전 조회를 거치지 않고 DB 유니크(uq_youtube_links_alive_channel)까지 그대로 보낸다 — Hibernate가 그 예외를 찍는 자리다. */
    private void insertDuplicateChannel(Long userId) {
        youtubeLinks.saveAndFlush(com.pokeclip.auth.youtube.YoutubeChannelLink.of(
                userId, YT_CHANNEL_ID, "채널", null, "youtube-access:dup", "youtube-refresh:dup",
                Instant.now().plus(Duration.ofHours(1)), Instant.now()));
    }

    /**
     * 유튜브 연동 경로 전체를 HTTP로 태운다 — 정상 연동·즉석 갱신·해제·재연동·교환 거부·갱신 거부.
     * 가짜 구글이 바늘 토큰·바늘 채널을 돌려주고, 거부 본문에도 바늘을 싣는다(핸들러가 원인 본문을
     * 옮기기 시작하면 여기서 걸린다). 바늘이 <b>실제로 구글로 나갔는지</b>도 함께 재므로
     * 「경로가 안 돌아서 초록」이 되지 않는다.
     */
    @Test
    void 유튜브_연동_경로를_돌려도_토큰_code_채널이_로그에_남지_않는다() throws Exception {
        var user = userService.findOrCreate(needle("youtube-sub"), "yt@example.com", "김태현", null);
        String bearer = "Bearer " + tokenService.issue(user).accessToken();
        String state = youtubeCodec.issue(user.getId(), Instant.now());
        YOUTUBE.tokenResponds(200, tokenJson(YT_ACCESS, YT_REFRESH));
        YOUTUBE.channelsResponds(200, "{\"items\":[{\"id\":\"" + YT_CHANNEL_ID
                + "\",\"snippet\":{\"title\":\"채널\"}}]}");
        List<String> needles = List.of(YT_ACCESS, YT_REFRESH, YT_CODE, YT_CLIENT_SECRET, YT_CHANNEL_ID,
                state, JWT_SECRET);

        try (LogCaptor captor = new LogCaptor()) {
            // ① 동의 URL — 여기에 시크릿이 실리면 브라우저 주소창에 그대로 남는다.
            String startBody = mockMvc.perform(post("/api/youtube-link/start").header("Authorization", bearer))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(startBody).as("동의 URL에는 시크릿이 없다").doesNotContain(YT_CLIENT_SECRET);
            assertThat(startBody).as("동의 URL 형태가 맞는지").contains("access_type=offline");

            // ② 연동 — 응답에 채널은 있고 토큰은 없다.
            String linked = mockMvc.perform(post("/api/youtube-link").header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"" + YT_CODE + "\",\"state\":\"" + state + "\"}"))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            assertThat(linked).contains(YT_CHANNEL_ID).doesNotContain(YT_ACCESS).doesNotContain(YT_REFRESH);
            assertThat(YOUTUBE.tokenRequests()).as("바늘이 실제로 구글로 나갔다").anySatisfy(r ->
                    assertThat(r).containsEntry("code", YT_CODE).containsEntry("client_secret", YT_CLIENT_SECRET));

            // ③ 내부 창구 — 여기서는 토큰을 준다(그것이 계약이다). 로그에는 남지 않아야 한다.
            String resolved = mockMvc.perform(post("/internal/youtube-link/resolve")
                            .header("X-Internal-Token", INTERNAL_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":" + user.getId() + "}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(resolved).contains(YT_ACCESS).contains(YT_CHANNEL_ID);

            // ④ 즉석 갱신 — access를 만료시켜 갱신 경로를 태운다. 새 바늘도 안 새야 한다.
            String refreshedAccess = needle("youtube-access-refreshed");
            jdbcTemplate.update("UPDATE youtube_channel_links SET access_expires_at = now() - interval '1 hour' "
                    + "WHERE user_id = ?", user.getId());
            YOUTUBE.tokenResponds(200, tokenJson(refreshedAccess, null));   // 갱신 응답엔 refresh가 없다(실물)
            String refreshed = mockMvc.perform(post("/internal/youtube-link/resolve")
                            .header("X-Internal-Token", INTERNAL_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":" + user.getId() + "}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(refreshed).contains(refreshedAccess);
            assertThat(YOUTUBE.tokenRequests()).anySatisfy(r -> assertThat(r).containsEntry("refresh_token", YT_REFRESH));
            // 커밋 뒤 로그가 정리 스레드가 아니라 요청 스레드에서 찍혀 상관 ID가 살아 있다.
            assertThat(captor.mdcOf("auth.youtube.link.refreshed", RequestIdFilter.MDC_KEY)).isNotBlank();
            assertThat(captor.events()).filteredOn(ev -> ev.getFormattedMessage().startsWith("auth.youtube.link.refreshed"))
                    .isNotEmpty()
                    .allSatisfy(ev -> assertThat(ev.getThreadName()).doesNotStartWith("youtube-cleanup-"));

            // ⑤ 해제 → 재연동. 두 경로의 커밋 뒤 로그(unlinked·relinked)가 실제로 찍히는지도 여기서 못박는다.
            mockMvc.perform(delete("/api/youtube-link").header("Authorization", bearer))
                    .andExpect(status().isNoContent());
            assertThat(youtubeCleanup.awaitIdle(Duration.ofSeconds(5))).isTrue();
            assertThat(YOUTUBE.revokeCalls()).as("해제는 구글에 아무것도 보내지 않는다").isZero();
            YOUTUBE.tokenResponds(200, tokenJson(YT_ACCESS, YT_REFRESH));
            String state2 = youtubeCodec.issue(user.getId(), Instant.now());
            mockMvc.perform(post("/api/youtube-link").header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"" + YT_CODE + "\",\"state\":\"" + state2 + "\"}"))
                    .andExpect(status().isCreated());
            youtubeWriter.create(user.getId(), new YoutubeChannel(YT_CHANNEL_ID, "채널"),
                    new YoutubeTokens(YT_ACCESS, YT_REFRESH, Duration.ofHours(1), null));   // relinked 경로
            assertThat(youtubeCleanup.awaitIdle(Duration.ofSeconds(5))).isTrue();

            // ⑥ 교환 거부 — 거부 본문에 바늘이 들어 있다. 핸들러가 그것을 옮기면 여기서 걸린다.
            YOUTUBE.tokenResponds(400, "{\"error\":\"invalid_grant\",\"error_description\":\"bad code "
                    + YT_CODE + " secret " + YT_CLIENT_SECRET + "\"}");
            String rejected = mockMvc.perform(post("/api/youtube-link").header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"" + YT_CODE + "\",\"state\":\""
                                    + youtubeCodec.issue(user.getId(), Instant.now()) + "\"}"))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertThat(rejected).contains("INVALID_CODE").doesNotContain(YT_CODE).doesNotContain(YT_CLIENT_SECRET);

            // ⑦ 갱신 거부 → BROKEN. 거부 본문에 refresh 바늘을 싣는다.
            jdbcTemplate.update("UPDATE youtube_channel_links SET access_expires_at = now() - interval '1 hour' "
                    + "WHERE user_id = ? AND revoked_at IS NULL", user.getId());
            YOUTUBE.tokenResponds(400, "{\"error\":\"invalid_grant\",\"error_description\":\"revoked "
                    + YT_REFRESH + "\"}");
            String broken = mockMvc.perform(post("/internal/youtube-link/resolve")
                            .header("X-Internal-Token", INTERNAL_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":" + user.getId() + "}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(broken).contains("BROKEN");
            assertThat(youtubeCleanup.awaitIdle(Duration.ofSeconds(5))).isTrue();

            assertThat(captor.messages())
                    .as("경로가 아예 안 돌았다. 그러면 아무것도 검사하지 않은 것이다")
                    .anyMatch(m -> m.startsWith("auth.youtube.link.created"))
                    .anyMatch(m -> m.startsWith("auth.youtube.link.refreshed"))
                    .anyMatch(m -> m.startsWith("auth.youtube.link.unlinked"))
                    .anyMatch(m -> m.startsWith("auth.youtube.link.relinked"))
                    .anyMatch(m -> m.startsWith("auth.youtube.link.rejected"))
                    .anyMatch(m -> m.startsWith("auth.youtube.link.refresh_rejected"));
            assertNoSecretsIn(captor, needles);
            assertNoSecretsIn(captor, List.of(refreshedAccess));
            assertNoSecretsIn(broken, List.of(YT_ACCESS, YT_REFRESH, refreshedAccess));

            // ⑧ 통째로 찍는 코드가 들어온 것 자체를 잡는다. record toString은 단순 이름만 쓰므로
            //    LinkResult[·LinkSnapshot[·RefreshResult[·LinkStatusResponse[는 위 치지직 목록이 이미 덮는다.
            assertThat(String.join("\n", captor.messages()))
                    .doesNotContain("YoutubeTokens[")
                    .doesNotContain("YoutubeChannel[")
                    .doesNotContain("YoutubeResolveResult[")
                    .doesNotContain("YoutubeResolveResponse[")
                    .doesNotContain("LinkResult[")
                    .doesNotContain("LinkSnapshot[")
                    .doesNotContain("RefreshResult[")
                    .doesNotContain("LinkStatusResponse[");
        }
    }

    /** 구글 토큰 응답 한 벌. refresh가 null이면 빼고 만든다 — 갱신 응답의 실물 모양이다. */
    private static String tokenJson(String access, String refresh) {
        return "{\"access_token\":\"" + access + "\""
                + (refresh == null ? "" : ",\"refresh_token\":\"" + refresh + "\"")
                + ",\"expires_in\":3600,\"token_type\":\"Bearer\",\"scope\":\""
                + com.pokeclip.auth.support.FakeYoutubeServer.SCOPE_GRANTED + "\"}";
    }

    /**
     * 치지직 쪽 DEBUG 유출 재현(감사 1회차 실측). {@code DefaultRestClient.logBody}는 폼뿐 아니라
     * JSON(Map) 본문도 {@code Writing [{clientId=…, clientSecret=…, code=…, state=…}]}로 통째로 찍는다 —
     * client_secret·code·state·refresh 토큰 원문 넷이 샌다. {@code application.yml}의
     * {@code org.springframework.web: info} 한 줄이 유일한 방어선이다. 양성(DEBUG에서 샌다)을 재현해
     * 그 줄의 근거를 남기고, INFO 음성은 위 테스트가 잰다.
     */
    @Test
    void 스프링_web을_DEBUG로_켜면_치지직_본문도_새므로_설정에서_켜지_않는다() throws Exception {
        var user = userService.findOrCreate(needle("chzzk-debug-sub"), "a@example.com", "김태현", null);
        String bearer = "Bearer " + tokenService.issue(user).accessToken();
        String state = stateCodec.issue(user.getId(), Instant.now());
        linkWriter.create(user.getId(), new ChzzkMe(CHZZK_CHANNEL_ID, "채널"),
                new ChzzkTokens(CHZZK_ACCESS, CHZZK_REFRESH, Duration.ofHours(1), null));
        CHZZK.tokenResponds(503, "{}");
        Level levelBefore = levelOf(SPRING_WEB_LOGGER);
        assertThat(levelBefore).isNotNull();

        try (LogCaptor captor = new LogCaptor()) {
            setLevel(SPRING_WEB_LOGGER, Level.DEBUG);
            try {
                mockMvc.perform(post("/api/chzzk-link").header("Authorization", bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"code\":\"" + CHZZK_CODE + "\",\"state\":\"" + state + "\"}"))
                        .andExpect(status().isBadGateway());
                mockMvc.perform(post("/internal/chzzk-link/resolve")
                                .header("X-Internal-Token", INTERNAL_TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"userId\":" + user.getId() + "}"))
                        .andExpect(status().isOk());
            } finally {
                setLevel(SPRING_WEB_LOGGER, levelBefore);
            }
            assertThat(CHZZK.tokenCalls())
                    .as("""
                            바늘이 실제로 나갔다. 호출 수가 이보다 <b>늘었으면</b> 유출이 아니라 \
                            application.yml의 spring.http.clients.imperative.factory=jdk 핀이 빠진 것이다 — \
                            그러면 스택이 httpclient5로 바뀌고 그쪽 기본 재시도(429·503에 1회, 간격 1초)가 \
                            같은 요청을 한 번 더 내보낸다.""")
                    .isEqualTo(2);

            String debugLog = renderAll(captor);
            for (String secret : List.of(CHZZK_CODE, CHZZK_CLIENT_SECRET, state, CHZZK_REFRESH)) {
                assertThat(debugLog)
                        .as(secret + "가 DEBUG에서 더는 안 샌다면 배포 규칙을 다시 볼 때다")
                        .contains(secret);
            }
            // 응답 본문·access 토큰은 DEBUG에서도 안 샌다(실측). 빠진 것이 실수가 아님을 못박는다.
            assertNoSecretsIn(captor, List.of(CHZZK_ACCESS, JWT_SECRET));
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
     * auth/CLAUDE.md의 함정: 시크릿 필드에 @Size·@Pattern을 걸면 바인딩 실패
     * 리포트가 "rejected value [원문]"으로 값을 평문으로 찍는다. @NotBlank는
     * 거부되는 값이 빈 문자열·null뿐이라 안전하다. 지금 상태를 못박아, 나중에
     * 제약을 더하는 변경이 여기서 걸리게 한다.
     */
    @Test
    void 시크릿을_받는_DTO에는_NotBlank_말고는_걸지_않는다() throws Exception {
        assertThat(constraintsOn(GoogleLoginRequest.class, "code")).containsExactly(NotBlank.class);
        assertThat(constraintsOn(RefreshRequest.class, "refreshToken")).containsExactly(NotBlank.class);
        assertThat(constraintsOn(ExchangeRequest.class, "code")).containsExactly(NotBlank.class);
        assertThat(constraintsOn(ResolveRequest.class, "streamid")).containsExactly(NotBlank.class);
        assertThat(constraintsOn(LinkRequest.class, "code")).containsExactly(NotBlank.class);
        assertThat(constraintsOn(LinkRequest.class, "state")).containsExactly(NotBlank.class);
        assertThat(constraintsOn(ChzzkResolveRequest.class, "userId")).containsExactly(NotNull.class);
        assertThat(constraintsOn(YoutubeResolveRequest.class, "userId")).containsExactly(NotNull.class);
        // 유튜브 LinkRequest는 FQN이다 — 위에서 치지직 LinkRequest를 이미 import했다.
        assertThat(constraintsOn(com.pokeclip.auth.youtube.api.dto.LinkRequest.class, "code"))
                .containsExactly(NotBlank.class);
        assertThat(constraintsOn(com.pokeclip.auth.youtube.api.dto.LinkRequest.class, "state"))
                .containsExactly(NotBlank.class);
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
     * <p>org.springframework.web을 DEBUG로 켜면 스프링이 두 곳에서 찍는다.
     * 들어오는 요청은 역직렬화한 DTO를({@code GoogleLoginRequest[code=...]}),
     * 나가는 요청은 폼 본문을({@code client_secret=[...]}) 그대로 남긴다.
     * <b>둘의 위험도가 다르다</b> — 들어오는 쪽은 LogFormatUtils가 100자에서 자르지만
     * 나가는 폼 본문은 자르지 않아 client_secret이 통째로 남는다. 하이버네이트를
     * DEBUG로 켜면 User 엔티티 toString이 email·name·google_sub를 통째로 찍는다.
     * 우리 코드가 아무리 조심해도 막을 수 없다.
     *
     * <p>그래서 이것은 코드가 아니라 <b>배포 규칙</b>이다: 운영에서 로그 레벨을
     * DEBUG로 내리지 않는다. 규칙이 지켜지는지를 설정으로 못박고, 규칙의 근거를
     * 아래에서 실제로 재현한다 — 주석으로만 적으면 스프링이 바뀌었을 때 아무도
     * 모른다. 이 단언이 깨지면 규칙을 다시 볼 때다.
     */
    @Test
    void 스프링_web을_DEBUG로_켜면_새므로_설정에서_켜지_않는다() throws Exception {
        assertThat(environment.getProperty("logging.level.root", "info")).isEqualToIgnoringCase("info");

        // root만 보면 방어가 사람 손에 달린다 — LOGGING_LEVEL_ROOT=debug 한 줄이면 뚫린다.
        // 구체 로거 레벨이 root보다 우선하므로 이 프로퍼티가 실제 방어선이고,
        // 그래서 기본값을 주지 않는다. 주면 application.yml에서 줄이 사라져도 초록이다.
        String springWebLevel = environment.getProperty("logging.level." + SPRING_WEB_LOGGER);
        assertThat(springWebLevel)
                .as("application.yml에 이 로거 레벨이 박혀 있어야 root를 내려도 버틴다")
                .isNotNull();
        assertThat(Level.toLevel(springWebLevel, Level.DEBUG).toInt())
                .as("아래가 재현하는 유출이 이 레벨에서 실제로 열린다: " + springWebLevel)
                .isGreaterThanOrEqualTo(Level.INFO.toInt());

        // 위 둘은 문자열이 Environment에 있다는 것까지만 증명한다. 부팅 때
        // LogbackLoggingSystem이 이 로거에 명시 레벨을 박았는지는 logback에서 직접 봐야
        // 한다 — 프로퍼티가 여기까지 안 닿으면 방어가 없는 것과 같다.
        // 이 값이 아래 복원값이기도 하다. null로 되돌리면 그 명시 레벨이 지워져
        // root 상속으로 떨어지고, 방금 단언한 방어선이 이 테스트가 도는 순간부터
        // 그 JVM에서 사라진다 — 유출을 증명하는 테스트가 유출 방어를 끄는 꼴이다.
        Level levelBefore = levelOf(SPRING_WEB_LOGGER);
        assertThat(levelBefore)
                .as("프로퍼티가 Environment에만 있고 logback까지 안 닿았다")
                .isNotNull();

        try (LogCaptor captor = new LogCaptor()) {
            setLevel(SPRING_WEB_LOGGER, Level.DEBUG);
            try {
                mockMvc.perform(post("/api/auth/google")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"code\":\"" + GOOGLE_CODE + "\"}"))
                        .andExpect(status().isUnauthorized());
            } finally {
                setLevel(SPRING_WEB_LOGGER, levelBefore);
            }

            // 바늘마다 따로 단언한다. 묶어서 "하나라도 새면 통과"로 두면 스프링이
            // 나가는 폼 본문 로깅만 없애도 — 즉 더 위험한 쪽이 사라져도 — 들어오는
            // DTO 쪽이 남아 이 테스트는 초록으로 유지되고, 근거가 조용히 썩는다.
            String debugLog = renderAll(captor);
            for (String secret : LEAKS_AT_DEBUG) {
                assertThat(debugLog)
                        .as(secret + "가 DEBUG에서 더는 안 샌다면 배포 규칙을 다시 볼 때다")
                        .contains(secret);
            }
            // 서명키는 요청·응답 본문에 실리지 않아 DEBUG에서도 안 샌다.
            // LEAKS_AT_DEBUG에서 빠진 것이 실수가 아님을 여기서 못박는다.
            assertNoSecretsIn(captor, List.of(JWT_SECRET));
        }
    }

    /**
     * 프로필 사진이 나가는 길에 붙은 로거 셋. AWS SDK 2.46.7의 HTTP 클라이언트는 Apache 5이고,
     * {@code org.apache.hc.client5.http.wire}는 요청 본문 전체를, {@code .headers}는 Authorization
     * 값을 DEBUG에 찍는다. SDK 상위 계층 DEBUG는 서명 canonical request를 대량으로 남긴다.
     * netty는 async 클라이언트용이라 지금은 안 도는 길이지만 s3가 딸려오므로 같이 막는다.
     */
    private static final List<String> STORAGE_QUIET_LOGGERS =
            List.of("software.amazon.awssdk", "org.apache.hc.client5.http", "io.netty");

    /**
     * 위 셋을 info로 눌러 두는 application.yml 세 줄을 지금까지 어느 검사도 안 지켰다 —
     * 지워도 아무 데서도 안 걸린다(계획 검증 1회차). 창고 호출이 실제로 도는 것은
     * 뒤 태스크가 얹고, <b>여기서는 배선만 잰다</b>.
     *
     * <p>web 로거와 같은 모양이다 — 기본값 없는 {@code getProperty}(줄이 사라지면 빨간불) +
     * logback까지 닿았는지(프로퍼티만 있고 안 박히면 방어가 없는 것과 같다). 거기에 한 겹 더:
     * <b>root를 TRACE로 내린 채</b> 유효 레벨을 본다. 구체 로거 레벨이 root보다 우선한다는 것이
     * 이 방어의 전제인데, 그 전제를 글로만 적어 두면 다음 사람이 "root로 막으면 되지"로 지운다.
     */
    @Test
    void 창고_SDK_로거는_root를_TRACE로_내려도_INFO_아래로_안_간다() {
        for (String logger : STORAGE_QUIET_LOGGERS) {
            String level = environment.getProperty("logging.level." + logger);
            assertThat(level)
                    .as(logger + " 레벨이 application.yml에 박혀 있어야 root를 내려도 버틴다")
                    .isNotNull();
            assertThat(Level.toLevel(level, Level.DEBUG).toInt())
                    .as(logger + "가 이 레벨이면 본문·Authorization이 열린다: " + level)
                    .isGreaterThanOrEqualTo(Level.INFO.toInt());
            assertThat(levelOf(logger))
                    .as(logger + ": 프로퍼티가 Environment에만 있고 logback까지 안 닿았다")
                    .isNotNull();
        }

        // 여기서부터가 양성 대조다. root를 TRACE로 내려도 셋의 판정 레벨이 안 따라 내려가야
        // 한다 — 따라 내려간다면 방어가 "운영에서 root를 안 내린다"는 사람의 규칙에 걸려 있는 것이다.
        Level rootBefore = levelOf(Logger.ROOT_LOGGER_NAME);
        assertThat(rootBefore).as("root에 명시 레벨이 없으면 아래 복원이 상속을 지운다").isNotNull();
        try {
            setLevel(Logger.ROOT_LOGGER_NAME, Level.TRACE);
            for (String logger : STORAGE_QUIET_LOGGERS) {
                assertThat(effectiveLevelOf(logger).toInt())
                        .as(logger + "가 root=TRACE를 그대로 물려받았다 — 세 줄 중 이 줄이 없다")
                        .isGreaterThanOrEqualTo(Level.INFO.toInt());
            }
        } finally {
            setLevel(Logger.ROOT_LOGGER_NAME, rootBefore);
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

    /**
     * 서명의 <b>첫</b> 글자를 바꿔 깨뜨린다.
     *
     * <p><b>마지막 글자를 쓰면 안 된다.</b> HMAC-SHA256 서명 32바이트를 base64url로
     * 인코딩하면 43글자인데(43×6 = 258 &gt; 256), 마지막 글자는 유효 비트가 4개뿐이다.
     * 그래서 인코더가 마지막 자리에 만드는 값은 16개(0 4 8 A E I M Q U Y c g k o s w)로
     * 제한되고 {@code B}는 아예 나오지 않는다. 마지막이 {@code A}(0000)일 때
     * {@code B}(000001)로 바꾸면 상위 4비트가 같아 <b>같은 32바이트로 디코딩</b>되고,
     * 서명이 유효한 채 남아 이 테스트가 약 16분의 1로 헛통과한다(실측).
     *
     * <p>첫 글자는 유효 비트 6개를 다 쓰므로 다른 문자로 바꾸면 반드시 바이트가 달라진다.
     */
    private static String tamperSignature(String jwt) {
        int signatureStart = jwt.lastIndexOf('.') + 1;
        char first = jwt.charAt(signatureStart);

        return jwt.substring(0, signatureStart)
                + (first == 'A' ? 'B' : 'A')
                + jwt.substring(signatureStart + 1);
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

    /** 명시 레벨만 돌려준다. 안 박혀 있으면 null이다(부모에서 물려받는 상태). */
    private static Level levelOf(String loggerName) {
        return ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loggerName)).getLevel();
    }

    /** 상속까지 반영한 실제 판정 레벨. 명시 레벨이 없으면 부모(끝은 root) 값이 온다. */
    private static Level effectiveLevelOf(String loggerName) {
        return ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loggerName)).getEffectiveLevel();
    }

    private static void assertNoSecretsIn(LogCaptor captor, List<String> secrets) {
        assertNoSecretsIn(renderAll(captor), secrets);
    }

    /** 모인 로그 전부를 한 덩어리 문자열로. 딸려 붙은 예외까지 포함한다. */
    private static String renderAll(LogCaptor captor) {
        return captor.events().stream()
                .map(SecretLeakTest::renderFully)
                .collect(Collectors.joining("\n"));
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
