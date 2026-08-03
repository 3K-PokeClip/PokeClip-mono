package com.pokeclip.auth.streamkey;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "pokeclip.secret-store")
@Validated
public record SecretStoreProperties(
        /*
         * 길이·base64 형식을 여기서 검증하지 않는다. 바인딩 실패 리포트가 거부된
         * 값을 평문으로 찍어 진짜 암호화 키가 로그·CI에 남는다. 검증은
         * SecretStoreConfig의 빈 등록 시점에 있다. @NotBlank는 거부되는 값이
         * 빈 문자열뿐이라 안전하다.
         */
        @NotBlank String key) {
}
