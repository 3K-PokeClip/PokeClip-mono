package com.pokeclip.clip.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * /internal/** 밖의 전부. 사람이 부르는 문이라 Bearer JWT만 받는다.
 *
 * <p>permitAll 목록이 auth보다 짧은 것이 정상이다 — 토큰을 얻는 경로(로그인·재발급)가
 * clip에는 없다. 여기 여는 것은 인프라가 부르는 둘뿐이다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        return http
                // 토큰 인증이라 쿠키를 쓰지 않는다. CSRF 방어의 전제가 없다.
                .csrf(csrf -> csrf.disable())
                // 이 줄이 없으면 CorsConfigurationSource 빈이 있어도 적용되지 않는다.
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 컨테이너가 400·404·405를 만들 때 요청을 /error로 ERROR 디스패치하는데,
                        // 시큐리티 체인은 그 디스패치에도 걸린다(AuthorizationFilter의
                        // filterErrorDispatch 기본값이 true다). 여기를 열지 않으면 미인증
                        // 요청의 400·404·405가 전부 401로 바뀌어 나가고, 프론트는 그것을
                        // "토큰 만료"로 오진해 재로그인 루프에 든다(auth와 같은 함정).
                        //
                        // 여는 것이 안전한 이유: /error 본문은 timestamp·status·error·path뿐이다.
                        // message·trace는 Spring Boot 기본값(include-message=never,
                        // include-stacktrace=never)이라 나가지 않는다.
                        .requestMatchers("/error").permitAll()
                        // 로드밸런서·ECS가 토큰 없이 부른다. 막으면 컨테이너가 계속
                        // unhealthy로 판정돼 배포가 롤백된다. 여는 것은 health 하나뿐이며,
                        // 노출 엔드포인트도 application.yml에서 health로 제한해 뒀다.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder)))
                .exceptionHandling(handling ->
                        handling.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }
}
