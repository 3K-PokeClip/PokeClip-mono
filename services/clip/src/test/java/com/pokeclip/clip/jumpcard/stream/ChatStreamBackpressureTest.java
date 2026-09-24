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
import tools.jackson.databind.ObjectMapper;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>안 읽는 구독자가 채팅 줄을 막을 때</b>(main 결정 F4). 스트라이프 1·큐 20으로 줄이고, 안 읽는 구독자는
 * 생 {@code Socket}으로 한 바이트도 안 읽는다({@code SlowSubscriberBackpressureTest}가 재현한 조건 셋).
 *
 * <p><b>큐가 5가 아니라 20인 이유</b> — 하트비트 1초라 카드 줄에 ping이 초당 연결 수만큼 쌓인다. 막힌 A 몫 job이
 * 1초까지 기다리는 동안 카드 둘·ping 둘이 더 들어오면 큐 5는 경계에 걸려 <b>B의 카드가 큐 거부로</b> 사라질 수 있다 —
 * 그러면 이 시험은 건너뛰기가 아니라 큐 크기를 잰다. 채팅 줄은 거부가 날 때까지 밀기 때문에 큐 크기와 무관하다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "pokeclip.jump-card.stream.stripes=1",
        "pokeclip.jump-card.stream.queue-capacity=20",
        "pokeclip.jump-card.stream.heartbeat=PT1S"
})
class ChatStreamBackpressureTest extends IntegrationTestSupport {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamBackpressureTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RESOLVE = "/internal/editor-delegations/resolve";
    private static final String INTERNAL = "test-only-internal-token-32bytes-long!!";
    private static final String BIG = "x".repeat(20_000);
    private static final java.util.concurrent.atomic.AtomicLong CARD = new java.util.concurrent.atomic.AtomicLong();

    private final int port;
    private final BroadcastRepository broadcasts;
    private final CardStreamRegistry registry;
    private final JumpCardService service;
    private final JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newHttpClient();

    ChatStreamBackpressureTest(@LocalServerPort int port, BroadcastRepository broadcasts, CardStreamRegistry registry,
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
     * 큐가 차면 거부가 세어지고, 같은 채팅 줄의 <b>읽는</b> 구독자는 번호가 건너뛴다(남는 한계 — 문서에 적는다).
     * 화면은 그 빈틈을 보고 범위 창구로 메운다.
     *
     * <p>🔴 <b>빈틈은 줄을 풀고 장벽 번호가 온 뒤에 잰다</b>(감사 L19). 줄이 막힌 채로 「받은 수 &lt; 보낸 수」를 재면
     * B가 A 뒤에 서서 <b>아직 못 받은 것</b>만으로도 참이라, 버림이 없어도 초록이었다.
     */
    @Test
    void 채팅_큐가_차면_버린_수가_오르고_같은_줄_읽는_구독자의_번호가_건너뛴다() throws Exception {
        String streamId = liveBroadcast();
        long droppedBefore = registry.chatDroppedCount();
        try (Socket stuck = nonReadingSubscriber(streamId, "3301");
             SseReader reader = open(streamId, "3302")) {
            long sent = fillUntilDropped(streamId, droppedBefore);
            assertThat(registry.chatDroppedCount() - droppedBefore).as("양성 대조 — 채팅 거부가 실제로 났다").isPositive();

            stuck.close();   // 막힌 쓰기를 실패시켜 줄을 푼다
            long barrier = sent + 1;
            push(streamId, barrier);
            assertThat(await(() -> chatIds(reader).contains(barrier), Duration.ofSeconds(10)))
                    .as("줄이 풀린 뒤 장벽 번호가 와야 그 앞의 빈틈이 「아직」이 아니라 「버림」이다").isTrue();

            List<Long> ids = chatIds(reader);
            log.info("chat.e2e.backpressure sent={} dropped={} readerReceived={}",
                    barrier, registry.chatDroppedCount() - droppedBefore, ids.size());
            assertThat(ids).as("받은 것은 순서대로다").isSorted();
            assertThat((long) ids.size()).as("첫 번호부터 장벽까지 빠짐없이 왔다면 버림이 B에 안 닿은 것이다 — 빈틈이 있어야 한다")
                    .isLessThan(barrier - ids.getFirst() + 1);
        }
    }

    /**
     * 🔴 <b>잠깐 막혔다가 다시 읽는 연결은 닫히고 명부에서 빠진다</b>(감사 라운드 2 M1).
     *
     * <p>건너뛰기만 하면 그 연결은 살아서 막힌 사이의 카드를 영영 못 받는다 — 재연결이 없어 카드 목록 문이 발동하지
     * 않는다. 닫혀야 화면이 다시 붙어 목록 문으로 메운다. 양성 대조: A 몫 카드가 실제로 건너뛰어졌다.
     */
    @Test
    void 잠깐_막혔다_회복한_연결은_닫히고_명부에서_빠진다() throws Exception {
        String streamId = liveBroadcast();
        long droppedBefore = registry.chatDroppedCount();
        try (Socket a = nonReadingSubscriber(streamId, "3501")) {
            fillUntilDropped(streamId, droppedBefore);
            long skippedBefore = registry.stuckSkippedCount();
            recordCard(streamId);
            assertThat(await(() -> registry.stuckSkippedCount() > skippedBefore, Duration.ofSeconds(5)))
                    .as("양성 대조 — 막힌 A 몫 쓰기가 건너뛰어졌다").isTrue();
            assertThat(registry.hasConnections(streamId)).as("막힌 동안은 아직 명부에 있다").isTrue();

            RawDrain drain = new RawDrain(a);   // 이제 읽는다 = 회복
            assertThat(await(() -> !registry.hasConnections(streamId), Duration.ofSeconds(10)))
                    .as("회복한 연결이 명부에 남으면 막힌 사이 카드를 영영 못 받는다").isTrue();
            assertThat(await(drain::responseEnded, Duration.ofSeconds(10)))
                    .as("서버가 응답을 끝내야 EventSource가 다시 붙는다").isTrue();
        } finally {
            jdbc.update("DELETE FROM jump_cards WHERE stream_id = ?", streamId);
        }
    }

    /** M1 둘째 — 막힌 사이 종료 알림을 건너뛴 연결도 회복하면 닫히고 명부에서 빠진다(재연결이 ended 스냅샷을 받는다). */
    @Test
    void 막힌_사이_종료_알림을_건너뛴_연결도_회복하면_닫히고_명부에서_빠진다() throws Exception {
        String streamId = liveBroadcast();
        String barrierStream = liveBroadcast();
        long droppedBefore = registry.chatDroppedCount();
        try (Socket a = nonReadingSubscriber(streamId, "3601");
             SseReader b = open(streamId, "3602");
             SseReader other = open(barrierStream, "3603")) {
            fillUntilDropped(streamId, droppedBefore);
            assertThat(registry.chatDroppedCount() - droppedBefore).as("양성 대조 — A가 막혔다").isPositive();

            registry.broadcastEnded(streamId);
            assertThat(b.awaitName("ended", Duration.ofSeconds(10))).as("양성 대조 — B는 종료를 받았다").isTrue();
            // 장벽: 카드 줄은 스레드 하나라, 다른 방송의 카드가 왔다면 A 몫 종료 job도 이미 지나갔다
            recordCard(barrierStream);
            assertThat(awaitCards(other, 1, Duration.ofSeconds(10))).isTrue();
            assertThat(registry.hasConnections(streamId)).as("건너뛴 채 막혀 있는 동안은 명부에 있다").isTrue();

            RawDrain drain = new RawDrain(a);
            assertThat(await(() -> !registry.hasConnections(streamId), Duration.ofSeconds(10)))
                    .as("끝난 방송의 연결이 명부에 남으면 ping만 받으며 상한을 먹는다").isTrue();
            assertThat(await(drain::responseEnded, Duration.ofSeconds(10))).isTrue();
        } finally {
            jdbc.update("DELETE FROM jump_cards WHERE stream_id = ?", barrierStream);
        }
    }

    /**
     * 막힌 쓰기가 끝내 실패하는 연결(클라이언트가 떠났다)도 명부에서 빠진다. 🔴 <b>빼는 것은 우리 문이 아니라 {@code Job}의
     * {@code completeWithError}와 컨테이너 완료 콜백이다</b> — {@code WriteGate}의 닫기를 꺼도 초록이었다. 그 경로의 그물이다.
     */
    @Test
    void 막힌_채로_끊긴_연결은_명부에서_빠진다() throws Exception {
        String streamId = liveBroadcast();
        long droppedBefore = registry.chatDroppedCount();
        Socket a = nonReadingSubscriber(streamId, "3701");
        try {
            fillUntilDropped(streamId, droppedBefore);
            assertThat(registry.chatDroppedCount() - droppedBefore).as("양성 대조 — A가 막혔다").isPositive();
        } finally {
            a.close();
        }
        assertThat(await(() -> !registry.hasConnections(streamId), Duration.ofSeconds(10)))
                .as("죽은 연결이 명부에 남으면 상한을 먹는다").isTrue();
    }

    /**
     * 🔴 <b>채팅이 안 읽는 A의 쓰기를 막아도 같은 카드 스트라이프의 B는 카드를 3초 안에 받는다</b>(main 결정 · {@code WriteGate}).
     *
     * <p>채팅 줄을 가르는 것만으로는 이 시험이 약 65초 늦었다 — 소켓과 emitter 쓰기 자물쇠가 연결당 하나라, A 몫 카드
     * job이 카드 스레드에서 채팅이 쥔 자물쇠를 기다렸다.
     *
     * <p><b>카드를 두 장 낸다.</b> {@code conns}를 훑는 순서는 해시가 정해 A가 앞일지 B가 앞일지 모른다. 한 장이면
     * B가 앞일 때 B의 카드가 A 몫 job보다 먼저 나가 <b>고치기 전에도 초록</b>이다(고치기 전 한 장짜리로 5회 중 2회 초록 실측).
     * 두 장이면 어느 순서든 B의 둘째 카드가 A의 첫 job 뒤에 선다.
     */
    @Test
    void 채팅이_큐를_채워도_같은_스트라이프_카드는_간다() throws Exception {
        String streamId = liveBroadcast();
        long droppedBefore = registry.chatDroppedCount();
        long skippedBefore = registry.stuckSkippedCount();
        try (Socket stuck = nonReadingSubscriber(streamId, "3401");
             SseReader reader = open(streamId, "3402")) {
            fillUntilDropped(streamId, droppedBefore);
            assertThat(registry.chatDroppedCount() - droppedBefore)
                    .as("양성 대조 — 채팅 줄이 찼다 = 채팅 스레드가 A의 쓰기에서 안 돌아온다").isPositive();

            Instant recordedAt = Instant.now();
            recordCard(streamId);
            recordCard(streamId);

            boolean arrived = awaitCards(reader, 2, Duration.ofSeconds(3));
            log.info("chat.e2e.card_under_chat_backpressure arrived={} afterMs={} stuckSkipped={}", arrived,
                    Duration.between(recordedAt, Instant.now()).toMillis(), registry.stuckSkippedCount() - skippedBefore);
            assertThat(arrived).as("채팅이 A를 막았다고 같은 스트라이프의 B가 카드를 늦게 받으면 안 된다").isTrue();
            assertThat(registry.stuckSkippedCount() - skippedBefore)
                    .as("B가 받은 것은 A 몫을 건너뛰어서다 — 안 세어졌으면 A가 사실 안 막혀 있었다").isPositive();
        } finally {
            jdbc.update("DELETE FROM jump_cards WHERE stream_id = ?", streamId);
        }
    }

    // ------------------------------------------------------------------

    /** 큰 채팅을 거부가 날 때까지 민다. 고정 횟수가 아니라 관측할 사건(거부)을 본다. 보낸 번호 수를 돌려준다. */
    private long fillUntilDropped(String streamId, long droppedBefore) throws Exception {
        long seq = 0;
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (registry.chatDroppedCount() == droppedBefore && System.nanoTime() < deadline) {
            seq++;
            push(streamId, seq);
            Thread.sleep(5);
        }
        // 거부가 난 뒤 몇 건 더 — 읽는 구독자의 빈틈이 뒤에 생기게
        for (int i = 0; i < 5; i++) {
            seq++;
            push(streamId, seq);
        }
        return seq;
    }

    /** 창 시작을 매번 바꾼다 — 같은 창이면 {@code jumpcard.duplicate_skipped}로 한 장만 생긴다(한 번 그렇게 틀렸다). */
    private void recordCard(String streamId) throws Exception {
        long start = 1_000_000L + CARD.incrementAndGet() * 100_000L;
        service.record(streamId, new HighlightRequest("evt-bp-" + UUID.randomUUID(), "auto", start + 23_000L,
                new HighlightRequest.Window(start, start + 42_000L), 97, MAPPER.readTree("{}")));
    }

    /** 카드가 {@code count}장 올 때까지 폴링한다. 채팅도 같은 통로로 오므로 이름으로 센다. */
    private static boolean awaitCards(SseReader reader, int count, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (reader.named().stream().filter(e -> "card".equals(e.name())).count() >= count) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private static List<Long> chatIds(SseReader reader) {
        return reader.named().stream().filter(e -> "chat".equals(e.name())).map(e -> Long.parseLong(e.id())).toList();
    }

    private static boolean await(java.util.function.BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    /** 안 읽던 소켓을 이제부터 읽는다. 응답이 끝났는지(청크 끝 표식)만 본다. */
    static final class RawDrain {
        private final StringBuilder tail = new StringBuilder();

        RawDrain(Socket socket) {
            Thread.ofPlatform().daemon().name("bp-drain").start(() -> {
                byte[] chunk = new byte[65536];
                try {
                    java.io.InputStream in = socket.getInputStream();
                    int n;
                    while ((n = in.read(chunk)) >= 0) {
                        synchronized (tail) {
                            tail.append(new String(chunk, 0, n, StandardCharsets.ISO_8859_1));
                            if (tail.length() > 64) {
                                tail.delete(0, tail.length() - 64);
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // 시험이 끝나 소켓이 닫혔다
                }
            });
        }

        /** HTTP/1.1 청크 응답의 마지막 표식. 연결은 keep-alive라 EOF가 안 온다. */
        boolean responseEnded() {
            synchronized (tail) {
                return tail.toString().endsWith("\r\n0\r\n\r\n");
            }
        }
    }

    private Socket nonReadingSubscriber(String streamId, String userId) throws Exception {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
        OutputStream out = socket.getOutputStream();
        out.write(("GET /api/clip/broadcasts/" + streamId + "/events HTTP/1.1\r\n"
                + "Host: localhost:" + port + "\r\n"
                + "Accept: text/event-stream\r\n"
                + "Authorization: Bearer " + TestTokens.access(userId) + "\r\n"
                + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        // 한 바이트도 안 읽는다. 연결이 명부에 오를 때까지만 본다(고정 대기 대신).
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!registry.hasConnections(streamId) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(registry.hasConnections(streamId)).as("안 읽는 구독자가 명부에 올랐다").isTrue();
        return socket;
    }

    private String liveBroadcast() {
        String streamId = "chat-bp-" + UUID.randomUUID().toString().substring(0, 8);
        broadcasts.save(Broadcast.startedNow(streamId, TestIds.STREAMER, 1L, Instant.now(), null));
        return streamId;
    }

    private SseReader open(String streamId, String userId) {
        return new SseReader("http://localhost:" + port + "/api/clip/broadcasts/" + streamId + "/events",
                Map.of("Authorization", "Bearer " + TestTokens.access(userId)));
    }

    private void push(String streamId, long seq) throws Exception {
        String body = "{\"events\":[{\"seq\":" + seq + ",\"kind\":\"chat\",\"text\":\"" + BIG + "\"}]}";
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(
                                "http://localhost:" + port + "/internal/broadcasts/" + streamId + "/chat-events"))
                        .header("X-Internal-Token", INTERNAL).header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }
}
