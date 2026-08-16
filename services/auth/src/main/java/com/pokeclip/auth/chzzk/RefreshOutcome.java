package com.pokeclip.auth.chzzk;

public enum RefreshOutcome {
    /** 치지직이 새 토큰을 줬고 한 커밋으로 저장했다. */
    REFRESHED,
    /** 남은 수명이 충분해 치지직을 부르지 않았다. */
    SKIPPED_FRESH,
    /** 치지직이 4xx(429·408·INVALID_CLIENT 제외)로 거부 — 행을 BROKEN(REFRESH_REJECTED)으로 닫았다. 다시 시도하지 않는다. */
    REJECTED,
    /** 치지직 5xx·타임아웃·형식 오류·429·408·INVALID_CLIENT(앱 자격증명 오류 — 우리 설정 문제) — 행 무변경. 다음 틱에 다시 시도한다. */
    UNAVAILABLE,
    /** 살아있는 연동이 없다(미연동·해제·BROKEN). */
    NOT_LINKED
}
