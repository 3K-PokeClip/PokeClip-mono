package com.pokeclip.auth.soop.api;

import com.pokeclip.auth.soop.RevokeReason;
import com.pokeclip.auth.soop.SoopChannelLink;
import com.pokeclip.auth.soop.SoopChannelLinkRepository;
import com.pokeclip.auth.soop.SoopCleanupExecutor;
import com.pokeclip.auth.soop.SoopLinkStateCodec;
import com.pokeclip.auth.soop.SoopLinkTestSupport;
import com.pokeclip.auth.soop.SoopLinkWriter;
import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code ChzzkLinkStatusAndUnlinkTest}의 쌍둥이. 다른 점: 해제가 아이디·닉네임을 바로 비우고 외부 HTTP가 없다. */
class SoopLinkStatusAndUnlinkTest extends SoopLinkTestSupport {

    SoopLinkStatusAndUnlinkTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                                TokenService tokenService, SoopLinkStateCodec codec,
                                SoopChannelLinkRepository linkRepository, SecretStore secretStore,
                                SoopLinkWriter writer, JdbcTemplate jdbc, SoopCleanupExecutor cleanup) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer, jdbc, cleanup);
    }

    private MockHttpServletRequestBuilder statusOf(User u) {
        return get("/api/soop-link").header("Authorization", bearer(u));
    }

    private MockHttpServletRequestBuilder unlink(User u) {
        return delete("/api/soop-link").header("Authorization", bearer(u));
    }

    @Test
    void access가_지났는데_살아있으면_EXPIRED이고_linked_true() throws Exception {
        User u = newUser();
        linked(u, Duration.ofHours(-1));
        mockMvc.perform(statusOf(u)).andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    void DELETE하면_204이고_행은_남되_아이디_닉네임은_비고_secrets는_정리된다() throws Exception {
        User u = newUser();
        SoopChannelLink link = linked(u, Duration.ofHours(8));

        mockMvc.perform(unlink(u)).andExpect(status().isNoContent());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT soop_user_id, nickname, revoked_at, revoke_reason FROM soop_channel_links WHERE id = ?",
                link.getId());
        assertThat(row.get("revoke_reason")).isEqualTo(RevokeReason.USER_UNLINKED.name());
        assertThat(row.get("revoked_at")).isNotNull();
        assertThat(row.get("soop_user_id")).isNull();
        assertThat(row.get("nickname")).isNull();
        awaitCleanup();   // secrets 삭제는 커밋 뒤 전용 스레드에서 「결국」 된다
        assertThat(secretStore.get(link.getAccessTokenRef())).isEmpty();
        assertThat(secretStore.get(link.getRefreshTokenRef())).isEmpty();
        assertThat(SOOP.tokenCalls() + SOOP.statusCalls() + SOOP.stationCalls()).as("해제에 외부 HTTP가 없다").isZero();
    }

    /** 닉네임은 파기됐으니 키조차 없다. doesNotExist는 null 값 키도 통과하므로 본문 문자열로 본다. */
    @Test
    void 해제_뒤_GET은_UNLINKED이고_닉네임_키가_없다() throws Exception {
        User u = newUser();
        linked(u, Duration.ofHours(8));
        mockMvc.perform(unlink(u)).andExpect(status().isNoContent());

        String body = mockMvc.perform(statusOf(u)).andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(false))
                .andExpect(jsonPath("$.status").value("UNLINKED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("nickname").doesNotContain("닉네임").doesNotContain("soop-");
    }

    @Test
    void 연동이_없어도_DELETE는_204() throws Exception {
        mockMvc.perform(unlink(newUser())).andExpect(status().isNoContent());
        assertThat(cleanup.awaitIdle(Duration.ofSeconds(1))).isTrue();
    }

    /** 이미 해제한 연동을 또 해제해도 204이고 닫은 시각·사유를 덮어쓰지 않는다. */
    @Test
    void 두_번_해제해도_204이고_처음_닫은_시각이_남는다() throws Exception {
        User u = newUser();
        SoopChannelLink link = linked(u, Duration.ofHours(8));
        mockMvc.perform(unlink(u)).andExpect(status().isNoContent());
        Object firstClose = jdbc.queryForObject("SELECT revoked_at FROM soop_channel_links WHERE id = ?",
                Object.class, link.getId());

        mockMvc.perform(unlink(u)).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT revoked_at FROM soop_channel_links WHERE id = ?",
                Object.class, link.getId())).isEqualTo(firstClose);
    }

    @Test
    void 해제_로그는_unlinked_한_줄이고_아이디_닉네임이_없다() throws Exception {
        User u = newUser();
        SoopChannelLink link = linked(u, Duration.ofHours(8));
        try (LogCaptor logs = new LogCaptor()) {
            mockMvc.perform(unlink(u)).andExpect(status().isNoContent());
            awaitCleanup();
            assertThat(logs.messages()).contains("auth.soop.link.unlinked userId=" + u.getId());
            assertThat(logs.messages()).noneMatch(m -> m.contains(link.getSoopUserId()) || m.contains("닉네임")
                    || m.contains("at-old") || m.contains("rt-old"));
        }
    }

    @Test
    void 해제_뒤_다시_연동할_수_있다() throws Exception {
        User u = newUser();
        linked(u, Duration.ofHours(8));
        mockMvc.perform(unlink(u)).andExpect(status().isNoContent());
        mockMvc.perform(link(u)).andExpect(status().isCreated());
        mockMvc.perform(statusOf(u)).andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void JWT_없이는_401() throws Exception {
        mockMvc.perform(delete("/api/soop-link")).andExpect(status().isUnauthorized());
    }
}
