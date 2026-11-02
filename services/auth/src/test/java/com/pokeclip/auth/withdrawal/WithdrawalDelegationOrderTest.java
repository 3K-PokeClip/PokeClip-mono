package com.pokeclip.auth.withdrawal;

import com.pokeclip.auth.delegation.InvitationService;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>탈퇴가 초대를 위임보다 <u>먼저</u> 쳐야 한다</b>(PR #148 codex C4, 감사가 재현·처방까지 검증).
 *
 * <p><b>순서가 왜 결과를 바꾸나</b> — 위임을 만드는 유일한 자리가 초대 수락이고, 그 수락은
 * 회원 행 락을 <b>안 잡는다</b>(잠그는 것은 조건부 UPDATE가 거는 <b>초대 행</b> 하나다).
 * 게다가 탈퇴가 쥔 락은 {@code FOR NO KEY UPDATE}라 {@code editor_delegations} INSERT를 안 막는다.
 * 그래서 수락이 탈퇴 <b>사이로</b> 끼어들 수 있다.
 *
 * <pre>
 * 위임 먼저(고친 전):  revokeAllOfUser  → 아직 커밋 안 된 위임을 못 본다(READ COMMITTED)
 *                     cancelAllOfUser  → 초대 행 락에서 대기 → 수락 커밋 → ACCEPTED라 건너뜀
 *                     결과: 그 위임은 <b>유일한 일괄 폐기 뒤에 태어나</b> 살아남는다
 *
 * 초대 먼저(고친 뒤):  cancelAllOfUser  → 초대 행 락에서 대기 → 수락 커밋 → ACCEPTED라 건너뜀
 *                     revokeAllOfUser  → 이제 그 위임이 <b>보인다</b> → 닫는다
 * </pre>
 *
 * <p>반대로 수락이 더 늦게 오면 {@code respond}가 탈퇴의 초대 취소에 막혔다가 {@code CANCELED}를
 * 만나 0행으로 실패한다 — <b>남는 틈이 없다.</b>
 *
 * <p>🔴 <b>이 처방은 「위임을 만드는 자리가 초대 수락 하나뿐」에 기대고 있다.</b> 확인함:
 * {@code delegations.save}는 {@code InvitationService.accept} 한 곳뿐이다.
 * 초대 없이 위임을 만드는 경로가 생기는 날 이 순서는 다시 무의미해진다.
 *
 * <p>시각이 아니라 <b>빗장</b>으로 순서를 정한다 — 수락이 쓰기를 끝낸 것을 확인한 뒤에야 탈퇴를 시작한다.
 * 기계가 바빠도 재현이 안 흔들린다.
 */
class WithdrawalDelegationOrderTest extends WithdrawalTestSupport {

    private final InvitationService invitations;
    private final WithdrawalService withdrawalService;
    private final TransactionTemplate tx;

    WithdrawalDelegationOrderTest(MockMvc mockMvc, UserService userService, TokenService tokenService,
                                  JdbcTemplate jdbc, InvitationService invitations,
                                  WithdrawalService withdrawalService, TransactionTemplate tx) {
        super(mockMvc, userService, tokenService, jdbc);
        this.invitations = invitations;
        this.withdrawalService = withdrawalService;
        this.tx = tx;
    }

    @Test
    void 수락이_탈퇴_사이로_끼어들어도_위임이_안_남는다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        long invitationId = invitations.invite(streamer.getId(), emailOf(editor)).getId();

        CountDownLatch 수락이_썼다 = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 수락을 커밋 직전에 붙들어 둔다 — 위임 INSERT는 끝났고 아직 아무도 못 본 상태다.
            Future<?> 수락 = pool.submit(() -> tx.execute(status -> {
                invitations.accept(editor.getId(), invitationId);
                수락이_썼다.countDown();
                sleep(1500);
                return null;
            }));

            assertThat(수락이_썼다.await(10, TimeUnit.SECONDS))
                    .as("수락이 시작조차 안 됐다 — 아래 탈퇴가 경합 없이 도므로 아무것도 안 잰다")
                    .isTrue();
            withdrawalService.withdraw(streamer.getId());
            수락.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(delegationsOf(streamer))
                .as("🔴 탈퇴한 스트리머에게 살아있는 위임이 남았다 — 그 편집자는 사라진 계정의 방송을 "
                        + "계속 편집한다. 탈퇴가 초대를 위임보다 나중에 쳐서 생긴다")
                .allSatisfy(row -> {
                    assertThat(row.get("revoked_at")).as("위임이 안 닫혔다").isNotNull();
                    assertThat(row.get("revoked_by"))
                            .as("사람이 한 행동과 계정이 사라진 것은 다른 사건이다")
                            .isEqualTo("WITHDRAWAL");
                })
                .as("위임이 아예 안 생겼으면 위 단언이 처음부터 참이라 아무것도 안 잰다")
                .hasSize(1);
    }

    // ── 도구 ────────────────────────────────────────────────────────────

    private List<Map<String, Object>> delegationsOf(User streamer) {
        return jdbc.queryForList(
                "SELECT revoked_at, revoked_by FROM editor_delegations WHERE streamer_id = ?",
                streamer.getId());
    }

    private String emailOf(User user) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, user.getId());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("붙들어 두는 중에 끊겼다", e);
        }
    }
}
