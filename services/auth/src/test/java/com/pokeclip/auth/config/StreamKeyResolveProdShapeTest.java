package com.pokeclip.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokeclip.auth.support.IntegrationTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * prod 꼴로 연 resolve 전용 창구를 요청으로 잰다(POK-429). 속성은 런타임 · platform 이 prod auth 에 넣는 꼴이다: 허용
 * 대역은 Media 프라이빗 서브넷 같은 /24, 전달 헤더 전략 {@code native}, 신뢰 프록시 대역은 VPC 대역.
 *
 * <p>두 가지를 본다. 경로 행렬(T-A1) — 전용 토큰이 여는 auth 내부 경로는 resolve 하나뿐이다. 내부 경로는 손으로 적지 않고
 * 매핑에서 뽑으므로, 새 내부 경로가 생기면 저절로 행렬에 들어와 401 을 요구받는다. 출발지(T-A2b) — 허용 대역 경계 안은
 * 통과하고, 같은 VPC 라도 대역 밖은 403 이다.
 *
 * <p>출발지는 MockMvc 요청의 원격 주소로 준다. MockMvc 는 톰캣 밸브를 안 태우므로 진짜 전달 머리글이 붙은 요청의 403 은
 * {@link ProxyTrustNativeTest}가 맡는다. 여기서는 표시 밸브가 남기는 속성을 손으로 심는다.
 *
 * <p>헤더 이름은 제품 상수를 쓰지 않고 글자로 적는다(계약4 4C — 이름이 틀어지면 여기서 잡혀야 한다). 속성이 달라 시험
 * 컨텍스트를 하나 더 띄운다({@code IntegrationTestSupport}의 커넥션 예산 주석). 두 시험을 한 컨텍스트에 둔 이유다.
 */
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "pokeclip.internal-api.stream-key-resolve.token="
          + StreamKeyResolveProdShapeTest.RESOLVE_TOKEN,
      "pokeclip.internal-api.stream-key-resolve.allowed-cidrs=10.0.32.0/24",
      "server.forward-headers-strategy=native",
      "FORWARD_HEADERS_TRUSTED_PROXIES=10.0.0.0/16"
    })
class StreamKeyResolveProdShapeTest extends IntegrationTestSupport {

  static final String RESOLVE_TOKEN = "test-only-prod-shape-resolve-token-0123456789";

  private static final String RESOLVE_TOKEN_HEADER = "X-Stream-Key-Resolve-Token";
  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

  /** application-test.yml 의 pokeclip.internal-api.token 과 같아야 한다. */
  private static final String INTERNAL_TOKEN = "test-only-internal-token-32bytes-long!!";

  private static final String RESOLVE_PATH = "/internal/stream-keys/resolve";

  /** 허용 대역(10.0.32.0/24) 안의 Media 상자 자리다. */
  private static final String MEDIA_SOURCE = "10.0.32.17";

  /** 같은 VPC 의 다른 서브넷(clip 태스크 자리)이다. */
  private static final String OTHER_SUBNET_SOURCE = "10.0.40.5";

  /**
   * 뽑기가 깨지지 않았는지 보는 바닥이다 — 지금 알려진 내부 경로 전부(모두 POST). 뽑은 집합은 이것을 다 담아야 한다. 새
   * 경로는 여기 적지 않아도 행렬에 들어간다.
   */
  private static final Set<String> KNOWN_INTERNAL_PATHS =
      Set.of(
          RESOLVE_PATH,
          "/internal/chzzk-link/resolve",
          "/internal/soop-link/resolve",
          "/internal/youtube-link/resolve",
          "/internal/youtube-link/status",
          "/internal/editor-delegations/resolve",
          "/internal/editor-delegations/accessible",
          "/internal/streamer-channel-names");

  private static final String UNKNOWN_KEY_BODY =
      "{\"streamid\":\"#!::r=7ZK3M9QW2XJ4NB6TC8VDFG5HRP,m=publish\"}";

  /** 다른 경로의 본문이다. 보안 체인에서 끝나야 하므로 401 이 나와야 하고, 400 이 나오면 체인이 열린 것이다. */
  private static final String MINIMAL_BODY = "{}";

  private final MockMvc mockMvc;
  private final RequestMappingHandlerMapping handlerMapping;

  StreamKeyResolveProdShapeTest(MockMvc mockMvc, ApplicationContext context) {
    this.mockMvc = mockMvc;
    this.handlerMapping =
        context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
  }

  /**
   * T-A1 — 행렬은 내부 경로 × {전용 토큰을 전용 헤더로, 전용 토큰을 공용 헤더로}, 출발지는 허용 대역 안이다. resolve 를 전용
   * 헤더로 부른 칸만 200 이고 나머지는 전부 401 이다. 전용 체인 매처가 넓어지면 다른 경로의 전용 헤더 칸이 401 이 아니게
   * 된다.
   */
  @Test
  void dedicatedToken_opensOnlyStreamKeyResolve() throws Exception {
    Set<String> paths = internalPostPaths();
    assertThat(paths).as("내부 경로 뽑기가 깨졌다").containsAll(KNOWN_INTERNAL_PATHS);

    List<String> mismatches = new ArrayList<>();
    for (String path : paths) {
      for (String header : List.of(RESOLVE_TOKEN_HEADER, INTERNAL_TOKEN_HEADER)) {
        boolean opens = path.equals(RESOLVE_PATH) && header.equals(RESOLVE_TOKEN_HEADER);
        MockHttpServletResponse response =
            mockMvc
                .perform(
                    post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(path.equals(RESOLVE_PATH) ? UNKNOWN_KEY_BODY : MINIMAL_BODY)
                        .header(header, RESOLVE_TOKEN)
                        .with(from(MEDIA_SOURCE)))
                .andReturn()
                .getResponse();
        int expected = opens ? 200 : 401;
        if (response.getStatus() != expected
            || (opens && !response.getContentAsString().contains("\"valid\":false"))) {
          mismatches.add(
              path + " × " + header + " → " + response.getStatus() + " (기대 " + expected + ")");
        }
      }
    }
    assertThat(mismatches).as("전용 토큰이 resolve 밖에서 통하거나 resolve 를 못 연다").isEmpty();
  }

  /** T-A2b — 허용 대역의 경계 주소까지 전용 창구가 열린다. */
  @ParameterizedTest
  @ValueSource(strings = {MEDIA_SOURCE, "10.0.32.0", "10.0.32.255"})
  void dedicatedTokenFromInsideAllowedSubnet_reachesResolve(String source) throws Exception {
    mockMvc
        .perform(resolve().header(RESOLVE_TOKEN_HEADER, RESOLVE_TOKEN).with(from(source)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(false));
  }

  /** T-A2b — 신뢰 프록시 대역(VPC) 안이라도 허용 대역 바로 밖이면 403 이고 본문이 없다. */
  @ParameterizedTest
  @ValueSource(strings = {"10.0.31.255", "10.0.33.0"})
  void dedicatedTokenFromOutsideAllowedSubnet_isForbiddenWithoutBody(String source)
      throws Exception {
    mockMvc
        .perform(resolve().header(RESOLVE_TOKEN_HEADER, RESOLVE_TOKEN).with(from(source)))
        .andExpect(status().isForbidden())
        .andExpect(content().string(""));
  }

  /** T-A2b — 도착 때 전달 머리글이 있었다고 표시된 요청은 허용 대역 안에서 와도 403 이다(POK-316). */
  @Test
  void markedForwardedRequestFromAllowedSubnet_isForbiddenWithoutBody() throws Exception {
    mockMvc
        .perform(
            resolve()
                .header(RESOLVE_TOKEN_HEADER, RESOLVE_TOKEN)
                .requestAttr(ForwardedHeaderMarkValve.ATTRIBUTE, Boolean.TRUE)
                .with(from(MEDIA_SOURCE)))
        .andExpect(status().isForbidden())
        .andExpect(content().string(""));
  }

  /** T-A2b — 전용 헤더가 없는 resolve 는 공용 체인이 받아 출발지 잠금이 없다(clip 수동 마킹 경로). */
  @Test
  void internalTokenResolveFromOtherSubnet_isUnchanged() throws Exception {
    mockMvc
        .perform(
            resolve().header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN).with(from(OTHER_SUBNET_SOURCE)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(false));
  }

  /**
   * 매핑에서 /internal/ 로 시작하는 경로를 뽑는다. 행렬이 다루지 못하는 꼴이면 매핑 이름과 이유를 담아 실패한다 — 새 꼴의
   * 내부 경로를 더하는 사람이 행렬을 함께 넓히게 하려는 것이다.
   */
  private Set<String> internalPostPaths() {
    Set<String> paths = new TreeSet<>();
    List<String> problems = new ArrayList<>();
    handlerMapping
        .getHandlerMethods()
        .forEach(
            (info, method) -> {
              for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/internal/")) {
                  continue;
                }
                String name =
                    method.getBeanType().getSimpleName()
                        + "#"
                        + method.getMethod().getName()
                        + " "
                        + pattern;
                if (pattern.contains("{") || pattern.contains("*")) {
                  problems.add(name + " — 경로 변수: 행렬에 경로 채우기 규칙이 없다");
                } else if (!info.getMethodsCondition()
                    .getMethods()
                    .equals(Set.of(RequestMethod.POST))) {
                  problems.add(name + " — POST 아닌 매핑: 행렬은 POST 만 보낸다");
                } else {
                  paths.add(pattern);
                }
              }
            });
    assertThat(problems).as("행렬이 다루지 못하는 내부 매핑").isEmpty();
    return paths;
  }

  private static MockHttpServletRequestBuilder resolve() {
    return post(RESOLVE_PATH).contentType(MediaType.APPLICATION_JSON).content(UNKNOWN_KEY_BODY);
  }

  private static RequestPostProcessor from(String remoteAddress) {
    return request -> {
      request.setRemoteAddr(remoteAddress);
      return request;
    };
  }
}
