package com.pokeclip.auth.chzzk;

/**
 * 사유를 상태 코드와 본문으로 나눠 내보낸다(StreamKeyFailure와 같은 정책). 전부 JWT 뒤라
 * 미인증 트래픽이 이 사유를 캐낼 수 없다.
 */
public enum ChzzkLinkFailure {
    /** state가 이 사용자 것이 아니거나 만료·위조. 400 */
    INVALID_STATE,
    /** 치지직이 교환 또는 me를 4xx(429·408 제외)로 거부 — code 소모·만료·scope 부족. 동의부터 다시. 400 */
    INVALID_CODE,
    /** 다른 계정에 이미 묶인 채널. 409 */
    CHANNEL_ALREADY_LINKED,
    /** 치지직 5xx·타임아웃·형식 오류·429·408. 502 */
    CHZZK_UNAVAILABLE
}
