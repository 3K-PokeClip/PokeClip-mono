package com.pokeclip.clip.thumbnail;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.CardPositionReader;
import com.pokeclip.clip.jumpcard.CardPositionReader.Scene;
import com.pokeclip.clip.segment.OffsetOrigin;
import com.pokeclip.clip.segment.RoundRows;
import com.pokeclip.clip.segment.RoundRows.Row;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * 사진을 찍을 대상과 그 장면이 든 영상 조각을 찾는다(읽기 전용). 조각 장부({@code stream_segments})는 1번 표라 SELECT만 한다
 * ({@code StreamSegmentReader}와 같은 규칙). 올라간 조각({@code upload_state = 'uploaded'})만 쓴다: 일꾼은 창고에서 받는다.
 *
 * <p><b>조각은 방송의 회차로 고른다</b>(POK-196 · {@link RoundRows}). 카드는 장면을 따로 판정하지 않고 카드 장면 함수
 * ({@link CardPositionReader})의 장면을 그대로 쓴다 — 카드 응답의 {@code playerAtMs} · {@code fileHead}와 사진이 같은 조각이다
 * (틈 · 비귀속 자리는 뒤 귀속 행 첫 화면 · 겹침은 나중 조각 · 설계 r7 8.2). 라이브는 회차 귀속 행 가운데 올라간 seq 최대 행이다.
 * 머리 세션 행이 없는 옛 방송만 방송 시간(시작 2분 전부터 종료 10분 뒤까지)으로 자른다. 원점 표지({@code offsetOrigin})는 고른 행의
 * 판이다({@link OffsetOrigin#ofTimingVersion}).
 */
@Component
public class ThumbnailTargets {

    /**
     * 사진이 없는 카드(보관 기한이 지난 방송은 조각이 지워졌으니 안 본다). 장면은 여기서 고르지 않는다 — 카드 장면 함수가 고르고,
     * 장면이 없거나(라이브 머리) 장면 조각이 아직 안 올라왔으면 그 카드는 이번 순회에 주문이 안 나간다(다음 순회에 다시 본다).
     */
    static final String CARDS = """
            %s
             ORDER BY c.id DESC
             LIMIT ?""".formatted(cardRows(""));

    /** 새 카드 명단(POK-475)의 카드만. 조건은 {@link #CARDS}와 한 글자도 다르지 않다: 번호 조건 하나를 더한다. */
    static final String CARDS_IN = """
            %s
             ORDER BY c.id DESC
             LIMIT ?""".formatted(cardRows("AND c.id = ANY(?)"));

    private static String cardRows(String extra) {
        return """
            SELECT c.id, c.stream_id, c.stream_timestamp_ms
              FROM jump_cards c
              JOIN broadcasts b ON b.stream_id = c.stream_id
              LEFT JOIN thumbnails t ON t.kind = 'card' AND t.target_id = c.id::text
             WHERE (b.vod_expires_at IS NULL OR b.vod_expires_at > now())
               AND (t.target_id IS NULL OR (t.s3_key IS NULL AND t.attempts < ? AND t.requested_at < ?))
               %s""".formatted(extra);
    }

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

    /** 구간 안에서 점수가 가장 높은 카드의 시각. 점수가 없는 카드는 뒤로, 같으면 먼저 생긴 카드. 가린 카드(POK-463)는 안 본다. */
    static final String TOP_CARD_IN = """
            SELECT stream_timestamp_ms FROM jump_cards
             WHERE stream_id = ? AND hidden_at IS NULL
               AND stream_timestamp_ms >= ? AND stream_timestamp_ms < ?
             ORDER BY score DESC NULLS LAST, id
             LIMIT 1""";

    /** 라이브 사진 대상 — 조각 {@code s3Key}, 그 조각 시작의 재생 시각 {@code shotAt}, 원점 표지. */
    record Live(String streamId, String s3Key, int durationMs, Instant shotAt, OffsetOrigin offsetOrigin) {
    }

    /** 주문할 카드 — 카드 장면의 조각 {@code s3Key} 안 {@code offsetMs}, 원점은 그 행의 판. */
    record Card(long id, String s3Key, long offsetMs, OffsetOrigin offsetOrigin) {
    }

    record ClipRow(long id, String streamId, String outputsJson, Long inAtMs, Long outAtMs) {
    }

    /** 사진이 없는 카드 줄(장면을 고르기 전). */
    private record Candidate(long id, String streamId, long streamTimestampMs) {
    }

    private static final RowMapper<Candidate> CANDIDATE = (rs, i) -> new Candidate(rs.getLong("id"),
            rs.getString("stream_id"), rs.getLong("stream_timestamp_ms"));

    private final JdbcTemplate jdbc;
    private final BroadcastRepository broadcasts;
    private final RoundRows rounds;
    private final CardPositionReader scenes;

    ThumbnailTargets(JdbcTemplate jdbc, BroadcastRepository broadcasts, RoundRows rounds, CardPositionReader scenes) {
        this.jdbc = jdbc;
        this.broadcasts = broadcasts;
        this.rounds = rounds;
        this.scenes = scenes;
    }

    /** 방송 중인 방송마다 회차에서 가장 최근에 올라간 조각 하나. 올라간 행이 없는 방송은 없다(다음 순회 — 계약 6-6-5). */
    List<Live> live(int limit) {
        List<Broadcast> on = broadcasts.findLiveStarted(limit);
        Map<String, Row> latest = rounds.latestUploadedOf(rounds.roundsOf(on));
        List<Live> rows = new ArrayList<>();
        for (Broadcast b : on) {
            Row row = latest.get(b.getStreamId());
            if (row == null) {
                continue;
            }
            Instant shotAt = Instant.ofEpochMilli(row.pdtMs() != null ? row.pdtMs() : row.wallMs());
            rows.add(new Live(b.getStreamId(), row.s3Key(), row.durationMs(), shotAt,
                    OffsetOrigin.ofTimingVersion(row.timingVersion())));
        }
        return rows;
    }

    List<Card> cards(int maxAttempts, Instant retryBefore, int limit) {
        return ordered(jdbc.query(CARDS, CANDIDATE, maxAttempts, Timestamp.from(retryBefore), limit));
    }

    /** {@link #cards}와 같되 번호가 {@code ids} 안인 카드만. */
    List<Card> cardsIn(List<Long> ids, int maxAttempts, Instant retryBefore, int limit) {
        return ordered(jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(CARDS_IN);
            ps.setInt(1, maxAttempts);
            ps.setTimestamp(2, Timestamp.from(retryBefore));
            ps.setArray(3, con.createArrayOf("bigint", ids.toArray(Long[]::new)));
            ps.setInt(4, limit);
            return ps;
        }, CANDIDATE));
    }

    /**
     * 후보 카드를 장면 주문으로 바꾼다 — 묶음이 여러 방송에 걸쳐도 방송마다 그 회차로 푼다(카드 장면 함수 한 번 · 장부 왕복 두
     * 번 · 계획 착수 체크리스트 C4). 장면이 없거나 장면 조각이 아직 안 올라간 카드는 빠진다. 순서는 후보 순서 그대로다.
     */
    private List<Card> ordered(List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<String, List<Long>> values = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            values.computeIfAbsent(c.streamId(), k -> new ArrayList<>()).add(c.streamTimestampMs());
        }
        Map<String, Map<Long, Scene>> found = scenes.scenesOf(broadcasts.findByStreamIdIn(values.keySet()), values);
        List<Card> cards = new ArrayList<>();
        for (Candidate c : candidates) {
            Scene scene = found.getOrDefault(c.streamId(), Map.of()).get(c.streamTimestampMs());
            if (scene == null) {
                continue;
            }
            scene.thumbnailOrder().ifPresent(order -> cards.add(
                    new Card(c.id(), scene.row().s3Key(), order.offsetMs(), order.offsetOrigin())));
        }
        return cards;
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
