package com.pokeclip.auth.chzzk.api;

import com.pokeclip.auth.chzzk.ChzzkChannelLink;
import com.pokeclip.auth.chzzk.ChzzkChannelLinkRepository;
import com.pokeclip.auth.chzzk.ChzzkLinkStateCodec;
import com.pokeclip.auth.chzzk.ChzzkLinkTestSupport;
import com.pokeclip.auth.chzzk.ChzzkCleanupExecutor;
import com.pokeclip.auth.chzzk.ChzzkLinkWriter;
import com.pokeclip.auth.chzzk.ChzzkTokenRefresher;
import com.pokeclip.auth.chzzk.RevokeReason;
import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChzzkLinkStatusAndUnlinkTest extends ChzzkLinkTestSupport {

    private final ChzzkTokenRefresher refresher;

    ChzzkLinkStatusAndUnlinkTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                                 TokenService tokenService, ChzzkLinkStateCodec codec,
                                 ChzzkChannelLinkRepository linkRepository, SecretStore secretStore,
                                 ChzzkLinkWriter writer, JdbcTemplate jdbc, ChzzkCleanupExecutor cleanup, ChzzkTokenRefresher refresher) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer, jdbc, cleanup);
        this.refresher = refresher;
    }

    private MockHttpServletRequestBuilder statusOf(User u) {
        return get("/api/chzzk-link").header("Authorization", bearer(u));
    }

    @Test
    void 연동이_없으면_linked_false() throws Exception {
        mockMvc.perform(statusOf(newUser())).andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(false))
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.channelId").doesNotExist());
    }

    @Test
    void 살아있으면_ACTIVE와_마지막_갱신_시각() throws Exception {
        User u = newUser();
        linked(u, Duration.ofHours(20));
        mockMvc.perform(statusOf(u)).andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.channelId").exists())
                .andExpect(jsonPath("$.channelName").exists())
                .andExpect(jsonPath("$.linkedAt").exists())
                .andExpect(jsonPath("$.lastRefreshedAt").exists())
                .andExpect(jsonPath("$.accessExpiresAt").exists());
    }

    @Test
    void access가_지났는데_살아있으면_EXPIRED() throws Exception {
        User u = newUser();
        linked(u, Duration.ofHours(-1));
        mockMvc.perform(statusOf(u)).andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    void 갱신_거부로_닫혔으면_BROKEN이고_linked_false() throws Exception {
        User u = newUser();
        linked(u, Duration.ofHours(1));
        CHZZK.tokenResponds(401, "{}");
        refresher.refreshIfExpiringWithin(u.getId(), Duration.ofHours(6));
        mockMvc.perform(statusOf(u)).andExpect(jsonPath("$.linked").value(false))
                .andExpect(jsonPath("$.status").value("BROKEN"))
                .andExpect(jsonPath("$.channelName").exists());
    }

    @Test
    void DELETE하면_204이고_행은_남고_토큰은_버려지고_secrets는_지워진다() throws Exception {
        User u = newUser();
        ChzzkChannelLink link = linked(u, Duration.ofHours(20));
        mockMvc.perform(delete("/api/chzzk-link").header("Authorization", bearer(u))).andExpect(status().isNoContent());
        ChzzkChannelLink after = linkRepository.findById(link.getId()).orElseThrow();
        assertThat(after.getRevokeReason()).isEqualTo(RevokeReason.USER_UNLINKED);
        awaitCleanup();   // secrets 삭제·revoke는 커밋 뒤 전용 스레드에서 — "결국" 된다
        assertThat(secretStore.get(link.getAccessTokenRef())).isEmpty();
        assertThat(secretStore.get(link.getRefreshTokenRef())).isEmpty();
        assertThat(CHZZK.revokedTokens()).containsExactlyInAnyOrder("at-old", "rt-old");
        mockMvc.perform(statusOf(u)).andExpect(jsonPath("$.linked").value(false))
                .andExpect(jsonPath("$.status").value("UNLINKED"));
        mockMvc.perform(resolve(u.getId())).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("UNLINKED"));
    }

    @Test
    void 연동이_없어도_DELETE는_204() throws Exception {
        mockMvc.perform(delete("/api/chzzk-link").header("Authorization", bearer(newUser()))).andExpect(status().isNoContent());
        assertThat(CHZZK.revokeCalls()).isZero();
    }

    @Test
    void 해제_뒤_다시_연동할_수_있다() throws Exception {
        User u = newUser();
        linked(u, Duration.ofHours(20));
        mockMvc.perform(delete("/api/chzzk-link").header("Authorization", bearer(u))).andExpect(status().isNoContent());
        mockMvc.perform(link(u)).andExpect(status().isCreated());
        mockMvc.perform(statusOf(u)).andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.channelId").value("chan-default"));
        assertThat(linkRepository.findByUserIdAndRevokedAtIsNull(u.getId()).orElseThrow().status(Instant.now()).name())
                .isEqualTo("ACTIVE");
    }

    @Test
    void JWT_없이는_401() throws Exception {
        mockMvc.perform(get("/api/chzzk-link")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/chzzk-link")).andExpect(status().isUnauthorized());
    }
}
