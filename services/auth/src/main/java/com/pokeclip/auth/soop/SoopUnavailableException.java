package com.pokeclip.auth.soop;

/**
 * SOOP 응답을 쓸 수 없다: 408·429·5xx·그 밖의 4xx·연결 실패·시간 초과·형식 오류. 재시도할 일시 상태다.
 *
 * <p>원인 예외를 cause로 품지 않는다(치지직과 같다): {@code RestClientResponseException.getMessage()}에 응답
 * 본문이 붙어 오므로 cause 체인을 렌더링하는 로그·핸들러가 본문을 옮긴다. 타입 이름만 보관한다.
 *
 * <p>치지직의 {@code issuedTokens}(토큰은 읽혔는데 뒤가 깨진 경우 버리라고 실어 보내는 값)는 옮기지 않았다:
 * SOOP에는 폐기(revoke) API가 없어 받아도 버릴 길이 없다.
 */
public class SoopUnavailableException extends RuntimeException {

    private final String causeType;

    public SoopUnavailableException(String causeType) {
        super("SOOP 응답을 쓸 수 없다 causeType=" + causeType);
        this.causeType = causeType;
    }

    public String causeType() {
        return causeType;
    }
}
