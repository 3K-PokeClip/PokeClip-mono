package com.pokeclip.auth.youtube.api;

import com.jayway.jsonpath.JsonPath;
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
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 동의 왕복의 정상 경로와 거절 경로. 「실패했을 때 무엇을 버리는가」는 {@code YoutubeLinkFailureCleanupTest}가
 * 따로 잰다 — 이 PR에서 제일 비싼 실패라 한 파일에 모았다.
 */
class YoutubeLinkStartAndLinkTest extends YoutubeLinkTestSupport {

    YoutubeLinkStartAndLinkTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                                TokenService tokenService, YoutubeLinkStateCodec codec,
                                YoutubeChannelLinkRepository linkRepository, SecretStore secretStore,
                                YoutubeLinkWriter writer, JdbcTemplate jdbc, YoutubeCleanupExecutor cleanup) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer,
                jdbc, cleanup);
    }

    /**
     * {@code access_type=offline}·{@code prompt=consent}가 빠지면 재동의에서 refresh_token이 오지 않아
     * 연동이 반쪽이 된다(실측 A ②가 이 둘의 효과를 확인했다). scope는 둘 다 필요하다 —
     * upload만으로는 channels.list가 403 insufficientPermissions다(실측 A ④).
     */
    @Test
    void start는_offline_consent_scope_둘_state가_든_동의_URL을_준다() throws Exception {
        String body = mockMvc.perform(post("/api/youtube-link/start").header("Authorization", bearer(newUser())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(body, "$.authorizeUrl");

        assertThat(url).startsWith("https://accounts.google.com/o/oauth2/v2/auth?")
                .contains("client_id=test-youtube-client-id")
                .contains("response_type=code")
                .contains("access_type=offline")
                .contains("prompt=consent")
                .contains("youtube.upload")
                .contains("youtube.readonly")
                .contains("state=");
        assertThat(url).as("동의 URL에 시크릿이 실렸다").doesNotContain("test-youtube-client-secret");
    }

    @Test
    void 정상_왕복이면_201_표에는_참조만_secrets에_둘() throws Exception {
        User user = newUser();
        String state = codec.issue(user.getId(), Instant.now());

        mockMvc.perform(post("/api/youtube-link").header("Authorization", bearer(user)).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"code-1\",\"state\":\"" + state + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.channelId").value("chan-default"))
                .andExpect(jsonPath("$.channelName").value("채널"))
                .andExpect(jsonPath("$.linkedAt").exists());

        YoutubeChannelLink link = linkRepository.findByUserIdAndRevokedAtIsNull(user.getId()).orElseThrow();
        assertThat(link.getChannelId()).isEqualTo("chan-default");
        assertThat(link.getScope()).as("받은 scope를 그대로 적어 둔다 — 뒷날 권한 축소를 알아보는 유일한 단서다")
                .contains("youtube.upload").contains("youtube.readonly");
        assertThat(secretStore.get(link.getAccessTokenRef())).contains("at-1");
        assertThat(secretStore.get(link.getRefreshTokenRef())).contains("rt-1");
        assertThat(secretCount()).isEqualTo(2);
        // 교환은 form 하나에 code만 싣는다 — state는 우리 서명이라 구글에 보내지 않는다.
        assertThat(YOUTUBE.tokenRequests().get(0))
                .containsEntry("code", "code-1")
                .containsEntry("grant_type", "authorization_code")
                .doesNotContainKey("state");
        assertThat(YOUTUBE.lastChannelsBearer()).isEqualTo("Bearer at-1");
        assertThat(YOUTUBE.lastChannelsQuery()).contains("mine=true");
        assertThat(YOUTUBE.revokeCalls()).as("성공 왕복이 revoke를 불렀다").isZero();
    }

    /** CSRF — 공격자 링크로 남의 계정에 자기 채널을 붙이는 경로. */
    @Test
    void 다른_사용자의_state면_400이고_구글을_부르지_않는다() throws Exception {
        User victim = newUser();
        User attacker = newUser();
        String attackersState = codec.issue(attacker.getId(), Instant.now());

        mockMvc.perform(post("/api/youtube-link").header("Authorization", bearer(victim))
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"c\",\"state\":\"" + attackersState + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("INVALID_STATE"));

        assertThat(YOUTUBE.tokenCalls()).isZero();
        assertThat(linkRepository.findByUserIdAndRevokedAtIsNull(victim.getId())).isEmpty();
    }

    @Test
    void 만료된_state면_400이고_구글을_부르지_않는다() throws Exception {
        User user = newUser();
        String stale = codec.issue(user.getId(), Instant.now().minus(Duration.ofMinutes(11)));

        mockMvc.perform(post("/api/youtube-link").header("Authorization", bearer(user)).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"c\",\"state\":\"" + stale + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("INVALID_STATE"));

        assertThat(YOUTUBE.tokenCalls()).isZero();
    }

    @Test
    void 교환이_4xx면_400_INVALID_CODE_5xx면_502() throws Exception {
        User user = newUser();
        YOUTUBE.tokenResponds(400, "{\"error\":\"invalid_grant\"}");

        mockMvc.perform(link(user)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_CODE"));

        YOUTUBE.tokenResponds(503, "{}");
        mockMvc.perform(link(user)).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.reason").value("YOUTUBE_UNAVAILABLE"));

        assertThat(linkRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
        assertThat(YOUTUBE.channelsCalls()).as("교환이 실패했는데 채널을 물었다").isZero();
    }

    /**
     * 403이라도 할당량 소진은 일시다 — 태평양시 자정에 스스로 풀린다. 400(동의부터 다시)로 내보내면
     * 사용자가 몇 번을 다시 동의해도 같은 자리에서 막힌다. 오류 코드는 YouTube Data API 형식
     * (객체 {@code error.errors[0].reason})으로 온다 — 실측 A ④에서 실물로 확인한 모양이다.
     */
    @Test
    void 채널_조회가_403_quotaExceeded면_502다() throws Exception {
        User user = newUser();
        YOUTUBE.channelsResponds(403, "{\"error\":{\"code\":403,\"message\":\"quota\","
                + "\"errors\":[{\"reason\":\"quotaExceeded\",\"domain\":\"youtube.quota\"}]}}");

        mockMvc.perform(link(user)).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.reason").value("YOUTUBE_UNAVAILABLE"));

        assertThat(linkRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
    }

    /**
     * upload만 준 토큰으로 channels.list를 부르면 403 {@code insufficientPermissions}다(실측 A ④).
     * 그 코드는 일시 특례에 없으므로 영구 거절 = 동의부터 다시(400)가 맞다.
     */
    @Test
    void 채널_조회가_403_insufficientPermissions면_400_INVALID_CODE다() throws Exception {
        User user = newUser();
        YOUTUBE.channelsResponds(403, "{\"error\":{\"code\":403,"
                + "\"errors\":[{\"reason\":\"insufficientPermissions\"}]}}");

        mockMvc.perform(link(user)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_CODE"));
    }

    /**
     * 실물은 항상 1개다(2026-08-24 실측 — 동의 시점에 채널이 확정되고 그 토큰의 channels.list는 고른 채널만 준다).
     * 이 검사는 오늘 열리는 경로가 아니라 방어를 못박는다 — 목록이 여럿이어도 「첫 번째」로 결정론적이어야 한다.
     */
    @Test
    void 채널이_둘이면_목록의_첫_번째로_확정한다() throws Exception {
        User user = newUser();
        YOUTUBE.channelsResponds(200, "{\"items\":[{\"id\":\"chan-first\",\"snippet\":{\"title\":\"첫째\"}},"
                + "{\"id\":\"chan-second\",\"snippet\":{\"title\":\"둘째\"}}]}");

        mockMvc.perform(link(user)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.channelId").value("chan-first"));

        assertThat(linkRepository.findByUserIdAndRevokedAtIsNull(user.getId()).orElseThrow().getChannelId())
                .isEqualTo("chan-first");
    }

    /** 채널이 하나도 없는 계정. items 키가 아예 없어도 「0개」지 형식 붕괴가 아니다(계획 2절 결정 9). */
    @Test
    void 채널이_0개면_400_NO_CHANNEL이다_빈_배열도_키_부재도() throws Exception {
        User user = newUser();
        YOUTUBE.channelsResponds(200, "{\"items\":[],\"pageInfo\":{\"totalResults\":0}}");

        mockMvc.perform(link(user)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("NO_CHANNEL"));

        YOUTUBE.channelsResponds(200, "{\"kind\":\"youtube#channelListResponse\",\"pageInfo\":{\"totalResults\":0}}");
        mockMvc.perform(link(user)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("NO_CHANNEL"));

        assertThat(linkRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
    }

    /** 동의 화면에서 업로드 체크를 지운 경우. 응답 scope 순서는 요청과 반대로 온다(실측) — 포함 여부로 본다. */
    @Test
    void 받은_scope에_upload가_없으면_400_SCOPE_MISSING이다() throws Exception {
        User user = newUser();
        YOUTUBE.tokenResponds(200, "{\"access_token\":\"at-x\",\"refresh_token\":\"rt-x\",\"expires_in\":3600,"
                + "\"scope\":\"https://www.googleapis.com/auth/youtube.readonly\"}");

        mockMvc.perform(link(user)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("SCOPE_MISSING"));

        assertThat(YOUTUBE.channelsCalls()).as("scope가 모자란데 채널을 물었다").isZero();
        assertThat(linkRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
    }

    /** 응답 scope 순서가 요청과 반대다(실측 2026-08-24). 순서에 기대면 정상 동의가 SCOPE_MISSING이 된다. */
    @Test
    void scope가_요청과_반대_순서로_와도_통과한다() throws Exception {
        User user = newUser();
        YOUTUBE.tokenResponds(200, "{\"access_token\":\"at-x\",\"refresh_token\":\"rt-x\",\"expires_in\":3600,"
                + "\"scope\":\"https://www.googleapis.com/auth/youtube.readonly "
                + "https://www.googleapis.com/auth/youtube.upload\"}");

        mockMvc.perform(link(user)).andExpect(status().isCreated());
    }

    /** 다른 계정이 이미 쓰는 채널. 사전 조회가 걸러 409고, 표에는 새 행이 안 생긴다. */
    @Test
    void 다른_계정에_묶인_채널이면_409() throws Exception {
        User owner = newUser();
        YoutubeChannelLink mine = linked(owner);
        YOUTUBE.channelsResponds(200, "{\"items\":[{\"id\":\"" + mine.getChannelId()
                + "\",\"snippet\":{\"title\":\"채널\"}}]}");
        User other = newUser();

        mockMvc.perform(link(other)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("CHANNEL_ALREADY_LINKED"));

        assertThat(linkRepository.findFirstByUserIdOrderByCreatedAtDesc(other.getId())).isEmpty();
    }

    @Test
    void JWT_없이는_401() throws Exception {
        mockMvc.perform(post("/api/youtube-link/start")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/youtube-link").contentType(APPLICATION_JSON)
                        .content("{\"code\":\"c\",\"state\":\"s\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(YOUTUBE.tokenCalls()).isZero();
    }
}
