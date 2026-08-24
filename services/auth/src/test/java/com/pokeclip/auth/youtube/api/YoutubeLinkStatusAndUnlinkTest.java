package com.pokeclip.auth.youtube.api;

import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.auth.youtube.YoutubeChannelLink;
import com.pokeclip.auth.youtube.YoutubeChannelLinkRepository;
import com.pokeclip.auth.youtube.YoutubeCleanupExecutor;
import com.pokeclip.auth.youtube.YoutubeLinkStateCodec;
import com.pokeclip.auth.youtube.YoutubeLinkTestSupport;
import com.pokeclip.auth.youtube.YoutubeLinkWriter;
import com.pokeclip.auth.youtube.RevokeReason;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 화면이 보는 상태와 사용자 해제. 치지직과 갈리는 곳은 <b>EXPIRED가 없다</b>는 것이다 —
 * 구글 access는 1시간짜리라 늘 만료돼 있고 갱신으로 항상 해소되므로 상태가 아니라 일상이다.
 */
class YoutubeLinkStatusAndUnlinkTest extends YoutubeLinkTestSupport {

    YoutubeLinkStatusAndUnlinkTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                                   TokenService tokenService, YoutubeLinkStateCodec codec,
                                   YoutubeChannelLinkRepository linkRepository, SecretStore secretStore,
                                   YoutubeLinkWriter writer, JdbcTemplate jdbc, YoutubeCleanupExecutor cleanup) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer,
                jdbc, cleanup);
    }

    private MockHttpServletRequestBuilder statusOf(User u) {
        return get("/api/youtube-link").header("Authorization", bearer(u));
    }

    @Test
    void 연동이_없으면_linked_false이고_아무_필드도_없다() throws Exception {
        mockMvc.perform(statusOf(newUser())).andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(false))
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.channelId").doesNotExist());
    }

    @Test
    void 살아있으면_ACTIVE와_시각_셋을_준다() throws Exception {
        User u = newUser();
        linked(u);

        mockMvc.perform(statusOf(u)).andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.channelId").exists())
                .andExpect(jsonPath("$.channelName").exists())
                .andExpect(jsonPath("$.linkedAt").exists())
                .andExpect(jsonPath("$.lastRefreshedAt").exists())
                .andExpect(jsonPath("$.accessExpiresAt").exists());
    }

    /**
     * 갱신 거부로 닫힌 행. 갱신기는 태스크 8이라 여기서는 그 결과 모양(REFRESH_REJECTED)만 만들어 둔다 —
     * 진짜 갱신 거부로 이 상태가 되는지는 갱신기 테스트가 잰다.
     */
    @Test
    void 갱신_거부로_닫혔으면_BROKEN이고_linked_false다() throws Exception {
        User u = newUser();
        YoutubeChannelLink link = linked(u);
        jdbc.update("UPDATE youtube_channel_links SET revoked_at = now(), revoke_reason = ? WHERE id = ?",
                RevokeReason.REFRESH_REJECTED.name(), link.getId());

        mockMvc.perform(statusOf(u)).andExpect(jsonPath("$.linked").value(false))
                .andExpect(jsonPath("$.status").value("BROKEN"))
                .andExpect(jsonPath("$.channelName").exists());
    }

    @Test
    void DELETE하면_204_행은_남고_secrets는_지워지고_토큰은_한_번_버려진다() throws Exception {
        User u = newUser();
        YoutubeChannelLink link = linked(u, "at-mine", "rt-mine");

        mockMvc.perform(delete("/api/youtube-link").header("Authorization", bearer(u)))
                .andExpect(status().isNoContent());
        awaitCleanup();   // secrets 삭제·revoke는 커밋 뒤 전용 스레드에서 — "결국" 된다

        YoutubeChannelLink after = linkRepository.findById(link.getId()).orElseThrow();
        assertThat(after.getRevokeReason()).isEqualTo(RevokeReason.USER_UNLINKED);
        assertThat(secretStore.get(link.getAccessTokenRef())).isEmpty();
        assertThat(secretStore.get(link.getRefreshTokenRef())).isEmpty();
        assertThat(YOUTUBE.revokedTokens()).as("해제는 refresh 하나로 grant 전체를 죽인다")
                .containsExactly("rt-mine");
        mockMvc.perform(statusOf(u)).andExpect(jsonPath("$.linked").value(false))
                .andExpect(jsonPath("$.status").value("UNLINKED"));
    }

    @Test
    void 연동이_없어도_DELETE는_204이고_구글을_부르지_않는다() throws Exception {
        mockMvc.perform(delete("/api/youtube-link").header("Authorization", bearer(newUser())))
                .andExpect(status().isNoContent());

        assertThat(YOUTUBE.revokeCalls()).isZero();
    }

    @Test
    void 해제_뒤_다시_연동할_수_있다() throws Exception {
        User u = newUser();
        linked(u);

        mockMvc.perform(delete("/api/youtube-link").header("Authorization", bearer(u)))
                .andExpect(status().isNoContent());
        mockMvc.perform(link(u)).andExpect(status().isCreated());

        mockMvc.perform(statusOf(u)).andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.channelId").value("chan-default"));
    }

    @Test
    void JWT_없이는_401() throws Exception {
        mockMvc.perform(get("/api/youtube-link")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/youtube-link")).andExpect(status().isUnauthorized());
    }
}
