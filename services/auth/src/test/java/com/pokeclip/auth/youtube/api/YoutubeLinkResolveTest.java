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

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 워커·clip이 부르는 창구. <b>항상 200</b>이다 — 「연동 안 됨/끊김」(업로드를 안 한다)과 「Auth 장애」(판단 불가)는
 * 조치가 정반대라 둘 다 4xx면 호출자가 구분할 수 없다.
 *
 * <p>거절 응답에 accessToken 필드가 <b>아예 나타나지 않아야</b> 한다(NON_NULL) — 「null이지만 키는 있다」는
 * 호출자가 실수로 문자열 "null"을 실어 보내는 자리를 만든다.
 */
class YoutubeLinkResolveTest extends YoutubeLinkTestSupport {

    YoutubeLinkResolveTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                           TokenService tokenService, YoutubeLinkStateCodec codec,
                           YoutubeChannelLinkRepository linkRepository, SecretStore secretStore,
                           YoutubeLinkWriter writer, JdbcTemplate jdbc, YoutubeCleanupExecutor cleanup) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer,
                jdbc, cleanup);
    }

    /**
     * 🔴 「키가 없다」는 {@code jsonPath(...).doesNotExist()}로는 못 잰다 — 그 단언은 <b>값이 null인 키도
     * 통과시킨다</b>(NON_NULL을 지우고 돌려 실측했다: 그 단언만으로는 초록이었다). 본문 문자열로 본다.
     */
    @Test
    void 미연동이면_200에_valid_false_NOT_LINKED이고_토큰_키가_아예_없다() throws Exception {
        String body = mockMvc.perform(resolve(newUser().getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("NOT_LINKED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("거절 응답에 토큰·채널 키가 나타났다 — 호출자가 문자열 null을 실어 보낼 자리가 열린다")
                .doesNotContain("accessToken").doesNotContain("channelId").doesNotContain("expiresAt");
    }

    /** 남은 수명 60분 > 요구 30분 — 갱신 없이 그대로 준다. */
    @Test
    void 수명이_충분하면_갱신_없이_토큰과_채널을_준다() throws Exception {
        User u = newUser();
        linked(u, "at-old", "rt-old");

        mockMvc.perform(resolve(u.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.channelId").exists())
                .andExpect(jsonPath("$.accessToken").value("at-old"))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.reason").doesNotExist());

        assertThat(YOUTUBE.tokenCalls()).isZero();
    }

    /** 구글 access는 1시간짜리라 워커가 오래 붙어 있으면 손에 쥔 토큰이 죽는다 — 30분 미만이면 즉석 갱신해서 준다. */
    @Test
    void 남은_수명이_30분_미만이면_즉석_갱신한_새_토큰을_준다() throws Exception {
        User u = newUser();
        accessRemaining(linked(u, "at-old", "rt-old"), Duration.ofMinutes(10));

        mockMvc.perform(resolve(u.getId())).andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.accessToken").value("at-1"));

        assertThat(YOUTUBE.tokenCalls()).isEqualTo(1);
    }

    /** 임박한 토큰을 그냥 주면 워커가 곧 죽는 토큰으로 업로드를 시작한다 — 차라리 거절한다. */
    @Test
    void 즉석_갱신이_일시_실패면_임박_토큰을_주지_않고_REFRESH_UNAVAILABLE() throws Exception {
        User u = newUser();
        accessRemaining(linked(u, "at-old", "rt-old"), Duration.ofMinutes(10));
        YOUTUBE.tokenResponds(503, "{}");

        String body = mockMvc.perform(resolve(u.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("REFRESH_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("임박한 토큰을 거절 응답에 실었다").doesNotContain("accessToken").doesNotContain("at-old");
    }

    @Test
    void 즉석_갱신이_invalid_grant면_BROKEN이고_다시_시도하지_않는다() throws Exception {
        User u = newUser();
        accessRemaining(linked(u, "at-old", "rt-old"), Duration.ofMinutes(10));
        YOUTUBE.tokenResponds(400, "{\"error\":\"invalid_grant\"}");

        String body = mockMvc.perform(resolve(u.getId())).andExpect(jsonPath("$.reason").value("BROKEN"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).as("끊긴 연동의 응답에 토큰 키가 남았다").doesNotContain("accessToken").doesNotContain("at-old");
        awaitCleanup();
        mockMvc.perform(resolve(u.getId())).andExpect(jsonPath("$.reason").value("BROKEN"));   // 행이 닫혔어도 사유는 유지

        assertThat(YOUTUBE.tokenCalls()).as("BROKEN인데 다시 구글을 불렀다").isEqualTo(1);
    }

    /** 사용자가 스스로 끊은 것과 갱신이 거부된 것은 호출자에게 다른 사건이다 — 전자는 재연동 안내, 후자는 재동의 안내. */
    @Test
    void 사용자가_해제했으면_UNLINKED다() throws Exception {
        User u = newUser();
        linked(u, "at-old", "rt-old");
        mockMvc.perform(delete("/api/youtube-link").header("Authorization", bearer(u)))
                .andExpect(status().isNoContent());
        awaitCleanup();

        String body = mockMvc.perform(resolve(u.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("UNLINKED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).as("해제된 연동의 응답에 토큰 키가 남았다").doesNotContain("accessToken").doesNotContain("at-old");
    }

    /**
     * 🔴 태스크 6·7이 미뤄 둔 마지막 확인 — 치명-1을 <b>창구로</b> 잰다. 가짜 구글을 실물처럼(revoke 한 번이면
     * grant 전체가 죽는다) 켠 채 재연동하면, resolve가 유효한 토큰을 줘야 한다. 재연동 정리가 revoke를 부르면
     * 여기서 즉석 갱신이 invalid_grant를 맞아 <b>valid:false BROKEN</b>이 된다 — 사용자가 방금 다시 연동했는데
     * 워커는 「끊겼다」를 보는 그 사건이다.
     */
    @Test
    void 캐스케이드를_켠_채_재연동해도_resolve가_유효한_토큰을_준다() throws Exception {
        YOUTUBE.cascadeOnRevoke(true);
        User u = newUser();
        linked(u, "at-old", "rt-old");
        YoutubeChannelLink fresh = linked(u, "at-new", "rt-new");
        awaitCleanup();
        accessRemaining(fresh, Duration.ofMinutes(10));   // 즉석 갱신을 강제해 새 refresh가 실제로 통하는지 본다

        mockMvc.perform(resolve(u.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.accessToken").value("at-1"))
                .andExpect(jsonPath("$.reason").doesNotExist());
    }

    /**
     * 대조군 — 해제 뒤에는 <b>우리 표가 UNLINKED</b>라 창구가 거절한다. 캐스케이드 모드를 켜 두었는데도
     * 구글 호출이 0인 것이 「해제는 구글에 안 보낸다」의 증거다.
     */
    @Test
    void 대조군_해제_뒤_같은_회원이_다시_연동하지_않으면_resolve는_UNLINKED다() throws Exception {
        YOUTUBE.cascadeOnRevoke(true);
        User u = newUser();
        linked(u, "at-old", "rt-old");
        mockMvc.perform(delete("/api/youtube-link").header("Authorization", bearer(u)))
                .andExpect(status().isNoContent());
        awaitCleanup();

        assertThat(YOUTUBE.revokeCalls()).as("해제는 구글에 아무것도 보내지 않는다").isZero();
        mockMvc.perform(resolve(u.getId())).andExpect(jsonPath("$.reason").value("UNLINKED"));
    }

    @Test
    void 내부_토큰_없이는_401() throws Exception {
        mockMvc.perform(post("/internal/youtube-link/resolve").contentType(APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isUnauthorized());
    }

    /** 내부 API는 JWT를 안 받는다 — 별도 체인(@Order(1))이라 사용자 토큰으로는 못 연다. */
    @Test
    void 사용자_JWT로는_401() throws Exception {
        User u = newUser();

        mockMvc.perform(post("/internal/youtube-link/resolve").header("Authorization", bearer(u))
                        .contentType(APPLICATION_JSON).content("{\"userId\":" + u.getId() + "}"))
                .andExpect(status().isUnauthorized());
    }
}
