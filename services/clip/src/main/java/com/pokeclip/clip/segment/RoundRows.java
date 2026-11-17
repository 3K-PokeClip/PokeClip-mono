package com.pokeclip.clip.segment;

import com.pokeclip.clip.broadcast.Broadcast;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 방송 회차의 <b>귀속 행</b>을 고르는 한 곳(POK-196 · 설계 r7 8.2 「회차 고르기」 · r6 S-c1). 기준점
 * ({@link TimelineOriginReader}) · 마크 저장 위치({@link ServerClockPosition}) · 카드 장면
 * ({@code CardPositionReader})이 모두 이 행 열 위에서 floor를 한다. 썸네일 순회(카드 사진 · 라이브 사진)도 이 행 열에서
 * 조각을 고른다.
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
     * 두 번 따라가지 않는다(사슬이 고리를 이뤄도 끝난다 · 깊이 상한은 두지 않는다 — 사슬 길이는 그 키의 세션 수 안이다). 사슬은
     * 머리와 같은 물리 키({@code stream_id}) 안에서만 잇는다 — 행 읽기도 그 키로 거르지만 회차 세션 목록부터 다른 키가 섞이지 않게
     * 한다. 색인: 세션 PK · {@code stream_sessions_inherits_idx} · 회차 세션은 {@code stream_sessions_stream_idx}(stream_id 범위).
     */
    static final String HEADS = """
            WITH RECURSIVE head AS (
                SELECT session_id, stream_id, round_id
                  FROM public.stream_sessions
                 WHERE session_id = ANY(?::text[])
            ), chain AS (
                SELECT h.session_id AS head_id, h.session_id, h.stream_id
                  FROM head h
                 WHERE h.round_id IS NULL
                UNION
                SELECT c.head_id, s.session_id, s.stream_id
                  FROM public.stream_sessions s
                  JOIN chain c ON s.inherits_session = c.session_id AND s.stream_id = c.stream_id
            )
            SELECT h.session_id AS head_id, m.session_id
              FROM head h
              JOIN public.stream_sessions m ON m.stream_id = h.stream_id AND m.round_id = h.round_id
            UNION
            SELECT head_id, session_id FROM chain""";

    private static final String ROW_FIELDS = """
            s.seq, s.start_pts_ms, s.duration_ms, s.start_wall_utc, s.playback_pdt, s.timing_version,
                   s.upload_state, s.s3_key""";

    private static final String ROW_COLUMNS = """
            SELECT %s
              FROM public.stream_segments s""".formatted(ROW_FIELDS);

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
     * 회차 여럿의 행, 회차마다 seq 순(썸네일 순회 한 묶음 — 방송이 몇이든 왕복 한 번). 회차는 (열쇠, 물리 키, 세션) 쌍 배열로,
     * 옛 방송 시간창은 (열쇠, 물리 키, 아래 끝, 위 끝) 배열로 받는다 — 방송마다 세션 수가 달라 2차원 배열로 못 싣는다
     * ({@code TimelineOriginReader.ORIGINS_BY_ROUND}와 같은 꼴). 🔴 세션마다 {@code LATERAL} 로 읽는다 — 평범한 잇기면 계획기가
     * 키 이력 전체를 훑어 해시 잇기를 고른다(2만 행 시드 실측 — {@code RoundRowsPlanTest}). 안쪽 {@code ORDER BY} 가 잇기로
     * 펴지는 것을 막아 세션마다 {@code stream_segments_session_idx} 범위({@link #MEMBER_ROWS}와 같은 꼴)가 된다.
     */
    static final String BATCH_ROWS = """
            SELECT m.round_key, r.*
              FROM unnest(?::text[], ?::text[], ?::text[]) AS m(round_key, ingest_key, session_id)
              CROSS JOIN LATERAL (
                    SELECT %1$s
                      FROM public.stream_segments s
                     WHERE s.stream_id = m.ingest_key AND s.session_id = m.session_id
                     ORDER BY s.seq) r
            UNION ALL
            SELECT w.round_key, r.*
              FROM unnest(?::text[], ?::text[], ?::timestamptz[], ?::timestamptz[]) AS w(round_key, ingest_key, lo, hi)
              CROSS JOIN LATERAL (
                    SELECT %1$s
                      FROM public.stream_segments s
                     WHERE s.stream_id = w.ingest_key
                       AND s.start_wall_utc >= w.lo
                       AND s.start_wall_utc <= COALESCE(w.hi, 'infinity'::timestamptz)
                     ORDER BY s.seq) r
             ORDER BY round_key, seq""".formatted(ROW_FIELDS);

    /**
     * 회차 여럿의 올라간 최신 행 하나씩(라이브 사진 — r6 S-c4). 회차는 세션마다 올라간 seq 최대 행을 세션 색인 뒤쪽에서 하나
     * 짚고 그 가운데 seq 최대를 쓴다. 옛 방송 시간창은 develop 라이브 조건 그대로(벽시각 최신 · 같으면 seq 최대). 매개변수
     * 꼴은 {@link #BATCH_ROWS}와 같다.
     */
    static final String LATEST_UPLOADED = """
            (SELECT DISTINCT ON (m.round_key) m.round_key, r.*
               FROM unnest(?::text[], ?::text[], ?::text[]) AS m(round_key, ingest_key, session_id)
               CROSS JOIN LATERAL (
                     SELECT %1$s
                       FROM public.stream_segments s
                      WHERE s.stream_id = m.ingest_key AND s.session_id = m.session_id
                        AND s.upload_state = 'uploaded'
                      ORDER BY s.seq DESC
                      LIMIT 1) r
              ORDER BY m.round_key, r.seq DESC)
            UNION ALL
            (SELECT w.round_key, r.*
               FROM unnest(?::text[], ?::text[], ?::timestamptz[], ?::timestamptz[]) AS w(round_key, ingest_key, lo, hi)
               CROSS JOIN LATERAL (
                     SELECT %1$s
                       FROM public.stream_segments s
                      WHERE s.stream_id = w.ingest_key
                        AND s.upload_state = 'uploaded'
                        AND s.start_wall_utc >= w.lo
                        AND s.start_wall_utc <= COALESCE(w.hi, 'infinity'::timestamptz)
                      ORDER BY s.start_wall_utc DESC, s.seq DESC
                      LIMIT 1) r)""".formatted(ROW_FIELDS);

    /**
     * 회차 완결 술어(r6 S-c1 C6): 그 회차의 세션이 하나 이상이고 모두 끝났고 회차가 닫혔다. 생산자 종료 알림
     * ({@code media/internal/broadcast/outbox.go} 의 members · done)과 같은 열쇠 · 같은 술어다. 회차 칸이 빈 세션 · 없는
     * 세션은 술어가 없다(거짓) — 부르는 쪽이 방송 {@code ended}로만 정한다.
     *
     * <p>세션 여럿을 한 왕복에 묻고 완결인 세션만 낸다(줄이 없으면 거짓). 묶음으로 묻는 쪽(썸네일 카드 순회)과 하나만 묻는 쪽이
     * 같은 문장을 쓴다 — 술어가 두 벌로 갈리지 않게.
     */
    static final String ROUND_COMPLETE = """
            SELECT k.session_id
              FROM public.stream_sessions k
              JOIN public.stream_sessions m ON m.stream_id = k.stream_id AND m.round_id = k.round_id
             WHERE k.session_id = ANY(?::text[]) AND k.round_id IS NOT NULL
             GROUP BY k.session_id
            HAVING pg_catalog.bool_and(m.state = 'ended' AND m.round_closed_at IS NOT NULL)""";

    /** 이 방송의 장부 행을 무엇으로 자르나. */
    public sealed interface Round permits Members, TimeWindow {

        /** 장부의 물리 키(장부 {@code stream_id}). */
        String ingestKey();
    }

    /**
     * 회차 세션들. 비어 있지 않다(머리 자신이 늘 든다). 행은 {@code ingestKey}(방송의 물리 키)로 읽는다 — 회차 세션의 장부
     * {@code stream_id} 가 이 키와 같다는 것이 계약(방송 번호 = 머리 세션 · 물리 키 = 그 세션의 {@code stream_id})이다. 어긋난
     * 세션의 행은 읽히지 않는다(모두 어긋나면 행 없음 — 마크는 503).
     */
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
     * (귀속 행에는 늘 있으나 DB 제약은 아니다). {@code timingVersion}은 null(옛 식) 또는 2. {@code s3Key}는 조각 창고 키다
     * (썸네일 주문서가 싣는다).
     */
    public record Row(long seq, long startPtsMs, int durationMs, long wallMs, Long pdtMs, Integer timingVersion,
                      boolean uploaded, String s3Key) {

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

    /**
     * 회차 여럿의 행(왕복 한 번 — 썸네일 순회 한 묶음이 여러 방송에 걸칠 때).
     *
     * @param rounds 열쇠(보통 방송 번호) → 회차
     * @return 열쇠 → 행, seq 오름차순. 행이 없는 회차는 없다. 회차가 비면 DB 를 안 탄다
     */
    public Map<String, List<Row>> rowsOf(Map<String, Round> rounds) {
        Map<String, List<Row>> rows = new HashMap<>();
        if (rounds.isEmpty()) {
            return rows;
        }
        jdbc.query(con -> bind(con, BATCH_ROWS, rounds), rs -> {
            rows.computeIfAbsent(rs.getString("round_key"), k -> new ArrayList<>()).add(toRow(rs, 0));
        });
        return rows;
    }

    /**
     * 회차 여럿의 올라간 최신 행(왕복 한 번 — 라이브 사진).
     *
     * @return 열쇠 → 행. 올라간 행이 없는 회차는 없다. 회차가 비면 DB 를 안 탄다
     */
    public Map<String, Row> latestUploadedOf(Map<String, Round> rounds) {
        Map<String, Row> latest = new HashMap<>();
        if (rounds.isEmpty()) {
            return latest;
        }
        jdbc.query(con -> bind(con, LATEST_UPLOADED, rounds), rs -> {
            latest.put(rs.getString("round_key"), toRow(rs, 0));
        });
        return latest;
    }

    /** {@link #BATCH_ROWS} · {@link #LATEST_UPLOADED} 의 매개변수 일곱 — 회차 몫 (열쇠, 키, 세션) · 시간창 몫 (열쇠, 키, 끝 둘). */
    private static PreparedStatement bind(Connection con, String sql, Map<String, Round> rounds) throws SQLException {
        List<String> memberOf = new ArrayList<>();
        List<String> memberKey = new ArrayList<>();
        List<String> memberSession = new ArrayList<>();
        List<String> windowOf = new ArrayList<>();
        List<String> windowKey = new ArrayList<>();
        List<Timestamp> lows = new ArrayList<>();
        List<Timestamp> highs = new ArrayList<>();
        rounds.forEach((key, round) -> {
            switch (round) {
                case Members m -> m.sessionIds().forEach(session -> {
                    memberOf.add(key);
                    memberKey.add(m.ingestKey());
                    memberSession.add(session);
                });
                case TimeWindow w -> {
                    windowOf.add(key);
                    windowKey.add(w.ingestKey());
                    lows.add(Timestamp.from(w.lo()));
                    highs.add(w.hi() == null ? null : Timestamp.from(w.hi()));
                }
            }
        });
        PreparedStatement ps = con.prepareStatement(sql);
        ps.setArray(1, con.createArrayOf("text", memberOf.toArray()));
        ps.setArray(2, con.createArrayOf("text", memberKey.toArray()));
        ps.setArray(3, con.createArrayOf("text", memberSession.toArray()));
        ps.setArray(4, con.createArrayOf("text", windowOf.toArray()));
        ps.setArray(5, con.createArrayOf("text", windowKey.toArray()));
        ps.setArray(6, con.createArrayOf("timestamptz", lows.toArray()));
        ps.setArray(7, con.createArrayOf("timestamptz", highs.toArray()));
        return ps;
    }

    /** @param sessionId 회차의 아무 세션(보통 머리) */
    public boolean roundComplete(String sessionId) {
        return completedOf(List.of(sessionId)).contains(sessionId);
    }

    /**
     * 회차가 완결인 세션들(왕복 한 번 · 술어는 {@link #roundComplete} 와 같은 문장).
     *
     * @param sessionIds 물을 세션(각각 회차의 아무 세션). 비면 묻지 않는다
     * @return {@code sessionIds} 가운데 회차가 완결인 것
     */
    public Set<String> completedOf(Collection<String> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Set.of();
        }
        List<String> complete = jdbc.query(con -> {
            var ps = con.prepareStatement(ROUND_COMPLETE);
            ps.setArray(1, con.createArrayOf("text", sessionIds.toArray()));
            return ps;
        }, (rs, rowNum) -> rs.getString("session_id"));
        return Set.copyOf(complete);
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
                "uploaded".equals(rs.getString("upload_state")),
                rs.getString("s3_key"));
    }
}
