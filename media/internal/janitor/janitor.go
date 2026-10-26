// Package janitor 는 업로드가 끝난 ② 로컬 조각 파일을 지우고 장부의 local_path 를 NULL 로
// 만드는 정리기다(POK-170 · 설계 3절).
//
// 무엇을 지울지(판정 SQL)와 행 잠금 트랜잭션은 index 가 소유하고, 이 패키지에는 SQL 이 없다.
// 이 패키지가 소유하는 변경 이유는 「한 주기를 어떤 순서로 돌고, 실패한 행을 언제 다시 보며,
// 무엇을 신호로 내는가」다.
//
// 불변식 J-debt(설계 3-3): 한 실행 안에서 언제나 「janitor 가 remove 를 성공시켰거나 stall 로 끝낸
// 행 가운데 장부에 그 경로가 남은 행」은 1개 이하다. 그런 행이 생기는 길(COMMIT 미확인 · stall)은
// 둘 다 부채(debt)를 채우고, 부채가 있으면 새 삭제를 하지 않는다(게이팅). 부채는 장부 갱신을
// 확인하거나 행이 없어졌을 때만 빠진다 — 드롭하지 않는다. 예외 = COMMIT 을 확인받은 뒤 DB 가
// 비정상 종료해 비동기 커밋 갱신이 사라진 행(한계 L31 — K7 나이 규칙이 흡수한다).
//
// Janitor 는 단일 고루틴(Run)이 소유한다 — 고루틴 안전하지 않다.
package janitor

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"strconv"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/fsop"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// Ledger 는 janitor 가 쓰는 장부 메서드 다섯이다(소비자 정의). *index.LocalCleanupStore 가 만족한다.
type Ledger interface {
	SchemaReady(ctx context.Context) (bool, error)
	DeletableSegments(ctx context.Context, p index.LocalCleanupPolicy, limit int, after index.LocalCursor) ([]index.LocalSegment, index.LocalCursor, error)
	InvalidCutoffs(ctx context.Context) ([]index.InvalidCutoff, error)
	AbandonedLocalRetained(ctx context.Context) (int64, error)
	RemoveUnderRowLock(ctx context.Context, streamID string, seq int64, path string, remove func() (clear bool)) (index.RowLockResult, error)
	ClearLocalPath(ctx context.Context, streamID string, seq int64, deletedPath string) (bool, error)
}

// 호출별 클라이언트 기한(연결 획득 포함 — 설계 2-3 기한 표). 서버 쪽 기한(statement_timeout 15s ·
// lock_timeout 2s · transaction_timeout 8s)보다 길어, 정상 실패는 서버의 깨끗한 오류로 온다.
const (
	queryTimeout = 20 * time.Second
	// rowLockTimeout = RowLockTxnTimeout + 2s — 서버가 8s 에 세션을 끝낸 뒤 그 오류를 받을 여유다.
	rowLockTimeout = index.RowLockTxnTimeout + 2*time.Second
	reclaimTimeout = 5 * time.Second
)

// 보류 시간(설계 3-2).
const (
	rejectedHold   = time.Hour
	unexpectedHold = 10 * time.Minute
	fsHoldMax      = 30 * time.Minute
)

// skipCapacityFactor × MaxPerCycle 이 건너뛰기 목록의 상한이다(설계 2-3).
const skipCapacityFactor = 10

// Options 는 janitor 의 판단 기준이다. 검증(하한 · 상한)은 설정(cmd/janitor)이 한다 — 여기서는
// 동작이 성립하지 않는 값(0 이하)만 거부한다.
type Options struct {
	// Root 는 경로 검증과 래치 해제 프로브의 기준 경로(SEGMENT_ROOT)다. 파일을 지우는 데는
	// New 가 받은 *os.Root 만 쓴다.
	Root        string
	Interval    time.Duration
	Policy      index.LocalCleanupPolicy
	Batch       int
	MaxPerCycle int
	FSOpTimeout time.Duration
	// RunID 는 이 실행의 식별자다. 비면 New 가 기동 시각(unix 나노초 10진)으로 만든다.
	RunID string
	Log   *slog.Logger
}

// Janitor 는 정리기 한 실행이다. 상태는 메모리 전용이라 재기동하면 비워진다(한계 L15).
type Janitor struct {
	opt    Options
	ledger Ledger
	log    *slog.Logger

	// 시험 주입점 — 기본은 fsop.RemoveT · time.Now.
	remove func(name string) error
	now    func() time.Time
	limits callLimits

	latch       *fsop.Latch
	cursor      index.LocalCursor
	skip        *skipList
	debt        []debtEntry
	halted      bool // 불변식 위반 — 이 실행 동안 새 삭제를 하지 않는다
	fsHoldUntil time.Time
	stallStreak int
	schemaReady bool
	schemaLog   bool // janitor_schema_waiting 을 이번 실행에 냈다
	dumped      bool // 시작 덤프를 냈다
	gauges      *gauges
}

// callLimits 는 호출별 클라이언트 기한이다(시험만 줄인다).
type callLimits struct {
	query, rowLock, reclaim time.Duration
}

// New 는 정리기를 만든다. root 는 SEGMENT_ROOT 를 연 손잡이이고 파일을 지우는 유일한 통로다.
func New(opt Options, ledger Ledger, root *os.Root) (*Janitor, error) {
	switch {
	case ledger == nil || root == nil || opt.Log == nil:
		return nil, errors.New("janitor: ledger · root · Log 는 nil 일 수 없다")
	case opt.Interval <= 0 || opt.FSOpTimeout <= 0:
		return nil, fmt.Errorf("janitor: Interval(%v) · FSOpTimeout(%v) 는 양수여야 한다", opt.Interval, opt.FSOpTimeout)
	case opt.Batch <= 0 || opt.MaxPerCycle <= 0 || opt.Batch > opt.MaxPerCycle:
		return nil, fmt.Errorf("janitor: Batch(%d) 는 양수이고 MaxPerCycle(%d) 이하여야 한다", opt.Batch, opt.MaxPerCycle)
	}
	if opt.RunID == "" {
		opt.RunID = strconv.FormatInt(time.Now().UnixNano(), 10)
	}
	log := opt.Log.With("run_id", opt.RunID)
	timeout := opt.FSOpTimeout
	return &Janitor{
		opt:    opt,
		ledger: ledger,
		log:    log,
		remove: func(name string) error { return fsop.RemoveT(root, name, timeout) },
		now:    time.Now,
		limits: callLimits{query: queryTimeout, rowLock: rowLockTimeout, reclaim: reclaimTimeout},
		latch:  fsop.NewLatch(log),
		skip:   newSkipList(skipCapacityFactor*opt.MaxPerCycle, opt.Interval),
		gauges: newGauges(log),
	}, nil
}

// Run 은 ctx 가 끝날 때까지 Interval 마다 한 주기를 돈다. 첫 주기는 곧바로 돈다. 돌려주는 값은
// ctx 의 오류다.
func (j *Janitor) Run(ctx context.Context) error {
	t := time.NewTimer(0)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-t.C:
		}
		j.runCycle(ctx)
		t.Reset(j.opt.Interval)
	}
}

// runCycle 은 한 주기다(설계 3-1). 순서가 계약이다 — 시작 덤프 → 스키마 준비 → FS 보류 → 래치
// 해제 → 무효 컷오프 신호 → 회수 → 삭제 → 게이지 · summary.
func (j *Janitor) runCycle(ctx context.Context) {
	if !j.dumped {
		j.gauges.startDump()
		j.dumped = true
	}
	st := newCycleStats(j.now())
	defer j.finishCycle(ctx, st)

	if !j.ensureSchema(ctx, st) || j.fsHeld(st) {
		return
	}
	st.dbWork = true
	if !j.signalInvalidCutoffs(ctx, st) || !j.signalAbandonedRetained(ctx, st) {
		return
	}
	if len(j.debt) > 0 {
		j.reclaim(ctx, st)
		if st.aborted() || len(j.debt) > 0 {
			return
		}
	}
	if j.halted {
		return
	}
	j.deletePhase(ctx, st)
}

// ensureSchema 는 판정 표가 보일 때만 참이다(0′단계). 거짓이면 주기를 끝낸다 — cycle_failing 은
// 바꾸지 않는다(새 DB 에서 EnsureSchema 가 커밋되기 전은 장애가 아니다).
func (j *Janitor) ensureSchema(ctx context.Context, st *cycleStats) bool {
	if j.schemaReady {
		return true
	}
	cctx, cancel := context.WithTimeout(ctx, j.limits.query)
	defer cancel()
	ready, err := j.ledger.SchemaReady(cctx)
	if err != nil {
		st.dbWork = true
		st.abort(classifyQuery(err), "schema_ready", err)
		return false
	}
	if !ready {
		st.schemaWaiting = true
		if !j.schemaLog {
			j.log.Info("janitor_schema_waiting", "note", "판정 표가 아직 없다 — EnsureSchema 커밋을 기다린다")
			j.schemaLog = true
		}
		return false
	}
	j.schemaReady = true
	j.log.Info("janitor_schema_ready")
	return true
}

// fsHeld 는 FS 전역 보류 중이거나 래치를 풀지 못했으면 참이다(1 · 2단계). 이때는 회수도 하지 않는다.
func (j *Janitor) fsHeld(st *cycleStats) bool {
	if j.now().Before(j.fsHoldUntil) {
		st.fsHeld = true
		return true
	}
	if j.latch.Tripped() && !j.latch.Reset(j.opt.Root, j.opt.FSOpTimeout) {
		j.enterFSHold()
		st.fsHeld = true
		return true
	}
	return false
}

// enterFSHold 는 FS 전역 보류에 들어간다 — min(Interval × 2^(n−1), 30m).
func (j *Janitor) enterFSHold() {
	j.stallStreak++
	hold := j.opt.Interval
	for i := 1; i < j.stallStreak && hold < fsHoldMax; i++ {
		hold *= 2
	}
	hold = min(hold, fsHoldMax)
	j.fsHoldUntil = j.now().Add(hold)
	j.gauges.fsSuspended.set("hold", hold, "stall_streak", j.stallStreak)
}

// signalInvalidCutoffs 는 무효 컷오프 게이지를 맞춘다(3단계). 그 스트림의 삭제 정지는 판정 SQL 이
// 한다 — 여기는 신호뿐이다.
func (j *Janitor) signalInvalidCutoffs(ctx context.Context, st *cycleStats) bool {
	cctx, cancel := context.WithTimeout(ctx, j.limits.query)
	defer cancel()
	cuts, err := j.ledger.InvalidCutoffs(cctx)
	if err != nil {
		st.abort(classifyQuery(err), "invalid_cutoffs", err)
		return false
	}
	j.gauges.cutoffs(cuts)
	return true
}

// signalAbandonedRetained 는 ③ 포기 보존 기간이 지났는데 ② 가 안 올라 지울 수 없는 행이 있는지로 게이지를
// 맞춘다(설계 M6 7절 X5). ② 먼저 삭제는 금지라 정리기는 기다릴 뿐이고 사람이 ② 정체를 볼 일이다.
func (j *Janitor) signalAbandonedRetained(ctx context.Context, st *cycleStats) bool {
	cctx, cancel := context.WithTimeout(ctx, j.limits.query)
	defer cancel()
	n, err := j.ledger.AbandonedLocalRetained(cctx)
	if err != nil {
		st.abort(classifyQuery(err), "abandoned_local_retained", err)
		return false
	}
	if n > 0 {
		j.gauges.abandonedRetained.set("rows", n)
		return true
	}
	j.gauges.abandonedRetained.clear()
	return true
}

// deletePhase 는 커서에서 이어 후보를 살펴본다(5단계). 살펴본 행 수가 MaxPerCycle 에 닿거나, 끝에
// 닿거나, 주기 중단 · 부채가 생기면 멈춘다. 페이지는 limit+1 행을 받아 앞의 limit 행만 처리한다 —
// 받은 행이 limit 이하면 끝에 닿은 것이다.
func (j *Janitor) deletePhase(ctx context.Context, st *cycleStats) {
	for st.examined < j.opt.MaxPerCycle {
		n := min(j.opt.Batch, j.opt.MaxPerCycle-st.examined)
		segs, err := j.page(ctx, n+1)
		if err != nil {
			st.abort(classifyQuery(err), "deletable_segments", err)
			return
		}
		for _, seg := range segs[:min(n, len(segs))] {
			st.examined++
			j.cursor = index.LocalCursor{StreamID: seg.StreamID, Seq: seg.Seq}
			if j.skip.held(rowKey{seg.StreamID, seg.Seq}, j.now()) {
				st.skipped++
				continue
			}
			j.deleteRow(ctx, seg, st)
			if st.aborted() || len(j.debt) > 0 || j.halted {
				return
			}
		}
		if len(segs) <= n {
			j.cursor = index.LocalCursor{}
			j.skip.endLap(j.now())
			st.reachedEnd = true
			st.deleteDone = true
			return
		}
	}
	st.deleteDone = true
}

func (j *Janitor) page(ctx context.Context, limit int) ([]index.LocalSegment, error) {
	cctx, cancel := context.WithTimeout(ctx, j.limits.query)
	defer cancel()
	segs, _, err := j.ledger.DeletableSegments(cctx, j.opt.Policy, limit, j.cursor)
	return segs, err
}

// finishCycle 은 게이지를 맞추고 summary 를 낸다(6단계). Run 의 ctx 가 끝나(종료 신호) 주기가
// 끊겼으면 그 중단은 주기 실패가 아니다 — cycle_failing 을 건드리지 않고 종료 사유 한 줄만 낸다
// (종결 r1 R6 — 종료 때 ERROR 헛경보를 막는다).
func (j *Janitor) finishCycle(ctx context.Context, st *cycleStats) {
	g := j.gauges
	switch {
	case st.aborted() && ctx.Err() != nil:
		j.log.Info("janitor_cycle_canceled", st.failAttrs()...)
	case st.aborted():
		g.cycleFailing.set(st.failAttrs()...)
	case st.dbWork:
		g.cycleFailing.clear()
	}
	if st.deleteDone {
		g.backlog(!st.reachedEnd, st.examined)
	}
	j.signalSkip()
	if st.fileOps > 0 && st.stalled == 0 {
		j.stallStreak = 0
		g.fsSuspended.clear()
	}
	if st.rejected > 0 {
		j.log.Error("janitor_path_rejected", "count", st.rejected, "reasons", st.rejectReasons(), "sample", st.rejectSample)
	}
	j.summary(st)
}

// signalSkip 은 건너뛰기 목록에서 나오는 게이지 둘을 맞춘다 — delete_failing(failed · rejected ≥ 1) ·
// volume_suspect(unexpected ≥ 3).
func (j *Janitor) signalSkip() {
	if n := j.skip.count(skipFailed, skipRejected); n > 0 {
		k, _ := j.skip.sample(skipFailed, skipRejected)
		j.gauges.deleteFailing.set("items", n, "sample_stream_id", k.streamID, "sample_seq", k.seq)
	} else {
		j.gauges.deleteFailing.clear()
	}
	if n := j.skip.count(skipUnexpected); n >= volumeSuspectItems {
		k, _ := j.skip.sample(skipUnexpected)
		j.gauges.volumeSuspect.set("items", n, "sample_stream_id", k.streamID, "sample_seq", k.seq)
	} else {
		j.gauges.volumeSuspect.clear()
	}
}

// volumeSuspectItems 는 볼륨 오연결 의심 문턱이다(예상 밖 항목 수 — K7).
const volumeSuspectItems = 3
