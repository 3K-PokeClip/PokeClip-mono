package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.jumpcard.api.HighlightRequest;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>안 읽는 구독자가 카드를 저장하는 요청을 잠그면 안 된다.</b>
 *
 * <p>이것이 POK-93에서 실제로 난 그림이다 — 커밋 뒤 처리가 요청 스레드를 붙들어 JDBC 커넥션을
 * 쥔 채 잠기고 풀이 고갈된다. 우리는 「{@code afterCommit}에서 <b>제출만</b> 한다」로 그것을
 * 피했다고 믿었는데, <b>제출 자체가 막히는 경로</b>가 남아 있었다: 큐가 차면 거부 처리기가
 * {@code execute()}를 부른 스레드(=요청 스레드)에서 돌고, 거기서 {@code completeWithError}가
 * 막힌 {@code send}의 {@code writeLock}을 기다린다. 실측 <b>59,164ms</b>(비동기 2차 감사).
 *
 * <p><b>안 읽는 구독자를 진짜로 만들려면 조건이 셋이다</b>(감사가 재현했다) —
 * ① 생 {@code Socket} ② {@code getInputStream()}을 <b>한 바이트도 안 읽음</b>
 * ③ 큰 페이로드를 짧은 간격으로. {@code HttpClient}·{@code SseReader}는 백그라운드로 계속 읽어서
 * 소켓 버퍼가 안 찬다 — 그래서 앞서 이 상황을 「재현 못 함」으로 적었던 것이 틀렸다.
 *
 * <p>단언이 <b>상한</b>(3초 안에 끝난다)이라 느린 기계에서 더 안전하고, 막힘이 스케줄러 운이
 * 아니라 <b>TCP가 강제</b>하는 것이라 「직렬로 돌아도 통과」하지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "pokeclip.jump-card.stream.stripes=1",
        "pokeclip.jump-card.stream.queue-capacity=5",
        "pokeclip.jump-card.stream.heartbeat=PT1S"
})
class SlowSubscriberBackpressureTest extends IntegrationTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INTERNAL = "test-only-internal-token-32bytes-long!!";

    private final int port;
    private final JumpCardService service;
    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;

    SlowSubscriberBackpressureTest(@LocalServerPort int port, JumpCardService service,
                                   BroadcastRepository broadcasts, JdbcTemplate jdbc) {
        this.port = port;
        this.service = service;
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 정리() {
        jdbc.update("DELETE FROM jump_cards");
        broadcasts.deleteAllInBatch();
        broadcasts.save(Broadcast.startedNow("s-slow", "u-1", 1L, Instant.now(), null));
    }

    @Test
    void 안_읽는_구독자가_있어도_카드_저장_요청은_바로_끝난다() throws Exception {
        // 큰 evidence — 소켓 버퍼를 빨리 채우려면 페이로드가 커야 한다.
        String big = "x".repeat(20_000);

        try (Socket socket = new Socket("localhost", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET /api/clip/broadcasts/s-slow/events HTTP/1.1\r\n"
                    + "Host: localhost:" + port + "\r\n"
                    + "Accept: text/event-stream\r\n"
                    + "Authorization: Bearer " + TestTokens.access("slow-subscriber") + "\r\n"
                    + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            // 여기서부터 getInputStream()을 한 번도 안 읽는다. 그것이 조건 ②다.
            Thread.sleep(500);

            // 전송 스레드를 소켓 쓰기에 세우고 큐(5)를 넘치게 만든다.
            //
            // 🔴 저장 하나하나를 다 잰다. 마지막 것만 재면 안 된다 — 잠기는 것은 「거부를 처음
            // 만난 저장」이고 그것이 채우는 도중일 수 있다. 그 뒤에는 write timeout이 이미 풀려
            // 마지막 요청이 빨라서, 마지막만 재는 시험은 결함을 넣어도 초록이다(실측: 클래스가
            // 71초 걸렸는데 시험은 통과했다).
            Duration worst = Duration.ZERO;
            String worstAt = "없음";
            for (int i = 0; i < 60; i++) {
                Instant each = Instant.now();
                service.record("s-slow", auto("evt-fill-" + i, 1_000_000L + i * 100_000L, big));
                Duration took = Duration.between(each, Instant.now());
                if (took.compareTo(worst) > 0) {
                    worst = took;
                    worstAt = "evt-fill-" + i;
                }
                Thread.sleep(20);
            }

            // 진짜 HTTP 2A도 한 번 — 컨트롤러·트랜잭션까지 같은 경로를 탄다.
            Instant started = Instant.now();
            HttpResponse<String> response = post2A("evt-measured", 90_000_000L, big);
            Duration overHttp = Duration.between(started, Instant.now());
            if (overHttp.compareTo(worst) > 0) {
                worst = overHttp;
                worstAt = "HTTP 2A";
            }

            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(worst)
                    .as("가장 오래 걸린 저장은 %s였다 — 거부 처리기가 요청 스레드에서 막힌 send의 "
                            + "락을 기다리면 여기서 수십 초가 나온다", worstAt)
                    .isLessThan(Duration.ofSeconds(3));
        }
    }

    private HighlightRequest auto(String eventId, long start, String big) {
        return new HighlightRequest(eventId, "auto", start + 23_000L,
                new HighlightRequest.Window(start, start + 42_000L), 97,
                MAPPER.readTree("{\"pad\":\"" + big + "\"}"));
    }

    private HttpResponse<String> post2A(String eventId, long start, String big) throws Exception {
        String body = """
                {"eventId":"%s","source":"auto","streamTimestampMs":%d,
                 "window":{"startMs":%d,"endMs":%d},"score":97,"evidence":{"pad":"%s"}}
                """.formatted(eventId, start + 23_000L, start, start + 42_000L, big);
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port
                                + "/internal/broadcasts/s-slow/highlights"))
                        .header("X-Internal-Token", INTERNAL)
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(90))
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
