package com.pokeclip.clip.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 수동 마킹 문({@code POST /api/clip/marks}, 계약4 4B) 전용 체인. <b>로그인(JWT) 밖</b>이다: OBS 플러그인은 사람 토큰이 없고,
 * 자격은 본문의 스트림키와 그 파생 값이다. 판정은 문(서비스)이 하고 거절 본문 {@code {"reason":…}}도 문이 만든다.
 *
 * <p>{@code @Order(0)}로 사람 체인({@code SecurityConfig}, 경로를 안 가린다)보다 앞에서 이 경로만 가져간다. 빠지면 사람 체인이
 * 「JWT가 아닌 Bearer」로 읽어 사유 없는 401을 내고, 플러그인은 그것을 「마크 경로가 아직 없다」로 읽어 버린다(mark-policy.cpp).
 * 사람 토큰을 실어 보내도 이 체인은 안 읽는다(필터가 없다).
 */
@Configuration
public class MarkSecurityConfig {

    @Bean
    @Order(0)
    SecurityFilterChain markFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/api/clip/marks")
                // 쿠키를 안 쓴다(자격이 본문·헤더에 실려 온다). CSRF 방어의 전제가 없다.
                .csrf(csrf -> csrf.disable())
                // 브라우저가 부르는 경로가 아니다.
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
