package com.pokeclip.auth.chzzk;

/** 치지직이 4xx로 거부했다(429·408·INVALID_CLIENT 제외 — 그 셋은 일시라 Unavailable) — code 소모·만료·토큰 철회·scope 부족. 상태 코드만 품는다. */
public class ChzzkRejectedException extends RuntimeException {

    private final int status;

    public ChzzkRejectedException(int status) {
        super("치지직이 요청을 거부했다 status=" + status);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
