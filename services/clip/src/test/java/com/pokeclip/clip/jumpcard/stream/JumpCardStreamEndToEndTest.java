package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.broadcast.BroadcastEventProcessor;
import com.pokeclip.clip.broadcast.Envelopes;
import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.jumpcard.JumpCardSnapshot;
import com.pokeclip.clip.jumpcard.api.HighlightRequest;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.SseReader;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 HTTP로 SSE를 연다. MockMvc로는 비동기 응답을 끝까지 흘려보내지 않아 이 갈래들이 안 보인다.
 *
 * <p>🔴 <b>시험마다 사용자 번호를 다르게 쓴다.</b> 같은 번호를 재사용하면 앞 시험이 닫은 연결의
 * 자리가 즉시 반납되지 않아(서버는 다음 쓰기가 실패해야 안다) 뒤 시험이 503을 맞는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JumpCardStreamEndToEndTest extends IntegrationTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int port;
    private final JumpCardService service;
    private final BroadcastRepository broadcasts;
    private final CardStreamRegistry registry;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final BroadcastEventProcessor processor;

    JumpCardStreamEndToEndTest(@LocalServerPort int port, JumpCardService service,
                               BroadcastRepository broadcasts, CardStreamRegistry registry, JdbcTemplate jdbc,
                               TransactionTemplate transactions, BroadcastEventProcessor processor) {
        this.port = port;
        this.service = service;
        this.broadcasts = broadcasts;
        this.registry = registry;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.processor = processor;
    }

    /** 카드를 남기면 다른 클래스의 broadcasts.deleteAllInBatch()가 FK로 죽는다. */
    @BeforeEach
    void 정리() {
        jdbc.update("DELETE FROM jump_cards");
        broadcasts.deleteAllInBatch();
        broadcasts.save(Broadcast.startedNow("s-1", "u-1", 1L, Instant.now(), null));
    }

    @Test
    void 연결_직후_그_방송_카드_전부가_순번_순으로_오고_숨긴_것은_hidden_true다() {
        long first = service.record("s-1", auto("evt-1", 1_000_000L)).card().id();
        service.record("s-1", auto("evt-2", 2_000_000L));
        service.hide(first, "u-9");   // 뒤에 바뀌었으니 순번이 뒤로 간다

        try (SseReader reader = open("s-1", TestTokens.access("e2e-snapshot"))) {
            assertThat(reader.statusCode()).isEqualTo(200);
            assertThat(reader.awaitNamed(2, Duration.ofSeconds(3))).as("PRD 기준이 3초다").isTrue();

            assertThat(reader.named()).extracting(SseReader.Event::name).containsExactly("card", "card");
            assertThat(reader.named()).extracting(e -> json(e).get("hidden").asBoolean())
                    .as("숨긴 것이 뒤에 바뀌었으니 순번 순으로는 뒤다").containsExactly(false, true);
            assertThat(reader.headers().firstValue("X-Accel-Buffering"))
                    .as("앞단 프록시가 모아 보내면 「3초 내 도착」이 깨진다").hasValue("no");
            assertThat(reader.headers().firstValue("Content-Type").orElse(""))
                    .startsWith("text/event-stream");
        }
    }

    /**
     * <b>카드가 0장인 방송에 붙어도 헤더가 바로 온다.</b>
     *
     * <p>{@code SseEmitter}는 첫 쓰기가 있어야 응답을 커밋한다. 카드가 없고 방송이 진행 중이면
     * 쓸 것이 없어 헤더가 <b>다음 하트비트까지</b> 늦는다(실기동 실측 5.449초, 최악 20초).
     * 브라우저에는 「느리다」가 아니라 <b>「연결이 안 된다」</b>로 보인다.
     * <b>방송이 막 시작해 카드가 아직 없을 때가 정확히 이 상태다.</b>
     *
     * <p>이 클래스는 하트비트가 운영 기본값(20초)이라 이 갈래가 실제로 드러난다 —
     * 하트비트를 짧게 준 컨텍스트에서는 우연히 통과한다.
     */
    @Test
    void 카드가_0장인_방송에_붙어도_헤더가_바로_온다() {
        broadcasts.save(Broadcast.startedNow("s-empty", "u-1", 3L, Instant.now(), null));
        assertThat(service.snapshotsOf("s-empty")).as("카드가 0장이어야 이 갈래를 잰다").isEmpty();

        long startedAt = System.nanoTime();
        try (SseReader reader = open("s-empty", TestTokens.access("t11-empty"))) {
            // SseReader 생성자가 헤더를 받을 때까지 막힌다 — 여기까지 온 시간이 곧 헤더 지연이다.
            Duration untilHeaders = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(reader.statusCode()).isEqualTo(200);
            assertThat(reader.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
            assertThat(untilHeaders)
                    .as("헤더가 하트비트까지 늦으면 브라우저는 연결 실패로 본다")
                    .isLessThan(Duration.ofSeconds(1));
        }
    }

    /** 따라잡기는 전체 스냅샷이다 — Last-Event-ID를 받아 적기만 하고 쓰지 않는다(PRD 결정). */
    @Test
    void 재연결하면_전부_다시_온다_Last_Event_ID는_무시한다() {
        service.record("s-1", auto("evt-1", 1_000_000L));
        service.record("s-1", auto("evt-2", 2_000_000L));

        try (SseReader reader = open("s-1", TestTokens.access("e2e-reconnect"),
                Map.of("Last-Event-ID", "999"))) {
            assertThat(reader.awaitNamed(2, Duration.ofSeconds(3))).isTrue();
            assertThat(reader.named()).hasSize(2);
        }
    }

    /**
     * 「연결이 닫히면 자리가 돌아온다」를 여기서 잰다 — 단위 시험에서는 콜백이 안 불려 잴 수 없다.
     *
     * <p>서버는 <b>다음 쓰기가 실패해야</b> 끊긴 것을 안다. 카드 한 장으로는 부족했고 여러 장을
     * 밀어야 반납됐다(plan-critic 실측: 끊고 1초=1, 1장=1, 30장=0). 그래서 카드를 밀어 반납을
     * 강제한 뒤 다시 연다.
     */
    @Test
    void 끊고_다시_열_수_있다() {
        // 전용 방송을 쓴다. connectionCount()는 서버 전체 수라 다른 시험이 열어 둔 연결이
        // 섞이면 "0이 된다"를 못 잰다 — 그 연결들은 다음 쓰기가 있어야 정리되기 때문이다.
        // 그래서 기준선을 재고, 이 시험이 연 자리 하나가 돌아오는 것만 본다.
        broadcasts.save(Broadcast.startedNow("s-reopen", "u-1", 2L, Instant.now(), null));
        JumpCardSnapshot card = service.record("s-reopen", auto("evt-drain", 100_000L)).card();

        int baseline = registry.connectionCount();
        String token = TestTokens.access("e2e-reopen");
        try (SseReader reader = open("s-reopen", token)) {
            assertThat(reader.statusCode()).isEqualTo(200);
            assertThat(reader.awaitNamed(1, Duration.ofSeconds(3))).isTrue();
        }
        assertThat(registry.connectionCount()).isEqualTo(baseline + 1);

        // 카드를 저장하는 것으로는 안 밀린다 — publishAfterCommit이 아직 비어 있다(태스크 10이 채운다).
        // 여기서 재는 것은 「쓰기가 실패하면 자리가 반납된다」이므로 출구를 직접 부른다.
        for (int i = 0; i < 30; i++) {
            registry.publish(card);
        }
        awaitUntil(() -> registry.connectionCount() == baseline, Duration.ofSeconds(10));

        try (SseReader again = open("s-reopen", token)) {
            assertThat(again.statusCode()).isEqualTo(200);
        }
    }

    @Test
    void 토큰_없음_401_서명_틀림_401() {
        try (SseReader none = new SseReader(url("s-1"), Map.of())) {
            assertThat(none.statusCode()).isEqualTo(401);
        }
        try (SseReader bad = open("s-1", TestTokens.tampered(TestTokens.access("e2e-badsig")))) {
            assertThat(bad.statusCode()).isEqualTo(401);
        }
    }

    @Test
    void 없는_방송은_404다() {
        try (SseReader reader = open("s-없음", TestTokens.access("e2e-nostream"))) {
            assertThat(reader.statusCode()).isEqualTo(404);
        }
    }

    @Test
    void 끝난_방송에_붙으면_스냅샷_뒤_ended가_오고_닫힌다() {
        broadcasts.save(Broadcast.endedPlaceholder("s-ended", "u-1", 9L, Instant.now()));
        service.record("s-ended", auto("evt-1", 1_000_000L));

        try (SseReader reader = open("s-ended", TestTokens.access("e2e-ended"))) {
            assertThat(reader.awaitNamed(2, Duration.ofSeconds(3))).isTrue();
            assertThat(reader.named()).extracting(SseReader.Event::name).containsExactly("card", "ended");
            assertThat(reader.awaitClosed(Duration.ofSeconds(3)))
                    .as("더 올 카드가 없는데 열어 두면 연결만 먹는다").isTrue();
        }
    }

    /**
     * 연결 수명 = min(설정값, 토큰 exp까지). 만료 시점에 닫히고 브라우저가 새 토큰으로 다시 붙는다.
     *
     * <p><b>카드를 하나 먼저 저장한다.</b> 보낼 것이 하나도 없으면 서버가 아무것도 안 써서
     * 응답 헤더가 안 나가고, 클라이언트는 헤더를 기다리다 타임아웃을 맞는다 — 그러면
     * {@code AsyncRequestTimeoutException}이 <b>본문 없는 503</b>으로 잡혀 200을 볼 수 없다(실측).
     * 운영에서는 하트비트(20초)가 헤더를 밀어내지만 여기선 토큰이 2초에 죽어 그 전에 끝난다.
     */
    @Test
    void 토큰_만료_시각에_연결이_닫힌다() {
        service.record("s-1", auto("evt-exp", 1_000_000L));
        String shortLived = TestTokens.access("e2e-exp", Instant.now().plusSeconds(2));

        try (SseReader reader = open("s-1", shortLived)) {
            assertThat(reader.statusCode()).as("본문=%s", reader.body()).isEqualTo(200);
            assertThat(reader.awaitNamed(1, Duration.ofSeconds(3))).isTrue();
            assertThat(reader.awaitClosed(Duration.ofSeconds(6)))
                    .as("만료 뒤에도 연결이 살면 죽은 토큰으로 계속 받는다").isTrue();
        }
    }

    // ── 커밋 뒤 발행 · 종료 알림 (태스크 10) ──────────────────────────────

    /** PRD 성공 기준이 3초다. {@code await}가 3초 안에 통과한 것이 아니라 <b>실제 시각차</b>를 잰다. */
    @Test
    void 카드를_넣으면_3초_안에_연결된_화면에_card가_온다() {
        try (SseReader reader = open("s-1", TestTokens.access("t10-live"))) {
            assertThat(reader.statusCode()).isEqualTo(200);
            서두를_틔운다(reader);

            Instant sent = Instant.now();
            post2A("s-1", "evt-live", 5_020_000L);

            assertThat(reader.awaitName("card", Duration.ofSeconds(3))).isTrue();
            SseReader.Event card = 마지막_card(reader);
            assertThat(Duration.between(sent, card.receivedAt()))
                    .as("보낸 시각과 받은 시각의 차가 성공 기준이다").isLessThan(Duration.ofSeconds(3));
            assertThat(MAPPER.readTree(card.data()).get("window").get("startMs").asLong())
                    .isEqualTo(5_020_000L);
        }
    }

    /** 점유는 남에게 보여야 의미가 있다 — 안 보이면 둘이 같은 카드를 잡는다. */
    @Test
    void 집으면_다른_연결에도_card가_온다() {
        long id = service.record("s-1", auto("evt-claim", 3_000_000L)).card().id();

        try (SseReader watcher = open("s-1", TestTokens.access("t10-watcher"))) {
            assertThat(watcher.awaitNamed(1, Duration.ofSeconds(3))).isTrue();
            int before = watcher.named().size();

            service.claim(id, "t10-claimer");

            assertThat(watcher.awaitName("card", Duration.ofSeconds(3))).isTrue();
            awaitUntil(() -> watcher.named().size() > before, Duration.ofSeconds(3));
            assertThat(MAPPER.readTree(마지막_card(watcher).data()).get("claimedBy").asString())
                    .isEqualTo("t10-claimer");
        }
    }

    /**
     * <b>놓기가 나갈 때 {@code claimedBy}가 비어 있어야 한다.</b> 네이티브 UPDATE 뒤 1차 캐시에
     * 낡은 엔티티가 남으면 「놓았는데 아직 잡혀 있는」 카드가 화면에 뜬다 —
     * {@code @Modifying(clearAutomatically = true)}가 그것을 막는다.
     * 태스크 5까지는 {@code publishAfterCommit}이 비어 있어 이 자리가 가려져 있었다.
     */
    @Test
    void 놓으면_비어_있는_카드가_나간다() {
        long id = service.record("s-1", auto("evt-release", 4_000_000L)).card().id();
        service.claim(id, "t10-owner");

        try (SseReader watcher = open("s-1", TestTokens.access("t10-release"))) {
            assertThat(watcher.awaitNamed(1, Duration.ofSeconds(3))).isTrue();
            int before = watcher.named().size();

            service.release(id, "t10-owner");

            awaitUntil(() -> watcher.named().size() > before, Duration.ofSeconds(3));
            assertThat(MAPPER.readTree(마지막_card(watcher).data()).get("claimedBy").isNull())
                    .as("놓았는데 잡힌 채로 나가면 아무도 그 카드를 못 집는다").isTrue();
        }
    }

    /**
     * 「안 온다」를 재기 전에 <b>같은 대기로 긍정 경로를 먼저 잰다</b> — 그래야 그 대기 시간이
     * 충분하다는 증거가 생긴다(async-test-reality 문항 4(가)).
     */
    @Test
    void 트랜잭션이_되감기면_발행되지_않는다() {
        try (SseReader reader = open("s-1", TestTokens.access("t10-rollback"))) {
            assertThat(reader.statusCode()).isEqualTo(200);
            서두를_틔운다(reader);

            // (가) 긍정 경로 — 커밋된 카드가 3초 안에 온다. 이것이 아래 3초 대기의 근거다.
            service.record("s-1", auto("evt-ok", 1_000_000L));
            awaitUntil(() -> 카드가_왔나(reader, 1_000_000L), Duration.ofSeconds(3));

            // (나) 되감기
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                service.record("s-1", auto("evt-rollback", 9_000_000L));
                throw new IllegalStateException("되감기");
            })).isInstanceOf(IllegalStateException.class);

            // 이벤트 개수를 세지 않는다 — 초기 스냅샷·하트비트가 섞여 흔들린다.
            // 「되감긴 그 카드가 왔는가」만 본다(문항 5).
            잠깐(3_000);
            assertThat(카드가_왔나(reader, 9_000_000L)).as("롤백된 카드가 화면에 나갔다").isFalse();
        }
        // 개수가 아니라 「그 카드가 없다」를 잰다 — 개수는 서두를 틔우며 만든 카드까지 세어
        // 시험 준비를 바꾸면 같이 흔들린다(문항 5).
        assertThat(service.snapshotsOf("s-1")).extracting(c -> c.window().startMs())
                .as("되감긴 카드가 표에 남았다").doesNotContain(9_000_000L)
                .as("같은 트랜잭션 밖의 성공한 카드까지 사라졌다").contains(1_000_000L);
    }

    @Test
    void 종료_편지가_처리되면_ended가_오고_닫힌다() {
        try (SseReader reader = open("s-1", TestTokens.access("t10-ended"))) {
            서두를_틔운다(reader);

            processor.process(Envelopes.ended("evt-end", "s-1", 2L));
            registry.broadcastEnded("s-1");

            assertThat(reader.awaitName("ended", Duration.ofSeconds(3))).isTrue();
            assertThat(reader.awaitClosed(Duration.ofSeconds(3))).isTrue();
        }
    }

    /**
     * 응답 헤더를 실제로 내보낸다. 보낼 것이 하나도 없으면 서버가 아무것도 안 써서 헤더가
     * 안 나가고 클라이언트가 헤더를 기다린 채 멈춘다(태스크 9 실측).
     */
    private void 서두를_틔운다(SseReader reader) {
        registry.publish(service.snapshotsOf("s-1").isEmpty()
                ? service.record("s-1", auto("evt-open", 500_000L)).card()
                : service.snapshotsOf("s-1").get(0));
        assertThat(reader.awaitNamed(1, Duration.ofSeconds(3))).isTrue();
    }

    /**
     * <b>이미 만료된 토큰으로는 통로가 안 열린다.</b> 디코더의 clock skew 허용치(기본 60초)
     * 안쪽 토큰은 인증을 통과해 컨트롤러까지 온다 — 그때 남은 수명이 음수이고,
     * 서블릿 규약상 {@code timeout <= 0}은 「시한 없음」이라 <b>만료된 토큰일수록 연결이 더
     * 오래 산다</b>(인가 2차 감사 실측: exp 59초 전 → timeout -59311ms → 45초 뒤에도 살아 있음).
     *
     * <p>기존 {@code 토큰_만료_시각에_연결이_닫힌다}는 exp가 <b>미래</b>인 토큰이라 이 창을 안 지난다.
     */
    @Test
    void 이미_만료된_토큰으로는_통로가_안_열리고_연결도_안_남는다() {
        service.record("s-1", auto("evt-expired", 2_000_000L));
        int before = registry.connectionCount();

        // skew 허용치(60초) 안쪽이라 인증은 통과한다 — 그래서 컨트롤러의 가드가 유일한 방어선이다.
        String expired = TestTokens.access("t10-expired", Instant.now().minusSeconds(30));

        try (SseReader reader = open("s-1", expired)) {
            assertThat(reader.statusCode()).as("본문=%s", reader.body()).isEqualTo(401);
        }
        assertThat(registry.connectionCount())
                .as("401인데 연결이 남으면 그 연결은 시한이 없어 영영 산다").isEqualTo(before);
    }

    /** 그 창의 카드가 화면에 도착했는가. 개수 대신 이것을 본다. */
    private boolean 카드가_왔나(SseReader reader, long windowStartMs) {
        return reader.events().stream()
                .filter(e -> "card".equals(e.name()) && e.data() != null && !e.data().isEmpty())
                .anyMatch(e -> MAPPER.readTree(e.data()).get("window").get("startMs").asLong() == windowStartMs);
    }

    private SseReader.Event 마지막_card(SseReader reader) {
        return reader.events().stream().filter(e -> "card".equals(e.name()))
                .reduce((a, b) -> b).orElseThrow();
    }

    private void post2A(String streamId, String eventId, long start) {
        String body = """
                {"eventId":"%s","source":"auto","streamTimestampMs":%d,
                 "window":{"startMs":%d,"endMs":%d},"score":97,"evidence":{"multiplier":4.2}}
                """.formatted(eventId, start + 23_000L, start, start + 42_000L);
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port
                                    + "/internal/broadcasts/" + streamId + "/highlights"))
                            .header("X-Internal-Token", "test-only-internal-token-32bytes-long!!")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(201);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void 잠깐(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private HighlightRequest auto(String eventId, long start) {
        return new HighlightRequest(eventId, "auto", start + 23_000L,
                new HighlightRequest.Window(start, start + 42_000L), 97,
                MAPPER.readTree("{\"multiplier\":4.2}"));
    }

    private JsonNode json(SseReader.Event event) {
        return MAPPER.readTree(event.data());
    }

    private String url(String streamId) {
        return "http://localhost:" + port + "/api/clip/broadcasts/" + streamId + "/events";
    }

    private SseReader open(String streamId, String token) {
        return open(streamId, token, Map.of());
    }

    private SseReader open(String streamId, String token, Map<String, String> extraHeaders) {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>(extraHeaders);
        headers.put("Authorization", "Bearer " + token);
        return new SseReader(url(streamId), headers);
    }

    private void awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(timeout + " 안에 조건이 참이 되지 않았다");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
