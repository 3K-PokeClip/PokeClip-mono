package com.pokeclip.auth.delegation;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EditorInvitationTest {

    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    /**
     * 만료를 상태 컬럼에 쓰지 않으므로 DB에는 PENDING으로 남아 있다.
     * 응답에서만 EXPIRED로 바꿔 준다 — 이 파생이 없으면 만료된 초대가 초대함에 살아 있는 것처럼 보인다.
     */
    @Test
    void 기한이_지난_PENDING은_EXPIRED로_보인다() {
        EditorInvitation invitation = EditorInvitation.of(1L, 2L, NOW.plusSeconds(60), NOW);

        assertThat(invitation.view(NOW)).isEqualTo(InvitationView.PENDING);
        assertThat(invitation.view(NOW.plusSeconds(61))).isEqualTo(InvitationView.EXPIRED);
    }

    /** 기한 경계는 지난 뒤부터 만료다. 같은 시각은 아직 살아 있다. */
    @Test
    void 기한과_같은_시각은_아직_만료가_아니다() {
        EditorInvitation invitation = EditorInvitation.of(1L, 2L, NOW.plusSeconds(60), NOW);

        assertThat(invitation.view(NOW.plusSeconds(60))).isEqualTo(InvitationView.PENDING);
    }

    /** 이미 처리된 초대는 기한이 지나도 그 결과가 남는다 — 거절한 것이 만료로 바뀌면 이력이 거짓이 된다. */
    @Test
    void 처리된_초대는_기한이_지나도_결과가_남는다() {
        EditorInvitation invitation = EditorInvitation.of(1L, 2L, NOW.plusSeconds(60), NOW);
        invitation.markResponded(InvitationStatus.DECLINED, NOW.plusSeconds(10));

        assertThat(invitation.view(NOW.plusSeconds(999))).isEqualTo(InvitationView.DECLINED);
    }
}
