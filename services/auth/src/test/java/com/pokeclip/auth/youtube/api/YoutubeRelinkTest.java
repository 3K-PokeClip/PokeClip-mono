package com.pokeclip.auth.youtube.api;

import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.auth.youtube.RevokeReason;
import com.pokeclip.auth.youtube.YoutubeChannel;
import com.pokeclip.auth.youtube.YoutubeChannelLink;
import com.pokeclip.auth.youtube.YoutubeChannelLinkRepository;
import com.pokeclip.auth.youtube.YoutubeCleanupExecutor;
import com.pokeclip.auth.youtube.YoutubeLinkStateCodec;
import com.pokeclip.auth.youtube.YoutubeLinkTestSupport;
import com.pokeclip.auth.youtube.YoutubeLinkWriter;
import com.pokeclip.auth.youtube.YoutubeOAuthClient;
import com.pokeclip.auth.youtube.YoutubeTokens;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 재연동. <b>채널을 바꾸는 유일한 수단이다</b> — 구글은 동의 시점에 채널을 확정하고 그 토큰의
 * channels.list는 고른 채널 하나만 주므로(2026-08-24 실측) 목록·재선택 창구가 성립하지 않는다.
 *
 * <p>치지직과 정반대인 자리 하나: <b>옛 토큰을 revoke하지 않는다.</b> 새 동의가 옛 grant를 대체하고,
 * 구글에서 revoke를 부르면 방금 저장한 새 토큰까지 죽는다.
 */
class YoutubeRelinkTest extends YoutubeLinkTestSupport {

    private final YoutubeOAuthClient oauthClient;

    YoutubeRelinkTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                      TokenService tokenService, YoutubeLinkStateCodec codec,
                      YoutubeChannelLinkRepository linkRepository, SecretStore secretStore,
                      YoutubeLinkWriter writer, JdbcTemplate jdbc, YoutubeCleanupExecutor cleanup,
                      YoutubeOAuthClient oauthClient) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer,
                jdbc, cleanup);
        this.oauthClient = oauthClient;
    }

    @Test
    void 재동의하면_옛_행이_닫히고_새_행이_생기며_옛_secrets만_지워진다() throws Exception {
        User user = newUser();
        mockMvc.perform(link(user)).andExpect(status().isCreated());                  // at-1/rt-1
        YoutubeChannelLink old = linkRepository.findByUserIdAndRevokedAtIsNull(user.getId()).orElseThrow();
        String oldAccessRef = old.getAccessTokenRef();
        String oldRefreshRef = old.getRefreshTokenRef();
        YOUTUBE.channelsResponds(200, "{\"items\":[{\"id\":\"chan-new\",\"snippet\":{\"title\":\"새채널\"}}]}");

        mockMvc.perform(link(user)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.channelId").value("chan-new"));
        awaitCleanup();

        YoutubeChannelLink fresh = linkRepository.findByUserIdAndRevokedAtIsNull(user.getId()).orElseThrow();
        assertThat(fresh.getId()).isNotEqualTo(old.getId());
        YoutubeChannelLink closed = linkRepository.findById(old.getId()).orElseThrow();
        assertThat(closed.isRevoked()).isTrue();
        assertThat(closed.getRevokeReason()).isEqualTo(RevokeReason.USER_UNLINKED);
        assertThat(secretStore.get(oldAccessRef)).isEmpty();
        assertThat(secretStore.get(oldRefreshRef)).isEmpty();
        assertThat(secretCount()).isEqualTo(2);
        assertThat(YOUTUBE.revokedTokens()).as("재연동이 옛 토큰을 revoke했다 — 새 토큰까지 죽는다").isEmpty();
    }

    /**
     * 🔴 치명-1을 창구까지 통과시켜 재는 자리. 가짜 구글을 실물처럼(revoke 한 번이면 그 사용자의 grant 전체가
     * 죽는다) 켜 둔 채 재동의하고, 새 refresh로 갱신이 되는지 본다. 재연동 정리가 revoke를 부르면
     * 여기서 400 invalid_grant가 난다 — 표는 ACTIVE인데 다음 갱신이 죽는 그 사건이다.
     */
    @Test
    void 캐스케이드를_켠_채_재동의해도_새_토큰이_살아_있다() throws Exception {
        YOUTUBE.cascadeOnRevoke(true);
        User user = newUser();
        mockMvc.perform(link(user)).andExpect(status().isCreated());
        YOUTUBE.channelsResponds(200, "{\"items\":[{\"id\":\"chan-new\",\"snippet\":{\"title\":\"새채널\"}}]}");

        mockMvc.perform(link(user)).andExpect(status().isCreated());
        awaitCleanup();

        YoutubeChannelLink fresh = linkRepository.findByUserIdAndRevokedAtIsNull(user.getId()).orElseThrow();
        String newRefresh = secretStore.get(fresh.getRefreshTokenRef()).orElseThrow();
        assertThat(oauthClient.refresh(newRefresh).accessToken())
                .as("재동의 뒤 새 refresh가 죽어 있다 — 옛 토큰 revoke가 새 grant까지 끊었다").isNotNull();
    }

    /**
     * 재연동 요청 A가 구글 HTTP에 걸려 있는 동안 다른 경로 B가 같은 회원의 행을 먼저 만들면, A의 새 행
     * created_at이 B보다 앞서면 안 된다 — GET 상태·resolve가 「회원별 최신 행」(created_at DESC)을 보므로
     * 살아있는 행이 최신 행이 아니게 된다. created_at은 요청 시작 시각이 아니라 <b>락을 잡은 뒤</b>의 시각이어야 한다.
     */
    @Test
    void 재연동이_늦게_커밋돼도_살아있는_행이_회원별_최신_행이다() throws Exception {
        User user = newUser();
        mockMvc.perform(link(user)).andExpect(status().isCreated());
        YOUTUBE.tokenDelays(Duration.ofMillis(600));   // A의 교환을 늦춘다 — A의 요청 시작 시각은 B보다 앞선다
        YOUTUBE.channelsResponds(200, "{\"items\":[{\"id\":\"chan-a\",\"snippet\":{\"title\":\"a\"}}]}");
        MockHttpServletRequestBuilder a = link(user);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Integer> aStatus = pool.submit(() -> mockMvc.perform(a).andReturn().getResponse().getStatus());
        Thread.sleep(200);                              // A가 교환 지연 중 — B가 끼어든다
        writer.create(user.getId(), new YoutubeChannel("chan-b", "b"),
                new YoutubeTokens("at-b", "rt-b", Duration.ofHours(1), null));
        assertThat(aStatus.get(30, TimeUnit.SECONDS)).isEqualTo(201);
        pool.shutdown();
        awaitCleanup();

        YoutubeChannelLink alive = linkRepository.findByUserIdAndRevokedAtIsNull(user.getId()).orElseThrow();
        assertThat(alive.getChannelId()).isEqualTo("chan-a");   // A가 마지막에 커밋했다
        YoutubeChannelLink latest = linkRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow();
        assertThat(latest.getId()).as("살아있는 행 = 회원별 최신 행").isEqualTo(alive.getId());
    }
}
