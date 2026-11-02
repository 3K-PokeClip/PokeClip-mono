package com.pokeclip.auth.soop;

/**
 * 치지직과 달리 {@code WITHDRAWN}이 있다. 치지직은 탈퇴도 {@code USER_UNLINKED}로 닫지만 SOOP은 닫는 순간
 * 아이디·닉네임을 비우므로 「왜 비었나」를 사유로 남긴다. 상태 파생에서는 UNLINKED로 본다.
 */
public enum RevokeReason {
    USER_UNLINKED,
    REFRESH_REJECTED,
    WITHDRAWN
}
