package com.pokeclip.clip.config;

import com.pokeclip.web.security.DenyAllFilter;
import com.pokeclip.web.security.ForwardedHeaderMarkValve;
import com.pokeclip.web.security.SourceNetworkFilter;
import java.util.ArrayList;
import java.util.List;
import org.apache.catalina.Valve;
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
import org.springframework.util.StringUtils;

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
    requireSafeToOpen(access, internalApi, server, tomcat.getRemoteip());
    return http
        .addFilterBefore(
            new SourceNetworkFilter(access.allowedCidrs()),
            UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(
            new com.pokeclip.web.security.InternalTokenFilter(access.token(), HEADER),
            SourceNetworkFilter.class)
        .build();
  }

  /**
   * 표시 밸브를 엔진 밸브 맨 앞에 넣는다. Boot 는 {@code RemoteIpValve}를 뒤에 덧붙이므로({@code
   * TomcatWebServerFactoryCustomizer}의 {@code addEngineValves}) 이 커스터마이저가 먼저 돌든 나중에 돌든 순서가 같다.
   * 창구가 닫혀도 단다: 속성 하나를 적을 뿐이고, 표시를 읽는 것은 열린 창구의 출발지 필터뿐이다.
   */
  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> forwardedHeaderMarkValveFirst() {
    return factory -> {
      List<Valve> valves = new ArrayList<>(factory.getEngineValves());
      valves.addFirst(new ForwardedHeaderMarkValve());
      factory.setEngineValves(valves);
    };
  }

  /**
   * 창구를 열어도 되는지 본다. 어긋나면 부팅을 세운다. 값은 메시지에 싣지 않는다 — 부팅 실패 메시지는 로그와 CI 출력에
   * 남는다. IPv4 가 아닌 대역은 {@link SourceNetworkFilter}가 거부한다. 검사 넷은 auth resolve 전용 체인과 같다.
   */
  private static void requireSafeToOpen(
      ExpiredBroadcastAccessProperties access,
      InternalApiProperties internalApi,
      ServerProperties server,
      TomcatServerProperties.Remoteip remoteIp) {
    if (access.allowedCidrs().isEmpty()) {
      throw new IllegalStateException(
          "pokeclip.internal-api.expired-broadcasts.allowed-cidrs 가 비었다: 전용 토큰이 있으면 허용"
              + " 출발지(IPv4 CIDR)가 있어야 한다");
    }
    if (access.token().equals(internalApi.token())) {
      throw new IllegalStateException(
          "pokeclip.internal-api.expired-broadcasts.token 이 pokeclip.internal-api.token 과 같다:"
              + " 전용 토큰은 공용 토큰과 달라야 한다");
    }
    // 아래 두 검사는 Boot 4.1.0 TomcatWebServerFactoryCustomizer.customizeRemoteIpValve 의 밸브 조건에서
    // 왔다(auth StreamKeyResolveSecurityConfig 와 같다). Boot 판을 올리면 그 조건과 다시 대조한다.
    // native 는 톰캣 RemoteIpValve 가 기본 이름의 머리글만 읽어, 표시 밸브가 그 머리글을 먼저 본다. framework 는
    // Spring 필터가 X-Forwarded-Prefix 같은 다른 이름까지 읽는다. 빈 값은 none 이 아니라 미설정(null)이다. 미설정이면
    // Boot 가 클라우드 플랫폼을 감지해 전략을 고른다. clip 은 전략을 env(FORWARD_HEADERS_STRATEGY)로 받고 비면 플랫폼
    // 감지이므로, 창구를 여는 환경은 그 env 를 같이 넣어야 뜬다.
    ForwardHeadersStrategy strategy = server.getForwardHeadersStrategy();
    if (strategy != ForwardHeadersStrategy.NONE && strategy != ForwardHeadersStrategy.NATIVE) {
      throw new IllegalStateException(
          "server.forward-headers-strategy 가 none 이나 native 가 아니다(지금 "
              + (strategy == null ? "미설정" : strategy)
              + "): 표시 밸브가 안 보는 전달 헤더로 출발지 잠금이 뚫릴 수 있다");
    }
    // 이 둘에 이름을 적으면 RemoteIpValve 가 그 이름의 머리글을 읽는데, 표시 밸브는 기본 이름만 본다.
    if (StringUtils.hasText(remoteIp.getRemoteIpHeader())
        || StringUtils.hasText(remoteIp.getProtocolHeader())) {
      throw new IllegalStateException(
          "server.tomcat.remoteip.remote-ip-header 나 protocol-header 에 값이 있다: 톰캣이 표시 밸브가"
              + " 안 보는 이름의 전달 헤더를 믿어 출발지 잠금이 뚫릴 수 있다");
    }
  }
}
