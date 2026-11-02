package janitor

// 한 주기의 삭제 흐름 — 행 처리 결과 · 순서 · 진행성 · 스키마 대기(설계 3-1 · 3-2).

import (
	"fmt"
	"log/slog"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// D02 · D44 — 파일을 지운 뒤에만 NULL 하고, 행마다 기본 수준(INFO)에 경로 · 축이 남는다.
func TestCycleRemovesFileBeforeClearingPathAndLogsEachDeletion(t *testing.T) {
	r := newRig(t, nil)
	keys := []rowKey{r.row("s1", 1, false, true), r.row("s1", 2, false, true), r.row("s2", 1, false, true)}

	r.cycle(t)

	ops := r.ops.snapshot()
	for _, k := range keys {
		rel := testRel(k.streamID, k.seq)
		ri, ni := indexOf(ops, "remove "+rel), indexOf(ops, "null "+rel)
		if ri < 0 || ni < 0 || ri > ni {
			t.Errorf("%s 차례 = remove@%d null@%d, want remove 가 null 보다 먼저(ops=%q)", rel, ri, ni, ops)
		}
		if _, ok := r.ledger.pathOf(k); ok {
			t.Errorf("%v 의 local_path 가 NULL 이 아니다", k)
		}
	}
	got := r.log.all("segment_local_deleted")
	if len(got) != len(keys) {
		t.Fatalf("segment_local_deleted %d줄, want %d", len(got), len(keys))
	}
	for i, rec := range got {
		k := keys[i]
		want := map[string]string{"stream_id": k.streamID, "seq": fmt.Sprint(k.seq), "path": testPath(k.streamID, k.seq), "axis": "archive", "outcome": "removed"}
		if rec.level != slog.LevelInfo {
			t.Errorf("segment_local_deleted 등급 = %v, want INFO(E7)", rec.level)
		}
		for key, v := range want {
			if rec.attrs[key] != v {
				t.Errorf("segment_local_deleted[%d].%s = %q, want %q", i, key, rec.attrs[key], v)
			}
		}
		if rec.attrs["grace_basis"] == "" || rec.attrs["basis_age"] == "" {
			t.Errorf("segment_local_deleted[%d] 에 grace_basis · basis_age 가 없다: %v", i, rec.attrs)
		}
	}
}

func indexOf(ops []string, op string) int {
	for i, o := range ops {
		if o == op {
			return i
		}
	}
	return -1
}

// D04 — 나이 지난 행의 ENOENT 는 MediaMTX 가 먼저 지운 것이다 → gone → NULL.
func TestCycleAcceptsOldMissingFileAsGone(t *testing.T) {
	r := newRig(t, nil)
	k := r.row("s1", 1, true, false)

	r.cycle(t)

	if _, ok := r.ledger.pathOf(k); ok {
		t.Fatal("나이 지난 ENOENT 행이 NULL 되지 않았다")
	}
	if rec := r.log.last(t, "segment_local_deleted"); rec.attrs["outcome"] != "gone" {
		t.Errorf("outcome = %q, want gone", rec.attrs["outcome"])
	}
}

// K7 — 젊은 행의 ENOENT 는 설명되지 않는 부재다 → 장부를 그대로 두고 10m 보류한다.
func TestCycleKeepsPathForYoungMissingFile(t *testing.T) {
	r := newRig(t, nil)
	k := r.row("s1", 1, false, false)

	r.cycle(t)

	if _, ok := r.ledger.pathOf(k); !ok {
		t.Fatal("젊은 ENOENT 행이 NULL 됐다(K7 위반)")
	}
	if got := r.log.last(t, "janitor_cycle_summary").attrs["unexpected_gone"]; got != "1" {
		t.Errorf("summary unexpected_gone = %q, want 1", got)
	}
	r.clock.advance(10*time.Minute - time.Second)
	r.cycle(t)
	if n := r.fs.callCount(); n != 1 {
		t.Errorf("10m 보류 안 remove 호출 = %d, want 1(첫 시도만)", n)
	}
	r.clock.advance(time.Second)
	r.cycle(t)
	if n := r.fs.callCount(); n != 2 {
		t.Errorf("10m 뒤 remove 호출 = %d, want 2", n)
	}
}

// 잠긴 행(Busy)은 파일을 건드리지 않고 Interval 동안 보류한다.
func TestCycleLeavesBusyRowUntouchedAndRetriesAfterInterval(t *testing.T) {
	r := newRig(t, nil)
	k := r.row("s1", 1, false, true)
	busy := true
	r.ledger.lockHook = func(rowKey) (index.RowLockResult, error, bool) {
		return index.RowLockResult{Outcome: index.RowLockBusy}, nil, busy
	}

	r.cycle(t)
	if !r.fs.exists(testRel("s1", 1)) || r.fs.callCount() != 0 {
		t.Fatal("잠긴 행의 파일을 건드렸다")
	}
	if got := r.log.last(t, "janitor_cycle_summary").attrs["lock_skipped"]; got != "1" {
		t.Errorf("summary lock_skipped = %q, want 1", got)
	}
	r.cycle(t) // Interval 안 — 다시 잠그지 않는다
	if r.ledger.lockCalls != 1 {
		t.Errorf("Interval 안 선잠금 시도 = %d, want 1", r.ledger.lockCalls)
	}
	busy = false
	r.clock.advance(30 * time.Second)
	r.cycle(t)
	if _, ok := r.ledger.pathOf(k); ok {
		t.Error("잠금이 풀린 뒤에도 NULL 되지 않았다")
	}
}

// c1 다중 경합 — 1,000행이 모두 잠겨 있어도 파일 조작 0 · 부채 0 이다.
func TestCycleWithThousandBusyRowsTouchesNoFileAndOwesNothing(t *testing.T) {
	r := newRig(t, nil)
	for i := range int64(1000) {
		r.row("s1", i+1, false, true)
	}
	r.ledger.lockHook = func(rowKey) (index.RowLockResult, error, bool) {
		return index.RowLockResult{Outcome: index.RowLockBusy}, nil, true
	}

	r.cycle(t)

	if n := r.fs.callCount(); n != 0 {
		t.Errorf("remove 호출 = %d, want 0", n)
	}
	if len(r.j.debt) != 0 {
		t.Errorf("부채 = %d, want 0", len(r.j.debt))
	}
	if got := r.log.last(t, "janitor_cycle_summary").attrs["lock_skipped"]; got != "1000" {
		t.Errorf("summary lock_skipped = %q, want 1000", got)
	}
}

// D41 — 행 부재(clip 탈퇴 정리의 DELETE)는 cas_lost 가 아니다: row_gone DEBUG · 계수만.
func TestCycleCountsGoneRowWithoutCASLost(t *testing.T) {
	r := newRig(t, nil)
	r.row("s1", 1, false, true)
	r.ledger.lockHook = func(rowKey) (index.RowLockResult, error, bool) {
		return index.RowLockResult{Outcome: index.RowLockGone}, nil, true
	}

	r.cycle(t)

	if n := len(r.log.all("janitor_cas_lost")); n != 0 {
		t.Errorf("janitor_cas_lost %d줄, want 0", n)
	}
	gone := r.log.all("janitor_row_gone")
	if len(gone) != 1 || gone[0].level != slog.LevelDebug {
		t.Fatalf("janitor_row_gone = %+v, want DEBUG 1줄", gone)
	}
	if got := r.log.last(t, "janitor_cycle_summary").attrs["row_gone"]; got != "1" {
		t.Errorf("summary row_gone = %q, want 1", got)
	}
	if !r.fs.exists(testRel("s1", 1)) {
		t.Error("행이 없는데 파일을 지웠다")
	}
}

// 값이 바뀐 행은 cas_lost WARN(stage=pre) · 파일 무접촉이다.
func TestCycleWarnsCASLostOnPathChanged(t *testing.T) {
	r := newRig(t, nil)
	r.row("s1", 1, false, true)
	r.ledger.lockHook = func(rowKey) (index.RowLockResult, error, bool) {
		return index.RowLockResult{Outcome: index.RowLockPathChanged}, nil, true
	}

	r.cycle(t)

	rec := r.log.last(t, "janitor_cas_lost")
	if rec.level != slog.LevelWarn || rec.attrs["stage"] != "pre" {
		t.Errorf("janitor_cas_lost = %+v, want WARN stage=pre", rec)
	}
	if !r.fs.exists(testRel("s1", 1)) || r.fs.callCount() != 0 {
		t.Error("값이 바뀐 행의 파일을 건드렸다")
	}
}

// D03 — 영구 실패(EACCES)는 장부를 그대로 두고 지수 보류한다: 창 안 재시도 0, 창이 지나면 1회.
func TestCycleBacksOffPermanentFailureAcrossCycles(t *testing.T) {
	r := newRig(t, nil)
	k := r.row("s1", 1, false, true)
	r.fs.hook = func(string) (error, bool) { return syscall.EACCES, true }

	r.cycle(t)
	if _, ok := r.ledger.pathOf(k); !ok {
		t.Fatal("삭제 실패 행이 NULL 됐다")
	}
	if rec := r.log.last(t, "janitor_cycle_summary"); rec.attrs["failed_permission"] != "1" {
		t.Errorf("summary failed_permission = %q, want 1", rec.attrs["failed_permission"])
	}
	for range 5 {
		r.clock.advance(5 * time.Second) // 첫 보류 = Interval 30s
		r.cycle(t)
	}
	if n := r.fs.callCount(); n != 1 {
		t.Fatalf("보류 창 안 remove 호출 = %d, want 1", n)
	}
	r.clock.advance(5 * time.Second)
	r.cycle(t)
	if n := r.fs.callCount(); n != 2 {
		t.Fatalf("창 뒤 remove 호출 = %d, want 2", n)
	}
	r.clock.advance(59 * time.Second) // 둘째 보류 = 60s
	r.cycle(t)
	if n := r.fs.callCount(); n != 2 {
		t.Errorf("둘째 보류 창 안 remove 호출 = %d, want 2", n)
	}
}

// D15 — 1만 행: 주기마다 살펴보는 행은 MaxPerCycle(2000)이고 5주기 안에 한 바퀴를 돈다.
func TestCycleExaminesAtMostMaxPerCycleAndLapsInFiveCycles(t *testing.T) {
	r := newRig(t, nil)
	for i := range int64(10000) {
		r.row(fmt.Sprintf("s%02d", i%20), i, false, true)
	}
	r.ledger.lockHook = func(rowKey) (index.RowLockResult, error, bool) {
		return index.RowLockResult{Outcome: index.RowLockBusy}, nil, true // 후보에 계속 남게
	}

	for c := 1; c <= 5; c++ {
		r.cycle(t)
		rec := r.log.last(t, "janitor_cycle_summary")
		if rec.attrs["examined"] != "2000" {
			t.Fatalf("주기 %d examined = %q, want 2000", c, rec.attrs["examined"])
		}
		wantEnd := fmt.Sprint(c == 5)
		if rec.attrs["reached_end"] != wantEnd {
			t.Errorf("주기 %d reached_end = %q, want %s", c, rec.attrs["reached_end"], wantEnd)
		}
	}
	if r.ledger.lockCalls != 10000 {
		t.Errorf("5주기 선잠금 시도 = %d, want 10000(한 바퀴)", r.ledger.lockCalls)
	}
}

// D28 — 앞쪽 영구 실패 2,500행이 상한을 채워도 커서가 넘어가 뒤쪽 행은 3주기 안에 정리된다.
func TestCycleCursorAdvancesPastFailingPrefix(t *testing.T) {
	r := newRig(t, nil)
	for i := range int64(2500) {
		r.row("a", i+1, false, true)
	}
	last := r.row("b", 1, false, true)
	r.fs.hook = func(rel string) (error, bool) {
		return syscall.EACCES, strings.HasPrefix(rel, "a/")
	}

	for range 3 {
		r.cycle(t)
	}
	if _, ok := r.ledger.pathOf(last); ok {
		t.Error("앞쪽 실패 행 뒤의 정상 행이 3주기 안에 정리되지 않았다(HOL)")
	}
}

// D42 — 표가 아직 없으면(새 DB) 조회하지 않고 기다린다: cycle_failing 0 · schema_waiting INFO 1회.
func TestCycleWaitsForSchemaWithoutQueryingOrFailing(t *testing.T) {
	r := newRig(t, nil)
	r.row("s1", 1, false, true)
	r.ledger.schemaReady = false

	for range 3 {
		r.cycle(t)
	}
	if r.ledger.deletableCalls+r.ledger.invalidCalls+r.ledger.lockCalls != 0 {
		t.Fatalf("스키마 준비 전 조회 = %d · %d · %d, want 0", r.ledger.deletableCalls, r.ledger.invalidCalls, r.ledger.lockCalls)
	}
	if n := len(r.log.all("janitor_schema_waiting")); n != 1 {
		t.Errorf("janitor_schema_waiting %d줄, want 1(실행당 처음 한 번)", n)
	}
	if n := len(r.log.all("janitor_cycle_failing")); n != 1 { // 시작 덤프 한 줄뿐
		t.Errorf("janitor_cycle_failing %d줄, want 1(시작 덤프만)", n)
	}
	if got := r.log.last(t, "janitor_cycle_summary").attrs["schema_ready"]; got != "false" {
		t.Errorf("summary schema_ready = %q, want false", got)
	}

	r.ledger.schemaReady = true
	r.cycle(t)
	r.cycle(t)
	if n := len(r.log.all("janitor_schema_ready")); n != 1 {
		t.Errorf("janitor_schema_ready %d줄, want 1", n)
	}
	if r.ledger.schemaCalls != 4 {
		t.Errorf("SchemaReady 호출 = %d, want 4(준비 확인 뒤 다시 묻지 않는다)", r.ledger.schemaCalls)
	}
	if _, ok := r.ledger.pathOf(rowKey{"s1", 1}); ok {
		t.Error("준비 뒤에도 삭제가 진행되지 않았다")
	}
}
