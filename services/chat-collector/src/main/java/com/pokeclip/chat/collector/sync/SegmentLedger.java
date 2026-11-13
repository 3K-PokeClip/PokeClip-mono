package com.pokeclip.chat.collector.sync;

import org.postgresql.PGStatement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
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
     * <b>옛 후보(판 NULL 행) floor와 시계 역행 신호 둘을 왕복 1회로 같이 가져온다.</b>
     *
     * <p>{@code ORDER BY start_wall_utc DESC, seq DESC} — 둘째 키가 없으면 벽시계가 같은
     * 두 조각에서 어느 행이 올지가 정해지지 않는다.
     *
     * <p><b>본문 · 서브쿼리 둘 모두 {@code timing_version IS NULL}이다</b>(계약 세그먼트인덱스 6-6-9 · cc AC-M1). 판 2 행은
     * 파일명 시각이 floor 축이 아니라 후보가 아니고, 역행 신호의 후보 집합에 넣으면 판 2 행 하나가 옛 후보를 「뒤 조각이
     * 과거로 튐」 으로 버린다. 범위 조건 · 울타리는 두지 않는다 — 계약 문언 그대로의 꼴이다.
     *
     * <p>판 2 행만 있는 스트림에서 이 조회가 그 행들을 끝까지 훑지 않게 하는 것이 생산자의 부분 색인
     * {@code stream_segments_legacy_wall_idx}({@code (stream_id, start_wall_utc DESC) WHERE timing_version IS NULL} —
     * {@code media/internal/index/ddl.go})다. 색인은 생산자 DDL이 만들고 여기는 그 조건을 그대로 쓴다(설계 r7 10절 ·
     * 처분 P-3). 정확성은 색인과 무관하다.
     *
     * <p><b>{@code earlier_is_future}는 「seq 가 floor 보다 앞선 판 NULL 행 가운데 파일명 시각 최대 하나」 를 보고 floor 의
     * 파일명 시각과 견준다</b> — 「seq 가 앞서는데 파일명 시각이 늦은 행이 있나」 와 같은 뜻이다(앞선 행의 최댓값이 floor 보다
     * 늦으면 그런 행이 있고, 아니면 없다. 앞선 행이 없으면 거짓). 옛 꼴 {@code EXISTS(y.seq < s.seq AND y.start_wall_utc >
     * s.start_wall_utc)}는 플래너가 표 전체 Seq Scan으로 풀어 비용이 표 전체 행 수에 비례했다(표 907,200행에서 12,676 버퍼 ·
     * 약 35ms — {@code SegmentLedgerIndexTest} 기록). 지금 꼴은 판 NULL 부분 색인을 파일명 시각 내림차순으로 타
     * {@code LIMIT 1}에서 멈추므로, 꼬리 시각이면 꼬리 몇 행이고 꼬리에서 먼 시각이면 그 시각 뒤 그 스트림의 판 NULL 행 수만큼
     * 읽는다. 뜻이 같은지는 {@code SegmentLedgerInversionEquivalenceTest}(옛 꼴과 4,000번 대조)가 묶는다.
     *
     * <p>파라미터 순서는 (t, streamId, t)다. 첫 {@code ?}가 서브쿼리 안에 있어
     * 텍스트 순서와 인자 순서가 눈에 잘 안 들어온다 — 바꾸면 조용히 틀린 답이 나온다.
     */
    static final String FLOOR_BY_WALL_CLOCK = """
            SELECT s.seq, s.start_pts_ms, s.start_wall_utc, s.playback_pdt, s.timing_version,
                   s.duration_ms, s.is_discontinuity,
                   (SELECT max(x.seq) FROM public.stream_segments x
                     WHERE x.stream_id = s.stream_id AND x.timing_version IS NULL
                       AND x.start_wall_utc <= ?) AS max_candidate_seq,
                   COALESCE((SELECT y.start_wall_utc FROM public.stream_segments y
                              WHERE y.stream_id = s.stream_id AND y.timing_version IS NULL AND y.seq < s.seq
                              ORDER BY y.start_wall_utc DESC
                              LIMIT 1) > s.start_wall_utc, false) AS earlier_is_future
              FROM public.stream_segments s
             WHERE s.stream_id = ? AND s.timing_version IS NULL AND s.start_wall_utc <= ?
             ORDER BY s.start_wall_utc DESC, s.seq DESC
             LIMIT 1
            """;

    /**
     * 판 2 후보 — {@code playback_pdt ≤ t} 인 판 2 행 가운데 <b>seq 최대</b>(계약 6-6-9 섞인 floor 1항). PDT 최대가 아니다 —
     * 판 2 PDT도 스트림에서 단조가 아니다(회차 첫 행에서 뒤로 갈 수 있고 장 경계에서 1초 미만 겹친다). 뒤 seq가 t의 실제
     * 방송이고 겹친 시각의 나중 조각이다(경계 표 B8). 벽시각 역행 거르기에 해당하는 검사는 두지 않는다.
     *
     * <p><b>비용 — 「있나」 를 먼저 묻고 「어느 행」 을 묻는다.</b> 꼬리 시각이면 생산자 부분 색인
     * {@code stream_segments_v2_seq_idx}({@code (stream_id, seq) WHERE timing_version = 2})를 seq 역순으로 타 꼬리 몇 행에서
     * 끝난다. 그 스트림의 판 2 행보다 이른 시각이면 seq 역순 훑기는 그 스트림 판 2 행을 끝까지 읽는다(판 2 30만 행에서 7,246
     * 버퍼 — 보안 L2). 그래서 「그 스트림 판 2 행의 최소 PDT ≤ t」 를 먼저 본다 — 「PDT ≤ t 인 판 2 행이 있다」 와 같은
     * 뜻이고(판 2 행이 없으면 최솟값이 NULL 이라 거짓), 거짓이면 본 조회를 건너뛴다. 최솟값은 생산자 판 2 PDT 색인
     * {@code stream_segments_v2_pdt_idx}({@code (stream_id, playback_pdt DESC) WHERE timing_version = 2} — 생산자 PR 510
     * · develop 머지 커밋 fc7dd91c(2026-11-13))의 한쪽 끝 한 항목이라 t 값과 무관하게 꼬리 몇 행이다 — 플래너가 t 를 모르는
     * 일반 계획이어도 그렇다. 이 조회는 서버 준비 문장을 쓰지 않아({@link #floorByPlaybackPdt}) 실제로는 매번 바인딩 값으로 세운
     * 계획이다. {@code EXISTS(… playback_pdt <= ?)} 꼴은 플래너가 첫 행만 찾으면 된다고 보고 표 전체 Seq Scan 을
     * 골랐다(2026-11-13 실측 12,668 버퍼). 확인은 본 조회와 같은 조건이라 답은 그대로다.
     *
     * <p>색인이 없는 DB(생산자 적용 전)에서도 답은 같다. 다만 판 2 행이 표에 생긴 뒤 이 색인이 없으면 조회마다 그 스트림 행을
     * 훑을 수 있다 — 판 2 행이 많은 스트림(판 2 30만 행에서 약 4,500 버퍼)뿐 아니라 계획에 따라 <b>판 2 행이 없는 스트림도</b>
     * 그렇다(시험 4,241 버퍼 — 판 2 행 0 스트림 {@code idx-legacy-only}). 판 2 행이 없는 표에서는 3 버퍼다. 그래서 prod
     * 적용은 판 2 쓰기 스위치 전 조건이다(POK-551 운영 SQL 과 한 재실행). 제품 DDL 은 fc7dd91c 에 있다 — 이 브랜치(develop 5ad61a0c 기준) 코드에는 없다.
     *
     * <p><b>첫 판 2 PDT 바로 뒤 시각은 이 조회가 서버 준비 문장을 쓰지 않아(kty 2026-11-13 L3 (나) ·
     * {@link #floorByPlaybackPdt}) 매번 사용자 지정 계획이다</b> — 판 2 30만 행 · 첫 PDT + 1ms 시험 8 버퍼. 일반 계획이었다면
     * 그 스트림 판 2 행 거의 전부를 읽었다(같은 시험 7,249 버퍼 · 51–141ms(5회)). <b>답이 있고 그 뒤 판 2 행이 많은 중간
     * 시각의 비용은 받아들인 것이다(kty 2026-11-13 중간 t 결정 그대로)</b> — 본 조회가 seq 역순으로 그 행들을 지나야 답에
     * 닿는다(시험 3,629 버퍼 · 생산자 리뷰 실측 5,923 버퍼 · 약 32ms). 호출자는 판별기가 카드당 1회이고 시각은 보통 라이브
     * 머리라 꼬리 몇 행이다.
     *
     * <p>파라미터 순서는 (streamId, t, streamId, t)다.
     */
    static final String FLOOR_BY_PLAYBACK_PDT = """
            SELECT seq, start_pts_ms, start_wall_utc, playback_pdt, timing_version, duration_ms, is_discontinuity
              FROM public.stream_segments
             WHERE stream_id = ? AND timing_version = 2 AND playback_pdt <= ?
               AND (SELECT min(e.playback_pdt) FROM public.stream_segments e
                     WHERE e.stream_id = ? AND e.timing_version = 2) <= ?
             ORDER BY seq DESC
             LIMIT 1
            """;

    /** 「다음」의 기준은 <b>seq</b>다 — 시계가 튄 방송에서 벽시계 순서와 갈린다. 판과도 무관하다(계약 6-6-9). */
    private static final String NEXT_AFTER_SEQ = """
            SELECT seq, start_pts_ms, start_wall_utc, playback_pdt, timing_version, duration_ms, is_discontinuity
              FROM public.stream_segments
             WHERE stream_id = ? AND seq > ?
             ORDER BY seq ASC
             LIMIT 1
            """;

    /**
     * 조각이 <b>하나도</b> 없는 것과 「이 시각보다 이른 조각이 없는 것」은 다른 상태다.
     * 앞은 「장부가 아직」(다시 물으면 됨)이고 뒤는 「첫 조각 이전」(영영 없음)이다.
     */
    private static final String HAS_ANY_SEGMENT = """
            SELECT EXISTS(SELECT 1 FROM public.stream_segments WHERE stream_id = ?)
            """;

    private final JdbcTemplate jdbc;

    public SegmentLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return 판 NULL 행 가운데 파일명 시각이 {@code t} 이하에서 가장 늦은 조각과 역행 신호. 그런 조각이 없으면 빈손 */
    public Optional<LedgerFloor> floorByWallClock(String streamId, Instant t) {
        Timestamp at = Timestamp.from(t);
        List<LedgerFloor> found = jdbc.query(FLOOR_BY_WALL_CLOCK, SegmentLedger::toFloor, at, streamId, at);
        return found.stream().findFirst();
    }

    /**
     * @return 판 2 행 가운데 {@code playback_pdt}가 {@code t} 이하인 seq 최대 조각. 그런 조각이 없으면 빈손
     *
     * <p><b>이 조회만 서버 준비 문장을 쓰지 않는다</b>(kty 2026-11-13 L3 (나)). 드라이버는 같은 문장을 다섯 번째 실행부터 이름
     * 붙은 서버 준비 문장으로 올리고, 그러면 PG 가 t 값을 모르는 일반 계획을 쓸 수 있다 — 첫 판 2 PDT 바로 뒤 시각에서 그
     * 계획은 그 스트림 판 2 행 거의 전부를 읽는다({@code SegmentLedgerIndexTest} 기록 7,249 버퍼). 문장의 문턱을 0 으로 두면
     * 늘 이름 없는 문장이라 매번 바인딩 값으로 계획을 세운다(같은 기록 8 버퍼). 연결 · 데이터소스 설정은 건드리지 않는다 —
     * 다른 조회는 그대로다.
     */
    public Optional<LedgerSegment> floorByPlaybackPdt(String streamId, Instant t) {
        Timestamp at = Timestamp.from(t);
        List<LedgerSegment> found = jdbc.query(con -> {
            PreparedStatement statement = con.prepareStatement(FLOOR_BY_PLAYBACK_PDT);
            statement.unwrap(PGStatement.class).setPrepareThreshold(0);
            statement.setString(1, streamId);
            statement.setTimestamp(2, at);
            statement.setString(3, streamId);
            statement.setTimestamp(4, at);
            return statement;
        }, SegmentLedger::toSegment);
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
                instantOrNull(row.getTimestamp("playback_pdt")),
                row.getObject("timing_version", Integer.class),
                row.getInt("duration_ms"),
                row.getBoolean("is_discontinuity"));
    }

    private static Instant instantOrNull(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
