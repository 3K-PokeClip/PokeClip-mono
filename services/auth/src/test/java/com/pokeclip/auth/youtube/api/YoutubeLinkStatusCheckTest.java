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
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * clip이 렌더 주문 전에 「이 스트리머가 유튜브를 연결했나」만 묻는 창구(POK-291).
 *
 * <p>resolve와 판정은 같고(NOT_LINKED · UNLINKED · BROKEN) <b>토큰을 돌려주지 않고 갱신도 하지 않는다.</b>
 * clip은 토큰이 필요 없다: 받으면 clip 로그·메모리에 토큰이 흘러든다.
 *
 * <p>본문은 문자열 전체로 비교한다. {@code jsonPath(...).doesNotExist()}는 값이 null인 키도 통과시켜
 * 「토큰 키가 없다」를 재지 못한다(auth/CLAUDE.md 「시험이 안 재고 있던 자리」).
 */
class YoutubeLinkStatusCheckTest extends YoutubeLinkTestSupport {

    private static final String LINKED = "{\"linked\":true,\"reason\":null}";

    YoutubeLinkStatusCheckTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                               TokenService tokenService, YoutubeLinkStateCodec codec,
                               YoutubeChannelLinkRepository linkRepository, SecretStore secretStore,
                               YoutubeLinkWriter writer, JdbcTemplate jdbc, YoutubeCleanupExecutor cleanup) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer,
                jdbc, cleanup);
    }

    @Test
    void 연동이_살아_있으면_linked_true이고_토큰도_채널도_싣지_않는다() throws Exception {
        User u = newUser();
        linked(u, "at-old", "rt-old");

        String body = statusBody(u.getId());

        assertThat(body).isEqualTo(LINKED);
        assertThat(body).as("상태만 묻는 창구가 토큰을 실었다").doesNotContain("accessToken").doesNotContain("at-old");
    }

    @Test
    void 연동한_적이_없으면_NOT_LINKED() throws Exception {
        assertThat(statusBody(newUser().getId())).isEqualTo("{\"linked\":false,\"reason\":\"NOT_LINKED\"}");
    }

    /** 없는 회원 번호도 resolve와 같이 NOT_LINKED다. 캐시 밖 번호로 잰다(Long 캐시 함정). */
    @Test
    void 없는_회원도_NOT_LINKED() throws Exception {
        assertThat(statusBody(987_654_321L)).isEqualTo("{\"linked\":false,\"reason\":\"NOT_LINKED\"}");
    }

    @Test
    void 사용자가_해제했으면_UNLINKED() throws Exception {
        User u = newUser();
        linked(u, "at-old", "rt-old");
        mockMvc.perform(delete("/api/youtube-link").header("Authorization", bearer(u)))
                .andExpect(status().isNoContent());
        awaitCleanup();

        assertThat(statusBody(u.getId())).isEqualTo("{\"linked\":false,\"reason\":\"UNLINKED\"}");
    }

    /** 갱신이 거부돼 닫힌 행. 구글을 거치지 않고 표를 그 상태로 만든다(이 창구는 구글을 부르면 안 되므로). */
    @Test
    void 갱신이_거부돼_닫혔으면_BROKEN() throws Exception {
        User u = newUser();
        YoutubeChannelLink link = linked(u, "at-old", "rt-old");
        jdbc.update("UPDATE youtube_channel_links SET revoked_at = now(), revoke_reason = 'REFRESH_REJECTED' "
                + "WHERE id = ?", link.getId());

        assertThat(statusBody(u.getId())).isEqualTo("{\"linked\":false,\"reason\":\"BROKEN\"}");
    }

    /** 해제 뒤 다시 연동하면 마지막 행이 살아 있는 행이다: 옛 UNLINKED 행에 끌려가지 않는다. */
    @Test
    void 해제_뒤_재연동하면_다시_linked_true() throws Exception {
        User u = newUser();
        linked(u, "at-old", "rt-old");
        mockMvc.perform(delete("/api/youtube-link").header("Authorization", bearer(u)))
                .andExpect(status().isNoContent());
        awaitCleanup();
        linked(u, "at-new", "rt-new");

        assertThat(statusBody(u.getId())).isEqualTo(LINKED);
    }

    /**
     * 🔴 resolve라면 즉석 갱신할 상태(남은 수명 10분 · 이미 만료)에서도 이 창구는 구글을 부르지 않고
     * 표의 access 만료 시각도 그대로 둔다. 갱신이 끼면 clip의 렌더 주문 하나마다 구글 할당량을 쓰고
     * 회원 행 락을 잡는다.
     */
    @Test
    void 만료가_임박하거나_지났어도_갱신하지_않는다() throws Exception {
        User soon = newUser();
        YoutubeChannelLink soonLink = linked(soon, "at-old", "rt-old");
        accessRemaining(soonLink, Duration.ofMinutes(10));
        User gone = newUser();
        YoutubeChannelLink goneLink = linked(gone, "at-old", "rt-old");
        accessRemaining(goneLink, Duration.ofMinutes(-5));
        Timestamp soonBefore = expiresAt(soonLink);
        Timestamp goneBefore = expiresAt(goneLink);

        assertThat(statusBody(soon.getId())).isEqualTo(LINKED);
        assertThat(statusBody(gone.getId())).isEqualTo(LINKED);

        assertThat(YOUTUBE.tokenCalls()).as("상태만 묻는데 구글 토큰 갱신을 불렀다").isZero();
        assertThat(expiresAt(soonLink)).as("갱신이 일어나 만료 시각이 바뀌었다").isEqualTo(soonBefore);
        assertThat(expiresAt(goneLink)).as("갱신이 일어나 만료 시각이 바뀌었다").isEqualTo(goneBefore);
    }

    /**
     * 토큰 원문이 secrets에서 사라져도 200 linked다: 이 창구는 secrets를 아예 안 읽는다.
     * 갱신기를 부르면 갱신기는 원문을 읽다가 IllegalStateException을 던져 500이 된다. 그래서 이 시험은
     * 「만료가 넉넉해 구글을 안 부르는 갈래」까지 포함해 갱신기를 부르는 모든 길을 잡는다.
     */
    @Test
    void 토큰_원문이_없어도_secrets를_읽지_않아_200() throws Exception {
        User u = newUser();
        YoutubeChannelLink link = linked(u, "at-old", "rt-old");
        secretStore.delete(link.getAccessTokenRef());
        secretStore.delete(link.getRefreshTokenRef());

        assertThat(statusBody(u.getId())).isEqualTo(LINKED);
    }

    @Test
    void 회원_번호가_없으면_400() throws Exception {
        mockMvc.perform(post("/internal/youtube-link/status").header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 내부_토큰_없이는_401() throws Exception {
        mockMvc.perform(post("/internal/youtube-link/status").contentType(APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 내부_토큰이_틀리면_401() throws Exception {
        mockMvc.perform(post("/internal/youtube-link/status").header("X-Internal-Token", "wrong-token")
                        .contentType(APPLICATION_JSON).content("{\"userId\":1}"))
                .andExpect(status().isUnauthorized());
    }

    /** 내부 API는 사용자 JWT를 안 받는다: 별도 체인이라 사용자 토큰으로는 못 연다. */
    @Test
    void 사용자_JWT로는_401() throws Exception {
        User u = newUser();

        mockMvc.perform(post("/internal/youtube-link/status").header("Authorization", bearer(u))
                        .contentType(APPLICATION_JSON).content("{\"userId\":" + u.getId() + "}"))
                .andExpect(status().isUnauthorized());
    }

    private String statusBody(Long userId) throws Exception {
        return mockMvc.perform(statusRequest(userId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static MockHttpServletRequestBuilder statusRequest(Long userId) {
        return post("/internal/youtube-link/status").header("X-Internal-Token", INTERNAL_TOKEN)
                .contentType(APPLICATION_JSON).content("{\"userId\":" + userId + "}");
    }

    private Timestamp expiresAt(YoutubeChannelLink link) {
        return jdbc.queryForObject("SELECT access_expires_at FROM youtube_channel_links WHERE id = ?",
                Timestamp.class, link.getId());
    }
}
