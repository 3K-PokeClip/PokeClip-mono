package janitor

import (
	"context"
	"errors"
	"log/slog"
	"maps"
	"slices"
	"time"

	"github.com/jackc/pgx/v5/pgconn"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// cycleStats 는 한 주기의 계수 · 중단 사유다 — summary 와 게이지가 읽는다.
type cycleStats struct {
	start time.Time

	examined, skipped                   int
	removed, gone, unexpectedGone       int
	cleared, lockSkipped, casLost       int
	rowGone, rejected, stalled, fileOps int
	failed                              map[string]int
	rejectedBy                          map[string]int
	rejectSample                        string
	recovery                            string // 회수 결과(빈 값 = 회수 없음)
	fsHeld, schemaWaiting               bool
	dbWork, deleteDone                  bool
	reachedEnd                          bool
	failReason, failStage, failSQLState string
	failErr                             error
}

func newCycleStats(start time.Time) *cycleStats {
	return &cycleStats{start: start, failed: map[string]int{}, rejectedBy: map[string]int{}}
}

// abort 는 주기 중단을 적는다. 첫 사유만 남긴다(뒤는 그 결과다).
func (st *cycleStats) abort(reason, stage string, err error) {
	if st.failReason != "" {
		return
	}
	st.failReason, st.failStage, st.failErr = reason, stage, err
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) {
		st.failSQLState = pgErr.Code
	}
}

func (st *cycleStats) aborted() bool { return st.failReason != "" }

func (st *cycleStats) failAttrs() []any {
	return []any{"reason", st.failReason, "stage", st.failStage, "sqlstate", st.failSQLState, "err", st.failErr}
}

func (st *cycleStats) reject(reason string, seg index.LocalSegment) {
	st.rejected++
	st.rejectedBy[reason]++
	if st.rejectSample == "" {
		st.rejectSample = seg.StreamID + " " + seg.LocalPath
	}
}

func (st *cycleStats) rejectReasons() map[string]int { return maps.Clone(st.rejectedBy) }

// worked 는 summary 를 INFO 로 올릴 만한 일(작업 · 실패)이 있었는가다.
func (st *cycleStats) worked() bool {
	n := st.removed + st.gone + st.unexpectedGone + st.cleared + st.lockSkipped + st.casLost +
		st.rowGone + st.rejected + st.stalled
	for _, v := range st.failed {
		n += v
	}
	return n > 0 || st.aborted() || st.recovery != ""
}

// summary 는 주기 요약 한 줄이다 — 작업 · 실패가 있으면 INFO, 아니면 DEBUG(설계 8절).
func (j *Janitor) summary(st *cycleStats) {
	level := slog.LevelDebug
	if st.worked() {
		level = slog.LevelInfo
	}
	attrs := []any{
		"examined", st.examined, "skipped", st.skipped, "removed", st.removed, "gone", st.gone,
		"unexpected_gone", st.unexpectedGone, "cleared", st.cleared, "lock_skipped", st.lockSkipped,
		"cas_lost", st.casLost, "row_gone", st.rowGone, "rejected", st.rejected, "stalled", st.stalled,
		"fs_held", st.fsHeld, "schema_ready", !st.schemaWaiting, "recovery", st.recovery,
		"debt_size", len(j.debt), "skip_size", j.skip.len(), "skip_full", j.skip.refused,
		"reached_end", st.reachedEnd, "elapsed", j.now().Sub(st.start),
	}
	for _, reason := range slices.Sorted(maps.Keys(st.failed)) {
		attrs = append(attrs, "failed_"+reason, st.failed[reason])
	}
	if len(j.debt) > 0 {
		d := j.debt[0]
		attrs = append(attrs, "debt_kind", d.kind.String(), "debt_failures", d.failures)
	}
	if st.aborted() {
		attrs = append(attrs, "abort_reason", st.failReason, "abort_stage", st.failStage)
	}
	j.skip.refused = false
	j.log.Log(context.Background(), level, "janitor_cycle_summary", attrs...)
}

// classifyQuery 는 조회(SchemaReady · DeletableSegments · InvalidCutoffs) 오류의 중단 사유다(3-3 「조회」 행).
// 조회는 행을 잠그지 않으므로 55P03 · 40P01 · 25P04 는 「그 밖」(db_error)이다.
func classifyQuery(err error) string {
	switch {
	case index.IsStatementTimeout(err):
		return "statement_timeout"
	case errors.Is(err, context.DeadlineExceeded):
		return "client_deadline"
	case isAcquire(err):
		return "acquire"
	}
	return "db_error"
}

// classifyRowLock 은 RemoveUnderRowLock · 회수 CAS 오류의 중단 사유다(3-3 — 잠금 · 교착 · 세션
// 종료를 가른다).
func classifyRowLock(err error) string {
	switch {
	case index.IsLockTimeout(err):
		return "lock_timeout"
	case index.IsDeadlock(err):
		return "deadlock"
	case index.IsTxnTimeout(err):
		return "txn_timeout"
	}
	return classifyQuery(err)
}

// classifyReclaim 은 회수 CAS 오류의 중단 사유다. 회수는 잠금 트랜잭션 밖이라 25P04 는 「그 밖」이다.
func classifyReclaim(err error) string {
	if index.IsTxnTimeout(err) {
		return "db_error"
	}
	return classifyRowLock(err)
}

// isAcquire 는 err 가 연결을 얻지 못한 실패인가다. 조회 오류에는 index 가 단계를 감싸지 않으므로
// pgconn 의 접속 오류로 가른다(세부 선택 S3).
func isAcquire(err error) bool {
	var ce *pgconn.ConnectError
	return errors.As(err, &ce)
}
