package com.pokeclip.chat.detector.observe;

import com.pokeclip.chat.detector.metrics.ChatWindowReader;
import com.pokeclip.chat.detector.metrics.LateArrivalCount;
import com.pokeclip.chat.detector.support.IntegrationTestSupport;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LateArrivalReporterTest extends IntegrationTestSupport {

    private static final Instant T0 = Instant.parse("2026-08-25T12:00:00Z");

    private final JdbcTemplate jdbc;
    private final ChatWindowReader reader;

    LateArrivalReporterTest(JdbcTemplate jdbc, ChatWindowReader reader) {
        this.jdbc = jdbc;
        this.reader = reader;
    }

    @BeforeEach
    void 표를_준비한다() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS chat_messages (
                    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    channel_id        TEXT        NOT NULL,
                    sender_channel_id TEXT        NOT NULL,
                    content           TEXT        NOT NULL,
                    message_time      TIMESTAMPTZ NOT NULL,
                    received_at       TIMESTAMPTZ NOT NULL,
                    content_sha256    VARCHAR(64) NOT NULL,
                    stream_id         VARCHAR(128)
                )
                """);
        jdbc.update("DELETE FROM chat_messages");
    }

    /** @param delayMs 전달 지연 — 찍힌 시각과 받은 시각의 차 */
    private void 채팅(String streamId, Instant messageTime, long delayMs) {
        jdbc.update("""
                INSERT INTO chat_messages
                    (channel_id, sender_channel_id, content, message_time, received_at, content_sha256, stream_id)
                VALUES ('c1', 'u1', 'hi', ?, ?, repeat('a', 64), ?)
                """, Timestamp.from(messageTime), Timestamp.from(messageTime.plusMillis(delayMs)), streamId);
    }

    @Test
    void 유예를_넘긴_채팅을_센다() {
        채팅("s1", T0, 100);     // 정상
        채팅("s1", T0, 175);     // 실측 중앙값
        채팅("s1", T0, 2_500);   // 유예(2초) 넘김
        채팅("s1", T0, 9_000);   // 유예 + 창(5초)도 넘김 → 반드시 놓친다

        LateArrivalCount count = reader.lateArrivals("s1",
                T0.minusSeconds(60), T0.plusSeconds(60), 2_000L, 7_000L);

        assertThat(count.total()).isEqualTo(4);
        assertThat(count.beyondGrace()).isEqualTo(2);
        assertThat(count.beyondWindowAndGrace()).isEqualTo(1);
        assertThat(count.maxDelayMs()).isEqualTo(9_000);
    }

    /**
     * 전달 지연의 부호는 환경에 따라 뒤집힌다 — 수집 서버가 실측했다(시계 오프셋 혼입으로
     * −39~−70ms). 음수를 절댓값으로 접으면 멀쩡한 채팅이 「늦었다」로 잡힌다.
     */
    @Test
    void 지연이_음수인_채팅은_늦은_것이_아니다() {
        채팅("s1", T0, -5_000);

        LateArrivalCount count = reader.lateArrivals("s1",
                T0.minusSeconds(60), T0.plusSeconds(60), 2_000L, 7_000L);

        assertThat(count.total()).isEqualTo(1);
        assertThat(count.beyondGrace()).isZero();
    }

    /**
     * 🔴 지연이 <b>전부 음수</b>인 방송에서 최댓값이 실제값으로 남아야 한다.
     * {@code EMPTY}의 최댓값을 0으로 두면 {@code Math.max}가 실제값(−5,000)을 0으로 깔아뭉갠다
     * — 유예값을 정하는 근거 숫자가 조용히 틀린다(계획 검증 F12).
     */
    @Test
    void 지연이_전부_음수여도_최댓값이_0으로_안_깔린다() {
        채팅("s1", T0, -5_000);
        채팅("s1", T0, -3_000);

        LateArrivalCount 합 = LateArrivalCount.EMPTY.plus(reader.lateArrivals("s1",
                T0.minusSeconds(60), T0.plusSeconds(60), 2_000L, 7_000L));

        assertThat(합.maxDelayMs()).isEqualTo(-3_000);
        assertThat(합.maxDelayForLog()).isEqualTo("-3000");
    }

    /** 한 건도 안 잰 합계는 최댓값이 <b>없다</b>. 0을 적으면 「지연 0」이라는 거짓이 남는다. */
    @Test
    void 아무것도_안_잰_합계는_최댓값이_none이다() {
        assertThat(LateArrivalCount.EMPTY.maxDelayForLog()).isEqualTo("none");
    }

    /**
     * 🔴 채팅이 없으면 최댓값은 <b>0이 아니라 「없음」</b>이다.
     *
     * <p>예전에는 SQL이 {@code COALESCE(MAX(...), 0)}이라 0을 실어 보냈다. 그러면
     * {@code EMPTY}가 {@code Long.MIN_VALUE}인 것이 <b>합산에서 무력해진다</b> —
     * 계획 검증 F12를 자바 쪽만 고치고 SQL 쪽을 안 봐서 한 자리가 남아 있었다
     * (「같은 뿌리인데 한 자리만」의 일곱 번째, 감사가 읽다 찾았다).
     */
    @Test
    void 채팅이_없으면_건수는_0이고_최댓값은_없음이다() {
        LateArrivalCount count = reader.lateArrivals("s1",
                T0.minusSeconds(60), T0.plusSeconds(60), 2_000L, 7_000L);

        assertThat(count.total()).isZero();
        assertThat(count.beyondGrace()).isZero();
        assertThat(count.maxDelayMs()).isEqualTo(Long.MIN_VALUE);
        assertThat(count.maxDelayForLog()).isEqualTo("none");
    }

    /**
     * 🔴 <b>줄이 없는 방송이 섞여도 최댓값이 0으로 안 깔린다.</b> F12가 SQL 쪽으로 되살아나던
     * 자리다 — 빈 방송이 0을 실어 보내면 {@code Math.max(-3000, 0)}이 0이 된다.
     *
     * <p>예전에 이것이 「도달 못 한다」던 이유는 <b>설정값 우연</b>이었다(관측 기간 10분 &gt;
     * 활성 창 60초라 활성 방송은 늘 줄이 있다). 두 설정 사이에 교차 검사가 없어 앞의 값을
     * 뒤의 값 아래로 내리면 되살아났다. <b>뿌리를 고쳤으므로 이제 그 관계에 안 기댄다.</b>
     */
    @Test
    void 줄이_없는_방송이_섞여도_최댓값이_0으로_안_깔린다() {
        채팅("s2", T0, -3_000);   // s1에는 한 건도 없다

        LateArrivalCount 합 = LateArrivalCount.EMPTY
                .plus(reader.lateArrivals("s1", T0.minusSeconds(60), T0.plusSeconds(60), 2_000L, 7_000L))
                .plus(reader.lateArrivals("s2", T0.minusSeconds(60), T0.plusSeconds(60), 2_000L, 7_000L));

        assertThat(합.maxDelayMs()).isEqualTo(-3_000);
        assertThat(합.maxDelayForLog()).isEqualTo("-3000");
    }

    @Test
    void 남의_방송_채팅은_안_섞인다() {
        채팅("s1", T0, 100);
        채팅("s2", T0, 9_000);

        assertThat(reader.lateArrivals("s1", T0.minusSeconds(60), T0.plusSeconds(60), 2_000L, 7_000L)
                .beyondGrace()).isZero();
    }

    /** 요약 한 줄에 유예값과 세 숫자가 같이 있어야 한다 — 따로 있으면 읽는 쪽이 이어 붙여야 한다. */
    @Test
    void 요약_한_줄에_유예값과_세_숫자가_같이_실린다() {
        채팅("s1", T0, 100);
        채팅("s1", T0, 2_500);

        LateArrivalReporter reporter = new LateArrivalReporter(reader,
                () -> List.of("s1"), Duration.ofSeconds(2), 5_000L,
                Duration.ofMinutes(10), () -> T0.plusSeconds(60));

        try (LogCaptor captor = new LogCaptor()) {
            reporter.report();

            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("detect.late_arrivals"))
                    .singleElement().satisfies(line -> assertThat(line)
                            .contains("windowGraceMs=2000")
                            .contains("observed=2")
                            .contains("beyondGrace=1")
                            .contains("beyondWindowAndGrace=0")
                            .contains("maxDelayMs=2500"));
        }
    }

    /** 방송이 여럿이면 합쳐서 한 줄로 찍는다. 방송마다 찍으면 100개일 때 로그가 100줄이다. */
    @Test
    void 방송이_여럿이면_합쳐서_한_줄이다() {
        채팅("s1", T0, 2_500);
        채팅("s2", T0, 3_000);

        LateArrivalReporter reporter = new LateArrivalReporter(reader,
                () -> List.of("s1", "s2"), Duration.ofSeconds(2), 5_000L,
                Duration.ofMinutes(10), () -> T0.plusSeconds(60));

        try (LogCaptor captor = new LogCaptor()) {
            reporter.report();

            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("detect.late_arrivals"))
                    .singleElement().satisfies(line -> assertThat(line)
                            .contains("streams=2")
                            .contains("observed=2")
                            .contains("beyondGrace=2")
                            // 최댓값은 합이 아니라 최댓값이다.
                            .contains("maxDelayMs=3000"));
        }
    }

    /** 볼 방송이 없으면 아무것도 안 찍는다. 조용한 새벽에 0만 적힌 줄이 쌓이지 않게. */
    @Test
    void 활성_방송이_없으면_안_찍는다() {
        LateArrivalReporter reporter = new LateArrivalReporter(reader,
                List::of, Duration.ofSeconds(2), 5_000L, Duration.ofMinutes(10), () -> T0);

        try (LogCaptor captor = new LogCaptor()) {
            reporter.report();

            assertThat(captor.messages()).filteredOn(m -> m.startsWith("detect.late_arrivals")).isEmpty();
        }
    }

    /** 🔴 터져도 다음 주기가 돌아야 한다. 관측이 판별을 멈추면 앞뒤가 바뀐다. */
    @Test
    void 터져도_예외가_밖으로_안_나간다() {
        LateArrivalReporter reporter = new LateArrivalReporter(reader,
                () -> { throw new IllegalStateException("DB가 죽었다"); },
                Duration.ofSeconds(2), 5_000L, Duration.ofMinutes(10), () -> T0);

        try (LogCaptor captor = new LogCaptor()) {
            reporter.report();

            assertThat(captor.levelOf("detect.late_arrivals_failed"))
                    .isEqualTo(ch.qos.logback.classic.Level.WARN);
        }
    }
}
