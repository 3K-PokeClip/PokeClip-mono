package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.jumpcard.JumpCardSnapshot;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.SseReader;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>한 방송에 카드가 몰아칠 때 같은 줄의 다른 연결이 얼마나 밀리나.</b>
 *
 * <p>🔴 <b>POK-174가 표적을 옮겼다.</b> 전에는 「초기 스냅샷 300장을 한 태스크로 묶은 대가」를
 * 쟀는데 통로가 지난 카드를 더 이상 안 보내므로 <b>잴 대상이 사라졌다</b>. 그대로 두면 카드
 * 0장을 보내며 밀림 0ms를 재는 시험, 즉 아무것도 안 재는 시험이 된다. 그래서 실시간 발행
 * 쪽으로 옮겼다 — 판별기가 한 방송에 카드를 몰아 넣으면 <b>발행마다 태스크 하나</b>가 같은 줄에
 * 쌓이고, 그 뒤에 선 남의 연결이 그만큼 늦는다.
 *
 * <p>스트라이프를 <b>1</b>로 조인다. 운영 기본값 4로 두면 두 연결이 다른 줄에 갈 수 있어
 * <b>밀림이 없는 것이 우연</b>이 되고, 그러면 이 시험은 아무것도 안 잰다.
 *
 * <p>기준은 PRD의 「카드가 3초 안에 화면에 온다」다.
 *
 * <p><b>실측값은 기계 부하에 따라 통째로 달라진다</b> — 아래 출력과 자기 측정을 비교하려면 어느
 * 조건인지 봐야 한다. 초기 스냅샷 방식일 때의 값(카드 300장, 2026-08-23 각 5회)을 참고로 남긴다:
 * 부하 중 전송 71~86ms·밀림 32~49ms · 깨끗 전송 32~114ms·밀림 15~24ms ·
 * 전수 실행 중 전송 17~24ms·밀림 5ms. <b>같은 기계·같은 부하라도 단독이냐 전수냐로 갈렸다.</b>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "pokeclip.jump-card.stream.stripes=1",
        // 하트비트가 끼면 받은 이벤트에 주석이 섞여 도착 시각 계산이 흔들린다.
        "pokeclip.jump-card.stream.heartbeat=PT1H"
})
class StripeHeadOfLineTest extends IntegrationTestSupport {

    private static final int BURST_CARDS = 300;

    private static final String RESOLVE = "/internal/editor-delegations/resolve";

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
    void 한_방송에_카드_300장이_몰아쳐도_같은_줄의_다른_연결이_3초_안에_받는다() {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");
        broadcasts.save(Broadcast.startedNow("s-hol-big", TestIds.STREAMER, 801L, Instant.now(), null));
        broadcasts.save(Broadcast.startedNow("s-hol-small", TestIds.STREAMER, 802L, Instant.now(), null));
        카드를_심는다("s-hol-big", BURST_CARDS);
        카드를_심는다("s-hol-small", 1);
        // 재는 동안 DB를 안 읽는다 — 무엇 때문에 늦었는지가 갈리지 않는다.
        List<JumpCardSnapshot> 몰아칠_것 = service.snapshotsOf("s-hol-big");
        JumpCardSnapshot 작은_카드 = service.snapshotsOf("s-hol-small").get(0);

        try (SseReader small = open("s-hol-small", TestTokens.access("1801"));
             SseReader big = open("s-hol-big", TestTokens.access("1802"))) {
            assertThat(small.await(1, Duration.ofSeconds(5)))
                    .as("작은 연결이 자리를 잡은 뒤라야 밀림을 잰다").isTrue();
            assertThat(big.await(1, Duration.ofSeconds(5)))
                    .as("큰 연결도 명부에 올라야 발행이 그 줄에 쌓인다").isTrue();

            Instant 몰아친_때 = Instant.now();
            몰아칠_것.forEach(registry::publish);

            // 300개가 이미 줄에 서 있다. 그 뒤에 하나 더 밀어 넣는다.
            Instant published = Instant.now();
            registry.publish(작은_카드);

            awaitUntil(() -> !small.named().isEmpty(), Duration.ofSeconds(20));
            Duration 밀림 = Duration.between(published, small.named().get(0).receivedAt());

            assertThat(big.awaitNamed(BURST_CARDS, Duration.ofSeconds(20)))
                    .as("카드 %d장이 다 와야 전송 시간을 잴 수 있다", BURST_CARDS).isTrue();
            Duration 전송 = Duration.between(몰아친_때, big.named().get(BURST_CARDS - 1).receivedAt());

            System.out.printf("[스트라이프 밀림] 발행 %d장 전송 %dms · 같은 줄의 다른 연결 밀림 %dms%n",
                    BURST_CARDS, 전송.toMillis(), 밀림.toMillis());

            assertThat(밀림)
                    .as("카드 %d장(전송 %dms)이 같은 줄의 다른 연결을 이만큼 밀었다. "
                            + "3초를 넘으면 PRD 도착 기준이 깨진다", BURST_CARDS, 전송.toMillis())
                    .isLessThan(Duration.ofSeconds(3));
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
