package com.pokeclip.chat.detector.metrics;

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
        jdbc.update("DELETE FROM chat_metrics WHERE stream_id = ?", STREAM);
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

    private Integer 줄수() {
        return jdbc.queryForObject("SELECT count(*) FROM chat_metrics WHERE stream_id = ?", Integer.class, STREAM);
    }
}
