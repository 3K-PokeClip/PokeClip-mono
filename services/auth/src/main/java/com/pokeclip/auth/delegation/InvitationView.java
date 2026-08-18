package com.pokeclip.auth.delegation;

/** 응답에 나가는 상태. PENDING이면서 기한이 지난 것을 EXPIRED로 갈라 준다. */
public enum InvitationView {
    PENDING, ACCEPTED, DECLINED, CANCELED, EXPIRED
}
