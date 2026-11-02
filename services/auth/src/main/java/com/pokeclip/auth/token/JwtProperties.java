package com.pokeclip.auth.token;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties(prefix = "pokeclip.jwt")
@Validated
public record JwtProperties(
        /*
         * 길이는 여기서 검증하지 않는다. 바인딩 실패 리포트가 거부된 값을 평문으로
         * 찍기 때문이다 — 32자에 한 글자 모자란 진짜 시크릿이 로그·CI에 남는다.
         * 길이 검증은 JwtConfig의 빈 등록 시점에 있다. 누락(빈 값)은 값이 없어서
         * 샐 것도 없으므로 여기서 잡는다.
         */
        @NotBlank String secret,
        /*
         * 수명 둘은 @NotNull이다. 없으면 바인딩이 조용히 null을 넣고, 그 null이 터지는 자리가 값에서
         * 멀다 — refresh 쪽은 RetentionKeepForCheck.check()의 compareTo(null)이 「이름 없는 NPE」로
         * 부팅을 죽이고, access 쪽은 부팅을 지나 토큰 발급 때 500이 된다. 이름을 찍어야 어디를 고칠지 안다.
         * 시크릿과 달리 값 자체는 비밀이 아니라 바인딩 실패 리포트에 실려도 된다.
         */
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl) {
}
