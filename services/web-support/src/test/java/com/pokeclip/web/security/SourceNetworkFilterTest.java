package com.pokeclip.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 출발지 필터를 요청 하나씩 잰다. 출발지는 MockHttpServletRequest 의 원격 주소로 준다 — 진짜 톰캣에서 소켓 주소가
 * 놓이는 자리다. 체인 배선(판정 순서 · 닫힘 401)은 이 부품을 쓰는 서버의 체인 시험이 잰다.
 */
class SourceNetworkFilterTest {

  /** 허용 대역은 이 주소 하나(/32)다. */
  private static final String ALLOWED_SOURCE = "198.51.100.7";

  /** 허용 주소 바로 옆이다. /32 가 그 주소 하나만 여는지 본다. */
  private static final String NEIGHBOR_SOURCE = "198.51.100.8";

  /**
   * 앞 네 바이트(c6 33 64 07)가 {@link #ALLOWED_SOURCE}와 같은 IPv6 주소다. Spring Security 7.1 의 대역 매처는 주소
   * 계열을 보지 않아 이 주소를 허용 대역 안으로 판정한다(spring-projects/spring-security#19733).
   */
  private static final String IPV6_WITH_ALLOWED_LEADING_BYTES = "c633:6407::";

  private final SourceNetworkFilter filter =
      new SourceNetworkFilter(List.of(ALLOWED_SOURCE + "/32"));

  @Test
  void allowedSource_passesToChain() throws Exception {
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletResponse response = run(from(ALLOWED_SOURCE), chain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void sourceOutsideAllowedCidrs_isForbiddenWithoutBody() throws Exception {
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletResponse response = run(from(NEIGHBOR_SOURCE), chain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).isEmpty();
    assertThat(chain.getRequest()).as("막힌 요청이 체인 뒤로 갔다").isNull();
  }

  /** 도착 때 전달 머리글이 있었다고 표시된 요청은 허용 주소에서 와도 403 이다(POK-316). */
  @Test
  void markedForwardedRequestFromAllowedSource_isForbidden() throws Exception {
    MockHttpServletRequest request = from(ALLOWED_SOURCE);
    request.setAttribute(ForwardedHeaderMarkValve.ATTRIBUTE, Boolean.TRUE);
    MockFilterChain chain = new MockFilterChain();

    assertThat(run(request, chain).getStatus()).isEqualTo(403);
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void ipv6SourceWithAllowedLeadingBytes_isForbidden() throws Exception {
    assertThat(run(from(IPV6_WITH_ALLOWED_LEADING_BYTES), new MockFilterChain()).getStatus())
        .isEqualTo(403);
  }

  /** 대역 꼴은 생성자가 본다. 실패 메시지에 값을 싣지 않는다 — 부팅 실패 메시지는 로그와 CI 출력에 남는다. */
  @Test
  void nonIpv4OrMalformedCidr_isRejectedWithoutValue() {
    for (String cidr : List.of("2001:db8::/32", ALLOWED_SOURCE + "/33", "010.0.0.0/8")) {
      assertThatThrownBy(() -> new SourceNetworkFilter(List.of(cidr)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("allowed-cidrs")
          .hasMessageNotContaining(cidr);
    }
  }

  private MockHttpServletResponse run(MockHttpServletRequest request, MockFilterChain chain)
      throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  private static MockHttpServletRequest from(String remoteAddress) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/x");
    request.setRemoteAddr(remoteAddress);
    return request;
  }
}
