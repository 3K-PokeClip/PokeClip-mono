package com.pokeclip.auth.delegation.api;

import com.pokeclip.auth.delegation.DelegationTestSupport;
import com.pokeclip.auth.delegation.EditorDelegation;
import com.pokeclip.auth.delegation.EditorDelegationRepository;
import com.pokeclip.auth.delegation.EditorInvitation;
import com.pokeclip.auth.delegation.EditorInvitationRepository;
import com.pokeclip.auth.delegation.InvitationStatus;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InvitationRespondTest extends DelegationTestSupport {

    InvitationRespondTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                          TokenService tokenService, EditorInvitationRepository invitations,
                          EditorDelegationRepository delegations, JdbcTemplate jdbc) {
        super(mockMvc, userService, userRepository, tokenService, invitations, delegations, jdbc);
    }

    @Test
    void 수락하면_초대가_ACCEPTED가_되고_위임이_생긴다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        Long id = pendingInvitation(streamer, editor);

        mockMvc.perform(accept(editor, id)).andExpect(status().isNoContent());

        assertThat(invitations.findById(id).orElseThrow().getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(delegations.existsByStreamerIdAndEditorIdAndRevokedAtIsNull(
                streamer.getId(), editor.getId())).isTrue();
    }

    @Test
    void 거절하면_위임이_안_생긴다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        Long id = pendingInvitation(streamer, editor);

        mockMvc.perform(post("/api/editor-invitations/" + id + "/decline")
                        .header("Authorization", "Bearer " + accessTokenOf(editor)))
                .andExpect(status().isNoContent());

        assertThat(invitations.findById(id).orElseThrow().getStatus()).isEqualTo(InvitationStatus.DECLINED);
        assertThat(delegations.existsByStreamerIdAndEditorIdAndRevokedAtIsNull(
                streamer.getId(), editor.getId())).isFalse();
    }

    @Test
    void 기한이_지난_초대는_수락되지_않는다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        Long id = invitations.save(EditorInvitation.of(streamer.getId(), editor.getId(),
                Instant.now().minusSeconds(60), Instant.now().minusSeconds(600))).getId();

        mockMvc.perform(accept(editor, id))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.reason").value("INVITATION_EXPIRED"));

        assertThat(delegations.existsByStreamerIdAndEditorIdAndRevokedAtIsNull(
                streamer.getId(), editor.getId())).isFalse();
    }

    /**
     * 취소가 먼저 닿으면 수락은 실패해야 한다. 조건을 건 UPDATE라 읽고-쓰기 사이의 틈이 없다.
     * 부분 유니크 인덱스는 이 경합을 막지 못한다 — 둘 다 같은 행을 고치기 때문이다.
     */
    @Test
    void 취소된_초대는_수락되지_않는다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        Long id = pendingInvitation(streamer, editor);
        mockMvc.perform(delete("/api/editor-invitations/" + id)
                .header("Authorization", "Bearer " + accessTokenOf(streamer))).andExpect(status().isNoContent());

        mockMvc.perform(accept(editor, id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("INVITATION_NOT_PENDING"));

        assertThat(delegations.existsByStreamerIdAndEditorIdAndRevokedAtIsNull(
                streamer.getId(), editor.getId())).isFalse();
    }

    @Test
    void 두_번_수락해도_위임은_하나다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        Long id = pendingInvitation(streamer, editor);
        mockMvc.perform(accept(editor, id)).andExpect(status().isNoContent());

        mockMvc.perform(accept(editor, id)).andExpect(status().isConflict());

        assertThat(delegations.findByStreamerIdAndRevokedAtIsNullOrderByGrantedAtDesc(
                streamer.getId())).hasSize(1);
    }

    @Test
    void 남의_초대는_수락할_수_없다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        User stranger = newUser();
        Long id = pendingInvitation(streamer, editor);

        mockMvc.perform(accept(stranger, id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("INVITATION_NOT_FOUND"));
    }

    /**
     * 예외 핸들러의 uq_delegations_alive_pair 분기를 재는 <b>유일한</b> 테스트다.
     *
     * <p>바로 위 `두_번_수락해도_위임은_하나다`는 이 경로를 지나지 않는다 — 첫 수락으로
     * status가 ACCEPTED가 되어 두 번째는 조건부 UPDATE가 0행이 되고 INVITATION_NOT_PENDING으로
     * 끝나기 때문이다. 초록이지만 다른 이유로 초록이다. 여기서는 초대를 PENDING으로 남긴 채
     * 위임만 미리 심어, 실제로 INSERT가 DB 제약에 부딪히게 만든다(invite의 사전 조회를
     * 지나친 경합이 만드는 상태다).
     */
    @Test
    void 이미_위임이_살아있는데_수락하면_DB가_막아_409_ALREADY_EDITOR다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        Long id = pendingInvitation(streamer, editor);
        delegations.save(EditorDelegation.of(streamer.getId(), editor.getId(), id, Instant.now()));

        mockMvc.perform(accept(editor, id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("ALREADY_EDITOR"));

        // 여기가 「한 트랜잭션」을 재는 자리다. 위임 INSERT가 DB에 막혔으니 앞서 돌아간
        // 초대 UPDATE도 같이 되돌아가야 한다. ACCEPTED로 남으면 「수락은 됐는데 편집자가
        // 아니다」 — 권한 없는 편집자가 생기는, 카드가 막겠다고 한 바로 그 상태다.
        //
        // 이 단언은 실제로 잰다. 깨는 변이를 확인했다(2026-08-18 재현):
        // invitations.respond를 REQUIRES_NEW 컴포넌트로 떼면 초대 UPDATE가 독립 커밋되고
        // 뒤이은 INSERT 실패가 그걸 못 되돌린다 → expected: PENDING but was: ACCEPTED.
        // 이 저장소는 쓰기를 별도 컴포넌트로 떼는 패턴을 도처에 쓰므로(UserCreator ·
        // InvitationWriter · ChzzkLinkWriter · StreamKeyCreator, 넷 다 REQUIRES_NEW)
        // accept도 그렇게 하고 싶어지는 자리다. 이 두 줄이 그때 빨간불이 된다.
        //
        // 반대 방향(위임 INSERT를 REQUIRES_NEW로 떼기)은 안 깨진다 — 예외가 그대로
        // 전파돼 바깥도 같이 롤백된다. 위험한 쪽은 먼저 도는 UPDATE가 독립 커밋되는 경우다.
        // (delegations.save를 지우는 주입은 「INSERT가 있다」만 재지 원자성은 못 잰다.)
        assertThat(invitations.findById(id).orElseThrow().getStatus())
                .isEqualTo(InvitationStatus.PENDING);
        // 미리 심어 둔 그 한 건뿐 — 실패한 수락이 위임을 덧붙이지 않았다.
        assertThat(delegations.findAll()).hasSize(1);
    }

    private Long pendingInvitation(User streamer, User editor) throws Exception {
        mockMvc.perform(invite(streamer, editor.getEmail())).andExpect(status().isCreated());
        return invitations.findByStreamerIdAndInviteeIdAndStatus(
                streamer.getId(), editor.getId(), InvitationStatus.PENDING).orElseThrow().getId();
    }

    private MockHttpServletRequestBuilder accept(User user, Long invitationId) {
        return post("/api/editor-invitations/" + invitationId + "/accept")
                .header("Authorization", "Bearer " + accessTokenOf(user));
    }
}
