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
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * {@code respond}의 {@code clearAutomatically = true}를 재는 <b>유일한</b> 테스트다.
 *
 * <p>이 옵션은 <b>mine()이 읽은 뒤 respond가 돌기 전에 상태가 바뀌었을 때만</b> 결과를 바꾼다.
 * 평범한 경로에서는 mine()이 이미 최종 상태를 읽으므로 1차 캐시가 낡을 일이 없고, 그래서
 * 옵션을 떼도 나머지 테스트가 전부 초록이다(직접 주입해 확인). 그 상태로 두면
 * <b>근거 없이 남은 애너테이션</b>이 되어 다음 사람이 지운다.
 *
 * <p>그 사이를 여기서 만든다 — 바깥 트랜잭션을 열어 초대를 1차 캐시에 PENDING으로 올려 두고,
 * 같은 트랜잭션 안에서 raw SQL로 행을 CANCELED로 바꾼다. JPA는 그 변경을 모른다.
 * 옵션이 없으면 {@code reasonFor}의 재조회가 캐시의 낡은 PENDING을 읽어
 * <b>취소된 초대를 「기한이 지났다」고 답한다.</b>
 */
class InvitationStaleCacheTest extends DelegationTestSupport {

    private final InvitationService service;
    private final TransactionTemplate tx;

    InvitationStaleCacheTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                             TokenService tokenService, EditorInvitationRepository invitations,
                             EditorDelegationRepository delegations, JdbcTemplate jdbc,
                             InvitationService service, TransactionTemplate tx) {
        super(mockMvc, userService, userRepository, tokenService, invitations, delegations, jdbc);
        this.service = service;
        this.tx = tx;
    }

    @Test
    void 읽은_뒤_취소가_끼어들면_만료가_아니라_취소로_답한다() {
        User streamer = newUser();
        User editor = newUser();
        Instant now = Instant.now();
        Long id = invitations.save(EditorInvitation.of(
                streamer.getId(), editor.getId(), now.plusSeconds(3600), now)).getId();

        DelegationException thrown = tx.execute(status -> {
            // 1차 캐시에 PENDING을 올린다 — accept의 mine()이 하는 일과 같다.
            invitations.findById(id).orElseThrow();
            // JPA를 거치지 않고 행만 바꾼다. 영속성 컨텍스트는 이 변경을 모른다.
            jdbc.update("UPDATE editor_invitations SET status = 'CANCELED' WHERE id = ?", id);

            DelegationException e = catchThrowableOfType(
                    DelegationException.class, () -> service.accept(editor.getId(), id));
            // accept가 던지면서 이 트랜잭션은 이미 rollback-only다. 표시해 두지 않으면
            // 바깥 커밋이 UnexpectedRollbackException으로 터져 단언까지 가지 못한다.
            status.setRollbackOnly();
            return e;
        });

        assertThat(thrown).isNotNull();
        assertThat(thrown.getFailure()).isEqualTo(DelegationFailure.INVITATION_NOT_PENDING);
        assertThat(delegations.existsByStreamerIdAndEditorIdAndRevokedAtIsNull(
                streamer.getId(), editor.getId())).isFalse();
    }
}
