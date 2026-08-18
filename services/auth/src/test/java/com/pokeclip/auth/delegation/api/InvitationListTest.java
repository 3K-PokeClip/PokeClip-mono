package com.pokeclip.auth.delegation.api;

import com.pokeclip.auth.delegation.DelegationTestSupport;
import com.pokeclip.auth.delegation.EditorDelegationRepository;
import com.pokeclip.auth.delegation.EditorInvitation;
import com.pokeclip.auth.delegation.EditorInvitationRepository;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InvitationListTest extends DelegationTestSupport {

    InvitationListTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                       TokenService tokenService, EditorInvitationRepository invitations,
                       EditorDelegationRepository delegations, JdbcTemplate jdbc) {
        super(mockMvc, userService, userRepository, tokenService, invitations, delegations, jdbc);
    }

    @Test
    void 보낸_목록에_상대와_상태가_나온다() throws Exception {
        User streamer = newUser();
        User invitee = newUser();
        mockMvc.perform(invite(streamer, invitee.getEmail())).andExpect(status().isCreated());

        mockMvc.perform(get("/api/editor-invitations/sent")
                        .header("Authorization", "Bearer " + accessTokenOf(streamer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].inviteeEmail").value(invitee.getEmail()))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    void 받은_목록에_보낸_사람이_나온다() throws Exception {
        User streamer = newUser();
        User invitee = newUser();
        mockMvc.perform(invite(streamer, invitee.getEmail())).andExpect(status().isCreated());

        mockMvc.perform(get("/api/editor-invitations/received")
                        .header("Authorization", "Bearer " + accessTokenOf(invitee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].streamerName").value(streamer.getName()));
    }

    /** 만료는 DB에 안 쓰이므로 조회 시점에 갈라 준다. */
    @Test
    void 기한이_지난_초대는_보낸_목록에_EXPIRED로_나온다() throws Exception {
        User streamer = newUser();
        User invitee = newUser();
        invitations.save(EditorInvitation.of(streamer.getId(), invitee.getId(),
                Instant.now().minusSeconds(60), Instant.now().minusSeconds(600)));

        mockMvc.perform(get("/api/editor-invitations/sent")
                        .header("Authorization", "Bearer " + accessTokenOf(streamer)))
                .andExpect(jsonPath("$[0].status").value("EXPIRED"));
    }

    /** 초대함에는 응답할 수 있는 것만 담는다. 만료된 것을 보여주면 눌러도 실패한다. */
    @Test
    void 기한이_지난_초대는_받은_목록에_안_나온다() throws Exception {
        User streamer = newUser();
        User invitee = newUser();
        invitations.save(EditorInvitation.of(streamer.getId(), invitee.getId(),
                Instant.now().minusSeconds(60), Instant.now().minusSeconds(600)));

        mockMvc.perform(get("/api/editor-invitations/received")
                        .header("Authorization", "Bearer " + accessTokenOf(invitee)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** 수락 전에는 편집자에게 그 스트리머의 어떤 데이터도 보이지 않는다(카드 완료조건). */
    @Test
    void 남의_초대는_내_목록에_안_나온다() throws Exception {
        User streamer = newUser();
        User invitee = newUser();
        User stranger = newUser();
        mockMvc.perform(invite(streamer, invitee.getEmail())).andExpect(status().isCreated());

        mockMvc.perform(get("/api/editor-invitations/received")
                        .header("Authorization", "Bearer " + accessTokenOf(stranger)))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
