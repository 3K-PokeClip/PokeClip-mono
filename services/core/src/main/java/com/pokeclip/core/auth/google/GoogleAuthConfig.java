package com.pokeclip.core.auth.google;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
public class GoogleAuthConfig {

    /**
     * 구글 공개키 디코더를 빈으로 등록하지 않고 여기서 만들어 넘긴다.
     * JwtDecoder 타입 빈이 둘이 되면(우리 토큰용·구글용) 자동설정이 어느 것을
     * 쓸지 못 고른다.
     */
    @Bean
    GoogleIdTokenVerifier googleIdTokenVerifier(GoogleAuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        return new GoogleIdTokenVerifier(decoder, properties.clientId());
    }
}
