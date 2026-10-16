package ingestauth

import (
	"fmt"
	"runtime"
	"slices"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// syntheticToken 은 i 번째 합성 토큰이다. 토큰 꼴(26자 Crockford 대문자 — streamkey.IsToken)만 맞춘 값이고,
// 어떤 계정의 키도 아니다.
func syntheticToken(i int) string {
	return fmt.Sprintf("SYNTHET1CTEST%013d", i)
}

// admitResult 는 admit 의 두 결과를 실패 메시지에 쓰기 좋은 한 낱말로 묶는다 — 참이면 "ok", 거짓이면 wait
// 값("gap" · "yield")이다. 참인데 까닭이 붙거나 거짓인데 까닭이 없으면 그 꼴을 그대로 드러낸다.
func admitResult(f *failedTokens, token string) string {
	wait, ok := f.admit(token)
	switch {
	case ok && wait == "":
		return "ok"
	case !ok && wait != "":
		return wait
	default:
		return fmt.Sprintf("invalid(wait=%q, ok=%v)", wait, ok)
	}
}

// rememberAll 은 tokens 를 차례대로 판정 불가로 기록한다 — 앞의 토큰일수록 오래 기다렸다.
func rememberAll(f *failedTokens, tokens ...string) {
	for _, token := range tokens {
		f.record(token, outcomeUndecided)
	}
}

// admitStep 은 시나리오의 한 걸음이다. 시계를 시험 시작에서 at 으로 옮기고 token(실패 메시지의 이름 name)의
// admit 을 부른다. want 는 admitResult 의 꼴("ok" · "gap" · "yield")이다.
type admitStep struct {
	at    time.Duration
	name  string
	token string
	want  string
}

// 기억된 토큰의 재확인은 기억된 토큰 전체에서 4초(failedRecheckGap)에 하나다(설계 3-8 규칙 2). 기억된 토큰
// 셋이 4초 안에 차례로 오면 참은 하나이고, 나머지는 칸이 닫혀(gap) 거절된다. 기억에 없는 토큰은 그동안에도
// 참이다.
func TestFailedTokensGapAdmitsOneRecheck(t *testing.T) {
	clock := newManualClock()
	f := newFailedTokens(clock.now)
	a, b, c, other := syntheticToken(1), syntheticToken(2), syntheticToken(3), syntheticToken(9)
	rememberAll(f, a, b, c)

	for _, s := range []admitStep{
		{at: 10 * time.Second, name: "A", token: a, want: "ok"},
		{at: 11 * time.Second, name: "B", token: b, want: "gap"},
		{at: 12 * time.Second, name: "C", token: c, want: "gap"},
		{at: 12500 * time.Millisecond, name: "기억에 없는 토큰", token: other, want: "ok"},
	} {
		clock.moveTo(s.at)
		if got := admitResult(f, s.token); got != s.want {
			t.Errorf("admit(%s) at t=%v = %q, want %q", s.name, s.at, got, s.want)
		}
	}
}

// 첫 판정 불가는 칸을 쓰지 않는다(설계 2-4 시험). 기억에 없던 X 는 칸 없이 참이고, X 를 판정 불가로 새로
// 기억해도 재확인 칸을 건드리지 않는다 — 그보다 먼저 기억된 A 가 곧바로 와도 칸이 열려 있다. X 의 도착은
// X 가 기억된 토큰으로 다시 와야 적힌다(admit 에서만 — 설계 2-4 538–541행). 그래서 X 보다 나중에 다시 잰
// A 는, 아직 다시 오지 않은 X 에게 양보하지 않는다.
func TestFailedTokensFirstUndecidedLeavesGapOpen(t *testing.T) {
	clock := newManualClock()
	f := newFailedTokens(clock.now)
	a, x := syntheticToken(1), syntheticToken(2)
	f.record(a, outcomeUndecided)

	clock.moveTo(10 * time.Second)
	if got := admitResult(f, x); got != "ok" {
		t.Errorf("admit(X) at t=10s before X is remembered = %q, want %q", got, "ok")
	}
	f.record(x, outcomeUndecided)

	clock.moveTo(10500 * time.Millisecond)
	if got := admitResult(f, a); got != "ok" {
		t.Errorf("admit(A) at t=10.5s right after X's first undecided = %q, want %q (첫 판정 불가는 칸을 쓰지 않는다)", got, "ok")
	}
	f.record(a, outcomeUndecided) // A 는 맨 뒤로 간다 — 이제 X 가 A 보다 오래 기다렸다

	clock.moveTo(15 * time.Second)
	if got := admitResult(f, a); got != "ok" {
		t.Errorf("admit(A) at t=15s with X remembered but not come back = %q, want %q (X 의 도착은 admit 에서만 적힌다)", got, "ok")
	}
}

// 기억된 토큰이 하나뿐이면 5초 간격 시도마다 재확인한다 — 양보가 없다(설계 3-8 「송출 하나」).
func TestFailedTokensSingleStreamRechecksEveryAttempt(t *testing.T) {
	clock := newManualClock()
	f := newFailedTokens(clock.now)
	a := syntheticToken(1)
	f.record(a, outcomeUndecided)

	for attempt := range 6 {
		at := 10*time.Second + time.Duration(attempt)*5*time.Second
		clock.moveTo(at)
		if got := admitResult(f, a); got != "ok" {
			t.Errorf("admit(A) at t=%v (attempts 5s apart) = %q, want %q", at, got, "ok")
		}
		f.record(a, outcomeUndecided)
	}
}

// rememberThenCloseGap 은 C · A · B 를 차례로 기억시키고, C 가 t=1s 에 칸을 쓴 동안 A(t=2s) · B(t=3s)가 와
// 칸에 막히게 한다. C 는 다시 판정 불가라 맨 뒤로 간다 — 남은 차례는 A(가장 오래 기다림) → B 다.
func rememberThenCloseGap(t *testing.T) (clock *manualClock, f *failedTokens, a, b string) {
	t.Helper()
	clock = newManualClock()
	f = newFailedTokens(clock.now)
	c := syntheticToken(1)
	a, b = syntheticToken(2), syntheticToken(3)
	rememberAll(f, c, a, b)

	clock.moveTo(time.Second)
	if got := admitResult(f, c); got != "ok" {
		t.Fatalf("Setup: admit(C) at t=1s = %q, want %q", got, "ok")
	}
	f.record(c, outcomeUndecided)
	for _, s := range []admitStep{
		{at: 2 * time.Second, name: "A", token: a, want: "gap"},
		{at: 3 * time.Second, name: "B", token: b, want: "gap"},
	} {
		clock.moveTo(s.at)
		if got := admitResult(f, s.token); got != s.want {
			t.Fatalf("Setup: admit(%s) at t=%v = %q, want %q", s.name, s.at, got, s.want)
		}
	}
	return clock, f, a, b
}

// 칸은 가장 오래 기다린(마지막 판정 불가가 가장 이른) 활동 토큰이 갖는다(설계 3-8 규칙 3 — 돌림). 기억된
// A(먼저) · B 가 둘 다 10초 안에 왔으면, 칸이 열린 뒤 B 가 먼저 와도 양보하고(yield) 그다음 A 가 재확인한다.
// A 가 10초 넘게 오지 않으면 활동에서 빠져 B 가 재확인한다.
func TestFailedTokensYieldsToLongestWaiting(t *testing.T) {
	t.Run("오래_기다린_A_가_오면_A", func(t *testing.T) {
		clock, f, a, b := rememberThenCloseGap(t)
		for _, s := range []admitStep{
			{at: 5500 * time.Millisecond, name: "B", token: b, want: "yield"},
			{at: 6 * time.Second, name: "A", token: a, want: "ok"},
		} {
			clock.moveTo(s.at)
			if got := admitResult(f, s.token); got != s.want {
				t.Errorf("admit(%s) at t=%v (A came at t=2s) = %q, want %q", s.name, s.at, got, s.want)
			}
		}
	})

	t.Run("A_가_10초_넘게_오지_않으면_B", func(t *testing.T) {
		clock, f, _, b := rememberThenCloseGap(t)
		for _, s := range []admitStep{
			{at: 8 * time.Second, name: "B", token: b, want: "yield"},
			{at: 12500 * time.Millisecond, name: "B", token: b, want: "ok"},
		} {
			clock.moveTo(s.at)
			if got := admitResult(f, s.token); got != s.want {
				t.Errorf("admit(%s) at t=%v (A came at t=2s and not again) = %q, want %q", s.name, s.at, got, s.want)
			}
		}
	})
}

// 재확인이 판정 불가로 끝난 토큰은 차례 맨 뒤로 간다(설계 3-8 규칙 3). 고정 순서로 오는 기억 토큰 셋(늘
// 판정 불가 — 플러그인 5초 간격 구간의 한 주기 8초, 연결 사이 6ms)에서 재확인은 첫째 · 둘째 · 셋째 · 첫째로
// 돈다. 맨 앞 토큰이 칸을 두 번 잇달아 갖지 않는다.
func TestFailedTokensRecheckRotatesThroughWaitingTokens(t *testing.T) {
	clock := newManualClock()
	f := newFailedTokens(clock.now)
	names := []string{"첫째", "둘째", "셋째"}
	tokens := []string{syntheticToken(1), syntheticToken(2), syntheticToken(3)}
	rememberAll(f, tokens...)

	var granted [][]string
	for wave := range 4 {
		var got []string
		for i, token := range tokens {
			clock.moveTo(10*time.Second + time.Duration(wave)*8*time.Second + time.Duration(i)*6*time.Millisecond)
			if _, ok := f.admit(token); ok {
				got = append(got, names[i])
				f.record(token, outcomeUndecided)
			}
		}
		granted = append(granted, got)
	}

	want := [][]string{{"첫째"}, {"둘째"}, {"셋째"}, {"첫째"}}
	if !slices.EqualFunc(granted, want, slices.Equal) {
		t.Errorf("rechecks per wave = %v, want %v (맨 뒤로 — 맨 앞이 칸을 두 번 잇달아 갖지 않는다)", granted, want)
	}
}

// 확인과 설정은 잠금 하나 안에서 한다(설계 3-8 「원자적 차례」). 칸이 열린 때 고루틴 여럿이 서로 다른 기억
// 토큰으로 함께 admit 을 부르면 참은 하나다. 판마다 새로 만들어 겹침의 모양을 바꾼다. -race -count=20 으로
// 돈다. 확인과 설정이 갈라지는 회귀는 잠금 안의 논리 경합이라 경합 탐지기가 보지 못하고, P 가 하나면 고루틴이
// 그 틈에 끼어들지 못해 이 시험이 가르지 못한다 — 그때는 건너뛴다.
func TestFailedTokensAdmitIsAtomic(t *testing.T) {
	if p := runtime.GOMAXPROCS(0); p < 2 {
		t.Skipf("GOMAXPROCS=%d — 원자적 차례는 P 가 둘 이상일 때만 가른다", p)
	}
	const tokens, rounds = 16, 200
	list := make([]string, tokens)
	for i := range list {
		list[i] = syntheticToken(i)
	}
	for round := range rounds {
		f := newFailedTokens(newManualClock().now)
		rememberAll(f, list...)

		var granted atomic.Int32
		start := make(chan struct{})
		var wg sync.WaitGroup
		for _, token := range list {
			wg.Go(func() {
				<-start
				if _, ok := f.admit(token); ok {
					granted.Add(1)
				}
				f.record(token, outcomeUndecided)
			})
		}
		close(start)
		wg.Wait()

		if got := granted.Load(); got != 1 {
			t.Fatalf("round %d: %d concurrent admits of remembered tokens with the slot open granted %d, want 1", round, tokens, got)
		}
	}
}

// 재확인이 쓸 수 있는 값으로 끝나면 기억을 모두 지운다(설계 3-8 규칙 4 · 6절 ingestauth_failed_cleared
// scope=all). cleared 는 지운 기억 토큰 수이고 풀린 항목은 세지 않는다(설계 3-8 규칙 5 — 풀린 항목은 기억
// 밖이다). 다른 기억 토큰은 칸이 닫혀 있어도 곧바로 참이다.
func TestFailedTokensUsableClearsAll(t *testing.T) {
	clock := newManualClock()
	f := newFailedTokens(clock.now)
	stale, a, b, c := syntheticToken(0), syntheticToken(1), syntheticToken(2), syntheticToken(3)
	f.record(stale, outcomeUndecided) // t=300s 에 풀린다
	clock.moveTo(300 * time.Second)
	rememberAll(f, a, b, c)

	clock.moveTo(310 * time.Second)
	if got := admitResult(f, a); got != "ok" {
		t.Fatalf("Setup: admit(A) at t=310s = %q, want %q", got, "ok")
	}
	if scope, cleared := f.record(a, outcomeUsable); scope != "all" || cleared != 3 {
		t.Errorf("record(A, outcomeUsable) = %q, %d, want %q, 3 (A · B · C — 풀린 항목은 세지 않는다)", scope, cleared, "all")
	}

	clock.moveTo(311 * time.Second)
	for _, s := range []struct{ name, token, want string }{
		{name: "B", token: b, want: "ok"},
		{name: "C", token: c, want: "ok"},
	} {
		if got := admitResult(f, s.token); got != s.want {
			t.Errorf("admit(%s) at t=311s after the clear = %q, want %q (칸이 닫혀 있어도 기억 밖)", s.name, got, s.want)
		}
	}
}

// 재확인이 쓸 수 없는 값(valid:false · passphrase 형식 밖)으로 끝나면 그 토큰만 지운다(설계 3-8 규칙 4 ·
// scope=token). 다른 기억 토큰은 여전히 차례를 기다린다.
func TestFailedTokensUnusableClearsOnlyThatToken(t *testing.T) {
	clock := newManualClock()
	f := newFailedTokens(clock.now)
	a, b := syntheticToken(1), syntheticToken(2)
	rememberAll(f, a, b)

	clock.moveTo(10 * time.Second)
	if got := admitResult(f, a); got != "ok" {
		t.Fatalf("Setup: admit(A) at t=10s = %q, want %q", got, "ok")
	}
	if scope, cleared := f.record(a, outcomeUnusable); scope != "token" || cleared != 1 {
		t.Errorf("record(A, outcomeUnusable) = %q, %d, want %q, 1", scope, cleared, "token")
	}

	clock.moveTo(11 * time.Second)
	for _, s := range []struct{ name, token, want string }{
		{name: "B", token: b, want: "gap"},
		{name: "A", token: a, want: "ok"},
	} {
		if got := admitResult(f, s.token); got != s.want {
			t.Errorf("admit(%s) at t=11s after A's unusable outcome = %q, want %q", s.name, got, s.want)
		}
	}
}

// 기억에 없는 토큰의 판정은 기억을 바꾸지 않는다(설계 2-4 record) — 쓸 수 있는 값이어도 남의 기억을
// 지우지 않는다.
func TestFailedTokensOutcomeOfUnrememberedTokenKeepsMemory(t *testing.T) {
	for _, tc := range []struct {
		name    string
		outcome resolveOutcome
	}{
		{name: "쓸_수_있는_값", outcome: outcomeUsable},
		{name: "쓸_수_없는_값", outcome: outcomeUnusable},
	} {
		t.Run(tc.name, func(t *testing.T) {
			clock := newManualClock()
			f := newFailedTokens(clock.now)
			a, b, x := syntheticToken(1), syntheticToken(2), syntheticToken(9)
			rememberAll(f, a, b)
			clock.moveTo(10 * time.Second)
			if got := admitResult(f, a); got != "ok" {
				t.Fatalf("Setup: admit(A) at t=10s = %q, want %q", got, "ok")
			}

			if scope, cleared := f.record(x, tc.outcome); scope != "" || cleared != 0 {
				t.Errorf("record(X, %s) for a token not in memory = %q, %d, want %q, 0", tc.name, scope, cleared, "")
			}

			clock.moveTo(11 * time.Second)
			if got := admitResult(f, b); got != "gap" {
				t.Errorf("admit(B) at t=11s after X's outcome = %q, want %q (B 는 여전히 기억에 있다)", got, "gap")
			}
		})
	}
}

// 마지막 판정 불가에서 300초(failedForgetAfter)가 지난 토큰은 기억 밖이다(설계 3-8 규칙 5 · 2-4 시험 「풀린
// 항목」). 칸 없이 참이고, 그 판정은 남의 기억을 지우지 않고, 차례에서 세지 않는다. 판정 불가가 또 나면 새로
// 기억한다. 기억에 있는 동안 판정 불가가 또 나면 300초를 다시 잰다.
func TestFailedTokensReleasedEntryIsOutOfMemory(t *testing.T) {
	t.Run("칸_없이_참이고_남의_기억을_지우지_않음", func(t *testing.T) {
		clock := newManualClock()
		f := newFailedTokens(clock.now)
		x, y := syntheticToken(1), syntheticToken(2)
		f.record(x, outcomeUndecided) // t=300s 에 풀린다
		clock.moveTo(time.Second)
		f.record(y, outcomeUndecided)
		clock.moveTo(297 * time.Second)
		if got := admitResult(f, y); got != "ok" {
			t.Fatalf("Setup: admit(Y) at t=297s = %q, want %q", got, "ok")
		}
		f.record(y, outcomeUndecided) // 칸은 t=301s 까지 닫힌다

		for _, s := range []admitStep{
			{at: 299 * time.Second, name: "X", token: x, want: "gap"},
			{at: 300 * time.Second, name: "X", token: x, want: "ok"},
		} {
			clock.moveTo(s.at)
			if got := admitResult(f, s.token); got != s.want {
				t.Errorf("admit(%s) at t=%v = %q, want %q", s.name, s.at, got, s.want)
			}
		}
		if scope, cleared := f.record(x, outcomeUsable); scope != "" || cleared != 0 {
			t.Errorf("record(X, outcomeUsable) after X's memory was released = %q, %d, want %q, 0", scope, cleared, "")
		}

		clock.moveTo(300500 * time.Millisecond)
		if got := admitResult(f, y); got != "gap" {
			t.Errorf("admit(Y) at t=300.5s = %q, want %q (풀린 X 의 판정은 Y 의 기억을 지우지 않는다)", got, "gap")
		}
		f.record(x, outcomeUndecided)
		clock.moveTo(300600 * time.Millisecond)
		if got := admitResult(f, x); got != "gap" {
			t.Errorf("admit(X) at t=300.6s after a new undecided = %q, want %q (새로 기억한다)", got, "gap")
		}
	})

	t.Run("차례에서_세지_않음", func(t *testing.T) {
		clock := newManualClock()
		f := newFailedTokens(clock.now)
		x, y := syntheticToken(1), syntheticToken(2)
		f.record(x, outcomeUndecided) // t=300s 에 풀린다
		clock.moveTo(time.Second)
		f.record(y, outcomeUndecided)

		for _, s := range []admitStep{
			{at: 295 * time.Second, name: "X", token: x, want: "ok"},
			{at: 299500 * time.Millisecond, name: "Y", token: y, want: "yield"},
			{at: 300500 * time.Millisecond, name: "Y", token: y, want: "ok"},
		} {
			clock.moveTo(s.at)
			if got := admitResult(f, s.token); got != s.want {
				t.Errorf("admit(%s) at t=%v = %q, want %q (X 는 t=295s 에 왔고 t=300s 에 풀린다)", s.name, s.at, got, s.want)
			}
		}
	})

	t.Run("다시_재면_300초가_새로_시작", func(t *testing.T) {
		clock := newManualClock()
		f := newFailedTokens(clock.now)
		x, y := syntheticToken(1), syntheticToken(2)
		rememberAll(f, x, y)
		clock.moveTo(200 * time.Second)
		if got := admitResult(f, x); got != "ok" {
			t.Fatalf("Setup: admit(X) at t=200s = %q, want %q", got, "ok")
		}
		f.record(x, outcomeUndecided) // 다시 잰다 — 이제 t=500s 에 풀린다
		clock.moveTo(299 * time.Second)
		if got := admitResult(f, y); got != "ok" {
			t.Fatalf("Setup: admit(Y) at t=299s = %q, want %q", got, "ok")
		}

		clock.moveTo(301 * time.Second)
		if got := admitResult(f, x); got != "gap" {
			t.Errorf("admit(X) at t=301s = %q, want %q (t=200s 의 판정 불가에서 다시 잰 기억이 남아 있다)", got, "gap")
		}
	})
}

// 풀리지 않은 기억은 상한(failedTokensMax)까지다(설계 3-8 규칙 5). 넘친 토큰은 기억하지 않는다 — 그 토큰의
// admit 은 칸이 닫혀 있어도 참이다(예산만 탄다). 풀린 항목은 상한에 세지 않는다 — 맵이 차 있으면 풀린 항목을
// 먼저 치우고 넣는다. 시험은 상한을 2 로 준다.
func TestFailedTokensLimit(t *testing.T) {
	clock := newManualClock()
	f := newFailedTokens(clock.now)
	f.limit = 2
	a, b, over, late := syntheticToken(1), syntheticToken(2), syntheticToken(3), syntheticToken(4)
	f.record(a, outcomeUndecided) // t=300s 에 풀린다
	clock.moveTo(time.Second)
	f.record(b, outcomeUndecided)
	clock.moveTo(2 * time.Second)
	f.record(over, outcomeUndecided) // 상한에 닿았다 — 기억하지 않는다

	clock.moveTo(3 * time.Second)
	if got := admitResult(f, a); got != "ok" {
		t.Fatalf("Setup: admit(A) at t=3s = %q, want %q", got, "ok")
	}
	clock.moveTo(4 * time.Second)
	for _, s := range []struct{ name, token, want string }{
		{name: "넘친 토큰", token: over, want: "ok"},
		{name: "B", token: b, want: "gap"},
	} {
		if got := admitResult(f, s.token); got != s.want {
			t.Errorf("admit(%s) at t=4s with the slot closed = %q, want %q", s.name, got, s.want)
		}
	}

	clock.moveTo(300 * time.Second) // A 가 풀렸다. 맵은 아직 A · B 둘이다
	f.record(late, outcomeUndecided)
	clock.moveTo(300200 * time.Millisecond)
	if got := admitResult(f, b); got != "ok" {
		t.Fatalf("Setup: admit(B) at t=300.2s = %q, want %q", got, "ok")
	}
	clock.moveTo(300500 * time.Millisecond)
	if got := admitResult(f, late); got != "gap" {
		t.Errorf("admit(late) at t=300.5s = %q, want %q (풀린 A 를 치우고 기억했다)", got, "gap")
	}
}

// 긴 간격 구간(30 · 60초 간격 — 주기가 활동 창 10초보다 길다)에서는 돌림이 서지 않는다(설계 2-4 711행 ·
// 3-8 기억 수명 상한). 늘 먼저 오는 지속 실패 토큰 F 가 물결마다 칸을 갖고, 쓸 수 있는 값의 L 은 기억이
// 풀리기 전까지 물결마다 gap 이다. 풀린 뒤 L 의 첫 시도는 참이고, 그 판정은 F 의 기억을 지우지 않는다. 상한을
// 관측으로 단언한다 — L 이 실제로 거절된 마지막 시각 ≤ L 의 마지막 판정 불가 + 300초 + 한 주기. 거절이
// 이어지면 상한을 한 주기 넘길 때까지 보내 본다. 대조: 같은 입력을 8초마다 보내면 둘째 물결에 L 이 재확인한다(돌림).
func TestFailedTokensLongIntervalBoundedByMemoryLifetime(t *testing.T) {
	// remember 는 L(t=0s) · F(t=1s)를 기억시킨다 — L 의 마지막 판정 불가가 F 보다 이르다.
	remember := func() (*manualClock, *failedTokens, string, string) {
		clock := newManualClock()
		f := newFailedTokens(clock.now)
		l, fail := syntheticToken(1), syntheticToken(2)
		f.record(l, outcomeUndecided)
		clock.moveTo(time.Second)
		f.record(fail, outcomeUndecided)
		return clock, f, l, fail
	}

	t.Run("33초_주기", func(t *testing.T) {
		const period = 33 * time.Second
		const released = 300 * time.Second // L 의 마지막 판정 불가(t=0s) + 300초
		const bound = released + period    // L 이 거절될 수 있는 상한
		clock, f, l, fail := remember()

		var lastRejected time.Duration // L 이 실제로 거절된 마지막 시각
		for wave := 0; ; wave++ {
			base := 10*time.Second + time.Duration(wave)*period
			at := base + 10*time.Millisecond
			if at > bound+period {
				break // 거절이 상한을 한 주기 넘겨 이어졌다 — 아래 상한 단언이 잡는다
			}
			clock.moveTo(base)
			if got := admitResult(f, fail); got != "ok" {
				t.Errorf("admit(F) at t=%v = %q, want %q", base, got, "ok")
			}
			f.record(fail, outcomeUndecided)

			clock.moveTo(at)
			got := admitResult(f, l)
			switch {
			case at < released && got != "gap":
				t.Errorf("admit(L) at t=%v while L is remembered = %q, want %q", at, got, "gap")
			case at >= released && got != "ok":
				t.Errorf("admit(L) at t=%v after L's memory was released = %q, want %q", at, got, "ok")
			}
			if got != "ok" {
				lastRejected = at
				continue
			}
			if scope, cleared := f.record(l, outcomeUsable); scope != "" || cleared != 0 {
				t.Errorf("record(L, outcomeUsable) after L's memory was released = %q, %d, want %q, 0 (F 의 기억을 지우지 않는다)", scope, cleared, "")
			}
			break
		}

		if lastRejected > bound {
			t.Errorf("last recheck_wait for L at t=%v, want ≤ t=%v (L 의 마지막 판정 불가 + 300초 + 한 주기)", lastRejected, bound)
		}
		if got := admitResult(f, fail); got != "gap" {
			t.Errorf("admit(F) right after L's admit = %q, want %q (F 는 여전히 기억에 있다)", got, "gap")
		}
	})

	t.Run("8초_주기_대조", func(t *testing.T) {
		clock, f, l, fail := remember()
		clock.moveTo(10 * time.Second)
		if got := admitResult(f, fail); got != "ok" {
			t.Fatalf("Setup: admit(F) at t=10s = %q, want %q", got, "ok")
		}
		f.record(fail, outcomeUndecided)

		for _, s := range []admitStep{
			{at: 10010 * time.Millisecond, name: "L", token: l, want: "gap"},
			{at: 18 * time.Second, name: "F", token: fail, want: "yield"},
			{at: 18010 * time.Millisecond, name: "L", token: l, want: "ok"},
		} {
			clock.moveTo(s.at)
			if got := admitResult(f, s.token); got != s.want {
				t.Errorf("admit(%s) at t=%v = %q, want %q", s.name, s.at, got, s.want)
			}
		}
	})
}

// 시험 시계가 뒤로 가면 지금을 새 기준으로 삼는다 — 활동은 되감은 시각에서 길어야 10초 이어진다(설계 2-4
// 712행 · 651행). 기억된 A(먼저) · B 가 있고, 칸이 닫힌 때 A 가 와 도착이 적힌다. 시계를 뒤로 돌린 뒤 처음
// 부르는 시각이 되감은 시각이다. 그 뒤 10초 안에, 칸이 열린 뒤 B 가 오면 양보하고, 10초가 지난 뒤 오면 참이다.
func TestFailedTokensActivityWindowAfterClockRewind(t *testing.T) {
	clock, f, _, b := rememberThenCloseGap(t) // A 의 도착 t=2s, 칸은 t=5s 까지 닫힘
	const rewound = -50 * time.Second

	for _, s := range []admitStep{
		{at: rewound, name: "B", token: b, want: "gap"},
		{at: rewound + 5*time.Second, name: "B", token: b, want: "yield"},
		{at: rewound + 10*time.Second, name: "B", token: b, want: "ok"},
	} {
		clock.moveTo(s.at)
		if got := admitResult(f, s.token); got != s.want {
			t.Errorf("admit(%s) at t=%v (rewound to t=%v) = %q, want %q", s.name, s.at, rewound, got, s.want)
		}
	}
}

// 시험 시계가 뒤로 가도 칸은 4초보다 길게 닫히지 않고, 기억은 300초보다 길게 남지 않는다(설계 2-4 651행) —
// 되감기 전에 적힌 시각이 되감은 시각으로 당겨진다.
func TestFailedTokensClockRewindBoundsGapAndMemory(t *testing.T) {
	t.Run("칸", func(t *testing.T) {
		clock := newManualClock()
		f := newFailedTokens(clock.now)
		a := syntheticToken(1)
		f.record(a, outcomeUndecided)
		clock.moveTo(10 * time.Second)
		if got := admitResult(f, a); got != "ok" {
			t.Fatalf("Setup: admit(A) at t=10s = %q, want %q", got, "ok")
		}
		f.record(a, outcomeUndecided)

		const rewound = -100 * time.Second
		for _, s := range []admitStep{
			{at: rewound, name: "A", token: a, want: "gap"},
			{at: rewound + 4*time.Second, name: "A", token: a, want: "ok"},
		} {
			clock.moveTo(s.at)
			if got := admitResult(f, s.token); got != s.want {
				t.Errorf("admit(%s) at t=%v (rewound from t=10s) = %q, want %q", s.name, s.at, got, s.want)
			}
		}
	})

	t.Run("기억", func(t *testing.T) {
		clock := newManualClock()
		f := newFailedTokens(clock.now)
		y, x := syntheticToken(1), syntheticToken(2)
		f.record(y, outcomeUndecided)
		clock.moveTo(100 * time.Second)
		f.record(x, outcomeUndecided)

		const rewound = -1000 * time.Second
		for _, at := range []time.Duration{rewound, rewound + 298*time.Second} {
			clock.moveTo(at)
			if got := admitResult(f, y); got != "ok" {
				t.Fatalf("Setup: admit(Y) at t=%v = %q, want %q", at, got, "ok")
			}
			f.record(y, outcomeUndecided)
		}

		for _, s := range []admitStep{
			{at: rewound + 299*time.Second, name: "X", token: x, want: "gap"},
			{at: rewound + 300*time.Second, name: "X", token: x, want: "ok"},
		} {
			clock.moveTo(s.at)
			if got := admitResult(f, s.token); got != s.want {
				t.Errorf("admit(%s) at t=%v (X remembered at t=100s, rewound to t=%v) = %q, want %q", s.name, s.at, rewound, got, s.want)
			}
		}
	})
}

// 시험 시계가 뒤로 가도 이미 풀린 기억은 되살아나지 않는다(설계 3-8 규칙 5 · 2-4 651행) — 되감기 전에 마지막으로 읽은
// 지금으로 풀린 항목을 먼저 치우고 남은 시각을 당긴다. 치우지 않으면 결과가 게으른 치움 시점에 따라 갈린다 — 되살아난 항목이
// 재확인 칸을 받거나, 그 판정이 남의 기억을 지운다.
func TestFailedTokensReleasedEntryStaysReleasedAfterClockRewind(t *testing.T) {
	t.Run("칸을_받지_않음", func(t *testing.T) {
		clock := newManualClock()
		f := newFailedTokens(clock.now)
		x, y, z := syntheticToken(1), syntheticToken(2), syntheticToken(3)
		f.record(x, outcomeUndecided) // t=300s 에 풀린다
		clock.moveTo(400 * time.Second)
		f.record(y, outcomeUndecided)   // 상한 아래라 풀린 X 는 아직 치우지 않았다
		clock.moveTo(100 * time.Second) // 되감기 — 이 기록이 지금을 새 기준으로 삼는다
		f.record(z, outcomeUndecided)

		for _, s := range []struct{ name, token string }{{name: "X", token: x}, {name: "Z", token: z}} {
			if got := admitResult(f, s.token); got != "ok" {
				t.Errorf("admit(%s) at t=100s after rewinding from t=400s = %q, want %q (풀린 X 는 칸을 쓰지 않는다)", s.name, got, "ok")
			}
		}
	})

	t.Run("판정이_남의_기억을_지우지_않음", func(t *testing.T) {
		clock := newManualClock()
		f := newFailedTokens(clock.now)
		a, b := syntheticToken(1), syntheticToken(2)
		f.record(a, outcomeUndecided) // t=300s 에 풀린다
		clock.moveTo(100 * time.Second)
		f.record(b, outcomeUndecided)
		clock.moveTo(301 * time.Second)
		if got := admitResult(f, b); got != "ok" {
			t.Fatalf("Setup: admit(B) at t=301s = %q, want %q", got, "ok")
		}

		clock.moveTo(299 * time.Second) // 되감기
		if scope, cleared := f.record(a, outcomeUsable); scope != "" || cleared != 0 {
			t.Errorf("record(A, outcomeUsable) after rewinding from t=301s = %q, %d, want %q, 0 (A 는 t=300s 에 풀렸다)", scope, cleared, "")
		}
		if got := admitResult(f, b); got != "gap" {
			t.Errorf("admit(B) at t=299s after A's outcome = %q, want %q (B 는 여전히 기억에 있다)", got, "gap")
		}
	})
}

// 경계. 닫힘 · 활동 · 기억은 시작에서 그 길이까지의 반열린 구간이다 — 칸은 마지막 재확인에서
// 4초가 지나면(≥ 4초) 열리고, 활동은 도착에서 10초 안(< 10초)이고, 기억은 마지막 판정 불가에서 300초가
// 지나면(≥ 300초) 풀린다. 길이 − 1ns 와 길이에서 판정이 갈린다.
func TestFailedTokensBoundaries(t *testing.T) {
	t.Run("칸_4초", func(t *testing.T) {
		clock := newManualClock()
		f := newFailedTokens(clock.now)
		a := syntheticToken(1)
		f.record(a, outcomeUndecided)
		clock.moveTo(10 * time.Second)
		if got := admitResult(f, a); got != "ok" {
			t.Fatalf("Setup: admit(A) at t=10s = %q, want %q", got, "ok")
		}
		f.record(a, outcomeUndecided)

		for _, s := range []admitStep{
			{at: 14*time.Second - time.Nanosecond, name: "A", token: a, want: "gap"},
			{at: 14 * time.Second, name: "A", token: a, want: "ok"},
		} {
			clock.moveTo(s.at)
			if got := admitResult(f, s.token); got != s.want {
				t.Errorf("admit(%s) at t=%v (last recheck t=10s) = %q, want %q", s.name, s.at, got, s.want)
			}
		}
	})

	t.Run("활동_10초", func(t *testing.T) {
		clock, f, _, b := rememberThenCloseGap(t) // A 의 도착 t=2s
		for _, s := range []admitStep{
			{at: 12*time.Second - time.Nanosecond, name: "B", token: b, want: "yield"},
			{at: 12 * time.Second, name: "B", token: b, want: "ok"},
		} {
			clock.moveTo(s.at)
			if got := admitResult(f, s.token); got != s.want {
				t.Errorf("admit(%s) at t=%v (A came at t=2s) = %q, want %q", s.name, s.at, got, s.want)
			}
		}
	})

	t.Run("기억_300초", func(t *testing.T) {
		clock := newManualClock()
		f := newFailedTokens(clock.now)
		x, y := syntheticToken(1), syntheticToken(2)
		f.record(x, outcomeUndecided)
		clock.moveTo(time.Second)
		f.record(y, outcomeUndecided)
		clock.moveTo(297 * time.Second)
		if got := admitResult(f, y); got != "ok" {
			t.Fatalf("Setup: admit(Y) at t=297s = %q, want %q", got, "ok")
		}

		for _, s := range []admitStep{
			{at: 300*time.Second - time.Nanosecond, name: "X", token: x, want: "gap"},
			{at: 300 * time.Second, name: "X", token: x, want: "ok"},
		} {
			clock.moveTo(s.at)
			if got := admitResult(f, s.token); got != s.want {
				t.Errorf("admit(%s) at t=%v (X remembered at t=0s, slot closed until t=301s) = %q, want %q", s.name, s.at, got, s.want)
			}
		}
	})
}

// 차례의 동률은 기록 차례로 가른다. 같은 시각에 기록된 A · B 는 먼저 기록된 A 가 오래
// 기다렸다. 시계가 뒤로 가 늦게 적힌 시각이 지금으로 당겨져 같아져도 기록 차례가 그대로 가른다 — 시각만으로
// 견주면 둘 다 상대보다 이르지 않아 아무도 양보하지 않는다.
func TestFailedTokensTieBreaksByRecordOrder(t *testing.T) {
	tests := []struct {
		name string
		// remember 는 C · A · B 를 이 차례로 기억시킨다. A 와 B 의 마지막 판정 불가 시각은 같아진다.
		remember func(clock *manualClock, f *failedTokens, c, a, b string)
		start    time.Duration // 이 시각에 C 가 칸을 쓰고, 1초 뒤 A 가 와 막힌다
	}{
		{
			name: "같은_시각",
			remember: func(clock *manualClock, f *failedTokens, c, a, b string) {
				rememberAll(f, c, a, b)
			},
			start: 10 * time.Second,
		},
		{
			name: "되감기_뒤",
			remember: func(clock *manualClock, f *failedTokens, c, a, b string) {
				f.record(c, outcomeUndecided)
				clock.moveTo(100 * time.Second)
				f.record(a, outcomeUndecided)
				clock.moveTo(-50 * time.Second) // B 의 기록이 C · A 의 시각을 t=-50s 로 당긴다
				f.record(b, outcomeUndecided)
			},
			start: -49 * time.Second,
		},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			clock := newManualClock()
			f := newFailedTokens(clock.now)
			c, a, b := syntheticToken(1), syntheticToken(2), syntheticToken(3)
			tc.remember(clock, f, c, a, b)

			clock.moveTo(tc.start)
			if got := admitResult(f, c); got != "ok" {
				t.Fatalf("Setup: admit(C) at t=%v = %q, want %q", tc.start, got, "ok")
			}
			f.record(c, outcomeUndecided)

			for _, s := range []admitStep{
				{at: tc.start + time.Second, name: "A", token: a, want: "gap"},
				{at: tc.start + 4500*time.Millisecond, name: "B", token: b, want: "yield"},
				{at: tc.start + 5*time.Second, name: "A", token: a, want: "ok"},
			} {
				clock.moveTo(s.at)
				if got := admitResult(f, s.token); got != s.want {
					t.Errorf("admit(%s) at t=%v = %q, want %q (A 가 B 보다 먼저 기록됐다)", s.name, s.at, got, s.want)
				}
			}
		})
	}
}

// now 가 nil 이면 time.Now 를 쓴다(OpenConnLog 와 같은 규칙). 기억은 비어서 시작한다.
func TestNewFailedTokensNilNowUsesTimeNow(t *testing.T) {
	f := newFailedTokens(nil)
	a := syntheticToken(1)
	if got := admitResult(f, a); got != "ok" {
		t.Errorf("admit(A) on a new memory with now == nil = %q, want %q", got, "ok")
	}
	f.record(a, outcomeUndecided)
	if got := admitResult(f, a); got != "ok" {
		t.Errorf("admit(A) right after its first undecided = %q, want %q (첫 재확인은 칸이 열려 있다)", got, "ok")
	}
}
