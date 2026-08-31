package com.pokeclip.auth.config;

import com.pokeclip.auth.user.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
                                            UserRepository userRepository) throws Exception {
        return http
                // 토큰 인증이라 쿠키를 쓰지 않는다. CSRF 방어의 전제가 없다.
                .csrf(csrf -> csrf.disable())
                // 이 줄이 없으면 CorsConfigurationSource 빈이 있어도 적용되지 않는다.
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 토큰이 없어야 부를 수 있는 것만 연다. 로그인은 토큰을 얻는
                        // 경로이고, 재발급·로그아웃은 access가 만료된 뒤에도 되어야 한다.
                        .requestMatchers("/api/auth/google", "/api/auth/refresh", "/api/auth/logout")
                        .permitAll()
                        // 플러그인은 로그인하지 않는다. 코드 자체가 자격증명이다(ADR-019).
                        // /api/auth/refresh에 이은 두 번째 permitAll이라 같은 함정을
                        // 공유한다 — 이 경로의 실패 로그에 건수로 알람을 걸면 안 된다.
                        .requestMatchers("/api/stream-keys/pairing-codes/exchange").permitAll()
                        // 그림 태그는 인증 헤더를 못 싣는다(웹은 쿠키를 안 쓴다). 자격은 주소에 실린
                        // 사진 표가 대신한다 — 그 표로 열 수 있는 것은 그림 한 장뿐이고 몇 분이면 죽는다.
                        // /api/auth/refresh·페어링 교환에 이은 세 번째 permitAll이라 같은 함정을
                        // 공유한다: 이 경로의 실패 로그에 건수로 알람을 걸면 안 된다.
                        //
                        // GET만 연다. 사진을 바꾸는 PUT은 /api/auth/me/photo에 있고 토큰을 요구한다.
                        .requestMatchers(HttpMethod.GET, "/api/profile-photos/**").permitAll()
                        // 컨테이너가 400·404·405를 만들 때 요청을 /error로 ERROR 디스패치하는데,
                        // 시큐리티 체인은 그 디스패치에도 걸린다(AuthorizationFilter의
                        // filterErrorDispatch 기본값이 true다). 여기를 열지 않으면 미인증
                        // 요청의 400·404·405가 전부 401로 바뀌어 나가고, 프론트는 그것을
                        // "토큰 만료"로 오진해 재로그인 루프에 든다.
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
                // 탈퇴한 회원의 남은 접근 표를 막는다(POK-171). 자리가 인증 "뒤"여야
                // 주체에서 회원 번호를 읽을 수 있다 — 앞에 두면 SecurityContext가 아직
                // 비어 있어 전 요청을 그냥 통과시킨다(주입으로 확인: 7건이 빨간불).
                //
                // 빈으로 두지 않는 이유는 InternalTokenFilter와 같다. @Component를 붙이면
                // 서블릿이 전역 등록해 끼우는 자리가 명시가 아니라 등록 순서에 딸려 가고,
                // 보안 체인 밖에서도 돈다. WithdrawnAccountBlockTest가 빈 목록으로 못박는다.
                //
                // 🔴 패키지가 Spring Security 6.x와 다르다 — 7.x는
                // ...resource.web.authentication.BearerTokenAuthenticationFilter다(jar에서 확인).
                .addFilterAfter(new WithdrawnAccountFilter(userRepository),
                        BearerTokenAuthenticationFilter.class)
                .exceptionHandling(handling ->
                        handling.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }
}
