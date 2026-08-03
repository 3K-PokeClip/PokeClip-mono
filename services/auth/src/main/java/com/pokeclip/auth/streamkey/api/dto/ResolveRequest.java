package com.pokeclip.auth.streamkey.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * streamid에 @Pattern·@Size를 걸지 않는다. 바인딩 실패 리포트가 거부된 값을
 * 평문으로 찍는다. 형식 검증은 StreamId.parse가 하고, 실패는 MALFORMED로 나간다.
 */
public record ResolveRequest(@NotBlank String streamid) {
}
