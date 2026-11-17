package main

// 발행기 준비 재시도의 수명 · 신호(sgd 설계 2-2 · 4-6 · 6절 — J8′ · J11′). DB 없이 준비 걸음을 가짜로 채워 재시도
// 고루틴의 정지 순서와 기동 · 회복 줄을 본다.

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/broadcast"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/keyresolve"
)

// degradeLC 는 발행 의도가 켜진 설정이다(주제 · 모든 스트림 — resolver 는 부르는 쪽이 따로 준다).
var degradeLC = config.LifecycleOptions{TopicARN: lifecycleTopic, TopicRegion: "ap-northeast-2", Streams: []string{"*"}}

// errStepFake 는 가짜 준비 걸음의 실패다.
var errStepFake = errors.New("시험 — 준비 걸음 실패")

// okResolve 는 모든 토큰을 사용자 42 로 푸는 4C 대역이다.
func okResolve(context.Context, string) (keyresolve.Verdict, error) {
	return keyresolve.Verdict{Valid: true, UserID: 42}, nil
}

// lines 는 lockedLog(endsignal_wire_test.go)에 지금까지 쓴 msg 줄들이다.
func (l *lockedLog) lines(t *testing.T, msg string) []map[string]any {
	t.Helper()
	l.mu.Lock()
	text := l.buf.String()
	l.mu.Unlock()
	return logLines(t, bytes.NewBufferString(text), msg)
}

func (l *lockedLog) String() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.buf.String()
}

// newLockedLog 는 level 이상만 남기는 JSON 로거와 그 버퍼다.
func newLockedLog(level slog.Level) (*slog.Logger, *lockedLog) {
	l := &lockedLog{}
	return slog.New(slog.NewJSONHandler(l, &slog.HandlerOptions{Level: level})), l
}

// waitUntil 은 cond 가 참이 되기를 limit 까지 기다린다.
func waitUntil(limit time.Duration, cond func() bool) bool {
	for deadline := time.Now().Add(limit); time.Now().Before(deadline); time.Sleep(5 * time.Millisecond) {
		if cond() {
			return true
		}
	}
	return cond()
}

// waitClosed 는 ch 가 limit 안에 닫히는지다.
func waitClosed(ch <-chan struct{}, limit time.Duration) bool {
	select {
	case <-ch:
		return true
	case <-time.After(limit):
		return false
	}
}

// fakePrep 는 준비 걸음 셋의 가짜다. privileges 는 privFail 이 참인 동안 실패하고, runPublisher 는 정지 함수 호출 수를
// 센다. 걸음마다 호출 수와 동시 진입 수를 센다.
type fakePrep struct {
	calls, inFlight, maxInFlight, runs, stops atomic.Int32
	privileges                                func(ctx context.Context, n int32) ([]string, error)
	runPublisher                              func(ctx context.Context) error
}

func (f *fakePrep) steps(every time.Duration) publisherSteps {
	return publisherSteps{
		privileges: func(ctx context.Context) ([]string, error) {
			n := f.calls.Add(1)
			cur := f.inFlight.Add(1)
			defer f.inFlight.Add(-1)
			for m := f.maxInFlight.Load(); cur > m && !f.maxInFlight.CompareAndSwap(m, cur); m = f.maxInFlight.Load() {
			}
			if f.privileges == nil {
				return nil, nil
			}
			return f.privileges(ctx, n)
		},
		snsPublish: func(context.Context) (func(context.Context, broadcast.Message) error, error) {
			return func(context.Context, broadcast.Message) error { return nil }, nil
		},
		runPublisher: func(ctx context.Context, _ broadcast.PublisherOptions) (func(), error) {
			f.runs.Add(1)
			if f.runPublisher != nil {
				if err := f.runPublisher(ctx); err != nil {
					return nil, err
				}
			}
			return func() { f.stops.Add(1) }, nil
		},
		every: every,
	}
}

// stopWithin 은 stop 을 부르고 limit 안에 돌아오는지다.
func stopWithin(t *testing.T, stop func(), limit time.Duration) {
	t.Helper()
	done := make(chan struct{})
	go func() { stop(); close(done) }()
	select {
	case <-done:
	case <-time.After(limit):
		t.Fatal("정지 함수가 돌아오지 않았다")
	}
}

// requireNoRecoveryLines 는 회복 줄(lifecycle_started · lifecycle_degraded)이 하나도 없는지다.
func requireNoRecoveryLines(t *testing.T, log *lockedLog) {
	t.Helper()
	if s, d := log.lines(t, "lifecycle_started"), log.lines(t, "lifecycle_degraded"); len(s) != 0 || len(d) != 0 {
		t.Errorf("정지 뒤 회복 줄 started %v · degraded %v, want 없음", s, d)
	}
}

// J8′ — 재시도 고루틴의 정지 순서(P3 · P5 · P6). 정지 함수가 돌아온 뒤에는 걸음 호출이 더 없고, 회복해 띄운 발행기는
// 정확히 한 번 멈추며, 정지와 겹친 회복은 줄을 내지 않는다. 시도는 겹치지 않는다.
// 잡는 결함: 정지가 재시도를 기다리지 않음(V7) · 회복 발행기 정지 미호출(V8) · 시도 병렬(V9) · 성공 뒤 ctx 확인 제거(V19).
func TestRetryPublisherStopOrderJ8(t *testing.T) {
	t.Run("시도_중_정지", testRetryStopDuringAttempt)
	t.Run("첫_재시도_전_정지", testRetryStopBeforeFirstAttempt)
	t.Run("회복_직후_정지", testRetryStopAfterRecovery)
	t.Run("회복과_정지_겹침", testRetryStopOverlapsRecovery)
	t.Run("시도_겹침_없음", testRetryAttemptsNeverOverlap)
}

func testRetryStopDuringAttempt(t *testing.T) {
	entered := make(chan struct{})
	var once sync.Once
	var returned atomic.Bool
	f := &fakePrep{privileges: func(ctx context.Context, _ int32) ([]string, error) {
		once.Do(func() { close(entered) })
		<-ctx.Done()
		time.Sleep(50 * time.Millisecond) // 끊긴 걸음이 늦게 돌아온다
		returned.Store(true)
		return nil, ctx.Err()
	}}
	log, buf := newLockedLog(slog.LevelInfo)
	stop := retryPublisher(t.Context(), degradeLC, f.steps(time.Millisecond), okResolve, log)
	if !waitClosed(entered, 5*time.Second) {
		stop()
		t.Fatal("재시도가 걸음에 들어가지 않았다")
	}
	stopWithin(t, stop, 5*time.Second)

	calls := f.calls.Load()
	time.Sleep(30 * time.Millisecond)
	if !returned.Load() || f.calls.Load() != calls || f.runs.Load() != 0 {
		t.Fatalf("걸음 귀환 %v · 정지 뒤 호출 %d → %d · 발행기 %d, want 참 · 같음 · 0",
			returned.Load(), calls, f.calls.Load(), f.runs.Load())
	}
	requireNoRecoveryLines(t, buf)
}

func testRetryStopBeforeFirstAttempt(t *testing.T) {
	f := &fakePrep{}
	log, buf := newLockedLog(slog.LevelInfo)
	stop := retryPublisher(t.Context(), degradeLC, f.steps(time.Hour), okResolve, log)
	stopWithin(t, stop, 5*time.Second)

	time.Sleep(30 * time.Millisecond)
	if n := f.calls.Load(); n != 0 {
		t.Fatalf("걸음 호출 %d, want 0 — 첫 재시도는 주기 뒤다", n)
	}
	requireNoRecoveryLines(t, buf)
}

func testRetryStopAfterRecovery(t *testing.T) {
	f := &fakePrep{}
	log, buf := newLockedLog(slog.LevelInfo)
	stop := retryPublisher(t.Context(), degradeLC, f.steps(time.Millisecond), okResolve, log)
	if !waitUntil(5*time.Second, func() bool { return len(buf.lines(t, "lifecycle_degraded")) > 0 }) {
		stop()
		t.Fatalf("회복하지 않았다: %s", buf)
	}
	stopWithin(t, stop, 5*time.Second)

	calls := f.calls.Load()
	time.Sleep(30 * time.Millisecond)
	if f.runs.Load() != 1 || f.stops.Load() != 1 || f.calls.Load() != calls {
		t.Fatalf("발행기 기동 %d · 정지 %d · 정지 뒤 호출 %d → %d, want 1 · 1 · 같음",
			f.runs.Load(), f.stops.Load(), calls, f.calls.Load())
	}
	started, degraded := buf.lines(t, "lifecycle_started"), buf.lines(t, "lifecycle_degraded")
	if len(started) != 1 || started[0]["attempt"] != float64(1) || len(degraded) != 1 ||
		degraded[0]["value"] != float64(0) || degraded[0]["reason"] != "recovered" {
		t.Fatalf("회복 줄 started %v · degraded %v, want attempt=1 · value=0 reason=recovered", started, degraded)
	}
}

func testRetryStopOverlapsRecovery(t *testing.T) {
	entered := make(chan struct{})
	var once sync.Once
	f := &fakePrep{runPublisher: func(ctx context.Context) error {
		once.Do(func() { close(entered) })
		<-ctx.Done() // 정지가 이 발행기 기동과 겹친다
		return nil
	}}
	log, buf := newLockedLog(slog.LevelInfo)
	stop := retryPublisher(t.Context(), degradeLC, f.steps(time.Millisecond), okResolve, log)
	if !waitClosed(entered, 5*time.Second) {
		stop()
		t.Fatal("재시도가 걸음에 들어가지 않았다")
	}
	stopWithin(t, stop, 5*time.Second)

	if f.runs.Load() != 1 || f.stops.Load() != 1 {
		t.Fatalf("발행기 기동 %d · 정지 %d, want 1 · 1 — 정지와 겹쳐 띄운 발행기도 멈춰야 한다", f.runs.Load(), f.stops.Load())
	}
	requireNoRecoveryLines(t, buf)
}

func testRetryAttemptsNeverOverlap(t *testing.T) {
	f := &fakePrep{privileges: func(context.Context, int32) ([]string, error) {
		time.Sleep(10 * time.Millisecond) // 주기(1ms)보다 긴 시도
		return nil, errStepFake
	}}
	log, buf := newLockedLog(slog.LevelInfo)
	stop := retryPublisher(t.Context(), degradeLC, f.steps(time.Millisecond), okResolve, log)
	ok := waitUntil(5*time.Second, func() bool { return f.calls.Load() >= 5 })
	stopWithin(t, stop, 5*time.Second)

	if !ok || f.maxInFlight.Load() != 1 {
		t.Fatalf("시도 %d · 동시 시도 최대 %d, want ≥5 · 1", f.calls.Load(), f.maxInFlight.Load())
	}
	// 정지에 끊긴 마지막 시도는 줄을 내지 않는다 — 줄 수는 시도 수 또는 하나 적다.
	warns, calls := buf.lines(t, "lifecycle_prepare_retry_failed"), int(f.calls.Load())
	if len(warns) < calls-1 || len(warns) > calls || warns[0]["level"] != "WARN" || warns[0]["reason"] != "privilege_check" ||
		warns[0]["attempt"] != float64(1) || warns[1]["attempt"] != float64(2) {
		t.Fatalf("재시도 실패 줄 %d 개(시도 %d) · 줄 %v", len(warns), calls, warns)
	}
	requireNoRecoveryLines(t, buf)
}

// gauge 는 ERROR 로그의 lifecycle_degraded 한 줄을 (value, reason) 으로 줄인 것이다.
type gauge struct {
	value  float64
	reason string
}

// gauges 는 ERROR 처리기에 남은 lifecycle_degraded 줄을 차례로 읽는다(등급이 ERROR 가 아니면 실패).
func gauges(t *testing.T, log *lockedLog) []gauge {
	t.Helper()
	var out []gauge
	for _, l := range log.lines(t, "lifecycle_degraded") {
		if l["level"] != "ERROR" {
			t.Errorf("lifecycle_degraded 등급 %v, want ERROR", l["level"])
		}
		v, _ := l["value"].(float64)
		r, _ := l["reason"].(string)
		out = append(out, gauge{v, r})
	}
	return out
}

// J11′ — 운영 판정은 LOG_LEVEL=error 의 lifecycle_degraded 줄만 읽는다(설계 6절 운영 판정 1 · 2). 처리기 하나를 기동 둘이
// 나눠 쓴다: 기동 1(privilege_check 실패 → 회복 전 정지) → 기동 2(준비 성공)는 [1 privilege_check] → [0 ready] 이고
// 기동 줄 수 = 기동 수 · 마지막 줄 value=0 이다. 회복 갈래는 [1] → [0 recovered]. 설정으로 꺼진 기동은 이 줄이 없다.
// 잡는 결함: 기동 줄 INFO(V11) · ready 줄 제거(V18) · reason 글자 오기.
func TestLifecycleDegradedContractAtErrorLevelJ11(t *testing.T) {
	t.Run("기동_둘", testGaugeAcrossTwoBoots)
	t.Run("회복", testGaugeRecovery)
	t.Run("사유_넷", testGaugeReasons)
	t.Run("설정_꺼짐", testGaugeAbsentWhenOff)
}

func testGaugeAcrossTwoBoots(t *testing.T) {
	log, buf := newLockedLog(slog.LevelError)
	down := &fakePrep{privileges: func(context.Context, int32) ([]string, error) { return nil, errStepFake }}
	gate, stop := startLifecycleWith(t.Context(), degradeLC, okResolve, down.steps(time.Hour), log)
	if !gate.Enabled() {
		t.Error("강등 기동의 게이트가 꺼졌다 — 의도 게이트는 준비와 무관하게 켜진다(안 Ⅰ)")
	}
	stop()
	up := &fakePrep{}
	_, stop = startLifecycleWith(t.Context(), degradeLC, okResolve, up.steps(time.Hour), log)
	stop()

	got := gauges(t, buf)
	want := []gauge{{1, "privilege_check"}, {0, "ready"}}
	if len(got) != len(want) || got[0] != want[0] || got[1] != want[1] {
		t.Fatalf("ERROR lifecycle_degraded %v, want %v — 기동 줄 수 = 기동 수 · 마지막 value=0 · 전체 %s", got, want, buf)
	}
	if up.runs.Load() != 1 || up.stops.Load() != 1 {
		t.Errorf("준비 성공 기동의 발행기 기동 %d · 정지 %d, want 1 · 1", up.runs.Load(), up.stops.Load())
	}
}

func testGaugeRecovery(t *testing.T) {
	log, buf := newLockedLog(slog.LevelError)
	f := &fakePrep{privileges: func(_ context.Context, n int32) ([]string, error) {
		if n == 1 {
			return nil, errStepFake
		}
		return nil, nil
	}}
	_, stop := startLifecycleWith(t.Context(), degradeLC, okResolve, f.steps(time.Millisecond), log)
	ok := waitUntil(5*time.Second, func() bool { return len(buf.lines(t, "lifecycle_degraded")) >= 2 })
	stop()

	got := gauges(t, buf)
	if !ok || len(got) != 2 || got[0] != (gauge{1, "privilege_check"}) || got[1] != (gauge{0, "recovered"}) {
		t.Fatalf("ERROR lifecycle_degraded %v, want [1 privilege_check] → [0 recovered] · 전체 %s", got, buf)
	}
}

func testGaugeReasons(t *testing.T) {
	for _, c := range []struct {
		reason, field, has string
		steps              func(*fakePrep) publisherSteps
	}{
		{"privilege_check", "err", errStepFake.Error(), func(f *fakePrep) publisherSteps {
			f.privileges = func(context.Context, int32) ([]string, error) { return nil, errStepFake }
			return f.steps(time.Hour)
		}},
		{"privilege", "missing", "broadcast_lifecycle_outbox:INSERT", func(f *fakePrep) publisherSteps {
			f.privileges = func(context.Context, int32) ([]string, error) {
				return []string{"broadcast_lifecycle_outbox:INSERT"}, nil
			}
			return f.steps(time.Hour)
		}},
		{"sns_client", "err", errStepFake.Error(), func(f *fakePrep) publisherSteps {
			s := f.steps(time.Hour)
			s.snsPublish = func(context.Context) (func(context.Context, broadcast.Message) error, error) {
				return nil, errStepFake
			}
			return s
		}},
		{"assembly", "err", errStepFake.Error(), func(f *fakePrep) publisherSteps {
			f.runPublisher = func(context.Context) error { return errStepFake }
			return f.steps(time.Hour)
		}},
	} {
		log, buf := newLockedLog(slog.LevelError)
		f := &fakePrep{}
		gate, stop := startLifecycleWith(t.Context(), degradeLC, okResolve, c.steps(f), log)
		stop()
		lines := buf.lines(t, "lifecycle_degraded")
		if len(lines) == 0 {
			t.Errorf("%s: lifecycle_degraded 줄이 없다 — 전체 %s", c.reason, buf)
			continue
		}
		field, _ := lines[0][c.field].(string)
		if !gate.Enabled() || len(lines) != 1 || lines[0]["value"] != float64(1) || lines[0]["reason"] != c.reason ||
			!strings.Contains(field, c.has) || len(buf.lines(t, "lifecycle_disabled")) != 0 {
			t.Errorf("%s: 게이트 켬 %v · lifecycle_degraded %v, want value=1 reason=%s %s∋%q · disabled 0",
				c.reason, gate.Enabled(), lines, c.reason, c.field, c.has)
		}
	}
}

func testGaugeAbsentWhenOff(t *testing.T) {
	log, buf := newLockedLog(slog.LevelError)
	for _, cfg := range []config.Config{lifecycleCfg("", "*"), lifecycleCfg(lifecycleTopic), lifecycleCfg(lifecycleTopic, "*")} {
		_, stop := startLifecycle(t.Context(), cfg, nil, nil, log) // 셋째는 resolver 가 없다(no_resolver)
		stop()
	}
	if got := gauges(t, buf); len(got) != 0 {
		t.Fatalf("설정으로 꺼진 기동의 lifecycle_degraded %v, want 없음", got)
	}
	if lines := buf.lines(t, "lifecycle_disabled"); len(lines) != 1 || lines[0]["reason"] != "no_resolver" {
		t.Fatalf("ERROR lifecycle_disabled %v, want no_resolver 한 줄", lines)
	}
}
