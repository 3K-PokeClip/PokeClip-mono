package com.pokeclip.auth.streamkey.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * <b>code에 @Pattern·@Size를 걸지 않는다.</b> 바인딩 실패 리포트가 거부된 값을
 * "rejected value [...]"로 평문 기록한다 — 페어링 코드가 그대로 로그에 남는다.
 * 형식 검증은 CrockfordBase32.normalize가 서비스 계층에서 한다.
 * SecretLeakTest.시크릿을_받는_DTO에는_NotBlank_말고는_걸지_않는다()가 못박는다.
 */
public record ExchangeRequest(@NotBlank String code) {
}
