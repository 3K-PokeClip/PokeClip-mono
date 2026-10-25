package com.pokeclip.clip.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.pokeclip.clip.support.IntegrationTestSupport;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * 만료 방송 전용 창구를 운영 꼴({@code native})의 진짜 톰캣으로 잰다(POK-196 M6, 설계 D27 XFF 위조). MockMvc 는 톰캣
 * 밸브를 안 태우므로 표시 밸브 배선은 여기서만 잴 수 있다. auth {@code ProxyTrustNativeTest}의 전용 창구 갈래를 옮겨
 * 왔다.
 *
 * <p>소켓은 늘 루프백이고 허용 대역도 루프백이다. 루프백은 Boot 기본 신뢰 프록시 대역 안이라, 톰캣 {@code RemoteIpValve}는
 * {@code X-Forwarded-For}의 값을 요청자 주소로 채택하고 머리글을 지운다. 표시 밸브가 그보다 앞에 없으면 출발지 필터에는
 * 「머리글 없는 허용 주소 요청」으로 보여 200 이 된다 — Media 프라이빗 서브넷 밖의 호출자가 신뢰 프록시를 거쳐 허용 주소를
 * 적어 보내는 길이다. 별도 컨텍스트다: {@code IntegrationTestSupport} 주석 참조.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = {
      "server.forward-headers-strategy=native",
      "pokeclip.internal-api.expired-broadcasts.token=" + ExpiredBroadcastNativeProxyTest.TOKEN,
      "pokeclip.internal-api.expired-broadcasts.allowed-cidrs=127.0.0.1/32"
    })
class ExpiredBroadcastNativeProxyTest extends IntegrationTestSupport {

  static final String TOKEN = "test-only-expired-native-token-0123456789";

  private final int port;

  ExpiredBroadcastNativeProxyTest(@LocalServerPort int port) {
    this.port = port;
  }

  /** 대조: 전달 머리글이 없으면 출발지(루프백, 허용 대역)와 토큰을 지나 만료 목록에 닿는다. */
  @Test
  void withoutForwardedHeaders_reachesExpiredList() {
    assertThat(expired(TOKEN, Map.of()).statusCode()).isEqualTo(200);
  }

  /** 머리글이 없으면 판정은 그대로다: 출발지를 지나고 토큰이 틀려 401 이다. */
  @Test
  void withoutForwardedHeaders_stillChecksToken() {
    assertThat(expired("wrong-" + TOKEN, Map.of()).statusCode()).isEqualTo(401);
  }

  @Test
  void anyForwardedHeader_isForbiddenWithoutBody() {
    for (Map.Entry<String, String> header :
        List.of(
            Map.entry("X-Forwarded-For", "127.0.0.1"),
            Map.entry("Forwarded", "for=127.0.0.1"),
            Map.entry("X-Forwarded-Proto", "https"),
            Map.entry("X-Forwarded-Host", "clip.internal"),
            Map.entry("X-Forwarded-Port", "443"))) {
      HttpResponse<String> response = expired(TOKEN, Map.of(header.getKey(), header.getValue()));

      assertThat(response.statusCode())
          .as("%s가 붙었는데 통과했다", header.getKey())
          .isEqualTo(403);
      assertThat(response.body()).as("403 에 본문이 있다").isEmpty();
    }
  }

  private HttpResponse<String> expired(String token, Map<String, String> extraHeaders) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + port
                        + "/internal/broadcasts/expired?before=2026-10-01T00:00:00Z"))
            .header("X-Expired-Broadcasts-Token", token)
            .GET();
    extraHeaders.forEach(builder::header);
    try {
      return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
