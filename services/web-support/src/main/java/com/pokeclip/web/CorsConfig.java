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
        // preflight는 Access-Control-Request-Method(GET·POST·PUT·PATCH·DELETE)로 판정된다.
        // OPTIONS 자체를 목록에 둘 필요가 없다. DELETE는 치지직 연동 해제(auth `DELETE /api/chzzk-link`)가
        // 쓴다 — 목록에 없으면 브라우저 preflight가 403으로 막혀 화면에서 해제가 안 된다(PR #63 codex 지적).
        // PATCH는 회원정보 수정(auth `PATCH /api/auth/me`, POK-207)이 같은 이유로 쓴다.
        // PUT은 프로필 사진 올리기(auth `PUT /api/auth/me/photo`, POK-207)다 — 회원마다 사진이 하나라
        // 같은 자리에 덮어쓴다.
        //
        // 이 빈을 실제로 무는 것은 auth와 clip 둘이다(chat-collector는 이 모듈의 testFixtures에 있는
        // LogCaptor만 쓴다). 여기를 고치면 :auth:test와 :clip:test를 둘 다 돌린다 — POK-207에서
        // 이 파일을 두 번 고쳐 놓고 :clip:test는 최종 감사가 강제하기 전까지 아무도 안 돌렸다.
        //
        // 쓰지 않는 메서드는 열지 않는다 — 여는 것 자체가 손해는 아니지만, 목록에 있는 메서드는
        // "누군가 쓰고 있다"는 뜻으로 읽히므로 실제 창구가 생길 때 같이 넣는다.
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // 쿠키를 안 쓴다. 토큰은 Authorization 헤더로 온다.
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
