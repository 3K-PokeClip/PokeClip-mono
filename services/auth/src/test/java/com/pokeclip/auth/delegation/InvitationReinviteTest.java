package com.pokeclip.auth.delegation;

import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.doAnswer;

/**
 * 재초대를 누르는 <b>사이에</b> 상대가 거절·취소하면 연장 UPDATE가 0행이 된다.
 * 그때 처리된 초대를 그대로 201로 돌려주면 <b>스트리머 화면엔 「보냈다」인데 상대 초대함엔 없다.</b>
 *
 * <p>그 틈은 {@code invite} 안(사전 조회 ~ extend 사이)이라 밖에서 끼어들 수 없다.
 * 그래서 {@code InvitationWriter}를 스파이로 두고 <b>extend가 도는 순간 행을 DECLINED로
 * 바꾸면서 0을 돌려주게</b> 만든다 — 경합이 실제로 만드는 상태와 같다.
 */
class InvitationReinviteTest extends DelegationTestSupport {

    private final InvitationService service;

    // 스파이 빈은 컨텍스트 수준에서 교체되므로 필드로 둔다. 생성자 주입 대상이 아니다.
    @MockitoSpyBean InvitationWriter writer;

    InvitationReinviteTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                           TokenService tokenService, EditorInvitationRepository invitations,
                           EditorDelegationRepository delegations, JdbcTemplate jdbc,
                           InvitationService service) {
        super(mockMvc, userService, userRepository, tokenService, invitations, delegations, jdbc);
        this.service = service;
    }

    @Test
    void 연장_직전에_거절되면_새_초대가_만들어진다() {
        User streamer = newUser();
        User invitee = newUser();
        service.invite(streamer.getId(), invitee.getEmail());

        doAnswer(call -> {
            jdbc.update("UPDATE editor_invitations SET status = 'DECLINED' WHERE id = ?",
                    (Long) call.getArgument(0));
            return 0;
        }).when(writer).extend(anyLong(), any(), any());

        EditorInvitation second = service.invite(streamer.getId(), invitee.getEmail());

        // 처리된 초대를 돌려주면 안 된다 — 응답이 PENDING이어야 상대 초대함과 화면이 일치한다.
        assertThat(second.getStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(second.view(second.getCreatedAt())).isEqualTo(InvitationView.PENDING);
        // 거절 이력은 남고 새 행이 쌓인다 — 부분 유니크가 PENDING만 보므로 가능하다.
        assertThat(invitations.findByStreamerIdOrderByCreatedAtDesc(streamer.getId())).hasSize(2);
    }

    /**
     * 연장이 0행인 <b>원인이 「그 사이 상대가 수락」</b>이면 새 초대를 만들면 안 된다.
     * 위임이 방금 생겼는데 새 초대를 주면 그 초대는 <b>영원히 수락 불가</b>다 —
     * 매번 uq_delegations_alive_pair에 걸려 롤백되고 PENDING으로 되돌아온다.
     * (codex PR #79 P2. 거절·취소로 0행이 되는 위 경우와 갈라야 한다.)
     */
    @Test
    void 연장_직전에_수락되면_이미_편집자라고_답한다() throws Exception {
        User streamer = newUser();
        User invitee = newUser();
        service.invite(streamer.getId(), invitee.getEmail());

        doAnswer(call -> {
            Long id = call.getArgument(0);
            jdbc.update("UPDATE editor_invitations SET status = 'ACCEPTED' WHERE id = ?", id);
            jdbc.update("INSERT INTO editor_delegations "
                            + "(streamer_id, editor_id, invitation_id, granted_at) VALUES (?, ?, ?, now())",
                    streamer.getId(), invitee.getId(), id);
            return 0;
        }).when(writer).extend(anyLong(), any(), any());

        mockMvc.perform(invite(streamer, invitee.getEmail()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("ALREADY_EDITOR"));

        // 수락 불가인 새 초대가 만들어지지 않았다.
        assertThat(invitations.findByStreamerIdOrderByCreatedAtDesc(streamer.getId())).hasSize(1);
    }
}
