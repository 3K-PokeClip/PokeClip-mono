package com.pokeclip.auth.streamkey.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pokeclip.auth.streamkey.StreamKeyService.ResolveResult;

/**
 * 계약4의 응답. NON_NULL이라 거절 응답에는 passphrase 필드가 아예 나타나지 않는다.
 *
 * <p><b>{}에 통째로 넣지 않는다.</b> SecretLeakTest가 "ResolveResponse["를 금지한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResolveResponse(boolean valid, Long userId, String passphrase, String reason) {

    public static ResolveResponse from(ResolveResult result) {
        return new ResolveResponse(
                result.valid(), result.userId(), result.passphrase(), result.reason());
    }
}
