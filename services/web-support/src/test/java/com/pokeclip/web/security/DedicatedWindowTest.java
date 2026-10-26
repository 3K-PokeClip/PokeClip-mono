package com.pokeclip.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.apache.catalina.Valve;
import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;

/**
 * 출발지 잠금 창구를 열 때의 부팅 검사와 표시 밸브 배선을 잰다. auth · clip 의 부팅 검사 시험은 이 함수를 서버 설정으로
 * 부른 결과를 재고, 여기서는 갈래마다 함수 자체를 잰다. 실패 메시지에 토큰과 대역 값이 실리면 안 된다 — 부팅 실패 메시지는
 * 로그와 CI 출력에 남는다.
 */
class DedicatedWindowTest {

  private static final String PREFIX = "pokeclip.internal-api.example.";
  private static final String DEDICATED = "dedicated-token-0123456789";
  private static final String INTERNAL = "internal-token-0123456789";
  private static final List<String> CIDRS = List.of("198.51.100.7/32");

  @Test
  void everyConditionHolds_passes() {
    for (String strategy : List.of("NONE", "NATIVE", "none", "native")) {
      assertThatCode(() -> check(CIDRS, DEDICATED, strategy, null, null))
          .as("전략 %s", strategy)
          .doesNotThrowAnyException();
    }
    assertThatCode(() -> check(CIDRS, DEDICATED, "NONE", "", " ")).doesNotThrowAnyException();
  }

  @Test
  void emptyCidrs_fails() {
    assertRejected(() -> check(List.of(), DEDICATED, "NONE", null, null), PREFIX + "allowed-cidrs");
  }

  @Test
  void dedicatedTokenSameAsInternal_fails() {
    assertRejected(
        () -> check(CIDRS, INTERNAL, "NONE", null, null), "pokeclip.internal-api.token");
  }

  /** 미설정(null)은 none 이 아니다 — Boot 가 플랫폼을 감지해 고른다. framework 는 표시 밸브가 안 보는 헤더까지 읽는다. */
  @Test
  void strategyOtherThanNoneOrNative_fails() {
    for (String strategy : new String[] {null, "", "FRAMEWORK"}) {
      assertRejected(
          () -> check(CIDRS, DEDICATED, strategy, null, null), "server.forward-headers-strategy");
    }
  }

  @Test
  void eitherRemoteIpHeaderSet_fails() {
    assertRejected(
        () -> check(CIDRS, DEDICATED, "NONE", "X-Forwarded-For", null), "server.tomcat.remoteip");
    assertRejected(
        () -> check(CIDRS, DEDICATED, "NONE", null, "X-Forwarded-Proto"), "server.tomcat.remoteip");
  }

  /** Boot 는 RemoteIpValve 를 뒤에 덧붙인다. 커스터마이저가 언제 돌든 표시 밸브가 맨 앞이어야 한다. */
  @Test
  void markValveFirst_putsMarkValveAheadOfExistingValves() {
    TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
    factory.addEngineValves(new RemoteIpValve());

    DedicatedWindow.markValveFirst().customize(factory);

    List<Valve> valves = List.copyOf(factory.getEngineValves());
    assertThat(valves).hasSize(2);
    assertThat(valves.get(0)).isInstanceOf(ForwardedHeaderMarkValve.class);
    assertThat(valves.get(1)).isInstanceOf(RemoteIpValve.class);
  }

  private static void check(
      List<String> cidrs, String dedicated, String strategy, String remoteIp, String protocol) {
    DedicatedWindow.requireSafeToOpen(
        PREFIX, cidrs, dedicated, INTERNAL, strategy, remoteIp, protocol);
  }

  private static void assertRejected(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String reason) {
    assertThatThrownBy(call)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(reason)
        .hasMessageNotContaining(DEDICATED)
        .hasMessageNotContaining(INTERNAL)
        .hasMessageNotContaining("198.51.100.7");
  }
}
