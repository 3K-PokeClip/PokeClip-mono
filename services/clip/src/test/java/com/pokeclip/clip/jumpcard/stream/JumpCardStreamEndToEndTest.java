package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

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

    JumpCardStreamEndToEndTest(@LocalServerPort int port, JumpCardService service,
                               BroadcastRepository broadcasts, CardStreamRegistry registry, JdbcTemplate jdbc) {
        this.port = port;
        this.service = service;
        this.broadcasts = broadcasts;
        this.registry = registry;
        this.jdbc = jdbc;
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
            assertThat(reader.await(2, Duration.ofSeconds(3))).as("PRD 기준이 3초다").isTrue();

            assertThat(reader.events()).extracting(SseReader.Event::name).containsExactly("card", "card");
            assertThat(reader.events()).extracting(e -> json(e).get("hidden").asBoolean())
                    .as("숨긴 것이 뒤에 바뀌었으니 순번 순으로는 뒤다").containsExactly(false, true);
            assertThat(reader.headers().firstValue("X-Accel-Buffering"))
                    .as("앞단 프록시가 모아 보내면 「3초 내 도착」이 깨진다").hasValue("no");
            assertThat(reader.headers().firstValue("Content-Type").orElse(""))
                    .startsWith("text/event-stream");
        }
    }

    /** 따라잡기는 전체 스냅샷이다 — Last-Event-ID를 받아 적기만 하고 쓰지 않는다(PRD 결정). */
    @Test
    void 재연결하면_전부_다시_온다_Last_Event_ID는_무시한다() {
        service.record("s-1", auto("evt-1", 1_000_000L));
        service.record("s-1", auto("evt-2", 2_000_000L));

        try (SseReader reader = open("s-1", TestTokens.access("e2e-reconnect"),
                Map.of("Last-Event-ID", "999"))) {
            assertThat(reader.await(2, Duration.ofSeconds(3))).isTrue();
            assertThat(reader.events()).hasSize(2);
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
            assertThat(reader.await(1, Duration.ofSeconds(3))).isTrue();
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
            assertThat(reader.await(2, Duration.ofSeconds(3))).isTrue();
            assertThat(reader.events()).extracting(SseReader.Event::name).containsExactly("card", "ended");
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
            assertThat(reader.await(1, Duration.ofSeconds(3))).isTrue();
            assertThat(reader.awaitClosed(Duration.ofSeconds(6)))
                    .as("만료 뒤에도 연결이 살면 죽은 토큰으로 계속 받는다").isTrue();
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
