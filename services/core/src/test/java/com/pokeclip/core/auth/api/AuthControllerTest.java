package com.pokeclip.core.auth.api;

import com.pokeclip.core.auth.AuthException;
import com.pokeclip.core.auth.AuthFailure;
import com.pokeclip.core.auth.google.GoogleIdTokenVerifier;
import com.pokeclip.core.auth.google.GoogleTokenClient;
import com.pokeclip.core.auth.google.GoogleUser;
import com.pokeclip.core.support.IntegrationTestSupport;
import com.pokeclip.core.auth.token.RefreshTokenRepository;
import com.pokeclip.core.auth.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AuthControllerTest extends IntegrationTestSupport {

    private final MockMvc mockMvc;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    // 목 빈은 컨텍스트 수준에서 교체되므로 필드로 둔다. 생성자 주입 대상이 아니다.
    @MockitoBean GoogleTokenClient googleTokenClient;
    @MockitoBean GoogleIdTokenVerifier googleIdTokenVerifier;

    AuthControllerTest(MockMvc mockMvc, UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository) {
        this.mockMvc = mockMvc;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
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
        given(googleTokenClient.exchangeCodeForIdToken("code-1")).willReturn("id-token-1");
        given(googleIdTokenVerifier.verify("id-token-1"))
                .willReturn(new GoogleUser("sub-1", "a@example.com", "김태현", null));

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"code":"code-1"}
                                 """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists());

        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void 잘못된_구글_코드는_401이고_계정이_생기지_않는다() throws Exception {
        willThrow(new AuthException(AuthFailure.GOOGLE_TOKEN_EXCHANGE_FAILED, "구글 토큰 교환 실패"))
                .given(googleTokenClient).exchangeCodeForIdToken(any());

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"code":"expired-code"}
                                 """))
                .andExpect(status().isUnauthorized());

        assertThat(userRepository.count()).isZero();
    }

    @Test
    void code가_비어_있으면_400이다() throws Exception {
        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"code":""}
                                 """))
                .andExpect(status().isBadRequest());
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
                .given(googleTokenClient).exchangeCodeForIdToken(any());

        // 핸들러가 안 잡으므로 MockMvc가 예외를 그대로 올려보낸다. 상태 코드가
        // 아니라 예외로 오는 것 자체가 401이 아니라는 증거다.
        assertThatThrownBy(() -> mockMvc.perform(post("/api/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"code":"code-1"}
                         """)))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void access_토큰으로_내_정보를_조회한다() throws Exception {
        given(googleTokenClient.exchangeCodeForIdToken("code-1")).willReturn("id-token-1");
        given(googleIdTokenVerifier.verify("id-token-1"))
                .willReturn(new GoogleUser("sub-1", "a@example.com", "김태현", "https://img/1.png"));

        String body = mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"code":"code-1"}
                                 """))
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
                .given(googleTokenClient).exchangeCodeForIdToken(any());

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"code":"expired-code"}
                                 """))
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
        given(googleTokenClient.exchangeCodeForIdToken("code-" + sub)).willReturn("id-" + sub);
        given(googleIdTokenVerifier.verify("id-" + sub))
                .willReturn(new GoogleUser(sub, sub + "@example.com", "사용자", null));

        String body = mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"code-" + sub + "\"}"))
                .andReturn().getResponse().getContentAsString();

        return new TokenResponsePair(
                com.jayway.jsonpath.JsonPath.read(body, "$.accessToken"),
                com.jayway.jsonpath.JsonPath.read(body, "$.refreshToken"));
    }

    private record TokenResponsePair(String accessToken, String refreshToken) {
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"));
    }
}
