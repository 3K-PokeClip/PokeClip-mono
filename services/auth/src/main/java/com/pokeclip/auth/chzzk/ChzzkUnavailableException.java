package com.pokeclip.auth.chzzk;

import java.util.Optional;

/**
 * 치지직 응답을 쓸 수 없다 — 5xx·타임아웃·IO·형식 오류, 그리고 4xx 중 429·408·INVALID_CLIENT(일시 — 재시도).
 *
 * <p>원인 예외를 cause로 품지 않는다. {@code RestClientResponseException.getMessage()}에 응답
 * 본문이 붙어 오므로 cause 체인을 렌더링하는 로그·핸들러가 본문을 옮긴다. 타입 이름만 보관한다.
 */
public class ChzzkUnavailableException extends RuntimeException {

    private final String causeType;
    /** 응답에서 토큰까지는 읽혔는데 그 뒤가 깨진 경우 — 치지직엔 이미 발급됐으니 호출부가 버려야 한다. 메시지·toString에 안 실린다. */
    private final ChzzkTokens issuedTokens;

    public ChzzkUnavailableException(String causeType) {
        this(causeType, null);
    }

    public ChzzkUnavailableException(String causeType, ChzzkTokens issuedTokens) {
        super("치지직 응답을 쓸 수 없다 causeType=" + causeType);
        this.causeType = causeType;
        this.issuedTokens = issuedTokens;
    }

    public String causeType() {
        return causeType;
    }

    public Optional<ChzzkTokens> issuedTokens() {
        return Optional.ofNullable(issuedTokens);
    }
}
