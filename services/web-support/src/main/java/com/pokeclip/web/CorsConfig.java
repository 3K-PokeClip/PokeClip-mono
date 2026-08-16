package com.pokeclip.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class CorsConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.allowedOrigins());
        // preflight는 Access-Control-Request-Method(GET·POST·DELETE)로 판정된다.
        // OPTIONS 자체를 목록에 둘 필요가 없다. DELETE는 치지직 연동 해제(auth `DELETE /api/chzzk-link`)가
        // 쓴다 — 목록에 없으면 브라우저 preflight가 403으로 막혀 화면에서 해제가 안 된다(PR #63 codex 지적).
        config.setAllowedMethods(List.of("GET", "POST", "DELETE"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // 쿠키를 안 쓴다. 토큰은 Authorization 헤더로 온다.
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
