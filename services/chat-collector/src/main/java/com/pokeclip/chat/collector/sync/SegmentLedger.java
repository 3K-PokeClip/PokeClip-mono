package com.pokeclip.chat.collector.sync;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 조각 장부({@code stream_segments})를 <b>읽기만</b> 한다.
 *
 * <p><b>이 표의 소유자는 1번(Media)이다</b>({@code media/internal/index/ddl.go}).
 * 칸을 더하거나 바꾸려면 계약-세그먼트인덱스 4절에 따라 승인이 필요하고, 이 카드는
 * SELECT만 하므로 승인 사안이 아니다. <b>INSERT·UPDATE·DELETE를 여기에 더하지 마라.</b>
 * chat-collector의 Flyway는 이 표를 만들지 않는다 — 운영에서는 {@code segment-indexer}가
 * 만들고, 그것이 안 돌면 이 조회는 <b>500으로 죽는다. 그게 옳은 신호다</b>(설정 장애와
 * 조각 미도착은 완전히 다른 상태다).
 *
 * <p><b>{@code upload_state}를 안 본다.</b> 시각을 위치로 바꾸는 산수는 그 조각이 S3에
 * 올라갔는지와 무관하다 — 올라가기 전에도 위치는 이미 정해져 있다. 업로드 상태로 거르는
 * 것은 바이트를 서빙하는 쪽(POK-117)의 관심사다.
 *
 * <p>DB 접근은 이 서버의 다른 곳과 같은 {@link JdbcTemplate}이다({@code EndedStreamStore} 선례).
 * <b>시각 파라미터는 반드시 {@link Timestamp#from(Instant)}로 바인딩한다</b> —
 * {@code Instant}를 그대로 넘기면 pgjdbc가 SQL 타입을 못 정해
 * {@code BadSqlGrammarException}이다(계획 검증 F9 실측).
 */
@Component
public class SegmentLedger {

    /**
     * <b>왕복 1회로 floor와 시계 역행 신호 둘을 같이 가져온다.</b>
     *
     * <p>{@code ORDER BY start_wall_utc DESC, seq DESC} — 둘째 키가 없으면 벽시계가 같은
     * 두 조각에서 어느 행이 올지가 정해지지 않는다.
     *
     * <p>스칼라 서브쿼리 둘의 비용은 8시간 방송 10개(72,000행)에서 2.5ms다(계획 실측).
     * {@code EXISTS}가 지금은 Seq Scan을 타므로 <b>판별기(POK-59)가 붙어 채팅마다
     * 부르는 시점에는 {@code (stream_id, start_wall_utc)} 인덱스가 필요하다</b> —
     * 그 인덱스는 1번 소유라 그때 요청한다.
     *
     * <p>파라미터 순서는 (t, streamId, t)다. 첫 {@code ?}가 서브쿼리 안에 있어
     * 텍스트 순서와 인자 순서가 눈에 잘 안 들어온다 — 바꾸면 조용히 틀린 답이 나온다.
     */
    private static final String FLOOR_BY_WALL_CLOCK = """
            SELECT s.seq, s.start_pts_ms, s.start_wall_utc, s.duration_ms, s.is_discontinuity,
                   (SELECT max(x.seq) FROM stream_segments x
                     WHERE x.stream_id = s.stream_id AND x.start_wall_utc <= ?) AS max_candidate_seq,
                   EXISTS(SELECT 1 FROM stream_segments y
                           WHERE y.stream_id = s.stream_id
                             AND y.seq < s.seq AND y.start_wall_utc > s.start_wall_utc) AS earlier_is_future
              FROM stream_segments s
             WHERE s.stream_id = ? AND s.start_wall_utc <= ?
             ORDER BY s.start_wall_utc DESC, s.seq DESC
             LIMIT 1
            """;

    /** 「다음」의 기준은 <b>seq</b>다 — 시계가 튄 방송에서 벽시계 순서와 갈린다. */
    private static final String NEXT_AFTER_SEQ = """
            SELECT seq, start_pts_ms, start_wall_utc, duration_ms, is_discontinuity
              FROM stream_segments
             WHERE stream_id = ? AND seq > ?
             ORDER BY seq ASC
             LIMIT 1
            """;

    /**
     * 조각이 <b>하나도</b> 없는 것과 「이 시각보다 이른 조각이 없는 것」은 다른 상태다.
     * 앞은 「장부가 아직」(다시 물으면 됨)이고 뒤는 「첫 조각 이전」(영영 없음)이다.
     */
    private static final String HAS_ANY_SEGMENT = """
            SELECT EXISTS(SELECT 1 FROM stream_segments WHERE stream_id = ?)
            """;

    private final JdbcTemplate jdbc;

    public SegmentLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return {@code t} 이하에서 가장 늦은 조각. 그런 조각이 없으면 빈손 */
    public Optional<LedgerFloor> floorByWallClock(String streamId, Instant t) {
        Timestamp at = Timestamp.from(t);
        List<LedgerFloor> found = jdbc.query(FLOOR_BY_WALL_CLOCK, SegmentLedger::toFloor, at, streamId, at);
        return found.stream().findFirst();
    }

    /** @return seq가 더 큰 조각 중 가장 작은 것. 마지막 조각이면 빈손 */
    public Optional<LedgerSegment> nextAfterSeq(String streamId, long seq) {
        List<LedgerSegment> found = jdbc.query(NEXT_AFTER_SEQ, SegmentLedger::toSegment, streamId, seq);
        return found.stream().findFirst();
    }

    public boolean hasAnySegment(String streamId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(HAS_ANY_SEGMENT, Boolean.class, streamId));
    }

    private static LedgerFloor toFloor(ResultSet row, int rowNum) throws SQLException {
        return new LedgerFloor(toSegment(row, rowNum),
                row.getLong("max_candidate_seq"),
                row.getBoolean("earlier_is_future"));
    }

    private static LedgerSegment toSegment(ResultSet row, int rowNum) throws SQLException {
        return new LedgerSegment(
                row.getLong("seq"),
                row.getLong("start_pts_ms"),
                row.getTimestamp("start_wall_utc").toInstant(),
                row.getInt("duration_ms"),
                row.getBoolean("is_discontinuity"));
    }
}
