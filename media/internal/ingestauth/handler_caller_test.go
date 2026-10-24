package ingestauth

import (
	"bytes"
	"encoding/base64"
	"io"
	"log/slog"
	"net/http"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// 호출자 Basic 검사의 시험이다(계약 D2 — MediaMTX 는 authHTTPAddress 의 URL 자격을 인증 요청마다 Authorization: Basic
// 으로 싣는다). 같은 netns 의 다른 프로세스도 TCP 피어가 루프백이라 출발지 검사만으로는 MediaMTX 와 가려지지 않는다 —
// 그래서 출발지 검사 다음에 자격을 본다. 실패는 401 본문 없음이고, 본문을 읽지 않으며 예산을 만지지 않는다.

// base64Of 는 s 의 표준 base64 다 — 쌍점 없는 Basic 자격을 만든다.
func base64Of(s string) string {
	return base64.StdEncoding.EncodeToString([]byte(s))
}

// withBasic 은 r 의 Authorization 을 value 로 바꾼다. value 가 비면 머리를 지운다.
func withBasic(r *http.Request, value string) *http.Request {
	if value == "" {
		r.Header.Del("Authorization")
		return r
	}
	r.Header.Set("Authorization", value)
	return r
}

// 자격이 없거나 · 깨졌거나 · 틀리면 action 과 무관하게 401(본문 없음)이고 ingestauth_caller_unauthenticated(WARN)에
// remote_ip · reason 을 싣는다. 사용자와 비번 가운데 하나만 틀려도 401 이다. 둘 다 맞으면 다음 행(본문 판정)으로 간다.
func TestHandlerChecksCallerBasic(t *testing.T) {
	bodies := map[string]mtxAuthBody{
		"api":     apiBody(testSelfIP.String(), testAPIUser, testAPIPass),
		"read":    {IP: testPublisherIP, Action: "read", Protocol: "srt"},
		"publish": publishBody(testTokenA, testConnID(1)),
	}
	for action, body := range bodies {
		for _, tc := range []struct {
			name       string
			auth       string // Authorization 머리 값 — 비면 없음
			wantCode   int
			wantReason string // caller_unauthenticated 의 reason — 401 일 때만
		}{
			{name: "자격_없음", wantCode: http.StatusUnauthorized, wantReason: "missing"},
			{name: "사용자만_틀림", auth: "Basic " + basicCredentials("other", testCallerPass), wantCode: http.StatusUnauthorized, wantReason: "mismatch"},
			{name: "비번만_틀림", auth: "Basic " + basicCredentials(testCallerUser, "other"), wantCode: http.StatusUnauthorized, wantReason: "mismatch"},
			{name: "Basic_아닌_scheme", auth: "Bearer " + basicCredentials(testCallerUser, testCallerPass), wantCode: http.StatusUnauthorized, wantReason: "malformed"},
			{name: "base64_깨짐", auth: "Basic !!!not-base64", wantCode: http.StatusUnauthorized, wantReason: "malformed"},
			{name: "쌍점_없음", auth: "Basic " + base64Of(testCallerUser+testCallerPass), wantCode: http.StatusUnauthorized, wantReason: "malformed"},
			{name: "둘_다_맞음", auth: "Basic " + basicCredentials(testCallerUser, testCallerPass), wantCode: http.StatusOK},
		} {
			t.Run(action+"/"+tc.name, func(t *testing.T) {
				f := newHandlerFixture(t, fixtureOptions{})
				rr := f.serve(withBasic(newAuthRequest(t, body), tc.auth))
				if rr.Code != tc.wantCode {
					t.Errorf("%s request with %s = %d, want %d", action, tc.name, rr.Code, tc.wantCode)
				}
				events := handlerEvents(t, f.logs, "ingestauth_caller_unauthenticated")
				if tc.wantReason == "" {
					if len(events) != 0 {
						t.Errorf("caller_unauthenticated events = %d, want 0", len(events))
					}
					return
				}
				if rr.Body.Len() != 0 {
					t.Errorf("401 body = %d bytes, want none", rr.Body.Len())
				}
				if len(events) != 1 || events[0].Level != "WARN" || events[0].Reason != tc.wantReason || events[0].RemoteIP != testCallerIP.String() {
					t.Errorf("caller_unauthenticated events = %+v, want one WARN with reason %s and remote_ip %s", events, tc.wantReason, testCallerIP)
				}
			})
		}
	}
}

// CallerBasicOff(명시 끔)면 Authorization 을 보지 않는다 — 없어도 다음 행으로 간다.
func TestHandlerSkipsCallerBasicWhenOff(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{callerBasicOff: true})
	if rr := f.serve(withBasic(newAuthRequest(t, mtxAuthBody{IP: testPublisherIP, Action: "read", Protocol: "srt"}), "")); rr.Code != http.StatusOK {
		t.Errorf("read without Authorization while caller Basic is off = %d, want 200", rr.Code)
	}
}

// 출발지 검사가 Basic 보다 먼저다 — 낯선 TCP 피어는 자격이 맞든 틀리든 403 이다.
func TestHandlerCallerIPBeforeBasic(t *testing.T) {
	for _, auth := range []string{"", "Basic " + basicCredentials(testCallerUser, testCallerPass), "Basic " + basicCredentials(testCallerUser, "other")} {
		f := newHandlerFixture(t, fixtureOptions{})
		r := withBasic(newAuthRequest(t, mtxAuthBody{IP: testPublisherIP, Action: "read", Protocol: "srt"}), auth)
		r.RemoteAddr = "192.0.2.11:40000"
		if rr := f.serve(r); rr.Code != http.StatusForbidden {
			t.Errorf("request from a foreign TCP peer (Authorization %d bytes) = %d, want 403", len(auth), rr.Code)
		}
		if n := len(handlerEvents(t, f.logs, "ingestauth_caller_unauthenticated")); n != 0 {
			t.Errorf("caller_unauthenticated events for a foreign TCP peer = %d, want 0 (403 comes first)", n)
		}
	}
}

// 호출자 Basic 의 401 은 예산을 만지지 않는다 — 출발지 403 과 같은 층이다(같은 netns 의 침해된 프로세스가 틀린 자격을
// 몰아 보내 모든 계정의 새 송출을 budget_exhausted 로 만들지 못하게). 틀린 자격 스물이 함께 오고 그 로그 줄이 줄마다
// 10ms 걸려 줄을 서도 잔액은 처음(1.375초) 그대로이고 resolve 는 0회다. 맞는 자격의 publish 는 지금처럼 걸린 시간을
// 뺀다(대조).
func TestHandlerUnauthenticatedCallerLeavesBudget(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{})
	slow := &clockedLog{rec: f.logs, clock: f.clock, event: []byte(`"msg":"ingestauth_caller_unauthenticated"`), step: 10 * time.Millisecond}
	f.h.log = slog.New(slog.NewJSONHandler(slow, &slog.HandlerOptions{Level: slog.LevelDebug}))
	const callers = 20
	start := make(chan struct{})
	var wg sync.WaitGroup
	for i := range callers {
		r := withBasic(newAuthRequest(t, publishBody(waveToken(i), testConnID(i))), "Basic "+basicCredentials(testCallerUser, "wrong"))
		wg.Go(func() {
			<-start
			if rr := f.serve(r); rr.Code != http.StatusUnauthorized {
				t.Errorf("auth request %d with a wrong caller Basic = %d, want 401", i, rr.Code)
			}
		})
	}
	close(start)
	wg.Wait()
	if n := len(handlerEvents(t, f.logs, "ingestauth_caller_unauthenticated")); n != callers {
		t.Fatalf("Setup: caller_unauthenticated events = %d, want %d", n, callers)
	}
	if got := balanceOf(f.h.budget); got != holdBurst {
		t.Errorf("balance after %d concurrent caller 401s = %v, want %v (401 은 예산을 만지지 않는다)", callers, got, holdBurst)
	}
	if n := f.auth.callCount(); n != 0 {
		t.Errorf("resolve calls after caller 401s = %d, want 0", n)
	}

	f.auth.answerAll(answerUsable(400 * time.Millisecond))
	if rr := f.publish(t, testTokenA, testConnID(callers)); rr.Code != http.StatusOK {
		t.Errorf("publish with the right caller Basic = %d, want 200", rr.Code)
	}
	if got, want := balanceOf(f.h.budget), 1175*time.Millisecond; got != want {
		t.Errorf("balance after a 0.4s publish = %v, want %v (1.375 + 0.2 − 0.4)", got, want)
	}
}

// countingBody 는 Read 가 불린 횟수를 세는 요청 본문이다.
type countingBody struct {
	r     io.Reader
	reads atomic.Int32
}

func (b *countingBody) Read(p []byte) (int, error) {
	b.reads.Add(1)
	return b.r.Read(p)
}

func (b *countingBody) Close() error { return nil }

// 호출자 Basic 이 틀리면 본문을 읽지 않는다 — 깨진 JSON 도 400 이 아니라 401 이다. 상태 코드만으로는 본문을 읽고도
// 401 을 낸 경우를 가리지 못하므로 읽기 횟수를 본다.
func TestHandlerUnauthenticatedCallerSkipsBody(t *testing.T) {
	for _, tc := range []struct {
		name string
		body []byte
	}{
		{name: "publish", body: mustJSON(t, publishBody(testTokenA, testConnID(1)))},
		{name: "깨진_JSON", body: []byte("not json")},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := newHandlerFixture(t, fixtureOptions{})
			body := &countingBody{r: bytes.NewReader(tc.body)}
			r := withBasic(newAuthRequest(t, ""), "Basic "+basicCredentials(testCallerUser, "wrong"))
			r.Body = body
			if rr := f.serve(r); rr.Code != http.StatusUnauthorized {
				t.Errorf("%s with a wrong caller Basic = %d, want 401", tc.name, rr.Code)
			}
			if n := body.reads.Load(); n != 0 {
				t.Errorf("body reads with a wrong caller Basic = %d, want 0", n)
			}
		})
	}
}
