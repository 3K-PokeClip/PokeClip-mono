package ingestauth

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"runtime"
	"slices"
	"sync"
	"testing"
	"time"
)

// yieldingHandler 는 publish_denied 기록을 안쪽 처리기에 넘기기 전에 다른 고루틴에 차례를 넘긴다 — 차례 넘기기가 안쪽
// 처리기의 쓰기 잠금 앞이라, 거절 로그를 내는 자리와 그 앞의 게이지 잠금 사이의 틈을 넓힌다(helpers_test 의
// fakeClock.Now 와 같은 수). 게이지 로그(degraded · recovered)에서는 넘기지 않는다 — 넘기면 끼어든 clear 의 로그도 함께
// 늦어 틈이 도로 좁아진다.
type yieldingHandler struct{ slog.Handler }

func (y yieldingHandler) Handle(ctx context.Context, r slog.Record) error {
	if r.Message == "ingestauth_publish_denied" {
		for range 3 {
			runtime.Gosched()
		}
	}
	return y.Handler.Handle(ctx, r)
}

func (y yieldingHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	return yieldingHandler{y.Handler.WithAttrs(attrs)}
}

func (y yieldingHandler) WithGroup(name string) slog.Handler {
	return yieldingHandler{y.Handler.WithGroup(name)}
}

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

// auth 게이지 짝(설계 6절 게이지 표 · 2-4 713행): 처음 상태(판정됨)에서 첫 판정 불가면 auth_degraded(reason ·
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
		{at: 11 * time.Second, token: tokB, wantReason: "undecided"},   // 다시 판정 불가 → 다시 set(t=12.125 — 셈은 0 에서)
		{at: 13 * time.Second, token: tokC, wantReason: ""},            // 판정 결과 → 다시 clear(t=13.03)
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
		[]string{"ingestauth_auth_degraded", "ingestauth_auth_recovered", "ingestauth_auth_degraded", "ingestauth_auth_recovered"}; !slices.Equal(got, want) {
		t.Fatalf("auth gauge events = %v, want %v (예산 · 차례 거절은 게이지를 바꾸지 않는다)", got, want)
	}
	degraded := handlerEvents(t, f.logs, "ingestauth_auth_degraded")[0]
	if degraded.Level != "ERROR" || degraded.Reason != "status" || degraded.Status != http.StatusInternalServerError {
		t.Errorf("auth_degraded = %v, want ERROR with reason status and status 500", degraded)
	}
	// 둘째 짝의 셈은 둘째 set 에서 0 으로 시작한다 — 첫 짝의 거절이 쌓이지 않는다.
	recovered := handlerEvents(t, f.logs, "ingestauth_auth_recovered")
	for i, want := range []handlerEvent{
		{Level: "ERROR", DownFor: int64(8905 * time.Millisecond), DeniedDuring: 5, BudgetDenied: 1, RecheckDenied: 1}, // set t=1.125 · clear t=10.03
		{Level: "ERROR", DownFor: int64(905 * time.Millisecond), DeniedDuring: 1},                                     // set t=12.125 · clear t=13.03
	} {
		got := recovered[i]
		if got.Level != want.Level || got.DownFor != want.DownFor || got.DeniedDuring != want.DeniedDuring ||
			got.BudgetDenied != want.BudgetDenied || got.RecheckDenied != want.RecheckDenied {
			t.Errorf("auth_recovered[%d] = %v, want level %s, down_for %v, denied_during %d, budget_denied %d, recheck_denied %d",
				i, got, want.Level, time.Duration(want.DownFor), want.DeniedDuring, want.BudgetDenied, want.RecheckDenied)
		}
	}
}

// 거부 목록 짝(설계 6절 · 2-4 713행): 파일 권한을 뺐다가 되돌리면 denylist_error · denylist_recovered 가 같은
// 등급(ERROR)으로 한 번씩 난다. 이 짝은 DenyList 가 내고 처리기는 다시 내지 않는다.
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

// 기록 짝(설계 6절 「쓰기 실패 → set · 첫 쓰기 성공 → clear」 · 2-4 713행): 기록 디렉터리 쓰기 권한을 뺐다가
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

// 기록 게이지는 쓰기와 전이를 잠금 하나로 묶는다: 실패하는 쓰기와 성공하는 쓰기가 여러 고루틴에서
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

// 판정 불가로 끝난 거절의 게이지 set · 그 거절의 셈 · publish_denied 로그는 한 차례로 난다(설계 6절 「denied_during 은
// 게이지가 선 동안(set 을 낸 요청 포함) publish_denied 로 끝난 publish 수」). 판정 불가와 쓸 수 있는 값이 여러
// 고루틴에서 함께 와도, auth_degraded 에서 다음 auth_recovered 까지 난 publish_denied 줄 수가 그 recovered 의
// denied_during 과 같고, 세운 요청이 늘 들어 1 이상이다. 세운 요청의 셈이 다른 요청의 clear 뒤로 밀리면 0 이 되고, 거절
// 로그가 셈과 떨어지면 줄 수와 셈이 어긋난다. 판마다 출발 장벽을 함께 지나 겹침이 스케줄에 기대지 않고, 로그 처리기가
// 거절 기록마다 차례를 넘겨(yieldingHandler) 거절 로그를 내는 자리와 게이지 잠금 사이의 틈을 넓힌다(-race).
func TestHandlerAuthGaugeCountsEveryDenialWhileDegraded(t *testing.T) {
	if p := runtime.GOMAXPROCS(0); p < 2 {
		t.Skipf("GOMAXPROCS=%d — 게이지 경쟁은 P 가 둘 이상일 때만 가른다", p)
	}
	f := newHandlerFixture(t, fixtureOptions{})
	log := slog.New(yieldingHandler{slog.NewJSONHandler(f.logs, &slog.HandlerOptions{Level: slog.LevelDebug})})
	f.h.log, f.h.authGauge.log = log, log // 거절 로그와 게이지 로그가 한 처리기로 줄을 선다
	f.auth.setAnswer(func(token string) authAnswer {
		if token[len(token)-1]%2 == 1 {
			return answerUndecided(0)
		}
		return answerUsable(0)
	})
	const rounds, workers = 200, 8
	for round := range rounds {
		start := make(chan struct{})
		var wg sync.WaitGroup
		for w := range workers {
			n := round*workers + w
			r := newAuthRequest(t, publishBody(waveToken(n), testConnID(n)))
			wg.Go(func() {
				<-start
				f.serve(r)
			})
		}
		close(start)
		wg.Wait()
	}

	degraded, segments := false, 0
	denied := 0 // 지금 구간(degraded 뒤)에 난 publish_denied 줄 수
	for i, e := range handlerEvents(t, f.logs, "") {
		switch e.Msg {
		case "ingestauth_auth_degraded":
			degraded, denied = true, 0
		case "ingestauth_publish_denied":
			if degraded {
				denied++
			}
		case "ingestauth_auth_recovered":
			segments++
			if e.DeniedDuring != denied || e.DeniedDuring < 1 {
				t.Errorf("event %d: auth_recovered denied_during = %d after %d publish_denied lines since auth_degraded, want equal and at least 1", i, e.DeniedDuring, denied)
			}
			degraded = false
		}
	}
	if segments == 0 {
		t.Errorf("auth gauge segments = 0, want degraded/recovered pairs (판정 불가와 판정 결과가 섞이지 않았다)")
	}
}

// 게이지 상태는 잠금 안에서 바뀌고 set · clear 로그는 그 차례대로 난다: 여러 요청이 판정 불가 ·
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
