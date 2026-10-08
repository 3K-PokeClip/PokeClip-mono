package com.pokeclip.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.Filter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 착수 체크리스트 C1 — resolve 전용 출입증의 부팅 검사(1번 T 설계 2-7). 검사는 전용 토큰이 있을 때만 돈다. 운영
 * ECS 는 {@code native} 로 뜨는데, 이 창구를 쓰지 않는 그 auth 가 검사에 걸려 죽으면 안 된다.
 *
 * <p>실패 메시지는 여기서 직접 읽는다. {@code SecretLeakTest} 는 뜬 컨텍스트의 요청 로그만 봐서 부팅 실패
 * 메시지를 못 잡는다. 컨텍스트 캐시를 쓰지 않는다(ContextRunner) — 갈래마다 시험 컨텍스트를 띄우면 커넥션
 * 예산이 넘친다({@code IntegrationTestSupport}).
 */
class StreamKeyResolveBootCheckTest {

  private static final String RESOLVE_TOKEN = "LEAK-resolve-token-" + UUID.randomUUID();
  private static final String INTERNAL_TOKEN = "LEAK-internal-token-" + UUID.randomUUID();
  private static final String ALLOWED_ADDRESS = "198.51.100.7";
  private static final String IPV6_ADDRESS = "2001:db8::";

  private static final String PREFIX = "pokeclip.internal-api.stream-key-resolve.";
  private static final String INTERNAL_TOKEN_SET = "pokeclip.internal-api.token=" + INTERNAL_TOKEN;
  private static final String RESOLVE_TOKEN_SET = PREFIX + "token=" + RESOLVE_TOKEN;
  private static final String RESOLVE_TOKEN_EMPTY = PREFIX + "token=";
  private static final String ALLOWED_CIDR_SET =
      PREFIX + "allowed-cidrs=" + ALLOWED_ADDRESS + "/32";
  private static final String STRATEGY = "server.forward-headers-strategy=";
  private static final String STRATEGY_NONE = STRATEGY + "none";
  private static final String STRATEGY_NATIVE = STRATEGY + "native";
  private static final String REMOTE_IP_HEADER_SET =
      "server.tomcat.remoteip.remote-ip-header=X-Forwarded-For";
  private static final String PROTOCOL_HEADER_SET =
      "server.tomcat.remoteip.protocol-header=X-Forwarded-Proto";

  /** 대조 — 다 갖추면 뜬다. 아래 실패 갈래들이 엉뚱한 이유로 실패하지 않는다는 바탕이다. */
  @Test
  void withToken_bootsWhenEveryConditionHolds() {
    web(INTERNAL_TOKEN_SET, RESOLVE_TOKEN_SET, ALLOWED_CIDR_SET, STRATEGY_NONE)
        .run(
            context ->
                assertThat(context).hasNotFailed().hasBean("streamKeyResolveFilterChain"));
  }

  /** 운영 ECS 꼴이다. 허용 대역도 비어 있지만 닫힌 창구는 403 이 아니라 401 이다. */
  @Test
  void withoutToken_bootsUnderNativeAndClosesWith401() {
    web(INTERNAL_TOKEN_SET, RESOLVE_TOKEN_EMPTY, STRATEGY_NATIVE)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(dedicatedHeaderStatus(context)).isEqualTo(401);
            });
  }

  @Test
  void withoutToken_bootsWithRemoteIpHeaders() {
    web(
            INTERNAL_TOKEN_SET,
            RESOLVE_TOKEN_EMPTY,
            STRATEGY_NONE,
            REMOTE_IP_HEADER_SET,
            PROTOCOL_HEADER_SET)
        .run(context -> assertThat(context).hasNotFailed());
  }

  /** 정확히 none 이어야 한다. 빈 값은 none 이 아니라 미설정으로 읽혀 플랫폼 감지로 간다. */
  @Test
  void withToken_failsUnlessStrategyIsExactlyNone() {
    String empty = STRATEGY;
    for (String strategy : List.of(STRATEGY_NATIVE, STRATEGY + "framework", empty)) {
      web(INTERNAL_TOKEN_SET, RESOLVE_TOKEN_SET, ALLOWED_CIDR_SET, strategy)
          .run(context -> assertFailedWithoutValues(context, "server.forward-headers-strategy"));
    }
    // 아예 적지 않은 경우
    web(INTERNAL_TOKEN_SET, RESOLVE_TOKEN_SET, ALLOWED_CIDR_SET)
        .run(context -> assertFailedWithoutValues(context, "server.forward-headers-strategy"));
  }

  /** strategy 가 none 이어도 둘 가운데 하나만 있으면 톰캣이 전달 헤더를 믿는 밸브를 단다. */
  @Test
  void withToken_failsWhenEitherRemoteIpHeaderIsSet() {
    for (String header : List.of(REMOTE_IP_HEADER_SET, PROTOCOL_HEADER_SET)) {
      web(INTERNAL_TOKEN_SET, RESOLVE_TOKEN_SET, ALLOWED_CIDR_SET, STRATEGY_NONE, header)
          .run(context -> assertFailedWithoutValues(context, "server.tomcat.remoteip"));
    }
  }

  @Test
  void withToken_failsWhenAnyCidrIsNotIpv4() {
    String mixed = PREFIX + "allowed-cidrs=" + ALLOWED_ADDRESS + "/32," + IPV6_ADDRESS + "/32";
    // IPv4 꼴 오타(접두 33)도 꼴 검사가 막아야 한다. 매처에 닿으면 매처가 값을 실패 메시지에 싣는다.
    String malformedIpv4 = PREFIX + "allowed-cidrs=" + ALLOWED_ADDRESS + "/33";

    web(INTERNAL_TOKEN_SET, RESOLVE_TOKEN_SET, mixed, STRATEGY_NONE)
        .run(context -> assertFailedWithoutValues(context, "allowed-cidrs"));
    web(INTERNAL_TOKEN_SET, RESOLVE_TOKEN_SET, malformedIpv4, STRATEGY_NONE)
        .run(context -> assertFailedWithoutValues(context, "allowed-cidrs"));
  }

  @Test
  void withToken_failsWithoutAllowedCidrs() {
    // 아예 적지 않은 경우와 빈 값
    web(INTERNAL_TOKEN_SET, RESOLVE_TOKEN_SET, STRATEGY_NONE)
        .run(context -> assertFailedWithoutValues(context, "allowed-cidrs"));
    web(INTERNAL_TOKEN_SET, RESOLVE_TOKEN_SET, PREFIX + "allowed-cidrs=", STRATEGY_NONE)
        .run(context -> assertFailedWithoutValues(context, "allowed-cidrs"));
  }

  @Test
  void withToken_failsWhenSameAsInternalToken() {
    web(INTERNAL_TOKEN_SET, PREFIX + "token=" + INTERNAL_TOKEN, ALLOWED_CIDR_SET, STRATEGY_NONE)
        .run(context -> assertFailedWithoutValues(context, "pokeclip.internal-api.token"));
  }

  /**
   * 이행 실행기(web none) 꼴이다. 속성 레코드는 모든 프로필에서 만들어지므로 검사가 레코드에 있으면 여기서
   * 죽는다(1번 T 설계 2-7). 웹 전용 설정 안에 있어야 체인과 함께 빠진다.
   */
  @Test
  void nonWebApplication_bindsPropertiesWithoutChainOrCheck() {
    new ApplicationContextRunner()
        .withUserConfiguration(BoundProperties.class, StreamKeyResolveSecurityConfig.class)
        .withPropertyValues(
            INTERNAL_TOKEN_SET,
            RESOLVE_TOKEN_SET,
            PREFIX + "allowed-cidrs=" + IPV6_ADDRESS + "/32",
            STRATEGY_NATIVE)
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(StreamKeyResolveAccessProperties.class)
                    .doesNotHaveBean(SecurityFilterChain.class));
  }

  private static WebApplicationContextRunner web(String... properties) {
    return new WebApplicationContextRunner()
        .withUserConfiguration(
            BoundProperties.class, WebSecurity.class, StreamKeyResolveSecurityConfig.class)
        .withPropertyValues(properties);
  }

  /** 부팅이 그 이유로 실패했고, 실패 보고 어디에도 토큰과 대역 값이 없다. */
  private static void assertFailedWithoutValues(
      AssertableWebApplicationContext context, String reason) {
    assertThat(context).hasFailed();
    String report = stackTraceOf(context.getStartupFailure());
    assertThat(report).as("다른 이유로 부팅이 실패했다").contains(reason);
    assertThat(report)
        .as("실패 보고에 토큰이나 대역 값이 남았다")
        .doesNotContain(RESOLVE_TOKEN)
        .doesNotContain(INTERNAL_TOKEN)
        .doesNotContain(ALLOWED_ADDRESS)
        .doesNotContain(IPV6_ADDRESS);
  }

  private static String stackTraceOf(Throwable failure) {
    StringWriter out = new StringWriter();
    failure.printStackTrace(new PrintWriter(out));
    return out.toString();
  }

  /** 전용 헤더를 빈 값으로 실은 resolve 요청이 보안 체인에서 받는 상태. 체인을 다 지나면 200 이다. */
  private static int dedicatedHeaderStatus(ApplicationContext context) throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/internal/stream-keys/resolve");
    request.addHeader("X-Stream-Key-Resolve-Token", "");
    MockHttpServletResponse response = new MockHttpServletResponse();
    context
        .getBean("springSecurityFilterChain", Filter.class)
        .doFilter(request, response, new MockFilterChain());
    return response.getStatus();
  }

  /** 앱은 {@code @ConfigurationPropertiesScan} 과 Boot 자동 설정이 등록하는 속성들이다. */
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({
    StreamKeyResolveAccessProperties.class,
    InternalApiProperties.class,
    ServerProperties.class,
    TomcatServerProperties.class
  })
  static class BoundProperties {}

  /** 앱은 Boot 의 보안 자동 설정이 켠다. {@code HttpSecurity} 빈이 여기서 나온다. */
  @Configuration(proxyBeanMethods = false)
  @EnableWebSecurity
  static class WebSecurity {}
}
