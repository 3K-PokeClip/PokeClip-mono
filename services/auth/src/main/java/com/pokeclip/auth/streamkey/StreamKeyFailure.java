package com.pokeclip.auth.streamkey;

/**
 * 스트림키·페어링 코드의 실패 사유.
 *
 * <p><b>AuthFailure와 정책이 반대다.</b> AuthFailure는 사유를 응답에 내보내지
 * 않는다 — 로그인 경로에서 "계정이 있나"가 새기 때문이다. 여기는 사유를
 * 그대로 내보낸다. POK-72 인수 기준이 요구하고, IP당 분당 5회 제한이 그
 * 누출을 무의미하게 만든다(ADR-019: 길이·만료·rate limit은 세트로만 유효하다).
 *
 * <p><b>rate limit을 빼면 이 결정이 같이 무너진다.</b>
 */
public enum StreamKeyFailure {

    PAIRING_CODE_NOT_FOUND,
    PAIRING_CODE_EXPIRED,
    PAIRING_CODE_ALREADY_USED,
    PAIRING_CODE_RATE_LIMITED,

    /** 재발급인데 폐기할 키가 없다. 조용히 새로 발급하면 무효화 로그가 거짓이 된다. */
    STREAM_KEY_NOT_FOUND
}
