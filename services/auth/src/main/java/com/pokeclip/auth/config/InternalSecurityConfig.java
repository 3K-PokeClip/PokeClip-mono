package com.pokeclip.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * /internal/** 전용 체인. 기본 SecurityConfig보다 먼저 매칭돼 이 경로를 통째로
 * 가져간다.
 *
 * <p>체인을 나눈 이유가 둘이다. 기본 체인의 anyRequest().authenticated()와
 * oauth2ResourceServer가 걸리면 Media 헤더가 401을 맞는다. 그리고 <b>인증 수단
 * 둘이 한 체인에 섞이면 "어느 쪽으로든 통과"가 될 위험</b>이 생긴다 —
 * 이 경로는 passphrase를 내려주므로 그 대가가 가장 크다.
 *
 * <p>JwtDecoder를 아예 안 태운다. 사용자 JWT로는 여기 들어올 길이 없다.
 */
@Configuration
public class InternalSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain internalFilterChain(HttpSecurity http,
                                            InternalApiProperties properties) throws Exception {
        return http
                .securityMatcher("/internal/**")
                // 서버끼리 부르고 쿠키를 안 쓴다. CSRF 방어의 전제가 없다.
                .csrf(csrf -> csrf.disable())
                // 브라우저가 부르는 경로가 아니다. CORS를 열 이유가 없다.
                .cors(cors -> cors.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new InternalTokenFilter(properties.token()),
                        UsernamePasswordAuthenticationFilter.class)
                // 필터가 통과시킨 요청은 전부 허용한다. 인가 판단은 위 필터가 끝냈다.
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .exceptionHandling(handling ->
                        handling.authenticationEntryPoint(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }
}
