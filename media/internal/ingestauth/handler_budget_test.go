package ingestauth

import (
	"net/http"
	"testing"
	"time"
)

// warmP95 는 웜 resolve 왕복의 p95 다(설계 3-7 257 실측 41.3ms). connGap 은 연결마다 MediaMTX · 응답기가 더하는 몫
// c 다(203 기준 B 6ms) — 처리기가 재지 않는 시간이라 요청 사이에 시계만 옮긴다.
const (
	warmP95 = 41300 * time.Microsecond
	connGap = 6 * time.Millisecond
)

// 건강한 묶음(설계 2-4 684행 · 3-7 표): 첫 호출 하나(웜 · 유휴 0.421 · 콜드 1.036 · 시한 1.125 · 가드 1.175초)와 웜
// 29건을 처음 상태에서 보내면 30건 모두 resolve 하고 예산 거절은 0 이다. 끝 잔액은 연산 순서(보충 → 차감 → 한도
// 자르기)로 손셈한 값이다 — 첫 호출 뒤 잔액은 1.375 + 첫 호출/2 − 첫 호출이고, 웜 한 건마다 0.003(c 동안 보충) +
// 0.02065(처리 중 보충) − 0.0413 = −0.01765초다. 가드 행의 첫 호출 뒤 잔액은 0.7875초다(685행 — 보충을 먼저 자르면
// 0.2초이고 13건에서 멈춘다). 시한 · 가드 행의 첫 호출은 판정 불가로 401 이다.
func TestHandlerHealthyBatch(t *testing.T) {
	tests := []struct {
		name             string
		first            authAnswer
		wantAfterFirst   time.Duration
		wantFinalBalance time.Duration
	}{
		{name: "모두_웜", first: answerUsable(warmP95), wantAfterFirst: 1354350 * time.Microsecond, wantFinalBalance: 842500 * time.Microsecond},
		{name: "유휴_0.421", first: answerUsable(421 * time.Millisecond), wantAfterFirst: 1164500 * time.Microsecond, wantFinalBalance: 652650 * time.Microsecond},
		{name: "콜드_1.036", first: answerUsable(1036 * time.Millisecond), wantAfterFirst: 857 * time.Millisecond, wantFinalBalance: 345150 * time.Microsecond},
		{name: "시한_1.125", first: answerUndecided(1125 * time.Millisecond), wantAfterFirst: 812500 * time.Microsecond, wantFinalBalance: 300650 * time.Microsecond},
		{name: "가드_1.175", first: answerUndecided(1175 * time.Millisecond), wantAfterFirst: 787500 * time.Microsecond, wantFinalBalance: 275650 * time.Microsecond},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f := newHandlerFixture(t, fixtureOptions{})
			first := waveToken(0)
			f.auth.setAnswer(func(token string) authAnswer {
				if token == first {
					return tc.first
				}
				return answerUsable(warmP95)
			})
			for i := range 30 {
				f.publish(t, waveToken(i), testConnID(i))
				if i == 0 {
					if got := balanceOf(f.h.budget); got != tc.wantAfterFirst {
						t.Errorf("balance after the first call = %v, want %v (보충 → 차감 → 한도 자르기)", got, tc.wantAfterFirst)
					}
				}
				f.clock.advance(connGap)
			}
			if n := f.auth.callCount(); n != 30 {
				t.Errorf("resolves in a healthy batch = %d, want 30", n)
			}
			if n := countDenied(t, f.logs, "budget_exhausted"); n != 0 {
				t.Errorf("budget denials in a healthy batch = %d, want 0", n)
			}
			if got := balanceOf(f.h.budget); got != tc.wantFinalBalance {
				t.Errorf("balance after the batch = %v, want %v", got, tc.wantFinalBalance)
			}
		})
	}
}

// countDenied 는 reason 으로 끝난 publish_denied 수다.
func countDenied(t *testing.T, logs *logRecorder, reason string) int {
	t.Helper()
	n := 0
	for _, e := range handlerEvents(t, logs, "ingestauth_publish_denied") {
		if e.Reason == reason {
			n++
		}
	}
	return n
}

// 갈색 정전(설계 2-4 686행): 모든 호출이 0.4초면(쉼 없이 잇달아) 7건째까지 resolve 하고, 8건째부터 resolve 호출 0 ·
// 401 · reason=budget_exhausted 다. 시계를 3초 앞당기면 다시 7건을 받는다. 예산 거절은 resolve 를 부르지 않으므로
// auth 게이지를 바꾸지 않는다(설계 2-4 「auth 게이지의 판정 재료」).
func TestHandlerBrownout(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{})
	f.auth.answerAll(answerUsable(400 * time.Millisecond))
	burst := func(from int) (resolved int) {
		for i := from; i < from+10; i++ {
			before := f.auth.callCount()
			rr := f.publish(t, waveToken(i), testConnID(i))
			if f.auth.callCount() > before {
				resolved++
				continue
			}
			if e, ok := lastDenied(t, f.logs); rr.Code != http.StatusUnauthorized || !ok || e.Reason != "budget_exhausted" || e.ResolveMS != 0 {
				t.Errorf("request %d after the budget ran out = %d with publish_denied %+v, want 401 budget_exhausted with resolve_ms 0", i, rr.Code, e)
			}
		}
		return resolved
	}
	if got := burst(0); got != 7 {
		t.Errorf("resolves in a 0.4s brown-out burst of 10 = %d, want 7", got)
	}
	f.clock.advance(3 * time.Second)
	if got := burst(10); got != 7 {
		t.Errorf("resolves 3s later = %d, want 7 again", got)
	}
	if got := gaugeNames(t, f.logs, "ingestauth_auth_degraded", "ingestauth_auth_recovered"); len(got) != 0 {
		t.Errorf("auth gauge events in a brown-out = %v, want none", got)
	}
}

// 결과 무관(설계 2-4 687행): 0.4초 valid:true 와 0.4초 판정 불가가 같은 몫을 뺀다. 빠른 valid:false(10ms)는 10ms 만
// 뺀다 — 앞서 바닥난 잔액을 채우지 못한다.
func TestHandlerBudgetIgnoresOutcome(t *testing.T) {
	balanceAfter := func(ans authAnswer) time.Duration {
		f := newHandlerFixture(t, fixtureOptions{})
		f.auth.answerAll(ans)
		f.publish(t, testTokenA, testConnID(1))
		return balanceOf(f.h.budget)
	}
	usable, undecided := balanceAfter(answerUsable(400*time.Millisecond)), balanceAfter(answerUndecided(400*time.Millisecond))
	if usable != 1175*time.Millisecond || undecided != usable {
		t.Errorf("balance after a 0.4s valid:true = %v and after a 0.4s undecided = %v, want both 1.175s", usable, undecided)
	}

	f := newHandlerFixture(t, fixtureOptions{})
	f.auth.answerAll(answerUsable(400 * time.Millisecond))
	for i := range 6 {
		f.publish(t, waveToken(i), testConnID(i)) // 1.375 − 6 × 0.2 = 0.175초
	}
	f.auth.answerAll(answerRejected("NOT_FOUND", 10*time.Millisecond))
	f.publish(t, waveToken(6), testConnID(6))
	if got := balanceOf(f.h.budget); got != 170*time.Millisecond {
		t.Errorf("balance after a fast valid:false from 0.175s = %v, want 170ms (10ms 를 빼고 5ms 를 채운다)", got)
	}
}

// guardDeadline 은 실제 가드를 일으키는 시험의 Deadline 이다(가드는 그 + 50ms). resolve 왕복이 부하 아래서도 이 안에
// 끝날 만큼 넉넉하고, 시험이 실제로 기다리는 것은 가드 한 번뿐이다.
const guardDeadline = 200 * time.Millisecond

// 바깥 가드로 끝난 요청은 가드 시각까지 뺀다(설계 2-4 689행 — 짧은 Deadline). 안쪽 처리기가 본문을 기다리며 멈춘
// 동안 가드가 답하고, 바깥 층이 끝 시각을 읽을 때 시험 시계가 1.175초 흘렀으면 잔액은 1.375 + 0.5875 − 1.175 =
// 0.7875초다. 가드 뒤 늦게 끝난 안쪽 처리기는 다시 빼지 않는다.
func TestHandlerGuardChargesUntilGuard(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{deadline: guardDeadline})
	rr, served, finish := f.stalledPublish(t, testTokenA, testConnID(1))
	// 바깥 층은 시작 시각을 이미 읽었고 안쪽은 본문을 기다린다 — 다음 시계 읽기는 가드가 답한 뒤 바깥 층의 끝이다.
	f.clock.onNextRead(func() { f.clock.advance(1175 * time.Millisecond) })

	waitClosed(t, served, "the guard's 503")

	if rr.Code != http.StatusServiceUnavailable || rr.Body.String() != "timeout" {
		t.Errorf("stalled publish = %d %q, want 503 \"timeout\" from the guard", rr.Code, rr.Body.String())
	}
	if got := balanceOf(f.h.budget); got != 787500*time.Microsecond {
		t.Errorf("balance when the guard answered = %v, want 787.5ms", got)
	}
	finish()
	if end := waitForInnerEnd(t, f.logs); end != "ingestauth_publish_late" {
		t.Errorf("late inner handler ended with %s, want ingestauth_publish_late", end)
	}
	if got := balanceOf(f.h.budget); got != 787500*time.Microsecond {
		t.Errorf("balance after the late inner handler finished = %v, want still 787.5ms (다시 빼지 않는다)", got)
	}
}

// 패닉으로 끝난 요청도 뺀다(설계 2-4 690행). resolve 가 0.4초 걸린 뒤 처리기가 패닉하면 그 패닉은 바깥으로 이어지고,
// 잔액은 1.375 + 0.2 − 0.4 = 1.175초다.
func TestHandlerPanicIsCharged(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{})
	ans := answerUsable(400 * time.Millisecond)
	ans.before = func() { f.clock.onNextRead(func() { panic(errInjectedPanic) }) } // resolve 직후 처리기의 첫 시계 읽기
	f.auth.answerAll(ans)

	_, recovered := f.servePanicking(newAuthRequest(t, publishBody(testTokenA, testConnID(1))))

	if recovered != errInjectedPanic {
		t.Fatalf("panic out of the handler = %v, want the injected panic", recovered)
	}
	if got := balanceOf(f.h.budget); got != 1175*time.Millisecond {
		t.Errorf("balance after a request that panicked after 0.4s = %v, want 1.175s", got)
	}
	for _, name := range []string{"ingestauth_publish_allowed", "ingestauth_publish_denied", "ingestauth_auth_degraded"} {
		if es := handlerEvents(t, f.logs, name); len(es) != 0 {
			t.Errorf("%s events after a panic = %+v, want none", name, es)
		}
	}
}

// 거부 목록 판정은 예산보다 앞이다(설계 2-4 691행 · 음성 B6). api 요청은 잔액이 음수여도 200 이다 — 예산 · 차례에
// 걸리지 않아 바닥난 예산에서도 cut 이 돈다.
func TestHandlerDenylistAndAPIBeforeBudget(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{})
	f.auth.answerAll(answerUsable(2 * time.Second))
	f.publish(t, waveToken(0), testConnID(0)) // 1.375 + 1 − 2 = 0.375초
	f.publish(t, waveToken(1), testConnID(1)) // 0.375 + 1 − 2 = −0.625초
	if got := balanceOf(f.h.budget); got >= 0 {
		t.Fatalf("Setup: balance = %v, want negative", got)
	}
	f.writeDenyList(t, testTokenA+"\n")

	f.publish(t, testTokenA, testConnID(2))
	if e, ok := lastDenied(t, f.logs); !ok || e.Reason != "denylisted" {
		t.Errorf("publish of a denylisted token with a negative balance: publish_denied = %+v, want reason denylisted", e)
	}
	if rr := f.serve(newAuthRequest(t, apiBody(testSelfIP.String(), testAPIUser, testAPIPass))); rr.Code != http.StatusOK {
		t.Errorf("api request with a negative balance = %d, want 200", rr.Code)
	}
	f.publish(t, testTokenB, testConnID(3))
	if e, ok := lastDenied(t, f.logs); !ok || e.Reason != "budget_exhausted" {
		t.Errorf("publish of another token with a negative balance: publish_denied = %+v, want reason budget_exhausted", e)
	}
}

// 바깥 가드가 끝낸 뒤 깨어난 안쪽 처리기는 resolve 를 부르지 않는다(설계 2-4 693행 · 677 · 음성 B7) — 가짜 auth 호출
// 0 · 게이지 전이 0 · 차례 소비 0(도착 · 재확인 칸 그대로). 끝난 ctx 로 Resolve 를 부르면 연결 전에 돌아오므로 호출
// 수만으로는 가르지 못한다 — 게이지와 차례를 함께 본다.
func TestHandlerLateInnerSkipsResolve(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{deadline: guardDeadline})
	f.h.failed.record(testTokenA, outcomeUndecided) // 기억된 토큰 — 칸이 열려 있어 admit 를 부르면 재확인을 갖는다
	before, _ := entryOf(f.h.failed, testTokenA)
	rr, served, finish := f.stalledPublish(t, testTokenA, testConnID(1))
	waitClosed(t, served, "the guard's 503")
	if rr.Code != http.StatusServiceUnavailable {
		t.Fatalf("stalled publish = %d, want the guard's 503", rr.Code)
	}

	finish()
	if end := waitForInnerEnd(t, f.logs); end != "ingestauth_publish_late" {
		t.Errorf("late inner handler ended with %s, want ingestauth_publish_late", end)
	}

	if n := f.auth.callCount(); n != 0 {
		t.Errorf("fake auth calls by the late inner handler = %d, want 0", n)
	}
	if got := gaugeNames(t, f.logs, "ingestauth_auth_degraded", "ingestauth_auth_recovered"); len(got) != 0 {
		t.Errorf("auth gauge events by the late inner handler = %v, want none", got)
	}
	if after, _ := entryOf(f.h.failed, testTokenA); after != before || !lastRecheckOf(f.h.failed).IsZero() {
		t.Errorf("failed-token state after the late inner handler: entry %+v → %+v, last recheck %v, want unchanged", before, after, lastRecheckOf(f.h.failed))
	}
}

// 가드가 먼저 503 을 낸 뒤 안쪽이 200 을 쓰면 그 쓰기는 버려진다 — publish_allowed 대신 publish_late(DEBUG)다(결함 예측
// P4). 기록 줄은 남는다(무해 — 설계 2-4 「시한 가드가 503 으로 바꿔 친 경우」).
func TestHandlerLateAllowIsNotLoggedAsAllowed(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{deadline: guardDeadline})
	release := make(chan struct{})
	ans := answerUsable(10 * time.Millisecond)
	ans.before = func() { f.clock.onNextRead(func() { <-release }) } // resolve 직후 처리기의 첫 시계 읽기에서 멈춘다
	f.auth.answerAll(ans)
	rr, served := f.publishAsync(t, testTokenA, testConnID(1))
	t.Cleanup(func() {
		select {
		case <-release:
		default:
			close(release)
		}
	})

	waitClosed(t, served, "the guard's 503")
	if rr.Code != http.StatusServiceUnavailable {
		t.Fatalf("publish whose inner handler stalled past the guard = %d, want 503", rr.Code)
	}
	close(release)
	if end := waitForInnerEnd(t, f.logs); end != "ingestauth_publish_late" {
		t.Errorf("inner handler that wrote its 200 after the guard ended with %s, want ingestauth_publish_late", end)
	}

	if allowed := handlerEvents(t, f.logs, "ingestauth_publish_allowed"); len(allowed) != 0 {
		t.Errorf("publish_allowed after the guard answered = %+v, want none", allowed)
	}
	if got := allowedConns(t, f.connDir); got[testConnID(1)] != testTokenA {
		t.Errorf("allowed connections = %v, want %s → %s (줄은 남는다)", got, testConnID(1), testTokenA)
	}
}
