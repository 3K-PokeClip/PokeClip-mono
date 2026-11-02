package com.pokeclip.chat.detector.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * clip과 수집 서버의 {@code /internal/**}을 여는 열쇠. 세 서버가 같은 값을 쓴다.
 *
 * <p>길이 제약을 걸지 않는다 — 바인딩 실패 리포트가 거부된 값을 평문으로 찍는다.
 * {@code @NotBlank}는 거부되는 값이 빈 문자열뿐이라 안전하다(clip의 같은 클래스와 같은 이유).
 *
 * <p><b>이 서버는 이 토큰으로 문을 열지 않는다. 보낼 때만 쓴다</b> — 판별 서버에는
 * {@code /internal/**} 문이 없다.
 */
@ConfigurationProperties(prefix = "pokeclip.internal-api")
@Validated
public record InternalApiProperties(@NotBlank String token) {
}
