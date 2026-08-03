package com.pokeclip.auth.google;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 전부 없으면 안 되는 값이라 @NotBlank를 건다. 환경변수가 빠진 채로 부팅이
 * 성공하면 로그인만 전부 401이 되는데, 배포도 헬스체크도 통과해서 원인을
 * 가리키는 신호가 아무 데도 남지 않는다. 부팅에서 죽는 편이 낫다.
 */
@ConfigurationProperties(prefix = "pokeclip.google")
@Validated
public record GoogleAuthProperties(
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @NotBlank String redirectUri,
        @NotBlank String tokenUri,
        @NotBlank String jwkSetUri
) {
}
