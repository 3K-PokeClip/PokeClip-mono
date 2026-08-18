package com.pokeclip.auth.delegation;

import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * revoke UPDATE의 <b>소유 조건</b>을 재는 유일한 테스트다.
 *
 * <p>서비스를 거치면 앞단 조회({@code findByIdAndRevokedAtIsNull} + 자바 filter)가 먼저 막아
 * UPDATE의 조건이 없어도 초록이 된다 — 겹치는 방어라 위에서 걸린다. 그래서 <b>리포지토리를
 * 직접 부른다.</b> 그래야 「인가가 쿼리에 박혀 있다」가 실제로 재어진다.
 *
 * <p>이게 필요한 이유는 다음 사람이 이 UPDATE를 다른 자리에서 재사용할 때다.
 * 쿼리만 보고 부르면 인가가 통째로 빠진다(authz-auditor 라운드 2 사소 1).
 */
class DelegationRevokeQueryTest extends DelegationTestSupport {

    private final TransactionTemplate tx;

    DelegationRevokeQueryTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                              TokenService tokenService, EditorInvitationRepository invitations,
                              EditorDelegationRepository delegations, JdbcTemplate jdbc,
                              TransactionTemplate tx) {
        super(mockMvc, userService, userRepository, tokenService, invitations, delegations, jdbc);
        this.tx = tx;
    }

    @Test
    void 당사자가_아니면_UPDATE가_0행이다() {
        User streamer = newUser();
        User editor = newUser();
        User stranger = newUser();
        Long id = alive(streamer, editor);

        Integer changed = tx.execute(st ->
                delegations.revoke(id, stranger.getId(), RevokedBy.STREAMER, Instant.now()));

        assertThat(changed).isZero();
        EditorDelegation row = delegations.findById(id).orElseThrow();
        assertThat(row.getRevokedAt()).isNull();
        assertThat(row.getRevokedBy()).isNull();
    }

    @Test
    void 당사자면_UPDATE가_1행이다() {
        User streamer = newUser();
        User editor = newUser();
        Long id = alive(streamer, editor);

        Integer changed = tx.execute(st ->
                delegations.revoke(id, editor.getId(), RevokedBy.EDITOR, Instant.now()));

        assertThat(changed).isEqualTo(1);
        assertThat(delegations.findById(id).orElseThrow().getRevokedBy()).isEqualTo(RevokedBy.EDITOR);
    }

    /**
     * revoke UPDATE의 {@code revokedAt IS NULL}을 재는 유일한 테스트다.
     *
     * <p>서비스를 거치면 앞단 조회({@code findByIdAndRevokedAtIsNull})가 먼저 막아 이 조건이
     * 없어도 초록이 된다. <b>진입점이 다르다</b> — 조회는 서비스 경로만 막고, 이 조건은
     * 리포지토리를 직접 부르는 <b>모든</b> 경로를 막는다. 그래서 리포지토리를 직접 부르면
     * 이 조건만 관측된다(바로 위 소유 조건 테스트와 같은 수법이다).
     *
     * <p>깨지면 <b>이력 위조</b>다 — 쫓겨난 편집자가 같은 행을 다시 끊어 revoked_by를
     * STREAMER에서 EDITOR로 덮으면 「내보내졌다」가 「내가 나갔다」가 된다.
     */
    @Test
    void 이미_끊긴_위임은_UPDATE가_0행이고_누가_끊었나가_안_덮인다() {
        User streamer = newUser();
        User editor = newUser();
        Long id = alive(streamer, editor);
        // tx.execute(...)를 assertThat에 바로 넣으면 assertThat이 ambiguous로 컴파일이 깨진다.
        Integer firstChanged = tx.execute(st ->
                delegations.revoke(id, streamer.getId(), RevokedBy.STREAMER, Instant.now()));
        assertThat(firstChanged).isEqualTo(1);

        Integer changed = tx.execute(st ->
                delegations.revoke(id, editor.getId(), RevokedBy.EDITOR, Instant.now()));

        assertThat(changed).isZero();
        assertThat(delegations.findById(id).orElseThrow().getRevokedBy()).isEqualTo(RevokedBy.STREAMER);
    }

    private Long alive(User streamer, User editor) {
        Instant now = Instant.now();
        Long invitationId = invitations.save(EditorInvitation.of(
                streamer.getId(), editor.getId(), now.plusSeconds(3600), now)).getId();
        return delegations.save(EditorDelegation.of(
                streamer.getId(), editor.getId(), invitationId, now)).getId();
    }
}
