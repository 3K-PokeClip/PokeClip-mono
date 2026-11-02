package com.pokeclip.auth.withdrawal;

import com.pokeclip.auth.AuthException;
import com.pokeclip.auth.streamkey.StreamKeyService;
import com.pokeclip.auth.streamkey.pairing.PairingCodeService;
import com.pokeclip.auth.support.CrockfordBase32;
import com.pokeclip.auth.support.Sha256;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>탈퇴 표시를 지나쳐 도착한 송출 자격 요청</b>을 잰다(PR #148 codex C1·C1b).
 *
 * <p>🔴 <b>왜 필터로는 못 막나</b> — 두 갈래 다 입구 필터 밖이다. 페어링 교환은 <b>로그인이 없고</b>
 * (코드 자체가 자격증명이다), 발급은 로그인이 있지만 <b>필터를 이미 지난 요청</b>이 탈퇴가 커밋된 뒤에
 * 도착할 수 있다. 감사가 그 창을 실제로 재현했다(18_bot-verifier_round1.md C1).
 *
 * <p>🔴 <b>창구 대신 서비스를 직접 부른다.</b> 창구로 부르면 필터가 401로 막아 <b>가드가 있든 없든
 * 초록</b>이다 — 그러면 이 검사는 아무것도 안 잰다. 「필터를 지나 도달한 요청」이 재려는 상태다.
 *
 * <p><b>단언이 둘씩인 이유</b>: 「거절된다」만 재면 {@code ensureKey}가 새 키를 만드는 갈래를
 * 한 번도 안 밟는다({@code WithdrawalStreamKeyTest}가 같은 이유로 같은 경고를 달았다).
 * <b>표에 행이 안 생겼다</b>까지 세야 그 갈래가 잡힌다.
 */
class WithdrawnWriteGuardStreamKeyTest extends WithdrawalTestSupport {

    private static final String EXCHANGE = "/api/stream-keys/pairing-codes/exchange";

    private final WithdrawalService withdrawalService;
    private final StreamKeyService streamKeyService;
    private final PairingCodeService pairingCodeService;

    WithdrawnWriteGuardStreamKeyTest(MockMvc mockMvc, UserService userService, TokenService tokenService,
                                     JdbcTemplate jdbc, WithdrawalService withdrawalService,
                                     StreamKeyService streamKeyService, PairingCodeService pairingCodeService) {
        super(mockMvc, userService, tokenService, jdbc);
        this.withdrawalService = withdrawalService;
        this.streamKeyService = streamKeyService;
        this.pairingCodeService = pairingCodeService;
    }

    /**
     * C1b — 가장 짧은 경로다. 살아있는 키가 없는 회원이면 코드를 거치지 않고 {@code ensureKey}가
     * <b>스트림키를 곧바로 만든다.</b> 탈퇴한 회원에게 그것이 일어나면 표에는 폐기된 키 옆에
     * <b>살아있는 키가 새로</b> 생긴다.
     */
    @Test
    void 탈퇴한_회원에게는_스트림키를_새로_안_만들어_준다() {
        User user = newUser();
        withdrawalService.withdraw(user.getId());

        assertThatThrownBy(() -> streamKeyService.ensureKey(user.getId()))
                .isInstanceOf(AuthException.class);

        assertThat(allKeys(user))
                .as("🔴 탈퇴한 계정에 스트림키가 새로 생겼다 — ensureKey가 탈퇴 표시를 안 봤다")
                .isZero();
    }

    /**
     * C1 — 발급 창구. {@code issue}는 <b>코드를 저장하기 전에</b> {@code ensureKey}를 부르므로
     * 그 자리가 막히면 코드도 안 생긴다.
     */
    @Test
    void 탈퇴한_회원에게는_페어링_코드를_안_발급한다() {
        User user = newUser();
        withdrawalService.withdraw(user.getId());

        assertThatThrownBy(() -> pairingCodeService.issue(user.getId()))
                .isInstanceOf(AuthException.class);

        assertThat(aliveCodes(user))
                .as("🔴 탈퇴한 계정에 살아있는 페어링 코드가 생겼다 — 그 코드는 10분 뒤까지 자격증명이다")
                .isZero();
        assertThat(allKeys(user))
                .as("🔴 코드는 안 생겼는데 키는 생겼다 — issue가 ensureKey를 먼저 부르는 순서가 깨졌다")
                .isZero();
    }

    /**
     * 🔴 <b>감사가 재현한 C1을 결정적으로 다시 세운다.</b> 탈퇴 트랜잭션이 회수를 다 돌린 뒤
     * <b>커밋 전</b>에 발급이 끼어들면 남는 상태가 이것이다 — <b>탈퇴한 계정에 살아있는 코드 하나</b>.
     * (회원 행 락이 {@code FOR NO KEY UPDATE}라 자식 표 INSERT를 안 막는 것이 그 창의 뿌리다.)
     *
     * <p>경합으로 만들지 않고 <b>그 결과 상태를 표에 직접 심는다</b> — 재현을 시각에 기대면
     * 기계가 바쁠 때 조용히 안 재게 된다.
     *
     * <p>교환은 <b>로그인이 없어</b> 여기서 필터가 못 막는다. 그래서 이 갈래가 이 카드에서
     * 가장 값비싼 자리다 — 막는 것이 {@code ensureKey}의 조건 한 줄뿐이다.
     */
    @Test
    void 탈퇴_뒤에도_살아있는_코드로는_새_송출_자격을_못_받는다() throws Exception {
        User user = newUser();
        withdrawalService.withdraw(user.getId());
        String code = 살아있는_코드를_심는다(user);

        mockMvc.perform(post(EXCHANGE)
                        .with(request -> {
                            request.setRemoteAddr("10.148." + UUID.randomUUID());
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(aliveKeys(user))
                .as("🔴 탈퇴한 계정이 새 송출 자격을 받았다 — 교환이 부른 ensureKey가 탈퇴 표시를 안 봤다")
                .isZero();
    }

    // ── 도구 ────────────────────────────────────────────────────────────

    /** 발급 창구를 안 쓴다 — 그 창구는 이제 막히므로 여기서 만들려는 상태를 못 만든다. */
    private String 살아있는_코드를_심는다(User user) {
        String code = CrockfordBase32.random(new java.security.SecureRandom(), 8);
        jdbc.update("INSERT INTO pairing_codes (user_id, code_hash, expires_at, created_at) "
                        + "VALUES (?, ?, ?, ?)",
                user.getId(), Sha256.hex(code),
                Timestamp.from(Instant.now().plus(Duration.ofMinutes(10))),
                Timestamp.from(Instant.now()));
        return code.substring(0, 4) + "-" + code.substring(4);
    }

    private int allKeys(User user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM stream_keys WHERE user_id = ?", Integer.class, user.getId());
    }

    private int aliveKeys(User user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM stream_keys WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, user.getId());
    }

    private int aliveCodes(User user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM pairing_codes WHERE user_id = ? AND used_at IS NULL",
                Integer.class, user.getId());
    }
}
