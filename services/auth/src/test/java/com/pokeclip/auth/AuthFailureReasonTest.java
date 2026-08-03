package com.pokeclip.auth;

import com.pokeclip.auth.google.GoogleIdTokenVerifier;
import com.pokeclip.auth.google.GoogleTokenClient;
import com.pokeclip.auth.google.GoogleUser;
import com.pokeclip.auth.token.RefreshTokenRepository;
import com.pokeclip.auth.token.TokenPair;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

/**
 * 프로덕션 경로를 실제로 태워 붙는 사유를 본다. 사유가 틀리면 레벨이 틀리고,
 * 레벨이 틀리면 PRD가 정한 알람 정책이 조용히 깨진다.
 */
class AuthFailureReasonTest extends IntegrationTestSupport {

    private final AuthService authService;
    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JdbcTemplate jdbcTemplate;

    @MockitoBean GoogleTokenClient googleTokenClient;
    @MockitoBean GoogleIdTokenVerifier googleIdTokenVerifier;

    AuthFailureReasonTest(AuthService authService, TokenService tokenService,
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
     */
    @AfterEach
    void tearDown() {
        refreshTokenRepository.deleteAll();
    }

    @Test
    void 모르는_refresh는_REFRESH_TOKEN_UNKNOWN이다() {
        assertThatThrownBy(() -> tokenService.rotate("never-issued"))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).failure())
                .isEqualTo(AuthFailure.REFRESH_TOKEN_UNKNOWN);
    }

    /** 유예 창 안이라 정상 동작이다. 이 값이 틀리면 정상 트래픽이 WARN을 채운다. */
    @Test
    void 유예_창_안의_중복_회전은_REFRESH_TOKEN_ALREADY_ROTATED다() {
        TokenPair issued = authService.loginWithGoogle("code-1");
        tokenService.rotate(issued.refreshToken());

        assertThatThrownBy(() -> tokenService.rotate(issued.refreshToken()))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).failure())
                .isEqualTo(AuthFailure.REFRESH_TOKEN_ALREADY_ROTATED);
    }

    /** 유예 창을 넘긴 재사용만 탈취로 다룬다. */
    @Test
    void 유예_창을_넘긴_재사용은_REFRESH_TOKEN_REUSED다() {
        TokenPair issued = authService.loginWithGoogle("code-1");
        tokenService.rotate(issued.refreshToken());
        ageRevokedTokens();

        assertThatThrownBy(() -> tokenService.rotate(issued.refreshToken()))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).failure())
                .isEqualTo(AuthFailure.REFRESH_TOKEN_REUSED);
    }

    @Test
    void 구글_토큰_교환_실패는_GOOGLE_TOKEN_EXCHANGE_FAILED다() {
        willThrow(new AuthException(AuthFailure.GOOGLE_TOKEN_EXCHANGE_FAILED, "구글 토큰 교환 실패"))
                .given(googleTokenClient).exchangeCodeForIdToken("bad-code");

        assertThatThrownBy(() -> authService.loginWithGoogle("bad-code"))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).failure())
                .isEqualTo(AuthFailure.GOOGLE_TOKEN_EXCHANGE_FAILED);
    }

    /** 서명은 통과했는데 주인이 없다. 인증 실패가 아니라 데이터 불일치다. */
    @Test
    void 주인_없는_토큰은_USER_NOT_FOUND이고_데이터_불일치다() {
        Long goneUserId = 999_999L;

        assertThatThrownBy(() -> authService.me(goneUserId))
                .isInstanceOf(DataInconsistencyException.class)
                .extracting(e -> ((AuthException) e).failure())
                .isEqualTo(AuthFailure.USER_NOT_FOUND);
    }

    /** 기존 TokenServiceTest.ageRevokedTokens()와 같은 값을 쓴다. */
    private void ageRevokedTokens() {
        jdbcTemplate.update("""
                UPDATE refresh_tokens SET revoked_at = revoked_at - INTERVAL '1 hour'
                WHERE revoked_at IS NOT NULL
                """);
    }
}
