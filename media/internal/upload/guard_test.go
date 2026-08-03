package upload

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"testing"
	"time"
)

func testOptions() Options {
	opt := DefaultOptions(nil, "/recordings")
	return opt
}

func newTestGates(clk *fakeClock) *gates {
	return newGates(testOptions(), clk.now)
}

func newTestBreaker(t *testing.T, clk *fakeClock, cap *logCapture, max int) *breaker {
	t.Helper()
	opt := testOptions()
	opt.CircuitMax = max
	return newBreaker(opt, cap.logger(), clk.now)
}

// ⑥ — 백오프는 min(SweepEvery·2^failures, BackoffMax) 로 벌어진다.
// 등록 때 failures 를 먼저 올리고 그 값으로 계산한다 — 1회차가 SweepEvery 그대로면
// mark_failed_deferred 의 "1회차 고정"과 구분이 사라진다.
func TestBackoffGrowsExponentiallyUpToMax(t *testing.T) {
	clk := newFakeClock()
	g := newTestGates(clk)
	opt := testOptions()
	k := targetKey{"s", 1}

	want := []time.Duration{
		2 * opt.SweepEvery,  // failures 1
		4 * opt.SweepEvery,  // 2
		8 * opt.SweepEvery,  // 3
		16 * opt.SweepEvery, // 4
		32 * opt.SweepEvery, // 5
		64 * opt.SweepEvery, // 6 = 32m > 30m → 상한
	}
	for i, w := range want {
		base := clk.now()
		g.registerFailure(k)
		nextAt, blocked := g.backoffBlocked(k)
		if !blocked {
			t.Fatalf("%d회차: 백오프가 걸리지 않았다", i+1)
		}
		if w > opt.BackoffMax {
			w = opt.BackoffMax
		}
		if got := nextAt.Sub(base); got != w {
			t.Errorf("%d회차 대기 = %v, want %v", i+1, got, w)
		}
		clk.advance(w) // 만료시켜 다음 회차로
	}

	// 마킹까지 성공하면 해제된다 — PUT 만 성공하고 마킹이 실패한 행은 해제하지 않는다(결정 6‴).
	g.clearBackoff(k)
	if _, blocked := g.backoffBlocked(k); blocked {
		t.Error("clearBackoff 후에도 백오프가 걸려 있다")
	}
}

// ㉒ — mark_failed_deferred 는 지수로 밀지 않는다.
// (i) deferred_streak 1→10 단조 증가 (ii) 임계 초과 구간에서도 간격 SweepEvery 고정
// (iii) failures 0 불변. 임계 초과는 새 로그 없이 이 필드로만 드러난다(N-3 관측안).
func TestDeferredBackoffStaysAtOneRoundAndCountsStreak(t *testing.T) {
	clk := newFakeClock()
	g := newTestGates(clk)
	opt := testOptions()
	k := targetKey{"s", 1}

	for i := 1; i <= 10; i++ {
		base := clk.now()
		streak := g.registerDeferred(k)
		if streak != i {
			t.Errorf("%d회차 deferred_streak = %d, want %d", i, streak, i)
		}
		nextAt, blocked := g.backoffBlocked(k)
		if !blocked {
			t.Fatalf("%d회차: 백오프가 걸리지 않았다", i)
		}
		if got := nextAt.Sub(base); got != opt.SweepEvery {
			t.Errorf("%d회차 대기 = %v, want %v (1회차 고정)", i, got, opt.SweepEvery)
		}
		if f := g.failuresOf(k); f != 0 {
			t.Errorf("%d회차 failures = %d, want 0 (불변)", i, f)
		}
		clk.advance(opt.SweepEvery)
	}
}

// 격리는 프로세스 수명 동안 되돌릴 수 없다. 그래서 등록 지점이 좁아야 한다.
func TestQuarantineIsSticky(t *testing.T) {
	clk := newFakeClock()
	g := newTestGates(clk)
	k := targetKey{"s", 1}

	if g.quarantined(k) {
		t.Fatal("처음부터 격리돼 있다")
	}
	g.quarantine(k)
	if !g.quarantined(k) {
		t.Fatal("격리가 등록되지 않았다")
	}
	if g.quarantined(targetKey{"s", 2}) {
		t.Error("다른 행까지 격리됐다")
	}
}

// in-flight 는 같은 행을 두 경로가 동시에 올리는 것을 막는다.
func TestInflightIsExclusiveUntilReleased(t *testing.T) {
	clk := newFakeClock()
	g := newTestGates(clk)
	k := targetKey{"s", 1}

	if !g.acquireInflight(k) {
		t.Fatal("첫 획득이 실패했다")
	}
	if g.acquireInflight(k) {
		t.Fatal("두 번째 획득이 성공했다 — 중복 업로드가 열린다")
	}
	g.releaseInflight(k)
	if !g.acquireInflight(k) {
		t.Fatal("해제 후 재획득이 실패했다")
	}
}

// ⑫ — 브레이커 전이표 15행 전수.
func TestBreakerTransitionTable(t *testing.T) {
	// 1행 — CircuitMax == 0 이면 Closed 고정, streak 미집계.
	t.Run("1_CircuitMax_0_은_전체_무효화", func(t *testing.T) {
		clk, cap := newFakeClock(), newLogCapture()
		b := newTestBreaker(t, clk, cap, 0)
		for i := 0; i < 10; i++ {
			b.record(outcomeHard, errors.New("boom"))
		}
		if got := b.current(); got != circuitClosed {
			t.Errorf("state = %v, want Closed", got)
		}
		if b.streak != 0 {
			t.Errorf("streak = %d, want 0 (미집계)", b.streak)
		}
		if n := cap.count("circuit_open"); n != 0 {
			t.Errorf("circuit_open = %d건, want 0", n)
		}
		if !b.allowEnqueue() {
			t.Error("접수가 거부됐다")
		}
	})

	// 2·3·5행 — Closed 에서 접수 허용, success·soft 는 streak 리셋, neutral·shutdown 은 불변.
	t.Run("2_3_5_Closed_의_접수와_streak", func(t *testing.T) {
		clk, cap := newFakeClock(), newLogCapture()
		b := newTestBreaker(t, clk, cap, 3)
		if !b.allowEnqueue() {
			t.Error("2행: Closed 인데 접수가 거부됐다")
		}
		b.record(outcomeHard, errors.New("boom"))
		if b.streak != 1 {
			t.Fatalf("streak = %d, want 1", b.streak)
		}
		for _, o := range []outcome{outcomeNeutral, outcomeShutdown} {
			b.record(o, nil)
			if b.streak != 1 {
				t.Errorf("5행: %v 뒤 streak = %d, want 1 (불변)", o, b.streak)
			}
		}
		b.record(outcomeSoft, errors.New("boom"))
		if b.streak != 0 {
			t.Errorf("3행: soft 뒤 streak = %d, want 0", b.streak)
		}
		b.record(outcomeHard, errors.New("boom"))
		b.record(outcomeSuccess, nil)
		if b.streak != 0 {
			t.Errorf("3행: success 뒤 streak = %d, want 0", b.streak)
		}
	})

	// 4행 — hard 가 CircuitMax 만큼 이어지면 Open + circuit_open ERROR.
	t.Run("4_hard_연속_임계에서_Open", func(t *testing.T) {
		clk, cap := newFakeClock(), newLogCapture()
		b := newTestBreaker(t, clk, cap, 3)
		for i := 0; i < 2; i++ {
			b.record(outcomeHard, errors.New("boom"))
			if b.current() != circuitClosed {
				t.Fatalf("%d회에서 벌써 열렸다", i+1)
			}
		}
		b.record(outcomeHard, errors.New("boom"))
		if b.current() != circuitOpen {
			t.Fatal("임계에서 열리지 않았다")
		}
		rec := cap.one(t, "circuit_open")
		if rec.level != slog.LevelError {
			t.Errorf("circuit_open 레벨 = %v, want ERROR", rec.level)
		}
		for _, f := range []string{"streak", "limit", "cooldown", "last_err"} {
			if _, ok := rec.attrs[f]; !ok {
				t.Errorf("circuit_open 에 %s 필드가 없다 (%v)", f, rec.attrs)
			}
		}
	})

	// 6·7행 — Open 이고 쿨다운 미경과면 접수도 작업도 거부한다.
	t.Run("6_7_Open_쿨다운_미경과", func(t *testing.T) {
		clk, cap := newFakeClock(), newLogCapture()
		b := newTestBreaker(t, clk, cap, 1)
		b.record(outcomeHard, errors.New("boom"))
		cap.reset()

		clk.advance(testOptions().CircuitCooldown - time.Second)
		if b.allowEnqueue() {
			t.Error("6행: Open 인데 접수가 허용됐다")
		}
		if b.allowWork(targetKey{"s", 1}) {
			t.Error("7행: Open 인데 작업이 허용됐다")
		}
		if n := cap.count("upload_skipped_circuit"); n != 1 {
			t.Errorf("7행: upload_skipped_circuit = %d건, want 1", n)
		}
	})

	// 8행 — CircuitBlockLog 마다 요약 1건. epoch 첫 건만 WARN, 이후 INFO.
	t.Run("8_circuit_blocking_요약", func(t *testing.T) {
		clk, cap := newFakeClock(), newLogCapture()
		b := newTestBreaker(t, clk, cap, 1)
		opt := testOptions()
		b.record(outcomeHard, errors.New("boom"))
		cap.reset()

		b.allowEnqueue() // 차단 1건 — 아직 요약 주기 전이다
		if n := cap.count("circuit_blocking"); n != 0 {
			t.Fatalf("주기 전에 요약이 나왔다 (%d건)", n)
		}
		clk.advance(opt.CircuitBlockLog)
		b.allowEnqueue()
		clk.advance(opt.CircuitBlockLog)
		b.allowEnqueue()

		got := cap.find("circuit_blocking")
		if len(got) != 2 {
			t.Fatalf("circuit_blocking = %d건, want 2 (%s)", len(got), cap.dump())
		}
		if got[0].level != slog.LevelWarn {
			t.Errorf("epoch 첫 요약 레벨 = %v, want WARN", got[0].level)
		}
		if got[1].level != slog.LevelInfo {
			t.Errorf("이후 요약 레벨 = %v, want INFO", got[1].level)
		}
		for _, f := range []string{"blocked_total", "epoch", "since", "cooldown_left"} {
			if _, ok := got[0].attrs[f]; !ok {
				t.Errorf("circuit_blocking 에 %s 필드가 없다 (%v)", f, got[0].attrs)
			}
		}
	})

	// 9·10·11·12행 — lazy 쿨다운 경과로 HalfOpen, probe 는 정확히 1건만 통과.
	t.Run("9_10_11_12_HalfOpen_의_probe_1건", func(t *testing.T) {
		clk, cap := newFakeClock(), newLogCapture()
		b := newTestBreaker(t, clk, cap, 1)
		b.record(outcomeHard, errors.New("boom"))
		cap.reset()

		clk.advance(testOptions().CircuitCooldown)
		if got := b.current(); got != circuitHalfOpen { // 9행 — lazy 평가
			t.Fatalf("state = %v, want HalfOpen", got)
		}
		if n := cap.count("circuit_half_open"); n != 1 {
			t.Errorf("circuit_half_open = %d건, want 1", n)
		}
		if !b.allowEnqueue() { // 10행
			t.Error("10행: HalfOpen 인데 접수가 거부됐다")
		}
		if !b.allowWork(targetKey{"s", 1}) { // 11행
			t.Error("11행: 첫 probe 가 거부됐다")
		}
		if b.allowWork(targetKey{"s", 2}) { // 12행
			t.Error("12행: 두 번째 작업이 probe 를 또 소비했다")
		}
	})

	// 13행 — probe 성공이면 Closed + circuit_closed INFO.
	t.Run("13_probe_성공은_Closed", func(t *testing.T) {
		clk, cap := newFakeClock(), newLogCapture()
		b := halfOpenBreaker(t, clk, cap)
		b.allowWork(targetKey{"s", 1})
		b.record(outcomeSuccess, nil)
		if got := b.current(); got != circuitClosed {
			t.Fatalf("state = %v, want Closed", got)
		}
		if b.streak != 0 {
			t.Errorf("streak = %d, want 0", b.streak)
		}
		if n := cap.count("circuit_closed"); n != 1 {
			t.Errorf("circuit_closed = %d건, want 1", n)
		}
	})

	// 14행 — probe 가 hard 든 soft 든 보수적으로 다시 Open.
	for _, o := range []outcome{outcomeHard, outcomeSoft} {
		t.Run("14_probe_"+o.String()+"_는_다시_Open", func(t *testing.T) {
			clk, cap := newFakeClock(), newLogCapture()
			b := halfOpenBreaker(t, clk, cap)
			b.allowWork(targetKey{"s", 1})
			openedBefore := b.openedAt
			clk.advance(time.Minute)
			b.record(o, errors.New("boom"))
			if got := b.current(); got != circuitOpen {
				t.Fatalf("state = %v, want Open", got)
			}
			if !b.openedAt.After(openedBefore) {
				t.Error("openedAt 이 갱신되지 않았다 — 쿨다운이 즉시 만료된다")
			}
		})
	}

	// 15행 — neutral·shutdown 은 판정 재료가 아니다. HalfOpen 유지 + probe 복귀(고착 방지).
	for _, o := range []outcome{outcomeNeutral, outcomeShutdown} {
		t.Run("15_probe_"+o.String()+"_는_HalfOpen_유지하고_probe_복귀", func(t *testing.T) {
			clk, cap := newFakeClock(), newLogCapture()
			b := halfOpenBreaker(t, clk, cap)
			if !b.allowWork(targetKey{"s", 1}) {
				t.Fatal("첫 probe 가 거부됐다")
			}
			b.record(o, nil)
			if got := b.current(); got != circuitHalfOpen {
				t.Fatalf("state = %v, want HalfOpen", got)
			}
			if !b.allowWork(targetKey{"s", 2}) {
				t.Fatal("probeUsed 가 복귀하지 않았다 — 재판정 기회가 영영 없어 고착한다")
			}
		})
	}
}

// halfOpenBreaker 는 Open → 쿨다운 경과 → HalfOpen 까지 밀어 둔 브레이커다.
func halfOpenBreaker(t *testing.T, clk *fakeClock, cap *logCapture) *breaker {
	t.Helper()
	b := newTestBreaker(t, clk, cap, 1)
	b.record(outcomeHard, errors.New("boom"))
	clk.advance(testOptions().CircuitCooldown)
	if got := b.current(); got != circuitHalfOpen {
		t.Fatalf("준비 실패: state = %v, want HalfOpen", got)
	}
	cap.reset()
	return b
}

// OpenCircuit 은 기동 시 자격증명이 없을 때 호출한다. 실패 없이 곧바로 열려야 한다.
func TestOpenCircuitOpensImmediately(t *testing.T) {
	clk, cap := newFakeClock(), newLogCapture()
	b := newTestBreaker(t, clk, cap, 3)
	b.open("credentials_unavailable")
	if got := b.current(); got != circuitOpen {
		t.Fatalf("state = %v, want Open", got)
	}
	if n := cap.count("circuit_open"); n != 1 {
		t.Errorf("circuit_open = %d건, want 1", n)
	}
}

// ⑳ — in-flight 는 큐 삽입 전에 등록하고, QueueFull 이면 반드시 되돌린다(rev6 N1).
//
// 되돌리지 않으면 그 행은 아무 작업도 없는데 in-flight 로 남아 이후 모든 접수가
// Rejected 가 된다 — 커서는 정상 전진하므로 조용히 영구 미업로드가 된다.
func TestEnqueueRollsBackInflightOnQueueFull(t *testing.T) {
	block := make(chan struct{})
	put := &fakePutter{fn: func(ctx context.Context, _ string, _ io.Reader, _ int64) error {
		select {
		case <-block:
		case <-ctx.Done():
			return ctx.Err()
		}
		return nil
	}}
	u, cap, dir := newTestUploader(t, &fakeUploadStore{}, put, func(o *Options) {
		o.QueueLen = 1
	})
	u.Start(context.Background())
	defer func() { close(block); u.Shutdown() }()

	// 워커가 1건을 잡고, 큐(용량 1)가 1건으로 찬다.
	for seq := int64(0); seq < 2; seq++ {
		p := writeSegment(t, dir, "demo", fmt.Sprintf("busy%d.mp4", seq), 16)
		if got := u.enqueue(newTarget("demo", seq, p, 16, false), OriginSweep); got != EnqueueAdmitted {
			t.Fatalf("사전 접수 %d = %v, want Admitted", seq, got)
		}
	}
	waitFor(t, func() bool { return len(put.putCalls()) == 1 }, "워커가 첫 건을 집을 때까지")

	path := writeSegment(t, dir, "demo", "full.mp4", 16)
	target := newTarget("demo", 9, path, 16, false)
	if got := u.enqueue(target, OriginSweep); got != EnqueueQueueFull {
		t.Fatalf("포화 접수 = %v, want EnqueueQueueFull", got)
	}
	if n := cap.count("upload_queue_full"); n != 1 {
		t.Errorf("upload_queue_full = %d건, want 1", n)
	}
	if !u.gate.acquireInflight(targetKey{"demo", 9}) {
		t.Fatal("QueueFull 인데 in-flight 가 남아 있다 — 이 행은 영영 다시 접수되지 않는다")
	}
}
