package com.pokeclip.auth.withdrawal;

import com.pokeclip.auth.token.TokenPair;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 🔴 <b>일괄 폐기를 넘어 살아남은 갱신 표</b>를 잰다(PR #148 전수 세기의 A2, 사용자 결정 2026-08-31).
 *
 * <p><b>어떻게 살아남나</b> — 로그인이 도는 중에 탈퇴가 커밋되면 그렇게 된다. 로그인은
 * {@code findOrCreate}로 회원을 읽은 <b>뒤</b> {@code issue}로 갱신 표를 넣는데, 그 INSERT는
 * <b>자식 표라 회원 행 락에 안 막힌다</b>({@code ActiveUserGuard} 맨 위의 뿌리). 그래서 탈퇴의
 * {@code revokeAllOfUser}가 지나간 <b>뒤에</b> 표 하나가 태어난다.
 *
 * <p>🔴 <b>왜 이 한 자리가 특히 비싼가</b> — 그 표는 <b>무기한</b> 새 접근 표를 찍어낸다.
 * auth 창구는 입구 필터가 전부 막지만 <b>clip은 표를 독립으로 검증한다</b>(ADR-049).
 * PRD가 남은 접근 표의 한계를 「최대 30분」으로 적었는데, 이 자리가 열려 있으면
 * <b>그 문장이 그 계정에서 거짓</b>이 된다 — 실제로 무기한 열린다.
 *
 * <p>경합을 시각으로 만들지 않고 <b>그 결과 상태를 그대로 세운다</b> — 탈퇴가 끝난 뒤에 표를 하나
 * 발급하면 「폐기를 넘어 태어난 표」와 표에서 구분되지 않는다. 기계가 바빠도 안 흔들린다.
 */
class WithdrawnWriteGuardRefreshTokenTest extends WithdrawalTestSupport {

    private final TokenService tokens;

    WithdrawnWriteGuardRefreshTokenTest(MockMvc mockMvc, UserService userService, TokenService tokenService,
                                        JdbcTemplate jdbc) {
        super(mockMvc, userService, tokenService, jdbc);
        this.tokens = tokenService;
    }

    @Test
    void 탈퇴를_넘어_살아남은_갱신_표로는_새_표를_못_받는다() throws Exception {
        User user = newUser();
        withdraw(user);
        assertThat(aliveTokens(user)).as("전제: 탈퇴가 갱신 표를 전부 폐기했다").isZero();

        // 로그인이 탈퇴 커밋 직전에 회원을 읽고 커밋 뒤에 표를 넣으면 남는 상태 그대로다.
        TokenPair 살아남은_표 = tokens.issue(user);
        assertThat(aliveTokens(user))
                .as("전제: 폐기를 넘어선 표가 하나 있다 — 없으면 아래가 아무것도 안 잰다")
                .isEqualTo(1);

        // 🔴 개수를 절대값으로 재지 않는다. 위 withdraw가 표를 얻으려고 bearer()를 부르면서
        // 갱신 표를 하나 더 만들기 때문에 「1」로 적으면 회전과 무관하게 틀린다(실제로 한 번 틀렸다).
        // 차이로 재면 이 계층이 앞에서 몇 개를 만들든 뜻이 안 변한다.
        int 회전_전 = allTokens(user);

        // 🔴 표를 헤더에 안 싣는다. 실으면 입구 필터가 막아 rotate 자신의 확인을 한 번도 안 밟는다.
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + 살아남은_표.refreshToken() + "\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(allTokens(user) - 회전_전)
                .as("🔴 탈퇴한 계정이 새 갱신 표를 받았다 — 이 자리가 열려 있으면 그 계정은 무기한으로 "
                        + "접근 표를 찍어내고, clip은 그것을 독립으로 검증하므로 실제로 열린다")
                .isZero();
    }

    // ── 도구 ────────────────────────────────────────────────────────────

    private void withdraw(User user) throws Exception {
        mockMvc.perform(delete("/api/auth/me").header("Authorization", bearer(user)))
                .andExpect(status().isNoContent());
    }

    private int aliveTokens(User user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, user.getId());
    }

    /** 회전이 되면 옛 표가 폐기되고 새 표가 생겨 <b>살아있는 수는 그대로 1</b>이다 — 전체로 세야 갈린다. */
    private int allTokens(User user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, user.getId());
    }
}
