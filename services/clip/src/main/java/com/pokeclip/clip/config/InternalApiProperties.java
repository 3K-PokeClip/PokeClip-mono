package com.pokeclip.clip.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "pokeclip.internal-api")
@Validated
public record InternalApiProperties(
        /*
         * 길이 제약을 걸지 않는다. 바인딩 실패 리포트가 거부된 값을 평문으로
         * 찍는다(JwtProperties와 같은 이유). @NotBlank는 거부되는 값이 빈
         * 문자열뿐이라 안전하다.
         */
        @NotBlank String token) {
}
