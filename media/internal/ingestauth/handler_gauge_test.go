package ingestauth

import (
	"net/http"
	"os"
	"slices"
	"sync"
	"testing"
	"time"
)

// gaugeNames 는 이벤트 이름 가운데 names 에 든 것만 차례대로 돌려준다.
func gaugeNames(t *testing.T, logs *logRecorder, names ...string) []string {
	t.Helper()
	var got []string
	for _, n := range eventNames(t, logs) {
		if slices.Contains(names, n) {
			got = append(got, n)
		}
	}
	return got
}

// auth 게이지 짝(설계 6절 게이지 표 · 2-4 713행 — 부31): 처음 상태(판정됨)에서 첫 판정 불가면 auth_degraded(reason ·
// status), 그 뒤 첫 판정 결과면 auth_recovered(down_for · denied_during · budget_denied · recheck_denied)다. 둘 다
// ERROR 다. 예산 · 차례 거절은 게이지를 바꾸지 않고 recovered 의 셈에만 든다. denied_during 은 게이지가 선 동안(세운
// 요청 포함) 401 로 끝난 publish 수(사유 무관)이고, budget_denied · recheck_denied 는 그 가운데 예산 · 차례 거절이다.
func TestHandlerAuthGaugePair(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{})
	tokA, tokB, tokC := waveToken(1), waveToken(2), waveToken(3)
	f.auth.setAnswer(func(token string) authAnswer {
		if token == tokC {
			return answerUsable(30 * time.Millisecond)
		}
		return answerUndecided(1125 * time.Millisecond)
	})
	steps := []struct {
		at         time.Duration // 보내는 시각(시험 시작에서). 음수면 앞 요청이 끝난 그대로
		token      string
		wantReason string // publish_denied 의 reason. 비면 200
	}{
		{at: 0, token: tokA, wantReason: "undecided"},                  // 잔액 1.375 → 0.8125. 게이지 set(t=1.125)
		{at: -1, token: tokB, wantReason: "undecided"},                 // t=1.125 → 0.25
		{at: -1, token: tokA, wantReason: "undecided"},                 // t=2.25 — A 의 재확인(칸 열림) → −0.3125
		{at: -1, token: tokB, wantReason: "budget_exhausted"},          // t=3.375 — 잔액이 0 보다 작다
		{at: 4 * time.Second, token: tokB, wantReason: "recheck_wait"}, // 잔액 0 · 칸은 t=6.25 까지 닫힘
		{at: 10 * time.Second, token: tokC, wantReason: ""},            // 판정 결과 → 게이지 clear(t=10.03)
		{at: 11 * time.Second, token: tokB, wantReason: "undecided"},   // 다시 판정 불가 → 다시 set
	}
	for i, s := range steps {
		if s.at >= 0 {
			f.clock.moveTo(s.at)
		}
		rr := f.publish(t, s.token, testConnID(i))
		e, denied := lastDenied(t, f.logs)
		switch {
		case s.wantReason == "" && rr.Code != http.StatusOK:
			t.Fatalf("step %d: publish %s = %d, want 200", i, s.token, rr.Code)
		case s.wantReason != "" && (rr.Code != http.StatusUnauthorized || !denied || e.Reason != s.wantReason):
			t.Fatalf("step %d: publish %s = %d with publish_denied %+v, want 401 with reason %q", i, s.token, rr.Code, e, s.wantReason)
		}
	}

	if got, want := gaugeNames(t, f.logs, "ingestauth_auth_degraded", "ingestauth_auth_recovered"),
		[]string{"ingestauth_auth_degraded", "ingestauth_auth_recovered", "ingestauth_auth_degraded"}; !slices.Equal(got, want) {
		t.Fatalf("auth gauge events = %v, want %v (예산 · 차례 거절은 게이지를 바꾸지 않는다)", got, want)
	}
	degraded := handlerEvents(t, f.logs, "ingestauth_auth_degraded")[0]
	if degraded.Level != "ERROR" || degraded.Reason != "status" || degraded.Status != http.StatusInternalServerError {
		t.Errorf("auth_degraded = %+v, want ERROR with reason status and status 500", degraded)
	}
	recovered := lastEvent(t, f.logs, "ingestauth_auth_recovered")
	want := handlerEvent{Level: "ERROR", DownFor: int64(8905 * time.Millisecond), DeniedDuring: 5, BudgetDenied: 1, RecheckDenied: 1}
	if recovered.Level != want.Level || recovered.DownFor != want.DownFor || recovered.DeniedDuring != want.DeniedDuring ||
		recovered.BudgetDenied != want.BudgetDenied || recovered.RecheckDenied != want.RecheckDenied {
		t.Errorf("auth_recovered = %+v, want level %s, down_for %v, denied_during %d, budget_denied %d, recheck_denied %d (set t=1.125 · clear t=10.03)",
			recovered, want.Level, time.Duration(want.DownFor), want.DeniedDuring, want.BudgetDenied, want.RecheckDenied)
	}
}

// 거부 목록 짝(설계 6절 · 2-4 713행 — 부31): 파일 권한을 뺐다가 되돌리면 denylist_error · denylist_recovered 가 같은
// 등급(ERROR)으로 한 번씩 난다. 이 짝은 DenyList 가 내고(c2) 처리기는 다시 내지 않는다(작업 지시 2절).
func TestHandlerDenylistGaugePair(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root 는 권한 검사를 우회한다")
	}
	f := newHandlerFixture(t, fixtureOptions{})
	f.writeDenyList(t, "")
	chmod(t, f.denyPath, 0o000)
	f.publish(t, testTokenA, testConnID(1))
	f.publish(t, testTokenA, testConnID(2))
	chmod(t, f.denyPath, 0o644)
	f.publish(t, testTokenA, testConnID(3))

	if got, want := gaugeNames(t, f.logs, "ingestauth_denylist_error", "ingestauth_denylist_recovered"),
		[]string{"ingestauth_denylist_error", "ingestauth_denylist_recovered"}; !slices.Equal(got, want) {
		t.Fatalf("deny list gauge events = %v, want %v", got, want)
	}
	set := lastEvent(t, f.logs, "ingestauth_denylist_error")
	clear := lastEvent(t, f.logs, "ingestauth_denylist_recovered")
	if set.Level != "ERROR" || clear.Level != set.Level {
		t.Errorf("deny list gauge levels = set %s · clear %s, want both ERROR", set.Level, clear.Level)
	}
}

// 기록 짝(설계 6절 「쓰기 실패 → set · 첫 쓰기 성공 → clear」 · 2-4 713행 — 부31): 기록 디렉터리 쓰기 권한을 뺐다가
// 되돌리면 connlog_error · connlog_recovered 가 같은 등급(ERROR)으로 한 번씩 난다. 실패가 이어져도 set 은 한 번이다.
func TestHandlerConnLogGaugePair(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root 는 권한 검사를 우회한다")
	}
	f := newHandlerFixture(t, fixtureOptions{connMaxBytes: 1})
	chmod(t, f.connDir, 0o500)
	f.publish(t, testTokenA, testConnID(1))
	f.publish(t, testTokenB, testConnID(2))
	chmod(t, f.connDir, 0o755)
	f.publish(t, testTokenA, testConnID(3))
	f.publish(t, testTokenB, testConnID(4))

	if got, want := gaugeNames(t, f.logs, "ingestauth_connlog_error", "ingestauth_connlog_recovered"),
		[]string{"ingestauth_connlog_error", "ingestauth_connlog_recovered"}; !slices.Equal(got, want) {
		t.Fatalf("connection log gauge events = %v, want %v", got, want)
	}
	set := lastEvent(t, f.logs, "ingestauth_connlog_error")
	clear := lastEvent(t, f.logs, "ingestauth_connlog_recovered")
	if set.Level != "ERROR" || clear.Level != set.Level {
		t.Errorf("connection log gauge levels = set %s · clear %s, want both ERROR", set.Level, clear.Level)
	}
}

// failed_cleared 는 recheck_wait 거절과 같은 WARN 이다(설계 6절 · 2-4 713행). 기억된 토큰의 재확인이 valid:false 면 그
// 토큰만(scope=token), 쓸 수 있는 값이면 모두(scope=all) 지운다. 차례 거절은 게이지를 바꾸지 않는다.
func TestHandlerFailedClearedLevelAndScope(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{})
	tokX, tokY, tokZ := waveToken(1), waveToken(2), waveToken(3)
	f.h.failed.record(tokX, outcomeUndecided)
	f.h.failed.record(tokY, outcomeUndecided)
	f.h.failed.record(tokZ, outcomeUndecided)
	f.auth.answerAll(answerRejected("NOT_FOUND", 10*time.Millisecond))

	f.publish(t, tokX, testConnID(1)) // X 재확인 → valid:false → X 만 지움
	f.publish(t, tokY, testConnID(2)) // 칸이 닫혀 recheck_wait(gap)
	f.clock.advance(5 * time.Second)
	f.auth.answerAll(answerUsable(10 * time.Millisecond))
	f.publish(t, tokY, testConnID(3)) // Y 재확인 → 쓸 수 있는 값 → 모두(Y · Z) 지움

	cleared := handlerEvents(t, f.logs, "ingestauth_failed_cleared")
	want := []handlerEvent{
		{Level: "WARN", Path: tokX, Scope: "token", Cleared: 1},
		{Level: "WARN", Path: tokY, Scope: "all", Cleared: 2},
	}
	if len(cleared) != len(want) {
		t.Fatalf("failed_cleared events = %+v, want %+v", cleared, want)
	}
	for i := range want {
		if c := cleared[i]; c.Level != want[i].Level || c.Path != want[i].Path || c.Scope != want[i].Scope || c.Cleared != want[i].Cleared {
			t.Errorf("failed_cleared[%d] = %+v, want %+v", i, c, want[i])
		}
	}
	waits := 0
	for _, e := range handlerEvents(t, f.logs, "ingestauth_publish_denied") {
		if e.Reason == "recheck_wait" {
			waits++
			if e.Level != cleared[0].Level || e.Wait != "gap" {
				t.Errorf("recheck_wait denial = %+v, want level %s (failed_cleared) with wait gap", e, cleared[0].Level)
			}
		}
	}
	if waits != 1 {
		t.Errorf("recheck_wait denials = %d, want 1", waits)
	}
	if got := gaugeNames(t, f.logs, "ingestauth_auth_degraded", "ingestauth_auth_recovered"); len(got) != 0 {
		t.Errorf("auth gauge events = %v, want none (판정 결과뿐이고 차례 거절은 게이지를 바꾸지 않는다)", got)
	}
}

// 기록 게이지는 쓰기와 전이를 잠금 하나로 묶는다(결함 예측 P8): 실패하는 쓰기와 성공하는 쓰기가 여러 고루틴에서
// 겹쳐도 경쟁이 없고(-race), connlog_error 와 connlog_recovered 가 error 로 시작해 번갈아 나며, 마지막 쓰기가
// 성공이면 recovered 로 끝난다. 실패하는 쓰기는 빈 토큰이다 — ConnLog.Append 가 파일에 닿기 전에 에러를 돌려준다.
func TestConnLogGaugeConcurrentAppends(t *testing.T) {
	log, logs := newLogRecorder()
	g := &connLogGauge{log: log}
	c, _ := openTestConnLog(t, t.TempDir(), 1<<20, newFakeClock())
	var wg sync.WaitGroup
	for w := range 8 {
		wg.Go(func() {
			for i := range 50 {
				token := testTokenA
				if (w+i)%2 == 0 {
					token = "" // 실패하는 쓰기 — 이 시험은 게이지의 전이만 보고 쓰기의 결과는 보지 않는다
				}
				_ = g.append(c, token, testConnID(w*100+i))
			}
		})
	}
	wg.Wait()
	if err := g.append(c, testTokenA, testConnID(9999)); err != nil {
		t.Fatalf("append after the concurrent writes returned error: %v", err)
	}

	got := gaugeNames(t, logs, "ingestauth_connlog_error", "ingestauth_connlog_recovered")
	if len(got) == 0 || got[len(got)-1] != "ingestauth_connlog_recovered" {
		t.Fatalf("connection log gauge events = %v, want alternating error/recovered ending with recovered", got)
	}
	for i, name := range got {
		want := "ingestauth_connlog_error"
		if i%2 == 1 {
			want = "ingestauth_connlog_recovered"
		}
		if name != want {
			t.Fatalf("connection log gauge event %d = %s, want %s (sequence %v)", i, name, want, got)
		}
	}
}

// 게이지 상태는 잠금 안에서 바뀌고 set · clear 로그는 그 차례대로 난다(결함 예측 P6 · P8): 여러 요청이 판정 불가 ·
// 판정 결과 · 기록 쓰기를 함께 내도 auth 게이지 이벤트는 degraded 로 시작해 번갈아 나고 경쟁이 없다(-race).
func TestHandlerGaugesUnderConcurrentRequests(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{})
	f.auth.setAnswer(func(token string) authAnswer {
		if token[len(token)-1]%2 == 1 {
			return answerUndecided(0)
		}
		return answerUsable(0)
	})
	const workers, perWorker = 8, 25
	var wg sync.WaitGroup
	for w := range workers {
		wg.Go(func() {
			for i := range perWorker {
				n := w*perWorker + i
				f.publish(t, waveToken(n), testConnID(n))
			}
		})
	}
	wg.Wait()

	got := gaugeNames(t, f.logs, "ingestauth_auth_degraded", "ingestauth_auth_recovered")
	if len(got) == 0 {
		t.Fatalf("auth gauge events = none, want alternating degraded/recovered")
	}
	for i, name := range got {
		want := "ingestauth_auth_degraded"
		if i%2 == 1 {
			want = "ingestauth_auth_recovered"
		}
		if name != want {
			t.Fatalf("auth gauge event %d = %s, want %s (sequence %v)", i, name, want, got)
		}
	}
	if n := f.auth.callCount(); n != workers*perWorker {
		t.Errorf("fake auth calls = %d, want %d", n, workers*perWorker)
	}
}
