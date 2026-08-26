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

    /**
     * 아직 발행권이 안 잡힌 창들을 오래된 것부터. 발행 창 크기만 본다.
     *
     * <h2>🔴 시간 하한이 반드시 있어야 한다(로컬 리뷰 라운드 2)</h2>
     *
     * 하한이 없으면 <b>보관 기간(기본 24시간) 전체</b>가 대상이 된다. 두 가지가 같이 온다.
     *
     * <ul>
     *   <li><b>상시 비용</b> — 인덱스에 {@code published_at}이 없어 그 방송·그 창 크기의
     *       모든 줄을 훑고 필터로 버린다. 24시간이면 방송당 <b>17,280줄</b>이고
     *       100방송 순회가 <b>105~308ms</b>다(전용 DB에 100방송·24시간치 547만 줄을 심어 실측).
     *       정상 바퀴 206~527ms 위에 그대로 얹혀 1초 예산의 여유를 절반 가까이 먹는다</li>
     *   <li><b>🔴 설정을 바꾸는 날</b> — 발행 창이 아닌 크기(3초·10초)의 줄은 이 조회에 안 걸려
     *       {@code published_at}이 <b>영영 NULL로 쌓인다.</b> {@code publish-window-ms}를
     *       그 크기로 바꾸면(기능명세 C7 M5의 A/B가 그 경로다) 첫 바퀴의 판정 대상이
     *       <b>288만 줄</b>이 되고 조회만 <b>1,024ms</b>다. 줄마다 기준선 조회와 발행권 UPDATE가
     *       붙어 한 바퀴가 분 단위가 되는데, {@code fixedDelay}라 <b>그동안 판별이 멈춘다</b></li>
     * </ul>
     *
     * <p><b>하한은 집계가 되돌아보는 폭과 같은 값이다</b> — 그보다 오래된 창은 집계도
     * 다시 안 하므로 판정만 하는 것이 앞뒤가 안 맞고, PRD의 「늦게 만든 카드는 되감기 창을
     * 벗어나 가치가 없다」와도 같은 방향이다.
     *
     * @param sinceWindowStartMs 이 눈금 이후의 창만. 호출자가 집계와 <b>같은 산식</b>으로 낸다
     */
    private static final String UNPUBLISHED = """
            SELECT stream_id, window_size_ms, window_start_ms, message_count, chatter_count
              FROM chat_metrics
             WHERE stream_id = ? AND window_size_ms = ? AND published_at IS NULL
               AND window_start_ms >= ?
             ORDER BY window_start_ms
            """;

    public List<MetricRow> unpublished(String streamId, long windowSizeMs, long sinceWindowStartMs) {
        return jdbc.query(UNPUBLISHED, (rs, n) -> new MetricRow(
                        rs.getString("stream_id"), rs.getLong("window_size_ms"), rs.getLong("window_start_ms"),
                        rs.getInt("message_count"), rs.getInt("chatter_count")),
                streamId, windowSizeMs, sinceWindowStartMs);
    }

    /**
     * 발행권을 되돌린다. <b>수집 서버 계약이 「재시도할 자리」로 명시한 경우에만 부른다</b> —
     * 조각이 아직 장부에 안 온 {@code not_yet_indexed} 하나다. 「창구를 못 물었다」는
     * 여기 안 든다(로컬 리뷰 라운드 3에서 되돌렸다 — 사정은 {@code HighlightPublisher}에).
     *
     * <p>🔴 <b>이것이 PRD의 「실패해도 되돌리지 않는다」를 뒤집는 것이 아니다.</b> 그 결정은
     * <b>clip에 못 넣은 경우</b>에 대한 것이고(거절·시도 소진), 그때는 다시 보내도 같은 답이라
     * 되돌리면 같은 실패만 반복한다. 여기는 <b>몇 초 뒤면 답이 바뀌는</b> 자리다.
     *
     * <p><b>무한 재시도가 아니다</b> — 되돌린 창도 {@link #unpublished}의 시간 하한을 벗어나면
     * 목록에서 사라진다. 즉 되돌아보기(기본 1분) 동안만 다시 시도한다.
     *
     * <p>{@code published_at IS NOT NULL} 조건이 막는 것은 <b>이미 풀린 줄을 다시 쓰는 것</b>이다.
     * 지금 부르는 자리는 하나뿐이라 그런 일이 안 생기지만, 이 메서드가 <b>「푼다」가 아니라
     * 「잡힌 것을 푼다」</b>라는 것을 SQL이 스스로 말하게 둔다.
     *
     * @return 실제로 되돌린 줄 수(0 또는 1)
     */
    private static final String RELEASE = """
            UPDATE chat_metrics SET published_at = NULL
             WHERE id = ? AND published_at IS NOT NULL
            """;

    public int releaseClaim(long metricId) {
        return jdbc.update(RELEASE, metricId);
    }

    /**
     * {@code idx_chat_metrics_created}를 탄다.
     *
     * <p>경계는 {@code <}다 — 그 시각에 딱 만들어진 줄은 <b>남긴다</b>. {@code <=}로 밀면
     * 보관 기간이 조용히 하루−1이 된다.
     */
    private static final String SWEEP = "DELETE FROM chat_metrics WHERE created_at < ?";

    /** @return 지운 줄 수 */
    public int sweepOlderThan(Instant before) {
        return jdbc.update(SWEEP, Timestamp.from(before));
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
