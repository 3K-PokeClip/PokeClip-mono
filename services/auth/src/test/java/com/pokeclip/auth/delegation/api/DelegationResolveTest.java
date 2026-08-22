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
            assertThat(logs.messages())
                    .anyMatch(m -> m.startsWith("auth.delegation.resolve_none")
                            && m.contains("userId=" + a.getId())
                            && m.contains("streamerUserId=" + b.getId()));
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

    // ── 헬퍼 ─────────────────────────────────────────────────────────

    MockHttpServletRequestBuilder resolve(Long userId, Long streamerUserId) {
        return resolveRaw("{\"userId\":" + userId + ",\"streamerUserId\":" + streamerUserId + "}");
    }

    MockHttpServletRequestBuilder resolveRaw(String json) {
        return post("/internal/editor-delegations/resolve").header("X-Internal-Token", INTERNAL_TOKEN)
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
