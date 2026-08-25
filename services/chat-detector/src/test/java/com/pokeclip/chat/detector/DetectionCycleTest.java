package com.pokeclip.chat.detector;

import com.pokeclip.chat.detector.config.DetectionProperties;
import com.pokeclip.chat.detector.detect.SpikeDetector;
import com.pokeclip.chat.detector.detect.SpikeVerdict;
import com.pokeclip.chat.detector.metrics.ChatMetricsStore;
import com.pokeclip.chat.detector.metrics.ChatWindowReader;
import com.pokeclip.chat.detector.publish.HighlightPublisher;
import com.pokeclip.chat.detector.support.IntegrationTestSupport;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 한 바퀴를 실제 PostgreSQL 위에서 돌린다. 발행은 안 한다 — 이 검사는 집계와 발행권까지만 본다.
 */
@SpringBootTest(properties = {
        "pokeclip.detection.window-sizes-ms=5000",
        "pokeclip.detection.publish-window-ms=5000",
        "pokeclip.detection.warmup-windows=2",
        "pokeclip.detection.window-grace=0s"
})
class DetectionCycleTest extends IntegrationTestSupport {

    private static final Instant T0 = Instant.parse("2026-08-25T12:00:00Z");

    private final JdbcTemplate jdbc;
    private final DetectionCycle cycle;
    private final ChatWindowReader reader;
    private final ChatMetricsStore metricsStore;
    private final SpikeDetector detector;
    private final DetectionProperties props;
    private final HighlightPublisher realPublisher;

    DetectionCycleTest(JdbcTemplate jdbc, DetectionCycle cycle, ChatWindowReader reader,
                       ChatMetricsStore metricsStore, SpikeDetector detector,
                       DetectionProperties props, HighlightPublisher realPublisher) {
        this.jdbc = jdbc;
        this.cycle = cycle;
        this.reader = reader;
        this.metricsStore = metricsStore;
        this.detector = detector;
        this.props = props;
        this.realPublisher = realPublisher;
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
        jdbc.update("DELETE FROM chat_metrics");
    }

    private void 채팅(String streamId, String sender, Instant at) {
        채팅(streamId, sender, at, at);
    }

    /** 치지직이 찍은 시각과 우리가 받은 시각을 따로 준다. 시계가 어긋난 상황을 만든다. */
    private void 채팅(String streamId, String sender, Instant messageTime, Instant receivedAt) {
        jdbc.update("""
                INSERT INTO chat_messages
                    (channel_id, sender_channel_id, content, message_time, received_at, content_sha256, stream_id)
                VALUES ('c1', ?, 'hi', ?, ?, repeat('a', 64), ?)
                """, sender, Timestamp.from(messageTime), Timestamp.from(receivedAt), streamId);
    }

    @Test
    void 한_바퀴가_창을_집계해_표에_남긴다() {
        채팅("s1", "u1", T0);
        채팅("s1", "u2", T0.plusMillis(500));

        cycle.runOnce(T0.plusSeconds(10));

        Integer count = jdbc.queryForObject("""
                SELECT message_count FROM chat_metrics
                 WHERE stream_id = 's1' AND window_size_ms = 5000 AND window_start_ms = ?
                """, Integer.class, T0.toEpochMilli());
        assertThat(count).isEqualTo(2);
    }

    /** 두 바퀴가 겹쳐 돌아도 같은 창이 두 줄이 되지 않는다. */
    @Test
    void 같은_바퀴를_두_번_돌려도_줄이_안_늘어난다() {
        채팅("s1", "u1", T0);

        cycle.runOnce(T0.plusSeconds(10));
        cycle.runOnce(T0.plusSeconds(10));

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM chat_metrics WHERE stream_id = 's1'", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    /**
     * 발행권은 한 번만 잡힌다. 두 번째는 빈손이라 카드가 두 장 안 나간다.
     *
     * <p><b>{@code runOnce}를 부르지 않는다.</b> 부르면 그 안에서 이미 발행권을 잡아 버려
     * 여기 첫 호출이 빈손이 된다 — 급증이 아닌 줄도 잡아 두는 것이 이 태스크의 설계이기
     * 때문이다. 계획 검증(F5)이 67건 중 유일한 실패로 재현했다.
     *
     * <p>그래서 줄을 손으로 넣고 발행권만 잰다. {@code runOnce}가 발행권을 잡는다는 것은
     * 아래 {@code 급증이_아닌_창도_발행권을_잡는다}가 따로 잰다.
     */
    @Test
    void 발행권은_한_번만_잡힌다() {
        jdbc.update("""
                INSERT INTO chat_metrics (stream_id, window_size_ms, window_start_ms, message_count, chatter_count)
                VALUES ('s1', 5000, ?, 3, 2)
                """, T0.toEpochMilli());

        assertThat(store().claimForPublish("s1", 5_000L, T0.toEpochMilli(), T0)).isPresent();
        assertThat(store().claimForPublish("s1", 5_000L, T0.toEpochMilli(), T0)).isEmpty();
    }

    /**
     * 발행권을 잡으면 <b>그 줄의 번호</b>가 나온다. 그 번호가 카드의 {@code eventId}가 되므로
     * (`detect-<번호>`) 엉뚱한 값이 나오면 clip의 중복 방어가 다른 카드를 같은 것으로 본다.
     *
     * <p>「비었나 아닌가」만 재면 <b>아무 상수나 돌려줘도 통과한다</b> — 값을 만드는 쪽이라
     * 값 자체를 잰다.
     */
    @Test
    void 발행권이_돌려주는_번호는_그_줄의_번호다() {
        Long id = jdbc.queryForObject("""
                INSERT INTO chat_metrics (stream_id, window_size_ms, window_start_ms, message_count, chatter_count)
                VALUES ('s1', 5000, ?, 3, 2) RETURNING id
                """, Long.class, T0.toEpochMilli());

        assertThat(store().claimForPublish("s1", 5_000L, T0.toEpochMilli(), T0)).hasValue(id);
    }

    /** 발행권을 잡으면 그 시각이 표에 찍힌다. 안 찍히면 다음 바퀴가 같은 창을 또 집는다. */
    @Test
    void 발행권을_잡으면_시각이_찍힌다() {
        jdbc.update("""
                INSERT INTO chat_metrics (stream_id, window_size_ms, window_start_ms, message_count, chatter_count)
                VALUES ('s1', 5000, ?, 3, 2)
                """, T0.toEpochMilli());

        store().claimForPublish("s1", 5_000L, T0.toEpochMilli(), T0);

        Timestamp at = jdbc.queryForObject(
                "SELECT published_at FROM chat_metrics WHERE stream_id = 's1'", Timestamp.class);
        assertThat(at).isEqualTo(Timestamp.from(T0));
    }

    /** 없는 창에 발행권을 걸면 빈손이다 — 없는 줄을 잡았다고 답하면 카드가 허공에 나간다. */
    @Test
    void 없는_창은_발행권이_안_잡힌다() {
        assertThat(store().claimForPublish("s1", 5_000L, T0.toEpochMilli(), T0)).isEmpty();
    }

    /**
     * 아직 발행권이 안 잡힌 창 목록이 <b>표의 값을 그대로</b> 담아야 한다.
     * 판정기는 이 값으로 급증을 가르므로, 칸이 밀리면 사람 수로 메시지 수를 판정한다.
     */
    @Test
    void 발행_안_된_창_목록이_표의_값을_그대로_담는다() {
        jdbc.update("""
                INSERT INTO chat_metrics (stream_id, window_size_ms, window_start_ms, message_count, chatter_count)
                VALUES ('s1', 5000, ?, 40, 25), ('s1', 5000, ?, 7, 3)
                """, T0.toEpochMilli(), T0.plusSeconds(5).toEpochMilli());
        // 이미 잡힌 줄은 안 나와야 한다.
        store().claimForPublish("s1", 5_000L, T0.plusSeconds(5).toEpochMilli(), T0);

        assertThat(store().unpublished("s1", 5_000L)).singleElement().satisfies(row -> {
            assertThat(row.streamId()).isEqualTo("s1");
            assertThat(row.windowSizeMs()).isEqualTo(5_000L);
            assertThat(row.windowStartMs()).isEqualTo(T0.toEpochMilli());
            assertThat(row.messageCount()).isEqualTo(40);
            assertThat(row.chatterCount()).isEqualTo(25);
        });
    }

    /** 다른 창 크기·다른 방송은 그 목록에 안 섞인다. */
    @Test
    void 발행_안_된_창_목록에_남의_것이_안_섞인다() {
        jdbc.update("""
                INSERT INTO chat_metrics (stream_id, window_size_ms, window_start_ms, message_count, chatter_count)
                VALUES ('s1', 5000, ?, 1, 1), ('s1', 3000, ?, 2, 2), ('s2', 5000, ?, 3, 3)
                """, T0.toEpochMilli(), T0.toEpochMilli(), T0.toEpochMilli());

        assertThat(store().unpublished("s1", 5_000L)).singleElement()
                .satisfies(row -> assertThat(row.messageCount()).isEqualTo(1));
    }

    /**
     * 급증이 아니어도 발행권을 잡아 둔다. 안 잡으면 그 줄이 매 바퀴 다시 판정돼 조회가
     * 계속 늘고, 베이스라인이 흘러 <b>한참 뒤에 뒤늦게 카드가 나갈 수 있다</b>.
     */
    @Test
    void 급증이_아닌_창도_발행권을_잡는다() {
        채팅("s1", "u1", T0);

        cycle.runOnce(T0.plusSeconds(10));

        Integer unclaimed = jdbc.queryForObject(
                "SELECT count(*) FROM chat_metrics WHERE stream_id = 's1' AND window_size_ms = 5000 AND published_at IS NULL",
                Integer.class);
        assertThat(unclaimed).isZero();
    }

    /** 채팅이 끊긴 방송은 활성이 아니라 집계 대상에서 빠진다. */
    @Test
    void 오래_조용한_방송은_안_센다() {
        채팅("old", "u1", T0.minusSeconds(600));

        cycle.runOnce(T0.plusSeconds(10));

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM chat_metrics WHERE stream_id = 'old'", Integer.class);
        assertThat(rows).isZero();
    }

    /**
     * 🔴 <b>감사 1회차 중대 A-1의 유일한 안전망이다.</b>
     *
     * <p>활성 판단은 {@code received_at}(우리 시계)으로 하는데 집계 범위는 우리 시계에서
     * 만든 {@code collect-lookback} 폭이라, <b>치지직 시각이 그 폭보다 크게 어긋난 방송은
     * 활성 목록에는 있는데 셀 창이 0줄</b>이 된다. 되돌아보는 폭이 계속 앞으로 가므로
     * 나중에 메워지지도 않는다 — <b>그 방송은 영영 카드가 안 나가는데 오류도 로그도 없다.</b>
     *
     * <p>구조를 안 바꾸기로 한 결정(F8 재발 방지 · 틀리는 방향이 안전 · 실측 없음)의 대가가
     * 이것이라, <b>이 한 줄이 유일하게 그 사실을 밖으로 내보내는 통로</b>다.
     */
    @Test
    void 활성인데_집계가_0줄이면_로그를_남긴다() {
        // 치지직이 10분 전으로 찍었는데 우리에게는 방금 왔다 — 활성이지만 셀 창이 없다.
        채팅("skewed", "u1", T0.minusSeconds(600), T0);

        try (LogCaptor captor = new LogCaptor()) {
            cycle.runOnce(T0.plusSeconds(10));

            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("detect.active_but_empty"))
                    .singleElement().satisfies(line -> assertThat(line)
                            .contains("streams=1")
                            .contains("skewed"));
        }
    }

    /**
     * 정상 방송에서는 그 줄이 안 찍힌다. 늘 찍히면 신호가 아니라 잡음이 된다.
     *
     * <p>🔴 <b>반드시 두 바퀴를 돌린다.</b> 한 바퀴만 돌리면 창이 전부 새것이라 무엇으로
     * 판정하든 통과한다 — 판정 근거를 「읽어 온 창 수」에서 「표에 새로 들어간 수」로 뒤집어도
     * <b>아흔일곱 건이 전부 초록</b>이었다(감사 2회차 R-1). 결함은 <b>둘째 바퀴부터</b> 나온다:
     * 그때는 창이 이미 표에 있어 {@code upsert}가 정상적으로 0을 돌려주므로,
     * <b>건강한 방송 전부가 매 초 이 줄을 찍어</b> A-1의 유일한 통로가 통째로 잡음이 된다.
     */
    @Test
    void 정상_방송에서는_그_줄이_안_찍힌다() {
        채팅("s1", "u1", T0);
        cycle.runOnce(T0.plusSeconds(10));   // 첫 바퀴: 창이 새것이라 아무 판정이나 통과한다

        try (LogCaptor captor = new LogCaptor()) {
            cycle.runOnce(T0.plusSeconds(10));   // 둘째 바퀴가 진짜 시험이다

            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("detect.active_but_empty"))
                    .isEmpty();
        }
    }

    /**
     * 🔴 <b>방송 하나가 터져도 나머지가 그 바퀴를 잃지 않는다.</b> 바깥 {@code tick()}의 포획은
     * <b>주기</b>를 살리지만 그 바퀴는 이미 끝난 뒤다 — 100 방송 중 하나 때문에 99개가 통째로
     * 건너뛴다(감사 2회차 R-4).
     *
     * <p>첫 방송에서만 터지는 리더를 끼워 재현한다. 알파벳 순서로 {@code bad}가 {@code good}보다
     * 앞이라 실패가 먼저 온다.
     */
    @Test
    void 방송_하나가_터져도_나머지는_그_바퀴를_돈다() {
        채팅("bad", "u1", T0);
        채팅("good", "u1", T0);

        ChatWindowReader 한_방송만_터지는_리더 = new ChatWindowReader(jdbc) {
            @Override
            public java.util.List<com.pokeclip.chat.detector.metrics.MetricRow> countWindows(
                    String streamId, long windowSizeMs, java.time.Instant from, java.time.Instant to) {
                if ("bad".equals(streamId)) {
                    throw new IllegalStateException("주입된 실패");
                }
                return super.countWindows(streamId, windowSizeMs, from, to);
            }
        };
        DetectionCycle 갈아끼운_바퀴 = new DetectionCycle(한_방송만_터지는_리더, metricsStore, detector,
                realPublisher, props, Runnable::run);

        try (LogCaptor captor = new LogCaptor()) {
            갈아끼운_바퀴.runOnce(T0.plusSeconds(10));

            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("detect.stream_failed"))
                    .singleElement().satisfies(line -> assertThat(line).contains("bad"));
        }

        Integer 정상_방송_줄 = jdbc.queryForObject(
                "SELECT count(*) FROM chat_metrics WHERE stream_id = 'good'", Integer.class);
        assertThat(정상_방송_줄).as("터진 방송 뒤의 방송이 그 바퀴를 잃으면 안 된다").isPositive();
    }

    /**
     * 🔴 <b>발행에 넘기는 상한은 「지금」이 아니라 바퀴의 시각이다.</b>
     *
     * <p>그 상한은 「집계에 쓰인 채팅의 도착 시각 한계」다. 발행은 실행기에서 돌고 clip 재시도까지
     * 끼면 바퀴에서 초 단위로 떨어질 수 있는데, 거기서 {@code Instant.now()}를 넘기면 <b>그 사이
     * 도착한(판정에 안 쓰인) 채팅</b>이 「우리가 다 받은 시각」에 섞여 우리 구간이 늘 낙관적으로 틀린다.
     *
     * <p>발행기 쪽 검사({@code 집계에_쓰인_채팅만_보도록_상한을_건다})는 <b>받은 값을 어떻게 쓰는지</b>만
     * 본다. <b>그 값을 만들어 넘기는 쪽</b>이 여기다 — 배선을 「지금」으로 바꿔도 백스물한 건이
     * 전부 초록이었다(주입 S2로 실측).
     */
    @Test
    void 발행에_넘기는_상한은_바퀴의_시각이다() {
        급증_한_건을_심는다();
        java.time.Instant 바퀴_시각 = T0.plusSeconds(10);

        java.util.List<java.time.Instant> 넘어간_상한 = new java.util.ArrayList<>();
        HighlightPublisher 기록기 = new HighlightPublisher(null, null, reader, props) {
            @Override
            public boolean publish(String streamId, long metricId, long windowStartMs,
                                   SpikeVerdict verdict, java.time.Instant countedUntil,
                                   java.time.Instant now) {
                넘어간_상한.add(countedUntil);
                return true;
            }
        };
        new DetectionCycle(reader, metricsStore, detector, 기록기, props, Runnable::run)
                .runOnce(바퀴_시각);

        assertThat(넘어간_상한).as("바퀴의 시각을 그대로 넘겨야 한다").containsExactly(바퀴_시각);
    }

    /**
     * 🔴 <b>실행기에 던진 일이 터져도 조용히 사라지지 않는다.</b> 발행권은 이미 잡혀 재시도가
     * 없으므로, 로그가 없으면 그 카드는 흔적 없이 없어진다(감사 2회차 R-5).
     *
     * <p><b>지금 이 경로로 새는 예외는 못 찾았다</b> — 두 클라이언트가 {@code Exception}을 다 잡는다.
     * 그래도 재는 이유는 그 사이 코드가 앞으로 바뀌기 때문이다.
     */
    @Test
    void 발행이_터져도_조용히_사라지지_않는다() {
        급증_한_건을_심는다();

        HighlightPublisher 터지는_발행기 = new HighlightPublisher(null, null, reader, props) {
            @Override
            public boolean publish(String streamId, long metricId, long windowStartMs,
                                   SpikeVerdict verdict, java.time.Instant countedUntil,
                                   java.time.Instant now) {
                throw new IllegalStateException("주입된 실패");
            }
        };
        DetectionCycle 갈아끼운_바퀴 = new DetectionCycle(reader, metricsStore, detector,
                터지는_발행기, props, Runnable::run);

        try (LogCaptor captor = new LogCaptor()) {
            갈아끼운_바퀴.runOnce(T0.plusSeconds(10));   // 던지면 이 줄에서 검사가 실패한다

            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("detect.publish_threw"))
                    .singleElement().satisfies(line -> assertThat(line).contains("s1"));
        }
    }

    /**
     * {@code @Scheduled} 메서드가 예외를 <b>밖으로 내지 않는다.</b> 한 번이라도 내면
     * 스프링이 그 주기를 다시 안 돌리고, 판별이 통째로 멈추는데 아무 신호가 없다.
     *
     * <p>{@code chat_messages}를 지워 조회가 확실히 터지게 만든 뒤 {@code tick()}을 부른다.
     */
    @Test
    void 터져도_예외가_밖으로_안_나간다() {
        jdbc.execute("DROP TABLE IF EXISTS chat_messages");

        try (LogCaptor captor = new LogCaptor()) {
            cycle.tick();   // 던지면 이 줄에서 검사가 실패한다

            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("detect.cycle_failed"))
                    .isNotEmpty();
        }
    }

    /** 급증 하나가 실제로 발행까지 가는 상태를 표에 만든다. 기준선 둘은 이미 발행권이 잡힌 줄이다. */
    private void 급증_한_건을_심는다() {
        채팅("s1", "u1", T0);
        jdbc.update("""
                INSERT INTO chat_metrics (stream_id, window_size_ms, window_start_ms, message_count, chatter_count, published_at)
                VALUES ('s1', 5000, ?, 1, 1, now()), ('s1', 5000, ?, 1, 1, now())
                """, T0.minusSeconds(10).toEpochMilli(), T0.minusSeconds(5).toEpochMilli());
        jdbc.update("""
                INSERT INTO chat_metrics (stream_id, window_size_ms, window_start_ms, message_count, chatter_count)
                VALUES ('s1', 5000, ?, 40, 25)
                """, T0.toEpochMilli());
    }

    /**
     * 🔴 <b>발행을 판정 스레드에서 하지 않는다.</b> clip이 죽어 있으면 재시도가 초 단위로
     * 걸리는데, 그 사이 판정이 멈추면 <b>다른 방송의 급증을 통째로 놓친다</b>(POK-139).
     *
     * <p>재는 법: <b>받은 일을 안 돌리는 실행기</b>를 준다. 발행이 실행기를 거치면 이 시점에
     * 발행기는 한 번도 안 불렸어야 하고, 실행기를 우회해 직접 부르면 그 자리에서 불린다.
     *
     * <p>이 갈래가 없으면 {@code publishExecutor.execute(...)}를 직접 호출로 바꿔도
     * <b>아흔다섯 건이 전부 초록</b>이다(2026-08-26 주입 N11로 실측).
     */
    @Test
    void 발행을_판정_스레드에서_직접_하지_않는다() {
        급증_한_건을_심는다();

        java.util.List<Runnable> 받은_일 = new java.util.ArrayList<>();
        java.util.List<String> 발행됨 = new java.util.ArrayList<>();
        HighlightPublisher 기록하는_발행기 = new HighlightPublisher(null, null, reader, props) {
            @Override
            public boolean publish(String streamId, long metricId, long windowStartMs,
                                   SpikeVerdict verdict, java.time.Instant countedUntil,
                                   java.time.Instant now) {
                발행됨.add(streamId);
                return true;
            }
        };
        DetectionCycle 갈아끼운_바퀴 = new DetectionCycle(reader, metricsStore, detector,
                기록하는_발행기, props, 받은_일::add);

        갈아끼운_바퀴.runOnce(T0.plusSeconds(10));

        assertThat(받은_일).as("발행이 실행기에 던져져야 한다").hasSize(1);
        assertThat(발행됨).as("판정 스레드에서 직접 부르면 안 된다").isEmpty();

        // 실행기가 받은 그 일이 실제로 발행을 부르는지도 같이 잰다 — 안 그러면 위 단언은
        // 「아무것도 안 하는 일 하나를 던졌다」로도 통과한다.
        받은_일.get(0).run();
        assertThat(발행됨).containsExactly("s1");
    }

    /**
     * 🔴 <b>큐가 차서 버릴 때 로그가 남아야 한다.</b> 표준 {@code DiscardPolicy}는 아무 말 없이
     * 버리는데, 그 창은 <b>발행권이 이미 잡혀</b> 다시 시도되지도 않는다 — 카드가 흔적 없이
     * 사라지고, 「clip이 죽어서 못 냈다」와 「급증이 없었다」가 로그에서 구분되지 않는다.
     *
     * <p>실물 빈 설정(core 2 · max 4 · queue 100)을 그대로 쓴다. 4개를 걸어 두고 큐 100을
     * 채운 뒤 105번째를 던지면 거절된다.
     */
    @Test
    void 큐가_차서_버릴_때_로그를_남긴다() throws Exception {
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
                (org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor)
                        new DetectorApplication().publishExecutor();
        executor.afterPropertiesSet();
        java.util.concurrent.CountDownLatch 잡아둠 = new java.util.concurrent.CountDownLatch(1);
        try (LogCaptor captor = new LogCaptor()) {
            for (int i = 0; i < 104; i++) {          // 도는 것 4 + 큐 100
                executor.execute(() -> {
                    try {
                        잡아둠.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            executor.execute(() -> { });            // 105번째 — 거절된다

            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("detect.publish_dropped"))
                    .as("버린 것을 아무도 모르면 카드가 조용히 사라진다")
                    .hasSize(1);
        } finally {
            잡아둠.countDown();
            executor.shutdown();
        }
    }

    private com.pokeclip.chat.detector.metrics.ChatMetricsStore store() {
        return new com.pokeclip.chat.detector.metrics.ChatMetricsStore(jdbc);
    }
}
