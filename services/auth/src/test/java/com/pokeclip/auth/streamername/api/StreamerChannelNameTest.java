package com.pokeclip.auth.streamername.api;

import com.pokeclip.auth.chzzk.ChzzkChannelLink;
import com.pokeclip.auth.chzzk.ChzzkChannelLinkRepository;
import com.pokeclip.auth.delegation.DelegationTestSupport;
import com.pokeclip.auth.delegation.EditorDelegationRepository;
import com.pokeclip.auth.delegation.EditorInvitationRepository;
import com.pokeclip.auth.soop.SoopChannelLink;
import com.pokeclip.auth.soop.SoopChannelLinkRepository;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * clip이 방송 목록에 스트리머 이름을 싣으려고 부르는 내부 창구(POK-430).
 *
 * <p>「살아 있는 연동만」이 이 창구의 핵심 조건이다. 치지직은 해제해도 이름이 행에 남고(revokeAlive가 이름을
 * 안 지운다), SOOP은 앱으로 닫으면 닉네임이 같이 비워져 조건 없이도 null이 나온다. 그래서 SOOP 쪽은 「닫혔는데
 * 닉네임이 남은 행」을 DB에 직접 넣어 조건을 잰다. 재연동(닫힌 옛 행 + 살아 있는 새 행)은 두 표 모두 잰다.
 */
class StreamerChannelNameTest extends DelegationTestSupport {

    /** application-test.yml의 pokeclip.internal-api.token과 같아야 한다. */
    static final String INTERNAL_TOKEN = "test-only-internal-token-32bytes-long!!";

    private final ChzzkChannelLinkRepository chzzkLinks;
    private final SoopChannelLinkRepository soopLinks;

    StreamerChannelNameTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                            TokenService tokenService, EditorInvitationRepository invitations,
                            EditorDelegationRepository delegations, JdbcTemplate jdbc,
                            ChzzkChannelLinkRepository chzzkLinks, SoopChannelLinkRepository soopLinks) {
        super(mockMvc, userService, userRepository, tokenService, invitations, delegations, jdbc);
        this.chzzkLinks = chzzkLinks;
        this.soopLinks = soopLinks;
    }

    /** FK: 두 연동 표는 users의 자식이다. 상위 정리(users 삭제)보다 먼저 지운다(@AfterEach는 하위가 먼저 돈다). */
    @BeforeEach
    @AfterEach
    void clearLinks() {
        jdbc.update("DELETE FROM chzzk_channel_links");
        jdbc.update("DELETE FROM soop_channel_links");
    }

    @Test
    void 살아_있는_두_연동의_이름을_준다() throws Exception {
        User u = newUser();
        aliveChzzk(u, "치지직채널");
        aliveSoop(u, "숲닉네임");

        mockMvc.perform(names(u.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.names['" + u.getId() + "'].chzzk").value("치지직채널"))
                .andExpect(jsonPath("$.names['" + u.getId() + "'].soop").value("숲닉네임"));
    }

    /** 치지직 해제는 이름을 행에 남긴다. 이 시험이 「살아 있는 행만」 조건을 진짜로 잰다. */
    @Test
    void 치지직을_해제했으면_이름이_남아_있어도_null() throws Exception {
        User u = newUser();
        closedChzzk(u, "해제된채널");

        mockMvc.perform(names(u.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.names['" + u.getId() + "'].chzzk").value(nullValue()));
    }

    /** 앱은 SOOP을 닫으며 닉네임을 비운다. 그래서 닉네임이 남은 닫힌 행을 직접 넣어야 조건이 재진다. */
    @Test
    void SOOP_닫힌_행에_닉네임이_남아_있어도_null() throws Exception {
        User u = newUser();
        SoopChannelLink l = aliveSoop(u, "닫힌닉네임");
        jdbc.update("UPDATE soop_channel_links SET revoked_at = now(), revoke_reason = 'USER_UNLINKED' WHERE id = ?",
                l.getId());

        mockMvc.perform(names(u.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.names['" + u.getId() + "'].soop").value(nullValue()));
    }

    /** 옛 행과 새 행이 같이 있다: 살아 있는 조건이 빠지면 옛 이름이 나오거나 중복 키로 500이 된다. */
    @Test
    void 재연동하면_새_이름을_준다() throws Exception {
        User u = newUser();
        closedChzzk(u, "옛채널");
        aliveChzzk(u, "새채널");
        SoopChannelLink old = aliveSoop(u, "옛닉네임");
        jdbc.update("UPDATE soop_channel_links SET revoked_at = now(), revoke_reason = 'USER_UNLINKED' WHERE id = ?",
                old.getId());
        aliveSoop(u, "새닉네임");

        mockMvc.perform(names(u.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.names['" + u.getId() + "'].chzzk").value("새채널"))
                .andExpect(jsonPath("$.names['" + u.getId() + "'].soop").value("새닉네임"));
    }

    /** 탈퇴는 행을 닫고 이름을 비운다(POK-341). 그 모양을 직접 넣는다. */
    @Test
    void 탈퇴로_비워진_닫힌_행은_null() throws Exception {
        User u = newUser();
        ChzzkChannelLink l = closedChzzk(u, "탈퇴채널");
        jdbc.update("UPDATE chzzk_channel_links SET channel_id = NULL, channel_name = NULL WHERE id = ?", l.getId());

        mockMvc.perform(names(u.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.names['" + u.getId() + "'].chzzk").value(nullValue()))
                .andExpect(jsonPath("$.names['" + u.getId() + "'].soop").value(nullValue()));
    }

    /** 연동이 없는 번호도 빼지 않고 두 칸 null로 준다(받는 쪽이 「모름」과 「없음」을 같은 모양으로 읽는다). */
    @Test
    void 연동이_없는_번호도_두_칸_null로_준다() throws Exception {
        User u = newUser();

        // exists()는 값이 null인 키를 「없음」으로 본다(Spring 실측). 키가 남는지는 hasKey로 잰다.
        mockMvc.perform(names(u.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.names['" + u.getId() + "']", allOf(hasKey("chzzk"), hasKey("soop"))))
                .andExpect(jsonPath("$.names['" + u.getId() + "'].chzzk").value(nullValue()))
                .andExpect(jsonPath("$.names['" + u.getId() + "'].soop").value(nullValue()));
    }

    @Test
    void 섞인_요청은_번호마다_제_이름을_준다() throws Exception {
        User both = newUser();
        aliveChzzk(both, "둘다치지직");
        aliveSoop(both, "둘다숲");
        User chzzkOnly = newUser();
        aliveChzzk(chzzkOnly, "치지직만");
        User none = newUser();

        mockMvc.perform(names(both.getId(), chzzkOnly.getId(), none.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.names.length()").value(3))
                .andExpect(jsonPath("$.names['" + both.getId() + "'].chzzk").value("둘다치지직"))
                .andExpect(jsonPath("$.names['" + both.getId() + "'].soop").value("둘다숲"))
                .andExpect(jsonPath("$.names['" + chzzkOnly.getId() + "'].chzzk").value("치지직만"))
                .andExpect(jsonPath("$.names['" + chzzkOnly.getId() + "'].soop").value(nullValue()))
                .andExpect(jsonPath("$.names['" + none.getId() + "'].chzzk").value(nullValue()));
    }

    @Test
    void 빈_목록이면_빈_이름표() throws Exception {
        mockMvc.perform(namesRaw("{\"streamerUserIds\":[]}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.names.length()").value(0));
    }

    // ── 상한: 경계 둘을 같이 잰다(하나만 있으면 상한 값이 틀려도 초록) ───────

    @Test
    void 번호_100개는_받는다() throws Exception {
        mockMvc.perform(namesRaw(idsJson(100))).andExpect(status().isOk())
                .andExpect(jsonPath("$.names.length()").value(100));
    }

    @Test
    void 번호_101개면_400() throws Exception {
        mockMvc.perform(namesRaw(idsJson(101))).andExpect(status().isBadRequest());
    }

    @Test
    void 번호_목록이_없으면_400() throws Exception {
        mockMvc.perform(namesRaw("{}")).andExpect(status().isBadRequest());
    }

    @Test
    void 번호에_null이_섞이면_400() throws Exception {
        mockMvc.perform(namesRaw("{\"streamerUserIds\":[1,null]}")).andExpect(status().isBadRequest());
    }

    // ── 잠금: /internal/** 체인이 그대로 걸리는가 ────────────────────

    @Test
    void 열쇠_없으면_401() throws Exception {
        mockMvc.perform(post(PATH).contentType(APPLICATION_JSON).content("{\"streamerUserIds\":[1]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 틀린_열쇠면_401() throws Exception {
        mockMvc.perform(post(PATH).header("X-Internal-Token", "wrong-token")
                        .contentType(APPLICATION_JSON).content("{\"streamerUserIds\":[1]}"))
                .andExpect(status().isUnauthorized());
    }

    /** 이 시험이 「내부 체인에 올라탔다」를 잰다: 사용자 체인(/api/**)에 열었다면 JWT로 200이 난다. */
    @Test
    void 사용자_JWT로는_401() throws Exception {
        User u = newUser();
        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + accessTokenOf(u))
                        .contentType(APPLICATION_JSON).content("{\"streamerUserIds\":[" + u.getId() + "]}"))
                .andExpect(status().isUnauthorized());
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────

    static final String PATH = "/internal/streamer-channel-names";

    MockHttpServletRequestBuilder names(Long... ids) {
        String list = java.util.Arrays.stream(ids).map(String::valueOf).collect(Collectors.joining(","));
        return namesRaw("{\"streamerUserIds\":[" + list + "]}");
    }

    MockHttpServletRequestBuilder namesRaw(String json) {
        return post(PATH).header("X-Internal-Token", INTERNAL_TOKEN).contentType(APPLICATION_JSON).content(json);
    }

    /** 없는 번호여도 된다: 창구는 회원 표를 안 읽는다. */
    static String idsJson(int n) {
        String list = LongStream.rangeClosed(1, n).mapToObj(String::valueOf).collect(Collectors.joining(","));
        return "{\"streamerUserIds\":[" + list + "]}";
    }

    ChzzkChannelLink aliveChzzk(User u, String name) {
        Instant now = Instant.now();
        return chzzkLinks.save(ChzzkChannelLink.of(u.getId(), "ch-" + UUID.randomUUID(), name, "scope",
                "ref-a", "ref-r", now.plus(1, ChronoUnit.HOURS), now));
    }

    ChzzkChannelLink closedChzzk(User u, String name) {
        ChzzkChannelLink l = aliveChzzk(u, name);
        jdbc.update("UPDATE chzzk_channel_links SET revoked_at = now(), revoke_reason = 'USER_UNLINKED' WHERE id = ?",
                l.getId());
        return l;
    }

    SoopChannelLink aliveSoop(User u, String nickname) {
        Instant now = Instant.now();
        return soopLinks.save(SoopChannelLink.of(u.getId(), "soop-" + UUID.randomUUID(), nickname, "scope",
                "ref-a", "ref-r", now.plus(1, ChronoUnit.HOURS), now));
    }
}
