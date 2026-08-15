package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>HTTP 응답 본문</b>에 수집 상태가 실리는지. 단위 테스트가
 * {@code HealthIndicator}를 직접 부르는 한 이 결함은 영영 안 보인다.
 *
 * <p>실제로 그렇게 놓쳤다. {@code CollectorHealth}는
 * {@code withDetail("status","disabled")}를 제대로 만들었고 단위 테스트도 초록이었는데,
 * Boot 기본값 {@code show-details: never}가 응답에서 detail을 잘라
 * 밖에서는 {@code {"status":"UP"}}만 보였다. 프로세스를 띄워 curl을 쳐야만 보였다.
 *
 * <p>그 상태가 이 카드가 막으려는 실패 그 자체다 — 서버는 뜨고 헬스체크도
 * 통과하는데 수집이 도는지 아무도 모른다.
 */
class CollectorHealthEndpointTest {

    /** 꺼짐. PRD 상태표 첫 행이다. */
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("test")
    static class 꺼져_있을_때 extends IntegrationTestSupport {

        @LocalServerPort int port;

        @Test
        void health_응답_본문에_disabled가_실린다() throws Exception {
            HttpResponse<String> response = get(port);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .as("show-details가 never면 여기서 잘린다. 단위 테스트는 못 잡는다")
                    .contains("\"status\":\"disabled\"");
        }
    }

    /**
     * 켜졌는데 실패. PRD 상태표가 {@code DOWN (reason: SESSION_AUTH_FAILED 등)}을
     * 규정한 행이다. 죽은 포트로 향하게 해 가짜 서버 없이 실패를 만든다.
     */
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                    "pokeclip.chzzk.enabled=true",
                    "pokeclip.chzzk.base-url=http://localhost:1"
            })
    @ActiveProfiles("test")
    static class 수집이_멈췄을_때 extends IntegrationTestSupport {

        @LocalServerPort int port;

        @Test
        void health가_DOWN이고_응답_본문에_사유가_실린다() throws Exception {
            HttpResponse<String> response = get(port);

            // DOWN이면 actuator가 503을 준다. 200이면 상태 자체가 안 내려간 것이다.
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).contains("\"status\":\"DOWN\"");
            assertThat(response.body())
                    .as("사유가 응답에 없으면 왜 멈췄는지 밖에서 알 길이 없다")
                    .contains("SESSION_AUTH_FAILED");
        }
    }

    private static HttpResponse<String> get(int port) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
