package com.pokeclip.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 닫힌 창구의 필터는 무엇을 실어 와도 401 이고 체인 뒤로 보내지 않는다. */
class DenyAllFilterTest {

  @Test
  void everyRequest_isUnauthorizedWithoutBody() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/x");
    request.addHeader("X-Internal-Token", "anything");
    request.setRemoteAddr("127.0.0.1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    new DenyAllFilter().doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).isEmpty();
    assertThat(chain.getRequest()).isNull();
  }
}
