package com.pokeclip.core.auth.google;

import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
public class GoogleAuthConfig {

    /**
     * 구글 공개키 디코더를 빈으로 등록하지 않고 여기서 만들어 넘긴다.
     * JwtDecoder 타입 빈이 둘이 되면(우리 토큰용·구글용) 자동설정이 어느 것을
     * 쓸지 못 고른다.
     *
     * <p>withJwkSetUri()는 자기 기본 RestTemplate을 만든다. JWK 조회도 로그인
     * 요청 안에서 일어나는 외부 호출이라, 토큰 교환만 막으면 막은 효과가 절반이다.
     * 자동설정된 빌더로 만들어 넘기면 같은 spring.http.clients.*가 적용돼
     * 두 호출의 타임아웃이 한 곳에서 관리된다.
     */
    @Bean
    GoogleIdTokenVerifier googleIdTokenVerifier(GoogleAuthProperties properties,
                                                RestTemplateBuilder restTemplateBuilder) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .restOperations(restTemplateBuilder.build())
                .build();
        return new GoogleIdTokenVerifier(decoder, properties.clientId());
    }
}
