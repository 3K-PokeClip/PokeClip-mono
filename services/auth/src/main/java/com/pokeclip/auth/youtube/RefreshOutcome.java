package com.pokeclip.auth.youtube;

public enum RefreshOutcome {
    /** 구글이 새 access를 줬고 한 커밋으로 저장했다. refresh는 응답에 있으면 교체, 없으면(정상) 유지. */
    REFRESHED,
    /** 남은 수명이 충분해 구글을 부르지 않았다. */
    SKIPPED_FRESH,
    /**
     * 구글이 4xx(429·408·invalid_client·403 할당량 제외)로 거부 — 대개 {@code invalid_grant}다.
     * 행을 BROKEN(REFRESH_REJECTED)으로 닫고 다시 시도하지 않는다. 복구 수단은 재동의뿐이다.
     */
    REJECTED,
    /** 구글 5xx·타임아웃·형식 오류·429·408·invalid_client·403 할당량 — 행 무변경. 다음 틱에 다시 시도한다. */
    UNAVAILABLE,
    /** 살아있는 연동이 없다(미연동·해제·BROKEN). */
    NOT_LINKED
}
