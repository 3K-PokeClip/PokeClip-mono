package com.pokeclip.auth.chzzk.api;

import com.pokeclip.auth.chzzk.ChzzkChannelLinkRepository;
import com.pokeclip.auth.chzzk.ChzzkLinkStateCodec;
import com.pokeclip.auth.chzzk.ChzzkLinkTestSupport;
import com.pokeclip.auth.chzzk.ChzzkCleanupExecutor;
import com.pokeclip.auth.chzzk.ChzzkLinkWriter;
import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChzzkLinkResolveTest extends ChzzkLinkTestSupport {

    ChzzkLinkResolveTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                         TokenService tokenService, ChzzkLinkStateCodec codec,
                         ChzzkChannelLinkRepository linkRepository, SecretStore secretStore,
                         ChzzkLinkWriter writer, JdbcTemplate jdbc, ChzzkCleanupExecutor cleanup) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer, jdbc, cleanup);
    }

    @Test
    void 미연동이면_200에_valid_false_NOT_LINKED() throws Exception {
        User u = newUser();
        mockMvc.perform(resolve(u.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("NOT_LINKED"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void 살아있고_수명이_충분하면_토큰과_채널을_주고_갱신은_안_한다() throws Exception {
        User u = newUser();
        linked(u, Duration.ofHours(20));
        mockMvc.perform(resolve(u.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.channelId").exists())
                .andExpect(jsonPath("$.accessToken").value("at-old"))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.reason").doesNotExist());
        assertThat(CHZZK.tokenCalls()).isZero();
    }

    @Test
    void 남은_수명이_12시간_미만이면_즉석_갱신한_새_토큰을_준다() throws Exception {
        User u = newUser();
        linked(u, Duration.ofHours(11));
        mockMvc.perform(resolve(u.getId())).andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.accessToken").value("at-1"));
        assertThat(CHZZK.tokenCalls()).isEqualTo(1);
    }

    @Test
    void 즉석_갱신이_일시_실패면_임박_토큰을_주지_않고_REFRESH_UNAVAILABLE() throws Exception {
        User u = newUser();
        linked(u, Duration.ofHours(1));
        CHZZK.tokenResponds(503, "{}");
        mockMvc.perform(resolve(u.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("REFRESH_UNAVAILABLE"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void 즉석_갱신이_4xx면_BROKEN() throws Exception {
        User u = newUser();
        linked(u, Duration.ofHours(1));
        CHZZK.tokenResponds(401, "{}");
        mockMvc.perform(resolve(u.getId())).andExpect(jsonPath("$.reason").value("BROKEN"));
        mockMvc.perform(resolve(u.getId())).andExpect(jsonPath("$.reason").value("BROKEN"));    // 다음 호출도 같다(재시도 안 함)
        assertThat(CHZZK.tokenCalls()).isEqualTo(1);
    }

    @Test
    void 내부_토큰_없이는_401() throws Exception {
        mockMvc.perform(post("/internal/chzzk-link/resolve").contentType(APPLICATION_JSON).content("{\"userId\":1}"))
                .andExpect(status().isUnauthorized());
    }

    /** 내부 API는 JWT를 안 받는다 — 별도 체인. */
    @Test
    void 사용자_JWT로는_401() throws Exception {
        User u = newUser();
        mockMvc.perform(post("/internal/chzzk-link/resolve").header("Authorization", bearer(u))
                        .contentType(APPLICATION_JSON).content("{\"userId\":1}"))
                .andExpect(status().isUnauthorized());
    }
}
