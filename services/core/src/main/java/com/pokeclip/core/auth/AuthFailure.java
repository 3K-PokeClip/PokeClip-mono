package com.pokeclip.core.auth;

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

    /** 인증 실패가 아니라 데이터 불일치. DataInconsistencyException이 쓴다. */
    USER_NOT_FOUND
}
