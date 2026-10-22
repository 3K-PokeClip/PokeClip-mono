package ingestauth

import (
	"context"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

// publish 판정의 IP 한도 시험이다(트랙 B 설계 2-2 · 계획 4-2 의 H1–H9 · H7b). 판정 순서는 형식 → IP peek → 거부 목록 →
// 예산 → 요청 ctx → 실패 토큰 차례 → IP take → resolve 다. 가짜 auth 는 시험 시계만 옮기고 실제로 기다리지 않는다.

// 시험 송출자 주소다(문서용 대역 — RFC 5737). testPublisherIP 와 다르다.
const (
	testAttackerIP = "203.0.113.50"
	testOtherIP    = "203.0.113.60"
)

// wantReasonIPLimited 는 한도 거절의 reason 이다(닫힌 집합 — 설계 2-1 의 12). 상수가 아니라 글자로 견준다 — 로그의 값이
// 계약이다.
const wantReasonIPLimited = "ip_limited"

// ipTestConnSeq 는 publishFrom 이 매기는 연결 id 의 순번이다.
var ipTestConnSeq atomic.Int64

// testIPLimit 은 운영 기본값(B = 30 · r = 3초)이다.
var testIPLimit = IPLimit{Burst: testIPBurst, Interval: testIPInterval}

// newIPLimitFixture 는 IP 한도를 켠 처리기다.
func newIPLimitFixture(t *testing.T) *handlerFixture {
	t.Helper()
	return newHandlerFixture(t, fixtureOptions{ipLimit: testIPLimit})
}

// publishFrom 은 ip 가 보낸 token 의 SRT publish 인증을 처리기 전체에 보낸다. 연결 id 는 부를 때마다 새것이다.
func (f *handlerFixture) publishFrom(t *testing.T, ip, token string) *httptest.ResponseRecorder {
	t.Helper()
	b := publishBody(token, testConnID(int(ipTestConnSeq.Add(1))))
	b.IP = ip
	return f.serve(newAuthRequest(t, b))
}

// publishStatuses 는 ip 로 token 을 n 번 보내 200 · 401 수를 돌려준다.
func (f *handlerFixture) publishStatuses(t *testing.T, ip, token string, n int) (ok, denied int) {
	t.Helper()
	for range n {
		switch rr := f.publishFrom(t, ip, token); rr.Code {
		case http.StatusOK:
			ok++
		case http.StatusUnauthorized:
			denied++
		default:
			t.Fatalf("publish from %s = %d, want 200 or 401", ip, rr.Code)
		}
	}
	return ok, denied
}

// ipLimitedDenials 는 reason 이 ip_limited 인 publish_denied 줄을 등급별로 센다.
func ipLimitedDenials(t *testing.T, logs *logRecorder) map[string]int {
	t.Helper()
	n := make(map[string]int)
	for _, e := range handlerEvents(t, logs, "ingestauth_publish_denied") {
		if e.Reason == wantReasonIPLimited {
			n[e.Level]++
		}
	}
	return n
}

// H1 — valid:true 반복. B 번은 200 이고 B+1 번째는 401(본문 없음) · ip_limited 다. auth 호출은 B 번이다 — 성공(200)이
// 한도를 초기화하지 않는다(B12 · B13).
func TestHandlerIPLimitCountsUsableResolves(t *testing.T) {
	f := newIPLimitFixture(t)
	ok, denied := f.publishStatuses(t, testAttackerIP, testTokenA, testIPBurst)
	if ok != testIPBurst || denied != 0 {
		t.Fatalf("first %d publishes = %d ok, %d denied, want all ok", testIPBurst, ok, denied)
	}
	rr := f.publishFrom(t, testAttackerIP, testTokenA)
	if rr.Code != http.StatusUnauthorized || rr.Body.Len() != 0 {
		t.Errorf("publish %d = %d with %d body bytes, want 401 with no body", testIPBurst+1, rr.Code, rr.Body.Len())
	}
	if got := f.auth.callCount(); got != testIPBurst {
		t.Errorf("auth calls = %d, want %d", got, testIPBurst)
	}
	if got := ipLimitedDenials(t, f.logs); got["DEBUG"] != 1 {
		t.Errorf("ip_limited denials by level = %v, want one at DEBUG", got)
	}
}

// H2 — 없는 토큰(valid:false · not_found) 반복도 같다 — 실패만 세지 않고 resolve 마다 센다.
func TestHandlerIPLimitCountsRejectedResolves(t *testing.T) {
	f := newIPLimitFixture(t)
	f.auth.answerAll(answerRejected("NOT_FOUND", 0))
	f.publishStatuses(t, testAttackerIP, testTokenA, testIPBurst+10)
	if got := f.auth.callCount(); got != testIPBurst {
		t.Errorf("auth calls after %d not_found publishes = %d, want %d", testIPBurst+10, got, testIPBurst)
	}
	if got := ipLimitedDenials(t, f.logs); got["DEBUG"] != 10 {
		t.Errorf("ip_limited denials by level = %v, want 10 at DEBUG", got)
	}
}

// H3 — 한도 거절은 auth 를 부르지 않고 거부 목록보다 앞이다. 목록에 오른 토큰도 잠긴 IP 에서는 ip_limited 다(peek 가
// 거부 목록 앞 — 잠긴 IP 의 홍수가 거부 목록 파일을 매번 읽지 않는다).
func TestHandlerIPLimitPrecedesDenylist(t *testing.T) {
	f := newIPLimitFixture(t)
	f.publishStatuses(t, testAttackerIP, testTokenA, testIPBurst)
	f.writeDenyList(t, testTokenA+"\n")
	f.publishFrom(t, testAttackerIP, testTokenA)
	if e := lastEvent(t, f.logs, "ingestauth_publish_denied"); e.Reason != wantReasonIPLimited {
		t.Errorf("publish of a denylisted token from a limited IP = %v, want reason ip_limited", e)
	}
	if got := f.auth.callCount(); got != testIPBurst {
		t.Errorf("auth calls = %d, want %d", got, testIPBurst)
	}
}

// H4 — 예산 거절은 세지 않는다. 다른 IP 가 예산을 말리는 동안 온 시도 100건 뒤에도 그 IP 의 첫 시도는 한도에 걸리지
// 않는다(B10 — take 는 예산 · ctx · 차례 뒤).
func TestHandlerIPLimitSkipsBudgetDenials(t *testing.T) {
	f := newIPLimitFixture(t)
	f.auth.setAnswer(func(token string) authAnswer {
		if token == testTokenB {
			return answerUsable(5 * time.Second) // 예산을 바닥 밑까지 쓴다(1.375 + 보충 2.5 − 5 < 0)
		}
		return answerUsable(0)
	})
	f.publishFrom(t, testOtherIP, testTokenB)
	for range 100 {
		f.publishFrom(t, testAttackerIP, testTokenA)
	}
	// 첫 시도가 예산 거절이면 예산이 마른 것이다. 마지막 시도는 보지 않는다 — take 가 예산 앞에 있으면 30번 뒤
	// 한도 거절로 바뀌고, 그 결함은 아래 단언이 판별한다.
	if e := handlerEvents(t, f.logs, "ingestauth_publish_denied"); len(e) == 0 || e[0].Reason != reasonBudgetExhausted {
		t.Fatalf("Setup: the first publish while the budget is drained was not denied as budget_exhausted — events %v", eventNames(t, f.logs))
	}

	f.clock.advance(10 * time.Second) // 예산이 다시 찬다
	ok, denied := f.publishStatuses(t, testAttackerIP, testTokenA, testIPBurst)
	if ok != testIPBurst || denied != 0 {
		t.Errorf("publishes after 100 budget denials = %d ok, %d denied, want %d ok (budget denials do not count)", ok, denied, testIPBurst)
	}
}

// H5 — 차례 거절(recheck_wait)은 세지 않는다.
func TestHandlerIPLimitSkipsRecheckWaits(t *testing.T) {
	f := newIPLimitFixture(t)
	f.auth.setAnswer(func(token string) authAnswer {
		if token == testTokenB {
			return answerUndecided(0)
		}
		return answerUsable(0)
	})
	f.publishStatuses(t, testAttackerIP, testTokenB, 2) // 기억 · 재확인 — resolve 2번
	for range 100 {
		f.publishFrom(t, testAttackerIP, testTokenB)
	}
	if e := lastEvent(t, f.logs, "ingestauth_publish_denied"); e.Reason != reasonRecheckWait {
		t.Fatalf("Setup: publish of a remembered token inside the gap = %v, want recheck_wait", e)
	}

	ok, _ := f.publishStatuses(t, testAttackerIP, waveToken(1), testIPBurst)
	if want := testIPBurst - 2; ok != want {
		t.Errorf("usable publishes after 2 resolves and 100 recheck waits = %d ok, want %d", ok, want)
	}
}

// H5 의 둘째 — 잠긴 IP 의 기억 토큰은 재확인 칸을 쓰지 않는다. 다른 IP 의 같은 토큰이 곧바로 재확인을 받는다(B11 —
// peek 가 admit 앞).
func TestHandlerIPLimitLeavesRecheckSlot(t *testing.T) {
	f := newIPLimitFixture(t)
	f.auth.setAnswer(func(token string) authAnswer {
		if token == testTokenB {
			return answerUndecided(0)
		}
		return answerUsable(0)
	})
	f.publishStatuses(t, testAttackerIP, testTokenA, testIPBurst) // 잠근다
	f.publishFrom(t, testOtherIP, testTokenB)                     // 기억한다(칸은 열린 채)
	f.publishFrom(t, testAttackerIP, testTokenB)                  // 잠긴 IP — 칸을 쓰면 안 된다
	calls := f.auth.callCount()
	f.publishFrom(t, testPublisherIP, testTokenB)
	if e := lastEvent(t, f.logs, "ingestauth_publish_denied"); e.Reason == reasonRecheckWait {
		t.Errorf("recheck from a third IP after a limited IP tried the remembered token = %v, want the slot open", e)
	}
	if got := f.auth.callCount(); got != calls+1 {
		t.Errorf("auth calls for the recheck = %d, want 1", got-calls)
	}
}

// H6 — 한도 거절은 auth 게이지의 denied_during 에 들지 않는다(설계 2-1 의 15).
func TestHandlerIPLimitOutsideAuthGauge(t *testing.T) {
	f := newIPLimitFixture(t)
	f.auth.setAnswer(func(token string) authAnswer {
		if token == testTokenB {
			return answerUndecided(10 * time.Millisecond)
		}
		return answerUsable(0)
	})
	f.publishStatuses(t, testAttackerIP, testTokenA, testIPBurst) // 잠근다
	f.publishFrom(t, testOtherIP, testTokenB)                     // 게이지 set
	f.publishStatuses(t, testAttackerIP, testTokenA, 5)           // 한도 거절 5
	f.publishFrom(t, testOtherIP, waveToken(1))                   // 판정 결과 → clear
	if e := lastEvent(t, f.logs, "ingestauth_auth_recovered"); e.DeniedDuring != 1 {
		t.Errorf("auth_recovered denied_during = %d, want 1 (ip_limited denials are outside the gauge)", e.DeniedDuring)
	}
}

// H7 — 같은 시각의 홍수 1,000건은 한도 로그 한 줄이고 요청마다의 WARN 은 없다(B3).
func TestHandlerIPLimitLogsOncePerEpisode(t *testing.T) {
	f := newIPLimitFixture(t)
	f.publishStatuses(t, testAttackerIP, testTokenA, 1000)
	limited := handlerEvents(t, f.logs, "ingestauth_ip_limited")
	if len(limited) != 1 {
		t.Fatalf("ingestauth_ip_limited lines after a 1000-request flood = %d, want 1", len(limited))
	}
	if e := limited[0]; e.Level != "WARN" || e.IP != testAttackerIP {
		t.Errorf("ingestauth_ip_limited = %v, want WARN with ip %s", e, testAttackerIP)
	}
	if got := ipLimitedDenials(t, f.logs); got["WARN"] != 0 || got["DEBUG"] != 1000-testIPBurst {
		t.Errorf("ip_limited denials by level = %v, want 0 WARN and %d DEBUG", got, 1000-testIPBurst)
	}
}

// H7b — 지속 홍수. 0.5초마다 120초 동안 쏘면 잠금 줄 1 · 해제 줄 0 이고 허용은 B + ⌊120/r⌋ 이하다. 쏘기를 멈추고 통이
// 다 찰 때까지 밀어 한 번 쏘면 해제 줄 1 이다(B3 · 설계 용어 「에피소드」 — 변이 m16).
func TestHandlerIPLimitSustainedFlood(t *testing.T) {
	f := newIPLimitFixture(t)
	allowed := 0
	for step := 0; step < 240; step++ {
		f.clock.moveTo(time.Duration(step) * 500 * time.Millisecond)
		if f.publishFrom(t, testAttackerIP, testTokenA).Code == http.StatusOK {
			allowed++
		}
	}
	if limit := testIPBurst + int(120*time.Second/testIPInterval); allowed > limit {
		t.Errorf("allowed during a 120s flood = %d, want ≤ %d", allowed, limit)
	}
	if got := len(handlerEvents(t, f.logs, "ingestauth_ip_limited")); got != 1 {
		t.Errorf("ingestauth_ip_limited lines during a 120s flood = %d, want 1", got)
	}
	if got := len(handlerEvents(t, f.logs, "ingestauth_ip_limit_released")); got != 0 {
		t.Errorf("ingestauth_ip_limit_released lines during a 120s flood = %d, want 0", got)
	}

	f.clock.moveTo(400 * time.Second)
	if rr := f.publishFrom(t, testAttackerIP, testTokenA); rr.Code != http.StatusOK {
		t.Errorf("publish after the bucket refilled = %d, want 200", rr.Code)
	}
	released := handlerEvents(t, f.logs, "ingestauth_ip_limit_released")
	if len(released) != 1 || released[0].Level != "WARN" || released[0].IP != testAttackerIP {
		t.Errorf("ingestauth_ip_limit_released = %v, want one WARN line for %s", released, testAttackerIP)
	}
}

// H8 — api · read 행은 한도와 무관하다(설계 2-1 의 14).
func TestHandlerIPLimitIgnoresAPIAndRead(t *testing.T) {
	f := newIPLimitFixture(t)
	f.publishStatuses(t, testAttackerIP, testTokenA, testIPBurst+1)
	if rr := f.serve(newAuthRequest(t, apiBody(testSelfIP.String(), testAPIUser, testAPIPass))); rr.Code != http.StatusOK {
		t.Errorf("api from SelfIP while a publisher is limited = %d, want 200", rr.Code)
	}
	read := publishBody(testTokenA, testConnID(9999))
	read.IP, read.Action = testAttackerIP, "read"
	if rr := f.serve(newAuthRequest(t, read)); rr.Code != http.StatusOK {
		t.Errorf("read from the limited IP = %d, want 200", rr.Code)
	}
}

// H9 — 영값 Options 는 꺼짐이다 — 한 IP 로 publish 를 많이 쏘는 기존 시험이 그대로 돈다.
func TestHandlerIPLimitOffByDefault(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{})
	ok, denied := f.publishStatuses(t, testAttackerIP, testTokenA, 3*testIPBurst)
	if ok != 3*testIPBurst || denied != 0 {
		t.Errorf("publishes with the zero IPLimit = %d ok, %d denied, want all ok", ok, denied)
	}
	if got := len(handlerEvents(t, f.logs, "ingestauth_ip_limited")); got != 0 {
		t.Errorf("ingestauth_ip_limited lines with the zero IPLimit = %d, want 0", got)
	}
}

// newHandler 는 범위 밖 IPLimit 을 거부한다(설계 2-3). 나머지 설정은 유효하게 준다 — 호출자 Basic 까지 갖춰야
// 에러가 IPLimit 에서만 나온다(빠뜨리면 Basic 검사가 대신 거부해 IPLimit 검사가 없어도 통과한다). 먼저 같은 설정에 유효한
// IPLimit 을 주어 에러가 없음을 본다.
func TestNewHandlerRejectsInvalidIPLimit(t *testing.T) {
	newWith := func(cfg IPLimit) error {
		_, err := newHandler(newTestResolver(t, "http://192.0.2.30:8082"), NewDenyList(t.TempDir()+"/deny", discardLogger()), mustConnLog(t), Options{
			CallerIP:   testCallerIP,
			SelfIP:     testSelfIP,
			APIUser:    testAPIUser,
			APIPass:    NewSecret(testAPIPass),
			IPLimit:    cfg,
			CallerUser: testCallerUser,
			CallerPass: NewSecret(testCallerPass),
		})
		return err
	}
	if err := newWith(testIPLimit); err != nil {
		t.Fatalf("Setup: newHandler with a valid IPLimit returned error: %v", err)
	}
	for _, cfg := range []IPLimit{
		{Burst: 30},
		{Interval: 3 * time.Second},
		{Burst: IPLimitBurstMax + 1, Interval: 3 * time.Second},
		{Burst: 30, Interval: IPLimitIntervalMax + time.Nanosecond},
	} {
		if err := newWith(cfg); err == nil {
			t.Errorf("newHandler(IPLimit%+v) returned nil error, want an error", cfg)
		}
	}
}

// stallingLogHandler 는 메시지가 stallOn 인 줄을 내기 전에 stall 만큼 멈추는 slog 처리기다 — 로그 쓰기 대기(디스크 ·
// 로그 드라이버)를 흉내 낸다.
type stallingLogHandler struct {
	slog.Handler
	stallOn string
	stall   time.Duration
}

func (s stallingLogHandler) Handle(ctx context.Context, r slog.Record) error {
	if r.Message == s.stallOn {
		time.Sleep(s.stall)
	}
	return s.Handler.Handle(ctx, r)
}

func (s stallingLogHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	return stallingLogHandler{Handler: s.Handler.WithAttrs(attrs), stallOn: s.stallOn, stall: s.stall}
}

func (s stallingLogHandler) WithGroup(name string) slog.Handler {
	return stallingLogHandler{Handler: s.Handler.WithGroup(name), stallOn: s.stallOn, stall: s.stall}
}

// IP take 의 동기 로그가 resolve 시한을 넘기면 auth 에 묻지 않고 publish_late 로 끝난다 — 시한이 지난 resolve 는 auth 에
// 닿지도 않고 판정 불가가 되어, 헛된 auth_degraded 와 그 토큰의 기억을 남긴다. 상한 0 이라 take 가 처음에
// ip_limit_full 을 내고, 그 줄이 시한을 넘겨 멈춘다.
func TestHandlerIPTakeLogPastDeadlineIsLate(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{deadline: guardDeadline, ipLimit: testIPLimit})
	f.h.ipLimit.limit = 0
	f.h.log = slog.New(stallingLogHandler{
		Handler: f.h.log.Handler(),
		stallOn: "ingestauth_ip_limit_full",
		stall:   guardDeadline + guardMargin + 100*time.Millisecond,
	})

	f.publishFrom(t, testAttackerIP, testTokenA)

	if end := waitForInnerEnd(t, f.logs); end != "ingestauth_publish_late" {
		t.Errorf("publish after a stalled take log ended with %s, want ingestauth_publish_late", end)
	}
	if n := f.auth.callCount(); n != 0 {
		t.Errorf("auth calls = %d, want 0", n)
	}
	if _, ok := entryOf(f.h.failed, testTokenA); ok {
		t.Errorf("token remembered as failed after a late publish, want no entry")
	}
	if got := f.logs.events(t, "ingestauth_auth_degraded"); len(got) != 0 {
		t.Errorf("auth_degraded lines = %d, want 0", len(got))
	}
}

// 출발지 403 · 호출자 Basic 401 은 본문을 읽기 전이라 송출자 IP 한도를 쓰지 않는다 — 같은 본문 ip 로 한도의 두 배를
// 보낸 뒤에도 정상 자격의 그 ip 첫 publish 는 200 이다(트랙 A 계획 8절 · 트랙 B 설계 2-2).
func TestHandlerCallerRejectionsDoNotSpendIPLimit(t *testing.T) {
	f := newIPLimitFixture(t)
	body := publishBody(testTokenA, testConnID(1))
	body.IP = testAttackerIP
	for range 2 * testIPBurst {
		foreign := newAuthRequest(t, body)
		foreign.RemoteAddr = "192.0.2.11:40000"
		if rr := f.serve(foreign); rr.Code != http.StatusForbidden {
			t.Fatalf("Setup: foreign caller = %d, want 403", rr.Code)
		}
		badBasic := withBasic(newAuthRequest(t, body), "Basic "+basicCredentials(testCallerUser, "other"))
		if rr := f.serve(badBasic); rr.Code != http.StatusUnauthorized {
			t.Fatalf("Setup: wrong caller Basic = %d, want 401", rr.Code)
		}
	}

	if rr := f.publishFrom(t, testAttackerIP, testTokenA); rr.Code != http.StatusOK {
		t.Errorf("first authenticated publish from %s after %d rejected callers = %d, want 200", testAttackerIP, 4*testIPBurst, rr.Code)
	}
}

// mustConnLog 는 임시 디렉터리의 허용 연결 기록이다.
func mustConnLog(t *testing.T) *ConnLog {
	t.Helper()
	conns, _ := openTestConnLog(t, t.TempDir(), 1<<20, newFakeClock())
	return conns
}
