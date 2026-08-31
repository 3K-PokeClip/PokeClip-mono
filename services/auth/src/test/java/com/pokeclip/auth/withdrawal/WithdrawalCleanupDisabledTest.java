package com.pokeclip.auth.withdrawal;

import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 🔴 <b>창고가 꺼진 것이 CI와 팀원 로컬의 기본이다.</b> 그 상태에서 자리지기({@code PhotoStorage.NONE})는
 * 아무것도 안 하고 <b>예외도 안 던진다</b> — 탈퇴가 막히면 안 된다.
 *
 * <p>이 클래스가 <b>가짜를 하나도 안 끼우는</b> 이유가 그것이다. 창고를 켜거나 바꾸면 이 갈래가
 * 통째로 안 재어진다.
 */
class WithdrawalCleanupDisabledTest extends WithdrawalTestSupport {

    private final WithdrawalCleanupExecutor cleanup;

    WithdrawalCleanupDisabledTest(MockMvc mockMvc, UserService userService, TokenService tokenService,
                                  JdbcTemplate jdbc, WithdrawalCleanupExecutor cleanup) {
        super(mockMvc, userService, tokenService, jdbc);
        this.cleanup = cleanup;
    }

    @Test
    void 창고가_꺼져_있어도_탈퇴가_막히지_않는다() throws Exception {
        User user = newUser();

        try (LogCaptor logs = new LogCaptor()) {
            mockMvc.perform(delete("/api/auth/me").header("Authorization", bearer(user)))
                    .andExpect(status().isNoContent());
            assertThat(cleanup.awaitIdle(Duration.ofSeconds(20))).isTrue();

            assertThat(logs.messages())
                    .as("🔴 꺼진 창고에서 정리 잡이 끝까지 못 갔다 — 비밀값도 같이 안 지워진다")
                    .anyMatch(m -> m.equals("auth.withdrawal.cleanup.completed userId=" + user.getId()));
            assertThat(logs.messages())
                    .noneMatch(m -> m.startsWith("auth.withdrawal.cleanup.failed userId=" + user.getId()));
        }
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM users WHERE id = ?", Object.class, user.getId()))
                .isNotNull();
    }
}
