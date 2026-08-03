package com.pokeclip.auth;

import ch.qos.logback.classic.Level;
import com.pokeclip.auth.google.GoogleIdTokenVerifier;
import com.pokeclip.auth.google.GoogleTokenClient;
import com.pokeclip.auth.google.GoogleUser;
import com.pokeclip.auth.token.RefreshTokenRepository;
import com.pokeclip.auth.token.TokenPair;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.support.IntegrationTestSupport;
import com.pokeclip.auth.support.LogCaptor;
import com.pokeclip.web.RequestIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

class AuthEventLoggingTest extends IntegrationTestSupport {

    private final AuthService authService;
    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;

    @MockitoBean GoogleTokenClient googleTokenClient;
    @MockitoBean GoogleIdTokenVerifier googleIdTokenVerifier;

    // 스파이라 스텁하지 않은 테스트에서는 진짜 인코더가 그대로 돈다.
    @MockitoSpyBean JwtEncoder jwtEncoder;

    AuthEventLoggingTest(AuthService authService, TokenService tokenService,
                         UserRepository userRepository,
                         RefreshTokenRepository refreshTokenRepository,
                         JdbcTemplate jdbcTemplate,
                         PlatformTransactionManager transactionManager) {
        this.authService = authService;
        this.tokenService = tokenService;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionManager = transactionManager;
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

    /**
     * rotated는 커밋 뒤에 찍힌다. 그 자리에서도 MDC가 살아 있어야 상관 ID를 잃지
     * 않는다 — afterCommit이 같은 스레드에서 동기로 돌기 때문인데, 이게 이 방식을
     * 고른 이유이므로 단언으로 묶어 둔다. 여기서는 필터 대신 직접 MDC를 채운다.
     */
    @Test
    void 회전에_성공하면_상관_ID를_단_INFO로_남는다() {
        TokenPair issued = authService.loginWithGoogle("code-1");

        try (LogCaptor captor = new LogCaptor()) {
            MDC.put(RequestIdFilter.MDC_KEY, "trace-rotate");
            try {
                tokenService.rotate(issued.refreshToken());
            } finally {
                MDC.remove(RequestIdFilter.MDC_KEY);
            }

            assertThat(captor.levelOf("auth.token.rotated")).isEqualTo(Level.INFO);
            assertThat(captor.mdcOf("auth.token.rotated", RequestIdFilter.MDC_KEY))
                    .as("커밋 뒤에 찍으면서 상관 ID를 잃었다")
                    .isEqualTo("trace-rotate");
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
     * 이미 죽은 토큰으로 로그아웃하면 끊긴 세션이 0개다. 그때도 로그가 남으면
     * 재시도·버튼 두 번 클릭이 로그아웃 사건 두 건으로 부풀려진다.
     */
    @Test
    void 이미_취소된_토큰으로_로그아웃하면_남지_않는다() {
        TokenPair issued = authService.loginWithGoogle("code-1");
        tokenService.logout(issued.refreshToken());

        try (LogCaptor captor = new LogCaptor()) {
            tokenService.logout(issued.refreshToken());

            assertThat(captor.levelOf("auth.logout")).isNull();
        }
    }

    /**
     * 커밋되지 않았으면 실제로 끊긴 세션이 없다. 그때 남은 auth.logout 한 줄은
     * "로그아웃했는데 왜 옛 토큰이 먹히냐" 조사에서 거짓 알리바이가 된다.
     *
     * <p>롤백을 만드는 지점으로 바깥 트랜잭션을 고른 이유: logout은 revoke 뒤에
     * 부르는 코드가 없어, 회전 쪽처럼 커밋 전 구간에 예외를 끼워 넣을 자리가 없다.
     * 검사하려는 것이 커밋 여부 하나뿐이라 이것으로 충분하다.
     */
    @Test
    void 로그아웃이_롤백되면_남지_않는다() {
        TokenPair issued = authService.loginWithGoogle("code-1");

        try (LogCaptor captor = new LogCaptor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                tokenService.logout(issued.refreshToken());
                status.setRollbackOnly();
            });

            assertThat(captor.levelOf("auth.logout")).isNull();
        }
    }

    /**
     * 회전이 롤백되면 실제로는 아무것도 회전되지 않았다. 그때 남은 rotated 한 줄은
     * 재시도가 성공한 뒤 같은 회전에 대해 두 줄이 되고, 나중에 reuse_detected가
     * 뜨면 그 중복이 리플레이처럼 보여 보안 사건 증거를 부풀린다.
     *
     * <p>롤백을 만드는 지점으로 JwtEncoder를 고른 이유: issue()가 INSERT를 낸
     * 다음, 아직 커밋 전에 부르는 자리다. 실제 위험(INSERT의 DataAccessException)과
     * 같은 구간이면서 리포지토리를 통째로 감쌀 필요가 없다.
     */
    @Test
    void 회전이_롤백되면_rotated가_남지_않는다() {
        TokenPair issued = authService.loginWithGoogle("code-1");
        willThrow(new IllegalStateException("커밋 전 실패")).given(jwtEncoder).encode(any());

        try (LogCaptor captor = new LogCaptor()) {
            assertThatThrownBy(() -> tokenService.rotate(issued.refreshToken()))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(captor.levelOf("auth.token.rotated")).isNull();
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
