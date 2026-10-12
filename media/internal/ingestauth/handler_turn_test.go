package ingestauth

import (
	"net/http"
	"slices"
	"sync/atomic"
	"testing"
	"time"
)

// waveStart 는 n 번째 물결의 시작(시험 시작에서)이다 — 8초마다(플러그인 재시도 주기 — 설계 3-8).
func waveStart(n int) time.Duration {
	return time.Duration(n-1) * 8 * time.Second
}

// wave 는 시험 시작에서 start 에 tokens 를 차례대로 처리기 전체에 보내고 응답 코드를 같은 차례로 돌려준다. 요청 사이에
// connGap(c)을 둔다 — 같은 박자 · 고정 순서 묶음이다(설계 3-8 셈의 조건). 연결 id 는 물결 번호 n 과 차례로 매긴다.
func (f *handlerFixture) wave(t *testing.T, n int, start time.Duration, tokens []string) []int {
	t.Helper()
	f.clock.moveTo(start)
	codes := make([]int, len(tokens))
	for i, token := range tokens {
		codes[i] = f.publish(t, token, testConnID(n*1000+i)).Code
		f.clock.advance(connGap)
	}
	return codes
}

// waveTokens 는 합성 토큰 n 개다.
func waveTokens(n int) []string {
	tokens := make([]string, n)
	for i := range tokens {
		tokens[i] = waveToken(i)
	}
	return tokens
}

// splitCalls 는 calls 를 failing 의 토큰과 나머지로 나눠, failing 의 호출 수와 나머지 호출이 끝난 시각을 돌려준다.
func splitCalls(calls []authCall, failing []string) (failingCalls int, otherEnds []time.Time) {
	for _, c := range calls {
		if slices.Contains(failing, c.token) {
			failingCalls++
			continue
		}
		otherEnds = append(otherEnds, c.end())
	}
	return failingCalls, otherEnds
}

// lastWithin 은 ends 가 n 개이고 그 가운데 가장 늦은 것이 start + limit 안인지 본다.
func lastWithin(ends []time.Time, n int, start time.Time, limit time.Duration) bool {
	return len(ends) == n && !slices.MaxFunc(ends, time.Time.Compare).After(start.Add(limit))
}

// 혼합 상태 k = 5(설계 2-4 708행 · 3-8 셈 1547행): 가짜 auth 가 토큰 다섯에 1.125초 판정 불가, 나머지 25 에 30ms
// valid:true 를 준다. 같은 순서의 30건을 요청 사이 6ms 를 두고 8초마다 보내면, 셋째 물결에서 정상 25건이 묶음 시작
// 2.25초 안에 resolve 하고, 넷째 물결부터 판정 불가 토큰의 resolve 는 물결마다 1건이다. 첫 물결은 예산이 F₁–F₃ 을,
// 둘째 물결은 재확인 하나와 아직 기억되지 않은 F₄ · F₅ 를 받는다(셈 — 기억이 다 차는 물결 j* = 2).
func TestHandlerMixedStateK5(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{})
	tokens := waveTokens(30)
	failing := tokens[:5]
	f.auth.setAnswer(func(token string) authAnswer {
		if slices.Contains(failing, token) {
			return answerUndecided(1125 * time.Millisecond)
		}
		return answerUsable(30 * time.Millisecond)
	})
	wantFailing := []int{0, 3, 3, 1, 1, 1, 1} // 물결 n(1부터)마다 판정 불가 토큰의 resolve 수
	for n := 1; n <= 6; n++ {
		f.wave(t, n, waveStart(n), tokens)
		start := f.clock.start.Add(waveStart(n))
		failingCalls, normalEnds := splitCalls(f.auth.callsBetween(start, start.Add(8*time.Second)), failing)
		if failingCalls != wantFailing[n] {
			t.Errorf("wave %d: resolves of undecided tokens = %d, want %d", n, failingCalls, wantFailing[n])
		}
		if n >= 3 && !lastWithin(normalEnds, 25, start, 2250*time.Millisecond) {
			t.Errorf("wave %d: normal resolves = %d ending by %v, want all 25 within 2.25s of the wave start", n, len(normalEnds), latestOffset(normalEnds, start))
		}
	}
}

// latestOffset 는 ends 가운데 가장 늦은 것이 start 에서 얼마 뒤인지다(없으면 0).
func latestOffset(ends []time.Time, start time.Time) time.Duration {
	if len(ends) == 0 {
		return 0
	}
	return slices.MaxFunc(ends, time.Time.Compare).Sub(start)
}

// 반례 A(설계 2-4 709행 · 3-8 1590행): 맨 앞 X 는 홀수 물결에 1.125초 판정 불가 · 짝수 물결에 10ms valid:false, 그다음
// 넷은 늘 1.125초 판정 불가, 나머지 25 는 30ms valid:true 다. 넷째 물결에서 정상 25건이 묶음 시작 2.25초 안에 resolve
// 하고, 해제는 물결 2 의 scope=token 하나(X)뿐이다. 해제를 「판정 결과면 모두 지움」(r1.5)으로 넓히면 그 해제가 모두를
// 지운다(음성 B9).
func TestHandlerCounterexampleA(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{})
	tokens := waveTokens(30)
	x, persistent := tokens[0], tokens[1:5]
	var waveNo atomic.Int32
	f.auth.setAnswer(func(token string) authAnswer {
		switch {
		case token == x && waveNo.Load()%2 == 0:
			return answerRejected("NOT_FOUND", 10*time.Millisecond)
		case token == x || slices.Contains(persistent, token):
			return answerUndecided(1125 * time.Millisecond)
		}
		return answerUsable(30 * time.Millisecond)
	})
	var clearedIn []int // failed_cleared 가 난 물결
	for n := 1; n <= 4; n++ {
		waveNo.Store(int32(n))
		before := len(handlerEvents(t, f.logs, "ingestauth_failed_cleared"))
		f.wave(t, n, waveStart(n), tokens)
		for range handlerEvents(t, f.logs, "ingestauth_failed_cleared")[before:] {
			clearedIn = append(clearedIn, n)
		}
	}
	cleared := handlerEvents(t, f.logs, "ingestauth_failed_cleared")
	if len(cleared) != 1 || clearedIn[0] != 2 || cleared[0].Path != x || cleared[0].Scope != "token" || cleared[0].Cleared != 1 {
		t.Errorf("failed_cleared events = %+v in waves %v, want one in wave 2 for X with scope token and cleared 1", cleared, clearedIn)
	}
	start := f.clock.start.Add(waveStart(4))
	if _, normalEnds := splitCalls(f.auth.callsBetween(start, start.Add(8*time.Second)), tokens[:5]); !lastWithin(normalEnds, 25, start, 2250*time.Millisecond) {
		t.Errorf("wave 4: normal resolves = %d ending by %v, want all 25 within 2.25s of the wave start", len(normalEnds), latestOffset(normalEnds, start))
	}
}

// 반례 B(설계 2-4 710행 · 3-8 1616행): 맨 앞 F 는 늘 판정 불가이고, 나머지 29(L₁–L₂₉)는 긴 장애 동안 판정 불가로
// 기억됐다. auth 가 돌아온 뒤 첫 물결을 w₁ 이라 하면, w₁ 의 돌림 차례가 어디에 있든 — F 에 있던 경우를 포함해 —
// 둘째 물결(w₂) 안에 scope=all 해제가 나고 셋째 물결(w₃) 안에 나머지 29 가 모두 200 이다. 기억은 실제 장애처럼
// 처리기로 쌓는다(3초 간격 판정 불가 — 예산이 매번 다시 찬다). 기억된 차례가 F → L₂₉ → L₁ … 이면 w₂ 의 해제는 맨
// 뒤에 오는 L₂₉ 가 낸다(최악 j = 29). 맨 뒤로 보내기를 빼거나(음성 B8) 돌림을 빼면(B10) F 가 칸을 쥐어 w₂ 에
// 해제가 없다.
func TestHandlerCounterexampleB(t *testing.T) {
	tokens := waveTokens(30)
	f0, ls := tokens[0], tokens[1:] // F · L₁–L₂₉(도착 차례)
	worstOrder := append([]string{f0, ls[28]}, ls[:28]...)
	tests := []struct {
		name          string
		memorized     []string // 장애 동안 기억된 차례(오래 기다린 차례)
		slotClosedAt0 bool     // w₀ 앞 2초에 재확인 하나가 칸을 써서 w₀ 에서는 도착만 적힌다 — w₁ 의 차례가 F 에 남는다
	}{
		{name: "w1_차례가_F", memorized: worstOrder, slotClosedAt0: true},
		{name: "w1_차례가_맨_뒤_L29", memorized: worstOrder},
		{name: "w1_차례가_맨_앞_L1", memorized: tokens},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f := newHandlerFixture(t, fixtureOptions{})
			f.auth.answerAll(answerUndecided(1125 * time.Millisecond)) // 장애
			for i, token := range tc.memorized {
				f.clock.moveTo(time.Duration(i) * 3 * time.Second)
				f.publish(t, token, testConnID(900+i))
			}
			const w0 = 100 * time.Second
			if tc.slotClosedAt0 {
				f.clock.moveTo(w0 - 2*time.Second)
				f.publish(t, ls[27], testConnID(999))
			}
			// w₀ — 아직 장애다.
			f.wave(t, 10, w0, tokens)
			// t_r — auth 가 정상 키에 다시 답한다. F 는 비밀 부재라 계속 판정 불가다.
			f.auth.setAnswer(func(token string) authAnswer {
				if token == f0 {
					return answerUndecided(1125 * time.Millisecond)
				}
				return answerUsable(30 * time.Millisecond)
			})
			attached := map[string]bool{}
			allClearedIn := 0
			for w := 1; w <= 3; w++ {
				before := len(handlerEvents(t, f.logs, "ingestauth_failed_cleared"))
				codes := f.wave(t, 10+w, w0+time.Duration(w)*8*time.Second, tokens)
				for i, code := range codes {
					if i > 0 && code == http.StatusOK {
						attached[tokens[i]] = true
					}
				}
				for _, e := range handlerEvents(t, f.logs, "ingestauth_failed_cleared")[before:] {
					if e.Scope == "all" && allClearedIn == 0 {
						allClearedIn = w
					}
				}
			}
			if allClearedIn == 0 || allClearedIn > 2 {
				t.Errorf("first scope=all clear after t_r in wave w%d, want within w2 (0 = none)", allClearedIn)
			}
			if len(attached) != 29 {
				t.Errorf("normal tokens attached by w3 = %d, want all 29", len(attached))
			}
		})
	}
}

// 예산 먼저(설계 2-4 704행 · c3 보안 1 · 2): 예산 거절은 차례를 쓰지 않고 도착 시각도 적지 않는다. 기억된 X(먼저) · Y
// 가 있고 예산이 바닥난 때 X 가 오면 401(budget_exhausted)이고 X 의 도착 · 재확인 칸이 그대로다. 그래서 잔액이 다시
// 차면 Y 가 곧바로 재확인한다 — X 의 도착이 적혔으면 Y 는 양보했고(yield), 칸을 썼으면 기다렸다(gap).
func TestHandlerBudgetDenialLeavesTurn(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{})
	tokX, tokY, tokZ := waveToken(1), waveToken(2), waveToken(3)
	f.h.failed.record(tokX, outcomeUndecided)
	f.h.failed.record(tokY, outcomeUndecided)
	f.auth.answerAll(answerUsable(3 * time.Second))
	f.publish(t, tokZ, testConnID(1)) // 1.375 + 1.5 − 3 = −0.125초

	f.publish(t, tokX, testConnID(2))

	if e, ok := lastDenied(t, f.logs); !ok || e.Reason != "budget_exhausted" {
		t.Fatalf("publish of remembered X with a negative balance: publish_denied = %+v, want budget_exhausted", e)
	}
	if e, _ := entryOf(f.h.failed, tokX); !e.arrivedAt.IsZero() || !lastRecheckOf(f.h.failed).IsZero() {
		t.Errorf("after X's budget denial: X arrived at %v, last recheck %v, want both unset", e.arrivedAt, lastRecheckOf(f.h.failed))
	}
	f.clock.advance(time.Second) // −0.125 + 0.5 = 0.375초
	f.auth.answerAll(answerUndecided(100 * time.Millisecond))
	before := f.auth.callCount()
	f.publish(t, tokY, testConnID(3))
	if e, ok := lastDenied(t, f.logs); f.auth.callCount() != before+1 || !ok || e.Reason != "undecided" {
		t.Errorf("publish of remembered Y after the refill: resolves %d → %d with publish_denied %+v, want Y's recheck (reason undecided)", before, f.auth.callCount(), e)
	}
}

// admit 가 참을 돌려준 뒤 resolve 쪽이 패닉하면 그 토큰을 판정 불가로 적어 차례 맨 뒤로 보낸다(c3 이월 5 · 결함 예측
// P5 — 설계 690행은 차감만 말한다). 적지 않으면 그 토큰이 맨 앞에 남아, 칸이 다시 열린 뒤 온 더 젊은 기억 토큰 Y 가
// 양보한다(최대 기억 수명 300초까지). 게이지는 건드리지 않는다 — 패닉은 auth 의 판정이 아니다.
func TestHandlerPanicSendsTokenToBack(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{})
	tokX, tokY := waveToken(1), waveToken(2)
	f.h.failed.record(tokX, outcomeUndecided)
	f.h.failed.record(tokY, outcomeUndecided)
	panicking := answerUsable(400 * time.Millisecond)
	panicking.before = func() { f.clock.onNextRead(func() { panic(errInjectedPanic) }) } // resolve 직후 처리기의 첫 시계 읽기
	f.auth.setAnswer(func(token string) authAnswer {
		if token == tokX {
			return panicking
		}
		return answerUndecided(100 * time.Millisecond)
	})

	if _, recovered := f.servePanicking(newAuthRequest(t, publishBody(tokX, testConnID(1)))); recovered != errInjectedPanic {
		t.Fatalf("panic out of X's recheck = %v, want the injected panic", recovered)
	}

	x, _ := entryOf(f.h.failed, tokX)
	y, _ := entryOf(f.h.failed, tokY)
	if x.seq <= y.seq || !x.failedAt.Equal(f.clock.start.Add(400*time.Millisecond)) {
		t.Errorf("after X's panic: X (seq %d, failed at %v) · Y (seq %d), want X behind Y and re-measured at the panic (+400ms)", x.seq, x.failedAt.Sub(f.clock.start), y.seq)
	}
	if got := gaugeNames(t, f.logs, "ingestauth_auth_degraded", "ingestauth_auth_recovered"); len(got) != 0 {
		t.Errorf("auth gauge events after a panic = %v, want none", got)
	}
	f.clock.moveTo(4500 * time.Millisecond) // 칸(0초 재확인)이 다시 열렸다. X 는 0초에 와 아직 활동 창 안이다
	before := f.auth.callCount()
	f.publish(t, tokY, testConnID(2))
	if e, ok := lastDenied(t, f.logs); f.auth.callCount() != before+1 || !ok || e.Reason != "undecided" {
		t.Errorf("publish of Y after X's panic: resolves %d → %d with publish_denied %+v, want Y's recheck (not recheck_wait yield)", before, f.auth.callCount(), e)
	}
}
