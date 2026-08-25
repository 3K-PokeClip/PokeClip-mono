package com.pokeclip.chat.detector;

import com.pokeclip.chat.detector.metrics.ChatMetricsStore;
import com.pokeclip.chat.detector.support.IntegrationTestSupport;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MetricsSweeperTest extends IntegrationTestSupport {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");

    private final JdbcTemplate jdbc;
    private final ChatMetricsStore store;

    MetricsSweeperTest(JdbcTemplate jdbc, ChatMetricsStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    @BeforeEach
    void 비운다() {
        jdbc.update("DELETE FROM chat_metrics");
    }

    private void 집계줄(long windowStartMs, Instant createdAt) {
        jdbc.update("""
                INSERT INTO chat_metrics
                    (stream_id, window_size_ms, window_start_ms, message_count, chatter_count, created_at)
                VALUES ('s1', 5000, ?, 1, 1, ?)
                """, windowStartMs, Timestamp.from(createdAt));
    }

    @Test
    void 보관_기간이_지난_줄만_지운다() {
        집계줄(1_000L, NOW.minus(Duration.ofHours(30)));
        집계줄(2_000L, NOW.minus(Duration.ofHours(1)));

        assertThat(store.sweepOlderThan(NOW.minus(Duration.ofHours(24)))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_metrics", Integer.class)).isEqualTo(1);
    }

    @Test
    void 지울_것이_없으면_아무것도_안_한다() {
        집계줄(1_000L, NOW);

        assertThat(store.sweepOlderThan(NOW.minus(Duration.ofHours(24)))).isZero();
    }

    /**
     * 경계에 딱 걸린 줄은 <b>남긴다</b>({@code < before}). 부등호를 한 칸 밀면 보관 기간이
     * 하루가 아니라 하루−1이 되고, 그 차이는 아무 검사도 안 깨진 채 조용히 들어온다.
     */
    @Test
    void 경계에_딱_걸린_줄은_남긴다() {
        Instant 경계 = NOW.minus(Duration.ofHours(24));
        집계줄(1_000L, 경계);

        assertThat(store.sweepOlderThan(경계)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_metrics", Integer.class)).isEqualTo(1);
    }

    /**
     * 치운 줄이 있으면 <b>몇 줄인지</b>가 로그에 남는다. 없으면 안 남긴다 —
     * 10분마다 {@code count=0}이 쌓이면 진짜 신호가 묻힌다.
     */
    @Test
    void 치운_줄이_있을_때만_수를_로그에_남긴다() {
        집계줄(1_000L, NOW.minus(Duration.ofHours(30)));
        MetricsSweeper sweeper = new MetricsSweeper(store, Duration.ofHours(24), () -> NOW);

        try (LogCaptor captor = new LogCaptor()) {
            sweeper.sweep();

            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("detect.metrics_swept"))
                    .singleElement().satisfies(line -> assertThat(line).contains("count=1"));
        }
        try (LogCaptor captor = new LogCaptor()) {
            sweeper.sweep();   // 이제 지울 것이 없다

            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("detect.metrics_swept"))
                    .isEmpty();
        }
    }

    /**
     * 🔴 <b>주기 작업이 보관 기간을 실제로 빼는지</b> 잰다. 안 빼면 지금 시각보다 오래된
     * <b>전부</b>를 지운다 — 즉 <b>표를 통째로 비운다.</b> 그러면 모든 방송의 기준선이 사라져
     * 워밍업으로 되돌아가고, 카드가 창 스물넷어치 동안 한 장도 안 나간다.
     *
     * <p>앞의 검사들은 {@code sweepOlderThan}을 <b>직접</b> 부르거나 오래된 줄만 넣어서,
     * {@code minus(retention)}을 지워도 전부 초록이었다(주입 P2로 실측).
     * <b>최근 줄이 살아남는지</b>를 {@code sweep()}을 통해 재는 갈래가 없었다.
     */
    @Test
    void 주기_치우기가_최근_줄은_남긴다() {
        집계줄(1_000L, NOW.minus(Duration.ofHours(30)));   // 지워야 한다
        집계줄(2_000L, NOW.minus(Duration.ofHours(1)));    // 남아야 한다
        집계줄(3_000L, NOW);                               // 남아야 한다

        new MetricsSweeper(store, Duration.ofHours(24), () -> NOW).sweep();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM chat_metrics", Integer.class))
                .as("보관 기간을 안 빼면 표가 통째로 비워진다").isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM chat_metrics WHERE window_start_ms = 1000", Integer.class)).isZero();
    }

    /**
     * 🔴 치우다 터져도 다음 주기가 돌아야 한다. @Scheduled는 태스크가 한 번 던지면 그 뒤로
     * 안 도는데, 그러면 표가 영영 안 치워지면서 아무 신호도 없다.
     */
    @Test
    void 치우기가_터져도_예외가_밖으로_안_나간다() {
        ChatMetricsStore 터지는_store = new ChatMetricsStore(jdbc) {
            @Override
            public int sweepOlderThan(Instant before) {
                throw new IllegalStateException("DB가 죽었다");
            }
        };
        MetricsSweeper sweeper = new MetricsSweeper(터지는_store, Duration.ofHours(24), () -> NOW);

        try (LogCaptor captor = new LogCaptor()) {
            // 예외가 밖으로 나오면 여기서 터진다 — 그것이 이 검사의 절반이다.
            sweeper.sweep();

            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("detect.metrics_sweep_failed"))
                    .singleElement()
                    .satisfies(line -> assertThat(line).contains("causeType=IllegalStateException"));
            // 레벨까지 잰다. INFO로 낮추면 표가 안 치워지는 것을 아무도 못 본다.
            assertThat(captor.levelOf("detect.metrics_sweep_failed"))
                    .isEqualTo(ch.qos.logback.classic.Level.WARN);
        }
    }
}
