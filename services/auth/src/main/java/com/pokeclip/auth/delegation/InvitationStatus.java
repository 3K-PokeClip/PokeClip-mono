package com.pokeclip.auth.delegation;

/** DB에 저장되는 상태. 만료는 여기 없다 — expires_at으로 판정한다. */
public enum InvitationStatus {
    PENDING, ACCEPTED, DECLINED, CANCELED
}
