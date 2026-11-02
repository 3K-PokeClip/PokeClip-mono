package com.pokeclip.clip;

import com.pokeclip.clip.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>HTTP 응답 본문</b>에 수신 상태가 실리는지 본다. {@code IntakeHealthTest}처럼
 * {@code HealthIndicator}를 직접 부르는 한 이 결함은 영영 안 보인다.
 *
 * <p>실제로 그렇게 놓쳤다(검증자 실기동, 2026-08-18). {@code IntakeHealth}는
 * {@code withDetail("status","disabled")}를 제대로 만들었고 단위 검사 4개도 초록이었는데,
 * {@code management.endpoint.health.show-details}가 없어 Boot 기본값 {@code never}가
 * 응답에서 detail을 잘랐다 — 밖에서는 {@code {"status":"UP"}}만 보였다.
 * 프로세스를 띄워 curl을 쳐야만 보이는 종류의 결함이다.
 *
 * <p>그 상태가 이 카드가 막으려는 실패 그 자체다 — 서버는 뜨고 헬스체크도 통과하는데
 * 방송 이벤트를 받는지 아무도 모른다. PRD 성공 기준
 * 「꺼둔 상태에서 상세에 '꺼져 있음'이 보인다」가 이 검사에 걸려 있다.
 *
 * <p>chat-collector {@code CollectorHealthEndpointTest}와 같은 방식이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointTest extends IntegrationTestSupport {

    private final int port;

    HealthEndpointTest(@LocalServerPort int port) {
        this.port = port;
    }

    @Test
    void health_응답_본문에_수신이_꺼져_있음이_실린다() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .as("show-details가 never면 여기서 잘린다. 단위 검사는 못 잡는다")
                .contains("\"broadcastIntake\"")
                .contains("\"status\":\"disabled\"");
    }
}
