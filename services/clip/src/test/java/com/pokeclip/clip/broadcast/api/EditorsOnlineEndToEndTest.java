package com.pokeclip.clip.broadcast.api;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.stream.CardStreamRegistry;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.SseReader;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「편집자 N명 접속 중」(POK-398)을 실제 대시보드 연결로 잰다. MockMvc로는 SSE 연결이 서블릿 밖이라 정리 콜백이 안 돌고,
 * 캐시된 공유 컨텍스트에 연결이 남아 다른 시험의 상한을 먹는다. 그래서 실제 HTTP로 열고 닫는다.
 *
 * <p>스트리머 1 · 편집자 A 두 탭 · 편집자 B 1을 연다. 묻는 사람에 따라 답이 다르다: 스트리머 2(A·B), A 1(B), B 1(A).
 * 스트리머와 묻는 사람을 빼고 같은 사람은 한 번 센다. 번호는 이 클래스만 쓰는 값이다(다른 시험의 연결이 안 섞이게).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EditorsOnlineEndToEndTest extends IntegrationTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RESOLVE = "/internal/editor-delegations/resolve";
    private static final String ACCESSIBLE = "/internal/editor-delegations/accessible";
    private static final String 방송 = "s-editors-online";
    private static final String 편집자_A = "39801";
    private static final String 편집자_B = "39802";

    private final int port;
    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;
    private final CardStreamRegistry registry;

    EditorsOnlineEndToEndTest(@LocalServerPort int port, BroadcastRepository broadcasts, JdbcTemplate jdbc,
                              CardStreamRegistry registry) {
        this.port = port;
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
        this.registry = registry;
    }

    @Test
    void 묻는_사람과_스트리머를_빼고_같은_사람은_한_번_센다() throws Exception {
        jdbc.update("DELETE FROM jump_cards");
        broadcasts.deleteAllInBatch();
        broadcasts.save(Broadcast.startedNow(방송, TestIds.STREAMER, null, 1L, Instant.now(), null));
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"EDITOR\"}");
        AUTH.respondWith(ACCESSIBLE, 200,
                "{\"streamers\":[{\"streamerUserId\":%s,\"relation\":\"EDITOR\"}]}".formatted(TestIds.STREAMER));

        List<SseReader> readers = new ArrayList<>();
        try {
            for (String user : List.of(TestIds.STREAMER, 편집자_A, 편집자_A, 편집자_B)) {
                SseReader reader = open(user);
                readers.add(reader);
                assertThat(reader.statusCode()).isEqualTo(200);
                assertThat(reader.await(1, Duration.ofSeconds(3))).as("주석이 와야 명부에 오른 연결이다").isTrue();
            }

            assertThat(편집자_수(TestIds.STREAMER)).as("스트리머가 보면 A·B 두 명").isEqualTo(2);
            assertThat(편집자_수(편집자_A)).as("A가 보면 자기를 빼고 B 한 명").isEqualTo(1);
            assertThat(편집자_수(편집자_B)).as("B가 보면 자기를 빼고 A 한 명(두 탭이어도)").isEqualTo(1);
        } finally {
            readers.forEach(SseReader::close);
            // 브라우저가 닫아도 서버는 다음 쓰기가 실패해야 연결을 뺀다(최대 하트비트 한 주기). 그 사이 같은 컨텍스트의 다른 시험이
            // 연결 수 기준선을 재면 어긋난다. 종료를 보내 서버 쪽에서 닫고, 빠질 때까지 기다린다.
            registry.broadcastEnded(방송);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!registry.viewerIdsOf(방송).isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
        }
        // finally 밖에서 잰다: 위 본문이 이미 실패했으면 그 원인을 이 단언이 가리지 않게. 남은 연결은 같은 컨텍스트의 다른 시험의
        // 연결 수 기준선을 흔드므로, 그 시험이 아니라 여기(원인 자리)에서 실패하게 한다
        int left = registry.viewerIdsOf(방송).size();
        assertThat(left).as("10초 안에 연결이 안 빠졌다(남은 수 %d)", left).isZero();
    }

    private SseReader open(String user) {
        return new SseReader("http://localhost:" + port + "/api/clip/broadcasts/" + 방송 + "/events",
                Map.of("Authorization", "Bearer " + TestTokens.access(user)));
    }

    private int 편집자_수(String user) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/clip/broadcasts?state=live"))
                        .header("Authorization", "Bearer " + TestTokens.access(user)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode row = MAPPER.readTree(response.body()).get("broadcasts").get(0);
        assertThat(row.get("streamId").asString()).isEqualTo(방송);
        return row.get("editorsOnline").asInt();
    }
}
