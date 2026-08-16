package com.pokeclip.auth.chzzk.api;

import com.jayway.jsonpath.JsonPath;
import com.pokeclip.auth.chzzk.ChzzkChannelLink;
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
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChzzkLinkStartAndLinkTest extends ChzzkLinkTestSupport {

    ChzzkLinkStartAndLinkTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                              TokenService tokenService, ChzzkLinkStateCodec codec,
                              ChzzkChannelLinkRepository linkRepository, SecretStore secretStore,
                              ChzzkLinkWriter writer, JdbcTemplate jdbc, ChzzkCleanupExecutor cleanup) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer, jdbc, cleanup);
    }

    @Test
    void start는_앱ID_리다이렉트_state가_든_동의_URL을_준다() throws Exception {
        String body = mockMvc.perform(post("/api/chzzk-link/start").header("Authorization", bearer(newUser())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(body, "$.authorizeUrl");
        assertThat(url).startsWith("https://chzzk.naver.com/account-interlock?")
                .contains("clientId=test-chzzk-client-id")
                // UriComponentsBuilder.encode()는 쿼리값의 :·/를 인코딩하지 않는다(Spring 7.0.8 실측).
                // 이 형태가 실제 스크립트(get-chzzk-token.sh)로 검증된 형태다.
                .contains("redirectUri=http://localhost:8081/oauth/chzzk/callback")
                .contains("state=");
    }

    @Test
    void 정상_왕복이면_201_표에는_참조만_secrets에_둘() throws Exception {
        User user = newUser();
        String state = codec.issue(user.getId(), Instant.now());
        mockMvc.perform(post("/api/chzzk-link").header("Authorization", bearer(user)).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"code-1\",\"state\":\"" + state + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.channelId").value("chan-default"))
                .andExpect(jsonPath("$.channelName").value("채널"))
                .andExpect(jsonPath("$.linkedAt").exists());
        ChzzkChannelLink link = linkRepository.findByUserIdAndRevokedAtIsNull(user.getId()).orElseThrow();
        assertThat(link.getChannelId()).isEqualTo("chan-default");
        assertThat(secretStore.get(link.getAccessTokenRef())).contains("at-1");
        assertThat(secretStore.get(link.getRefreshTokenRef())).contains("rt-1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM secrets", Integer.class)).isEqualTo(2);
        assertThat(CHZZK.tokenRequests().get(0)).containsEntry("code", "code-1").containsEntry("state", state);
        assertThat(CHZZK.lastMeBearer()).isEqualTo("Bearer at-1");
    }

    /** CSRF — 공격자 링크로 남의 계정에 자기 채널을 붙이는 경로. */
    @Test
    void 다른_사용자의_state면_400이고_치지직을_부르지_않는다() throws Exception {
        User victim = newUser();
        User attacker = newUser();
        String attackersState = codec.issue(attacker.getId(), Instant.now());
        mockMvc.perform(post("/api/chzzk-link").header("Authorization", bearer(victim)).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"c\",\"state\":\"" + attackersState + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("INVALID_STATE"));
        assertThat(CHZZK.tokenCalls()).isZero();
        assertThat(linkRepository.findByUserIdAndRevokedAtIsNull(victim.getId())).isEmpty();
    }

    @Test
    void 만료된_state면_400() throws Exception {
        User user = newUser();
        String stale = codec.issue(user.getId(), Instant.now().minus(Duration.ofMinutes(11)));
        mockMvc.perform(post("/api/chzzk-link").header("Authorization", bearer(user)).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"c\",\"state\":\"" + stale + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("INVALID_STATE"));
        assertThat(CHZZK.tokenCalls()).isZero();
    }

    @Test
    void 교환이_4xx면_400_INVALID_CODE_5xx면_502() throws Exception {
        User user = newUser();
        CHZZK.tokenResponds(403, "{\"code\":403,\"message\":\"잘못된 인증 코드\"}");
        mockMvc.perform(link(user)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_CODE"));
        CHZZK.tokenResponds(503, "{}");
        mockMvc.perform(link(user)).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.reason").value("CHZZK_UNAVAILABLE"));
        assertThat(linkRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
        assertThat(CHZZK.meCalls()).isZero();
    }

    /** 교환은 됐는데 me가 실패하면 받은 토큰이 치지직에 살아 있다 — 버리고 실패한다(409 경로와 같은 이유). */
    @Test
    void me가_4xx면_400_INVALID_CODE_5xx면_502() throws Exception {
        User user = newUser();
        CHZZK.meResponds(401, "{\"code\":401,\"message\":\"INVALID_TOKEN\"}");
        mockMvc.perform(link(user)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_CODE"));
        assertThat(CHZZK.revokedTokens()).containsExactlyInAnyOrder("at-1", "rt-1");
        CHZZK.meResponds(500, "{}");
        mockMvc.perform(link(user)).andExpect(status().isBadGateway());
        assertThat(CHZZK.revokedTokens()).containsExactlyInAnyOrder("at-1", "rt-1", "at-2", "rt-2");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM secrets", Integer.class)).isZero();
        assertThat(linkRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
    }

    /** 채널은 me로만 확정한다. 본문의 channelId는 무시된다(Jackson unknown-properties 꺼짐 — 실물 확인). */
    @Test
    void 요청_본문에_채널을_보내도_무시된다() throws Exception {
        User user = newUser();
        String state = codec.issue(user.getId(), Instant.now());
        mockMvc.perform(post("/api/chzzk-link").header("Authorization", bearer(user)).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"c\",\"state\":\"" + state + "\",\"channelId\":\"attacker-chan\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.channelId").value("chan-default"));
    }

    @Test
    void JWT_없이는_401() throws Exception {
        mockMvc.perform(post("/api/chzzk-link/start")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/chzzk-link").contentType(APPLICATION_JSON).content("{\"code\":\"c\",\"state\":\"s\"}"))
                .andExpect(status().isUnauthorized());
    }
}
