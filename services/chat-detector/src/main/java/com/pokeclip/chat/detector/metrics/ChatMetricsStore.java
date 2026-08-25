package com.pokeclip.chat.detector.metrics;

import com.pokeclip.chat.detector.config.DetectionProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;

/**
 * {@code chat_metrics}를 읽고 쓰고 치운다. DB 접근은 JPA가 아니라 {@link JdbcTemplate}이다
 * (chat-collector의 {@code EndedStreamStore}와 같은 모양).
 *
 * <p><b>import 넷 중 셋({@code Timestamp}·{@code Instant}·{@code OptionalLong})은 이 태스크에서
 * 안 쓴다.</b> 태스크 6(발행권)과 7(치우기)이 이 클래스에 메서드를 더하면서 쓴다 — 계획 검증이
 * 그때 컴파일이 깨지는 것을 잡았고(F2), 여기서 미리 넣어 둔다.
 */
@Component
public class ChatMetricsStore {

    /**
     * <b>{@code DO NOTHING}이다.</b> 한 바퀴가 밀려 같은 창을 두 번 집계해도 첫 값이 남는다.
     * 갱신하면 이미 카드를 보낸 창의 수가 뒤에서 바뀌어, 카드에 실린 근거와 표가 어긋난다.
     */
    private static final String UPSERT = """
            INSERT INTO chat_metrics (stream_id, window_size_ms, window_start_ms, message_count, chatter_count)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (stream_id, window_size_ms, window_start_ms) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public ChatMetricsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 이 창 <b>직전</b>의 같은 창 크기 값들을 최신순으로. 지금 창은 뺀다 —
     * 넣으면 스파이크가 자기 기준선을 올려 클수록 덜 잡힌다.
     *
     * <p>{@code idx_chat_metrics_baseline (stream_id, window_size_ms, window_start_ms DESC)}를 탄다.
     */
    private static final String BASELINE = """
            SELECT %s
              FROM chat_metrics
             WHERE stream_id = ?
               AND window_size_ms = ?
               AND window_start_ms <  ?
               AND window_start_ms >= ?
             ORDER BY window_start_ms DESC
            """;

    /** @return 실제로 새로 들어간 줄 수. 이미 있던 창은 안 센다 */
    public int upsert(List<MetricRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        int[] affected = jdbc.batchUpdate(UPSERT, rows.stream()
                .map(r -> new Object[]{r.streamId(), r.windowSizeMs(), r.windowStartMs(),
                        r.messageCount(), r.chatterCount()})
                .toList());
        return java.util.Arrays.stream(affected).sum();
    }

    public int[] baselineCounts(String streamId, long windowSizeMs,
                                long beforeWindowStartMs, long sinceWindowStartMs,
                                DetectionProperties.Metric metric) {
        // 칸 이름은 열거형이 정하므로 값이 둘뿐이다 — 밖에서 온 문자열이 아니다.
        String column = metric == DetectionProperties.Metric.CHATTER ? "chatter_count" : "message_count";
        return jdbc.queryForList(BASELINE.formatted(column), Integer.class,
                        streamId, windowSizeMs, beforeWindowStartMs, sinceWindowStartMs)
                .stream().mapToInt(Integer::intValue).toArray();
    }
}
