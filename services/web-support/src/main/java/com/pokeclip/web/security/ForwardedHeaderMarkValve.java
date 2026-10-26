package com.pokeclip.web.security;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

/**
 * 요청이 도착한 그대로 전달 머리글을 달고 왔는지 요청 속성에 적는다(POK-316). 엔진 밸브 맨 앞에 단다: 출발지 잠금 창구를
 * 여는 서버의 보안 설정이 배선한다(auth {@code StreamKeyResolveSecurityConfig} · clip {@code
 * ExpiredBroadcastSecurityConfig}).
 *
 * <p>머리글을 필터에서 직접 보면 안 되는 이유: {@code native}에서 톰캣 {@code RemoteIpValve}는 {@code X-Forwarded-For}를
 * 다 소비하면 그 머리글을 지운다(톰캣 11.0.22 {@code RemoteIpValve} 612~617줄, 요청이 끝난 뒤에야 되살린다). 그래서 믿는
 * 프록시 대역 안의 호출자가 허용 주소를 적어 보내면, 필터에는 「머리글 없는 허용 주소 요청」으로 보인다. 이 밸브는 그보다
 * 먼저 돌아 지워지기 전의 머리글을 본다.
 *
 * <p>머리글은 Boot 4.1 이 {@code native}에서 그 밸브에 주는 기본 이름 넷과, 그 밸브는 안 읽지만 다른 전략(framework)이 읽는
 * {@code Forwarded}다. 주소·프로토콜 머리글 이름을 바꾸는 설정({@code server.tomcat.remoteip.remote-ip-header} ·
 * {@code protocol-header})은 전용 창구를 열 때 부팅 검사가 막는다. host·port 이름은 바꿔도 출발지 판정에 안 닿는다.
 * 표시만 하고 거절은 하지 않는다: 판정 순서(출발지 403, 토큰 401)와 닫힌 창구의 401 은 보안 체인이 정한다.
 */
public final class ForwardedHeaderMarkValve extends ValveBase {

  /** 값은 {@link Boolean#TRUE} 하나다. 표시가 없으면 속성이 없다. */
  public static final String ATTRIBUTE = ForwardedHeaderMarkValve.class.getName() + ".present";

  private static final List<String> HEADERS =
      List.of(
          "Forwarded",
          "X-Forwarded-For",
          "X-Forwarded-Proto",
          "X-Forwarded-Host",
          "X-Forwarded-Port");

  public ForwardedHeaderMarkValve() {
    // 비동기 요청도 지나간다(RemoteIpValve 와 같다). false 면 그 요청에서 비동기 처리가 꺼진다.
    super(true);
  }

  @Override
  public void invoke(Request request, Response response) throws IOException, ServletException {
    for (String name : HEADERS) {
      if (request.getHeader(name) != null) {
        request.setAttribute(ATTRIBUTE, Boolean.TRUE);
        break;
      }
    }
    getNext().invoke(request, response);
  }
}
