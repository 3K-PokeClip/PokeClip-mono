package com.pokeclip.web.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.catalina.Valve;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;

/**
 * 출발지 잠금 전용 창구를 여는 서버가 같이 쓰는 두 가지: 열어도 되는지 보는 부팅 검사와 표시 밸브 배선. auth
 * resolve 전용 체인({@code StreamKeyResolveSecurityConfig})과 clip 만료 방송 전용 체인
 * ({@code ExpiredBroadcastSecurityConfig})이 부른다. 서버마다 두 벌로 두면 한쪽만 고쳐져 다른 서버 창구가 표시
 * 밸브가 안 보는 헤더를 믿는 설정으로 뜰 수 있다.
 *
 * <p>부팅 검사는 Boot 설정 타입을 받지 않고 값만 받는다. 부르는 쪽이 자기 속성에서 꺼내 넘긴다.
 */
public final class DedicatedWindow {

  private DedicatedWindow() {}

  /**
   * 전용 창구를 열어도 되는지 본다. 어긋나면 {@link IllegalStateException}으로 부팅을 세운다. 값은 메시지에 싣지
   * 않는다 — 부팅 실패 메시지는 로그와 CI 출력에 남는다. IPv4 가 아닌 대역은 {@link SourceNetworkFilter}가 거부한다.
   *
   * @param propertyPrefix 그 서버의 전용 창구 속성 접두사(끝 점 포함, 예
   *     {@code pokeclip.internal-api.expired-broadcasts.}). 실패 메시지가 이 이름으로 어느 속성인지 알린다
   * @param allowedCidrs 허용 출발지 대역
   * @param dedicatedToken 전용 토큰(비지 않은 값 — 빈 토큰이면 부르는 쪽이 창구를 닫는다)
   * @param internalToken 공용 토큰({@code pokeclip.internal-api.token})
   * @param forwardHeadersStrategy {@code server.forward-headers-strategy}의 이름. 미설정이면 null
   * @param remoteIpHeader {@code server.tomcat.remoteip.remote-ip-header}
   * @param protocolHeader {@code server.tomcat.remoteip.protocol-header}
   */
  public static void requireSafeToOpen(
      String propertyPrefix,
      List<String> allowedCidrs,
      String dedicatedToken,
      String internalToken,
      String forwardHeadersStrategy,
      String remoteIpHeader,
      String protocolHeader) {
    if (allowedCidrs.isEmpty()) {
      throw new IllegalStateException(
          propertyPrefix
              + "allowed-cidrs 가 비었다: 전용 토큰이 있으면 허용 출발지(IPv4 CIDR)가 있어야 한다");
    }
    if (dedicatedToken.equals(internalToken)) {
      throw new IllegalStateException(
          propertyPrefix
              + "token 이 pokeclip.internal-api.token 과 같다: 전용 토큰은 공용 토큰과 달라야 한다");
    }
    // 아래 두 검사는 Boot 4.1.0 TomcatWebServerFactoryCustomizer.customizeRemoteIpValve 의 밸브 조건에서
    // 왔다. Boot 판을 올리면 그 조건과 다시 대조한다(두 서버가 이 한 곳을 쓴다).
    // native 는 톰캣 RemoteIpValve 가 기본 이름의 머리글만 읽어, 표시 밸브가 그 머리글을 먼저 본다. framework 는
    // Spring 필터가 X-Forwarded-Prefix 같은 다른 이름까지 읽는다. 빈 값은 none 이 아니라 미설정(null)이다. 미설정이면
    // Boot 가 클라우드 플랫폼을 감지해 전략을 고른다.
    String strategy =
        forwardHeadersStrategy == null || forwardHeadersStrategy.isBlank()
            ? null
            : forwardHeadersStrategy.toUpperCase(Locale.ROOT);
    if (!"NONE".equals(strategy) && !"NATIVE".equals(strategy)) {
      throw new IllegalStateException(
          "server.forward-headers-strategy 가 none 이나 native 가 아니다(지금 "
              + (strategy == null ? "미설정" : strategy)
              + "): 표시 밸브가 안 보는 전달 헤더로 출발지 잠금이 뚫릴 수 있다");
    }
    // 이 둘에 이름을 적으면 RemoteIpValve 가 그 이름의 머리글을 읽는데, 표시 밸브는 기본 이름만 본다. 같은 이름을
    // 적어도 거부한다: 이름이 같은지 가려 줄 이유가 없다(native 기본은 둘 다 비어 있다).
    if (hasText(remoteIpHeader) || hasText(protocolHeader)) {
      throw new IllegalStateException(
          "server.tomcat.remoteip.remote-ip-header 나 protocol-header 에 값이 있다: 톰캣이 표시 밸브가"
              + " 안 보는 이름의 전달 헤더를 믿어 출발지 잠금이 뚫릴 수 있다");
    }
  }

  /**
   * 표시 밸브를 엔진 밸브 맨 앞에 넣는 커스터마이저. Boot 는 {@code RemoteIpValve}를 뒤에 덧붙이므로({@code
   * TomcatWebServerFactoryCustomizer}의 {@code addEngineValves}) 이 커스터마이저가 먼저 돌든 나중에 돌든 순서가 같다.
   * 창구가 닫혀도 단다: 속성 하나를 적을 뿐이고, 표시를 읽는 것은 열린 창구의 출발지 필터뿐이다.
   */
  public static WebServerFactoryCustomizer<TomcatServletWebServerFactory> markValveFirst() {
    return factory -> {
      List<Valve> valves = new ArrayList<>(factory.getEngineValves());
      valves.addFirst(new ForwardedHeaderMarkValve());
      factory.setEngineValves(valves);
    };
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
