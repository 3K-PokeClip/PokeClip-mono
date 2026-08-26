package com.pokeclip.chat.detector.metrics;

import com.pokeclip.chat.detector.config.DetectionProperties.Metric;
import com.pokeclip.chat.detector.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 창이 두 줄이 되지 않는다는 것을 <b>가게를 통해</b> 잰다.
 *
 * <p>표의 {@code UNIQUE} 제약 자체는 태스크 1이 생 SQL로 쟀다. 여기서 다시 재는 이유는
 * <b>가게가 그 제약을 실제로 쓰는지</b>가 별개이기 때문이다 — {@code ON CONFLICT} 줄을
 * 지우면 표는 그대로인데 두 번째 저장이 예외로 터지고, 그 예외는 한 바퀴를 통째로 끝낸다.
 * 2026-08-25 결함 주입으로 확인했다: 그 줄을 지워도 <b>다른 검사 스무 건이 전부 초록</b>이었다.
 *
 * <p>반환값도 같이 잰다. {@code upsert}의 javadoc이 「실제로 새로 들어간 줄 수」라고 적는데,
 * {@code rows.size()}로 바꿔도 아무도 안 잡았다(같은 주입). 계획 검증이 표 10에서
 * 「반환값을 재는 시험이 하나도 없다」고 미리 짚은 자리다.
 */
@SpringBootTest
class ChatMetricsStoreTest extends IntegrationTestSupport {

    /** 다른 검사와 안 겹치게 이 클래스 전용 방송 번호를 쓴다. 치울 때도 이 접두어만 지운다. */
    private static final String STREAM = "pok120-store-test";

    private final JdbcTemplate jdbc;
    private final ChatMetricsStore store;

    ChatMetricsStoreTest(JdbcTemplate jdbc, ChatMetricsStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    @BeforeEach
    void 내_줄만_치운다() {
        // 접두어로 지운다 — 아래 「남의 방송」 검사가 STREAM + "-other" 도 넣는다.
        jdbc.update("DELETE FROM chat_metrics WHERE stream_id LIKE ?", STREAM + "%");
    }

    private MetricRow 줄(long windowStartMs, int messageCount, int chatterCount) {
        return new MetricRow(STREAM, 5_000L, windowStartMs, messageCount, chatterCount);
    }

    @Test
    void 새_창은_들어가고_들어간_줄_수를_돌려준다() {
        assertThat(store.upsert(List.of(줄(1_000_000L, 10, 5), 줄(1_005_000L, 3, 2)))).isEqualTo(2);
        assertThat(줄수()).isEqualTo(2);
    }

    /**
     * 한 바퀴가 밀려 같은 창을 다시 집계해도 예외 없이 접히고, <b>새로 들어간 줄이 없다는
     * 것을 0으로 알린다.</b> 두 번째 값(99)이 표에 반영되지 않는 것도 같이 잰다 —
     * {@code DO NOTHING}이 아니라 {@code DO UPDATE}로 바뀌면 이미 카드를 보낸 창의 근거가
     * 뒤에서 바뀐다.
     */
    @Test
    void 같은_창을_다시_넣으면_0을_돌려주고_먼저_들어간_값이_남는다() {
        store.upsert(List.of(줄(1_000_000L, 10, 5)));

        assertThat(store.upsert(List.of(줄(1_000_000L, 99, 99)))).isZero();
        assertThat(줄수()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT message_count FROM chat_metrics WHERE stream_id = ? AND window_start_ms = 1000000",
                Integer.class, STREAM)).isEqualTo(10);
    }

    /** 새 창과 이미 있는 창이 한 묶음에 섞여 와도 새 것만 센다. */
    @Test
    void 섞여_오면_새로_들어간_것만_센다() {
        store.upsert(List.of(줄(1_000_000L, 10, 5)));

        assertThat(store.upsert(List.of(줄(1_000_000L, 10, 5), 줄(1_010_000L, 7, 4)))).isEqualTo(1);
        assertThat(줄수()).isEqualTo(2);
    }

    @Test
    void 빈_묶음은_DB를_안_건드리고_0이다() {
        assertThat(store.upsert(List.of())).isZero();
        assertThat(줄수()).isZero();
    }

    /**
     * 🔴 <b>지금 창은 자기 기준선에 안 들어간다.</b> 들어가면 스파이크가 자기 기준을 올려
     * <b>클수록 덜 잡힌다</b> — 이 기능이 조용히 반대로 도는 자리다.
     *
     * <p>{@code window_start_ms < ?}의 부등호 하나가 그 경계다. {@code <=}로 바꿔도
     * <b>다른 검사 마흔셋이 전부 초록</b>이었다(주입 K7, 직접 실측). 판정기 쪽 검사는
     * 배열을 손으로 만들어 넘기므로 <b>조회가 무엇을 담아 주는지는 안 본다.</b>
     */
    @Test
    void 기준선에_지금_창은_안_들어간다() {
        store.upsert(List.of(줄(1_000_000L, 10, 5), 줄(1_005_000L, 12, 6), 줄(1_010_000L, 999, 300)));

        int[] counts = store.baselineCounts(STREAM, 5_000L, 1_010_000L, 0L, Metric.MESSAGE);

        assertThat(counts).containsExactly(12, 10);
        assertThat(counts).as("지금 창(999)이 자기 기준선에 섞이면 안 된다").doesNotContain(999);
    }

    /**
     * 최신순이다. 지금은 중앙값이 순서를 안 타지만 <b>javadoc이 최신순이라고 약속</b>하고
     * {@code ORDER BY ... DESC}가 그것만을 위해 있다. 뒤에서 「최근 N개만」처럼 순서에
     * 기대는 코드가 붙으면 그때는 조용히 틀린다 — 약속을 지금 고정한다.
     */
    @Test
    void 기준선은_최신순이다() {
        store.upsert(List.of(줄(1_000_000L, 1, 1), 줄(1_005_000L, 2, 2), 줄(1_010_000L, 3, 3)));

        assertThat(store.baselineCounts(STREAM, 5_000L, 1_015_000L, 0L, Metric.MESSAGE))
                .containsExactly(3, 2, 1);
    }

    /** 지표를 사람 수로 바꾸면 <b>읽는 칸</b>도 바뀐다. 안 바뀌면 판정만 CHATTER고 기준선은 MESSAGE다. */
    @Test
    void 지표를_바꾸면_기준선도_그_칸을_읽는다() {
        store.upsert(List.of(줄(1_000_000L, 50, 1), 줄(1_005_000L, 60, 2)));

        assertThat(store.baselineCounts(STREAM, 5_000L, 1_010_000L, 0L, Metric.MESSAGE))
                .containsExactly(60, 50);
        assertThat(store.baselineCounts(STREAM, 5_000L, 1_010_000L, 0L, Metric.CHATTER))
                .containsExactly(2, 1);
    }

    /** 기간 밖(너무 오래된 창)은 안 들어간다 — 「평소」는 롤링이다. */
    @Test
    void 기준선_기간보다_오래된_창은_빠진다() {
        store.upsert(List.of(줄(1_000_000L, 7, 3), 줄(1_005_000L, 8, 4)));

        assertThat(store.baselineCounts(STREAM, 5_000L, 1_010_000L, 1_005_000L, Metric.MESSAGE))
                .containsExactly(8);
    }

    /** 남의 방송·다른 창 크기는 안 섞인다. */
    @Test
    void 남의_방송과_다른_창_크기는_기준선에_안_섞인다() {
        store.upsert(List.of(줄(1_000_000L, 11, 5),
                new MetricRow(STREAM, 3_000L, 1_002_000L, 77, 7),
                new MetricRow(STREAM + "-other", 5_000L, 1_000_000L, 88, 8)));

        assertThat(store.baselineCounts(STREAM, 5_000L, 1_010_000L, 0L, Metric.MESSAGE))
                .containsExactly(11);
    }

    @Test
    void 쌓인_창이_없으면_빈_배열이다() {
        assertThat(store.baselineCounts(STREAM, 5_000L, 1_010_000L, 0L, Metric.MESSAGE)).isEmpty();
    }

    private Integer 줄수() {
        return jdbc.queryForObject("SELECT count(*) FROM chat_metrics WHERE stream_id = ?", Integer.class, STREAM);
    }
}
