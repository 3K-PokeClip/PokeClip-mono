package com.pokeclip.clip.demo;

import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 발표 데모의 「채팅 이벤트」 문. 운영 사이트에 들어가므로 <b>목록에 적힌 데모 계정만</b> 연다.
 * 목록 밖 사람에게는 문이 없는 것처럼 404이고, 수집기까지 아무것도 안 간다.
 *
 * <p>🔴 데모 계정이어도 <b>그 방송을 볼 자격</b>(스트리머 본인 · 편집자)이 있어야 한다. 남의 방송에 채팅을 쏟지 못하게.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "pokeclip.demo.user-ids=" + DemoChatEventControllerTest.데모_계정 + ",9999")
class DemoChatEventControllerTest extends IntegrationTestSupport {

    static final String 데모_계정 = "4181";

    private static final String 보통_계정 = "4182";

    private static final String RESOLVE = "/internal/editor-delegations/resolve";

    private static final String 방송 = "s-demo";

    private final int port;
    private final JdbcTemplate jdbc;

    DemoChatEventControllerTest(@LocalServerPort int port, JdbcTemplate jdbc) {
        this.port = port;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 방송을_준비한다() {
        방송과_카드를_비운다(jdbc);
        jdbc.update("""
                        INSERT INTO broadcasts (stream_id, streamer_id, status, started_at, last_sequence)
                        VALUES (?, ?, 'live', ?, 1)""",
                방송, TestIds.STREAMER, OffsetDateTime.ofInstant(Instant.parse("2026-11-22T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void 데모_계정이면_쓸_수_있다고_답하고_보통_계정이면_문이_없다() throws Exception {
        assertThat(부른다("GET", "/api/clip/demo/chat-event", 데모_계정).statusCode()).isEqualTo(204);
        assertThat(부른다("GET", "/api/clip/demo/chat-event", 보통_계정).statusCode()).isEqualTo(404);
    }

    @Test
    void 데모_계정이_볼_수_있는_방송이면_수집기에_넘기고_답을_그대로_준다() throws Exception {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"EDITOR\"}");
        COLLECTOR.respondWith("/internal/streams/" + 방송 + "/demo-chat-burst", 202, "{\"started\":true}");

        HttpResponse<String> 응답 = 부른다("POST", 누르는_곳(), 데모_계정);

        assertThat(응답.statusCode()).as(응답.body()).isEqualTo(202);
        assertThat(응답.body()).isEqualTo("{\"started\":true}");
        assertThat(COLLECTOR.lastMethod()).isEqualTo("POST");
        assertThat(COLLECTOR.lastPath()).isEqualTo("/internal/streams/" + 방송 + "/demo-chat-burst");
    }

    @Test
    void 보통_계정은_자기_방송이어도_404이고_수집기에_안_간다() throws Exception {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");

        HttpResponse<String> 응답 = 부른다("POST", 누르는_곳(), 보통_계정);

        assertThat(응답.statusCode()).isEqualTo(404);
        assertThat(COLLECTOR.callCount()).isZero();
    }

    @Test
    void 데모_계정도_볼_자격이_없는_방송이면_404이고_수집기에_안_간다() throws Exception {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"NONE\"}");

        HttpResponse<String> 응답 = 부른다("POST", 누르는_곳(), 데모_계정);

        assertThat(응답.statusCode()).isEqualTo(404);
        assertThat(COLLECTOR.callCount()).isZero();
    }

    private static String 누르는_곳() {
        return "/api/clip/broadcasts/" + 방송 + "/demo/chat-event";
    }

    private HttpResponse<String> 부른다(String method, String path, String 사용자) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + TestTokens.access(사용자))
                .timeout(Duration.ofSeconds(30))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
