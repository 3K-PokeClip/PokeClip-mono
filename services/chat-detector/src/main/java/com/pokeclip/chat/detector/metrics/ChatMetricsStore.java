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
 * <p>{@code Timestamp}·{@code Instant}·{@code OptionalLong} import 셋은 태스크 2에서 미리
 * 넣어 뒀던 것이고 <b>여기 {@code claimForPublish}에서 드디어 쓰인다</b> — 계획 검증 F2가
 * 예고한 자리다(태스크 3까지는 안 쓰였다).
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

    /**
     * 발행권을 잡는다. <b>영향 행이 1일 때만 내가 보낸다.</b> 조회 후 갱신으로 가르면 동시
     * 요청에 뚫린다(auth·clip의 같은 자리 선례).
     *
     * <p><b>실패해도 되돌리지 않는다.</b> 늦게 도착한 카드는 편집자 화면을 과거로 오염시키므로
     * 버리는 편이 낫다(PRD 결정). 되돌리면 다음 바퀴가 또 집어 같은 실패를 반복한다.
     */
    private static final String CLAIM = """
            UPDATE chat_metrics SET published_at = ?
             WHERE stream_id = ? AND window_size_ms = ? AND window_start_ms = ?
               AND published_at IS NULL
            RETURNING id
            """;

    /** @return 잡았으면 그 줄의 번호. 이미 누가 잡았으면 빈 값 */
    public OptionalLong claimForPublish(String streamId, long windowSizeMs, long windowStartMs, Instant now) {
        List<Long> ids = jdbc.queryForList(CLAIM, Long.class,
                Timestamp.from(now), streamId, windowSizeMs, windowStartMs);
        return ids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(ids.get(0));
    }

    /** 아직 발행권이 안 잡힌 창들을 오래된 것부터. 발행 창 크기만 본다. */
    private static final String UNPUBLISHED = """
            SELECT stream_id, window_size_ms, window_start_ms, message_count, chatter_count
              FROM chat_metrics
             WHERE stream_id = ? AND window_size_ms = ? AND published_at IS NULL
             ORDER BY window_start_ms
            """;

    public List<MetricRow> unpublished(String streamId, long windowSizeMs) {
        return jdbc.query(UNPUBLISHED, (rs, n) -> new MetricRow(
                rs.getString("stream_id"), rs.getLong("window_size_ms"), rs.getLong("window_start_ms"),
                rs.getInt("message_count"), rs.getInt("chatter_count")), streamId, windowSizeMs);
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
