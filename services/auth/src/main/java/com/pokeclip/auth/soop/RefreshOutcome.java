package com.pokeclip.auth.soop;

public enum RefreshOutcome {
    /** SOOP이 새 토큰을 줬고 한 커밋으로 저장했다. */
    REFRESHED,
    /** 남은 수명이 충분하고(점검이면 확인도 신선하고) SOOP을 부르지 않았다. */
    SKIPPED_FRESH,
    /** SOOP이 400·401·403으로 거부. 행을 BROKEN(REFRESH_REJECTED)으로 닫고 아이디·닉네임·토큰을 지웠다. 다시 시도하지 않는다. */
    REJECTED,
    /** SOOP 5xx·시간 초과·형식 오류·그 밖의 4xx. 행 무변경. 다음에 다시 시도한다. */
    UNAVAILABLE,
    /** 살아 있는 연동이 없다(미연동·해제·BROKEN). */
    NOT_LINKED
}
