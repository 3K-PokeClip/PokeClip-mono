package com.pokeclip.web;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * 허용 출처가 비면 부팅을 실패시킨다. 안 주면 서버는 뜨고 헬스체크도 통과하는데
 * 브라우저 호출만 전부 죽는다 — JWT_SECRET을 안 줬을 때와 같은 모양의 함정이라
 * 같은 방식으로 막는다.
 *
 * <p>와일드카드도 부팅에서 막는다. allowCredentials=false라 CORS 명세상 "*"가
 * 허용되므로 아무도 안 막아준다. 우리 API는 Authorization 헤더로 토큰을 받으니
 * "*"가 들어가면 아무 사이트나 우리 API를 부를 수 있다.
 */
@ConfigurationProperties(prefix = "pokeclip.cors")
@Validated
public record CorsProperties(@NotEmpty List<String> allowedOrigins) {

    public CorsProperties {
        if (allowedOrigins != null && allowedOrigins.contains("*")) {
            throw new IllegalArgumentException("CORS 허용 출처에 와일드카드를 둘 수 없다");
        }
    }
}
