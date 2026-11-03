package com.pokeclip.clip.segment;

import com.pokeclip.clip.broadcast.Broadcast;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 방송 회차의 <b>귀속 행</b>을 고르는 한 곳(POK-196 · 설계 r7 8.2 「회차 고르기」 · r6 S-c1). 기준점
 * ({@link TimelineOriginReader}) · 마크 저장 위치({@link ServerClockPosition}) · 카드 장면
 * ({@code CardPositionReader})이 모두 이 행 열 위에서 floor를 한다.
 *
 * <p><b>회차로 먼저 자른다</b>(계약-세그먼트인덱스 6-6-5). 방송 번호({@code broadcasts.stream_id})가 곧 회차 머리 세션의
 * {@code session_id}다(계약9). 머리 세션의 {@code round_id}가 있으면 같은 물리 키 · 같은 회차의 세션 전부, 비었으면(옛 회차)
 * 머리에서 {@code inherits_session}으로 이어진 사슬이 회차다. 귀속 행은 그 세션들에 귀속된 장부 행뿐이다 — 회차 머리 앞 ·
 * 회차 사이의 비귀속 행, 다른 회차, 앞 방송 꼬리, 하한 거부 행은 이 열에 없다.
 *
 * <p><b>머리 세션 행이 없는 옛 방송만 시간창이다</b>(시작 편지 앞 2분 ~ 종료 편지 뒤 10분, 기준점의 옛 범위와 같다). 그때는
 * 창 안의 행을 귀속과 무관하게 읽는다 — 세션 장부 전의 방송이라 가를 칸이 없다. 시작 시각도 머리 행도 없으면 회차가 없다.
 *
 * <p>읽는 칸은 장부 소유자(1번)의 것이고 clip은 읽기만 한다. 판({@code timing_version})은 생산자가 c1 DDL로 더한 칸이다.
 */
@Component
public class RoundRows {

    /**
     * 머리 세션 여럿 → (머리, 회차 세션) 쌍. 머리 행이 없는 머리는 결과에 없다. 계승 사슬은 {@code UNION}으로 이어 같은 세션을
     * 두 번 따라가지 않는다(사슬이 고리를 이뤄도 끝난다). 색인: 세션 PK · {@code stream_sessions_inherits_idx} · 회차 세션은
     * {@code stream_sessions_stream_idx}(stream_id 범위).
     */
    static final String HEADS = """
            WITH RECURSIVE head AS (
                SELECT session_id, stream_id, round_id
                  FROM public.stream_sessions
                 WHERE session_id = ANY(?::text[])
            ), chain AS (
                SELECT h.session_id AS head_id, h.session_id
                  FROM head h
                 WHERE h.round_id IS NULL
                UNION
                SELECT c.head_id, s.session_id
                  FROM public.stream_sessions s
                  JOIN chain c ON s.inherits_session = c.session_id
            )
            SELECT h.session_id AS head_id, m.session_id
              FROM head h
              JOIN public.stream_sessions m ON m.stream_id = h.stream_id AND m.round_id = h.round_id
            UNION
            SELECT head_id, session_id FROM chain""";

    private static final String ROW_COLUMNS = """
            SELECT s.seq, s.start_pts_ms, s.duration_ms, s.start_wall_utc, s.playback_pdt, s.timing_version,
                   s.upload_state
              FROM public.stream_segments s""";

    /** 회차 세션에 귀속된 행, seq 순. 색인 {@code stream_segments_session_idx (stream_id, session_id, seq)}. */
    static final String MEMBER_ROWS = ROW_COLUMNS + """

             WHERE s.stream_id = ? AND s.session_id = ANY(?::text[])
             ORDER BY s.seq""";

    /** 옛 방송의 시간창 행, seq 순. 색인 {@code stream_segments_wall_idx (stream_id, start_wall_utc DESC)}. */
    static final String WINDOW_ROWS = ROW_COLUMNS + """

             WHERE s.stream_id = ?
               AND s.start_wall_utc >= ?
               AND s.start_wall_utc <= COALESCE(?::timestamptz, 'infinity'::timestamptz)
             ORDER BY s.seq""";

    /**
     * 회차 완결 술어(r6 S-c1 C6): 그 회차의 세션이 하나 이상이고 모두 끝났고 회차가 닫혔다. 생산자 종료 알림
     * ({@code media/internal/broadcast/outbox.go} 의 members · done)과 같은 열쇠 · 같은 술어다. 회차 칸이 빈 세션 · 없는
     * 세션은 술어가 없다(거짓) — 부르는 쪽이 방송 {@code ended}로만 정한다.
     */
    static final String ROUND_COMPLETE = """
            SELECT pg_catalog.count(*) > 0
                   AND pg_catalog.bool_and(m.state = 'ended' AND m.round_closed_at IS NOT NULL)
              FROM public.stream_sessions k
              JOIN public.stream_sessions m ON m.stream_id = k.stream_id AND m.round_id = k.round_id
             WHERE k.session_id = ? AND k.round_id IS NOT NULL""";

    /** 이 방송의 장부 행을 무엇으로 자르나. */
    public sealed interface Round permits Members, TimeWindow {

        /** 장부의 물리 키(장부 {@code stream_id}). */
        String ingestKey();
    }

    /** 회차 세션들. 비어 있지 않다(머리 자신이 늘 든다). */
    public record Members(String ingestKey, List<String> sessionIds) implements Round {
        public Members {
            sessionIds = List.copyOf(sessionIds);
        }
    }

    /** 머리 세션 행이 없는 옛 방송의 대체 — 벽시각 창. {@code hi}가 null이면 끝이 없다(방송 중). */
    public record TimeWindow(String ingestKey, Instant lo, Instant hi) implements Round {
    }

    /**
     * 회차 행 하나. 시각은 epoch ms다 — 벽시각({@code start_wall_utc})은 µs라 ms로 내린다. {@code pdtMs}는 빈 칸이면 null
     * (귀속 행에는 늘 있으나 DB 제약은 아니다). {@code timingVersion}은 null(옛 식) 또는 2.
     */
    public record Row(long seq, long startPtsMs, int durationMs, long wallMs, Long pdtMs, Integer timingVersion,
                      boolean uploaded) {

        /** 판 2 행인가. */
        public boolean v2() {
            return timingVersion != null && timingVersion == 2;
        }
    }

    private final JdbcTemplate jdbc;

    public RoundRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 페이지의 방송 전부를 왕복 한 번에 고른다(머리 세션이 있는 방송만 DB를 탄다).
     *
     * @return 방송 번호 → 회차. 머리 행도 시작 시각도 없는 방송은 없다
     */
    public Map<String, Round> roundsOf(List<Broadcast> broadcasts) {
        Map<String, List<String>> members = membersOf(broadcasts.stream().map(Broadcast::getStreamId).toList());
        Map<String, Round> rounds = new LinkedHashMap<>();
        for (Broadcast b : broadcasts) {
            List<String> sessions = members.get(b.getStreamId());
            if (sessions != null) {
                rounds.put(b.getStreamId(), new Members(b.ingestKey(), sessions));
            } else if (b.getStartedAt() != null) {
                Instant hi = b.getEndedAt() == null ? null : b.getEndedAt().plus(TimelineOriginReader.AFTER_END);
                rounds.put(b.getStreamId(),
                        new TimeWindow(b.ingestKey(), b.getStartedAt().minus(TimelineOriginReader.BEFORE_START), hi));
            }
        }
        return rounds;
    }

    public Optional<Round> roundOf(Broadcast broadcast) {
        return Optional.ofNullable(roundsOf(List.of(broadcast)).get(broadcast.getStreamId()));
    }

    /** 회차의 행, seq 오름차순(왕복 한 번). */
    public List<Row> rowsOf(Round round) {
        return switch (round) {
            case Members m -> jdbc.query(con -> {
                var ps = con.prepareStatement(MEMBER_ROWS);
                ps.setString(1, m.ingestKey());
                ps.setArray(2, con.createArrayOf("text", m.sessionIds().toArray()));
                return ps;
            }, RoundRows::toRow);
            case TimeWindow w -> jdbc.query(WINDOW_ROWS, RoundRows::toRow, w.ingestKey(), Timestamp.from(w.lo()),
                    w.hi() == null ? null : Timestamp.from(w.hi()));
        };
    }

    /** @param sessionId 회차의 아무 세션(보통 머리) */
    public boolean roundComplete(String sessionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(ROUND_COMPLETE, Boolean.class, sessionId));
    }

    private Map<String, List<String>> membersOf(List<String> heads) {
        Map<String, List<String>> members = new HashMap<>();
        if (heads.isEmpty()) {
            return members;
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement(HEADS);
            ps.setArray(1, con.createArrayOf("text", heads.toArray()));
            return ps;
        }, rs -> {
            members.computeIfAbsent(rs.getString("head_id"), k -> new ArrayList<>()).add(rs.getString("session_id"));
        });
        return members;
    }

    private static Row toRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp pdt = rs.getTimestamp("playback_pdt");
        short version = rs.getShort("timing_version");
        Integer timingVersion = rs.wasNull() ? null : (int) version;
        return new Row(
                rs.getLong("seq"),
                rs.getLong("start_pts_ms"),
                rs.getInt("duration_ms"),
                rs.getTimestamp("start_wall_utc").toInstant().toEpochMilli(),
                pdt == null ? null : pdt.toInstant().toEpochMilli(),
                timingVersion,
                "uploaded".equals(rs.getString("upload_state")));
    }
}
