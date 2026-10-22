package janitor

// 부채 · 회수 · FS 보류(설계 3-2 「회수」 · 3-3 J-debt · 계획 7절 c1 · c2).

import (
	"context"
	"errors"
	"fmt"
	"syscall"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/fsop"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

var errStall = fmt.Errorf("%w: 시험", fsop.ErrStalled)

func pgErr(code string) error { return &pgconn.PgError{Code: code, Message: "시험 " + code} }

// unrefreshed 는 J-debt 의 셈이다 — janitor 가 remove 를 성공시켰거나 stall 로 끝낸 행 가운데
// 파일은 없는데 장부에 경로가 남은 행 수.
func (r *rig) unrefreshed(touched map[rowKey]bool) int {
	n := 0
	for k := range touched {
		if _, ok := r.ledger.pathOf(k); ok && !r.fs.exists(testRel(k.streamID, k.seq)) {
			n++
		}
	}
	return n
}

// D05 — 삭제가 멈추면 장부를 그대로 두고 부채(maybe)로 들고, 늦은 unlink 를 회수에서 확인해 NULL 한다.
func TestStallBecomesMaybeDebtAndLateUnlinkIsReclaimed(t *testing.T) {
	r := newRig(t, nil)
	k := r.row("s1", 1, false, true)
	r.row("s1", 2, false, true)
	r.fs.hook = func(rel string) (error, bool) { return errStall, rel == testRel("s1", 1) }

	r.cycle(t)
	if _, ok := r.ledger.pathOf(k); !ok {
		t.Fatal("멈춘 삭제의 행이 NULL 됐다(변이 a — ErrStalled 를 성공으로)")
	}
	if len(r.j.debt) != 1 || r.j.debt[0].kind != debtMaybe {
		t.Fatalf("부채 = %+v, want maybe 1(변이 b — stall 을 부채에 안 넣음)", r.j.debt)
	}
	if !r.j.latch.Tripped() {
		t.Error("래치가 서지 않았다")
	}
	if rec := r.log.last(t, "janitor_fs_suspended"); rec.attrs["value"] != "1" {
		t.Errorf("janitor_fs_suspended = %v, want value=1", rec.attrs)
	}
	if r.fs.callCount() != 1 {
		t.Errorf("stall 뒤 같은 주기의 remove = %d, want 1(게이팅)", r.fs.callCount())
	}

	r.fs.hook = nil
	r.fs.unlink(testRel("s1", 1)) // 버려진 워커가 뒤늦게 지웠다
	r.clock.advance(30 * time.Second)
	r.cycle(t)

	if _, ok := r.ledger.pathOf(k); ok {
		t.Fatal("늦은 unlink 를 회수가 NULL 하지 않았다")
	}
	if len(r.j.debt) != 0 {
		t.Fatalf("회수 뒤 부채 = %+v, want 0", r.j.debt)
	}
	if _, ok := r.ledger.pathOf(rowKey{"s1", 2}); ok {
		t.Error("부채가 풀린 주기에 삭제가 재개되지 않았다")
	}
	if rec := r.log.last(t, "janitor_fs_suspended"); rec.attrs["value"] != "0" {
		t.Errorf("stall 없이 파일 조작한 주기 뒤 fs_suspended = %v, want value=0", rec.attrs)
	}
}

// D16 — 계속 멈춰 있어도 보류 창 안에서는 remove · 프로브가 0 이고, 창마다 프로브 1 + remove 1 이다.
func TestPersistentStallProbesOnceAndRemovesOncePerHoldWindow(t *testing.T) {
	r := newRig(t, nil)
	r.row("s1", 1, false, true)
	r.fs.hook = func(string) (error, bool) { return errStall, true }

	r.cycle(t) // 첫 stall — 보류 30s
	probes := func() int { return len(r.log.all("fs_degraded")) }
	for i, hold := range []time.Duration{30 * time.Second, time.Minute, 2 * time.Minute} {
		removes, p := r.fs.callCount(), probes()
		for range 3 {
			r.clock.advance(hold / 4)
			r.cycle(t)
		}
		if r.fs.callCount() != removes || probes() != p {
			t.Fatalf("창 %d 안 remove · 프로브 증가 = %d · %d, want 0 · 0", i, r.fs.callCount()-removes, probes()-p)
		}
		r.clock.advance(hold / 4)
		r.cycle(t)
		if got := r.fs.callCount() - removes; got != 1 {
			t.Errorf("창 %d 끝 remove = %d, want 1", i, got)
		}
		// 프로브 1회 = 래치 해제(fs_degraded value=0) 1줄 + 다시 멈춤(value=1) 1줄.
		if got := probes() - p; got != 2 {
			t.Errorf("창 %d 끝 fs_degraded 줄 = %d, want 2(해제 1 · 재트립 1)", i, got)
		}
	}
}

// D17 a · D23 — COMMIT 을 확인하지 못하면 부채(deleted) · 주기 중단 · 그 주기 추가 삭제 0, 다음
// 주기에 회수한다.
func TestCommitFailureLeavesDeletedDebtAndStopsCycle(t *testing.T) {
	r := newRig(t, nil)
	k := r.row("s1", 1, false, true)
	r.row("s1", 2, false, true)
	r.row("s1", 3, false, true)
	fail := true
	r.ledger.commitHook = func(context.Context, rowKey, bool) error {
		if fail {
			fail = false
			return errors.New("연결 끊김")
		}
		return nil
	}

	r.cycle(t)
	if r.fs.callCount() != 1 {
		t.Fatalf("COMMIT 실패 뒤 같은 주기 remove = %d, want 1(변이 — StageCommit 오류 무시)", r.fs.callCount())
	}
	if len(r.j.debt) != 1 || r.j.debt[0].kind != debtDeleted {
		t.Fatalf("부채 = %+v, want deleted 1", r.j.debt)
	}
	rec := r.log.last(t, "janitor_cycle_failing")
	if rec.attrs["value"] != "1" || rec.attrs["stage"] != "commit" || rec.attrs["reason"] != "db_error" {
		t.Errorf("janitor_cycle_failing = %v, want value=1 stage=commit reason=db_error", rec.attrs)
	}

	r.clock.advance(30 * time.Second)
	r.cycle(t)
	if _, ok := r.ledger.pathOf(k); ok {
		t.Error("회수가 부채 행을 NULL 하지 않았다")
	}
	if r.ledger.nonNullCount() != 0 {
		t.Errorf("회수 뒤 남은 행 = %d, want 0(삭제 재개)", r.ledger.nonNullCount())
	}
	if rec := r.log.last(t, "janitor_cycle_failing"); rec.attrs["value"] != "0" {
		t.Errorf("중단 없이 끝난 주기 뒤 cycle_failing = %v, want value=0", rec.attrs)
	}
}

// D17 b — 삭제와 COMMIT 사이에 죽은 뒤 새 실행: 부채는 비어 있으므로 그 행은 젊은 ENOENT(예상 밖) —
// NULL 0 · unexpected 1 · volume_suspect 꺼짐(문턱 3).
func TestNewRunTreatsPreviousRunsUnclearedRowAsUnexpected(t *testing.T) {
	r := newRig(t, nil)
	k := r.row("s1", 1, false, false) // 지난 실행이 파일을 지웠고 장부는 남았다

	r.cycle(t)

	if _, ok := r.ledger.pathOf(k); !ok {
		t.Fatal("새 실행이 젊은 ENOENT 행을 NULL 했다")
	}
	if got := r.j.skip.count(skipUnexpected); got != 1 {
		t.Errorf("예상 밖 항목 = %d, want 1", got)
	}
	if n := len(r.log.all("janitor_volume_suspect")); n != 1 {
		t.Errorf("janitor_volume_suspect %d줄, want 1(시작 덤프만 — 변이 문턱 3→1 이면 켜진다)", n)
	}
}

// D23 — 선잠금 단계 오류는 파일을 건드리지 않고 주기를 멈춘다.
func TestLockStageErrorLeavesFileAndStopsCycle(t *testing.T) {
	r := newRig(t, nil)
	r.row("s1", 1, false, true)
	r.row("s1", 2, false, true)
	r.ledger.lockHook = func(rowKey) (index.RowLockResult, error, bool) {
		return index.RowLockResult{Stage: index.StageLock}, pgErr("40P01"), true
	}

	r.cycle(t)

	if r.fs.callCount() != 0 || len(r.j.debt) != 0 {
		t.Fatalf("선잠금 오류에서 remove = %d · 부채 = %d, want 0 · 0", r.fs.callCount(), len(r.j.debt))
	}
	if r.ledger.lockCalls != 1 {
		t.Errorf("선잠금 시도 = %d, want 1(주기 중단)", r.ledger.lockCalls)
	}
	if rec := r.log.last(t, "janitor_cycle_failing"); rec.attrs["reason"] != "deadlock" || rec.attrs["stage"] != "lock" {
		t.Errorf("janitor_cycle_failing = %v, want reason=deadlock stage=lock", rec.attrs)
	}
}

// D29 — remove 뒤 COMMIT 이 답하지 않으면 클라이언트 기한 + 1s 안에 주기를 끝낸다 · 추가 remove 0 ·
// 부채 1 · 다음 주기는 회수만 한다.
func TestCommitHangEndsCycleWithinDeadlineAndNextCycleOnlyReclaims(t *testing.T) {
	r := newRig(t, nil)
	r.j.limits.rowLock = 50 * time.Millisecond
	r.row("s1", 1, false, true)
	r.row("s1", 2, false, true)
	r.ledger.commitHook = func(ctx context.Context, _ rowKey, _ bool) error {
		<-ctx.Done()
		return ctx.Err()
	}
	r.ledger.clearHook = func(context.Context, rowKey) (bool, error, bool) { return false, pgErr("55P03"), true }

	start := time.Now()
	r.cycle(t)
	if took := time.Since(start); took > 50*time.Millisecond+time.Second {
		t.Fatalf("주기 = %v, want ≤ 기한 + 1s", took)
	}
	if r.fs.callCount() != 1 || len(r.j.debt) != 1 {
		t.Fatalf("remove = %d · 부채 = %d, want 1 · 1(변이 r2.1 꼴 — 기한 초과를 행 미룸으로)", r.fs.callCount(), len(r.j.debt))
	}
	if rec := r.log.last(t, "janitor_cycle_failing"); rec.attrs["reason"] != "client_deadline" {
		t.Errorf("reason = %q, want client_deadline", rec.attrs["reason"])
	}

	locks := r.ledger.lockCalls
	r.clock.advance(30 * time.Second)
	r.cycle(t)
	if r.ledger.lockCalls != locks || r.fs.callCount() != 1 || r.ledger.clearCalls != 1 {
		t.Errorf("다음 주기 선잠금 +%d · remove %d · 회수 %d, want +0 · 1 · 1(회수만)", r.ledger.lockCalls-locks, r.fs.callCount(), r.ledger.clearCalls)
	}
}

// D39-a — 부채(deleted)의 회수가 55P03 으로 10번 막혀도 드롭하지 않는다: 그동안 삭제 0 ·
// 10번째에 reclaim_blocked=1 · 11번째에 풀리면 부채 0 · 게이지 clear · 삭제 재개.
func TestDeletedDebtBlockedByLockIsNeverDropped(t *testing.T) {
	r := newRig(t, nil)
	k := r.row("s1", 1, false, true)
	next := r.row("s1", 2, false, true)
	r.ledger.commitHook = func(context.Context, rowKey, bool) error { return errors.New("COMMIT 미확인") }
	r.cycle(t)
	r.ledger.commitHook = nil
	attempts := 0
	r.ledger.clearHook = func(context.Context, rowKey) (bool, error, bool) {
		attempts++
		return false, pgErr("55P03"), attempts <= 10
	}

	for i := 1; i <= 10; i++ {
		r.clock.advance(30 * time.Second)
		r.cycle(t)
		if r.fs.callCount() != 1 {
			t.Fatalf("회수 %d회째 삭제 remove = %d, want 1(변이 — r3 의 10회 드롭)", i, r.fs.callCount())
		}
		blocked := r.log.all("janitor_reclaim_blocked")
		if want := map[bool]int{true: 2, false: 1}[i >= 10]; len(blocked) != want {
			t.Fatalf("회수 %d회째 reclaim_blocked 줄 = %d, want %d", i, len(blocked), want)
		}
		if i < 10 {
			if rec := r.log.last(t, "janitor_cycle_failing"); rec.attrs["value"] == "1" {
				t.Fatalf("회수 55P03 이 주기 중단이 됐다: %v", rec.attrs)
			}
		}
	}
	if rec := r.log.last(t, "janitor_reclaim_blocked"); rec.attrs["value"] != "1" || rec.attrs["kind"] != "deleted" || rec.attrs["failures"] != "10" {
		t.Errorf("reclaim_blocked = %v, want value=1 kind=deleted failures=10", rec.attrs)
	}
	if r.unrefreshed(map[rowKey]bool{k: true, next: true}) > 1 {
		t.Fatal("J-debt 위반 — 미갱신 2")
	}

	r.clock.advance(30 * time.Second)
	r.cycle(t)
	if len(r.j.debt) != 0 {
		t.Fatalf("11회째 뒤 부채 = %+v, want 0", r.j.debt)
	}
	if rec := r.log.last(t, "janitor_reclaim_blocked"); rec.attrs["value"] != "0" {
		t.Errorf("해결 뒤 reclaim_blocked = %v, want value=0", rec.attrs)
	}
	if _, ok := r.ledger.pathOf(next); ok {
		t.Error("부채 해결 뒤 삭제가 재개되지 않았다")
	}
}

// D39-b — 부채(maybe)의 회수 remove 가 3번 연속 멈춰도 드롭하지 않는다 · reclaim_blocked=1 · 삭제 0.
func TestMaybeDebtStallingThreeTimesIsNeverDropped(t *testing.T) {
	r := newRig(t, nil)
	r.row("s1", 1, false, true)
	r.row("s1", 2, false, true)
	r.fs.hook = func(rel string) (error, bool) { return errStall, rel == testRel("s1", 1) }

	r.cycle(t) // 첫 stall → 부채 maybe
	for range 6 {
		r.clock.advance(fsHoldMax)
		r.cycle(t)
	}
	if len(r.j.debt) != 1 || r.j.debt[0].kind != debtMaybe {
		t.Fatalf("부채 = %+v, want maybe 1(변이 — r3 의 3회 드롭)", r.j.debt)
	}
	if r.fs.exists(testRel("s1", 2)) == false {
		t.Error("부채가 있는데 다음 행을 지웠다")
	}
	rec := r.log.last(t, "janitor_reclaim_blocked")
	if rec.attrs["value"] != "1" || rec.attrs["kind"] != "maybe" {
		t.Errorf("reclaim_blocked = %v, want value=1 kind=maybe", rec.attrs)
	}
}

// D39-c — 늦은 unlink: 회수 remove 가 ENOENT → deleted → ClearLocalPath true → 부채 0.
// D39-d — 부채 행이 사라졌다(clip DELETE): ClearLocalPath false → 해결.
func TestDebtResolvesOnLateUnlinkAndOnRowAbsence(t *testing.T) {
	t.Run("늦은 unlink", func(t *testing.T) {
		r := newRig(t, nil)
		k := r.row("s1", 1, false, true)
		r.fs.hook = func(string) (error, bool) { return errStall, true }
		r.cycle(t)
		r.fs.hook = nil
		r.fs.unlink(testRel("s1", 1))
		r.clock.advance(30 * time.Second)
		r.cycle(t)
		if _, ok := r.ledger.pathOf(k); ok || len(r.j.debt) != 0 {
			t.Errorf("늦은 unlink 뒤 장부 남음 = %v · 부채 = %d, want NULL · 0", ok, len(r.j.debt))
		}
	})
	t.Run("행 부재", func(t *testing.T) {
		r := newRig(t, nil)
		k := r.row("s1", 1, false, true)
		r.ledger.commitHook = func(context.Context, rowKey, bool) error { return errors.New("COMMIT 미확인") }
		r.cycle(t)
		r.ledger.commitHook = nil
		r.ledger.drop(k)
		r.clock.advance(30 * time.Second)
		r.cycle(t)
		if len(r.j.debt) != 0 {
			t.Errorf("행이 사라진 부채 = %+v, want 해결", r.j.debt)
		}
	})
}

// c2 — 회수 remove 의 일반 오류(EIO)는 파일 실재를 증명하지 않는다 → 부채 유지 · 연속 +1 · 다음 행
// 시도 0. 버려진 워커가 그 뒤에 늦게 지워도 미갱신은 1 을 넘지 않는다(변이: 일반 오류를 해결로 → 2).
func TestReclaimGeneralErrorKeepsMaybeDebt(t *testing.T) {
	for _, fileGoneBeforeEIO := range []bool{false, true} {
		t.Run(fmt.Sprintf("EIO 전에 파일 없음=%v", fileGoneBeforeEIO), func(t *testing.T) {
			r := newRig(t, nil)
			k1 := r.row("s1", 1, false, true)
			k2 := r.row("s1", 2, false, true)
			r.fs.hook = func(rel string) (error, bool) { return errStall, rel == testRel("s1", 1) }
			r.cycle(t)

			if fileGoneBeforeEIO {
				r.fs.unlink(testRel("s1", 1))
			}
			r.fs.hook = func(rel string) (error, bool) { return syscall.EIO, rel == testRel("s1", 1) }
			r.clock.advance(30 * time.Second)
			r.cycle(t)                    // 회수 EIO
			r.fs.unlink(testRel("s1", 1)) // 늦은 unlink
			r.clock.advance(30 * time.Second)
			r.cycle(t)

			if len(r.j.debt) != 1 || r.j.debt[0].failures < 1 {
				t.Fatalf("부채 = %+v, want maybe 유지 · 연속 실패 ≥ 1", r.j.debt)
			}
			if !r.fs.exists(testRel("s1", 2)) {
				t.Error("부채가 남았는데 다음 행을 지웠다")
			}
			if n := r.unrefreshed(map[rowKey]bool{k1: true, k2: true}); n > 1 {
				t.Errorf("미갱신 = %d, want ≤ 1", n)
			}
		})
	}
}

// 방어 단언 — 부채가 둘이 되면(정상 경로에서는 0) invariant_violated ERROR · 새 삭제 중지.
func TestSecondDebtTripsInvariantAndHaltsDeletion(t *testing.T) {
	r := newRig(t, nil)
	r.row("s1", 9, false, true)
	r.j.addDebt(index.LocalSegment{StreamID: "s1", Seq: 1, LocalPath: testPath("s1", 1)}, debtDeleted, "removed")
	r.j.addDebt(index.LocalSegment{StreamID: "s1", Seq: 2, LocalPath: testPath("s1", 2)}, debtDeleted, "removed")

	rec := r.log.last(t, "janitor_invariant_violated")
	if rec.attrs["debt_size"] != "2" {
		t.Errorf("janitor_invariant_violated = %v, want debt_size=2", rec.attrs)
	}
	for range 3 {
		r.cycle(t) // 회수가 둘 다 풀어도 이 실행에서는 새 삭제를 하지 않는다
	}
	// 선잠금 횟수로는 세지 않는다 — 회수 해결의 행 실재 확인(probeRow · Q3)도 선잠금을 쓴다.
	if _, ok := r.ledger.pathOf(rowKey{"s1", 9}); !ok || r.fs.callCount() != 0 {
		t.Errorf("불변식 위반 뒤 새 삭제: remove = %d · s1/9 장부 남음 = %v, want 0 · 참", r.fs.callCount(), ok)
	}
}

// 3-3 「ClearLocalPath(회수)」 행 — 55P03 만 연속 +1 · 주기 계속, 그 밖은 항목 유지 · 주기 중단.
func TestReclaimErrorColumns(t *testing.T) {
	cases := []struct {
		name       string
		err        error
		wantReason string // 빈 값 = 주기 중단 없음
		wantFail   int
	}{
		{"55P03", pgErr("55P03"), "", 1},
		{"40P01", pgErr("40P01"), "deadlock", 0},
		{"57014", pgErr("57014"), "statement_timeout", 0},
		{"25P04 은 그 밖으로", pgErr("25P04"), "db_error", 0},
		{"클라이언트 기한", context.DeadlineExceeded, "client_deadline", 0},
		{"연결 획득", &pgconn.ConnectError{}, "acquire", 0},
		{"그 밖", errors.New("x"), "db_error", 0},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			r := newRig(t, nil)
			r.row("s1", 1, false, true)
			r.ledger.commitHook = func(context.Context, rowKey, bool) error { return errors.New("COMMIT 미확인") }
			r.cycle(t)
			r.ledger.commitHook = nil
			r.ledger.clearHook = func(context.Context, rowKey) (bool, error, bool) { return false, c.err, true }
			r.clock.advance(30 * time.Second)
			r.cycle(t)

			if len(r.j.debt) != 1 || r.j.debt[0].failures != c.wantFail {
				t.Fatalf("부채 = %+v, want 1 · 연속 실패 %d", r.j.debt, c.wantFail)
			}
			rec := r.log.last(t, "janitor_cycle_summary")
			if rec.attrs["abort_reason"] != c.wantReason {
				t.Errorf("abort_reason = %q, want %q", rec.attrs["abort_reason"], c.wantReason)
			}
		})
	}
}
