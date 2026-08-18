package com.pokeclip.auth.delegation.api;

import com.pokeclip.auth.delegation.DelegationTestSupport;
import com.pokeclip.auth.delegation.EditorDelegation;
import com.pokeclip.auth.delegation.EditorDelegationRepository;
import com.pokeclip.auth.delegation.EditorInvitationRepository;
import com.pokeclip.auth.delegation.InvitationStatus;
import com.pokeclip.auth.delegation.RevokedBy;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DelegationListAndRevokeTest extends DelegationTestSupport {

    DelegationListAndRevokeTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                                TokenService tokenService, EditorInvitationRepository invitations,
                                EditorDelegationRepository delegations, JdbcTemplate jdbc) {
        super(mockMvc, userService, userRepository, tokenService, invitations, delegations, jdbc);
    }

    @Test
    void 스트리머는_자기_편집자를_보고_편집자는_자기_스트리머를_본다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        grant(streamer, editor);

        mockMvc.perform(get("/api/editor-delegations/as-streamer")
                        .header("Authorization", "Bearer " + accessTokenOf(streamer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].counterpartName").value(editor.getName()));

        mockMvc.perform(get("/api/editor-delegations/as-editor")
                        .header("Authorization", "Bearer " + accessTokenOf(editor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].counterpartName").value(streamer.getName()));
    }

    @Test
    void 스트리머가_내보내면_STREAMER로_남는다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        Long id = grant(streamer, editor);

        mockMvc.perform(delete("/api/editor-delegations/" + id)
                        .header("Authorization", "Bearer " + accessTokenOf(streamer)))
                .andExpect(status().isNoContent());

        EditorDelegation row = delegations.findById(id).orElseThrow();
        assertThat(row.getRevokedBy()).isEqualTo(RevokedBy.STREAMER);
        assertThat(row.getRevokedAt()).isNotNull();
    }

    @Test
    void 편집자가_나가면_EDITOR로_남는다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        Long id = grant(streamer, editor);

        mockMvc.perform(delete("/api/editor-delegations/" + id)
                        .header("Authorization", "Bearer " + accessTokenOf(editor)))
                .andExpect(status().isNoContent());

        assertThat(delegations.findById(id).orElseThrow().getRevokedBy()).isEqualTo(RevokedBy.EDITOR);
    }

    /**
     * <b>「누가 끊었나」는 한 번 정해지면 안 바뀐다.</b> 스트리머가 내보낸 뒤 편집자가 같은 행을
     * 호출해 EDITOR로 덮어쓰면 이력이 거짓이 된다 — 쫓겨난 사람이 「내가 나갔다」로 바꿀 수 있다.
     * 살아있는 위임만 찾는 조회와 revoke JPQL의 {@code revokedAt IS NULL}이 그걸 막는 자리다.
     */
    @Test
    void 이미_해제된_위임을_상대가_또_해제해도_누가_끊었는지_안_바뀐다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        Long id = grant(streamer, editor);
        mockMvc.perform(delete("/api/editor-delegations/" + id)
                .header("Authorization", "Bearer " + accessTokenOf(streamer))).andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/editor-delegations/" + id)
                        .header("Authorization", "Bearer " + accessTokenOf(editor)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("DELEGATION_NOT_FOUND"));

        assertThat(delegations.findById(id).orElseThrow().getRevokedBy()).isEqualTo(RevokedBy.STREAMER);
    }

    /** 행을 지우지 않는다 — 끊긴 이력이 남아야 한다. */
    @Test
    void 해제된_위임은_목록에서_빠지지만_행은_남는다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        Long id = grant(streamer, editor);
        mockMvc.perform(delete("/api/editor-delegations/" + id)
                .header("Authorization", "Bearer " + accessTokenOf(streamer))).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/editor-delegations/as-streamer")
                        .header("Authorization", "Bearer " + accessTokenOf(streamer)))
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(delegations.findById(id)).isPresent();
    }

    @Test
    void 해제한_뒤_다시_초대하고_수락하면_새_행이_생긴다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        Long first = grant(streamer, editor);
        mockMvc.perform(delete("/api/editor-delegations/" + first)
                .header("Authorization", "Bearer " + accessTokenOf(streamer))).andExpect(status().isNoContent());

        Long second = grant(streamer, editor);

        assertThat(second).isNotEqualTo(first);
        assertThat(delegations.findByStreamerIdAndRevokedAtIsNullOrderByGrantedAtDesc(
                streamer.getId())).hasSize(1);
    }

    /** PRD 성공기준 — 수락 전에는 편집자에게 그 스트리머가 안 보인다. 초대만 있고 위임은 없다. */
    @Test
    void 수락하기_전에는_편집자의_위임_목록에_그_스트리머가_안_나온다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        mockMvc.perform(invite(streamer, editor.getEmail())).andExpect(status().isCreated());

        mockMvc.perform(get("/api/editor-delegations/as-editor")
                        .header("Authorization", "Bearer " + accessTokenOf(editor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** 내보내진 편집자에게서도 그 스트리머가 사라져야 한다. as-streamer만 재면 반쪽이다. */
    @Test
    void 해제되면_편집자의_목록에서도_그_스트리머가_사라진다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        Long id = grant(streamer, editor);
        mockMvc.perform(delete("/api/editor-delegations/" + id)
                .header("Authorization", "Bearer " + accessTokenOf(streamer))).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/editor-delegations/as-editor")
                        .header("Authorization", "Bearer " + accessTokenOf(editor)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 무관한_사람은_남의_위임을_끊을_수_없다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        User stranger = newUser();
        Long id = grant(streamer, editor);

        mockMvc.perform(delete("/api/editor-delegations/" + id)
                        .header("Authorization", "Bearer " + accessTokenOf(stranger)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("DELEGATION_NOT_FOUND"));
    }

    /**
     * <b>같은 엔드포인트를 스트리머와 편집자가 함께 쓴다.</b> 로그에 actorId가 없으면
     * 누가 눌렀는지 로그만으로는 모르고 DB를 다시 조회해야 한다 — 바로 위
     * {@code auth.delegation.granted}가 두 주체를 다 남기는 것과도 어긋난다.
     */
    @Test
    void 해제_로그에_누가_눌렀는지_남는다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        Long id = grant(streamer, editor);

        try (LogCaptor logs = new LogCaptor()) {
            mockMvc.perform(delete("/api/editor-delegations/" + id)
                            .header("Authorization", "Bearer " + accessTokenOf(editor)))
                    .andExpect(status().isNoContent());

            assertThat(logs.messages()).anyMatch(m -> m.startsWith("auth.delegation.revoked")
                    && m.contains("actorId=" + editor.getId())
                    && m.contains("by=EDITOR"));
        }
    }

    /** 초대 → 수락까지 실제 경로로 만든다. 위임 행 id를 준다. */
    private Long grant(User streamer, User editor) throws Exception {
        mockMvc.perform(invite(streamer, editor.getEmail())).andExpect(status().isCreated());
        Long invitationId = invitations.findByStreamerIdAndInviteeIdAndStatus(
                streamer.getId(), editor.getId(), InvitationStatus.PENDING).orElseThrow().getId();
        mockMvc.perform(post("/api/editor-invitations/" + invitationId + "/accept")
                .header("Authorization", "Bearer " + accessTokenOf(editor))).andExpect(status().isNoContent());
        return delegations.findByStreamerIdAndRevokedAtIsNullOrderByGrantedAtDesc(streamer.getId())
                .get(0).getId();
    }
}
