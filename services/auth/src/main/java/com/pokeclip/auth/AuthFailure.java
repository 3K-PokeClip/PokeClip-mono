package com.pokeclip.auth;

/**
 * 로그에 찍히는 실패 사유. 응답에는 나가지 않는다 — 클라이언트에는 전부 같은 401이다.
 *
 * <p>영어로 두는 이유는 검색·집계 때문이다. 예외 메시지는 한국어로 남겨 두고,
 * 로그에는 이 이름만 찍는다.
 */
public enum AuthFailure {

    GOOGLE_TOKEN_EXCHANGE_FAILED,
    GOOGLE_RESPONSE_MISSING_ID_TOKEN,
    GOOGLE_ID_TOKEN_INVALID,

    REFRESH_TOKEN_UNKNOWN,
    /** 유예 창 안의 중복 회전. 정상 동작이라 INFO다. */
    REFRESH_TOKEN_ALREADY_ROTATED,
    /** 유예 창을 넘긴 재사용. 탈취를 의심해 세션을 전부 끊은 뒤 던진다. */
    REFRESH_TOKEN_REUSED,
    REFRESH_TOKEN_EXPIRED,
    /**
     * 사용자 행 락 밖에서 도는 logout이 끼어들어 이미 취소된 토큰.
     * <b>세션을 하나도 끊지 않았다</b> — 여기가 REFRESH_TOKEN_REUSED와 갈리는 지점이다.
     * 둘을 같은 것으로 읽으면 봉쇄가 있었는지를 반대로 판단한다.
     */
    REFRESH_TOKEN_ALREADY_USED,

    ACCESS_TOKEN_SUBJECT_INVALID,

    /**
     * 새 google_sub인데 이메일이 이미 다른 계정에 있다. V108의 uq_users_email이 연 경로다 —
     * 구글 계정을 지웠다 같은 주소로 다시 만들면 sub가 바뀌어 여기 온다.
     *
     * <p><b>이 사유만 409로 답하고 이유를 알려 준다</b>(사용자 결정 2026-08-18 — "정중히 거절").
     * 나머지 인증 실패는 전부 401 일반 응답 그대로다. 갈리는 근거는 감출 이익이 없다는 것이다 —
     * 사용자가 직접 풀어야 하는 상태 충돌이라 안 알려주면 재시도만 반복한다.
     * <b>이메일은 응답에도 로그에도 안 실린다.</b>
     */
    EMAIL_ALREADY_REGISTERED,

    /** 인증 실패가 아니라 데이터 불일치. DataInconsistencyException이 쓴다. */
    USER_NOT_FOUND
}
