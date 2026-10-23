package com.pokeclip.auth.soop;

/**
 * SOOP이 요청을 거부했다: 토큰 주소의 400·401·403, 또는 API가 200에 {@code result} 음수로 답한 경우(status 200).
 * code 소모·만료·토큰 철회·권한 부족. 상태 코드만 품는다(응답 본문·msg는 옮기지 않는다).
 */
public class SoopRejectedException extends RuntimeException {

    private final int status;

    public SoopRejectedException(int status) {
        super("SOOP이 요청을 거부했다 status=" + status);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
