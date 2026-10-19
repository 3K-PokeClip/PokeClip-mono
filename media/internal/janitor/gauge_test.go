package janitor

// 관측 신호 — 게이지 7종 · 시작 덤프 · 오류 분기 표(설계 3-3 · 8절).

import (
	"context"
	"errors"
	"log/slog"
	"os"
	"syscall"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// warnRig 은 WARN 이상만 받는 처리기를 끼운 rig 이다(LOG_LEVEL=warn 수집의 흉내 — D32).
func warnRig(t *testing.T, tune func(*Options)) (*rig, *logSink) {
	t.Helper()
	warn := newLogSink(slog.LevelWarn)
	r := newRig(t, func(o *Options) {
		o.Log = slog.New(warn)
		if tune != nil {
			tune(o)
		}
	})
	return r, warn
}

// assertToggled 는 gauge 가 WARN 처리기에서 set(value=1) 뒤 clear(value=0) 로 보였는지 본다.
func assertToggled(t *testing.T, sink *logSink, name string) {
	t.Helper()
	var set, cleared bool
	for _, rec := range sink.all(name) {
		if rec.attrs["reason"] == "startup" {
			continue
		}
		switch rec.attrs["value"] {
		case "1":
			set = true
		case "0":
			cleared = set
		}
		if rec.attrs["run_id"] != "run-1" {
			t.Errorf("%s 줄에 run_id 가 없다: %v", name, rec.attrs)
		}
	}
	if !set || !cleared {
		t.Errorf("%s: WARN 처리기에서 set=%v clear=%v, want 둘 다(변이 — clear 를 INFO 로)", name, set, cleared)
	}
}

// D32 — 게이지 7종 모두 set 과 clear 가 WARN 수준 수집에서 보인다.
func TestEveryGaugeSetAndClearAreVisibleAtWarn(t *testing.T) {
	t.Run("delete_failing", func(t *testing.T) {
		r, warn := warnRig(t, nil)
		r.row("s1", 1, false, true)
		r.fs.hook = func(string) (error, bool) { return syscall.EACCES, true }
		r.cycle(t)
		r.fs.hook = nil
		r.clock.advance(30 * time.Second)
		r.cycle(t)
		assertToggled(t, warn, "janitor_delete_failing")
	})
	t.Run("cycle_failing", func(t *testing.T) {
		r, warn := warnRig(t, nil)
		r.ledger.deletableErr = errors.New("x")
		r.cycle(t)
		r.ledger.deletableErr = nil
		r.cycle(t)
		assertToggled(t, warn, "janitor_cycle_failing")
	})
	t.Run("cutoff_invalid", func(t *testing.T) {
		r, warn := warnRig(t, nil)
		r.ledger.invalid = []index.InvalidCutoff{{StreamID: "s1", CutoffSeq: 7}}
		r.cycle(t)
		r.ledger.invalid = nil
		r.cycle(t)
		assertToggled(t, warn, "janitor_cutoff_invalid")
	})
	t.Run("backlog_capped", func(t *testing.T) {
		r, warn := warnRig(t, func(o *Options) { o.Batch, o.MaxPerCycle = 2, 2 })
		for i := range int64(3) {
			r.row("s1", i+1, false, true)
		}
		r.cycle(t)
		r.cycle(t)
		assertToggled(t, warn, "janitor_backlog_capped")
	})
	t.Run("volume_suspect", func(t *testing.T) {
		r, warn := warnRig(t, nil)
		for i := range int64(3) {
			r.row("s1", i+1, false, false)
		}
		r.cycle(t)
		for i := range int64(3) {
			r.fs.put(testRel("s1", i+1)) // 실물 볼륨 복원
		}
		r.clock.advance(unexpectedHold)
		r.cycle(t)
		assertToggled(t, warn, "janitor_volume_suspect")
	})
	t.Run("fs_suspended", func(t *testing.T) {
		r, warn := warnRig(t, nil)
		r.row("s1", 1, false, true)
		r.fs.hook = func(string) (error, bool) { return errStall, true }
		r.cycle(t)
		r.fs.hook = nil
		r.clock.advance(30 * time.Second)
		r.cycle(t)
		assertToggled(t, warn, "janitor_fs_suspended")
	})
	t.Run("reclaim_blocked", func(t *testing.T) {
		r, warn := warnRig(t, nil)
		r.row("s1", 1, false, true)
		r.ledger.commitHook = func(context.Context, rowKey, bool) error { return errors.New("x") }
		r.cycle(t)
		r.ledger.commitHook = nil
		n := 0
		r.ledger.clearHook = func(context.Context, rowKey) (bool, error, bool) {
			n++
			return false, pgErr("55P03"), n <= deletedBlockedFailures
		}
		for range deletedBlockedFailures + 1 {
			r.cycle(t)
		}
		assertToggled(t, warn, "janitor_reclaim_blocked")
	})
}

// 시작 덤프 소비 계약 — 다른 어떤 게이지 줄보다 먼저 7종이 value=0 reason=startup 이고,
// cutoff_invalid 는 stream_id="*" 다. 현재 무효 스트림의 value=1 은 그 뒤에 온다.
func TestStartDumpPrecedesEveryOtherGaugeLine(t *testing.T) {
	r, warn := warnRig(t, nil)
	r.ledger.invalid = []index.InvalidCutoff{{StreamID: "s9", CutoffSeq: 3}}
	r.cycle(t)

	recs := warn.all("")
	if len(recs) < 8 {
		t.Fatalf("줄 = %d, want ≥ 8", len(recs))
	}
	want := map[string]bool{"janitor_delete_failing": true, "janitor_cycle_failing": true, "janitor_cutoff_invalid": true,
		"janitor_backlog_capped": true, "janitor_volume_suspect": true, "janitor_fs_suspended": true, "janitor_reclaim_blocked": true}
	for i, rec := range recs[:7] {
		if !want[rec.msg] || rec.attrs["value"] != "0" || rec.attrs["reason"] != "startup" || rec.attrs["run_id"] != "run-1" {
			t.Errorf("덤프 %d = %s %v, want 게이지 value=0 reason=startup run_id", i, rec.msg, rec.attrs)
		}
		if rec.msg == cutoffInvalidName && rec.attrs["stream_id"] != "*" {
			t.Errorf("cutoff_invalid 덤프 stream_id = %q, want *", rec.attrs["stream_id"])
		}
		delete(want, rec.msg)
	}
	if len(want) != 0 {
		t.Errorf("덤프에 빠진 게이지: %v", want)
	}
	if rec := recs[7]; rec.msg != cutoffInvalidName || rec.attrs["value"] != "1" || rec.attrs["stream_id"] != "s9" {
		t.Errorf("덤프 다음 줄 = %s %v, want cutoff_invalid value=1 stream_id=s9", rec.msg, rec.attrs)
	}
	r.cycle(t)
	if n := len(warn.all("janitor_reclaim_blocked")); n != 1 {
		t.Errorf("둘째 주기에 시작 덤프가 다시 났다(reclaim_blocked %d줄)", n)
	}
}

// D30 a — 빈 볼륨 + 젊은 행 100 → NULL 0 · volume_suspect=1.
// D30 b — 빈 볼륨 + 나이 지난 1000 + 젊은 10 → 나이 지난 행 NULL(L17 수용) · 젊은 행 NULL 0 · 게이지 1.
func TestEmptyVolumeKeepsYoungRowsAndRaisesVolumeSuspect(t *testing.T) {
	t.Run("a 젊은 100", func(t *testing.T) {
		r := newRig(t, nil)
		for i := range int64(100) {
			r.row("s1", i+1, false, false)
		}
		r.cycle(t)
		if n := r.ledger.nonNullCount(); n != 100 {
			t.Errorf("NULL 된 젊은 행 = %d, want 0(변이 — 나이 검사 제거)", 100-n)
		}
		if rec := r.log.last(t, "janitor_volume_suspect"); rec.attrs["value"] != "1" {
			t.Errorf("volume_suspect = %v, want value=1", rec.attrs)
		}
	})
	t.Run("b 나이 지난 1000 + 젊은 10", func(t *testing.T) {
		r := newRig(t, nil)
		for i := range int64(1000) {
			r.row("old", i+1, true, false)
		}
		for i := range int64(10) {
			r.row("young", i+1, false, false)
		}
		r.cycle(t)
		if n := r.ledger.nonNullCount(); n != 10 {
			t.Errorf("남은 행 = %d, want 10(젊은 행만)", n)
		}
		if rec := r.log.last(t, "janitor_volume_suspect"); rec.attrs["value"] != "1" {
			t.Errorf("volume_suspect = %v, want value=1(변이 — 비율 조건 재도입이면 0)", rec.attrs)
		}
	})
}

// 3-3 「조회」 행 — 행을 잠그지 않으므로 55P03 · 40P01 · 25P04 는 「그 밖」(db_error)이다.
func TestQueryErrorColumns(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want string
	}{
		{"55P03", pgErr("55P03"), "db_error"},
		{"40P01", pgErr("40P01"), "db_error"},
		{"57014", pgErr("57014"), "statement_timeout"},
		{"25P04", pgErr("25P04"), "db_error"},
		{"클라이언트 기한", context.DeadlineExceeded, "client_deadline"},
		{"연결 획득", &pgconn.ConnectError{}, "acquire"},
		{"그 밖", errors.New("x"), "db_error"},
	}
	calls := []struct {
		name string
		set  func(l *fakeLedger, err error)
	}{
		{"SchemaReady", func(l *fakeLedger, err error) { l.schemaErr = err }},
		{"InvalidCutoffs", func(l *fakeLedger, err error) { l.invalidErr = err }},
		{"DeletableSegments", func(l *fakeLedger, err error) { l.deletableErr = err }},
	}
	for _, call := range calls {
		for _, c := range cases {
			t.Run(call.name+"/"+c.name, func(t *testing.T) {
				r := newRig(t, nil)
				r.row("s1", 1, false, true)
				call.set(r.ledger, c.err)
				r.cycle(t)
				rec := r.log.last(t, "janitor_cycle_failing")
				if rec.attrs["value"] != "1" || rec.attrs["reason"] != c.want {
					t.Errorf("cycle_failing = %v, want value=1 reason=%s", rec.attrs, c.want)
				}
				if r.fs.callCount() != 0 {
					t.Error("조회 실패 주기에 파일을 건드렸다")
				}
			})
		}
	}
}

// 3-3 「RemoveUnderRowLock」 행 — StageLock(remove 전)은 파일 무접촉 · 주기 중단, StageCommit 은
// Cleared 면 부채(deleted) · 주기 중단, Cleared 가 아니면(ROLLBACK 오류) act 가 남긴 것만 남는다.
func TestRowLockErrorColumns(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want string
	}{
		{"55P03", pgErr("55P03"), "lock_timeout"},
		{"40P01", pgErr("40P01"), "deadlock"},
		{"57014", pgErr("57014"), "statement_timeout"},
		{"25P04", pgErr("25P04"), "txn_timeout"},
		{"클라이언트 기한", context.DeadlineExceeded, "client_deadline"},
		{"그 밖", errors.New("x"), "db_error"},
	}
	for _, c := range cases {
		t.Run("StageLock/"+c.name, func(t *testing.T) {
			r := newRig(t, nil)
			r.row("s1", 1, false, true)
			r.ledger.lockHook = func(rowKey) (index.RowLockResult, error, bool) {
				return index.RowLockResult{Stage: index.StageLock}, c.err, true
			}
			r.cycle(t)
			rec := r.log.last(t, "janitor_cycle_failing")
			if rec.attrs["reason"] != c.want || rec.attrs["stage"] != "lock" || r.fs.callCount() != 0 || len(r.j.debt) != 0 {
				t.Errorf("cycle_failing = %v · remove %d · 부채 %d, want reason=%s stage=lock · 0 · 0", rec.attrs, r.fs.callCount(), len(r.j.debt), c.want)
			}
		})
		t.Run("StageCommit Cleared/"+c.name, func(t *testing.T) {
			r := newRig(t, nil)
			r.row("s1", 1, false, true)
			r.row("s1", 2, false, true)
			r.ledger.commitHook = func(context.Context, rowKey, bool) error { return c.err }
			r.cycle(t)
			rec := r.log.last(t, "janitor_cycle_failing")
			if rec.attrs["reason"] != c.want || rec.attrs["stage"] != "commit" {
				t.Errorf("cycle_failing = %v, want reason=%s stage=commit", rec.attrs, c.want)
			}
			if len(r.j.debt) != 1 || r.j.debt[0].kind != debtDeleted || r.fs.callCount() != 1 {
				t.Errorf("부채 = %+v · remove %d, want deleted 1 · 1(변이 — 주기 계속)", r.j.debt, r.fs.callCount())
			}
		})
		t.Run("StageCommit Kept/"+c.name, func(t *testing.T) {
			r := newRig(t, nil)
			r.row("s1", 1, false, false) // 젊은 ENOENT → clear=false → ROLLBACK
			r.ledger.commitHook = func(_ context.Context, _ rowKey, cleared bool) error {
				if cleared {
					t.Fatal("clear=false 인데 cleared 로 왔다")
				}
				return c.err
			}
			r.cycle(t)
			if rec := r.log.last(t, "janitor_cycle_failing"); rec.attrs["value"] != "1" || rec.attrs["stage"] != "commit" {
				t.Errorf("cycle_failing = %v, want value=1 stage=commit", rec.attrs)
			}
			if len(r.j.debt) != 0 {
				t.Errorf("ROLLBACK 오류인데 부채 = %+v, want 0", r.j.debt)
			}
		})
	}
	t.Run("StageAcquire", func(t *testing.T) {
		r := newRig(t, nil)
		r.row("s1", 1, false, true)
		r.ledger.lockHook = func(rowKey) (index.RowLockResult, error, bool) {
			return index.RowLockResult{Stage: index.StageAcquire}, errors.New("풀 닫힘"), true
		}
		r.cycle(t)
		if rec := r.log.last(t, "janitor_cycle_failing"); rec.attrs["reason"] != "acquire" {
			t.Errorf("reason = %q, want acquire", rec.attrs["reason"])
		}
	})
	t.Run("StageCommit Kept 뒤 stall 은 maybe 만", func(t *testing.T) {
		r := newRig(t, nil)
		r.row("s1", 1, false, true)
		r.fs.hook = func(string) (error, bool) { return errStall, true }
		r.ledger.commitHook = func(context.Context, rowKey, bool) error { return errors.New("ROLLBACK 실패") }
		r.cycle(t)
		if len(r.j.debt) != 1 || r.j.debt[0].kind != debtMaybe {
			t.Errorf("부채 = %+v, want maybe 1", r.j.debt)
		}
	})
}

// Run 은 첫 주기를 곧바로 돌고 ctx 가 끝나면 그 오류로 돌아온다.
func TestRunCyclesUntilCanceled(t *testing.T) {
	r := newRig(t, func(o *Options) { o.Interval = time.Millisecond })
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.j.Run(ctx) }()

	deadline := time.Now().Add(5 * time.Second)
	for len(r.log.all("janitor_cycle_summary")) < 3 {
		if time.Now().After(deadline) {
			t.Fatal("Run 이 주기를 돌지 않는다")
		}
		time.Sleep(time.Millisecond)
	}
	cancel()
	if err := <-done; !errors.Is(err, context.Canceled) {
		t.Errorf("Run = %v, want context.Canceled", err)
	}
}

// 옵션 거부 — 사례마다 다른 조건은 모두 유효하게 두고 하나만 깨뜨린다(t2 r1 Q5). 유효한 root · ledger
// 가 공통 전제라서 개별 검사 하나를 지우면 그 사례가 FAIL 한다. nil 의존성은 별도 사례다.
func TestNewRejectsUnusableOptions(t *testing.T) {
	root, err := os.OpenRoot(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { root.Close() })
	ledger := newFakeLedger(&opLog{})
	base := Options{Root: testRoot, Interval: time.Second, Batch: 1, MaxPerCycle: 1, FSOpTimeout: time.Second, Log: slog.New(newLogSink(slog.LevelError))}
	if _, err := New(base, ledger, root); err != nil {
		t.Fatalf("유효한 기준 옵션을 거부했다: %v", err)
	}
	type deps struct {
		ledger Ledger
		root   *os.Root
	}
	bad := map[string]func(*Options, *deps){
		"Interval 0":      func(o *Options, _ *deps) { o.Interval = 0 },
		"Interval 음수":     func(o *Options, _ *deps) { o.Interval = -time.Second },
		"FSOpTimeout 0":   func(o *Options, _ *deps) { o.FSOpTimeout = 0 },
		"Batch 0":         func(o *Options, _ *deps) { o.Batch = 0 },
		"MaxPerCycle 0":   func(o *Options, _ *deps) { o.Batch, o.MaxPerCycle = 0, 0 },
		"Batch > Max":     func(o *Options, _ *deps) { o.Batch = 2 },
		"Log nil":         func(o *Options, _ *deps) { o.Log = nil },
		"ledger nil(의존성)": func(_ *Options, d *deps) { d.ledger = nil },
		"root nil(의존성)":   func(_ *Options, d *deps) { d.root = nil },
	}
	for name, mut := range bad {
		o, d := base, deps{ledger: ledger, root: root}
		mut(&o, &d)
		if _, err := New(o, d.ledger, d.root); err == nil {
			t.Errorf("%s: New 가 거부하지 않았다", name)
		}
	}
}
