package com.pokeclip.auth.retention;

import com.pokeclip.auth.AuthException;
import com.pokeclip.auth.AuthFailure;
import com.pokeclip.auth.token.TokenPair;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static java.time.temporal.ChronoUnit.DAYS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 🔴 <b>청소가 재사용 감지의 창을 먹지 않는가</b>가 이 클래스의 요점이다(카드 POK-223 완료 조건 2).
 * 회수된 행이 표에 남아 있어야 {@code TokenService.rotate}가 「이미 쓴 토큰이 또 왔다」를 잡는다.
 * 13일 된 행은 청소를 지나도 REUSED로 그 회원 세션이 전부 끊기고, 15일이면 UNKNOWN(401)만이고 봉쇄는 없다.
 * 후자가 잃는 것은 「사용자가 보관 기한 넘게 옛 토큰을 안 냈을 때의 봉쇄」다: 도둑이 회전을 이어간 세션은 만료가 안 오므로
 * (15일 시험의 둘째 단언, 살아있는 최신 토큰이 그대로 도는 것이 그 결과 자체다) 그 뒤 도둑 세션은 봉쇄 밖이다.
 *
 * <p>시계를 하나로 — 행을 심는 시각도 청소의 기준도 시험이 만든 {@code base} 하나다. 도커 VM 시계가 호스트와
 * 어긋나도 경계가 안 흔들린다(DB {@code now()}로 심고 JVM {@code Instant.now()}로 판정하면 시계가 둘이다).
 */
class RetentionCleanerRefreshTokensTest extends RetentionTestSupport {

    private final RetentionCleaner cleaner;
    private final RetentionProperties properties;
    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    RetentionCleanerRefreshTokensTest(RetentionCleaner cleaner, RetentionProperties properties,
                                      TokenService tokenService, UserService userService,
                                      UserRepository userRepository, JdbcTemplate jdbc, DataSource dataSource) {
        super(userService);
        this.cleaner = cleaner;
        this.properties = properties;
        this.tokenService = tokenService;
        this.userRepository = userRepository;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    /** refresh_tokens는 users의 자식이다 — 남기면 다른 시험의 users 정리가 FK로 터진다. */
    @BeforeEach
    @AfterEach
    void clear() {
        jdbc.update("DELETE FROM refresh_tokens");
        userRepository.deleteAll();
    }

    /** 여섯 갈래를 한 표에 심고 한 번 지운다 — 갈래마다 따로 지우면 조건의 OR가 안 재어진다. */
    @Test
    void 회수_뒤_14일_또는_만료_뒤_14일이_지난_행만_사라진다() {
        Instant base = Instant.now();
        User user = newUser();
        seed(user, "revoked-old", base.plus(1, DAYS), base.minus(15, DAYS));
        seed(user, "revoked-recent", base.plus(1, DAYS), base.minus(13, DAYS));
        seed(user, "expired-old", base.minus(15, DAYS), null);
        seed(user, "expired-recent", base.minus(13, DAYS), null);
        seed(user, "alive", base.plus(10, DAYS), null);
        // 여섯째 갈래 — 회수는 13일 전(봉쇄 창 안)인데 만료는 15일 전. 재사용 봉쇄·탈퇴가 만료 뒤에 revoked_at을
        // 찍으면 실제로 생기는 행이다. 조건의 「revoked_at IS NULL AND」를 지우면 이 행만 잘못 지워진다 —
        // 앞 다섯 갈래로는 그 주입이 안 잡힌다(retention-test-reality 주입 표).
        seed(user, "revoked-recent-expired-old", base.minus(15, DAYS), base.minus(13, DAYS));

        RetentionCleaner.Result result = cleaner.cleanRefreshTokens(base);

        assertThat(result.deleted()).isEqualTo(2);
        assertThat(hashes()).containsExactlyInAnyOrder(
                "revoked-recent", "expired-recent", "alive", "revoked-recent-expired-old");
    }

    @Test
    void 회수_뒤_13일이면_청소를_지나도_재사용이_봉쇄된다() {
        Instant base = Instant.now();
        User user = newUser();
        TokenPair stolen = tokenService.issue(user);
        TokenPair latest = tokenService.rotate(stolen.refreshToken());
        ageRevoked(base.minus(13, DAYS));

        cleaner.cleanRefreshTokens(base);

        assertThatThrownBy(() -> tokenService.rotate(stolen.refreshToken()))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).failure())
                .isEqualTo(AuthFailure.REFRESH_TOKEN_REUSED);
        assertThatThrownBy(() -> tokenService.rotate(latest.refreshToken()))
                .as("봉쇄가 그 회원의 최신 토큰에 방금(10초 안) revoked_at을 찍었어야 한다 — 그래서 사유가 ALREADY_ROTATED다")
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).failure())
                .isEqualTo(AuthFailure.REFRESH_TOKEN_ALREADY_ROTATED);
    }

    /**
     * 상한은 표 셋이 각자 건다({@code LIMIT ?}가 SQL 셋에 따로 있다). 그래서 {@code RetentionCleanerAttemptsTest}와
     * 같은 모양으로 이 표에서도 「상한 + 500」을 심는다 — 한 표의 상한 시험이 다른 표의 {@code LIMIT} 누락을 못 잰다.
     */
    @Test
    void 한_번에_상한만큼만_지우고_나머지는_다음_호출이_이어서_지운다() {
        Instant base = Instant.now();
        int limit = properties.batchLimit();
        seedRevoked(newUser(), limit + 500, base.minus(15, DAYS));

        RetentionCleaner.Result first = cleaner.cleanRefreshTokens(base);
        RetentionCleaner.Result second = cleaner.cleanRefreshTokens(base);
        RetentionCleaner.Result third = cleaner.cleanRefreshTokens(base);

        assertThat(first.deleted()).isEqualTo(limit);
        assertThat(first.capped()).as("상한에 걸렸으면 같은 틱이 이어서 부른다는 표시가 있어야 한다").isTrue();
        assertThat(second.deleted()).isEqualTo(500);
        assertThat(second.capped()).isFalse();
        assertThat(third.deleted()).isZero();
        assertThat(hashes()).isEmpty();
    }

    @Test
    void 회수_뒤_15일이면_모르는_토큰일_뿐_다른_세션은_안_끊긴다() {
        Instant base = Instant.now();
        User user = newUser();
        TokenPair stolen = tokenService.issue(user);
        TokenPair latest = tokenService.rotate(stolen.refreshToken());
        ageRevoked(base.minus(15, DAYS));

        cleaner.cleanRefreshTokens(base);

        assertThatThrownBy(() -> tokenService.rotate(stolen.refreshToken()))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).failure())
                .isEqualTo(AuthFailure.REFRESH_TOKEN_UNKNOWN);
        assertThatCode(() -> tokenService.rotate(latest.refreshToken()))
                .as("회수 행이 사라졌으니 봉쇄가 없고, 살아있는 최신 토큰은 그대로 돈다")
                .doesNotThrowAnyException();
    }

    /**
     * 청소 DELETE는 잠긴 행을 기다리지 않는다({@code SKIP LOCKED}). 기다리면 회수 UPDATE({@code revokeAllOfUser})와
     * 잠금 순서가 갈려 데드락이다 — 리뷰 라운드 1 스크래치 재현 60회 중 30회. 기다리지 않으면 사이클이 성립하지 않고,
     * 건너뛴 행은 다음 틱이 지운다. 5초 시한: 수정 전에는 DELETE가 락 해제까지 매달려 {@code TimeoutException}이다.
     * 잠그는 쪽은 풀에서 따로 꺼낸 커넥션이다 — 같은 트랜잭션 안에서는 자기 락이라 안 기다린다.
     */
    @Test
    void 다른_트랜잭션이_잠근_행은_기다리지_않고_건너뛴다() throws Exception {
        Instant base = Instant.now();
        User user = newUser();
        seed(user, "locked", base.plus(1, DAYS), base.minus(15, DAYS));
        seed(user, "free-1", base.plus(1, DAYS), base.minus(15, DAYS));
        seed(user, "free-2", base.plus(1, DAYS), base.minus(15, DAYS));

        Connection locker = dataSource.getConnection();
        try {
            locker.setAutoCommit(false);
            try (PreparedStatement lock = locker.prepareStatement(
                    "SELECT id FROM refresh_tokens WHERE token_hash = ? FOR UPDATE")) {
                lock.setString(1, "locked");
                lock.executeQuery();
            }

            RetentionCleaner.Result result = CompletableFuture
                    .supplyAsync(() -> cleaner.cleanRefreshTokens(base))
                    .get(5, TimeUnit.SECONDS);

            assertThat(result.deleted()).isEqualTo(2);
            assertThat(hashes()).as("잠긴 행은 남고 나머지만 지워져야 한다").containsExactly("locked");
        } finally {
            locker.rollback();
            locker.close();
        }
    }

    /** {@code created_at}은 청소가 안 본다 — 만료 20일 전이면 어느 갈래에서도 회수·만료보다 앞선다. */
    private void seed(User user, String hash, Instant expiresAt, Instant revokedAt) {
        jdbc.update("INSERT INTO refresh_tokens (user_id, token_hash, expires_at, revoked_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                user.getId(), hash, Timestamp.from(expiresAt),
                revokedAt == null ? null : Timestamp.from(revokedAt), Timestamp.from(expiresAt.minus(20, DAYS)));
    }

    /** 회수 뒤 보관 기간 밖인 행을 여럿 심는다. 해시는 {@code 'stale-' || g}로 행마다 갈라 UNIQUE에 안 걸리게 한다. */
    private void seedRevoked(User user, int rows, Instant revokedAt) {
        jdbc.update("INSERT INTO refresh_tokens (user_id, token_hash, expires_at, revoked_at, created_at) "
                        + "SELECT ?, 'stale-' || g, ?, ?, ? FROM generate_series(1, ?) g",
                user.getId(), Timestamp.from(revokedAt.plus(16, DAYS)), Timestamp.from(revokedAt),
                Timestamp.from(revokedAt.minus(5, DAYS)), rows);
    }

    /**
     * 회전으로 생긴 회수 시각을 과거로 민다. {@code TokenServiceTest.ageRevokedTokens}와 같은 표를 고치지만 SQL이 다르다:
     * 기존 넷은 −1시간 상대 이동(유예 창 넘기기), 여기는 절대 시각 설정(13·15일 경계).
     */
    private void ageRevoked(Instant revokedAt) {
        jdbc.update("UPDATE refresh_tokens SET revoked_at = ? WHERE revoked_at IS NOT NULL", Timestamp.from(revokedAt));
    }

    private List<String> hashes() {
        return jdbc.queryForList("SELECT token_hash FROM refresh_tokens", String.class);
    }
}
