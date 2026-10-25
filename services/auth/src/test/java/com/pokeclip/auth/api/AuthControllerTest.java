package com.pokeclip.auth.api;

import com.pokeclip.auth.AuthException;
import com.pokeclip.auth.AuthFailure;
import com.pokeclip.auth.google.GoogleIdTokenVerifier;
import com.pokeclip.auth.google.GoogleTokenClient;
import com.pokeclip.auth.google.GoogleUser;
import com.pokeclip.auth.support.IntegrationTestSupport;
import com.pokeclip.auth.token.RefreshTokenRepository;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AuthControllerTest extends IntegrationTestSupport {

    /** RFC 7636 부록 B의 verifier — 길이(43)·문자 집합이 형식 검사를 통과하는 표준 값이다. */
    private static final String CODE_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

    private final MockMvc mockMvc;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserService userService;

    // 목 빈은 컨텍스트 수준에서 교체되므로 필드로 둔다. 생성자 주입 대상이 아니다.
    @MockitoBean GoogleTokenClient googleTokenClient;
    @MockitoBean GoogleIdTokenVerifier googleIdTokenVerifier;

    AuthControllerTest(MockMvc mockMvc, UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository, UserService userService) {
        this.mockMvc = mockMvc;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userService = userService;
    }

    @BeforeEach
    void setUp() {
        // refresh_tokens가 users를 참조한다. 자식을 먼저 지우지 않으면
        // 앞선 로그인 테스트가 남긴 행 때문에 FK 제약에 걸린다.
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void 구글_코드로_로그인하면_토큰_두_개가_내려온다() throws Exception {
        given(googleTokenClient.exchangeCodeForIdToken("code-1", CODE_VERIFIER))
                .willReturn("id-token-1");
        given(googleIdTokenVerifier.verify("id-token-1"))
                .willReturn(new GoogleUser("sub-1", "a@example.com", "김태현", null));

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("code-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists());

        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void 잘못된_구글_코드는_401이고_계정이_생기지_않는다() throws Exception {
        willThrow(new AuthException(AuthFailure.GOOGLE_TOKEN_EXCHANGE_FAILED, "구글 토큰 교환 실패"))
                .given(googleTokenClient).exchangeCodeForIdToken(any(), any());

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("expired-code")))
                .andExpect(status().isUnauthorized());

        assertThat(userRepository.count()).isZero();
    }

    @Test
    void code가_비어_있으면_400이다() throws Exception {
        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("")))
                .andExpect(status().isBadRequest());
    }

    /**
     * PKCE 없는 교환은 받지 않는다(POK-483). web과 auth가 같은 배포로 나가 과도기 허용이 필요 없다.
     * 400인 이유: 자격이 틀린 것이 아니라 요청 모양이 계약과 다르다. 구글까지 가지도 않는다.
     */
    @Test
    void codeVerifier가_없으면_400이고_구글에_가지_않는다() throws Exception {
        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"code":"code-1"}
                                 """))
                .andExpect(status().isBadRequest());

        then(googleTokenClient).should(never()).exchangeCodeForIdToken(any(), any());
    }

    /** RFC 7636 4.1 — 43–128자, unreserved 문자([A-Za-z0-9-._~])만. */
    @ParameterizedTest
    @MethodSource("malformedVerifiers")
    void codeVerifier_형식이_RFC_7636과_다르면_400이고_구글에_가지_않는다(String verifier) throws Exception {
        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"code-1\",\"codeVerifier\":\"" + verifier + "\"}"))
                .andExpect(status().isBadRequest());

        then(googleTokenClient).should(never()).exchangeCodeForIdToken(any(), any());
    }

    static Stream<String> malformedVerifiers() {
        return Stream.of(
                "a".repeat(42), // 하한보다 한 글자 짧다
                "a".repeat(129), // 상한보다 한 글자 길다
                CODE_VERIFIER.substring(1) + "+", // base64의 +·/·=는 unreserved가 아니다
                CODE_VERIFIER.substring(1) + "=",
                CODE_VERIFIER.substring(1) + " ");
    }

    @Test
    void 정상_요청은_code와_codeVerifier를_그대로_토큰_교환에_넘긴다() throws Exception {
        String longest = "a".repeat(128); // 상한 그대로는 받는다
        given(googleTokenClient.exchangeCodeForIdToken("code-1", longest)).willReturn("id-token-1");
        given(googleIdTokenVerifier.verify("id-token-1"))
                .willReturn(new GoogleUser("sub-1", "a@example.com", "김태현", null));

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"code-1\",\"codeVerifier\":\"" + longest + "\"}"))
                .andExpect(status().isOk());

        then(googleTokenClient).should().exchangeCodeForIdToken("code-1", longest);
    }

    @Test
    void 토큰_없이_보호된_API를_부르면_401이다() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 헬스체크는 토큰 없이 열려 있어야 한다. 로드밸런서·ECS가 토큰을 들고 오지
     * 않으므로, 막히면 컨테이너가 계속 unhealthy로 판정돼 배포가 롤백된다.
     * 실제로 actuator를 넣으면서 한 번 401이 됐다 — 그래서 못박는다.
     */
    @Test
    void 헬스체크는_토큰_없이_열려_있다() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    /**
     * 인증과 무관한 고장이 401로 둔갑하면 안 된다. 401은 "자격이 없다"는 뜻인데
     * 서버가 깨진 것을 그렇게 알리면, 클라이언트는 재로그인을 반복하고 장애는
     * 인증 문제로 오진된다. 핸들러가 AuthException만 잡는지 못박는다.
     */
    @Test
    void 인증과_무관한_예외는_401로_새지_않는다() {
        willThrow(new IllegalStateException("구글 응답 파싱이 깨졌다"))
                .given(googleTokenClient).exchangeCodeForIdToken(any(), any());

        // 핸들러가 안 잡으므로 MockMvc가 예외를 그대로 올려보낸다. 상태 코드가
        // 아니라 예외로 오는 것 자체가 401이 아니라는 증거다.
        assertThatThrownBy(() -> mockMvc.perform(post("/api/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("code-1"))))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void access_토큰으로_내_정보를_조회한다() throws Exception {
        given(googleTokenClient.exchangeCodeForIdToken("code-1", CODE_VERIFIER))
                .willReturn("id-token-1");
        given(googleIdTokenVerifier.verify("id-token-1"))
                .willReturn(new GoogleUser("sub-1", "a@example.com", "김태현", "https://img/1.png"));

        String body = mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("code-1")))
                .andReturn().getResponse().getContentAsString();
        String accessToken = com.jayway.jsonpath.JsonPath.read(body, "$.accessToken");

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("a@example.com"))
                .andExpect(jsonPath("$.name").value("김태현"))
                .andExpect(jsonPath("$.profileImageUrl").value("https://img/1.png"));
    }

    @Test
    void 망가진_access_토큰이면_401이다() throws Exception {
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 인증_실패_본문은_이유를_알려주지_않는다() throws Exception {
        willThrow(new AuthException(AuthFailure.REFRESH_TOKEN_EXPIRED, "만료된 refresh 토큰이다"))
                .given(googleTokenClient).exchangeCodeForIdToken(any(), any());

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("expired-code")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("인증에 실패했습니다"));
    }

    @Test
    void refresh_토큰으로_새_토큰_쌍을_받는다() throws Exception {
        TokenResponsePair issued = login("sub-refresh");

        refresh(issued.refreshToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists());
    }

    @Test
    void 재발급에_쓴_refresh는_두_번째부터_401이다() throws Exception {
        TokenResponsePair issued = login("sub-reuse");

        refresh(issued.refreshToken()).andExpect(status().isOk());
        refresh(issued.refreshToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void 로그아웃한_refresh로는_재발급할_수_없다() throws Exception {
        TokenResponsePair issued = login("sub-logout");

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + issued.refreshToken() + "\"}"))
                .andExpect(status().isNoContent());

        refresh(issued.refreshToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void 없는_refresh로_로그아웃해도_204다() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"refreshToken":"never-issued"}
                                 """))
                .andExpect(status().isNoContent());
    }

    private TokenResponsePair login(String sub) throws Exception {
        given(googleTokenClient.exchangeCodeForIdToken("code-" + sub, CODE_VERIFIER))
                .willReturn("id-" + sub);
        given(googleIdTokenVerifier.verify("id-" + sub))
                .willReturn(new GoogleUser(sub, sub + "@example.com", "사용자", null));

        String body = mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("code-" + sub)))
                .andReturn().getResponse().getContentAsString();

        return new TokenResponsePair(
                com.jayway.jsonpath.JsonPath.read(body, "$.accessToken"),
                com.jayway.jsonpath.JsonPath.read(body, "$.refreshToken"));
    }

    private record TokenResponsePair(String accessToken, String refreshToken) {
    }

    private static String loginBody(String code) {
        return "{\"code\":\"" + code + "\",\"codeVerifier\":\"" + CODE_VERIFIER + "\"}";
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"));
    }

    /**
     * 같은 이메일에 <b>다른 구글 계정</b>이 로그인하면 정중히 거절한다(사용자 결정 2026-08-18).
     * 구글 계정을 지웠다 같은 주소로 다시 만들면 sub가 바뀌어 이 경로에 온다.
     *
     * <p>V108의 uq_users_email이 열었고, 고치기 전에는 <b>500</b>이었다 —
     * AuthService.loginWithGoogle에 catch가 없고 DataIntegrityViolationException을
     * 다루는 핸들러도 없었다. 409인 이유는 요청 자체는 유효한데 현재 상태와 충돌해서다.
     *
     * <p><b>이메일은 응답에도 로그에도 안 실린다.</b> 고객 문의가 왔을 때 로그의 사유 코드만
     * 보고 무슨 일인지 알 수 있어야 하되, 주소가 파일에 쌓이면 안 된다.
     * Hibernate가 던지기 전에 찍던 WARN까지 같이 막혔는지를 LogCaptor로 확인한다.
     */
    @Test
    void 같은_이메일에_다른_구글_계정이_로그인하면_409고_이메일이_안_샌다() throws Exception {
        String email = "taken@example.com";
        userService.findOrCreate("sub-original", email, "먼저", null);
        given(googleTokenClient.exchangeCodeForIdToken("code-dup", CODE_VERIFIER))
                .willReturn("id-dup");
        given(googleIdTokenVerifier.verify("id-dup"))
                .willReturn(new GoogleUser("sub-different", email, "나중", null));

        String body;
        try (LogCaptor logs = new LogCaptor()) {
            body = mockMvc.perform(post("/api/auth/google")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("code-dup")))
                    .andExpect(status().isConflict())
                    .andReturn().getResponse().getContentAsString();

            assertThat(logs.messages()).noneMatch(m -> m.contains(email));
            assertThat(logs.messages()).anyMatch(m -> m.contains("EMAIL_ALREADY_REGISTERED"));
        }

        assertThat(body).doesNotContain(email);
        assertThat(userRepository.count()).isEqualTo(1);
    }

    /**
     * 이메일 인증을 안 마친 구글 계정은 403으로 이유를 알려 주고 회원을 만들지 않는다(POK-256).
     * 401로 접으면 사용자는 무엇을 고칠지 모른 채 다시 누르기만 한다.
     */
    @Test
    void 이메일_인증을_안_마친_계정은_403이고_회원을_안_만든다() throws Exception {
        given(googleTokenClient.exchangeCodeForIdToken("code-unverified", CODE_VERIFIER))
                .willReturn("id-unverified");
        willThrow(new AuthException(AuthFailure.GOOGLE_EMAIL_UNVERIFIED, "미인증"))
                .given(googleIdTokenVerifier).verify("id-unverified");

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("code-unverified")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("GOOGLE_EMAIL_UNVERIFIED"));

        assertThat(userRepository.count()).isZero();
    }
}
