package com.pokeclip.auth.youtube.api;

import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.support.FakeYoutubeServer;
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
import com.pokeclip.auth.youtube.YoutubeOAuthClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 🔴 연동 <b>실패</b>가 무엇을 버리는가. 이 PR에서 제일 비싼 실패가 여기 있다.
 *
 * <p>구글 revoke는 「그 토큰 쌍」이 아니라 그 사용자가 우리 앱에 준 <b>동의 전부</b>를 죽인다(실측 A ⑥ —
 * 1차 refresh만 revoke했더니 직전까지 갱신되던 2차 refresh가 400 invalid_grant로 죽었다). 그래서
 * 치지직의 불변식(「교환에 성공한 뒤 실패하면 무조건 버린다」)을 그대로 미러하면, <b>실패 한 번이
 * 그 회원의 멀쩡한 기존 연동을 통째로 끊는다</b> — 표는 ACTIVE인데 다음 갱신이 invalid_grant다.
 *
 * <p>그래서 갈래가 둘이다. 살아있는 연동이 <b>없으면</b> 버리고(고아 토큰을 남기지 않는다),
 * <b>있으면</b> 안 버린다(버려진 access는 1시간이면 스스로 죽는다). 아래 검사들은 두 갈래를 나란히 잰다 —
 * 한쪽만 있으면 「아무것도 안 버린다」로 바꿔도 초록이다.
 *
 * <p>「기존 연동이 여전히 유효하다」는 가짜 구글의 캐스케이드 모드를 켜고 그 refresh로 실제 갱신을
 * 시도해 잰다. resolve는 태스크 9에서 생기므로 그때 같은 사건을 창구로 한 번 더 건다.
 */
class YoutubeLinkFailureCleanupTest extends YoutubeLinkTestSupport {

    /** 동의는 됐지만 업로드 권한이 빠진 응답. 이 access·refresh가 「버릴 것」이다. */
    private static final String SCOPE_MISSING_TOKENS =
            "{\"access_token\":\"at-x\",\"refresh_token\":\"rt-x\",\"expires_in\":3600,"
                    + "\"scope\":\"https://www.googleapis.com/auth/youtube.readonly\"}";

    private final YoutubeOAuthClient oauthClient;

    YoutubeLinkFailureCleanupTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                                  TokenService tokenService, YoutubeLinkStateCodec codec,
                                  YoutubeChannelLinkRepository linkRepository, SecretStore secretStore,
                                  YoutubeLinkWriter writer, JdbcTemplate jdbc, YoutubeCleanupExecutor cleanup,
                                  YoutubeOAuthClient oauthClient) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer,
                jdbc, cleanup);
        this.oauthClient = oauthClient;
    }

    // ── 살아있는 연동이 없을 때: 받은 토큰을 버린다 ────────────────────────────────

    @Test
    void 연동이_없는_회원의_scope_실패는_받은_토큰을_한_번_버린다() throws Exception {
        User user = newUser();
        YOUTUBE.tokenResponds(200, SCOPE_MISSING_TOKENS);

        mockMvc.perform(link(user)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("SCOPE_MISSING"));
        awaitCleanup();

        assertThat(YOUTUBE.revokedTokens()).as("refresh 우선 한 번").containsExactly("rt-x");
    }

    @Test
    void 연동이_없는_회원의_채널_0개_실패도_받은_토큰을_버린다() throws Exception {
        User user = newUser();
        YOUTUBE.channelsResponds(200, "{\"items\":[]}");

        mockMvc.perform(link(user)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("NO_CHANNEL"));
        awaitCleanup();

        assertThat(YOUTUBE.revokedTokens()).containsExactly("rt-1");
    }

    /**
     * 교환 응답에 refresh_token이 없으면 갱신할 수 없는 반쪽 연동이라 만들지 않는다(502).
     * 그런데 access는 이미 발급됐다 — 클라이언트가 그것을 예외에 실어 보내야 여기서 버릴 수 있다.
     */
    @Test
    void 교환에_refresh가_없으면_502이고_받은_access를_버린다() throws Exception {
        User user = newUser();
        YOUTUBE.tokenResponds(200, "{\"access_token\":\"at-only\",\"expires_in\":3600,"
                + "\"scope\":\"" + FakeYoutubeServer.SCOPE_GRANTED + "\"}");

        mockMvc.perform(link(user)).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.reason").value("YOUTUBE_UNAVAILABLE"));
        awaitCleanup();

        assertThat(YOUTUBE.revokedTokens()).containsExactly("at-only");
        assertThat(linkRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
    }

    /**
     * 🔴 409는 예외다 — <b>버리지 않는다.</b> 「그 채널이 남에게 묶여 있다」는 뜻이고, 채널이 같으면
     * <b>구글 계정도 같다.</b> 그 계정의 토큰을 revoke하면 원래 주인의 멀쩡한 연동이 끊긴다
     * (봇 리뷰 PR #116에서 재현). 이 회원에게 살아있는 행이 없다는 것만으로는 부족하다 —
     * <b>revoke의 영향 범위가 「이 회원」이 아니라 「그 구글 계정」</b>이기 때문이다.
     *
     * <p>이 검사는 원래 「409도 버린다」를 못박고 있었다. 그 단언이 바로 결함이었다.
     */
    @Test
    void 연동이_없는_회원이라도_409면_버리지_않는다() throws Exception {
        User owner = newUser();
        YoutubeChannelLink theirs = linked(owner);
        YOUTUBE.channelsResponds(200, "{\"items\":[{\"id\":\"" + theirs.getChannelId()
                + "\",\"snippet\":{\"title\":\"채널\"}}]}");
        User other = newUser();

        mockMvc.perform(link(other)).andExpect(status().isConflict());
        awaitCleanup();

        assertThat(YOUTUBE.revokedTokens())
                .as("남이 쓰는 채널이라 409인데 그 구글 계정의 토큰을 버렸다").isEmpty();
    }

    // ── 살아있는 연동이 있을 때: 아무것도 버리지 않는다 (치명-1의 두 번째 갈래) ──────

    @Test
    void 살아있는_연동이_있으면_scope_실패에서_revoke하지_않고_기존_연동이_산다() throws Exception {
        YOUTUBE.cascadeOnRevoke(true);
        User user = newUser();
        YoutubeChannelLink live = linked(user, "at-old", "rt-old");
        String liveRefresh = secretStore.get(live.getRefreshTokenRef()).orElseThrow();
        YOUTUBE.tokenResponds(200, SCOPE_MISSING_TOKENS);

        mockMvc.perform(link(user)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("SCOPE_MISSING"));
        awaitCleanup();

        assertThat(YOUTUBE.revokedTokens()).as("실패 정리가 살아있는 연동을 죽였다").isEmpty();
        assertThat(linkRepository.findByUserIdAndRevokedAtIsNull(user.getId()).orElseThrow().getId())
                .isEqualTo(live.getId());
        assertThat(oauthClient.refresh(liveRefresh).accessToken())
                .as("기존 refresh가 죽었다 — 실패한 시도의 토큰을 버리면서 grant 전체가 날아갔다").isNotNull();
    }

    @Test
    void 살아있는_연동이_있으면_채널_0개_실패에서도_revoke하지_않는다() throws Exception {
        YOUTUBE.cascadeOnRevoke(true);
        User user = newUser();
        YoutubeChannelLink live = linked(user, "at-old", "rt-old");
        String liveRefresh = secretStore.get(live.getRefreshTokenRef()).orElseThrow();
        YOUTUBE.channelsResponds(200, "{\"items\":[]}");

        mockMvc.perform(link(user)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("NO_CHANNEL"));
        awaitCleanup();

        assertThat(YOUTUBE.revokedTokens()).isEmpty();
        assertThat(oauthClient.refresh(liveRefresh).accessToken()).isNotNull();
    }

    /** 재연동 시도가 남의 채널로 409를 맞은 경우 — 옛 행도 옛 토큰도 그대로여야 한다. */
    @Test
    void 살아있는_연동이_있으면_409에서도_revoke하지_않고_옛_연동이_그대로다() throws Exception {
        YOUTUBE.cascadeOnRevoke(true);
        User owner = newUser();
        YoutubeChannelLink theirs = linked(owner);
        User user = newUser();
        YoutubeChannelLink live = linked(user, "at-old", "rt-old");
        String liveRefresh = secretStore.get(live.getRefreshTokenRef()).orElseThrow();
        YOUTUBE.channelsResponds(200, "{\"items\":[{\"id\":\"" + theirs.getChannelId()
                + "\",\"snippet\":{\"title\":\"채널\"}}]}");

        mockMvc.perform(link(user)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("CHANNEL_ALREADY_LINKED"));
        awaitCleanup();

        assertThat(YOUTUBE.revokedTokens()).isEmpty();
        YoutubeChannelLink after = linkRepository.findByUserIdAndRevokedAtIsNull(user.getId()).orElseThrow();
        assertThat(after.getId()).isEqualTo(live.getId());
        assertThat(after.getChannelId()).isEqualTo(live.getChannelId());
        assertThat(secretStore.get(live.getAccessTokenRef())).as("롤백된 시도가 옛 secrets를 지웠다").isPresent();
        assertThat(oauthClient.refresh(liveRefresh).accessToken()).isNotNull();
    }

    /** 구글 5xx도 마찬가지다 — 교환은 됐는데 채널 조회가 죽은 경우. */
    @Test
    void 살아있는_연동이_있으면_구글_5xx_실패에서도_revoke하지_않는다() throws Exception {
        YOUTUBE.cascadeOnRevoke(true);
        User user = newUser();
        YoutubeChannelLink live = linked(user, "at-old", "rt-old");
        String liveRefresh = secretStore.get(live.getRefreshTokenRef()).orElseThrow();
        YOUTUBE.channelsResponds(503, "{}");

        mockMvc.perform(link(user)).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.reason").value("YOUTUBE_UNAVAILABLE"));
        awaitCleanup();

        assertThat(YOUTUBE.revokedTokens()).isEmpty();
        assertThat(oauthClient.refresh(liveRefresh).accessToken()).isNotNull();
    }
}
