package com.pokeclip.auth.api;

import com.pokeclip.auth.AuthException;
import com.pokeclip.auth.profile.ProfileTestSupport;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 설정 › 계정의 「이용 분석 참여」 토글(V120). auth는 값을 저장하고 {@code /me}로 돌려줄 뿐이다 —
 * 끄면 대시보드가 분석 도구로 보내지 않는다(처리방침 7·9절).
 */
class UpdateAnalyticsTest extends ProfileTestSupport {

    private final JdbcTemplate db;

    UpdateAnalyticsTest(MockMvc mockMvc, UserRepository userRepository, UserService userService,
                        TokenService tokenService, JdbcTemplate jdbc) {
        super(mockMvc, userRepository, userService, tokenService, jdbc);
        this.db = jdbc;
    }

    @Test
    void 기본은_참여다_새_회원의_me는_analyticsOptOut이_false() throws Exception {
        User u = newUser();

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(u)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analyticsOptOut").value(false));
    }

    @Test
    void 끄면_저장되고_me가_꺼짐을_돌려준다_다시_켜면_돌아온다() throws Exception {
        User u = newUser();

        mockMvc.perform(toggle(u, "{\"optOut\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analyticsOptOut").value(true));
        assertThat(optOutOf(u)).isTrue();
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(u)))
                .andExpect(jsonPath("$.analyticsOptOut").value(true));

        mockMvc.perform(toggle(u, "{\"optOut\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analyticsOptOut").value(false));
        assertThat(optOutOf(u)).isFalse();
    }

    @Test
    void 같은_값이면_수정일시를_움직이지_않는다() throws Exception {
        User u = newUser();
        Instant before = userRepository.findById(u.getId()).orElseThrow().getUpdatedAt();

        mockMvc.perform(toggle(u, "{\"optOut\":false}")).andExpect(status().isOk());

        assertThat(userRepository.findById(u.getId()).orElseThrow().getUpdatedAt()).isEqualTo(before);
    }

    /** 원시 boolean이었다면 빠진 필드가 false(=참여)로 읽혀 끄려던 요청이 켜는 요청이 된다 */
    @Test
    void optOut이_빠지면_400이고_값을_바꾸지_않는다() throws Exception {
        User u = newUser();
        mockMvc.perform(toggle(u, "{\"optOut\":true}")).andExpect(status().isOk());

        mockMvc.perform(toggle(u, "{}")).andExpect(status().isBadRequest());

        assertThat(optOutOf(u)).as("빠진 필드가 참여로 되돌리지 않는다").isTrue();
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(patch("/api/auth/me/analytics").contentType(APPLICATION_JSON).content("{\"optOut\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 회원_번호를_본문으로_받지_않는다_남의_값을_못_바꾼다() throws Exception {
        User me = newUser();
        User other = newUser();

        mockMvc.perform(toggle(me, "{\"optOut\":true,\"userId\":" + other.getId() + "}"))
                .andExpect(status().isOk());

        assertThat(optOutOf(me)).isTrue();
        assertThat(optOutOf(other)).as("본문의 userId는 버려진다").isFalse();
    }

    @Test
    void 탈퇴한_회원은_바꾸지_못한다() throws Exception {
        User u = newUser();
        mockMvc.perform(delete("/api/auth/me").header("Authorization", bearer(u)))
                .andExpect(status().isNoContent());

        assertThatThrownBy(() -> userService.updateAnalyticsOptOut(u.getId(), true))
                .isInstanceOf(AuthException.class);
        assertThat(optOutOf(u)).isFalse();
    }

    // ── 도구 ────────────────────────────────────────────────────────────

    private MockHttpServletRequestBuilder toggle(User u, String json) {
        return patch("/api/auth/me/analytics").header("Authorization", bearer(u))
                .contentType(APPLICATION_JSON)
                .content(json);
    }

    /** 표에서 읽는다 — 손에 든 객체는 요청 전 값이다. */
    private boolean optOutOf(User u) {
        return Boolean.TRUE.equals(db.queryForObject(
                "SELECT analytics_opt_out FROM users WHERE id = ?", Boolean.class, u.getId()));
    }
}
