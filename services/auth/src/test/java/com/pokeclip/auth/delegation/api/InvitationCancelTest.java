package com.pokeclip.auth.delegation.api;

import com.pokeclip.auth.delegation.DelegationTestSupport;
import com.pokeclip.auth.delegation.EditorDelegationRepository;
import com.pokeclip.auth.delegation.EditorInvitationRepository;
import com.pokeclip.auth.delegation.InvitationStatus;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InvitationCancelTest extends DelegationTestSupport {

    InvitationCancelTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                         TokenService tokenService, EditorInvitationRepository invitations,
                         EditorDelegationRepository delegations, JdbcTemplate jdbc) {
        super(mockMvc, userService, userRepository, tokenService, invitations, delegations, jdbc);
    }

    @Test
    void 스트리머가_취소하면_초대가_CANCELED가_된다() throws Exception {
        User streamer = newUser();
        User invitee = newUser();
        mockMvc.perform(invite(streamer, invitee.getEmail())).andExpect(status().isCreated());
        Long id = invitations.findByStreamerIdAndInviteeIdAndStatus(
                streamer.getId(), invitee.getId(), InvitationStatus.PENDING).orElseThrow().getId();

        mockMvc.perform(delete("/api/editor-invitations/" + id)
                        .header("Authorization", "Bearer " + accessTokenOf(streamer)))
                .andExpect(status().isNoContent());

        assertThat(invitations.findById(id).orElseThrow().getStatus()).isEqualTo(InvitationStatus.CANCELED);
    }

    /** 취소하면 상한 20의 자리가 빈다 — 상한에 걸렸을 때 푸는 유일한 수단이다. */
    @Test
    void 취소하면_다시_초대할_수_있다() throws Exception {
        User streamer = newUser();
        User invitee = newUser();
        mockMvc.perform(invite(streamer, invitee.getEmail())).andExpect(status().isCreated());
        Long id = invitations.findByStreamerIdAndInviteeIdAndStatus(
                streamer.getId(), invitee.getId(), InvitationStatus.PENDING).orElseThrow().getId();
        mockMvc.perform(delete("/api/editor-invitations/" + id)
                .header("Authorization", "Bearer " + accessTokenOf(streamer))).andExpect(status().isNoContent());

        mockMvc.perform(invite(streamer, invitee.getEmail())).andExpect(status().isCreated());

        assertThat(invitations.findByStreamerIdOrderByCreatedAtDesc(streamer.getId())).hasSize(2);
    }

    @Test
    void 남의_초대는_취소할_수_없다() throws Exception {
        User streamer = newUser();
        User invitee = newUser();
        User stranger = newUser();
        mockMvc.perform(invite(streamer, invitee.getEmail())).andExpect(status().isCreated());
        Long id = invitations.findByStreamerIdAndInviteeIdAndStatus(
                streamer.getId(), invitee.getId(), InvitationStatus.PENDING).orElseThrow().getId();

        mockMvc.perform(delete("/api/editor-invitations/" + id)
                        .header("Authorization", "Bearer " + accessTokenOf(stranger)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("INVITATION_NOT_FOUND"));
    }

    /**
     * 두 번 취소해도 두 번째는 409다. <b>이 갈래가 없으면 cancel JPQL의
     * {@code AND status = PENDING}을 지워도 전부 초록이다</b> — 조건 없는 UPDATE는
     * 이미 처리된 초대의 respondedAt까지 덮어써 이력을 거짓으로 만든다(직접 주입해 확인).
     */
    @Test
    void 이미_취소된_초대를_또_취소하면_409다() throws Exception {
        User streamer = newUser();
        User invitee = newUser();
        mockMvc.perform(invite(streamer, invitee.getEmail())).andExpect(status().isCreated());
        Long id = invitations.findByStreamerIdAndInviteeIdAndStatus(
                streamer.getId(), invitee.getId(), InvitationStatus.PENDING).orElseThrow().getId();
        mockMvc.perform(delete("/api/editor-invitations/" + id)
                .header("Authorization", "Bearer " + accessTokenOf(streamer))).andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/editor-invitations/" + id)
                        .header("Authorization", "Bearer " + accessTokenOf(streamer)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("INVITATION_NOT_PENDING"));
    }

    /** 받은 사람은 취소가 아니라 거절을 쓴다. 둘은 남는 상태가 다르다. */
    @Test
    void 받은_사람은_취소할_수_없다() throws Exception {
        User streamer = newUser();
        User invitee = newUser();
        mockMvc.perform(invite(streamer, invitee.getEmail())).andExpect(status().isCreated());
        Long id = invitations.findByStreamerIdAndInviteeIdAndStatus(
                streamer.getId(), invitee.getId(), InvitationStatus.PENDING).orElseThrow().getId();

        mockMvc.perform(delete("/api/editor-invitations/" + id)
                        .header("Authorization", "Bearer " + accessTokenOf(invitee)))
                .andExpect(status().isNotFound());
    }
}
