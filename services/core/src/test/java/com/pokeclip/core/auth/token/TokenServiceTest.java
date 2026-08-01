package com.pokeclip.core.auth.token;

import com.pokeclip.core.auth.AuthException;
import com.pokeclip.core.support.IntegrationTestSupport;
import com.pokeclip.core.auth.user.User;
import com.pokeclip.core.auth.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenServiceTest extends IntegrationTestSupport {

    private final TokenService tokenService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserService userService;
    private final JwtDecoder jwtDecoder;
    private final JdbcTemplate jdbc;

    TokenServiceTest(TokenService tokenService, RefreshTokenRepository refreshTokenRepository,
                     UserService userService, JwtDecoder jwtDecoder, JdbcTemplate jdbc) {
        this.tokenService = tokenService;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userService = userService;
        this.jwtDecoder = jwtDecoder;
        this.jdbc = jdbc;
    }

    /**
     * 회전 시각을 유예 창 밖으로 민다. 탈취범은 방금 회전된 토큰이 아니라 한참
     * 전에 쓴 토큰을 들고 오므로, 재사용 감지를 검증하려면 그 조건을 만들어야 한다.
     * 유예 창 안의 재제출은 중복 요청으로 취급되고 세션을 끊지 않는다.
     */
    private void ageRevokedTokens() {
        jdbc.update("UPDATE refresh_tokens SET revoked_at = revoked_at - INTERVAL '1 hour' "
                + "WHERE revoked_at IS NOT NULL");
    }

    User user;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        user = userService.findOrCreate("sub-1", "a@example.com", "김태현", null);
    }

    /**
     * 남긴 refresh_tokens를 치운다. 다른 테스트 클래스가 users를 비울 때
     * refresh_tokens가 그 행을 참조하고 있으면 FK 제약에 걸린다.
     */
    @AfterEach
    void tearDown() {
        refreshTokenRepository.deleteAll();
    }

    @Test
    void access_토큰에_사용자_id가_담긴다() {
        TokenPair pair = tokenService.issue(user);

        Jwt jwt = jwtDecoder.decode(pair.accessToken());
        assertThat(jwt.getSubject()).isEqualTo(String.valueOf(user.getId()));
    }

    @Test
    void refresh_토큰은_원문이_아니라_해시로_저장된다() {
        TokenPair pair = tokenService.issue(user);

        var saved = refreshTokenRepository.findAll();
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getTokenHash()).isNotEqualTo(pair.refreshToken());
        assertThat(saved.getFirst().getTokenHash()).hasSize(64);
    }

    @Test
    void refresh로_새_토큰_쌍을_받는다() {
        TokenPair issued = tokenService.issue(user);

        TokenPair rotated = tokenService.rotate(issued.refreshToken());

        assertThat(rotated.refreshToken()).isNotEqualTo(issued.refreshToken());
        assertThat(jwtDecoder.decode(rotated.accessToken()).getSubject())
                .isEqualTo(String.valueOf(user.getId()));
    }

    @Test
    void 회전하면_직전_refresh는_더_이상_안_먹는다() {
        TokenPair issued = tokenService.issue(user);
        tokenService.rotate(issued.refreshToken());

        assertThatThrownBy(() -> tokenService.rotate(issued.refreshToken()))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void 이미_쓴_refresh를_다시_보내면_그_사용자의_모든_refresh가_무효화된다() {
        TokenPair first = tokenService.issue(user);
        TokenPair second = tokenService.rotate(first.refreshToken());
        ageRevokedTokens();

        // 탈취된 옛 토큰이 다시 온다
        assertThatThrownBy(() -> tokenService.rotate(first.refreshToken()))
                .isInstanceOf(AuthException.class);

        // 정상 사용자의 최신 토큰까지 함께 끊긴다
        assertThatThrownBy(() -> tokenService.rotate(second.refreshToken()))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void 로그아웃하면_그_refresh로_재발급할_수_없다() {
        TokenPair issued = tokenService.issue(user);

        tokenService.logout(issued.refreshToken());

        assertThatThrownBy(() -> tokenService.rotate(issued.refreshToken()))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void 없는_refresh로_로그아웃해도_예외가_나지_않는다() {
        tokenService.logout("never-issued-token");
    }

    @Test
    void 없는_refresh로_재발급하면_인증_실패다() {
        assertThatThrownBy(() -> tokenService.rotate("never-issued-token"))
                .isInstanceOf(AuthException.class);
    }

    /**
     * 재사용 감지가 도는 사이 다른 요청이 발급한 토큰까지 끊는지 본다.
     *
     * 탈취된 옛 토큰(재사용 감지)과 공격자가 쥔 최신 토큰(정상 회전)을 동시에
     * 낸다. 감지 쪽이 무효화하는 순간 회전 쪽이 새 토큰을 만들면, 그 새 토큰은
     * 감지 쪽 UPDATE의 대상 집합에 들어가지 않는다 — PostgreSQL READ COMMITTED에서
     * UPDATE는 문장 시작 이후 커밋된 행을 다시 훑지 않기 때문이다.
     *
     * 타이밍에 의존하므로 한 번으로는 안 잡힌다. 반복해서 창을 연다.
     */
    @Test
    void 재사용_감지와_정상_회전이_겹쳐도_그_사용자의_refresh가_하나도_안_남는다() throws Exception {
        for (int attempt = 1; attempt <= 30; attempt++) {
            refreshTokenRepository.deleteAll();

            TokenPair stolen = tokenService.issue(user);
            TokenPair attackerHolds = tokenService.rotate(stolen.refreshToken());
            ageRevokedTokens();

            CountDownLatch start = new CountDownLatch(1);
            Callable<Void> reuse = rotateIgnoringAuthFailure(start, stolen.refreshToken());
            Callable<Void> normal = rotateIgnoringAuthFailure(start, attackerHolds.refreshToken());

            try (var pool = Executors.newFixedThreadPool(2)) {
                var futures = List.of(pool.submit(reuse), pool.submit(normal));
                start.countDown();
                for (var f : futures) {
                    f.get();
                }
            }

            long alive = refreshTokenRepository.findAll().stream()
                    .filter(token -> token.getRevokedAt() == null)
                    .count();

            assertThat(alive)
                    .as("%d번째 시도 — 재사용 감지 후 살아남은 refresh", attempt)
                    .isZero();
        }
    }

    /**
     * 같은 토큰으로 동시에 재발급이 들어오는 경우다. SPA의 두 탭이 같은 순간
     * access 만료를 감지하거나, 타임아웃 후 자동 재시도하거나, 버튼을 두 번
     * 누르면 여기 온다. 하나는 거부되는 것이 맞지만, **성공한 쪽이 받은 토큰까지
     * 죽으면 안 된다** — 그러면 정상 사용자가 전 기기에서 로그아웃된다.
     */
    @Test
    void 같은_refresh로_동시에_재발급해도_성공한_쪽의_토큰은_살아_있다() throws Exception {
        for (int attempt = 1; attempt <= 20; attempt++) {
            refreshTokenRepository.deleteAll();
            TokenPair issued = tokenService.issue(user);

            CountDownLatch start = new CountDownLatch(1);
            Callable<TokenPair> rotate = () -> {
                start.await();
                try {
                    return tokenService.rotate(issued.refreshToken());
                } catch (AuthException rejected) {
                    return null;
                }
            };

            List<TokenPair> results = new ArrayList<>();
            try (var pool = Executors.newFixedThreadPool(2)) {
                var futures = List.of(pool.submit(rotate), pool.submit(rotate));
                start.countDown();
                for (var f : futures) {
                    results.add(f.get());
                }
            }

            List<TokenPair> won = results.stream().filter(Objects::nonNull).toList();
            assertThat(won)
                    .as("%d번째 시도 — 둘 중 하나만 성공해야 한다", attempt)
                    .hasSize(1);

            assertThatCode(() -> tokenService.rotate(won.getFirst().refreshToken()))
                    .as("%d번째 시도 — 방금 발급받은 토큰이 죽어 있다", attempt)
                    .doesNotThrowAnyException();
        }
    }

    private Callable<Void> rotateIgnoringAuthFailure(CountDownLatch start, String refreshToken) {
        return () -> {
            start.await();
            try {
                tokenService.rotate(refreshToken);
            } catch (AuthException expected) {
                // 둘 중 하나는 반드시 거부당한다. 무엇이 거부됐는지가 아니라
                // 끝난 뒤 살아있는 토큰이 있는지가 이 테스트의 관심사다.
            }
            return null;
        };
    }
}
