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
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>초기 스냅샷을 한 태스크로 묶은 대가를 잰다.</b>
 *
 * <p>스냅샷 전체가 태스크 하나라 그것이 도는 동안 <b>같은 스트라이프의 다른 연결</b>이 밀린다.
 * 쪼개 제출하면 밀림은 줄지만 큐를 카드 수만큼 먹어 상한을 넘는 순간 <b>영구 유실</b>이 된다
 * (봇 지적 ③ 재현: 재연결해도 같은 자리에서 또 잘린다). 밀림은 <b>늦는 것</b>이고 유실은
 * <b>안 오는 것</b>이라 밀림을 골랐다 — 이 시험이 그 대가가 기준 안인지 지킨다.
 *
 * <p>스트라이프를 <b>1</b>로 조인다. 운영 기본값 4로 두면 두 연결이 다른 줄에 갈 수 있어
 * <b>밀림이 없는 것이 우연</b>이 되고, 그러면 이 시험은 아무것도 안 잰다.
 *
 * <p>기준은 PRD의 「카드가 3초 안에 화면에 온다」다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "pokeclip.jump-card.stream.stripes=1",
        // 하트비트가 끼면 받은 이벤트에 주석이 섞여 도착 시각 계산이 흔들린다.
        "pokeclip.jump-card.stream.heartbeat=PT1H"
})
class StripeHeadOfLineTest extends IntegrationTestSupport {

    private static final int SNAPSHOT_CARDS = 300;

    private final int port;
    private final JumpCardService service;
    private final BroadcastRepository broadcasts;
    private final CardStreamRegistry registry;
    private final JdbcTemplate jdbc;

    StripeHeadOfLineTest(@LocalServerPort int port, JumpCardService service,
                         BroadcastRepository broadcasts, CardStreamRegistry registry, JdbcTemplate jdbc) {
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
    void 스냅샷_300장을_보내는_동안_같은_줄의_다른_연결이_3초_안에_받는다() {
        broadcasts.save(Broadcast.startedNow("s-hol-big", "u-1", 801L, Instant.now(), null));
        broadcasts.save(Broadcast.startedNow("s-hol-small", "u-1", 802L, Instant.now(), null));
        카드를_심는다("s-hol-big", SNAPSHOT_CARDS);
        카드를_심는다("s-hol-small", 1);

        try (SseReader small = open("s-hol-small", TestTokens.access("hol-small"))) {
            assertThat(small.awaitNamed(1, Duration.ofSeconds(5)))
                    .as("작은 연결이 자리를 잡은 뒤라야 밀림을 잰다").isTrue();
            int before = small.named().size();

            Instant openedBig = Instant.now();
            try (SseReader big = open("s-hol-big", TestTokens.access("hol-big"))) {
                // 큰 스냅샷 태스크가 지금 스트라이프를 잡고 있다. 그 줄에 하나 더 밀어 넣는다.
                Instant published = Instant.now();
                registry.publish(service.snapshotsOf("s-hol-small").get(0));

                awaitUntil(() -> small.named().size() > before, Duration.ofSeconds(20));
                Duration 밀림 = Duration.between(published, small.named().get(before).receivedAt());

                assertThat(big.awaitNamed(SNAPSHOT_CARDS, Duration.ofSeconds(20)))
                        .as("스냅샷 %d장이 다 와야 전송 시간을 잴 수 있다", SNAPSHOT_CARDS).isTrue();
                Duration 전송 = Duration.between(openedBig,
                        big.named().get(SNAPSHOT_CARDS - 1).receivedAt());

                System.out.printf("[스트라이프 밀림] 스냅샷 %d장 전송 %dms · 같은 줄의 다른 연결 밀림 %dms%n",
                        SNAPSHOT_CARDS, 전송.toMillis(), 밀림.toMillis());

                assertThat(밀림)
                        .as("스냅샷 %d장(전송 %dms)이 같은 줄의 다른 연결을 이만큼 밀었다. "
                                + "3초를 넘으면 PRD 도착 기준이 깨진다", SNAPSHOT_CARDS, 전송.toMillis())
                        .isLessThan(Duration.ofSeconds(3));
            }
        }
    }

    private void 카드를_심는다(String streamId, int count) {
        jdbc.batchUpdate("INSERT INTO jump_cards (stream_id, source, event_id, stream_timestamp_ms, "
                        + "window_start_ms, window_end_ms, score, evidence, event_seq) "
                        + "VALUES (?, 'auto', ?, ?, ?, ?, 97, CAST(? AS jsonb), 0)",
                IntStream.range(0, count)
                        .mapToObj(i -> new Object[]{streamId, "evt-" + i,
                                i * 100_000L + 23_000L, i * 100_000L, i * 100_000L + 42_000L,
                                "{\"multiplier\":4.2,\"messageCount\":183}"})
                        .toList());
    }

    private SseReader open(String streamId, String token) {
        return new SseReader("http://localhost:" + port + "/api/clip/broadcasts/" + streamId + "/events",
                Map.of("Authorization", "Bearer " + token));
    }

    private void awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
