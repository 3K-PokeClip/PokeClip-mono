package com.pokeclip.auth.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokeclip.auth.support.IntegrationTestSupport;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * resolve 전용 출입증 체인(1번 T 설계 2-7)을 요청으로 잰다. 출발지는 MockMvc 요청의 원격 주소로 준다 — 진짜 톰캣에서
 * 소켓 주소가 놓이는 자리다.
 *
 * <p>헤더 이름은 제품 상수를 쓰지 않고 글자로 적는다. Media 와 맞춘 계약(계약4 4C)이라 이름이 틀어지면 여기서
 * 잡혀야 한다.
 *
 * <p>{@link WithResolveToken}은 속성이 달라 시험 컨텍스트를 하나 더 띄운다({@code IntegrationTestSupport}의
 * 커넥션 예산 주석). {@link WithoutResolveToken}은 다른 MockMvc 시험과 같은 컨텍스트를 쓴다.
 */
@AutoConfigureMockMvc
class StreamKeyResolveSecurityConfigTest extends IntegrationTestSupport {

  private static final String RESOLVE_TOKEN_HEADER = "X-Stream-Key-Resolve-Token";
  private static final String RESOLVE_TOKEN = "test-only-stream-key-resolve-token-0123456789";

  /** application-test.yml 의 pokeclip.internal-api.token 과 같아야 한다. */
  private static final String INTERNAL_TOKEN = "test-only-internal-token-32bytes-long!!";

  /** 허용 대역은 이 주소 하나(/32)다. dev 의 media-dev 사설 주소 자리다. */
  private static final String ALLOWED_SOURCE = "198.51.100.7";

  /** 허용 주소 바로 옆이다. /32 가 그 주소 하나만 여는지 본다. */
  private static final String NEIGHBOR_SOURCE = "198.51.100.8";

  private static final String OUTSIDE_SOURCE = "203.0.113.50";

  private static final String UNKNOWN_KEY_BODY =
      "{\"streamid\":\"#!::r=7ZK3M9QW2XJ4NB6TC8VDFG5HRP,m=publish\"}";

  @Nested
  @TestPropertySource(
      properties = {
        "pokeclip.internal-api.stream-key-resolve.token=" + RESOLVE_TOKEN,
        "pokeclip.internal-api.stream-key-resolve.allowed-cidrs=" + ALLOWED_SOURCE + "/32"
      })
  class WithResolveToken {

    private final MockMvc mockMvc;

    WithResolveToken(MockMvc mockMvc) {
      this.mockMvc = mockMvc;
    }

    @Test
    void dedicatedTokenFromAllowedSource_reachesResolve() throws Exception {
      mockMvc
          .perform(resolve().header(RESOLVE_TOKEN_HEADER, RESOLVE_TOKEN).with(from(ALLOWED_SOURCE)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.valid").value(false));
    }

    @Test
    void sourceOutsideAllowedCidrs_isForbiddenWithoutBody() throws Exception {
      mockMvc
          .perform(
              resolve().header(RESOLVE_TOKEN_HEADER, RESOLVE_TOKEN).with(from(NEIGHBOR_SOURCE)))
          .andExpect(status().isForbidden())
          .andExpect(content().string(""));
    }

    /** 출발지는 소켓 주소만 본다. 허용 주소를 X-Forwarded-For 에 적어도 소켓이 밖이면 403 이다. */
    @Test
    void forgedForwardedFor_doesNotChangeSource() throws Exception {
      mockMvc
          .perform(
              resolve()
                  .header(RESOLVE_TOKEN_HEADER, RESOLVE_TOKEN)
                  .header("X-Forwarded-For", ALLOWED_SOURCE)
                  .with(from(OUTSIDE_SOURCE)))
          .andExpect(status().isForbidden());
    }

    /**
     * 공용 토큰을 같이 실어도 401 이다. 전용 헤더가 있으면 새 체인만 보고, 한 체인에 인증 수단 둘을 섞지 않는다
     * (InternalSecurityConfig 머리 주석). 공용 체인으로 흘러가면 공용 토큰 덕에 200 이 된다.
     */
    @Test
    void wrongDedicatedToken_isUnauthorizedEvenWithInternalToken() throws Exception {
      mockMvc
          .perform(
              resolve()
                  .header(RESOLVE_TOKEN_HEADER, "wrong-" + RESOLVE_TOKEN)
                  .header("X-Internal-Token", INTERNAL_TOKEN)
                  .with(from(ALLOWED_SOURCE)))
          .andExpect(status().isUnauthorized());
    }

    /** 전용 토큰은 resolve 만 연다. 다른 /internal 창구는 공용 체인이 받아 공용 토큰을 요구한다. */
    @Test
    void dedicatedToken_doesNotOpenOtherInternalEndpoints() throws Exception {
      mockMvc
          .perform(
              post("/internal/youtube-link/resolve")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"userId\":1}")
                  .header(RESOLVE_TOKEN_HEADER, RESOLVE_TOKEN)
                  .with(from(ALLOWED_SOURCE)))
          .andExpect(status().isUnauthorized());
    }

    /** 전용 헤더가 없는 resolve 는 지금처럼 공용 체인이 받는다. 출발지 잠금도 걸리지 않는다. */
    @Test
    void internalTokenResolve_isUnchanged() throws Exception {
      mockMvc
          .perform(resolve().header("X-Internal-Token", INTERNAL_TOKEN).with(from(OUTSIDE_SOURCE)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.valid").value(false));
    }
  }

  /** 시험 프로필에는 전용 토큰도 허용 대역도 없다. 창구가 닫힌 상태다. */
  @Nested
  class WithoutResolveToken {

    private final MockMvc mockMvc;

    WithoutResolveToken(MockMvc mockMvc) {
      this.mockMvc = mockMvc;
    }

    /**
     * 닫힌 창구는 401 이다. 막는 실수가 셋이다.
     *
     * <ul>
     *   <li>빈 토큰으로 토큰 필터를 만들면 빈 헤더가 통과한다 — 그래서 빈 값을 보낸다.
     *   <li>출발지 필터를 달면 빈 대역 목록이 먼저 403 을 낸다 — 닫힘은 401 로 한 가지다.
     *   <li>공용 체인으로 흘러가면 같이 실은 공용 토큰 덕에 200 이 된다.
     * </ul>
     */
    @Test
    void dedicatedHeader_isUnauthorizedEvenWithInternalToken() throws Exception {
      mockMvc
          .perform(
              resolve()
                  .header(RESOLVE_TOKEN_HEADER, "")
                  .header("X-Internal-Token", INTERNAL_TOKEN)
                  .with(from(ALLOWED_SOURCE)))
          .andExpect(status().isUnauthorized());
    }
  }

  private static MockHttpServletRequestBuilder resolve() {
    return post("/internal/stream-keys/resolve")
        .contentType(MediaType.APPLICATION_JSON)
        .content(UNKNOWN_KEY_BODY);
  }

  private static RequestPostProcessor from(String remoteAddress) {
    return request -> {
      request.setRemoteAddr(remoteAddress);
      return request;
    };
  }
}
