package com.pokeclip.auth.config;

import com.pokeclip.web.security.DedicatedWindow;
import com.pokeclip.web.security.DenyAllFilter;
import com.pokeclip.web.security.ForwardedHeaderMarkValve;
import com.pokeclip.web.security.InternalTokenFilter;
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
 * resolve 전용 출입증 체인(1번 T 설계 2-7). Media 가 공용 {@code X-Internal-Token} 대신 전용 헤더로 스트림키
 * resolve 를 부르는 길이다. 판정은 출발지(403) 다음 토큰(401)이다.
 *
 * <p>resolve 요청 가운데 전용 헤더가 있는 것만 이 체인이 가져간다. 헤더가 없으면 지금처럼 {@link
 * InternalSecurityConfig}의 공용 체인이 받는다. 그래서 한 체인에 인증 수단 둘이 섞이지 않는다.
 *
 * <p>전용 토큰이 비면 창구를 닫는다. 체인은 그대로 두고 {@link DenyAllFilter}만 단다 — 출발지 필터도 달지
 * 않아, 허용 대역까지 비어 있어도 닫힘은 늘 401 이다. 부팅 검사도 토큰이 있을 때만 한다.
 *
 * <p>운영 ECS 는 전달 헤더 전략을 {@code native}로 띄운다. 창구는 그 위에서도 열린다(POK-316): 전달 머리글을 달고 온
 * 요청은 {@link ForwardedHeaderMarkValve}가 톰캣 {@code RemoteIpValve}보다 먼저 표시하고 출발지 필터가 403 으로 끝낸다.
 * Media 는 auth 에 직접 붙어 그 머리글을 안 싣는다.
 *
 * <p>웹 앱일 때만 있다. 웹이 없는 컨텍스트에는 {@code HttpSecurity} 빈이 없어 이 설정이 있으면 못 뜬다. 공용
 * 체인 · 기본 체인과 같은 조건이다(POK-272 이행 실행기 때 달았다).
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class StreamKeyResolveSecurityConfig {

  /** Media 와 맞춘 헤더 이름(계약4 4C). 공용 헤더와 이름을 갈라 한쪽 토큰을 다른 쪽 헤더에 실어도 안 통한다. */
  static final String HEADER = "X-Stream-Key-Resolve-Token";

  private static final RequestMatcher RESOLVE_WITH_HEADER =
      new AndRequestMatcher(
          PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/internal/stream-keys/resolve"),
          new RequestHeaderRequestMatcher(HEADER));

  @Bean
  @Order(0) // 공용 체인 internalFilterChain(@Order(1))보다 먼저 이 요청을 가져간다
  SecurityFilterChain streamKeyResolveFilterChain(
      HttpSecurity http,
      StreamKeyResolveAccessProperties access,
      InternalApiProperties internalApi,
      ServerProperties server,
      TomcatServerProperties tomcat)
      throws Exception {
    http.securityMatcher(RESOLVE_WITH_HEADER)
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
        "pokeclip.internal-api.stream-key-resolve.",
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
        .addFilterAfter(new InternalTokenFilter(access.token(), HEADER), SourceNetworkFilter.class)
        .build();
  }

  /** 표시 밸브를 엔진 밸브 맨 앞에 넣는다. 순서 근거는 {@link DedicatedWindow#markValveFirst()}. */
  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> forwardedHeaderMarkValveFirst() {
    return DedicatedWindow.markValveFirst();
  }
}
