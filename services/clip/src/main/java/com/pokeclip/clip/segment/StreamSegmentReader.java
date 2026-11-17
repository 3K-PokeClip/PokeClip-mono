package com.pokeclip.clip.segment;

import com.pokeclip.clip.broadcast.ExpiredBroadcastReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code stream_segments}를 <b>읽기만</b> 한다. 이 표의 소유는 1번(Media)이고 clip은
 * 계약 4절이 허용한 SELECT만 쓴다(ADR-030) — <b>여기에 INSERT·UPDATE·DELETE를 만들지 않는다.</b>
 * JPA 엔티티로 매핑하지 않는 것도 같은 이유다(엔티티를 두면 flush 한 번에 남의 표에 쓰게 된다).
 */
@Component
public class StreamSegmentReader {

    /**
     * 구간 겹침 조건. <b>{@code BETWEEN}이 아니다</b> — 계약-세그먼트인덱스 2절이
     * 2026-08-14에 정정했다. {@code BETWEEN}은 클립 시작을 걸치는 첫 조각을 빠뜨려
     * 클립 머리가 최대 4초(조각 하나) 잘린다.
     *
     * <p>미만/초과인 이유는 <b>경계에 닿기만 한 조각은 겹침 길이가 0</b>이라 프레임을
     * 하나도 주지 않기 때문이다.
     *
     * <p>상태로 거르지 않는다 — pending 조각이 목록에 실려 와야 조립기가 「중간에서 끊긴다」를
     * 판정할 수 있다.
     */
    private static final String OVERLAPPING = """
            SELECT seq, start_pts_ms, duration_ms, s3_key, upload_state, is_discontinuity
              FROM public.stream_segments
             WHERE stream_id = ?
               AND start_pts_ms < ?
               AND start_pts_ms + duration_ms > ?
             ORDER BY seq""";

    /**
     * 재생 축으로 겹침을 찾는다(POK-125 렌더 주문). {@code playback_pdt}가 채워진 줄은 그것으로, 아직 빈 줄
     * (6-1 전 데이터·미확정)은 {@code start_wall_utc}로 잰다 — 두 값 다 timestamptz라 한 식에 든다.
     * 겹침 조건은 위 {@link #OVERLAPPING}과 같은 반개 구간이고 축만 다르다.
     *
     * <p>{@code playback_pdt}·{@code session_id}는 계약-세그먼트인덱스 6-1(3번 승인 2026-09-01)의 칸이다 — 그 전
     * DDL로 만든 표에는 없어 이 조회가 죽는다. 시험 시드가 그 칸을 같이 심는 이유다.
     */
    private static final String OVERLAPPING_BY_PLAYBACK_TIME = """
            SELECT seq,
                   (EXTRACT(EPOCH FROM COALESCE(playback_pdt, start_wall_utc)) * 1000)::bigint AS start_at_ms,
                   duration_ms, s3_key, upload_state
              FROM public.stream_segments
             WHERE stream_id = ?
               AND COALESCE(playback_pdt, start_wall_utc) < to_timestamp(? / 1000.0)
               AND COALESCE(playback_pdt, start_wall_utc) + make_interval(secs => duration_ms / 1000.0) > to_timestamp(? / 1000.0)
             ORDER BY seq""";

    private final JdbcTemplate jdbc;

    public StreamSegmentReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code [startMs, endMs)}와 겹치는 조각을 {@code seq} 오름차순으로 준다.
     *
     * <p><b>거르지 않은 전부다</b> — {@code uploaded}가 아닌 조각도 그대로 실린다.
     * 무엇을 쓸 수 있는지는 조립기가 정한다.
     *
     * <p>인자 순서에 주의: SQL 물음표는 {@code ingestKey} → {@code endMs} → {@code startMs}다.
     *
     * @param ingestKey 물리 키({@code Broadcast.ingestKey()}, POK-233). 방송 번호가 아니다
     */
    public List<StreamSegmentRow> findOverlapping(String ingestKey, long startMs, long endMs) {
        return jdbc.query(OVERLAPPING,
                (rs, rowNum) -> new StreamSegmentRow(
                        rs.getLong("seq"),
                        rs.getLong("start_pts_ms"),
                        rs.getInt("duration_ms"),
                        rs.getString("s3_key"),
                        rs.getString("upload_state"),
                        rs.getBoolean("is_discontinuity")),
                ingestKey, endMs, startMs);
    }

    /**
     * 재생 축 {@code [inAtMs, outAtMs)}(UTC epoch ms)와 겹치는 조각을 {@code seq} 오름차순으로. 상태로 거르지 않는 것은
     * {@link #findOverlapping}과 같다 — 무엇을 쓸 수 있는지는 조립기가 정한다.
     *
     * <p>인자 순서: {@code ingestKey} → {@code outAtMs} → {@code inAtMs}.
     *
     * @param ingestKey 물리 키(POK-233). 방송 번호가 아니다
     */
    public List<SegmentSource> findOverlappingByPlaybackTime(String ingestKey, long inAtMs, long outAtMs) {
        return jdbc.query(OVERLAPPING_BY_PLAYBACK_TIME,
                (rs, rowNum) -> new SegmentSource(
                        rs.getLong("seq"),
                        rs.getLong("start_at_ms"),
                        rs.getInt("duration_ms"),
                        rs.getString("s3_key"),
                        rs.getString("upload_state")),
                ingestKey, outAtMs, inAtMs);
    }

    /**
     * seq 다음 행(장부 PK 순 · 회차 · 세션과 무관). 끝 소리 확보 후보다. 장부 바로 다음 행이라 이웃 표시는 참이다.
     *
     * <p><b>회차 · 방송으로 자르지 않는다</b> — 계약 6-6-5 「컷 끝 소리 확보(6-6-8)는 별도 규칙이라 빠지지 않는다」. 같은 녹화기가
     * 이어 쓴 다음 파일이면 다른 회차 세션이어도 컷 끝 소리가 거기 있다. 다음 회차 · 다음 방송 행으로 넘어가는 것을 막는 것은
     * 확보 판정({@link AudioCaptureRule})이 보는 후보의 {@code is_discontinuity} 다 — 새 연결의 첫 조각은 같은 녹화기가 이어 쓴
     * 파일이 아니라 참이다(계약 6-6). 그 불변식이 깨지면(훅 유실 + 1.5초 안 재접속) 같은 물리 키의 다음 조각 하나가 확보로 실린다.
     */
    public Optional<RoundRow> findRowAfter(String ingestKey, long seq) {
        return jdbc.query(ROW_AFTER, StreamSegmentReader::readRoundRow, ingestKey, seq).stream()
                .findFirst()
                .map(r -> r.withFollows(true));
    }

    /**
     * 컷 판정 회차 행 읽기의 칸(POK-196 · kty 2026-11-12 D1). 시각은 두 칸 다 싣고 축(목록 PDT)은 {@link CutPlanner} 가 고른다.
     * {@code ledger_previous_seq} 는 장부 바로 앞 행 seq(회차 · 세션과 무관 · 행마다 PK 역순 한 행)다 — 「목록 바로 앞 행이 장부 바로
     * 앞 행인가」 를 가르려고 읽는다.
     */
    private static final String ROUND_COLUMNS = """
            SELECT s.seq, s.start_wall_utc, s.playback_pdt, s.duration_ms, s.is_discontinuity, s.upload_state,
                   s.timing_version, s.s3_key,
                   (SELECT max(p.seq) FROM public.stream_segments p
                     WHERE p.stream_id = s.stream_id AND p.seq < s.seq) AS ledger_previous_seq
              FROM public.stream_segments s""";

    /** seq 다음 행 하나(장부 PK 순 · 회차로 자르지 않음). */
    static final String ROW_AFTER = ROUND_COLUMNS + """

             WHERE s.stream_id = ? AND s.seq > ?
             ORDER BY s.seq
             LIMIT 1""";

    /** 회차 세션에 귀속된 행 전부, seq 순. 색인 {@code stream_segments_session_idx (stream_id, session_id, seq)}. */
    static final String ROUND_MEMBER_ROWS = ROUND_COLUMNS + """

             WHERE s.stream_id = ? AND s.session_id = ANY(?::text[])
             ORDER BY s.seq""";

    /** 머리 세션 행이 없는 옛 방송 — 방송 시간창 안 행 전부. 색인 {@code stream_segments_wall_idx}. */
    static final String ROUND_WINDOW_ROWS = ROUND_COLUMNS + """

             WHERE s.stream_id = ?
               AND s.start_wall_utc >= ?
               AND s.start_wall_utc <= COALESCE(?::timestamptz, 'infinity'::timestamptz)
             ORDER BY s.seq""";

    /**
     * 준비 후보(설계 r7 9.1 읽기 5) — 머리마다 장부 바로 앞 행 하나(PK 역순 · 회차 · 세션 · 판으로 거르지 않음)와 이 방송 장부 구간
     * 시작을 한 왕복에. 바로 앞 행은 seq 만 먼저 고르고(PK 색인), 구간 시작 앞(앞 방송 몫)이면 칸을 읽지 않는다 — 경계를 조회
     * 단계에 건다(계약 5-2 · K4). 머리가 그 방송에 없으면 줄이 없다.
     */
    static final String ROWS_BEFORE = """
            WITH section AS (
            """ + ExpiredBroadcastReader.SECTION_START + """
            )
            SELECT h.head, c.seq, c.duration_ms, c.upload_state, c.s3_key,
                   section.segments_from IS NOT NULL AS previous_broadcast
              FROM unnest(?::bigint[]) AS h(head)
              JOIN section ON true
              LEFT JOIN LATERAL (
                    SELECT max(p.seq) AS seq FROM public.stream_segments p
                     WHERE p.stream_id = ? AND p.seq < h.head) prev ON true
              LEFT JOIN public.stream_segments c
                     ON c.stream_id = ? AND c.seq = prev.seq
                    AND (section.segments_from IS NULL OR c.start_wall_utc >= section.segments_from)""";

    /**
     * 회차 행 전부, seq 순(컷 판정 입력 — 가장자리는 판정이 메모리에서 F 로 고른다 · POK-196 · 설계 r7 9.1). 상태로 거르지 않는다.
     * 각 행의 {@code followsLedgerPrevious} 는 「목록 바로 앞 행이 장부 바로 앞 행인가」(사이에 비귀속 행이 끼면 거짓 · 첫 행 거짓)다.
     * 왕복 한 번 — 회차 길이에 비례해 읽고 키 이력은 훑지 않는다.
     */
    public List<RoundRow> findRoundRows(RoundRows.Round round) {
        List<RoundRead> rows = switch (round) {
            case RoundRows.Members m -> jdbc.query(con -> {
                var ps = con.prepareStatement(ROUND_MEMBER_ROWS);
                ps.setString(1, m.ingestKey());
                ps.setArray(2, con.createArrayOf("text", m.sessionIds().toArray()));
                return ps;
            }, StreamSegmentReader::readRoundRow);
            case RoundRows.TimeWindow w -> jdbc.query(ROUND_WINDOW_ROWS, StreamSegmentReader::readRoundRow,
                    w.ingestKey(), Timestamp.from(w.lo()), w.hi() == null ? null : Timestamp.from(w.hi()));
        };
        List<RoundRow> linked = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Long previous = rows.get(i).ledgerPreviousSeq();
            boolean follows = i > 0 && previous != null && previous == rows.get(i - 1).seq();
            linked.add(rows.get(i).withFollows(follows));
        }
        return linked;
    }

    /**
     * 머리마다 이 방송 장부 구간 안의 장부 바로 앞 행(디코더 준비 후보 · 설계 r7 9.1 읽기 5 · 9.4). 왕복 한 번.
     *
     * @param ingestKey 물리 키(장부 {@code stream_id})
     * @param streamId 방송 번호 — 장부 구간 시작을 이 방송으로 잰다
     * @param heads 묻는 머리 seq. 비어 있으면 안 된다(부르는 쪽이 거른다)
     */
    public CutPlanner.Preceding findRowsBefore(String ingestKey, String streamId, List<Long> heads) {
        Map<Long, CutDecision.LedgerNeighbor> found = new HashMap<>();
        AtomicBoolean previousBroadcast = new AtomicBoolean();
        jdbc.query(con -> {
            var ps = con.prepareStatement(ROWS_BEFORE);
            ps.setString(1, streamId);
            ps.setArray(2, con.createArrayOf("bigint", heads.toArray()));
            ps.setString(3, ingestKey);
            ps.setString(4, ingestKey);
            return ps;
        }, rs -> {
            previousBroadcast.set(rs.getBoolean("previous_broadcast"));
            long seq = rs.getLong("seq");
            if (!rs.wasNull()) {
                found.put(rs.getLong("head"), new CutDecision.LedgerNeighbor(seq, rs.getInt("duration_ms"),
                        rs.getString("upload_state"), rs.getString("s3_key")));
            }
        });
        return new CutPlanner.Preceding(found, previousBroadcast.get());
    }

    /** 회차 행 읽기 한 줄 — 이웃 표시는 목록이 다 모인 뒤 장부 바로 앞 행 seq 로 정한다. */
    private record RoundRead(long seq, long wallMs, Long pdtMs, int durationMs, boolean isDiscontinuity,
                             String uploadState, Integer timingVersion, String s3Key, Long ledgerPreviousSeq) {

        RoundRow withFollows(boolean follows) {
            return new RoundRow(seq, wallMs, pdtMs, durationMs, isDiscontinuity, follows, uploadState, timingVersion,
                    s3Key);
        }
    }

    private static RoundRead readRoundRow(ResultSet rs, int rowNum) throws SQLException {
        long previous = rs.getLong("ledger_previous_seq");
        Long ledgerPrevious = rs.wasNull() ? null : previous;
        short version = rs.getShort("timing_version");
        Integer timingVersion = rs.wasNull() ? null : (int) version;
        Timestamp pdt = rs.getTimestamp("playback_pdt");
        return new RoundRead(rs.getLong("seq"), rs.getTimestamp("start_wall_utc").toInstant().toEpochMilli(),
                pdt == null ? null : pdt.toInstant().toEpochMilli(), rs.getInt("duration_ms"),
                rs.getBoolean("is_discontinuity"), rs.getString("upload_state"), timingVersion, rs.getString("s3_key"),
                ledgerPrevious);
    }
}
