package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.SseReader;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

/**
 * <b>연결을 여는 임계구역이 스냅샷 읽기까지 덮는가.</b>
 *
 * <p>스냅샷을 락 <b>밖에서</b> 읽으면 「읽은 뒤 ~ 명부에 오르기 전」 창이 열리고, 그 창에 지나간
 * 것은 <b>영구히</b> 유실된다(PR #109 봇 지적 ②, 2026-08-23 재현) —
 * <ul>
 *   <li>창에 커밋된 카드: {@code publish}는 연결이 명부에 없어 지나가고, {@code initialCards}에는
 *       그보다 먼저 읽혀서 없다. <b>재연결 전까지 그 카드를 못 본다</b></li>
 *   <li>창에 끝난 방송: 컨트롤러가 이미 읽은 상태가 {@code LIVE}라 {@code ended=false}로 열리고,
 *       {@code broadcastEnded}는 명부에 없어 지나간다. <b>연결이 토큰 만료까지 살아 있고</b>
 *       클라이언트는 방송이 끝난 줄 모른다(실측: 10초 뒤에도 안 닫힘, 표는 이미 {@code ended})</li>
 * </ul>
 *
 * <p><b>창을 벌리는 방법</b> — {@code snapshotsOf}를 spy로 감싸 <b>실제 조회가 끝난 뒤</b>
 * 사건을 일으킨다. 사건은 <b>다른 스레드</b>에서 시작하고 500ms를 잔다. 임계구역이 덮고 있으면
 * 그 스레드는 자물쇠에서 기다리다 우리가 끝난 뒤에 돌고, 안 덮고 있으면 500ms 안에 통째로
 * 끝난다. 「안 기다려서 통과」의 반대 방향이라 느린 기계에서 더 안전하다.
 *
 * <p>{@code snapshotsOf}가 {@code @Transactional(readOnly = true)}라 <b>같은 스레드에서 UPDATE를
 * 하면 안 된다</b> — PostgreSQL이 "cannot execute UPDATE in a read-only transaction"으로 거부한다
 * (재현 중 실측). 그래서 사건이 별도 스레드다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamOpenWindowTest extends IntegrationTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INTERNAL = "test-only-internal-token-32bytes-long!!";

    @MockitoSpyBean
    private JumpCardService service;

    private final int port;
    private final BroadcastRepository broadcasts;
    private final CardStreamRegistry registry;
    private final JdbcTemplate jdbc;

    StreamOpenWindowTest(@LocalServerPort int port, BroadcastRepository broadcasts,
                         CardStreamRegistry registry, JdbcTemplate jdbc) {
        this.port = port;
        this.broadcasts = broadcasts;
        this.registry = registry;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 정리() {
        방송과_카드를_비운다(jdbc);
    }

    @Test
    void 스냅샷을_읽은_뒤_연결이_열리는_사이에_카드가_커밋돼도_그_연결에_온다() {
        broadcasts.save(Broadcast.startedNow("s-win-card", "u-1", 701L, Instant.now(), null));
        post2A("s-win-card", "evt-before", 1_000_000L);

        AtomicInteger inWindowStatus = new AtomicInteger();
        열린_창에서("s-win-card", () -> {
            Thread writer = new Thread(() -> inWindowStatus.set(
                    post2A("s-win-card", "evt-in-window", 5_000_000L).statusCode()), "in-window-writer");
            writer.start();
            잠깐(500);
        });

        try (SseReader reader = open("s-win-card", TestTokens.access("win-card"))) {
            assertThat(reader.statusCode()).as("본문=%s", reader.body()).isEqualTo(200);
            awaitUntil(() -> 카드창시작(reader).contains(5_000_000L), Duration.ofSeconds(5));
            assertThat(카드창시작(reader))
                    .as("창에 커밋된 카드는 스냅샷에도 publish에도 없어 재연결 전까지 영영 안 온다")
                    .contains(1_000_000L, 5_000_000L);
        }
        assertThat(inWindowStatus.get()).as("창 안의 저장 자체는 성공해야 시험이 의미가 있다")
                .isIn(200, 201);
    }

    @Test
    void 스냅샷을_읽은_뒤_연결이_열리는_사이에_방송이_끝나도_ended를_받는다() {
        broadcasts.save(Broadcast.startedNow("s-win-end", "u-1", 702L, Instant.now(), null));
        post2A("s-win-end", "evt-1", 1_000_000L);

        열린_창에서("s-win-end", () -> {
            Thread ender = new Thread(() -> {
                jdbc.update("UPDATE broadcasts SET status = 'ended', ended_at = now() "
                        + "WHERE stream_id = 's-win-end'");
                registry.broadcastEnded("s-win-end");
            }, "in-window-ender");
            ender.start();
            잠깐(500);
        });

        try (SseReader reader = open("s-win-end", TestTokens.access("win-end"))) {
            assertThat(reader.statusCode()).as("본문=%s", reader.body()).isEqualTo(200);
            assertThat(reader.awaitName("ended", Duration.ofSeconds(5)))
                    .as("표는 ended인데 ended를 못 받으면 그 연결은 토큰 만료까지 살아 있고 "
                            + "클라이언트는 방송이 끝난 줄 모른다").isTrue();
            assertThat(reader.awaitClosed(Duration.ofSeconds(5))).isTrue();
        }
    }

    /**
     * {@code snapshotsOf}가 <b>실제 조회를 마친 뒤</b> {@code inside}를 돌게 한다.
     * 조회를 뒤로 미루면 안 된다 — 그러면 창 안에서 생긴 카드를 조회가 주워 담아
     * <b>고치기 전에도 초록</b>이 된다.
     */
    private void 열린_창에서(String streamId, Runnable inside) {
        doAnswer(invocation -> {
            Object real = invocation.callRealMethod();
            inside.run();
            return real;
        }).when(service).snapshotsOf(streamId);
    }

    private List<Long> 카드창시작(SseReader reader) {
        return reader.named().stream().filter(e -> "card".equals(e.name()))
                .map(e -> MAPPER.readTree(e.data()).get("window").get("startMs").asLong()).toList();
    }

    private HttpResponse<String> post2A(String streamId, String eventId, long start) {
        String body = """
                {"eventId":"%s","source":"auto","streamTimestampMs":%d,
                 "window":{"startMs":%d,"endMs":%d},"score":97}
                """.formatted(eventId, start + 23_000L, start, start + 42_000L);
        try {
            return HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port
                                    + "/internal/broadcasts/" + streamId + "/highlights"))
                            .header("X-Internal-Token", INTERNAL)
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(30))
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private SseReader open(String streamId, String token) {
        return new SseReader("http://localhost:" + port + "/api/clip/broadcasts/" + streamId + "/events",
                Map.of("Authorization", "Bearer " + token));
    }

    private void 잠깐(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return;
            }
            잠깐(50);
        }
    }
}
