package com.pokeclip.auth.soop;

/**
 * 사유를 상태 코드와 본문({@code {"reason": 이름}})으로 나눠 내보낸다(치지직과 같은 정책, 웹이 reason을 읽는다).
 * 전부 JWT 뒤라 미인증 트래픽이 이 사유를 캐낼 수 없다.
 */
public enum SoopLinkFailure {
    /** state가 이 사용자 것이 아니거나 만료·위조. 400 */
    INVALID_STATE,
    /** SOOP이 교환 또는 아이디·닉네임 조회를 거부(400·401·403, 또는 result 음수). 동의부터 다시. 400 */
    INVALID_CODE,
    /** 다른 계정에 이미 묶인 SOOP 방송국. 409 */
    CHANNEL_ALREADY_LINKED,
    /** SOOP 5xx·시간 초과·형식 오류·그 밖의 4xx. 502 */
    SOOP_UNAVAILABLE
}
