package com.pokeclip.auth.youtube;

/**
 * 사유를 상태 코드와 본문으로 나눠 내보낸다(ChzzkLinkFailure와 같은 정책). 전부 JWT 뒤라
 * 미인증 트래픽이 이 사유를 캐낼 수 없다.
 */
public enum YoutubeLinkFailure {
    /** state가 이 사용자 것이 아니거나 만료·위조. 400 */
    INVALID_STATE,
    /** 구글이 교환을 4xx(429·408·invalid_client·403 할당량 제외)로 거부 — code 소모·만료. 동의부터 다시. 400 */
    INVALID_CODE,
    /** 동의 화면에서 업로드 권한 체크를 지웠다 — 받은 scope에 youtube.upload가 없다. 400 */
    SCOPE_MISSING,
    /** 구글 계정에 유튜브 채널이 하나도 없다. 채널을 먼저 만들어야 한다. 400 */
    NO_CHANNEL,
    /** 다른 계정에 이미 묶인 채널. 409 */
    CHANNEL_ALREADY_LINKED,
    /** 구글 5xx·타임아웃·형식 오류·429·408·invalid_client·403 할당량. 502 */
    YOUTUBE_UNAVAILABLE
}
