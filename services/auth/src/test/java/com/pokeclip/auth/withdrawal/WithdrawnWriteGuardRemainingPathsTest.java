package com.pokeclip.auth.withdrawal;

import com.pokeclip.auth.AuthException;
import com.pokeclip.auth.delegation.DelegationException;
import com.pokeclip.auth.delegation.InvitationService;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>감사의 다섯에는 없었지만 전수로 세니 나온 자리</b>를 잰다.
 *
 * <p>세는 기준을 「회원에게 <b>무언가를 새로 만들어 주는</b> 쓰기 경로」로 잡고 main 전체를 훑었다.
 * 감사는 자격(스트림키·페어링)·사진·연동·위임 넷을 찾았는데, 같은 기준을 그대로 밀면 <b>둘이 더</b> 나온다.
 *
 * <ul>
 *   <li>🔴 <b>이름 수정</b> — 만들어 주는 것이 아니라 <b>탈퇴가 지운 것을 되돌리는 것</b>이다.
 *       탈퇴는 이름을 「탈퇴한 사용자」로 덮는데, 필터를 지난 이름 수정이 뒤늦게 도착하면
 *       <b>실명이 표에 다시 박힌다.</b> 이 카드가 막으려던 실패가 정확히 이 모양이다 —
 *       응답은 204였고 표는 익명이었는데 다시 실명이 된다. 기준을 「새로 만드는 것」으로만
 *       읽으면 이 자리가 안 세어진다.</li>
 *   <li><b>초대</b> — 양쪽 다다. 탈퇴자가 <b>스트리머로서</b> 새 초대를 만들면 일괄 취소 뒤라 살아남고,
 *       탈퇴자를 <b>상대로</b> 고르면 영영 수락 못 할 초대가 남의 초대함에 생긴다.</li>
 * </ul>
 *
 * <p>거절이 갈리는 이유: 자기 계정이 탈퇴한 경우는 인증 실패와 같은 401이고, <b>상대가 탈퇴한 경우는
 * 404({@code INVITEE_NOT_FOUND})</b>다 — 부르는 쪽 계정은 멀쩡하고 「그 이메일로 가입한 계정이 없다」가
 * 사실 그대로다. 그래서 그쪽은 가드가 아니라 <b>조회 자체를 살아있는 회원으로 좁혀서</b> 닫는다.
 */
class WithdrawnWriteGuardRemainingPathsTest extends WithdrawalTestSupport {

    private final UserService users;
    private final InvitationService invitations;

    WithdrawnWriteGuardRemainingPathsTest(MockMvc mockMvc, UserService userService, TokenService tokenService,
                                          JdbcTemplate jdbc, InvitationService invitations) {
        super(mockMvc, userService, tokenService, jdbc);
        this.users = userService;
        this.invitations = invitations;
    }

    @Test
    void 탈퇴한_회원의_이름은_다시_안_바뀐다() throws Exception {
        User user = newUser("이름되돌리기");
        withdraw(user);
        assertThat(nameOf(user)).as("전제: 탈퇴가 이름을 익명으로 덮었다").isEqualTo("탈퇴한 사용자");

        assertThatThrownBy(() -> users.updateName(user.getId(), "김태현"))
                .isInstanceOf(AuthException.class);

        assertThat(nameOf(user))
                .as("🔴 익명화된 이름이 실명으로 되돌아갔다 — 응답은 204였고 표는 익명이었는데 다시 실명이다")
                .isEqualTo("탈퇴한 사용자");
    }

    @Test
    void 탈퇴한_회원은_새_초대를_못_보낸다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        withdraw(streamer);

        assertThatThrownBy(() -> invitations.invite(streamer.getId(), emailOf(editor)))
                .isInstanceOf(AuthException.class);

        assertThat(pendingInvitationsFrom(streamer))
                .as("🔴 탈퇴한 계정이 보낸 초대가 남의 초대함에 살아 있다 — 일괄 취소는 이미 지나갔다")
                .isZero();
    }

    /**
     * 탈퇴하면 이메일이 {@code withdrawn+<번호>@invalid}로 바뀐다. 그 주소를 그대로 넣으면
     * <b>탈퇴한 계정이 초대 상대가 된다</b> — 수락은 전면 차단 필터에 막혀 영영 안 되고,
     * 보낸 사람 화면에는 「탈퇴한 사용자」에게 보낸 초대가 살아있는 자리 하나를 차지한 채 남는다.
     */
    @Test
    void 탈퇴한_회원을_초대_상대로_못_고른다() throws Exception {
        User streamer = newUser();
        User leaving = newUser();
        withdraw(leaving);
        String 익명_주소 = emailOf(leaving);
        assertThat(익명_주소).as("전제: 탈퇴가 이메일을 익명 주소로 바꿨다").endsWith("@invalid");

        assertThatThrownBy(() -> invitations.invite(streamer.getId(), 익명_주소))
                .as("부르는 쪽 계정은 멀쩡하다 — 401이 아니라 「그런 계정이 없다」여야 한다")
                .isInstanceOf(DelegationException.class);

        assertThat(pendingInvitationsFrom(streamer))
                .as("🔴 탈퇴한 계정 앞으로 초대가 생겼다 — 아무도 수락할 수 없는 초대다")
                .isZero();
    }

    // ── 도구 ────────────────────────────────────────────────────────────

    private void withdraw(User user) throws Exception {
        mockMvc.perform(delete("/api/auth/me").header("Authorization", bearer(user)))
                .andExpect(status().isNoContent());
    }

    private String nameOf(User user) {
        return jdbc.queryForObject("SELECT name FROM users WHERE id = ?", String.class, user.getId());
    }

    /** 표에서 읽는다 — 손에 든 객체는 탈퇴 전 값이다. */
    private String emailOf(User user) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, user.getId());
    }

    private int pendingInvitationsFrom(User streamer) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM editor_invitations WHERE streamer_id = ? AND status = 'PENDING'",
                Integer.class, streamer.getId());
    }
}
