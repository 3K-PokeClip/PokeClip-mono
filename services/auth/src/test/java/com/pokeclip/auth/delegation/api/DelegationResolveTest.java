package com.pokeclip.auth.delegation.api;

import com.pokeclip.auth.delegation.DelegationService;
import com.pokeclip.auth.delegation.DelegationTestSupport;
import com.pokeclip.auth.delegation.EditorDelegationRepository;
import com.pokeclip.auth.delegation.EditorInvitation;
import com.pokeclip.auth.delegation.EditorInvitationRepository;
import com.pokeclip.auth.delegation.InvitationStatus;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.web.support.LogCaptor;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * clip이 부르는 내부 창구 둘(POK-175). 두 문이 같은 세 단어(OWNER·EDITOR·NONE)로 답한다.
 *
 * <p>위임은 <b>단방향</b>이다(스트리머 → 편집자). 판정 조회의 인자가 둘 다 Long이라 순서를
 * 바꿔 넣어도 컴파일러가 못 잡는다 — 「방향이 뒤집히면 NONE」이 그 자리를 잰다.
 */
class DelegationResolveTest extends DelegationTestSupport {

    /** application-test.yml의 pokeclip.internal-api.token과 같아야 한다. */
    static final String INTERNAL_TOKEN = "test-only-internal-token-32bytes-long!!";

    /** BIGSERIAL이 여기 닿을 일이 없다. 「없는 회원 번호」로 쓴다. */
    static final long GHOST = 999_999_999L;

    private final DelegationService service;
    private final MeterRegistry meterRegistry;

    DelegationResolveTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                          TokenService tokenService, EditorInvitationRepository invitations,
                          EditorDelegationRepository delegations, JdbcTemplate jdbc,
                          DelegationService service, MeterRegistry meterRegistry) {
        super(mockMvc, userService, userRepository, tokenService, invitations, delegations, jdbc);
        this.service = service;
        this.meterRegistry = meterRegistry;
    }

    // ── 문 1: 한 쌍 판정 ──────────────────────────────────────────────

    @Test
    void 번호_둘이_같으면_OWNER() throws Exception {
        User me = newUser();
        mockMvc.perform(resolve(me.getId(), me.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.relation").value("OWNER"));
    }

    @Test
    void 초대를_수락한_편집자면_EDITOR() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        grant(streamer, editor);
        mockMvc.perform(resolve(editor.getId(), streamer.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.relation").value("EDITOR"));
    }

    @Test
    void 아무_관계_없으면_NONE() throws Exception {
        User a = newUser();
        User b = newUser();
        mockMvc.perform(resolve(a.getId(), b.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.relation").value("NONE"));
    }

    @Test
    void 초대만_받고_수락_안_했으면_NONE() throws Exception {
        User streamer = newUser();
        User invitee = newUser();
        pending(streamer, invitee);
        mockMvc.perform(resolve(invitee.getId(), streamer.getId()))
                .andExpect(jsonPath("$.relation").value("NONE"));
    }

    @Test
    void 해제_직후_NONE() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        Long id = grant(streamer, editor);
        service.revoke(streamer.getId(), id);
        mockMvc.perform(resolve(editor.getId(), streamer.getId()))
                .andExpect(jsonPath("$.relation").value("NONE"));
    }

    /**
     * {@code equals}를 {@code ==}로 바꿔도 <b>이 클래스만 돌리면 26건이 전부 초록이었다</b>
     * (authz-auditor 라운드 1, 구현자 재현). 단독 실행에서는 users.id가 14·15라 Long 캐시
     * 범위(-128~127) 안이고 {@code ==}가 우연히 참이 된다. 전체 실행에서 잡히는 것은 앞선
     * 테스트가 시퀀스를 128 너머로 밀어 준 덕이라, 그물이 실행 방식에 기대고 있었다.
     *
     * <p>그래서 캐시 밖 번호로 따로 잰다. 회원 표를 안 읽으므로 DB에 없는 번호로도 된다.
     */
    @Test
    void 캐시_범위_밖_번호도_본인이면_OWNER() throws Exception {
        mockMvc.perform(resolve(GHOST, GHOST)).andExpect(status().isOk())
                .andExpect(jsonPath("$.relation").value("OWNER"));
    }

    @Test
    void 없는_회원_번호면_NONE() throws Exception {
        User me = newUser();
        mockMvc.perform(resolve(me.getId(), GHOST)).andExpect(status().isOk())
                .andExpect(jsonPath("$.relation").value("NONE"));
        mockMvc.perform(resolve(GHOST, me.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.relation").value("NONE"));
    }

    /**
     * 아무 관계 없는 것과 다르다 — 관계가 <b>있는데 방향이 반대</b>다. A가 B의 편집자라고 해서
     * B가 A의 편집자는 아니다. 조회 인자(streamerId, editorId) 순서를 바꿔 넣으면 이것만 빨갛다.
     */
    @Test
    void 방향이_뒤집히면_NONE() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        grant(streamer, editor);
        mockMvc.perform(resolve(streamer.getId(), editor.getId()))
                .andExpect(jsonPath("$.relation").value("NONE"));
    }

    // ── 문 2: 볼 수 있는 스트리머 목록 ───────────────────────────────

    /** 순서가 아니라 relation으로 찾는다 — clip에게도 그렇게 적어 준다(README). */
    @Test
    void 본인이_OWNER로_들어_있다() throws Exception {
        User me = newUser();
        mockMvc.perform(accessible(me.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.streamers[?(@.relation=='OWNER')].streamerUserId").value(me.getId().intValue()));
    }

    @Test
    void 수락한_스트리머가_EDITOR로_들어_있다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        grant(streamer, editor);
        mockMvc.perform(accessible(editor.getId()))
                .andExpect(jsonPath("$.streamers.length()").value(2))
                .andExpect(jsonPath("$.streamers[?(@.relation=='EDITOR')].streamerUserId").value(streamer.getId().intValue()));
    }

    @Test
    void 해제된_것은_빠진다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        Long id = grant(streamer, editor);
        service.revoke(streamer.getId(), id);
        mockMvc.perform(accessible(editor.getId()))
                .andExpect(jsonPath("$.streamers.length()").value(1))
                .andExpect(jsonPath("$.streamers[0].relation").value("OWNER"));
    }

    @Test
    void 초대만_받고_수락_안_한_것은_빠진다() throws Exception {
        User streamer = newUser();
        User invitee = newUser();
        pending(streamer, invitee);
        mockMvc.perform(accessible(invitee.getId()))
                .andExpect(jsonPath("$.streamers.length()").value(1))
                .andExpect(jsonPath("$.streamers[0].relation").value("OWNER"));
    }

    /** 빈 배열이 아니다. 본인 한 줄은 항상 있다. */
    @Test
    void 위임이_하나도_없으면_본인_한_줄만() throws Exception {
        User me = newUser();
        mockMvc.perform(accessible(me.getId()))
                .andExpect(jsonPath("$.streamers.length()").value(1))
                .andExpect(jsonPath("$.streamers[0].streamerUserId").value(me.getId().intValue()))
                .andExpect(jsonPath("$.streamers[0].relation").value("OWNER"));
    }

    /** findByEditorId 대신 findByStreamerId를 쓰면 정확히 반대 목록이 나온다. 이것이 그 자리를 잰다. */
    @Test
    void 내가_스트리머로서_임명한_편집자들은_안_나온다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        grant(streamer, editor);
        mockMvc.perform(accessible(streamer.getId()))
                .andExpect(jsonPath("$.streamers.length()").value(1))
                .andExpect(jsonPath("$.streamers[0].streamerUserId").value(streamer.getId().intValue()));
    }

    /** 서버용 창구에 사람 화면용 정보가 실리면 노출 축이 는다. 공개 API의 틀을 재사용하지 않는다. */
    @Test
    void 응답에_이름_필드가_없다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        grant(streamer, editor);
        // status().isOk()와 양성 단언이 없으면 이 검사는 자동으로 참이다 — 엔드포인트를 지워 404가 되어도,
        // 잠금이 바뀌어 401 빈 응답이 와도 doesNotContain 셋은 통과한다(plan-critic 실측, 두 상태 모두).
        String body = mockMvc.perform(accessible(editor.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("streamerUserId")
                .doesNotContain("Name").doesNotContain("name").doesNotContain("email");
    }

    // ── NONE 관찰 ─────────────────────────────────────────────────────

    /**
     * 회원 표를 안 읽으므로 「없는 번호가 왔다」를 영영 모른다. Media가 보내는 스트리머 번호가
     * 우리 회원 번호인지가 미확인 가정(POK-127)이라, 틀리면 모든 판정이 조용히 NONE이 된다.
     * 그래서 NONE 자체를 센다 — 숫자가 튀면 그때 조사한다.
     */
    @Test
    void NONE이면_카운터가_정확히_1_오른다() throws Exception {
        User a = newUser();
        User b = newUser();
        double before = meterRegistry.counter("pokeclip.delegation.resolve.none").count();

        mockMvc.perform(resolve(a.getId(), b.getId())).andExpect(jsonPath("$.relation").value("NONE"));
        mockMvc.perform(resolve(a.getId(), a.getId())).andExpect(jsonPath("$.relation").value("OWNER"));

        assertThat(meterRegistry.counter("pokeclip.delegation.resolve.none").count()).isEqualTo(before + 1);
    }

    /** 기존 두 창구의 resolve_rejected와 같은 자리·같은 수위(INFO). 값은 번호 둘뿐이다. */
    @Test
    void NONE이면_번호_둘만_담은_INFO_한_줄() throws Exception {
        User a = newUser();
        User b = newUser();
        try (LogCaptor logs = new LogCaptor()) {
            mockMvc.perform(resolve(a.getId(), b.getId()));
            // 양성 단언 셋(startsWith + contains 둘)으로는 「둘만」을 못 잰다 — 줄에 값이 더 붙어도
            // 통과한다. 요청 record를 통째로 싣는 결함을 넣으면 옛 단언은 단독 27건도 전체 335건도
            // 빨개지지 않았다(단독은 authz-auditor 라운드 1, 전체는 구현자 실측).
            // 줄 전체를 정확히 비교해야 「둘만」이 된다.
            assertThat(logs.messages())
                    .contains("auth.delegation.resolve_none userId=" + a.getId()
                            + " streamerUserId=" + b.getId());
        }
    }

    // ── 요청 형식 ─────────────────────────────────────────────────────

    @Test
    void userId가_없으면_400() throws Exception {
        mockMvc.perform(resolveRaw("{\"streamerUserId\":3}")).andExpect(status().isBadRequest());
    }

    @Test
    void streamerUserId가_없으면_400() throws Exception {
        mockMvc.perform(resolveRaw("{\"userId\":3}")).andExpect(status().isBadRequest());
    }

    @Test
    void 번호가_숫자가_아니면_400() throws Exception {
        mockMvc.perform(resolveRaw("{\"userId\":\"abc\",\"streamerUserId\":3}")).andExpect(status().isBadRequest());
    }

    /**
     * 계획에는 resolve 쪽 400만 있었다. 문 둘은 쌍둥이라 한쪽에만 그물을 치면 다른 쪽 @NotNull이
     * 조용히 사라져도 아무도 모른다 — 그러면 userId 없이 부른 요청이 400이 아니라 500이 된다.
     */
    @Test
    void accessible도_userId가_없으면_400() throws Exception {
        mockMvc.perform(accessibleRaw("{}")).andExpect(status().isBadRequest());
    }

    // ── 잠금: /internal/** 체인이 그대로 걸리는가 ────────────────────

    @Test
    void resolve는_열쇠_없으면_401() throws Exception {
        mockMvc.perform(post("/internal/editor-delegations/resolve")
                        .contentType(APPLICATION_JSON).content("{\"userId\":1,\"streamerUserId\":2}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resolve는_틀린_열쇠면_401() throws Exception {
        mockMvc.perform(post("/internal/editor-delegations/resolve").header("X-Internal-Token", "wrong-token")
                        .contentType(APPLICATION_JSON).content("{\"userId\":1,\"streamerUserId\":2}"))
                .andExpect(status().isUnauthorized());
    }

    /** 진짜 유효한 access 토큰이어야 한다 — 아무 문자열이면 위와 같은 경로라 아무것도 안 잰다. */
    @Test
    void resolve는_사용자_JWT로는_401() throws Exception {
        User u = newUser();
        mockMvc.perform(post("/internal/editor-delegations/resolve")
                        .header("Authorization", "Bearer " + accessTokenOf(u))
                        .contentType(APPLICATION_JSON).content("{\"userId\":1,\"streamerUserId\":2}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accessible은_열쇠_없으면_401() throws Exception {
        mockMvc.perform(post("/internal/editor-delegations/accessible")
                        .contentType(APPLICATION_JSON).content("{\"userId\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accessible은_틀린_열쇠면_401() throws Exception {
        mockMvc.perform(post("/internal/editor-delegations/accessible").header("X-Internal-Token", "wrong-token")
                        .contentType(APPLICATION_JSON).content("{\"userId\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accessible은_사용자_JWT로는_401() throws Exception {
        User u = newUser();
        mockMvc.perform(post("/internal/editor-delegations/accessible")
                        .header("Authorization", "Bearer " + accessTokenOf(u))
                        .contentType(APPLICATION_JSON).content("{\"userId\":1}"))
                .andExpect(status().isUnauthorized());
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────

    MockHttpServletRequestBuilder resolve(Long userId, Long streamerUserId) {
        return resolveRaw("{\"userId\":" + userId + ",\"streamerUserId\":" + streamerUserId + "}");
    }

    MockHttpServletRequestBuilder resolveRaw(String json) {
        return post("/internal/editor-delegations/resolve").header("X-Internal-Token", INTERNAL_TOKEN)
                .contentType(APPLICATION_JSON).content(json);
    }

    MockHttpServletRequestBuilder accessible(Long userId) {
        return accessibleRaw("{\"userId\":" + userId + "}");
    }

    MockHttpServletRequestBuilder accessibleRaw(String json) {
        return post("/internal/editor-delegations/accessible").header("X-Internal-Token", INTERNAL_TOKEN)
                .contentType(APPLICATION_JSON).content(json);
    }

    /** 초대 → 수락까지 실제 HTTP 경로로 만든다. 위임 행 id를 준다(DelegationListAndRevokeTest와 같다). */
    Long grant(User streamer, User editor) throws Exception {
        mockMvc.perform(invite(streamer, editor.getEmail())).andExpect(status().isCreated());
        Long invitationId = invitations.findByStreamerIdAndInviteeIdAndStatus(
                streamer.getId(), editor.getId(), InvitationStatus.PENDING).orElseThrow().getId();
        mockMvc.perform(post("/api/editor-invitations/" + invitationId + "/accept")
                .header("Authorization", "Bearer " + accessTokenOf(editor))).andExpect(status().isNoContent());
        return delegations.findByStreamerIdAndRevokedAtIsNullOrderByGrantedAtDesc(streamer.getId())
                .get(0).getId();
    }

    /** 수락 전 상태. 행만 심는다(DelegationRevokeQueryTest와 같은 방식). */
    void pending(User streamer, User invitee) {
        Instant now = Instant.now();
        invitations.save(EditorInvitation.of(streamer.getId(), invitee.getId(), now.plusSeconds(3600), now));
    }
}
