package com.pokeclip.auth.soop.api;

import com.jayway.jsonpath.JsonPath;
import com.pokeclip.auth.soop.SoopChannelLink;
import com.pokeclip.auth.soop.SoopChannelLinkRepository;
import com.pokeclip.auth.soop.SoopCleanupExecutor;
import com.pokeclip.auth.soop.SoopLinkStateCodec;
import com.pokeclip.auth.soop.SoopLinkTestSupport;
import com.pokeclip.auth.soop.SoopLinkWriter;
import com.pokeclip.auth.soop.SoopRefreshBrake;
import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.support.FakeSoopServer;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SoopLinkStartAndLinkTest extends SoopLinkTestSupport {

    SoopLinkStartAndLinkTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                             TokenService tokenService, SoopLinkStateCodec codec,
                             SoopChannelLinkRepository linkRepository, SecretStore secretStore,
                             SoopLinkWriter writer, JdbcTemplate jdbc, SoopCleanupExecutor cleanup,
                    SoopRefreshBrake brake) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer, jdbc, cleanup, brake);
    }

    /** SOOP 문서대로 client_id·state만 싣는다(계획 검증 M10). redirect_uri·시크릿은 없다. */
    @Test
    void start는_client_id와_state만_든_동의_URL을_준다() throws Exception {
        User user = newUser();
        String body = mockMvc.perform(post("/api/soop-link/start").header("Authorization", bearer(user)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(body, "$.authorizeUrl");
        assertThat(url).startsWith("https://openapi.sooplive.com/auth/code?")
                .contains("client_id=test-soop-client-id")
                .doesNotContain("redirect_uri").doesNotContain("clientId").doesNotContain("test-soop-client-secret");
        String state = url.substring(url.indexOf("state=") + "state=".length());
        assertThat(codec.matches(state, user.getId(), Instant.now())).as("동의 URL의 state가 이 회원 것이다").isTrue();
    }

    @Test
    void 정상_왕복이면_201_표에는_참조만_secrets에_둘이고_응답에_SOOP_아이디가_없다() throws Exception {
        User user = newUser();
        String state = codec.issue(user.getId(), Instant.now());
        String body = mockMvc.perform(post("/api/soop-link").header("Authorization", bearer(user))
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"code-1\",\"state\":\"" + state + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nickname").value(FakeSoopServer.DEFAULT_NICK))
                .andExpect(jsonPath("$.linkedAt").exists())
                .andReturn().getResponse().getContentAsString();
        // 키 이름이 아니라 값으로 본다(계획 검증 C7): 칸 이름을 무엇으로 바꿔 실어도 값은 남는다.
        assertThat(body).doesNotContain(FakeSoopServer.DEFAULT_USER_ID).doesNotContain("soopUserId");

        SoopChannelLink link = linkRepository.findByUserIdAndRevokedAtIsNull(user.getId()).orElseThrow();
        assertThat(link.getSoopUserId()).isEqualTo(FakeSoopServer.DEFAULT_USER_ID);
        assertThat(link.getNickname()).isEqualTo(FakeSoopServer.DEFAULT_NICK);
        assertThat(link.getAccessExpiresAt()).isAfter(Instant.now().plus(Duration.ofHours(7)));
        assertThat(secretStore.get(link.getAccessTokenRef())).contains("at-1");
        assertThat(secretStore.get(link.getRefreshTokenRef())).contains("rt-1");
        assertThat(link.getAccessTokenRef()).startsWith("soop-access:");
        assertThat(link.getRefreshTokenRef()).startsWith("soop-refresh:");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM secrets", Integer.class)).isEqualTo(2);
        assertThat(SOOP.tokenRequests().get(0)).containsEntry("code", "code-1");
        assertThat(SOOP.statusRequests()).anySatisfy(r -> assertThat(r).containsEntry("access_token", "at-1"));
    }

    @Test
    void 연동_뒤_GET은_ACTIVE와_닉네임을_주고_SOOP_아이디는_안_준다() throws Exception {
        User user = newUser();
        mockMvc.perform(link(user)).andExpect(status().isCreated());

        String body = mockMvc.perform(get("/api/soop-link").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.nickname").value(FakeSoopServer.DEFAULT_NICK))
                .andExpect(jsonPath("$.linkedAt").exists())
                .andExpect(jsonPath("$.accessExpiresAt").exists())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(FakeSoopServer.DEFAULT_USER_ID).doesNotContain("soopUserId");
    }

    /** 행이 없으면 {linked:false} 하나뿐이다. null 칸이 실리지 않는다(본문 문자열로 본다, doesNotExist 금지). */
    @Test
    void 연동한_적_없으면_GET은_linked_false만() throws Exception {
        String body = mockMvc.perform(get("/api/soop-link").header("Authorization", bearer(newUser())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).isEqualTo("{\"linked\":false}");
    }

    /** CSRF: 공격자 링크로 남의 계정에 자기 방송국을 붙이는 경로. */
    @Test
    void 다른_사용자의_state면_400이고_SOOP을_부르지_않는다() throws Exception {
        User victim = newUser();
        User attacker = newUser();
        String attackersState = codec.issue(attacker.getId(), Instant.now());
        mockMvc.perform(post("/api/soop-link").header("Authorization", bearer(victim)).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"c\",\"state\":\"" + attackersState + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("INVALID_STATE"));
        assertThat(SOOP.tokenCalls()).isZero();
        assertThat(linkRepository.findByUserIdAndRevokedAtIsNull(victim.getId())).isEmpty();
    }

    @Test
    void 만료된_state면_400() throws Exception {
        User user = newUser();
        String stale = codec.issue(user.getId(), Instant.now().minus(Duration.ofMinutes(11)));
        mockMvc.perform(post("/api/soop-link").header("Authorization", bearer(user)).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"c\",\"state\":\"" + stale + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("INVALID_STATE"));
        assertThat(SOOP.tokenCalls()).isZero();
    }

    @Test
    void 교환이_4xx면_400_INVALID_CODE_5xx면_502_SOOP_UNAVAILABLE() throws Exception {
        User user = newUser();
        SOOP.tokenResponds(400, "{\"error\":\"invalid_grant\"}");
        mockMvc.perform(link(user)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_CODE"));
        SOOP.tokenResponds(503, "{}");
        mockMvc.perform(link(user)).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.reason").value("SOOP_UNAVAILABLE"));
        assertThat(linkRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
        assertThat(SOOP.statusCalls()).isZero();
    }

    /**
     * 교환은 됐는데 아이디 조회가 실패하면 받은 토큰이 SOOP에 살아 있다. 버릴 API가 없으니 그 사실만 INFO로 남긴다.
     * 교환 자체가 실패하면 받은 것이 없어 그 줄도 없다.
     */
    @Test
    void 아이디_조회가_거부되면_400이고_버리지_못한_토큰을_한_줄_남긴다() throws Exception {
        User user = newUser();
        SOOP.statusResponds(200, "{\"result\":-1,\"msg\":\"invalid\"}");
        try (LogCaptor logs = new LogCaptor()) {
            mockMvc.perform(link(user)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("INVALID_CODE"));
            assertThat(logs.messages()).contains("auth.soop.link.token_orphaned userId=" + user.getId());
            assertThat(logs.messages()).noneMatch(m -> m.contains("at-1") || m.contains("rt-1"));
        }
        SOOP.reset();
        SOOP.tokenResponds(400, "{}");
        try (LogCaptor logs = new LogCaptor()) {
            mockMvc.perform(link(user)).andExpect(status().isBadRequest());
            assertThat(logs.messages()).noneMatch(m -> m.startsWith("auth.soop.link.token_orphaned"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM secrets", Integer.class)).isZero();
        assertThat(linkRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
    }

    /**
     * 🔴 아이디를 어디서도 못 읽으면(방송 중이 아니고 대표 이미지가 LOGO 꼴이 아님·없음) 422 IDENTITY_UNAVAILABLE이다(사용자 결정 B).
     * 400 INVALID_CODE면 화면이 「동의부터 다시」를, 502면 「잠시 뒤 다시」를 그리는데 둘 다 틀린 안내다: 방송을 켜야 풀린다.
     * 표·secrets는 그대로고, 받은 토큰은 버릴 길이 없어 그 사실만 남긴다.
     */
    @Test
    void 아이디를_못_읽으면_422_IDENTITY_UNAVAILABLE이고_아무것도_안_남는다() throws Exception {
        for (String image : new String[]{FakeSoopServer.DEFAULT_PROFILE_IMAGE, null}) {
            User user = newUser();
            SOOP.reset();
            SOOP.stationResponds(200, FakeSoopServer.stationBody("닉", image));

            try (LogCaptor logs = new LogCaptor()) {
                mockMvc.perform(link(user)).andExpect(status().isUnprocessableEntity())
                        .andExpect(jsonPath("$.reason").value("IDENTITY_UNAVAILABLE"));
                assertThat(logs.messages()).as(String.valueOf(image))
                        .contains("auth.soop.link.identity_unavailable userId=" + user.getId())
                        .contains("auth.soop.link.token_orphaned userId=" + user.getId());
            }

            assertThat(linkRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM secrets", Integer.class)).isZero();
        }
    }

    @Test
    void 방송_중이면_방송_여부_체크의_아이디로_연동된다() throws Exception {
        User user = newUser();
        SOOP.statusOnAir("onair-id");

        mockMvc.perform(link(user)).andExpect(status().isCreated());

        assertThat(linkRepository.findByUserIdAndRevokedAtIsNull(user.getId()).orElseThrow().getSoopUserId())
                .isEqualTo("onair-id");
    }

    @Test
    void 닉네임_조회가_5xx면_502() throws Exception {
        User user = newUser();
        SOOP.stationResponds(500, "{}");
        mockMvc.perform(link(user)).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.reason").value("SOOP_UNAVAILABLE"));
        assertThat(linkRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
    }

    /** 방송국은 SOOP 조회로만 확정한다. 본문의 아이디는 무시된다. */
    @Test
    void 요청_본문에_아이디를_보내도_무시된다() throws Exception {
        User user = newUser();
        String state = codec.issue(user.getId(), Instant.now());
        mockMvc.perform(post("/api/soop-link").header("Authorization", bearer(user)).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"c\",\"state\":\"" + state + "\",\"soopUserId\":\"attacker\"}"))
                .andExpect(status().isCreated());
        assertThat(linkRepository.findByUserIdAndRevokedAtIsNull(user.getId()).orElseThrow().getSoopUserId())
                .isEqualTo(FakeSoopServer.DEFAULT_USER_ID);
    }

    @Test
    void 토큰_없이는_401() throws Exception {
        mockMvc.perform(post("/api/soop-link/start")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/soop-link").contentType(APPLICATION_JSON).content("{\"code\":\"c\",\"state\":\"s\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/soop-link")).andExpect(status().isUnauthorized());
        assertThat(SOOP.tokenCalls()).isZero();
    }
}
