package com.pokeclip.auth.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties;
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
import org.springframework.util.StringUtils;

/**
 * resolve 전용 출입증 체인(1번 T 설계 2-7). Media 가 공용 {@code X-Internal-Token} 대신 전용 헤더로 스트림키
 * resolve 를 부르는 길이다. 판정은 출발지(403) 다음 토큰(401)이다.
 *
 * <p>resolve 요청 가운데 전용 헤더가 있는 것만 이 체인이 가져간다. 헤더가 없으면 지금처럼 {@link
 * InternalSecurityConfig}의 공용 체인이 받는다. 그래서 한 체인에 인증 수단 둘이 섞이지 않는다.
 *
 * <p>전용 토큰이 비면 창구를 닫는다. 체인은 그대로 두고 {@link DenyAllFilter}만 단다 — 출발지 필터도 달지
 * 않아, 허용 대역까지 비어 있어도 닫힘은 늘 401 이다. 부팅 검사도 토큰이 있을 때만 한다. 운영 ECS 는 전달 헤더
 * 전략을 {@code native}로 띄우는데, 이 창구를 쓰지 않는 그 auth 가 검사에 걸려 죽으면 안 된다.
 *
 * <p>웹 앱일 때만 있다. 이행 실행기(web-application-type: none)는 {@code HttpSecurity} 빈이 없어 이 설정이
 * 있으면 못 뜬다. 공용 체인 · 기본 체인과 같은 조건이다.
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
    requireSafeToOpen(access, internalApi, server, tomcat.getRemoteip());
    return http
        .addFilterBefore(
            new SourceNetworkFilter(access.allowedCidrs()),
            UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(new InternalTokenFilter(access.token(), HEADER), SourceNetworkFilter.class)
        .build();
  }

  /**
   * 창구를 열어도 되는지 본다. 어긋나면 부팅을 세운다. 값은 메시지에 싣지 않는다 — 부팅 실패 메시지는 로그와
   * CI 출력에 남는다. IPv4 가 아닌 대역은 {@link SourceNetworkFilter}가 거부한다.
   */
  private static void requireSafeToOpen(
      StreamKeyResolveAccessProperties access,
      InternalApiProperties internalApi,
      ServerProperties server,
      TomcatServerProperties.Remoteip remoteIp) {
    if (access.allowedCidrs().isEmpty()) {
      throw new IllegalStateException(
          "pokeclip.internal-api.stream-key-resolve.allowed-cidrs 가 비었다: 전용 토큰이 있으면 허용"
              + " 출발지(IPv4 CIDR)가 있어야 한다");
    }
    if (access.token().equals(internalApi.token())) {
      throw new IllegalStateException(
          "pokeclip.internal-api.stream-key-resolve.token 이 pokeclip.internal-api.token 과 같다:"
              + " 전용 토큰은 공용 토큰과 달라야 한다");
    }
    // 빈 값은 none 이 아니라 미설정(null)이다. 미설정이면 Boot 가 클라우드 플랫폼을 감지해 native 를 켤 수 있다.
    ForwardHeadersStrategy strategy = server.getForwardHeadersStrategy();
    if (strategy != ForwardHeadersStrategy.NONE) {
      throw new IllegalStateException(
          "server.forward-headers-strategy 가 정확히 none 이 아니다(지금 "
              + (strategy == null ? "미설정" : strategy)
              + "): 전달 헤더를 믿으면 출발지 잠금이 헤더 한 줄로 뚫린다");
    }
    // strategy 가 none 이어도 둘 가운데 하나만 있으면 톰캣이 RemoteIpValve 를 단다(Boot 4.1
    // TomcatWebServerFactoryCustomizer). 그 밸브가 믿는 기본 대역 172.16.0.0/12 에 Docker 브리지
    // 게이트웨이가 든다.
    if (StringUtils.hasText(remoteIp.getRemoteIpHeader())
        || StringUtils.hasText(remoteIp.getProtocolHeader())) {
      throw new IllegalStateException(
          "server.tomcat.remoteip.remote-ip-header 나 protocol-header 에 값이 있다: 톰캣이 전달 헤더를"
              + " 믿는 밸브를 달아 출발지 잠금이 뚫린다");
    }
  }
}
