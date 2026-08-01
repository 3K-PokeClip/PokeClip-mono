package com.pokeclip.core.auth;

import ch.qos.logback.classic.Level;
import com.pokeclip.core.auth.google.GoogleIdTokenVerifier;
import com.pokeclip.core.auth.google.GoogleTokenClient;
import com.pokeclip.core.auth.google.GoogleUser;
import com.pokeclip.core.auth.token.RefreshTokenRepository;
import com.pokeclip.core.auth.token.TokenPair;
import com.pokeclip.core.auth.token.TokenService;
import com.pokeclip.core.auth.user.UserRepository;
import com.pokeclip.core.support.IntegrationTestSupport;
import com.pokeclip.core.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

class AuthEventLoggingTest extends IntegrationTestSupport {

    private final AuthService authService;
    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JdbcTemplate jdbcTemplate;

    @MockitoBean GoogleTokenClient googleTokenClient;
    @MockitoBean GoogleIdTokenVerifier googleIdTokenVerifier;

    AuthEventLoggingTest(AuthService authService, TokenService tokenService,
                         UserRepository userRepository,
                         RefreshTokenRepository refreshTokenRepository,
                         JdbcTemplate jdbcTemplate) {
        this.authService = authService;
        this.tokenService = tokenService;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();

        given(googleTokenClient.exchangeCodeForIdToken("code-1")).willReturn("id-1");
        given(googleIdTokenVerifier.verify("id-1"))
                .willReturn(new GoogleUser("sub-1", "a@example.com", "김태현", null));
    }

    /**
     * 자식 테이블 행을 남기면 다른 테스트의 부모 정리를 FK가 막는다.
     * TokenServiceTest가 같은 이유로 같은 정리를 한다. core/CLAUDE.md의 함정이다.
     * 지금 이게 없어도 초록불인 것은 클래스 실행 순서상 TokenServiceTest의
     * @AfterEach가 앞에 오는 우연 덕이고, 클래스를 새로 넣으면 그 우연이 깨진다.
     */
    @AfterEach
    void tearDown() {
        refreshTokenRepository.deleteAll();
    }

    @Test
    void 로그인에_성공하면_INFO로_남는다() {
        try (LogCaptor captor = new LogCaptor()) {
            authService.loginWithGoogle("code-1");

            assertThat(captor.levelOf("auth.login.success")).isEqualTo(Level.INFO);
            assertThat(captor.levelOf("auth.user.created")).isEqualTo(Level.INFO);
        }
    }

    @Test
    void 회전에_성공하면_INFO로_남는다() {
        TokenPair issued = authService.loginWithGoogle("code-1");

        try (LogCaptor captor = new LogCaptor()) {
            tokenService.rotate(issued.refreshToken());

            assertThat(captor.levelOf("auth.token.rotated")).isEqualTo(Level.INFO);
        }
    }

    @Test
    void 로그아웃하면_INFO로_남는다() {
        TokenPair issued = authService.loginWithGoogle("code-1");

        try (LogCaptor captor = new LogCaptor()) {
            tokenService.logout(issued.refreshToken());

            assertThat(captor.levelOf("auth.logout")).isEqualTo(Level.INFO);
        }
    }

    /**
     * 유예 창(10초)을 넘긴 재사용만 탈취로 다룬다. 테스트에서 10초를 기다릴 수
     * 없으므로 revoked_at을 과거로 돌린다. 이렇게 안 하면 REFRESH_TOKEN_REUSED가
     * 아니라 REFRESH_TOKEN_ALREADY_ROTATED 경로로 빠져 이 테스트가 거짓 통과한다.
     */
    @Test
    void 재사용을_감지하면_WARN으로_끊긴_세션_수까지_남는다() {
        TokenPair issued = authService.loginWithGoogle("code-1");
        tokenService.rotate(issued.refreshToken());
        // 기존 TokenServiceTest.ageRevokedTokens()와 같은 값·같은 WHERE를 쓴다.
        // WHERE가 없으면 테이블 전체·전 사용자가 대상이 돼 병렬 실행에서 깨지고,
        // 1분은 유예 창 10초 대비 여유가 6배뿐이다(기존 헬퍼는 360배).
        jdbcTemplate.update("""
                UPDATE refresh_tokens SET revoked_at = revoked_at - INTERVAL '1 hour'
                WHERE revoked_at IS NOT NULL
                """);

        try (LogCaptor captor = new LogCaptor()) {
            assertThatThrownBy(() -> tokenService.rotate(issued.refreshToken()))
                    .isInstanceOf(AuthException.class);

            assertThat(captor.levelOf("auth.token.reuse_detected")).isEqualTo(Level.WARN);
            assertThat(captor.messages())
                    .anyMatch(m -> m.startsWith("auth.token.reuse_detected") && m.contains("revokedSessions="));
        }
    }

    /** 로그에 남기는 식별자는 userId뿐이다. 유출 시 피해가 토큰과 같은 성질이다. */
    @Test
    void 이메일과_이름은_로그에_남지_않는다() {
        try (LogCaptor captor = new LogCaptor()) {
            authService.loginWithGoogle("code-1");

            String all = String.join("\n", captor.messages());
            assertThat(all).doesNotContain("a@example.com");
            assertThat(all).doesNotContain("김태현");
            assertThat(all).doesNotContain("sub-1");
        }
    }
}
