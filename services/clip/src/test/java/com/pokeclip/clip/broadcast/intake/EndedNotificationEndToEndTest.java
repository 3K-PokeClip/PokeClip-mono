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

    EndedNotificationEndToEndTest(@LocalServerPort int port, BroadcastEventProcessor processor,
                                  BroadcastRepository broadcasts, BroadcastEventRepository events,
                                  JumpCardService service, CardStreamRegistry registry, JdbcTemplate jdbc) {
        this.port = port;
        this.processor = processor;
        this.broadcasts = broadcasts;
        this.events = events;
        this.service = service;
        this.registry = registry;
        this.jdbc = jdbc;
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

            assertThat(reader.await(1, Duration.ofSeconds(3)))
                    .as("초기 스냅샷이 서야 연결이 실제로 선 것이다").isTrue();

            // 실제 러너 경로. 리스너로 실물 Registry를 넘긴다 — 운영 배선과 같다.
            new SqsIntakeRunner(LocalStackFixture.client(), 큐_설정(queueUrl),
                    new IntakeStatus(true), processor, MAPPER, null, registry).pollOnce();

            assertThat(reader.awaitName("ended", Duration.ofSeconds(5)))
                    .as("편지 → 러너 → Registry → 브라우저가 이어지지 않았다").isTrue();
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
