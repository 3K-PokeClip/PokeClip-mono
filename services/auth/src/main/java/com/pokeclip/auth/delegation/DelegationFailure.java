package com.pokeclip.auth.delegation;

/**
 * 사유를 상태 코드와 본문으로 나눠 내보낸다(ChzzkLinkFailure와 같은 정책).
 * 전부 JWT 뒤라 미인증 트래픽이 이 사유를 캐낼 수 없다.
 *
 * <p>INVITEE_NOT_FOUND는 "그 이메일로 가입했는지"를 알려준다 — 카드 완료조건이
 * 요구한 것이고, 로그인한 스트리머만 부를 수 있다는 것이 유일한 방벽이다.
 */
public enum DelegationFailure {
    /** 그 이메일로 가입한 계정이 없다. 404 */
    INVITEE_NOT_FOUND,
    /** 자기 자신을 초대했다. 400 */
    SELF_INVITE,
    /** 이미 살아있는 위임이 있다. 409 */
    ALREADY_EDITOR,
    /** 살아있는 초대가 상한(20)에 찼다. 취소로 자리를 비운다. 409 */
    TOO_MANY_PENDING,
    /** 없거나 남의 초대다. 존재 여부를 알려주지 않는다. 404 */
    INVITATION_NOT_FOUND,
    /** 7일이 지났다. 410 */
    INVITATION_EXPIRED,
    /** 이미 수락·거절·취소됐다. 409 */
    INVITATION_NOT_PENDING,
    /** 없거나 내 위임이 아니다. 404 */
    DELEGATION_NOT_FOUND
}
