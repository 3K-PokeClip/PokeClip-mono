package com.pokeclip.auth.delegation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 초대 행의 저장만 담당한다. InvitationService에서 떼어낸 이유는 UserCreator와 같다 —
 * 제약 위반이 난 트랜잭션은 rollback-only로 표시되고 Hibernate 세션도 오염돼 그 안에서는
 * 재조회를 할 수 없다. 예외를 밖으로 던져 이 트랜잭션을 끝내고, 호출한 쪽이 새로 읽는다.
 *
 * <p>REQUIRES_NEW와 "InvitationService.invite에 &#64;Transactional이 없다"는 <b>서로 겹치는
 * 방어다 — 실측하니 둘 중 하나만 있어도 재조회가 산다</b>(2026-08-18, 8스레드 경합 테스트).
 * invite에 &#64;Transactional을 걸어도 REQUIRES_NEW가 바깥 트랜잭션을 매달아 두고 별도
 * 세션에서 실패하므로, 오염되는 것은 안쪽뿐이고 바깥 세션으로 재조회가 된다.
 * <b>둘 다 없앴을 때만 빨간불이 됐다.</b> 그때 나온 원문이
 * {@code org.hibernate.AssertionFailure: Entry for instance of 'EditorInvitation' has a null
 * identifier (this can happen if the session is flushed after an exception occurs)}다 —
 * 오염된 세션에서 재조회가 돈다는 진단이 그대로 확인된다(transaction-auditor 라운드 2).
 *
 * <p><b>다만 invite에 트랜잭션을 거는 것은 「안 죽는다」일 뿐 공짜가 아니다.</b> 바깥이
 * 커넥션을 쥔 채 REQUIRES_NEW가 두 번째를 요구하므로 <b>요청당 커넥션이 2배</b>가 된다
 * (8스레드 기준 피크 8 → 16, 실측). 지금 구조는 요청당 1개다.
 *
 * <p>하나를 지워도 초록이라고 해서 안전한 것이 아니라 남은 하나가 막고 있는 것이니,
 * 둘 다 유지한다(UserService.findOrCreate와 같은 구조).
 */
@Component
@RequiredArgsConstructor
class InvitationWriter {

    private final EditorInvitationRepository invitations;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    EditorInvitation create(Long streamerId, Long inviteeId, Instant expiresAt, Instant now) {
        return invitations.saveAndFlush(
                EditorInvitation.of(streamerId, inviteeId, expiresAt, now));
    }

    /** &#64;Modifying 쿼리는 트랜잭션을 요구한다. 조합부에 트랜잭션이 없으므로 여기서 연다. */
    @Transactional
    int extend(Long invitationId, Instant expiresAt, Instant now) {
        return invitations.extend(invitationId, expiresAt, now);
    }
}
