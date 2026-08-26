package com.pokeclip.chat.detector;

import com.pokeclip.chat.detector.observe.LateArrivalReporter;
import com.pokeclip.chat.detector.support.IntegrationTestSupport;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>{@code DetectorApplication}의 {@code @Bean} 배선을 잰다 — 「어느 설정이 어느 인자로
 * 가는가」다.</b>
 *
 * <p><b>왜 따로 필요한가.</b> 다른 검사들은 {@code MetricsSweeper}·{@code LateArrivalReporter}를
 * <b>전부 손으로 조립</b>해서 쓴다(일곱 자리). 그래서 <b>조립 자체가 무방비였다</b> — 배선을
 * 엉뚱한 설정으로 바꿔도 백스물두 건이 전부 초록이었다(감사 3회차 W-1).
 *
 * <p><b>심각도 상한</b>: 치우기 배선을 {@code retention()} 대신 {@code cycleInterval()}(1초)로
 * 바꾸면 10분마다 <b>1초보다 오래된 줄을 전부</b> 지운다. 기준선이 영영 안 쌓여 <b>모든 방송이
 * 워밍업에 갇히고 카드가 한 장도 안 나간다.</b> 오류도 로그도 없다.
 *
 * <p>태스크 7에서 잡은 {@code clock.get()}과 <b>결과가 같은데 들어온 문이 다르다</b> —
 * 그때는 <b>인자</b>였고 여기는 <b>배선</b>이다. 같은 결과에 이르는 문이 둘이었고 하나만 닫혀 있었다.
 *
 * <p><b>배선마다 검사를 갈라 놓았다.</b> 한 검사에 몰면 「어느 배선이 덮였나」를 못 가른다.
 */
@SpringBootTest
class BeanWiringTest extends IntegrationTestSupport {

    /** 이 클래스 전용. 주기 작업이 같은 표에 쓰므로 내 줄만 세고 지운다. */
    private static final String STREAM = "pok120-wiring";

    private final JdbcTemplate jdbc;
    private final MetricsSweeper sweeper;
    private final LateArrivalReporter reporter;

    BeanWiringTest(JdbcTemplate jdbc, MetricsSweeper sweeper, LateArrivalReporter reporter) {
        this.jdbc = jdbc;
        this.sweeper = sweeper;
        this.reporter = reporter;
    }

    @BeforeEach
    void 표를_준비하고_내_줄만_치운다() {
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
        jdbc.update("DELETE FROM chat_metrics WHERE stream_id LIKE ?", STREAM + "%");
    }

    /** 나이만 다른 집계 줄. 시각은 DB의 now() 기준이라 조립된 빈의 시계와 같은 축이다. */
    private void 집계줄(long windowStartMs, String 나이) {
        jdbc.update("""
                INSERT INTO chat_metrics
                    (stream_id, window_size_ms, window_start_ms, message_count, chatter_count, created_at)
                VALUES (?, 5000, ?, 1, 1, now() - ?::interval)
                """, STREAM, windowStartMs, 나이);
    }

    /** 전달 지연 3초 — 유예(2초)는 넘고 발행 창+유예(7초)는 안 넘는다. 방금 도착해 활성이다. */
    private void 늦은_채팅_한_건() {
        jdbc.update("""
                INSERT INTO chat_messages
                    (channel_id, sender_channel_id, content, message_time, received_at, content_sha256, stream_id)
                VALUES ('c1', 'u1', 'hi', now() - interval '3.1 seconds', now() - interval '0.1 seconds',
                        repeat('a', 64), ?)
                """, STREAM);
    }

    private Integer 남은_줄(long windowStartMs) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM chat_metrics WHERE stream_id = ? AND window_start_ms = ?",
                Integer.class, STREAM, windowStartMs);
    }

    /**
     * 치우기 배선이 <b>보관 기간</b>(기본 24시간)을 쓴다. 기준선 기간(15분)으로 잘못 배선하면
     * 한 시간 된 줄이 사라진다 — <b>베이스라인이 15분치밖에 안 남아</b> 판정이 얕아진다.
     */
    @Test
    void 치우기_배선은_보관_기간을_쓴다() {
        집계줄(1_000L, "30 hours");   // 보관 기간을 넘겼다 — 지워야 한다
        집계줄(2_000L, "1 hour");     // 안 넘겼다 — 남아야 한다

        sweeper.sweep();

        assertThat(남은_줄(1_000L)).as("보관 기간이 지난 줄은 지워야 한다").isZero();
        assertThat(남은_줄(2_000L))
                .as("한 시간 된 줄이 사라지면 보관 기간이 아니라 더 짧은 설정이 배선된 것이다")
                .isEqualTo(1);
    }

    /**
     * 🔴 치우기 배선이 <b>주기 간격</b>(기본 1초)이 아니다. 위 검사와 <b>일부러 갈라 놓았다</b> —
     * 10분 된 줄은 기준선 기간(15분)으로 잘못 배선해도 살아남으므로, <b>이 검사만이</b>
     * 「1초짜리 값이 배선됐다」를 잡는다.
     *
     * <p>그 상태가 이 배선 실수의 <b>심각도 상한</b>이다 — 표가 사실상 비어 모든 방송이
     * 워밍업에 갇힌다.
     */
    @Test
    void 치우기_배선은_주기_간격이_아니다() {
        집계줄(3_000L, "10 minutes");

        sweeper.sweep();

        assertThat(남은_줄(3_000L))
                .as("10분 된 줄이 사라지면 초 단위 설정이 배선된 것이다").isEqualTo(1);
    }

    /**
     * 관측 배선이 <b>유예</b>(기본 2초)를 쓴다. 유예는 「놓칠 수 있었던 상한」의 문턱이자
     * 로그에 그대로 실려 <b>다음 사람이 값을 조정할 때 보는 숫자</b>다 — 엉뚱한 설정이 실리면
     * 조정의 근거가 통째로 어긋난다.
     *
     * <p>세 값이 전부 {@code Duration}이라 <b>서로 바꿔 배선해도 컴파일된다.</b> 위 검사는
     * 유예를 1초로 바꿔도 통과한다(지연 3초가 어느 쪽이든 문턱을 넘는다) — 그래서 갈라 놓았다.
     */
    @Test
    void 관측_배선은_유예를_쓴다() {
        늦은_채팅_한_건();

        try (LogCaptor captor = new LogCaptor()) {
            reporter.report();

            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("detect.late_arrivals"))
                    .singleElement().satisfies(line -> assertThat(line).contains("windowGraceMs=2000"));
        }
    }

    /** 관측 배선이 <b>보고 간격</b>(기본 10분)을 되돌아보는 폭으로 쓴다. 폭이 좁으면 표본이 준다. */
    @Test
    void 관측_배선은_보고_간격을_되돌아보는_폭으로_쓴다() {
        늦은_채팅_한_건();

        try (LogCaptor captor = new LogCaptor()) {
            reporter.report();

            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("detect.late_arrivals"))
                    .singleElement().satisfies(line -> assertThat(line).contains("lookbackMs=600000"));
        }
    }

    /**
     * 🔴 관측 배선이 <b>활성 창</b>(기본 60초)으로 볼 방송을 고른다. 더 긴 설정이 배선되면
     * <b>이미 끝난 방송</b>이 표본에 섞여 유예값 조정의 근거가 오염된다.
     *
     * <p>5분 전에 끊긴 방송을 만든다 — 활성 창(60초)으로는 안 잡히고, 잘못 배선되기 쉬운
     * 기준선 기간(15분)·보고 간격(10분)으로는 잡힌다.
     */
    @Test
    void 관측_배선은_활성_창으로_방송을_고른다() {
        jdbc.update("""
                INSERT INTO chat_messages
                    (channel_id, sender_channel_id, content, message_time, received_at, content_sha256, stream_id)
                VALUES ('c1', 'u1', 'hi', now() - interval '5 minutes', now() - interval '5 minutes',
                        repeat('a', 64), ?)
                """, STREAM);

        try (LogCaptor captor = new LogCaptor()) {
            reporter.report();

            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("detect.late_arrivals"))
                    .as("끊긴 지 5분 된 방송이 잡히면 활성 창보다 긴 설정이 배선된 것이다")
                    .isEmpty();
        }
    }

    /**
     * 관측 배선이 <b>발행 창</b>(기본 5초)을 쓴다. 「반드시 놓친 하한」의 문턱이
     * {@code 발행 창 + 유예}(7초)인데, 발행 창을 0으로 배선하면 문턱이 유예(2초)로 내려가
     * <b>「놓칠 수 있었다」와 「반드시 놓쳤다」가 같은 값이 된다</b> — 유예값을 정할 근거가
     * 두 개에서 한 개로 줄어든다.
     */
    @Test
    void 관측_배선은_발행_창을_쓴다() {
        늦은_채팅_한_건();

        try (LogCaptor captor = new LogCaptor()) {
            reporter.report();

            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("detect.late_arrivals"))
                    .singleElement().satisfies(line -> assertThat(line)
                            .contains("beyondGrace=1")
                            .as("발행 창을 0으로 배선하면 문턱이 유예와 같아져 여기가 1이 된다")
                            .contains("beyondWindowAndGrace=0"));
        }
    }
}
