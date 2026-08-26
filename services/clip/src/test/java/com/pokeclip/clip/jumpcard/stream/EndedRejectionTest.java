package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.jumpcard.api.HighlightRequest;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.SseReader;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>큐가 차서 {@code ended}를 못 보냈으면 그 자리를 즉시 회수해야 한다.</b>
 *
 * <p>{@code ThreadPoolExecutor}의 거부 처리기는 <b>예외를 던지지 않는다</b> — 로그만 남기고
 * 정상 복귀한다. 그래서 {@code broadcastEnded}가 성공처럼 보이고, SQS 러너의
 * {@code notifyEnded}는 {@code RuntimeException}만 잡으므로 그 다음 줄 {@code delete}가
 * 그대로 돌아 <b>편지가 사라진다</b>. 재전달로도 못 푼다(두 번째는 {@code DUPLICATE}라
 * 알림 가드를 못 지난다).
 *
 * <p>2026-08-23 재현(PR #112 봇 지적 ②): {@code broadcastEnded}가 <b>0ms에 예외 없이</b>
 * 반환하고 {@code rejected} 로그가 27→28로 정확히 1건 늘었으며, 그 연결은
 * <b>60초 관찰 내내 자리를 물고 있었다.</b> 그동안 하트비트도 같은 큐에서 거부돼
 * <b>회복 계기 자체가 큐에 못 들어갔다.</b>
 *
 * <p><b>emitter는 건드리지 않는다.</b> {@code complete()}도 {@code completeWithError()}와
 * <b>같은 {@code writeLock}</b>을 잡아(spring-webmvc 7.0.8 소스 확인), 막힌 {@code send}가
 * 그 락을 쥔 상태에서 부르면 같이 잠긴다 — 1판에서 요청 스레드가 59,164ms 잠긴 그림이다.
 * 자리만 빼고 emitter는 시한이 될 때까지 둔다. 클라이언트는 재연결 때 스냅샷에서
 * {@code ended}를 받는다.
 *
 * <p><b>안 읽는 구독자를 진짜로 만드는 조건 셋</b>({@code SlowSubscriberBackpressureTest}와 같다) —
 * ① 생 {@code Socket} ② {@code getInputStream()}을 한 바이트도 안 읽음 ③ 큰 페이로드.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "pokeclip.jump-card.stream.stripes=1",
        "pokeclip.jump-card.stream.queue-capacity=5",
        "pokeclip.jump-card.stream.heartbeat=PT1S"
})
class EndedRejectionTest extends IntegrationTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int port;
    private final JumpCardService service;
    private final BroadcastRepository broadcasts;
    private final CardStreamRegistry registry;
    private final JdbcTemplate jdbc;

    EndedRejectionTest(@LocalServerPort int port, JumpCardService service, BroadcastRepository broadcasts,
                       CardStreamRegistry registry, JdbcTemplate jdbc) {
        this.port = port;
        this.service = service;
        this.broadcasts = broadcasts;
        this.registry = registry;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 정리() {
        방송과_카드를_비운다(jdbc);
    }

    @Test
    void 큐가_차서_ended를_못_보내면_자리를_바로_회수한다() throws Exception {
        broadcasts.save(Broadcast.startedNow("s-full", TestIds.STREAMER, 1L, Instant.now(), null));
        String big = "x".repeat(20_000);

        try (LogCaptor logs = new LogCaptor();
             Socket socket = new Socket("localhost", port)) {
            안_읽는_연결을_연다(socket, "s-full", "1901");
            awaitUntil(() -> registry.connectionCount() == 1, Duration.ofSeconds(3),
                    "연결이 명부에 안 올랐다");

            // 전송 스레드를 소켓 쓰기에 세우고 큐(5)를 넘치게 만든다.
            for (int i = 0; i < 60; i++) {
                service.record("s-full", auto("evt-fill-" + i, 1_000_000L + i * 100_000L, big));
                Thread.sleep(10);
            }
            assertThat(logs.messages()).as("큐가 안 찼으면 이 시험은 아무것도 안 잰다")
                    .anyMatch(m -> m.startsWith("jumpcard.stream.rejected"));

            long rejectedBefore = 거부_건수(logs);
            registry.broadcastEnded("s-full");

            assertThat(registry.connectionCount())
                    .as("ended를 못 보냈는데 자리가 남으면 상한만 먹고 통로는 죽어 있다")
                    .isZero();
            assertThat(logs.messages()).as("조용히 사라지면 「왜 화면이 안 닫혔나」를 추적할 수 없다")
                    .anyMatch(m -> m.startsWith("jumpcard.stream.ended_dropped"));
            assertThat(거부_건수(logs)).as("거부는 실제로 일어나야 한다 — 안 났으면 다른 이유로 초록이다")
                    .isEqualTo(rejectedBefore + 1);

            // 명부에서 빠졌으니 하트비트가 더 이상 그 큐를 두드리지 않는다(주기 1초).
            long afterDrop = 거부_건수(logs);
            Thread.sleep(2_500);
            assertThat(거부_건수(logs))
                    .as("자리가 남아 있으면 하트비트가 계속 거부되며 로그만 쌓인다")
                    .isEqualTo(afterDrop);
        }
    }

    /** 대조군 — 같은 설정에서 큐에 자리가 있으면 실제로 보내고 닫는다. 「항상 빼는 것」이 아니다. */
    @Test
    void 큐에_자리가_있으면_ended가_실제로_가고_닫힌다() {
        broadcasts.save(Broadcast.startedNow("s-room", TestIds.STREAMER, 1L, Instant.now(), null));

        try (LogCaptor logs = new LogCaptor();
             SseReader reader = new SseReader("http://localhost:" + port + "/api/clip/broadcasts/s-room/events",
                     Map.of("Authorization", "Bearer " + TestTokens.access("1902")))) {
            assertThat(reader.await(1, Duration.ofSeconds(3))).isTrue();

            registry.broadcastEnded("s-room");

            assertThat(reader.awaitName("ended", Duration.ofSeconds(3)))
                    .as("자리가 있는데 안 가면 처방이 정상 경로까지 막은 것이다").isTrue();
            assertThat(reader.awaitClosed(Duration.ofSeconds(5))).isTrue();
            assertThat(logs.messages()).noneMatch(m -> m.startsWith("jumpcard.stream.ended_dropped"));
        }
    }

    private void 안_읽는_연결을_연다(Socket socket, String streamId, String subject) throws Exception {
        OutputStream out = socket.getOutputStream();
        out.write(("GET /api/clip/broadcasts/" + streamId + "/events HTTP/1.1\r\n"
                + "Host: localhost:" + port + "\r\n"
                + "Accept: text/event-stream\r\n"
                + "Authorization: Bearer " + TestTokens.access(subject) + "\r\n"
                + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        // 여기서부터 getInputStream()을 한 번도 안 읽는다. 그것이 조건 ②다.
    }

    private long 거부_건수(LogCaptor logs) {
        return logs.messages().stream().filter(m -> m.startsWith("jumpcard.stream.rejected")).count();
    }

    private void awaitUntil(java.util.function.BooleanSupplier condition, Duration timeout, String why) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(why, e);
            }
        }
        throw new AssertionError(why);
    }

    private HighlightRequest auto(String eventId, long start, String big) {
        return new HighlightRequest(eventId, "auto", start + 23_000L,
                new HighlightRequest.Window(start, start + 42_000L), 97,
                MAPPER.readTree("{\"blob\":\"" + big + "\"}"));
    }
}
