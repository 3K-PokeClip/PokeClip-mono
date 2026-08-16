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
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChzzkLinkConflictTest extends ChzzkLinkTestSupport {

    ChzzkLinkConflictTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                          TokenService tokenService, ChzzkLinkStateCodec codec,
                          ChzzkChannelLinkRepository linkRepository, SecretStore secretStore,
                          ChzzkLinkWriter writer, JdbcTemplate jdbc, ChzzkCleanupExecutor cleanup) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer, jdbc, cleanup);
    }

    /** 다른 계정 중복은 DB 부분 유니크가 막는다. 롤백된 뒤 받은 토큰은 치지직에 살아 있으므로 버린다. */
    @Test
    void 다른_계정에_묶인_채널이면_409이고_받은_토큰은_치지직에_버린다() throws Exception {
        User first = newUser();
        User second = newUser();
        mockMvc.perform(link(first)).andExpect(status().isCreated());              // chan-default가 first에 묶임
        CHZZK.reset();                                                                // 카운터 0으로 (기본 me는 여전히 chan-default)
        mockMvc.perform(link(second)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("CHANNEL_ALREADY_LINKED"));
        assertThat(linkRepository.findByUserIdAndRevokedAtIsNull(second.getId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM secrets", Integer.class)).isEqualTo(2);   // first 것만
        assertThat(CHZZK.revokedTokens()).containsExactlyInAnyOrder("at-1", "rt-1");                       // reset 후 첫 발급이 at-1/rt-1
    }

    @Test
    void 고아_토큰_revoke가_실패해도_409는_나가고_WARN이_남는다() throws Exception {
        User first = newUser();
        User second = newUser();
        mockMvc.perform(link(first)).andExpect(status().isCreated());
        CHZZK.reset();
        CHZZK.revokeResponds(503, "{}");
        try (LogCaptor logs = new LogCaptor()) {
            mockMvc.perform(link(second)).andExpect(status().isConflict());
            // 5xx는 Unavailable — causeType이 타입 이름이 아니라 원인(Http503·타임아웃)으로 남는다.
            assertThat(logs.messages()).anyMatch(m -> m.startsWith("auth.chzzk.link.orphan_token userId=" + second.getId())
                    && m.endsWith("causeType=Http503"));
            assertThat(logs.messages()).noneMatch(m -> m.contains("at-1") || m.contains("rt-1") || m.contains("chan-default"));
        }
    }

    /**
     * 치지직은 access·refresh를 한 세트로 무효화한다(2026-08-17 실측) — 첫 revoke가 둘 다 죽이고 둘째는
     * 401 INVALID_TOKEN을 받는다. 4xx는 "이미 무효" = 목적 달성이지 고아가 아니다 — WARN이면 해제·재연동·갱신
     * 거부마다 거짓 경보가 한 줄씩 남아 진짜 고아(5xx·타임아웃)를 못 가린다.
     */
    @Test
    void 고아_토큰_revoke가_4xx면_이미_무효라_WARN이_아니라_INFO다() throws Exception {
        User first = newUser();
        User second = newUser();
        mockMvc.perform(link(first)).andExpect(status().isCreated());
        CHZZK.reset();
        CHZZK.revokeResponds(401, "{\"code\":401,\"message\":\"INVALID_TOKEN\"}");
        try (LogCaptor logs = new LogCaptor()) {
            mockMvc.perform(link(second)).andExpect(status().isConflict());
            assertThat(logs.messages()).noneMatch(m -> m.startsWith("auth.chzzk.link.orphan_token"));
            assertThat(logs.messages()).anyMatch(m -> m.startsWith(
                    "auth.chzzk.link.token_already_dead userId=" + second.getId() + " hint=access_token status=401"));
            assertThat(logs.messages()).anyMatch(m -> m.startsWith(
                    "auth.chzzk.link.token_already_dead userId=" + second.getId() + " hint=refresh_token status=401"));
            assertThat(logs.messages()).noneMatch(m -> m.contains("at-1") || m.contains("rt-1") || m.contains("chan-default"));
        }
        assertThat(CHZZK.revokeCalls()).as("세트 무효화에 기대지 않고 둘 다 부른다").isEqualTo(2);
    }
}
