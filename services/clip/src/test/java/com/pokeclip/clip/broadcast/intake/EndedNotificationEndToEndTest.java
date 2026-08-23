package com.pokeclip.clip.broadcast.intake;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastEventProcessor;
import com.pokeclip.clip.broadcast.BroadcastEventRepository;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.jumpcard.api.HighlightRequest;
import com.pokeclip.clip.jumpcard.stream.CardStreamRegistry;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.LocalStackFixture;
import com.pokeclip.clip.support.SseReader;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>진짜 큐에 넣은 종료 편지가 브라우저까지 닿는가.</b>
 *
 * <p>조각을 따로만 재면 이어지는지는 아무도 안 잰다 — {@code JumpCardStreamEndToEndTest}는
 * {@code broadcastEnded}를 직접 부르고 {@code SqsIntakeRunnerTest}는 러너가 리스너를 부르는지만
 * 본다. <b>러너 생성자에 리스너를 안 넘겨도 둘 다 초록이다.</b> 그 사이를 여기서 관통한다.
 *
 * <p>이 클래스가 {@code broadcast.intake} 패키지에 있는 이유: {@code SqsIntakeRunner}가
 * <b>package-private</b>이라 바깥 패키지에서 못 만든다. 러너를 public으로 열지 않는 것이
 * 계획의 결정이므로 시험을 러너 쪽으로 옮겼다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndedNotificationEndToEndTest extends IntegrationTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int port;
    private final BroadcastEventProcessor processor;
    private final BroadcastRepository broadcasts;
    private final BroadcastEventRepository events;
    private final JumpCardService service;
    private final CardStreamRegistry registry;
    private final JdbcTemplate jdbc;
    private final SqsIntakeRunner runner;

    EndedNotificationEndToEndTest(@LocalServerPort int port, BroadcastEventProcessor processor,
                                  BroadcastRepository broadcasts, BroadcastEventRepository events,
                                  JumpCardService service, CardStreamRegistry registry, JdbcTemplate jdbc,
                                  SqsIntakeRunner runner) {
        this.port = port;
        this.processor = processor;
        this.broadcasts = broadcasts;
        this.events = events;
        this.service = service;
        this.registry = registry;
        this.jdbc = jdbc;
        this.runner = runner;
    }

    @BeforeEach
    void 정리() {
        jdbc.update("DELETE FROM jump_cards");
        events.deleteAllInBatch();
        broadcasts.deleteAllInBatch();
        broadcasts.save(Broadcast.startedNow("s-queue", "u-1", 1L, Instant.now(), null));
    }

    @Test
    void 진짜_큐에_넣은_종료_편지가_브라우저까지_닿는다() {
        service.record("s-queue", auto("evt-card", 1_000_000L));

        String queueUrl = LocalStackFixture.createFifoQueue("broadcast-ended-e2e.fifo");
        LocalStackFixture.sendRaw(queueUrl, 종료_편지("evt-q-end", "s-queue", 2L), "s-queue", "evt-q-end");

        try (SseReader reader = new SseReader(
                "http://localhost:" + port + "/api/clip/broadcasts/s-queue/events",
                Map.of("Authorization", "Bearer " + TestTokens.access("t10-queue")))) {

            assertThat(reader.awaitNamed(1, Duration.ofSeconds(3)))
                    .as("초기 스냅샷이 서야 연결이 실제로 선 것이다").isTrue();

            // 실제 러너 경로. 다만 리스너를 <b>손수 넘긴다</b> — 이 줄은 「편지 → 러너 → Registry →
            // 브라우저」가 이어지는 것만 증명하고, <b>운영 배선이 닿았는지는 증명하지 않는다.</b>
            // 그쪽은 아래 `운영_배선이_리스너를_받는다`가 잰다(둘이 있어야 이음매가 닫힌다).
            new SqsIntakeRunner(LocalStackFixture.client(), 큐_설정(queueUrl),
                    new IntakeStatus(true), processor, MAPPER, null, registry).pollOnce();

            assertThat(reader.awaitName("ended", Duration.ofSeconds(5)))
                    .as("편지 → 러너 → Registry → 브라우저가 이어지지 않았다").isTrue();
            assertThat(reader.awaitClosed(Duration.ofSeconds(3))).isTrue();
        }
    }

    /**
     * <b>운영 배선이 실제로 리스너를 받는지</b>를 잰다 — 위 관통 시험은 러너를 손수 만들어
     * 리스너를 넘기므로 이 자리를 못 본다. 실제로 {@code @Autowired} 생성자의
     * {@code endedListenerProvider.getIfAvailable()}을 null로 바꿔도 <b>177건이 전부 초록이었다</b>
     * (비동기 2차 감사) — 운영에서 방송 종료 알림이 통째로 죽어도 신호가 없었다는 뜻이다.
     *
     * <p>{@code hasQueueClient()}에 켜짐·꺼짐 대조 둘이 있는 것과 같은 이유로 둔다.
     */
    @Test
    void 운영_배선이_리스너를_받는다() {
        assertThat(runner.hasEndedListener())
                .as("이 자리가 비면 방송 종료 알림이 통째로 죽는데 아무 시험도 안 깨진다")
                .isTrue();
    }

    /**
     * <b>큐를 못 지운 것이 브라우저까지 번지면 안 된다.</b> 위 관통 시험은 정상 경로만 보므로
     * 이 자리를 못 본다 — 재현에서는 명부가 {@code ENDED}로 커밋됐는데도 붙어 있던 연결이
     * {@code ended}를 못 받고 <b>자리를 문 채 남았다</b>({@code connectionCount=1}, 8.07초까지 확인).
     */
    @Test
    void 삭제가_실패해도_붙어_있던_연결이_종료를_받고_닫힌다() {
        // 카드를 하나 둔다 — 카드가 0장이면 연결 직후 나가는 것이 주석("ok") 하나뿐이라
        // awaitNamed(1)로는 「연결이 섰다」를 못 잰다(이 시험을 처음 쓸 때 그렇게 헛짚었다).
        service.record("s-queue", auto("evt-card-del", 2_000_000L));

        try (SseReader reader = new SseReader(
                "http://localhost:" + port + "/api/clip/broadcasts/s-queue/events",
                Map.of("Authorization", "Bearer " + TestTokens.access("t10-delete-fail")))) {

            assertThat(reader.awaitNamed(1, Duration.ofSeconds(3)))
                    .as("초기 스냅샷이 서야 연결이 실제로 선 것이다").isTrue();

            FakeSqsClient sqs = FakeSqsClient.thatFailsOnDelete(종료_편지("evt-del", "s-queue", 2L));
            new SqsIntakeRunner(sqs, 큐_설정("http://localhost:4566/000000000000/unused.fifo"),
                    new IntakeStatus(true), processor, MAPPER, null, registry).pollOnce();

            assertThat(sqs.deletedReceiptHandles()).as("삭제는 실패한 채여야 이 갈래를 잰 것이다").isEmpty();
            assertThat(reader.awaitName("ended", Duration.ofSeconds(5)))
                    .as("편지를 못 지웠다고 화면이 끝난 방송에 남으면 안 된다").isTrue();
            assertThat(reader.awaitClosed(Duration.ofSeconds(3))).isTrue();
        }
    }

    private IntakeProperties 큐_설정(String queueUrl) {
        return new IntakeProperties(true, queueUrl, LocalStackFixture.region(),
                LocalStackFixture.endpoint(), Duration.ofSeconds(5), 10);
    }

    private HighlightRequest auto(String eventId, long start) {
        return new HighlightRequest(eventId, "auto", start + 23_000L,
                new HighlightRequest.Window(start, start + 42_000L), 97,
                MAPPER.readTree("{\"multiplier\":4.2}"));
    }

    private static String 종료_편지(String eventId, String streamId, long sequence) {
        return """
                {"schemaVersion":1,"eventId":"%s","eventType":"broadcast.ended",
                 "occurredAt":"2026-08-23T01:00:00Z","streamId":"%s","streamerId":"u-1",
                 "sequence":%d,"traceId":"trace-1","payload":{}}
                """.formatted(eventId, streamId, sequence);
    }
}
