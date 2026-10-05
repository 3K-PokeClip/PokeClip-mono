package com.pokeclip.clip.thumbnail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;

/**
 * 사진을 찍을 대상과 그 장면이 든 영상 조각을 찾는다(읽기 전용). 조각 장부({@code stream_segments})는 1번 표라 SELECT만 한다
 * ({@code StreamSegmentReader}와 같은 규칙).
 *
 * <p>조각은 <b>물리 키</b>({@code COALESCE(ingest_stream_id, stream_id)}, POK-233)로 찾고, 같은 키를 다음 방송도 쓰므로
 * 방송 시간(시작 2분 전 ~ 종료 10분 뒤, {@code TimelineOriginReader}와 같은 여유)으로 한 번 더 자른다. 올라간 조각
 * ({@code upload_state = 'uploaded'})만 쓴다: 일꾼은 창고에서 받는다.
 */
@Component
public class ThumbnailTargets {

    /** 방송 중인 방송마다 가장 최근에 올라간 조각 하나. */
    static final String LIVE = """
            SELECT b.stream_id, s.s3_key, s.duration_ms,
                   COALESCE(s.playback_pdt, s.start_wall_utc) AS shot_at
              FROM broadcasts b
              CROSS JOIN LATERAL (
                    SELECT seg.s3_key, seg.duration_ms, seg.playback_pdt, seg.start_wall_utc
                      FROM stream_segments seg
                     WHERE seg.stream_id = COALESCE(b.ingest_stream_id, b.stream_id)
                       AND seg.upload_state = 'uploaded'
                       AND seg.start_wall_utc >= b.started_at - interval '2 minutes'
                     ORDER BY seg.start_wall_utc DESC, seg.seq DESC
                     LIMIT 1) s
             WHERE b.status = 'live' AND b.started_at IS NOT NULL
             ORDER BY b.started_at DESC
             LIMIT ?""";

    /**
     * 사진이 없는 카드와 그 시점이 든 조각. 카드 시각({@code stream_timestamp_ms})은 조각의 {@code start_pts_ms}와 같은 축이다
     * (POK-255). 조각이 아직 안 올라왔으면 줄이 안 나온다(다음 순회에 다시 본다). 보관 기한이 지난 방송은 조각이 지워졌으니 안 본다.
     */
    static final String CARDS = """
            SELECT c.id, c.stream_timestamp_ms, seg.s3_key, seg.start_pts_ms
              FROM jump_cards c
              JOIN broadcasts b ON b.stream_id = c.stream_id
              LEFT JOIN thumbnails t ON t.kind = 'card' AND t.target_id = c.id::text
              CROSS JOIN LATERAL (
                    SELECT s.s3_key, s.start_pts_ms
                      FROM stream_segments s
                     WHERE s.stream_id = COALESCE(b.ingest_stream_id, b.stream_id)
                       AND s.upload_state = 'uploaded'
                       AND s.start_pts_ms <= c.stream_timestamp_ms
                       AND s.start_pts_ms + s.duration_ms > c.stream_timestamp_ms
                       AND s.start_wall_utc >= COALESCE(b.started_at - interval '2 minutes', '-infinity'::timestamptz)
                       AND s.start_wall_utc <= COALESCE(b.ended_at + interval '10 minutes', 'infinity'::timestamptz)
                     ORDER BY s.seq
                     LIMIT 1) seg
             WHERE (b.vod_expires_at IS NULL OR b.vod_expires_at > now())
               AND (t.target_id IS NULL OR (t.s3_key IS NULL AND t.attempts < ? AND t.requested_at < ?))
             ORDER BY c.id DESC
             LIMIT ?""";

    /** 사진이 없는 완성 영상. 구간은 그 영상을 만든 주문서의 레시피에서 읽는다(편집본이 그 뒤에 바뀌었을 수 있다). */
    static final String CLIPS = """
            SELECT cl.id, cl.stream_id, cl.outputs::text AS outputs,
                   (j.payload -> 'recipe' -> 'cut' ->> 'inAtMs')::bigint AS in_at_ms,
                   (j.payload -> 'recipe' -> 'cut' ->> 'outAtMs')::bigint AS out_at_ms
              FROM clips cl
              JOIN render_jobs j ON j.clip_id = cl.id
              LEFT JOIN thumbnails t ON t.kind = 'clip' AND t.target_id = cl.id::text
             WHERE cl.status = 'rendered'
               AND (t.target_id IS NULL OR (t.s3_key IS NULL AND t.attempts < ? AND t.requested_at < ?))
             ORDER BY cl.id DESC
             LIMIT ?""";

    /** 구간 안에서 점수가 가장 높은 카드의 시각. 점수가 없는 카드는 뒤로, 같으면 먼저 생긴 카드. 숨긴 카드는 안 본다. */
    static final String TOP_CARD_IN = """
            SELECT stream_timestamp_ms FROM jump_cards
             WHERE stream_id = ? AND hidden_at IS NULL
               AND stream_timestamp_ms >= ? AND stream_timestamp_ms < ?
             ORDER BY score DESC NULLS LAST, id
             LIMIT 1""";

    record Live(String streamId, String s3Key, int durationMs, Instant shotAt) {
    }

    record Card(long id, long streamTimestampMs, String s3Key, long segmentStartPtsMs) {
    }

    record ClipRow(long id, String streamId, String outputsJson, Long inAtMs, Long outAtMs) {
    }

    private final JdbcTemplate jdbc;

    ThumbnailTargets(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Live> live(int limit) {
        return jdbc.query(LIVE, (rs, i) -> new Live(rs.getString("stream_id"), rs.getString("s3_key"),
                rs.getInt("duration_ms"), rs.getTimestamp("shot_at").toInstant()), limit);
    }

    List<Card> cards(int maxAttempts, Instant retryBefore, int limit) {
        return jdbc.query(CARDS, (rs, i) -> new Card(rs.getLong("id"), rs.getLong("stream_timestamp_ms"),
                rs.getString("s3_key"), rs.getLong("start_pts_ms")), maxAttempts, Timestamp.from(retryBefore), limit);
    }

    List<ClipRow> clips(int maxAttempts, Instant retryBefore, int limit) {
        return jdbc.query(CLIPS, (rs, i) -> new ClipRow(rs.getLong("id"), rs.getString("stream_id"),
                rs.getString("outputs"), (Long) rs.getObject("in_at_ms"), (Long) rs.getObject("out_at_ms")),
                maxAttempts, Timestamp.from(retryBefore), limit);
    }

    /** {@code [fromMs, toMs)}(카드 축) 안 최고 점수 카드의 시각. */
    OptionalLong topCardIn(String streamId, long fromMs, long toMs) {
        List<Long> found = jdbc.queryForList(TOP_CARD_IN, Long.class, streamId, fromMs, toMs);
        return found.isEmpty() ? OptionalLong.empty() : OptionalLong.of(found.get(0));
    }
}
