package com.pokeclip.clip.config;

import com.pokeclip.web.security.DedicatedWindow;
import com.pokeclip.web.security.DenyAllFilter;
import com.pokeclip.web.security.SourceNetworkFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.server.autoconfigure.ServerProperties.ForwardHeadersStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * 만료 방송 창구 전용 출입증 체인(POK-196 M6). 1번(Media)의 만료 실행기가 공용 {@code X-Internal-Token} 대신 전용 헤더로
 * {@code GET /internal/broadcasts/expired}를 부르는 길이다. 판정은 출발지(403) 다음 토큰(401)이다. auth 의 resolve 전용
 * 체인({@code StreamKeyResolveSecurityConfig})과 같은 모양이고 부품도 같다(web-support {@code security} 패키지).
 *
 * <p>만료 목록 요청 가운데 전용 헤더가 있는 것만 이 체인이 가져간다. 헤더가 없으면 지금처럼 {@link
 * InternalSecurityConfig}의 공용 체인이 받는다. 그래서 한 체인에 인증 수단 둘이 섞이지 않는다.
 *
 * <p>순번은 {@code -1}이다. 마킹 체인({@link MarkSecurityConfig}, {@code 0})과 겹치지 않는 값으로 공용 체인({@code 1})보다
 * 앞에 둔다. 매처는 서로 겹치지 않지만, 같은 순번이면 등록 순서에 기대게 된다.
 *
 * <p>전용 토큰이 비면 창구를 닫는다. 체인은 그대로 두고 {@link DenyAllFilter}만 단다 — 출발지 필터도 달지 않아, 허용
 * 대역까지 비어 있어도 닫힘은 늘 401 이다. 부팅 검사도 토큰이 있을 때만 한다.
 *
 * <p>공용 체인의 토큰 필터는 clip 자기 {@code InternalTokenFilter}이고, 이 체인은 헤더 이름을 받는 web-support 의 것을 쓴다.
 * 같은 이름이라 아래에서 전체 이름으로 적는다.
 *
 * <p>웹 앱일 때만 있다. 웹이 없는 컨텍스트에는 {@code HttpSecurity} 빈이 없어 이 설정이 있으면 못 뜬다.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ExpiredBroadcastSecurityConfig {

  /** Media 와 맞춘 헤더 이름. 공용 헤더와 이름을 갈라 한쪽 토큰을 다른 쪽 헤더에 실어도 안 통한다. */
  static final String HEADER = "X-Expired-Broadcasts-Token";

  private static final RequestMatcher EXPIRED_WITH_HEADER =
      new AndRequestMatcher(
          PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/internal/broadcasts/expired"),
          new RequestHeaderRequestMatcher(HEADER));

  @Bean
  @Order(-1) // 마킹 체인(0) · 공용 체인 internalFilterChain(1)보다 먼저 이 요청을 가져간다
  SecurityFilterChain expiredBroadcastFilterChain(
      HttpSecurity http,
      ExpiredBroadcastAccessProperties access,
      InternalApiProperties internalApi,
      ServerProperties server,
      TomcatServerProperties tomcat)
      throws Exception {
    http.securityMatcher(EXPIRED_WITH_HEADER)
        // 서버끼리 부르고 쿠키를 안 쓴다. CSRF 방어의 전제가 없다.
        .csrf(csrf -> csrf.disable())
        // 브라우저가 부르는 경로가 아니다. CORS 를 열 이유가 없다.
        .cors(cors -> cors.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // 필터가 통과시킨 요청은 전부 허용한다. 판단은 아래 필터들이 끝낸다.
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

    if (!access.isEnabled()) {
      return http
          .addFilterBefore(new DenyAllFilter(), UsernamePasswordAuthenticationFilter.class)
          .build();
    }
    ForwardHeadersStrategy strategy = server.getForwardHeadersStrategy();
    TomcatServerProperties.Remoteip remoteIp = tomcat.getRemoteip();
    DedicatedWindow.requireSafeToOpen(
        "pokeclip.internal-api.expired-broadcasts.",
        access.allowedCidrs(),
        access.token(),
        internalApi.token(),
        strategy == null ? null : strategy.name(),
        remoteIp.getRemoteIpHeader(),
        remoteIp.getProtocolHeader());
    return http
        .addFilterBefore(
            new SourceNetworkFilter(access.allowedCidrs()),
            UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(
            new com.pokeclip.web.security.InternalTokenFilter(access.token(), HEADER),
            SourceNetworkFilter.class)
        .build();
  }

  /** 표시 밸브를 엔진 밸브 맨 앞에 넣는다. 순서 근거는 {@link DedicatedWindow#markValveFirst()}. */
  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> forwardedHeaderMarkValveFirst() {
    return DedicatedWindow.markValveFirst();
  }
}
