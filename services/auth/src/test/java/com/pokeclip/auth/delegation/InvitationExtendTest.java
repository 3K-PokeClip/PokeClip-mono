package com.pokeclip.auth.delegation;

import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조건을 건 UPDATE가 <b>조건 때문에</b> 0행이 되는지 잰다.
 *
 * <p>{@code extend}의 {@code AND status = PENDING}을 지워도 다른 테스트는 전부 초록으로
 * 남는다(transaction-auditor 라운드 1 실측). 조건이 없으면 이미 취소·거절된 초대가
 * 기한만 밀려 <b>되살아난다</b> — 사물함에서 사라진 초대가 다시 나타나는 버그다.
 */
class InvitationExtendTest extends DelegationTestSupport {

    private final InvitationWriter writer;

    InvitationExtendTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                         TokenService tokenService, EditorInvitationRepository invitations,
                         EditorDelegationRepository delegations, JdbcTemplate jdbc,
                         InvitationWriter writer) {
        super(mockMvc, userService, userRepository, tokenService, invitations, delegations, jdbc);
        this.writer = writer;
    }

    @Test
    void 살아있는_초대는_기한이_밀린다() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Long id = seed(now.plusSeconds(60), now);
        Instant pushed = now.plusSeconds(9999);

        assertThat(writer.extend(id, pushed, now)).isEqualTo(1);
        assertThat(invitations.findById(id).orElseThrow().getExpiresAt()).isEqualTo(pushed);
    }

    @Test
    void 이미_취소된_초대는_기한이_안_밀리고_되살아나지도_않는다() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant original = now.plusSeconds(60);
        Long id = seed(original, now);
        jdbc.update("UPDATE editor_invitations SET status = 'CANCELED' WHERE id = ?", id);

        assertThat(writer.extend(id, now.plusSeconds(9999), now)).isZero();
        EditorInvitation after = invitations.findById(id).orElseThrow();
        assertThat(after.getExpiresAt()).isEqualTo(original);
        assertThat(after.getStatus()).isEqualTo(InvitationStatus.CANCELED);
    }

    private Long seed(Instant expiresAt, Instant now) {
        User streamer = newUser();
        User invitee = newUser();
        return invitations.save(
                EditorInvitation.of(streamer.getId(), invitee.getId(), expiresAt, now)).getId();
    }
}
