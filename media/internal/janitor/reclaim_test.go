package janitor

// 회수의 기록 — 막힘 게이지의 계수 · 종류(t2 r1 Q9)와 응답 유실 뒤 삭제 한 줄(t2 r1 Q3).

import (
	"context"
	"errors"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// stallFirstThenAllow 는 s1/1 의 remove 를 n 번 멈춘 뒤 통과시킨다.
func stallFirstThenAllow(r *rig, n int) {
	stalls := 0
	r.fs.hook = func(rel string) (error, bool) {
		if rel != testRel("s1", 1) || stalls >= n {
			return nil, false
		}
		stalls++
		return errStall, true
	}
}

// maybeDebtCycles 는 FS 보류를 넘기며 n 주기를 돈다.
func maybeDebtCycles(t *testing.T, r *rig, n int) {
	t.Helper()
	for range n {
		r.clock.advance(fsHoldMax)
		r.cycle(t)
	}
}

// Q9 — maybe 가 deleted 로 바뀌면 연속 실패 수를 새로 센다. maybe 때 2번 멈춘 뒤 deleted 로 바뀌고
// 55P03 이 8번이면 deleted 문턱(10)에 닿지 않았다(변이 — 전환 때 failures 유지 → 2+8=10 으로 켜짐).
func TestReclaimBlockedCountRestartsWhenMaybeBecomesDeleted(t *testing.T) {
	r := newRig(t, nil)
	r.row("s1", 1, false, true)
	stallFirstThenAllow(r, 3) // 삭제 1 + 회수 2
	r.cycle(t)
	maybeDebtCycles(t, r, 2)
	if len(r.j.debt) != 1 || r.j.debt[0].kind != debtMaybe || r.j.debt[0].failures != 2 {
		t.Fatalf("부채 = %+v, want maybe · failures 2", r.j.debt)
	}
	locked := 0
	r.ledger.clearHook = func(context.Context, rowKey) (bool, error, bool) {
		locked++
		return false, pgErr("55P03"), locked <= 8
	}

	maybeDebtCycles(t, r, 8)

	if len(r.j.debt) != 1 || r.j.debt[0].kind != debtDeleted {
		t.Fatalf("부채 = %+v, want deleted 1", r.j.debt)
	}
	if got := r.j.debt[0].failures; got != 8 {
		t.Errorf("deleted 전환 뒤 failures = %d, want 8(전환 때 0 부터)", got)
	}
	for _, rec := range r.log.all("janitor_reclaim_blocked") {
		if rec.attrs["value"] == "1" {
			t.Fatalf("deleted 연속 실패 8 에서 reclaim_blocked 가 켜졌다: %v", rec.attrs)
		}
	}
}

// Q9 — reclaim_blocked 의 clear 줄은 set 줄과 같은 kind 를 싣는다. maybe 로 막혀 켜진 뒤 회수가
// deleted 로 바뀌어 해결되면 clear 줄의 kind 는 maybe 다(변이 — 해결 때의 kind 를 기록).
func TestReclaimBlockedClearCarriesKindOfSet(t *testing.T) {
	r := newRig(t, nil)
	r.row("s1", 1, false, true)
	stallFirstThenAllow(r, 4) // 삭제 1 + 회수 3 → 문턱 3
	r.cycle(t)
	maybeDebtCycles(t, r, 3)
	if set := r.log.last(t, "janitor_reclaim_blocked"); set.attrs["value"] != "1" || set.attrs["kind"] != "maybe" {
		t.Fatalf("reclaim_blocked = %v, want value=1 kind=maybe", set.attrs)
	}

	maybeDebtCycles(t, r, 1) // remove 통과 → deleted → ClearLocalPath true

	if len(r.j.debt) != 0 {
		t.Fatalf("부채 = %+v, want 해결", r.j.debt)
	}
	if clr := r.log.last(t, "janitor_reclaim_blocked"); clr.attrs["value"] != "0" || clr.attrs["kind"] != "maybe" {
		t.Errorf("clear 줄 = %v, want value=0 kind=maybe(set 줄과 같은 kind)", clr.attrs)
	}
}

// loseFirstCommitResponse 는 k 의 첫 COMMIT 을 서버에서는 반영하고(NULL) 응답만 잃게 한다.
func loseFirstCommitResponse(r *rig, k rowKey) {
	lost := true
	r.ledger.commitHook = func(context.Context, rowKey, bool) error {
		if !lost {
			return nil
		}
		lost = false
		r.ledger.setPath(k, nil)
		return errors.New("COMMIT 응답 유실")
	}
}

// Q3 — 파일 삭제와 COMMIT 은 됐는데 응답만 잃으면, 다음 회수의 ClearLocalPath 는 이미 NULL 이라 false
// 다. 그래도 행이 실재하고 경로가 비었으면 확인된 삭제 사실을 한 줄 남긴다(outcome 은 삭제 때 본 것).
// 행이 사라져 해소되면(clip DELETE) 줄을 내지 않는다(변이 — false 해소에서 줄 생략 · 행 부재에서도 냄).
// 행 실재 확인이 Busy 로 끝나면 모름이라 줄을 내지 않는다(부채는 이미 해소 — 줄 하나를 잃는 쪽).
func TestReclaimLogsConfirmedDeletionWhenCommitResponseWasLost(t *testing.T) {
	cases := []struct {
		name     string
		due      bool
		file     bool
		dropRow  bool
		busy     bool // 행 실재 확인(선잠금)이 Busy
		wantLine bool
		outcome  string
	}{
		{"응답 유실 · removed", false, true, false, false, true, "removed"},
		{"응답 유실 · 나이 지난 gone", true, false, false, false, true, "gone"},
		{"행 부재(clip DELETE)", false, true, true, false, false, ""},
		{"행 실재 확인 Busy(모름)", false, true, false, true, false, ""},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			r := newRig(t, nil)
			k := r.row("s1", 1, c.due, c.file)
			loseFirstCommitResponse(r, k)
			r.cycle(t)
			if len(r.j.debt) != 1 || r.log.all("segment_local_deleted") != nil {
				t.Fatalf("부채 = %+v · 삭제 줄 = %d, want deleted 1 · 0", r.j.debt, len(r.log.all("segment_local_deleted")))
			}
			if c.dropRow {
				r.ledger.drop(k)
			}
			if c.busy {
				r.ledger.lockHook = func(rowKey) (index.RowLockResult, error, bool) {
					return index.RowLockResult{Outcome: index.RowLockBusy}, nil, true
				}
			}

			maybeDebtCycles(t, r, 1)

			if len(r.j.debt) != 0 {
				t.Fatalf("부채 = %+v, want 해결", r.j.debt)
			}
			lines := r.log.all("segment_local_deleted")
			switch {
			case !c.wantLine && len(lines) != 0:
				t.Errorf("행 부재 · 모름으로 해소됐는데 삭제 줄 %d, want 0", len(lines))
			case c.wantLine && (len(lines) != 1 || lines[0].attrs["outcome"] != c.outcome || lines[0].attrs["axis"] != archiveAxis):
				t.Errorf("삭제 줄 = %v, want 1줄 outcome=%s axis=%s", lines, c.outcome, archiveAxis)
			}
		})
	}
}
