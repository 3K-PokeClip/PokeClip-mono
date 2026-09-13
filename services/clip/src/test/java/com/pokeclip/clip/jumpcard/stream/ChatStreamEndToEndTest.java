package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.jumpcard.api.HighlightRequest;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.SseReader;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 채팅 중계를 <b>진짜 HTTP</b>로 잰다 — 수집기 자리에서 내부 문으로 밀고, 사람 자리에서 SSE로 받는다(POK-234 PR-B 태스크 19).
 * MockMvc는 비동기 응답을 끝까지 흘려보내지 않아 「data가 있는가」·「몇 초 안에 오나」가 안 보인다.
 *
 * <p>🔴 <b>상한을 올린다</b>(계획 검증 F10) — 운영 기본이 한 방송 50·한 사람 4(시험 50)라 연결 100이면 51번째부터 503이다.
 * 시험마다 방송 번호·사람 번호를 새로 쓴다(끊긴 자리는 다음 쓰기가 실패해야 반납된다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "pokeclip.jump-card.stream.max-per-stream=200",
        "pokeclip.jump-card.stream.max-per-user=200"
})
class ChatStreamEndToEndTest extends IntegrationTestSupport {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamEndToEndTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RESOLVE = "/internal/editor-delegations/resolve";
    private static final String INTERNAL = "test-only-internal-token-32bytes-long!!";
    private static final AtomicInteger USER = new AtomicInteger(3100);

    private final int port;
    private final BroadcastRepository broadcasts;
    private final CardStreamRegistry registry;
    private final JumpCardService service;
    private final JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newHttpClient();

    ChatStreamEndToEndTest(@LocalServerPort int port, BroadcastRepository broadcasts, CardStreamRegistry registry,
                           JumpCardService service, JdbcTemplate jdbc) {
        this.port = port;
        this.broadcasts = broadcasts;
        this.registry = registry;
        this.service = service;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 자격() {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");
    }

    /**
     * 🔴 {@code data}가 없는 이벤트는 브라우저가 <b>버린다</b>(WHATWG) — 시험 파서({@link SseReader})도 같은 규칙이라
     * 여기서 도착했다는 것이 곧 브라우저에서도 도착한다는 뜻이다. 시각은 <b>보낸 직후 ~ 받은 시각</b> 차로 잰다(문항 4(나)).
     */
    @Test
    void 채팅_이벤트가_id와_data를_싣고_1초_안에_온다() throws Exception {
        String streamId = liveBroadcast();
        try (SseReader reader = open(streamId)) {
            assertThat(reader.statusCode()).isEqualTo(200);

            Instant postedAt = Instant.now();
            HttpResponse<String> response = push(streamId, chat(1, "첫채팅"));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(MAPPER.readTree(response.body()).get("accepted").asInt()).isEqualTo(1);

            assertThat(reader.awaitNamed(1, Duration.ofSeconds(3))).as("3초 안에 한 건도 안 왔다").isTrue();
            SseReader.Event event = reader.named().getFirst();
            assertThat(event.name()).isEqualTo("chat");
            assertThat(event.id()).isEqualTo("1");
            JsonNode data = MAPPER.readTree(event.data());
            assertThat(data.get("seq").asLong()).isEqualTo(1);
            assertThat(data.get("text").asString()).isEqualTo("첫채팅");
            Duration latency = Duration.between(postedAt, event.receivedAt());
            log.info("chat.e2e.single_latency_ms={}", latency.toMillis());
            assertThat(latency).as("보낸 뒤 받기까지").isLessThan(Duration.ofSeconds(1));
        }
    }

    @Test
    void 다른_방송_연결에는_안_간다() throws Exception {
        String mine = liveBroadcast();
        String other = liveBroadcast();
        try (SseReader a = open(mine); SseReader b = open(other)) {
            push(mine, chat(1, "내방송"));
            push(other, chat(1, "표지"));   // 장벽: b가 자기 것을 받은 뒤에 본다

            assertThat(a.awaitNamed(1, Duration.ofSeconds(3))).isTrue();
            assertThat(b.awaitNamed(1, Duration.ofSeconds(3))).isTrue();
            assertThat(b.named()).extracting(e -> MAPPER.readTree(e.data()).get("text").asString())
                    .as("남의 방송 채팅이 새면 편집자가 다른 방송 반응을 본다").containsExactly("표지");
        }
    }

    /**
     * 동시 연결 100 · 초당 50건 · 5초. <b>로컬 참고값</b>이다 — 단언은 느린 기계를 위해 3초로 느슨하게 두고
     * p50·p95·최대를 로그로 남긴다(정본 측정은 실기동). 전부 읽는 구독자라 버림이 없어야 한다.
     */
    @Test
    void 동시_연결_100에_초당_50건을_밀면_전부_오고_p95를_남긴다() throws Exception {
        int connections = 100;
        int perSecond = 50;
        int seconds = 5;
        int total = perSecond * seconds;
        String streamA = liveBroadcast();
        String streamB = liveBroadcast();
        long droppedBefore = registry.chatDroppedCount();
        List<SseReader> readers = new ArrayList<>();
        Map<Long, Instant> postedAt = new ConcurrentHashMap<>();
        try {
            for (int i = 0; i < connections; i++) {
                readers.add(open(i % 2 == 0 ? streamA : streamB));
            }
            assertThat(readers).allMatch(r -> r.statusCode() == 200);

            long seq = 0;
            for (int tick = 0; tick < seconds * 10; tick++) {
                long tickStart = System.nanoTime();
                StringBuilder events = new StringBuilder();
                for (int k = 0; k < perSecond / 10; k++) {
                    seq++;
                    if (events.length() > 0) events.append(',');
                    events.append(eventJson(seq, "부하" + seq));
                }
                String body = "{\"events\":[" + events + "]}";
                Instant now = Instant.now();
                for (long s = seq - perSecond / 10 + 1; s <= seq; s++) postedAt.put(s, now);
                assertThat(pushRaw(streamA, body).statusCode()).isEqualTo(200);
                assertThat(pushRaw(streamB, body).statusCode()).isEqualTo(200);
                long sleepNanos = Duration.ofMillis(100).toNanos() - (System.nanoTime() - tickStart);
                if (sleepNanos > 0) Thread.sleep(sleepNanos / 1_000_000, (int) (sleepNanos % 1_000_000));
            }

            for (SseReader reader : readers) {
                assertThat(reader.awaitNamed(total, Duration.ofSeconds(15))).as("모든 연결이 %d건을 다 받아야 한다", total).isTrue();
            }
            List<Long> latencies = new ArrayList<>();
            for (SseReader reader : readers) {
                for (SseReader.Event e : reader.named()) {
                    latencies.add(Duration.between(postedAt.get(Long.parseLong(e.id())), e.receivedAt()).toMillis());
                }
            }
            latencies.sort(Long::compare);
            long p50 = latencies.get(latencies.size() / 2);
            long p95 = latencies.get((int) Math.ceil(latencies.size() * 0.95) - 1);
            long max = latencies.getLast();
            log.info("chat.e2e.load connections={} events={} samples={} p50Ms={} p95Ms={} maxMs={}",
                    connections, total * 2, latencies.size(), p50, p95, max);
            assertThat(latencies).hasSize(connections * total);
            assertThat(registry.chatDroppedCount() - droppedBefore).as("전부 읽는 구독자면 버림이 없다").isZero();
            assertThat(p95).as("로컬 참고 상한 — 정본 1초는 실기동에서 잰다. 측정 p50=%d max=%d", p50, max)
                    .isLessThan(3_000L);
        } finally {
            readers.forEach(SseReader::close);
        }
    }

    /**
     * 🔴 <b>멀쩡한 연결은 채팅 폭주 중에도 카드를 하나도 안 잃는다</b> — 막힌 연결 건너뛰기({@code WriteGate})의 반대편 그물.
     *
     * <p>「쓰는 중이면 건너뜀」으로 짜면 채팅이 초당 수백 건일 때 카드 job 순간에 채팅 쓰기가 겹쳐 <b>읽고 있는 연결</b>이
     * 카드를 잃는다. 그래서 「1초 넘게 쓰는 중」만 막힌 것으로 보고, 아니면 채팅 쓰기가 끝나기를 기다린다.
     *
     * <p>폭주가 <b>흐르기 시작한 것을 확인한 뒤에</b> 카드를 넣는다(문항 4(다)). 카드 구간 동안의 채팅 수신 속도를 같이
     * 단언한다 — 카드를 넣는 동안 채팅이 멈춰 있었으면 이 시험은 아무것도 안 겹친다.
     */
    @Test
    void 멀쩡한_연결은_채팅_폭주_중에도_카드를_하나도_안_잃는다() throws Exception {
        int cards = 20;
        int perRequest = 50;
        String streamId = liveBroadcast();
        long skippedBefore = registry.stuckSkippedCount();
        long droppedBefore = registry.chatDroppedCount();
        AtomicBoolean flooding = new AtomicBoolean(true);
        AtomicReference<Throwable> pusherFailure = new AtomicReference<>();
        Thread pusher = null;
        try (SseReader reader = open(streamId)) {
            assertThat(reader.statusCode()).isEqualTo(200);
            String text = "폭주".repeat(200);
            pusher = Thread.ofPlatform().name("chat-flood").start(() -> {
                long seq = 0;
                try {
                    while (flooding.get()) {
                        StringBuilder events = new StringBuilder();
                        for (int k = 0; k < perRequest; k++) {
                            if (k > 0) events.append(',');
                            events.append(eventJson(++seq, text));
                        }
                        pushRaw(streamId, "{\"events\":[" + events + "]}");
                    }
                } catch (Throwable t) {
                    pusherFailure.set(t);
                }
            });
            assertThat(awaitCount(reader, "chat", 1_000, Duration.ofSeconds(10))).as("폭주가 안 흐른다").isTrue();

            long chatAtStart = count(reader, "chat");
            long startNanos = System.nanoTime();
            for (int i = 0; i < cards; i++) {
                service.record(streamId, new HighlightRequest("evt-flood-" + i + "-" + UUID.randomUUID(), "auto",
                        1_023_000L + i, new HighlightRequest.Window(1_000_000L + i, 1_042_000L + i), 97,
                        MAPPER.readTree("{}")));
            }
            // 카드 job은 record 안(afterCommit)에서 이미 줄에 들어갔다. 폭주는 여기서 멈춘다 — 카드를 기다리는 동안
            // 계속 밀면 받는 쪽이 이벤트를 전부 쥐고 있어 실패 갈래에서 시험 JVM이 OutOfMemoryError로 죽는다(주입 I2에서 실제로 죽었다).
            double seconds = (System.nanoTime() - startNanos) / 1e9;
            long chatDuring = count(reader, "chat") - chatAtStart;
            flooding.set(false);
            pusher.join(10_000);
            boolean allCards = awaitCount(reader, "card", cards, Duration.ofSeconds(3));

            log.info("chat.e2e.flood cards={} received={} chatDuring={} seconds={} chatPerSec={} stuckSkipped={}",
                    cards, count(reader, "card"), chatDuring, String.format("%.3f", seconds),
                    Math.round(chatDuring / seconds), registry.stuckSkippedCount() - skippedBefore);
            assertThat(pusherFailure.get()).as("밀던 스레드가 죽었다").isNull();
            assertThat(chatDuring / seconds).as("양성 대조 — 카드를 넣는 동안 채팅이 초당 수백 건 흘렀다").isGreaterThan(200);
            assertThat(allCards).as("멀쩡한 연결이 카드를 잃었다 — 받은 %d장", count(reader, "card")).isTrue();
            assertThat(count(reader, "card")).isEqualTo(cards);
            assertThat(registry.stuckSkippedCount() - skippedBefore).as("멀쩡한 연결은 한 번도 안 건너뛴다").isZero();
            assertThat(registry.chatDroppedCount() - droppedBefore).as("읽는 구독자라 채팅 거부도 없다").isZero();
        } finally {
            flooding.set(false);
            if (pusher != null) pusher.join(10_000);
            jdbc.update("DELETE FROM jump_cards WHERE stream_id = ?", streamId);
        }
    }

    // ------------------------------------------------------------------

    private static long count(SseReader reader, String name) {
        return reader.named().stream().filter(e -> name.equals(e.name())).count();
    }

    private static boolean awaitCount(SseReader reader, String name, long atLeast, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (count(reader, name) >= atLeast) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private String liveBroadcast() {
        String streamId = "chat-e2e-" + UUID.randomUUID().toString().substring(0, 8);
        broadcasts.save(Broadcast.startedNow(streamId, TestIds.STREAMER, 1L, Instant.now(), null));
        return streamId;
    }

    private SseReader open(String streamId) {
        return new SseReader("http://localhost:" + port + "/api/clip/broadcasts/" + streamId + "/events",
                Map.of("Authorization", "Bearer " + TestTokens.access(Integer.toString(USER.incrementAndGet()))));
    }

    private HttpResponse<String> push(String streamId, String event) throws Exception {
        return pushRaw(streamId, "{\"events\":[" + event + "]}");
    }

    private HttpResponse<String> pushRaw(String streamId, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(
                                "http://localhost:" + port + "/internal/broadcasts/" + streamId + "/chat-events"))
                        .header("X-Internal-Token", INTERNAL)
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String chat(long seq, String text) {
        return eventJson(seq, text);
    }

    private static String eventJson(long seq, String text) {
        return "{\"seq\":" + seq + ",\"seqEpoch\":1757000000000,\"kind\":\"chat\",\"time\":\"2026-09-03T15:00:01Z\","
                + "\"timeBasis\":\"message\",\"nickname\":\"n\",\"senderChannelId\":\"u\",\"role\":null,"
                + "\"text\":\"" + text + "\",\"amount\":null,\"donationType\":null}";
    }
}
