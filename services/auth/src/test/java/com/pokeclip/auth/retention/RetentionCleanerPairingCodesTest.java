package com.pokeclip.auth.retention;

import com.jayway.jsonpath.JsonPath;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 만료 뒤 보관 기간이 지나면 사용 여부와 무관하게 지운다. 지운 뒤 그 코드는 「모르는 코드」(404)다 —
 * 409(이미 씀)·410(만료)를 못 주지만, 코드 존재 여부가 덜 새므로 보안은 오히려 낫다(PRD 대가).
 *
 * <p>「심은 뒤 개수」를 따로 안 세는 이유는 다른 청소 시험 둘과 같다 — {@code deleted == 2} + 남은 둘의
 * {@code containsExactlyInAnyOrder}라 합이 심은 넷과 같아야 초록이다. INSERT가 빠지면 어느 한쪽이 빨간불이다
 * ({@code retention-test-reality} 문항 2를 이 모양으로 지킨다).
 *
 * <p>시계를 하나로 — 행을 심는 시각도 청소의 기준도 시험이 만든 {@code base} 하나다. 도커 VM 시계가 호스트와
 * 어긋나도 경계가 안 흔들린다.
 */
@AutoConfigureMockMvc
class RetentionCleanerPairingCodesTest extends RetentionTestSupport {

    private static final String EXCHANGE = "/api/stream-keys/pairing-codes/exchange";

    private final RetentionCleaner cleaner;
    private final RetentionProperties properties;
    private final MockMvc mockMvc;
    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbc;

    RetentionCleanerPairingCodesTest(RetentionCleaner cleaner, RetentionProperties properties, MockMvc mockMvc,
                                     TokenService tokenService, UserService userService, UserRepository userRepository,
                                     JdbcTemplate jdbc) {
        super(userService);
        this.cleaner = cleaner;
        this.properties = properties;
        this.mockMvc = mockMvc;
        this.tokenService = tokenService;
        this.userRepository = userRepository;
        this.jdbc = jdbc;
    }

    @BeforeEach
    @AfterEach
    void clear() {
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM pairing_exchange_attempts");
        jdbc.update("DELETE FROM pairing_codes");
        jdbc.update("DELETE FROM stream_keys");
        jdbc.update("DELETE FROM secrets");
        userRepository.deleteAll();
    }

    @Test
    void 만료_뒤_1시간이_지난_행만_사용_여부와_무관하게_사라진다() {
        Instant base = Instant.now();
        User user = newUser();
        seed(user, "expired-used", base.minus(Duration.ofMinutes(61)), base.minus(Duration.ofMinutes(70)));
        seed(user, "expired-unused", base.minus(Duration.ofMinutes(61)), null);
        seed(user, "expired-recent", base.minus(Duration.ofMinutes(59)), null);
        seed(user, "alive", base.plus(Duration.ofMinutes(5)), null);

        RetentionCleaner.Result result = cleaner.cleanPairingCodes(base);

        assertThat(result.deleted()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT code_hash FROM pairing_codes", String.class))
                .containsExactlyInAnyOrder("expired-recent", "alive");
    }

    /** 실제로 발급한 코드를 만료·보관 기간 밖으로 밀어 지우고 교환한다 — 404여야 한다(410이 아니다). */
    @Test
    void 지운_코드로_교환하면_404다() throws Exception {
        Instant base = Instant.now();
        User user = newUser();
        String code = issueCode(user);
        jdbc.update("UPDATE pairing_codes SET expires_at = ?", Timestamp.from(base.minus(Duration.ofMinutes(61))));

        cleaner.cleanPairingCodes(base);

        mockMvc.perform(post(EXCHANGE)
                        .with(request -> {
                            request.setRemoteAddr("10.0.0.1");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("PAIRING_CODE_NOT_FOUND"));
    }

    /**
     * 상한은 표 셋이 각자 건다({@code LIMIT ?}가 SQL 셋에 따로 있다). 그래서 {@code RetentionCleanerAttemptsTest}와
     * 같은 모양으로 이 표에서도 「상한 + 500」을 심는다 — 한 표의 상한 시험이 다른 표의 {@code LIMIT} 누락을 못 잰다.
     */
    @Test
    void 한_번에_상한만큼만_지우고_나머지는_다음_호출이_이어서_지운다() {
        Instant base = Instant.now();
        int limit = properties.batchLimit();
        seedExpired(newUser(), limit + 500, base.minus(Duration.ofMinutes(61)));

        RetentionCleaner.Result first = cleaner.cleanPairingCodes(base);
        RetentionCleaner.Result second = cleaner.cleanPairingCodes(base);
        RetentionCleaner.Result third = cleaner.cleanPairingCodes(base);

        assertThat(first.deleted()).isEqualTo(limit);
        assertThat(first.capped()).as("상한에 걸렸으면 같은 틱이 이어서 부른다는 표시가 있어야 한다").isTrue();
        assertThat(second.deleted()).isEqualTo(500);
        assertThat(second.capped()).isFalse();
        assertThat(third.deleted()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pairing_codes", Integer.class)).isZero();
    }

    /**
     * 시도 기록과 코드의 보관값이 운영·시험 yml 둘 다 PT1H라 서로 바꿔 써도 못 잡는다(감사 2회차 M6 주입이
     * 3/3 초록이던 자리) — 값을 갈라(시도 기록 1h·코드 2h) 직접 만든다. 컨텍스트를 하나 더 만들지 않는 이유는
     * {@code max_connections} 여유가 컨텍스트 0개라서다({@code IntegrationTestSupport}). 프록시 없이 돌아
     * {@code @Transactional}을 안 타지만 DELETE 한 문장이라 autocommit으로 무해하다.
     */
    @Test
    void 코드_청소는_시도_기록이_아니라_코드의_보관값을_쓴다() {
        RetentionCleaner direct = new RetentionCleaner(jdbc, new RetentionProperties(
                true, Duration.ofMinutes(10), 1000, Duration.ofDays(14), Duration.ofHours(1), Duration.ofHours(2)));
        Instant base = Instant.now();
        User user = newUser();
        seed(user, "expired-90m", base.minus(Duration.ofMinutes(90)), null);
        seed(user, "expired-150m", base.minus(Duration.ofMinutes(150)), null);

        RetentionCleaner.Result result = direct.cleanPairingCodes(base);

        assertThat(result.deleted()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT code_hash FROM pairing_codes", String.class))
                .containsExactly("expired-90m");
    }

    /** {@code created_at}은 청소가 안 본다 — 만료 3시간 전이면 어느 갈래에서도 사용 시각보다 앞선다. */
    private void seed(User user, String hash, Instant expiresAt, Instant usedAt) {
        jdbc.update("INSERT INTO pairing_codes (user_id, code_hash, expires_at, used_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                user.getId(), hash, Timestamp.from(expiresAt),
                usedAt == null ? null : Timestamp.from(usedAt), Timestamp.from(expiresAt.minus(Duration.ofHours(3))));
    }

    /** 만료 뒤 보관 기간 밖인 행을 여럿 심는다. {@code code_hash}는 UNIQUE라 {@code 'stale-' || g}로 행마다 가른다. */
    private void seedExpired(User user, int rows, Instant expiresAt) {
        jdbc.update("INSERT INTO pairing_codes (user_id, code_hash, expires_at, used_at, created_at) "
                        + "SELECT ?, 'stale-' || g, ?, NULL, ? FROM generate_series(1, ?) g",
                user.getId(), Timestamp.from(expiresAt), Timestamp.from(expiresAt.minus(Duration.ofHours(3))), rows);
    }

    private String issueCode(User user) throws Exception {
        String body = mockMvc.perform(post("/api/stream-keys/pairing-codes")
                        .header("Authorization", "Bearer " + tokenService.issue(user).accessToken()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.code");
    }
}
