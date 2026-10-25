package com.pokeclip.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 토큰 필터를 요청 하나씩 잰다. 헤더 이름은 제품 상수를 쓰지 않고 글자로 적는다 — 서버끼리 맞춘 이름이라 틀어지면 여기서
 * 잡혀야 한다.
 */
class InternalTokenFilterTest {

  private static final String TOKEN = "test-only-token-0123456789";
  private static final String DEDICATED_HEADER = "X-Dedicated-Token";

  @Test
  void matchingTokenOnGivenHeader_passesToChain() throws Exception {
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletResponse response =
        run(new InternalTokenFilter(TOKEN, DEDICATED_HEADER), with(DEDICATED_HEADER, TOKEN), chain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void missingOrWrongToken_isUnauthorizedWithoutBody() throws Exception {
    InternalTokenFilter filter = new InternalTokenFilter(TOKEN, DEDICATED_HEADER);
    for (MockHttpServletRequest request :
        new MockHttpServletRequest[] {
          new MockHttpServletRequest(),
          with(DEDICATED_HEADER, ""),
          with(DEDICATED_HEADER, "wrong-" + TOKEN),
          with(DEDICATED_HEADER, TOKEN.substring(1))
        }) {
      MockFilterChain chain = new MockFilterChain();
      MockHttpServletResponse response = run(filter, request, chain);

      assertThat(response.getStatus()).isEqualTo(401);
      assertThat(response.getContentAsString()).isEmpty();
      assertThat(chain.getRequest()).isNull();
    }
  }

  /** 맞는 토큰이라도 다른 헤더에 실으면 안 통한다. 공용 헤더와 전용 헤더가 갈리는 근거다. */
  @Test
  void matchingTokenOnOtherHeader_isUnauthorized() throws Exception {
    MockHttpServletResponse response =
        run(
            new InternalTokenFilter(TOKEN, DEDICATED_HEADER),
            with("X-Internal-Token", TOKEN),
            new MockFilterChain());

    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void singleArgumentConstructor_readsInternalTokenHeader() throws Exception {
    InternalTokenFilter filter = new InternalTokenFilter(TOKEN);

    assertThat(run(filter, with("X-Internal-Token", TOKEN), new MockFilterChain()).getStatus())
        .isEqualTo(200);
    assertThat(run(filter, with(DEDICATED_HEADER, TOKEN), new MockFilterChain()).getStatus())
        .isEqualTo(401);
  }

  private static MockHttpServletResponse run(
      InternalTokenFilter filter, MockHttpServletRequest request, MockFilterChain chain)
      throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  private static MockHttpServletRequest with(String header, String value) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(header, value);
    return request;
  }
}
