package com.pokeclip.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.junit.jupiter.api.Test;

/**
 * 표시 밸브가 도착한 그대로의 머리글을 보고 요청 속성에 적는지 잰다. 실물 Request 에 머리글을 심어 밸브를 직접 돌린다
 * (auth {@code RemoteIpValveTrustTest}와 같은 골격). 밸브가 맨 앞에 배선되는지는 auth 실서버 시험 {@code
 * ProxyTrustNativeTest}가 잰다.
 *
 * <p>머리글 이름은 제품 상수를 쓰지 않고 글자로 적는다. 목록에서 하나가 빠지면 여기서 잡혀야 한다.
 */
class ForwardedHeaderMarkValveTest {

  @Test
  void 전달_머리글이_하나라도_있으면_표시한다() {
    for (String header :
        List.of(
            "Forwarded",
            "X-Forwarded-For",
            "X-Forwarded-Proto",
            "X-Forwarded-Host",
            "X-Forwarded-Port")) {
      assertThat(markSeenBy(header))
          .as("%s가 붙은 요청을 표시하지 않았다", header)
          .isEqualTo(Boolean.TRUE);
    }
  }

  /** 머리글 이름은 대소문자를 가리지 않는다(HTTP). 소문자로 와도 톰캣 밸브는 읽는다. */
  @Test
  void 소문자_머리글도_표시한다() {
    assertThat(markSeenBy("x-forwarded-for")).isEqualTo(Boolean.TRUE);
  }

  @Test
  void 전달_머리글이_없으면_표시하지_않는다() {
    assertThat(markSeenBy("X-Stream-Key-Resolve-Token")).isNull();
  }

  private static Object markSeenBy(String header) {
    ForwardedHeaderMarkValve valve = new ForwardedHeaderMarkValve();
    AtomicReference<Object> seen = new AtomicReference<>();
    valve.setNext(
        new ValveBase() {
          @Override
          public void invoke(Request request, Response response) {
            seen.set(request.getAttribute(ForwardedHeaderMarkValve.ATTRIBUTE));
          }
        });

    org.apache.coyote.Request coyote = new org.apache.coyote.Request();
    coyote.getMimeHeaders().addValue(header).setString("127.0.0.1");
    Request request = new Request(new Connector(), coyote);
    Response response = new Response(new org.apache.coyote.Response());

    try {
      valve.invoke(request, response);
    } catch (Exception e) {
      throw new IllegalStateException("Valve 호출 실패", e);
    }
    return seen.get();
  }
}
