package com.pokeclip.clip.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.web.security.ForwardedHeaderMarkValve;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 만료 방송 창구의 전용 출입증 체인(POK-196 M6, 설계 D27)을 요청으로 잰다. auth resolve 전용 체인 시험
 * ({@code StreamKeyResolveSecurityConfigTest})을 옮겨 왔다 — 부품이 같고(web-support) 판정 순서도 같다: 출발지(403) 다음
 * 토큰(401). 출발지는 MockMvc 요청의 원격 주소로 준다 — 진짜 톰캣에서 소켓 주소가 놓이는 자리다.
 *
 * <p>헤더 이름은 제품 상수를 쓰지 않고 글자로 적는다. 1번(Media)과 맞춘 이름이라 틀어지면 여기서 잡혀야 한다.
 *
 * <p>{@link WithExpiredBroadcastsToken}은 속성이 달라 시험 컨텍스트를 하나 더 띄운다({@code IntegrationTestSupport}의
 * 커넥션 예산 주석). {@link WithoutExpiredBroadcastsToken}은 다른 MockMvc 시험과 같은 컨텍스트를 쓴다.
 */
@AutoConfigureMockMvc
class ExpiredBroadcastSecurityConfigTest extends IntegrationTestSupport {

  private static final String EXPIRED_TOKEN_HEADER = "X-Expired-Broadcasts-Token";
  private static final String EXPIRED_TOKEN = "test-only-expired-broadcasts-token-0123456789";

  /** application-test.yml 의 pokeclip.internal-api.token 과 같아야 한다. */
  private static final String INTERNAL_TOKEN = "test-only-internal-token-32bytes-long!!";

  /** 허용 대역은 이 주소 하나(/32)다. 운영은 Media 프라이빗 서브넷 대역이다. */
  private static final String ALLOWED_SOURCE = "198.51.100.7";

  /** 허용 주소 바로 옆이다. /32 가 그 주소 하나만 여는지 본다. */
  private static final String NEIGHBOR_SOURCE = "198.51.100.8";

  private static final String OUTSIDE_SOURCE = "203.0.113.50";

  /**
   * 앞 네 바이트(c6 33 64 07)가 {@link #ALLOWED_SOURCE}와 같은 IPv6 주소다
   * (spring-projects/spring-security#19733).
   */
  private static final String IPV6_WITH_ALLOWED_LEADING_BYTES = "c633:6407::";

  @Nested
  @TestPropertySource(
      properties = {
        "pokeclip.internal-api.expired-broadcasts.token=" + EXPIRED_TOKEN,
        "pokeclip.internal-api.expired-broadcasts.allowed-cidrs=" + ALLOWED_SOURCE + "/32",
        // 전략 env 가 비면 미설정(플랫폼 감지)이다. 창구를 여는 환경은 같이 넣어야 부팅 검사를 지난다.
        "server.forward-headers-strategy=none"
      })
  class WithExpiredBroadcastsToken {

    private final MockMvc mockMvc;

    WithExpiredBroadcastsToken(MockMvc mockMvc) {
      this.mockMvc = mockMvc;
    }

    @Test
    void dedicatedTokenFromAllowedSource_reachesExpiredList() throws Exception {
      mockMvc
          .perform(expired().header(EXPIRED_TOKEN_HEADER, EXPIRED_TOKEN).with(from(ALLOWED_SOURCE)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.broadcasts").isArray());
    }

    @Test
    void sourceOutsideAllowedCidrs_isForbiddenWithoutBody() throws Exception {
      mockMvc
          .perform(
              expired().header(EXPIRED_TOKEN_HEADER, EXPIRED_TOKEN).with(from(NEIGHBOR_SOURCE)))
          .andExpect(status().isForbidden())
          .andExpect(content().string(""));
    }

    /** 출발지는 소켓 주소만 본다. 허용 주소를 X-Forwarded-For 에 적어도 소켓이 밖이면 403 이다. */
    @Test
    void forgedForwardedFor_doesNotChangeSource() throws Exception {
      mockMvc
          .perform(
              expired()
                  .header(EXPIRED_TOKEN_HEADER, EXPIRED_TOKEN)
                  .header("X-Forwarded-For", ALLOWED_SOURCE)
                  .with(from(OUTSIDE_SOURCE)))
          .andExpect(status().isForbidden());
    }

    /**
     * 도착 때 전달 머리글이 있었다고 표시된 요청은 허용 주소에서 와도 403 이다(POK-316). 톰캣 밸브는 X-Forwarded-For 를
     * 읽고 지우므로 여기서는 머리글이 아니라 표시 밸브가 남긴 속성을 본다. MockMvc 는 밸브를 안 태워 속성을 손으로 심는다.
     */
    @Test
    void markedForwardedRequestFromAllowedSource_isForbiddenWithoutBody() throws Exception {
      mockMvc
          .perform(
              expired()
                  .header(EXPIRED_TOKEN_HEADER, EXPIRED_TOKEN)
                  .requestAttr(ForwardedHeaderMarkValve.ATTRIBUTE, Boolean.TRUE)
                  .with(from(ALLOWED_SOURCE)))
          .andExpect(status().isForbidden())
          .andExpect(content().string(""));
    }

    /**
     * 판정은 출발지 다음 토큰이다. 허용 밖에서 오면 토큰이 틀려도 403 이다 — 순서가 뒤집히면 이 요청은 401 이 되어, 대역
     * 밖에서도 응답 코드(401 · 403)로 토큰이 맞는지 가려 볼 수 있다.
     */
    @Test
    void wrongTokenFromOutsideSource_isForbidden() throws Exception {
      mockMvc
          .perform(
              expired()
                  .header(EXPIRED_TOKEN_HEADER, "wrong-" + EXPIRED_TOKEN)
                  .with(from(OUTSIDE_SOURCE)))
          .andExpect(status().isForbidden());
    }

    @Test
    void ipv6SourceWithAllowedLeadingBytes_isForbidden() throws Exception {
      mockMvc
          .perform(
              expired()
                  .header(EXPIRED_TOKEN_HEADER, EXPIRED_TOKEN)
                  .with(from(IPV6_WITH_ALLOWED_LEADING_BYTES)))
          .andExpect(status().isForbidden());
    }

    /**
     * 공용 토큰을 같이 실어도 401 이다. 전용 헤더가 있으면 전용 체인만 보고, 한 체인에 인증 수단 둘을 섞지 않는다
     * (InternalSecurityConfig 머리 주석). 공용 체인으로 흘러가면 공용 토큰 덕에 200 이 된다 — 체인 순서가 뒤집혀도 그렇다.
     */
    @Test
    void wrongDedicatedToken_isUnauthorizedEvenWithInternalToken() throws Exception {
      mockMvc
          .perform(
              expired()
                  .header(EXPIRED_TOKEN_HEADER, "wrong-" + EXPIRED_TOKEN)
                  .header("X-Internal-Token", INTERNAL_TOKEN)
                  .with(from(ALLOWED_SOURCE)))
          .andExpect(status().isUnauthorized());
    }

    /** 공용 토큰을 전용 헤더에 실어도 안 통한다. Media 에는 공용 토큰을 주지 않는다(설계 7절 prod 연결). */
    @Test
    void internalTokenInDedicatedHeader_isUnauthorized() throws Exception {
      mockMvc
          .perform(
              expired().header(EXPIRED_TOKEN_HEADER, INTERNAL_TOKEN).with(from(ALLOWED_SOURCE)))
          .andExpect(status().isUnauthorized());
    }

    /** 전용 토큰은 만료 목록만 연다. 다른 /internal 창구는 공용 체인이 받아 공용 토큰을 요구한다. */
    @Test
    void dedicatedToken_doesNotOpenOtherInternalEndpoints() throws Exception {
      mockMvc
          .perform(
              get("/internal/broadcasts/live")
                  .header(EXPIRED_TOKEN_HEADER, EXPIRED_TOKEN)
                  .with(from(ALLOWED_SOURCE)))
          .andExpect(status().isUnauthorized());
    }

    /** 전용 헤더가 없는 만료 목록 요청은 지금처럼 공용 체인이 받는다. 출발지 잠금도 걸리지 않는다. */
    @Test
    void internalTokenRequest_isUnchanged() throws Exception {
      mockMvc
          .perform(expired().header("X-Internal-Token", INTERNAL_TOKEN).with(from(OUTSIDE_SOURCE)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.broadcasts").isArray());
    }
  }

  /** 시험 프로필에는 전용 토큰도 허용 대역도 없다. 창구가 닫힌 상태다. */
  @Nested
  class WithoutExpiredBroadcastsToken {

    private final MockMvc mockMvc;

    WithoutExpiredBroadcastsToken(MockMvc mockMvc) {
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
              expired()
                  .header(EXPIRED_TOKEN_HEADER, "")
                  .header("X-Internal-Token", INTERNAL_TOKEN)
                  .with(from(ALLOWED_SOURCE)))
          .andExpect(status().isUnauthorized());
    }

    /** 전달 머리글 표시가 있어도 닫힘은 401 이다. 닫힌 체인에 출발지 필터를 달면 403 으로 갈린다(POK-316). */
    @Test
    void markedForwardedRequest_isStillUnauthorized() throws Exception {
      mockMvc
          .perform(
              expired()
                  .header(EXPIRED_TOKEN_HEADER, "")
                  .requestAttr(ForwardedHeaderMarkValve.ATTRIBUTE, Boolean.TRUE)
                  .with(from(ALLOWED_SOURCE)))
          .andExpect(status().isUnauthorized());
    }
  }

  private static MockHttpServletRequestBuilder expired() {
    return get("/internal/broadcasts/expired").param("before", "2026-10-01T00:00:00Z");
  }

  private static RequestPostProcessor from(String remoteAddress) {
    return request -> {
      request.setRemoteAddr(remoteAddress);
      return request;
    };
  }
}
