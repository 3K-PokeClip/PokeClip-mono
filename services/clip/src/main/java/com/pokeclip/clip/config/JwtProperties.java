package com.pokeclip.clip.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * auth의 같은 이름 record에서 TTL 둘(access·refresh)을 뺐다. clip은 토큰을 발급하지
 * 않으므로 수명을 정할 자격이 없다 — 수명은 발급자인 auth가 넣은 exp가 정본이다.
 */
@ConfigurationProperties(prefix = "pokeclip.jwt")
@Validated
public record JwtProperties(
        /*
         * 길이는 여기서 검증하지 않는다. 바인딩 실패 리포트가 거부된 값을 평문으로
         * 찍기 때문이다 — 32바이트에 한 글자 모자란 진짜 시크릿이 로그·CI에 남는다.
         * 길이 검증은 JwtConfig의 빈 등록 시점에 있다. 누락(빈 값)은 값이 없어서
         * 샐 것도 없으므로 여기서 잡는다.
         */
        @NotBlank String secret) {
}
