package com.pokeclip.chat.collector.status;

import com.pokeclip.chat.collector.broadcast.EndedStreamStore;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 창구를 <b>밖에서</b> 친다. 등록부에 세션이 없는 프로세스라 답은 메모 표와 unknown뿐이다 —
 * 상태 전이는 {@code ChatCollectionStatusResolverTest}가 가짜 치지직으로 잰다(두 겹, health 검사와 같은 구조).
 * 여기는 직렬화(시각이 ISO 문자열인가·null이 null로 나가나)·필터·경로·health 분리를 본다.
 */
class ChatCollectionEndpointTest {

    static HttpResponse<String> get(int port, String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) request.header("X-Internal-Token", token);
        return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "pokeclip.link.internal-token=test-internal-token")
    @ActiveProfiles("test")
    static class 토큰이_설정된_프로세스 extends IntegrationTestSupport {

        @LocalServerPort int port;
        @Autowired EndedStreamStore store;
        @Autowired JdbcTemplate jdbc;

        @BeforeEach
        void 표를_비운다() {
            jdbc.update("DELETE FROM chat_ended_streams");
        }

        @Test
        void 모르는_방송도_200이고_unknown이다() throws Exception {
            HttpResponse<String> response = get(port, "/internal/streams/never/chat-collection", "test-internal-token");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .contains("\"streamId\":\"never\"")
                    .contains("\"state\":\"unknown\"")
                    .contains("\"since\":null")
                    .contains("\"attempt\":null")
                    .contains("\"needsRelink\":false")
                    .contains("\"observedAt\":\"");
        }

        // 문항 4: state만 보면 본문에 JSON이 둘 실려도 참이다 — 상태 코드와 streamId를 같이 본다.
        @Test
        void 포기_메모가_있으면_stopped이고_시각은_ISO_문자열이다() throws Exception {
            store.rememberStopped("s1", "REVOKED", Instant.parse("2026-08-22T12:00:00Z"));
            HttpResponse<String> response = get(port, "/internal/streams/s1/chat-collection", "test-internal-token");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .contains("\"streamId\":\"s1\"")
                    .contains("\"state\":\"stopped\"")
                    .contains("\"since\":\"2026-08-22T12:00:00Z\"")
                    .contains("\"needsRelink\":true")
                    .as("내부 사유 이름은 밖에 안 나간다").doesNotContain("REVOKED");
        }

        @Test
        void 토큰이_틀리면_401_본문_없음() throws Exception {
            HttpResponse<String> response = get(port, "/internal/streams/s1/chat-collection", "wrong");
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).isEmpty();
        }

        @Test
        void health는_토큰_없이_그대로_200이다() throws Exception {
            HttpResponse<String> response = get(port, "/actuator/health", null);
            assertThat(response.statusCode()).as("창구와 경로가 분리돼 있다 — 카드 완료 조건 ③").isEqualTo(200);
        }
    }

    /**
     * 토큰 설정이 없는 프로세스(CI·팀원 로컬과 같은 모양). 창구는 잠겨 있어야 한다.
     * 빈 값을 <b>명시</b>한다 — 셸에 INTERNAL_API_TOKEN이 있는 기계에서는 yml 기본값이 그 값을 읽어 이 검사가 틀어진다(critic).
     */
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "pokeclip.link.internal-token=")
    @ActiveProfiles("test")
    static class 토큰이_빈_프로세스 extends IntegrationTestSupport {

        @LocalServerPort int port;

        // 문항 5: InternalTokenFilter의 `locked ||`를 지우면 빈 헤더 쪽이 200으로 빨간불(태스크 4에서 확인함).
        @Test
        void 빈_헤더로도_401이다() throws Exception {
            assertThat(get(port, "/internal/streams/s1/chat-collection", "").statusCode()).isEqualTo(401);
            assertThat(get(port, "/internal/streams/s1/chat-collection", null).statusCode()).isEqualTo(401);
        }
    }
}
