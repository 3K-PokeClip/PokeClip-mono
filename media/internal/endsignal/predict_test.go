package endsignal

// 결함 예측표(설계 7절) 행별 시험. 행 번호를 시험 이름 뒤에 단다.

import (
	"context"
	"net/http"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/control"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/keyresolve"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
)

// P1 — resolve 는 트랜잭션 밖 · 앞이다. resolve 가 도는 동안 Apply(풀 연결)는 한 번도 불리지 않는다.
func TestResolveRunsBeforeApplyP1(t *testing.T) {
	f := newFixture(t, nil)
	var appliedDuringResolve atomic.Int32
	f.resolve = func(ctx context.Context, _ string) (keyresolve.Verdict, error) {
		time.Sleep(200 * time.Millisecond)
		appliedDuringResolve.Store(f.applyCalls.Load())
		return validVerdict(t, testPassphrase), nil
	}
	if resp := f.postOK(testToken); resp.status != http.StatusAccepted {
		t.Fatalf("상태 = %d, want 202", resp.status)
	}
	if n := appliedDuringResolve.Load(); n != 0 {
		t.Fatalf("resolve 중 Apply 호출 = %d, want 0", n)
	}
	if f.applyCalls.Load() != 1 {
		t.Fatalf("Apply 호출 = %d, want 1", f.applyCalls.Load())
	}
}

// P2 — 서로 다른 인증 키 둘이 겹치면 동시 1 세마포어가 둘째를 기다리지 않고 503 busy 로 돌린다. Apply 동시 실행은 최대 1.
func TestConcurrentSignalsBoundedToOneP2(t *testing.T) {
	f := newFixture(t, nil)
	entered, release := make(chan struct{}, 2), make(chan struct{})
	var inApply, maxInApply atomic.Int32
	f.apply = func(context.Context, session.IntentionalEnd) (session.IntentionalResult, error) {
		n := inApply.Add(1)
		for {
			m := maxInApply.Load()
			if n <= m || maxInApply.CompareAndSwap(m, n) {
				break
			}
		}
		entered <- struct{}{}
		<-release
		inApply.Add(-1)
		return session.IntentionalResult{Effect: "marked"}, nil
	}
	first := make(chan response, 1)
	go func() { first <- f.postOK(testToken) }()
	<-entered
	second := make(chan response, 1)
	go func() { second <- f.postOK(testTokenB) }()
	var got response
	select {
	case got = <-second:
	case <-entered:
		close(release)
		t.Fatalf("둘째 요청도 Apply 에 들어갔다 — 동시 2")
	case <-time.After(3 * time.Second):
		close(release)
		t.Fatalf("둘째 요청이 돌아오지 않았다")
	}
	close(release)
	if got.status != http.StatusServiceUnavailable {
		t.Errorf("둘째 상태 = %d, want 503", got.status)
	}
	if r := <-first; r.status != http.StatusAccepted {
		t.Errorf("첫째 상태 = %d, want 202", r.status)
	}
	if m := maxInApply.Load(); m != 1 {
		t.Errorf("Apply 최대 동시 = %d, want 1", m)
	}
	busy := 0
	for _, l := range f.log.lines(t) {
		if l.Msg == "end_signal" && l.Reason == "busy" {
			busy++
		}
	}
	if busy != 1 {
		t.Errorf("busy 줄 = %d, want 1", busy)
	}
}

// waitCtx 는 ctx 가 끝나거나 d 가 지나면 돌아온다.
func waitCtx(ctx context.Context, d time.Duration) error {
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-time.After(d):
		return nil
	}
}

// P3 — 4C 가 매달려도 resolve 시한 1s 에 503 으로 돌아온다.
func TestResolveTimeoutP3(t *testing.T) {
	f := newFixture(t, nil)
	f.resolve = func(ctx context.Context, _ string) (keyresolve.Verdict, error) {
		if err := waitCtx(ctx, 3*time.Second); err != nil {
			return keyresolve.Verdict{}, &keyresolve.UndecidedError{Kind: "timeout"}
		}
		return validVerdict(t, testPassphrase), nil
	}
	start := time.Now()
	resp := f.postOK(testToken)
	if el := time.Since(start); el > 1100*time.Millisecond {
		t.Fatalf("응답까지 %v, want ≤ 1.1s", el)
	}
	if resp.status != http.StatusServiceUnavailable {
		t.Fatalf("상태 = %d, want 503", resp.status)
	}
}

// P4 — Apply(풀 대기 · 잠금 대기)가 늦어도 Apply 시한 1.5s 에 503 apply_timeout 으로 돌아온다.
func TestApplyTimeoutP4(t *testing.T) {
	f := newFixture(t, nil)
	f.apply = func(ctx context.Context, _ session.IntentionalEnd) (session.IntentionalResult, error) {
		if err := waitCtx(ctx, 4*time.Second); err != nil {
			return session.IntentionalResult{}, err
		}
		return session.IntentionalResult{Effect: "marked"}, nil
	}
	start := time.Now()
	resp := f.postOK(testToken)
	if el := time.Since(start); el > 1600*time.Millisecond {
		t.Fatalf("응답까지 %v, want ≤ 1.6s", el)
	}
	if resp.status != http.StatusServiceUnavailable {
		t.Fatalf("상태 = %d, want 503", resp.status)
	}
	if l := f.lastEndSignal(); l.Reason != "apply_timeout" || l.Level != "WARN" {
		t.Fatalf("end_signal 줄 = %+v", l)
	}
}

// P5ⓐ — Close 는 진행 중 처리기가 끝날 때까지 돌아오지 않는다. 가짜 Apply 는 취소를 본 뒤 release 에서 멈춘다.
func TestCloseWaitsForInflightHandlerP5(t *testing.T) {
	f := newFixture(t, nil)
	entered, release := make(chan struct{}), make(chan struct{})
	f.apply = func(ctx context.Context, _ session.IntentionalEnd) (session.IntentionalResult, error) {
		close(entered)
		<-ctx.Done()
		<-release
		return session.IntentionalResult{}, ctx.Err()
	}
	got := make(chan response, 1)
	go func() { got <- f.postOK(testToken) }()
	<-entered
	closed := make(chan error, 1)
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 1500*time.Millisecond)
		defer cancel()
		closed <- f.recv.Close(ctx)
	}()
	select {
	case <-closed:
		close(release)
		t.Fatalf("처리기가 release 에 선 동안 Close 가 돌아왔다")
	case <-time.After(100 * time.Millisecond):
	}
	close(release)
	if err := <-closed; err != nil {
		t.Fatalf("Close = %v, want nil", err)
	}
	// 처리기는 end_signal 줄을 쓴 뒤에 완료 계수를 내린다 — Close 가 돌아온 순간 그 줄이 있어야 한다.
	if l := f.lastEndSignal(); l.Reason != "closing" || l.Status != 503 || l.Level != "WARN" {
		t.Fatalf("Close 반환 때 처리기 완료 줄 = %+v, want 503 closing", l)
	}
	if r := <-got; r.status != http.StatusServiceUnavailable {
		t.Fatalf("상태 = %d, want 503", r.status)
	}
	changed, _, _ := f.snapshot()
	if len(changed) != 1 {
		t.Errorf("Changed = %v, want 1 번(커밋 결과 모름)", changed)
	}
}

// P5ⓑ — Close 는 진행 중 Apply 를 취소해 곧바로 끝낸다(Apply 4s 를 기다리지 않는다).
func TestCloseCancelsInflightApplyP5(t *testing.T) {
	f := newFixture(t, nil)
	entered := make(chan struct{})
	f.apply = func(ctx context.Context, _ session.IntentionalEnd) (session.IntentionalResult, error) {
		close(entered)
		if err := waitCtx(ctx, 4*time.Second); err != nil {
			return session.IntentionalResult{}, err
		}
		return session.IntentionalResult{Effect: "marked"}, nil
	}
	go f.postOK(testToken)
	<-entered
	ctx, cancel := context.WithTimeout(context.Background(), 1500*time.Millisecond)
	defer cancel()
	start := time.Now()
	if err := f.recv.Close(ctx); err != nil {
		t.Fatalf("Close = %v, want nil", err)
	}
	if el := time.Since(start); el > 200*time.Millisecond {
		t.Fatalf("Close 까지 %v, want ≤ 0.2s", el)
	}
	if err := f.recv.Close(ctx); err != nil {
		t.Fatalf("두 번째 Close = %v, want nil", err)
	}
}

// P5 경합 — Close 와 새 요청 100개를 동시에 돌려도 Close 가 돌아온 뒤 처리기 진입(resolve)은 0 이다.
func TestCloseRaceNoEntryAfterCloseP5(t *testing.T) {
	f := newFixture(t, func(o *Options) {
		o.Global = control.NewLimiter(1000, 1, o.Now, nil)
	})
	var closedDone atomic.Bool
	var afterClose atomic.Int32
	f.resolve = func(context.Context, string) (keyresolve.Verdict, error) {
		if closedDone.Load() {
			afterClose.Add(1)
		}
		return validVerdict(t, testPassphrase), nil
	}
	var wg sync.WaitGroup
	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			f.postOK(testToken)
		}()
	}
	ctx, cancel := context.WithTimeout(context.Background(), 1500*time.Millisecond)
	defer cancel()
	if err := f.recv.Close(ctx); err != nil {
		t.Fatalf("Close = %v", err)
	}
	closedDone.Store(true)
	wg.Wait()
	if n := afterClose.Load(); n != 0 {
		t.Fatalf("Close 뒤 처리기 진입 = %d, want 0", n)
	}
	if resp := f.postOK(testTokenB); resp.status != http.StatusServiceUnavailable {
		t.Fatalf("닫힌 뒤 요청 = %d, want 503", resp.status)
	}
}

// P8 — 틀린 Bearer 100건이 키당 계수기를 깎지 않는다. 전역 한도는 완화하고 고정 시계(같은 1초 창)로 돈다.
func TestMismatchDoesNotConsumePerKeyP8(t *testing.T) {
	f := newFixture(t, func(o *Options) {
		o.Global = control.NewLimiter(1000, 1, o.Now, nil)
	})
	for i := 0; i < 100; i++ {
		if resp := f.post(signalBody(testToken, 420, 1000), "Bearer "+wrongBearer); resp.status != http.StatusUnauthorized {
			t.Fatalf("틀린 Bearer %d번째 = %d, want 401", i, resp.status)
		}
	}
	if resp := f.postOK(testToken); resp.status != http.StatusAccepted {
		t.Fatalf("주인 요청 = %d, want 202", resp.status)
	}
}

// P9 — 같은 1초에 1000건이 와도 resolve 는 10번 이하이고 나머지는 503, 로그 줄은 11 이하다.
func TestGlobalLimitShedsFloodP9(t *testing.T) {
	f := newFixture(t, nil)
	codes := map[int]int{}
	for i := 0; i < 1000; i++ {
		codes[f.post(signalBody(testToken, 420, 1000), "Bearer "+wrongBearer).status]++
	}
	if n := f.resolveCalls.Load(); n > 10 {
		t.Fatalf("resolve 호출 = %d, want ≤ 10", n)
	}
	if codes[http.StatusServiceUnavailable] < 990 {
		t.Fatalf("503 = %d, want ≥ 990 (%v)", codes[http.StatusServiceUnavailable], codes)
	}
	n := 0
	for _, l := range f.log.lines(t) {
		if l.Msg == "end_signal" || l.Msg == "end_signal_shed" {
			n++
		}
	}
	if n > 11 {
		t.Fatalf("로그 줄 = %d, want ≤ 11", n)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := f.recv.Close(ctx); err != nil {
		t.Fatalf("Close = %v", err)
	}
	total := 0
	for _, l := range f.log.lines(t) {
		if l.Msg == "end_signal_shed" {
			if l.Reason != "rate_global" {
				t.Errorf("흘림 줄 reason = %q", l.Reason)
			}
			total += l.Count
		}
	}
	if total != 990 {
		t.Fatalf("흘림 묶음 count 합 = %d, want 990(Close 가 남은 묶음을 내보낸다)", total)
	}
}

// P10 — 시간 값 경계. 9223372036855ms 는 기간으로 곱하면 int64 를 넘는다 — 포화해 ConnStartedAt ≤ StoppedAt 이다.
func TestTimeBoundsP10(t *testing.T) {
	tests := []struct {
		name       string
		elapsed    int64
		duration   int64
		wantStatus int
	}{
		{name: "elapsed_0", elapsed: 0, duration: 0, wantStatus: 202},
		{name: "elapsed_60000", elapsed: 60000, duration: 1, wantStatus: 202},
		{name: "elapsed_60001", elapsed: 60001, duration: 1, wantStatus: 400},
		{name: "elapsed_음수", elapsed: -1, duration: 1, wantStatus: 400},
		{name: "duration_음수", elapsed: 1, duration: -1, wantStatus: 400},
		{name: "duration_넘침_경계_아래", elapsed: 1, duration: 9223372036854, wantStatus: 202},
		{name: "duration_넘침", elapsed: 1, duration: 9223372036855, wantStatus: 202},
		{name: "duration_int64_최대", elapsed: 1, duration: 9223372036854775807, wantStatus: 202},
	}
	for _, tc := range tests {
		f := newFixture(t, nil)
		resp := f.post(signalBody(testToken, tc.elapsed, tc.duration), "Bearer "+testBearer)
		if resp.status != tc.wantStatus {
			t.Errorf("%s: 상태 = %d, want %d", tc.name, resp.status, tc.wantStatus)
			continue
		}
		if tc.wantStatus != 202 {
			continue
		}
		_, _, applied := f.snapshot()
		e := applied[0]
		if e.ConnStartedAt.After(e.StoppedAt) || e.StoppedAt.After(testNow) {
			t.Errorf("%s: StoppedAt=%v ConnStartedAt=%v — 순서가 어긋났다", tc.name, e.StoppedAt, e.ConnStartedAt)
		}
	}
}
