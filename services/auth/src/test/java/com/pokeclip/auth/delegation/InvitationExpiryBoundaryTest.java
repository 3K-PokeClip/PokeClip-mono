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
 * "기한과 같은 시각은 아직 만료가 아니다"를 <b>네 자리가 같은 뜻으로</b> 판정하는지 잰다.
 *
 * <p>{@link EditorInvitation#view}와 respond JPQL은 경계를 살아있다고 보는데, 목록·상한을
 * 세는 파생 메서드가 Spring Data의 {@code After}(strict &gt;)면 경계에서만 죽은 것으로 갈린다.
 * 그러면 상한 20 테스트가 경계 시각을 쓰는 순간 <b>세어지지 않아 단언이 자동으로 참이 된다</b>
 * (transaction-auditor 라운드 1 지적).
 */
class InvitationExpiryBoundaryTest extends DelegationTestSupport {

    InvitationExpiryBoundaryTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                                 TokenService tokenService, EditorInvitationRepository invitations,
                                 EditorDelegationRepository delegations, JdbcTemplate jdbc) {
        super(mockMvc, userService, userRepository, tokenService, invitations, delegations, jdbc);
    }

    /** 밀리초로 자른다 — Instant는 나노초까지 담지만 timestamptz는 마이크로초라 경계 비교가 어긋난다. */
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Test
    void 기한과_같은_시각의_초대는_상한에도_초대함에도_살아있다() {
        User streamer = newUser();
        User invitee = newUser();
        Instant boundary = NOW.plusSeconds(60);
        invitations.save(EditorInvitation.of(streamer.getId(), invitee.getId(), boundary, NOW));

        assertThat(invitations.countByStreamerIdAndStatusAndExpiresAtGreaterThanEqual(
                streamer.getId(), InvitationStatus.PENDING, boundary)).isEqualTo(1);
        assertThat(invitations.findByInviteeIdAndStatusAndExpiresAtGreaterThanEqualOrderByCreatedAtDesc(
                invitee.getId(), InvitationStatus.PENDING, boundary)).hasSize(1);
    }

    /**
     * 경계를 <b>한 순간이라도</b> 지나면 죽는다. 이 짝이 없으면 조건을 통째로 지워도
     * 위 테스트가 초록이라 "항상 산다"와 구분되지 않는다.
     */
    @Test
    void 기한을_지난_초대는_상한에도_초대함에도_안_잡힌다() {
        User streamer = newUser();
        User invitee = newUser();
        Instant boundary = NOW.plusSeconds(60);
        invitations.save(EditorInvitation.of(streamer.getId(), invitee.getId(), boundary, NOW));

        Instant justAfter = boundary.plusMillis(1);
        assertThat(invitations.countByStreamerIdAndStatusAndExpiresAtGreaterThanEqual(
                streamer.getId(), InvitationStatus.PENDING, justAfter)).isZero();
        assertThat(invitations.findByInviteeIdAndStatusAndExpiresAtGreaterThanEqualOrderByCreatedAtDesc(
                invitee.getId(), InvitationStatus.PENDING, justAfter)).isEmpty();
    }
}
