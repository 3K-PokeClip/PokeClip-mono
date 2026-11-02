package com.pokeclip.auth.youtube;

/**
 * 구글이 4xx로 거부했다 — code 소모·만료·토큰 철회(invalid_grant)·권한 부족.
 * 429·408·invalid_client·403 할당량 코드는 여기 오지 않는다(일시라 Unavailable). 상태 코드만 품는다.
 */
public class YoutubeRejectedException extends RuntimeException {

    private final int status;

    public YoutubeRejectedException(int status) {
        super("구글이 요청을 거부했다 status=" + status);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
