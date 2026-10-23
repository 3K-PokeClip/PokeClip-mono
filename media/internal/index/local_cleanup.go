package index

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

// 로컬 ② 정리(janitor)의 장부 쪽 — 삭제 판정 SQL 과 행 잠금 삭제 트랜잭션(POK-369).
//
// 삭제 조건은 인덱스 불변식 2 다: 그레이스 경과 AND ② uploaded AND ③ 컷오프 인지 술어.
// 조각마다 따로 평가하므로 HOL 이 없다. 순서는 「행 잠금 → 파일 삭제 → local_path=NULL →
// COMMIT」이다(K8 — 설계 2-1). 파일을 지우는 일 자체는 호출자(janitor)의 몫이고, 이 파일은
// 그 일을 잠금 아래에서 돌리고 결과에 따라 장부를 고친다. DDL 은 바꾸지 않는다.

// RowLockTxnTimeout 은 행 잠금 삭제 트랜잭션의 서버 측 상한이다(PG 17 transaction_timeout — 커밋
// 직전까지 잰다). 넘기면 서버가 세션을 끝내고 잠금을 푼다 — 클라이언트가 멈춰도 그렇다. 정상
// 조건에서 잠금 보유의 기대치이며, 커밋 레코드 삽입 때 WAL 버퍼가 가득 차면 넘을 수 있다(설계 한계 L33).
//
// 사이드카의 두 기한보다 작아야 한다: upload MarkTimeout(10s) · 인덱서 lock_timeout 5s × 2
// (즉시 재시도 두 창). 그래야 정상 조건에서 janitor 를 기다린 쪽이 헛경보(mark_error ·
// ErrLockContended)를 내지 않는다 — 예외는 설계 한계 L30 · L32 · L33 이다. 두 관계는 결속 시험이
// 고정한다(janitor/bind_test.go · local_cleanup_test.go).
const RowLockTxnTimeout = 8 * time.Second

// rowLockTxnTimeoutSetting 은 RowLockTxnTimeout 을 PG 설정 값으로 옮긴 것이다. SQL 에 숫자를
// 따로 적지 않는 이유: 상수와 SQL 이 갈라지면 결속 시험이 지키는 값과 실제 상한이 달라진다.
var rowLockTxnTimeoutSetting = strconv.FormatInt(RowLockTxnTimeout.Milliseconds(), 10) + "ms"

// SQLSTATE — 판별 함수가 쓰는 코드다(설계 3-3).
const (
	pgQueryCanceled    = "57014" // statement_timeout
	pgDeadlockDetected = "40P01"
	pgTxnTimeout       = "25P04" // transaction_timeout — 서버가 세션을 끝낸다(FATAL)
)

// cutoffRowExists 는 「컷오프가 가리키는 행이 장부에 있다」(유효 컷오프)다. 삭제 정지
// (deletableSegmentsSQL)와 신호(invalidCutoffsSQL)가 이 조각 하나를 함께 쓴다 — 원본이
// 하나라 「신호는 켜졌는데 삭제는 계속」이 표현될 수 없다(D09).
const cutoffRowExists = `EXISTS (SELECT 1 FROM stream_segments c
         WHERE c.stream_id = cut.stream_id AND c.seq = cut.cutoff_seq)`

// deletableSegmentsSQL ($1 그레이스 초 · $2 재접속 창 초 · $3 limit · $4,$5 커서 · $6 외부 삭제 나이 초)
//
// 술어별 근거는 설계 4절이다. 요점만:
//   - ③ 항은 컷오프 인지 술어다 — 기록 없음 OR seq < cutoff 면 면제, 아니면 ③ uploaded 필수.
//     「기록 없음」은 PK 열(cut.stream_id IS NULL)로 판정한다(cutoff_seq 는 NOT NULL).
//   - 무효 컷오프가 있는 스트림은 통째로 멈춘다(fail-closed).
//   - 그레이스 기준은 셋(② 확정 · ③ 확정 · 조각 끝)의 최댓값이다 — 늘 안전 쪽이고 NULL 이 없다.
//     시각은 전부 DB 의 now() 한 곳에서 잰다(janitor 시계는 판정에 쓰지 않는다).
//   - 외부 삭제 나이는 조각 **시작**으로 잰다 — MediaMTX 정리기가 파일명의 시작 시각으로
//     판정하기 때문이다(설계 편차 E1).
//   - 보호 ① : init 미확정 · 미종료 회차의 최신 조각 — init 재수거 원천(pendingInitUploadsSQL
//     의 LATERAL 과 같은 선택자).
//   - 보호 ② : 회차 첫 조각(local_path 무관) — live 이거나, 종료 + 창 + 그레이스 안이거나,
//     그 회차를 계승한 회차의 init 이 미확정 · 미종료인 동안(K1 · K2). 종료 시각이 전부 NULL 이면
//     'infinity' 로 펴서 보호를 유지한다.
//
// 정렬은 (stream_id, seq) 키셋이다. 커서 비교는 ORDER BY 와 같은 순서여야 한다.
// duration_ms(integer) 는 ::double precision 으로 맞춘다 — pg_catalog 에는 integer * interval 이 없어, 맞추지 않으면
// public 의 integer 판 연산자(앱 사용자가 만들 수 있다)가 먼저 골린다. 값은 암묵 변환과 같다.
const deletableSegmentsSQL = `
SELECT t.stream_id, t.seq, t.local_path, b.grace_basis,
       t.start_wall_utc < pg_catalog.now() - pg_catalog.make_interval(secs => $6::double precision) AS external_delete_due
  FROM stream_segments t
  LEFT JOIN stream_cutoffs cut ON cut.stream_id = t.stream_id
  CROSS JOIN LATERAL (
       SELECT GREATEST(t.uploaded_at, t.playback_uploaded_at,
                       t.start_wall_utc + t.duration_ms::double precision * interval '1 millisecond') AS grace_basis) b
 WHERE t.local_path IS NOT NULL
   AND t.upload_state = 'uploaded'
   AND ( cut.stream_id IS NULL OR t.seq < cut.cutoff_seq
         OR t.playback_upload_state = 'uploaded' )
   AND ( cut.stream_id IS NULL OR ` + cutoffRowExists + ` )
   AND b.grace_basis < pg_catalog.now() - pg_catalog.make_interval(secs => $1::double precision)
   AND NOT EXISTS (
       SELECT 1 FROM stream_sessions s
        WHERE s.session_id = t.session_id
          AND s.init_uploaded_at IS NULL AND s.state <> 'ended'
          AND t.seq = (SELECT g.seq FROM stream_segments g
                        WHERE g.stream_id = s.stream_id AND g.session_id = s.session_id
                          AND g.local_path IS NOT NULL AND g.bytes IS NOT NULL
                        ORDER BY g.seq DESC LIMIT 1) )
   AND NOT EXISTS (
       SELECT 1 FROM stream_sessions s
        WHERE s.session_id = t.session_id
          AND t.seq = (SELECT g.seq FROM stream_segments g
                        WHERE g.stream_id = s.stream_id AND g.session_id = s.session_id
                        ORDER BY g.seq LIMIT 1)
          AND ( s.state = 'live'
                OR COALESCE(GREATEST(s.ending_at, s.ended_at,
                       (SELECT g.start_wall_utc + g.duration_ms::double precision * interval '1 millisecond'
                          FROM stream_segments g
                         WHERE g.stream_id = s.stream_id AND g.session_id = s.session_id
                         ORDER BY g.seq DESC LIMIT 1)),
                     'infinity'::timestamptz)
                   >= pg_catalog.now() - pg_catalog.make_interval(secs => $2::double precision + $1::double precision)
                OR EXISTS (SELECT 1 FROM stream_sessions h
                            WHERE h.stream_id = s.stream_id
                              AND h.inherits_session = s.session_id
                              AND h.init_uploaded_at IS NULL AND h.state <> 'ended') ) )
   AND (t.stream_id, t.seq) > ($4::text, $5::bigint)
 ORDER BY t.stream_id, t.seq
 LIMIT $3`

// invalidCutoffsSQL 은 무효 컷오프(가리키는 행이 없는 컷오프)다 — 삭제 정지와 같은 조각의 부정.
const invalidCutoffsSQL = `
SELECT cut.stream_id, cut.cutoff_seq FROM stream_cutoffs cut
 WHERE NOT ` + cutoffRowExists + `
 ORDER BY cut.stream_id`

// clearLocalPathSQL 은 local_path 하나만 비교-후-갱신한다(결정 4). 잠금 트랜잭션의 UPDATE 와
// 회수 ClearLocalPath 가 같은 문장이다.
const clearLocalPathSQL = `
UPDATE stream_segments SET local_path = NULL
 WHERE stream_id = $1 AND seq = $2 AND local_path = $3`

// schemaReadySQL — 판정이 쓰는 표 셋이 모두 보이는가. EnsureSchema 는 한 트랜잭션이라
// 셋이 보이면 DDL 이 커밋된 것이다.
const schemaReadySQL = `
SELECT pg_catalog.to_regclass('stream_segments') IS NOT NULL
   AND pg_catalog.to_regclass('stream_sessions') IS NOT NULL
   AND pg_catalog.to_regclass('stream_cutoffs')  IS NOT NULL`

// 잠금 트랜잭션의 세 문장 — 순서가 계약이다(lockRow).
//
//   - 전체 상한(transaction_timeout)을 행을 잠그기 **전에** 건다(E3′).
//   - 비동기 커밋을 건다(X1 A): transaction_timeout 은 커밋 직전까지만 재므로, 커밋 단계의
//     WAL flush · 동기 복제 대기를 없애야 정상 조건에서 잠금 보유가 「8s + 비-flush 처리」 안에
//     끝난다(WAL 버퍼 포화 때는 넘을 수 있다 — 한계 L33). 대가는
//     DB 비정상 종료 때 마지막 갱신 소실이다(한계 L31 — K7 이 흡수).
//   - 둘 다 이 트랜잭션에만 건다(is_local = true). 같은 풀의 조회(statement_timeout 15s)와
//     다른 서비스의 커밋은 그대로다.
//   - 선잠금은 한 표 한 행이고(JOIN 0 — D36) NOWAIT 다: 잠금 경합은 파일을 건드리기 전에
//     55P03 으로 갈라진다. 비교는 Go 에서 한다 — 0행 · 다른 값을 한 왕복에 가른다.
const (
	setRowLockTxnTimeoutSQL  = `SELECT pg_catalog.set_config('transaction_timeout', $1, true)`
	setRowLockAsyncCommitSQL = `SELECT pg_catalog.set_config('synchronous_commit','off',true)`
	rowLockSQL               = `SELECT local_path FROM stream_segments WHERE stream_id = $1 AND seq = $2 FOR UPDATE NOWAIT`
)

// LocalCleanupPolicy 는 삭제 판정의 기간 셋이다. 하한 검증은 설정(cmd/janitor)이 한다 —
// 여기서는 음수만 거부한다.
type LocalCleanupPolicy struct {
	// Grace 는 마지막 확정 · 조각 끝 뒤 삭제 전 대기다(계약 8분).
	Grace time.Duration
	// ReconnectWindow 는 보호 ② 의 재접속 창이다(ADR-044 300초).
	ReconnectWindow time.Duration
	// ExternalDeleteAge 는 「MediaMTX 가 이미 지웠을 수 있다」로 볼 조각 나이다(recordDeleteAfter − 여유).
	ExternalDeleteAge time.Duration
}

// LocalSegment 는 삭제 후보 한 행이다.
type LocalSegment struct {
	StreamID string
	Seq      int64
	// LocalPath 는 장부 원문 그대로이고 검증되지 않았다 — 호출자가 SEGMENT_ROOT 기준으로 검증하고
	// 루트 안에서 지운다.
	LocalPath string
	// GraceBasis 는 그레이스를 잰 기준 시각(셋의 최댓값)이다. 관측 로그용이다.
	GraceBasis time.Time
	// ExternalDeleteDue 는 조각 시작이 ExternalDeleteAge 를 넘겼는가다 — 참이면 ENOENT 를
	// 「MediaMTX 가 먼저 지움」으로 인정해도 된다(K7).
	ExternalDeleteDue bool
}

// LocalCursor 는 삭제 후보 키셋 페이징의 위치다(마지막으로 받은 행). 영값 = 처음부터.
type LocalCursor struct {
	StreamID string
	Seq      int64
}

// InvalidCutoff 는 가리키는 행이 장부에 없는 컷오프다. 그 스트림은 삭제가 멈춘다.
type InvalidCutoff struct {
	StreamID  string
	CutoffSeq int64
}

// RowLockOutcome 은 RemoveUnderRowLock 의 판정이다. 영값은 판정이 아니다(오류와 함께만 나간다).
type RowLockOutcome uint8

const (
	// RowLockCleared — remove 가 clear=true 를 돌려줬고 UPDATE · COMMIT 이 성공했다.
	RowLockCleared RowLockOutcome = iota + 1
	// RowLockKept — remove 가 clear=false 를 돌려줬다. ROLLBACK 했다.
	RowLockKept
	// RowLockBusy — 다른 세션이 그 행을 쥐고 있다(NOWAIT 55P03). remove 를 부르지 않았다.
	// NOWAIT 는 행 잠금에만 걸린다 — 표 잠금(이행 EnsureSchema 의 ACCESS EXCLUSIVE 등)에 막히면
	// 세션 lock_timeout(2s)까지 기다린 뒤 Busy 다.
	RowLockBusy
	// RowLockGone — 행이 없다(다른 주체의 DELETE — clip 탈퇴 정리 등). remove 를 부르지 않았다.
	RowLockGone
	// RowLockPathChanged — 행은 있으나 local_path 가 기대값과 다르다(NULL 포함). remove 를 부르지 않았다.
	RowLockPathChanged
)

// RowLockStage 는 RemoveUnderRowLock 이 실패한 단계다. 호출자는 이것으로 「파일을 건드렸는가」를
// 가른다 — StageCommit 만 remove 뒤다.
type RowLockStage uint8

const (
	// StageAcquire — 풀 연결 획득 · BEGIN.
	StageAcquire RowLockStage = iota + 1
	// StageLock — 상한 설정 · 선잠금(remove 전).
	StageLock
	// StageCommit — remove 뒤 UPDATE · COMMIT(또는 ROLLBACK).
	StageCommit
)

// RowLockResult 는 RemoveUnderRowLock 한 번의 결과다.
type RowLockResult struct {
	// Outcome 은 err 가 nil 일 때만 뜻이 있다.
	Outcome RowLockOutcome
	// Acted 는 remove 가 불렸는가다.
	Acted bool
	// Cleared 는 remove 가 clear=true 를 돌려줬는가다(Acted 일 때만 뜻이 있다).
	Cleared bool
	// Stage 는 err 가 nil 이 아닐 때만 뜻이 있다.
	Stage RowLockStage
}

// LocalCleanupStore 는 janitor 가 쓰는 장부 통로다. janitor 패키지의 Ledger(소비자 정의
// 인터페이스)를 만족한다.
type LocalCleanupStore struct {
	pool *pgxpool.Pool
}

// NewLocalCleanupStore 는 PostgreSQL 구현을 돌려준다. 세션 설정(statement_timeout · lock_timeout)은
// 풀이 연결마다 건다(cmd/janitor) — 이 타입은 잠금 트랜잭션의 지역 설정만 건다.
func NewLocalCleanupStore(pool *pgxpool.Pool) *LocalCleanupStore {
	return &LocalCleanupStore{pool: pool}
}

// SchemaReady 는 판정이 쓰는 표 셋(stream_segments · stream_sessions · stream_cutoffs)이 모두
// 보이는가다. 거짓이면 아직 EnsureSchema 가 커밋되지 않은 새 DB 다 — 조회하지 말고 기다린다.
func (s *LocalCleanupStore) SchemaReady(ctx context.Context) (bool, error) {
	var ready bool
	if err := s.pool.QueryRow(ctx, schemaReadySQL).Scan(&ready); err != nil {
		return false, fmt.Errorf("판정 표 준비 확인 실패: %w", err)
	}
	return ready, nil
}

// DeletableSegments 는 after 다음부터 삭제 조건을 채운 행을 limit 개까지 돌려준다. 둘째 반환값은
// 이 페이지 마지막 행이고, 행이 없으면 after 그대로다(되감김을 만들지 않는다).
func (s *LocalCleanupStore) DeletableSegments(ctx context.Context, p LocalCleanupPolicy, limit int, after LocalCursor) ([]LocalSegment, LocalCursor, error) {
	if limit <= 0 {
		return nil, after, fmt.Errorf("삭제 후보 페이지 크기 %d — 양수여야 한다", limit)
	}
	if p.Grace < 0 || p.ReconnectWindow < 0 || p.ExternalDeleteAge < 0 {
		return nil, after, fmt.Errorf("로컬 정리 정책 %+v — 기간은 음수일 수 없다", p)
	}
	rows, err := s.pool.Query(ctx, deletableSegmentsSQL,
		p.Grace.Seconds(), p.ReconnectWindow.Seconds(), limit,
		after.StreamID, after.Seq, p.ExternalDeleteAge.Seconds())
	if err != nil {
		return nil, after, fmt.Errorf("삭제 후보 조회 실패: %w", err)
	}
	segs, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (LocalSegment, error) {
		var seg LocalSegment
		err := row.Scan(&seg.StreamID, &seg.Seq, &seg.LocalPath, &seg.GraceBasis, &seg.ExternalDeleteDue)
		seg.GraceBasis = seg.GraceBasis.UTC()
		return seg, err
	})
	if err != nil {
		return nil, after, fmt.Errorf("삭제 후보 스캔 실패: %w", err)
	}
	if len(segs) == 0 {
		return segs, after, nil
	}
	last := segs[len(segs)-1]
	return segs, LocalCursor{StreamID: last.StreamID, Seq: last.Seq}, nil
}

// InvalidCutoffs 는 무효 컷오프 전부다(스트림 순).
func (s *LocalCleanupStore) InvalidCutoffs(ctx context.Context) ([]InvalidCutoff, error) {
	rows, err := s.pool.Query(ctx, invalidCutoffsSQL)
	if err != nil {
		return nil, fmt.Errorf("무효 컷오프 조회 실패: %w", err)
	}
	cuts, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (InvalidCutoff, error) {
		var c InvalidCutoff
		err := row.Scan(&c.StreamID, &c.CutoffSeq)
		return c, err
	})
	if err != nil {
		return nil, fmt.Errorf("무효 컷오프 스캔 실패: %w", err)
	}
	return cuts, nil
}

// RemoveUnderRowLock 은 한 트랜잭션 안에서 다음 순서로 한다.
//
//	BEGIN → transaction_timeout(RowLockTxnTimeout) · synchronous_commit off (둘 다 SET LOCAL)
//	→ SELECT local_path … FOR UPDATE NOWAIT
//	→ (0행 Gone · 다른 값 PathChanged · 잠김 Busy · 같은 값이면) remove()
//	→ clear 면 UPDATE … SET local_path = NULL (local_path CAS) → COMMIT, 아니면 ROLLBACK
//
// path 는 장부 원문 그대로 비교만 하고 검증하지 않는다 — 호출자가 remove 안에서 SEGMENT_ROOT
// 기준으로 검증한다(비교한 값과 지울 값이 같은 문자열이라 그 사이 바꿔치기는 없다).
//
// remove 는 그 행의 잠금을 쥔 동안에만, 한 번 불린다. 잠금 보유는 서버가 RowLockTxnTimeout 으로
// 묶으므로 remove 가 오래 걸리면 COMMIT 단계가 세션 종료 오류로 실패한다(StageCommit).
// 클라이언트 기한(연결 획득 포함)은 ctx 로 준다. 되돌림은 ctx 의 취소 · 시한과 떼고 RowLockTxnTimeout 을
// 새로 씌운다(rollbackRowLock).
func (s *LocalCleanupStore) RemoveUnderRowLock(ctx context.Context, streamID string, seq int64, path string, remove func() (clear bool)) (RowLockResult, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return RowLockResult{Stage: StageAcquire}, fmt.Errorf("행 잠금 트랜잭션 시작 실패 stream_id=%q seq=%d: %w", streamID, seq, err)
	}
	// 커밋 뒤의 Rollback 은 무해한 no-op 이다. 잠금 실패 · 판정 갈래의 되돌림이 여기 하나다.
	defer func() { _ = rollbackRowLock(ctx, tx) }()

	current, err := lockRow(ctx, tx, streamID, seq)
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		return RowLockResult{Outcome: RowLockGone}, nil
	case IsLockTimeout(err):
		return RowLockResult{Outcome: RowLockBusy}, nil
	case err != nil:
		return RowLockResult{Stage: StageLock}, fmt.Errorf("행 선잠금 실패 stream_id=%q seq=%d: %w", streamID, seq, err)
	case current == nil || *current != path:
		return RowLockResult{Outcome: RowLockPathChanged}, nil
	}

	res := RowLockResult{Acted: true, Cleared: remove()}
	if err := finishRowLock(ctx, tx, streamID, seq, path, res.Cleared); err != nil {
		res.Stage = StageCommit
		return res, err
	}
	res.Outcome = RowLockKept
	if res.Cleared {
		res.Outcome = RowLockCleared
	}
	return res, nil
}

// lockRow 는 잠금 트랜잭션의 세 문장을 순서대로 보내고 잠근 행의 local_path 를 돌려준다.
// 행이 없으면 pgx.ErrNoRows 다.
func lockRow(ctx context.Context, tx pgx.Tx, streamID string, seq int64) (*string, error) {
	if _, err := tx.Exec(ctx, setRowLockTxnTimeoutSQL, rowLockTxnTimeoutSetting); err != nil {
		return nil, fmt.Errorf("transaction_timeout 설정: %w", err)
	}
	if _, err := tx.Exec(ctx, setRowLockAsyncCommitSQL); err != nil {
		return nil, fmt.Errorf("synchronous_commit 설정: %w", err)
	}
	var current *string
	if err := tx.QueryRow(ctx, rowLockSQL, streamID, seq).Scan(&current); err != nil {
		return nil, err
	}
	return current, nil
}

// finishRowLock 은 remove 뒤 단계다 — clear 면 local_path CAS 후 COMMIT, 아니면 ROLLBACK.
func finishRowLock(ctx context.Context, tx pgx.Tx, streamID string, seq int64, path string, clear bool) error {
	if !clear {
		if err := rollbackRowLock(ctx, tx); err != nil {
			return fmt.Errorf("행 잠금 되돌림 실패 stream_id=%q seq=%d: %w", streamID, seq, err)
		}
		return nil
	}
	tag, err := tx.Exec(ctx, clearLocalPathSQL, streamID, seq, path)
	if err != nil {
		return fmt.Errorf("local_path 비우기 실패 stream_id=%q seq=%d: %w", streamID, seq, err)
	}
	// 잠근 행의 값을 방금 확인했으므로 0행은 일어날 수 없다 — 일어나면 조용히 넘기지 않는다.
	if n := tag.RowsAffected(); n != 1 {
		return fmt.Errorf("잠근 행의 local_path 비우기가 %d행이다(1행이어야 한다) stream_id=%q seq=%d", n, streamID, seq)
	}
	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("local_path 비우기 커밋 실패 stream_id=%q seq=%d: %w", streamID, seq, err)
	}
	return nil
}

// rollbackRowLock 은 행 잠금 트랜잭션을 되돌린다. 되돌림 ctx 는 되돌리는 순간에 만든다 — 부른 쪽 ctx 에서
// 취소와 시한을 떼고 RowLockTxnTimeout 을 새로 씌운다. 끝난 ctx 로 부르면 pgx 는 ROLLBACK 을 보내지 못하고
// 연결을 버리는데, 선잠금 문장이나 remove 가 부른 쪽 ctx 를 다 쓴 갈래에서 그 ctx 는 끝나 있다. 시한이
// RowLockTxnTimeout 인 이유: 서버 상한(transaction_timeout)은 SET LOCAL 이 닿은 뒤에 걸려 그 트랜잭션은 그만큼을
// 넘겨 살아 있지 못하고, 그 전에 ctx 가 끝난 갈래도 이 시한 뒤 pgx 가 연결을 버리면 서버 트랜잭션이 끝난다 —
// 되돌리는 순간부터 그보다 오래 기다릴 쓸모가 없다. 닫힌 트랜잭션(커밋 · 되돌림 뒤)이면 문장을 보내지 않는다.
func rollbackRowLock(ctx context.Context, tx pgx.Tx) error {
	rctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), RowLockTxnTimeout)
	defer cancel()
	return tx.Rollback(rctx)
}

// ClearLocalPath 는 회수 전용 한 문장 CAS 다 — 파일을 지웠는데 COMMIT 을 확인하지 못한 행의
// 장부를 맞춘다. 세션 lock_timeout 을 따른다. false = 행 없음 또는 값이 다름(이미 갱신됨 포함).
func (s *LocalCleanupStore) ClearLocalPath(ctx context.Context, streamID string, seq int64, deletedPath string) (bool, error) {
	tag, err := s.pool.Exec(ctx, clearLocalPathSQL, streamID, seq, deletedPath)
	if err != nil {
		return false, fmt.Errorf("local_path 회수 실패 stream_id=%q seq=%d: %w", streamID, seq, err)
	}
	return tag.RowsAffected() == 1, nil
}

// IsLockTimeout 은 err 가 55P03(lock_timeout · NOWAIT)인가다.
func IsLockTimeout(err error) bool { return isLockTimeout(err) }

// IsStatementTimeout 은 err 가 57014(statement_timeout)인가다.
func IsStatementTimeout(err error) bool { return hasSQLState(err, pgQueryCanceled) }

// IsDeadlock 은 err 가 40P01(교착 희생)인가다.
func IsDeadlock(err error) bool { return hasSQLState(err, pgDeadlockDetected) }

// IsTxnTimeout 은 err 가 25P04(transaction_timeout 으로 서버가 세션을 끝냄)인가다.
func IsTxnTimeout(err error) bool { return hasSQLState(err, pgTxnTimeout) }

func hasSQLState(err error, code string) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && pgErr.Code == code
}
