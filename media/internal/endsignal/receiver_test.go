package endsignal

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/keyresolve"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
)

// New 는 빈 함수 칸 · 0 이하 시한 · nil 계수기 · nil 로거를 거부한다 — 조립이 기동을 멈춘다(설계 2절 cc L9ⓒ).
func TestNewRejectsIncompleteOptions(t *testing.T) {
	tests := []struct {
		name string
		edit func(*Options)
	}{
		{name: "Resolve", edit: func(o *Options) { o.Resolve = nil }},
		{name: "Apply", edit: func(o *Options) { o.Apply = nil }},
		{name: "Changed", edit: func(o *Options) { o.Changed = nil }},
		{name: "PostIntent", edit: func(o *Options) { o.PostIntent = nil }},
		{name: "Now", edit: func(o *Options) { o.Now = nil }},
		{name: "Log", edit: func(o *Options) { o.Log = nil }},
		{name: "BodyTimeout_0", edit: func(o *Options) { o.BodyTimeout = 0 }},
		{name: "ResolveTimeout_음수", edit: func(o *Options) { o.ResolveTimeout = -time.Second }},
		{name: "ApplyTimeout_0", edit: func(o *Options) { o.ApplyTimeout = 0 }},
		{name: "PerKey", edit: func(o *Options) { o.PerKey = nil }},
		{name: "Global", edit: func(o *Options) { o.Global = nil }},
	}
	for _, tc := range tests {
		f := &fixture{t: t, log: &syncBuffer{}}
		o := f.testOptions()
		tc.edit(&o)
		if _, err := New(o); err == nil {
			t.Errorf("%s: New 가 빈 칸을 받아들였다", tc.name)
		}
	}
	f := &fixture{t: t, log: &syncBuffer{}}
	if _, err := New(f.testOptions()); err != nil {
		t.Fatalf("완전한 Options 를 거부했다: %v", err)
	}
}

func TestRoutePattern(t *testing.T) {
	f := newFixture(t, nil)
	if got, want := f.recv.Route().Pattern, "POST /api/ingest/end-signal"; got != want {
		t.Fatalf("Pattern = %q, want %q", got, want)
	}
}

// 정상 요청은 202 · 본문 없음이고, Apply 는 장부 stream_id = 토큰 · 수신 시각에서 되살린 두 시각을 받는다(설계 3절 8).
func TestAcceptedSignalAppliesAndMarks(t *testing.T) {
	f := newFixture(t, nil)
	resp := f.postOK(testToken)
	if resp.status != http.StatusAccepted || resp.body != "" {
		t.Fatalf("응답 = %d %q, want 202 빈 본문", resp.status, resp.body)
	}
	changed, posted, applied := f.snapshot()
	if len(applied) != 1 {
		t.Fatalf("Apply 호출 = %d, want 1", len(applied))
	}
	wantStopped := testNow.Add(-420 * time.Millisecond)
	want := session.IntentionalEnd{StreamID: testToken, StoppedAt: wantStopped, ConnStartedAt: wantStopped.Add(-1834 * time.Second)}
	if applied[0] != want {
		t.Errorf("Apply 입력 = %+v, want %+v", applied[0], want)
	}
	if len(changed) != 1 || changed[0] != testToken {
		t.Errorf("Changed = %v, want [%s]", changed, testToken)
	}
	if len(posted) != 0 {
		t.Errorf("PostIntent = %v, want 없음", posted)
	}
	if l := f.lastEndSignal(); l.Status != 202 || l.Reason != "accepted" || l.StreamID != testToken || l.Level != "INFO" {
		t.Errorf("end_signal 줄 = %+v", l)
	}
}

// 형식 오류는 400 이고 4C 를 부르지 않는다. Authorization 이 틀려도 400 이 먼저다(계약4 3-1 · 설계 7절 P10 · P11).
// 미래 시각 신호(elapsed 음수 — 멈춘 순간이 수신보다 뒤)도 400 이다(M6-3a 보안 검수 ①).
func TestMalformedBodyIs400BeforeAuth(t *testing.T) {
	sid := `"#!::r=` + testToken + `,m=publish"`
	tests := []struct {
		name string
		body string
	}{
		{name: "JSON_아님", body: `not json`},
		{name: "빈_본문", body: ``},
		{name: "배열", body: `[]`},
		{name: "뒤_쓰레기", body: signalBody(testToken, 420, 1000) + `x`},
		{name: "streamid_누락", body: `{"elapsedSinceStopMs":420,"connectionDurationMs":1000}`},
		{name: "elapsed_누락", body: `{"streamid":` + sid + `,"connectionDurationMs":1000}`},
		{name: "duration_누락", body: `{"streamid":` + sid + `,"elapsedSinceStopMs":420}`},
		{name: "elapsed_null", body: `{"streamid":` + sid + `,"elapsedSinceStopMs":null,"connectionDurationMs":1000}`},
		{name: "duration_null", body: `{"streamid":` + sid + `,"elapsedSinceStopMs":420,"connectionDurationMs":null}`},
		{name: "streamid_null", body: `{"streamid":null,"elapsedSinceStopMs":420,"connectionDurationMs":1000}`},
		{name: "elapsed_문자열", body: `{"streamid":` + sid + `,"elapsedSinceStopMs":"420","connectionDurationMs":1000}`},
		{name: "elapsed_소수", body: `{"streamid":` + sid + `,"elapsedSinceStopMs":420.5,"connectionDurationMs":1000}`},
		{name: "duration_문자열", body: `{"streamid":` + sid + `,"elapsedSinceStopMs":420,"connectionDurationMs":"1"}`},
		{name: "streamid_숫자", body: `{"streamid":1,"elapsedSinceStopMs":420,"connectionDurationMs":1000}`},
		{name: "이름_streamId", body: `{"streamId":` + sid + `,"elapsedSinceStopMs":420,"connectionDurationMs":1000}`},
		{name: "elapsed_음수_1", body: signalBody(testToken, -1, 1000)},
		{name: "미래_시각_1시간", body: signalBody(testToken, -3600000, 1000)},
		{name: "elapsed_60001", body: signalBody(testToken, 60001, 1000)},
		{name: "duration_음수", body: signalBody(testToken, 420, -1)},
		{name: "duration_int64_밖", body: `{"streamid":` + sid + `,"elapsedSinceStopMs":420,"connectionDurationMs":9223372036854775808}`},
		{name: "r_둘", body: `{"streamid":"#!::r=` + testTokenB + `,r=` + testToken + `,m=publish","elapsedSinceStopMs":420,"connectionDurationMs":1000}`},
		{name: "토큰_소문자", body: `{"streamid":"#!::r=` + strings.ToLower(testToken) + `,m=publish","elapsedSinceStopMs":420,"connectionDurationMs":1000}`},
		{name: "m_request", body: `{"streamid":"#!::r=` + testToken + `,m=request","elapsedSinceStopMs":420,"connectionDurationMs":1000}`},
		{name: "streamid_앞_공백", body: `{"streamid":" #!::r=` + testToken + `,m=publish","elapsedSinceStopMs":420,"connectionDurationMs":1000}`},
		{name: "4KiB_초과", body: `{"pad":"` + strings.Repeat("a", 4<<10) + `","streamid":` + sid + `,"elapsedSinceStopMs":420,"connectionDurationMs":1000}`},
	}
	for _, auth := range []string{"Bearer " + testBearer, "", "Basic eDp5"} {
		for _, tc := range tests {
			f := newFixture(t, nil)
			resp := f.post(tc.body, auth)
			if resp.status != http.StatusBadRequest {
				t.Errorf("%s (auth %q): 상태 = %d, want 400", tc.name, auth, resp.status)
				continue
			}
			if n := f.resolveCalls.Load(); n != 0 {
				t.Errorf("%s: resolve 호출 = %d, want 0", tc.name, n)
			}
			if l := f.lastEndSignal(); l.Reason != "bad_request" || l.Level != "WARN" {
				t.Errorf("%s: end_signal 줄 = %+v", tc.name, l)
			}
			if v, has := f.lastEndSignalKeys()["stream_id"]; has {
				t.Errorf("%s: end_signal 줄에 stream_id 키 = %s — 400 은 그 키를 싣지 않는다(빈 값도 아니다)", tc.name, v)
			}
		}
	}
}

// 모르는 필드는 무시한다(계약4 3-1). 4KiB 안의 본문이면 받는다.
func TestUnknownFieldsIgnored(t *testing.T) {
	f := newFixture(t, nil)
	body := `{"extra":{"a":[1,2]},"streamid":"#!::r=` + testToken + `,m=publish","elapsedSinceStopMs":0,"connectionDurationMs":0,"z":"y"}`
	if resp := f.post(body, "Bearer "+testBearer); resp.status != http.StatusAccepted {
		t.Fatalf("상태 = %d, want 202", resp.status)
	}
}

// Authorization 꼴이 틀리면 401 이고 4C 를 부르지 않는다(설계 3절 2). 값 끝 공백은 HTTP 층이 걷어 내므로(RFC 9110 5.5)
// 여기 두지 않고 TestParseBearer 가 본다.
func TestBadAuthorizationFormIs401WithoutResolve(t *testing.T) {
	tests := []string{
		"",
		"Basic " + testBearer,
		"Bearer",
		"Bearer ",
		"Bearer  " + testBearer,
		"Bearer " + testBearer[:63],
		"Bearer " + testBearer + "0",
		"Bearer " + testBearer[:63] + "g",
		"Bearer:" + testBearer,
		"Token " + testBearer,
	}
	for _, auth := range tests {
		f := newFixture(t, nil)
		resp := f.post(signalBody(testToken, 420, 1000), auth)
		if resp.status != http.StatusUnauthorized {
			t.Errorf("auth %q: 상태 = %d, want 401", auth, resp.status)
			continue
		}
		if n := f.resolveCalls.Load(); n != 0 {
			t.Errorf("auth %q: resolve 호출 = %d, want 0", auth, n)
		}
		if l := f.lastEndSignal(); l.Reason != "bad_auth_form" || l.StreamID != testToken {
			t.Errorf("auth %q: end_signal 줄 = %+v", auth, l)
		}
	}
}

// 계약4 3-1 시험 벡터 — 소문자 · 대문자 16진 · 스킴 대소문자 무관(RFC 9110 11.1).
func TestContractTestVectorAccepted(t *testing.T) {
	for _, auth := range []string{
		"Bearer " + testBearer,
		"Bearer " + strings.ToUpper(testBearer),
		"bearer " + testBearer,
		"BEARER " + testBearer,
		"bEaReR " + strings.ToUpper(testBearer[:32]) + testBearer[32:],
	} {
		f := newFixture(t, nil)
		if resp := f.post(signalBody(testToken, 420, 1000), auth); resp.status != http.StatusAccepted {
			t.Errorf("auth %q: 상태 = %d, want 202", auth, resp.status)
		}
	}
}

// 4C 의 「키 틀림」(valid:false)은 401, 「판단 불가」와 형식 밖 passphrase · 그 밖 오류는 503 이다(설계 7절 P12 ·
// 계약4 2절 「반드시 갈라 처리」). 어느 쪽이든 Apply 는 부르지 않는다.
func TestResolveOutcomes(t *testing.T) {
	tests := []struct {
		name       string
		verdict    keyresolve.Verdict
		err        error
		wantStatus int
		wantReason string
		wantKind   string
	}{
		{name: "MALFORMED", verdict: keyresolve.Verdict{Reason: "MALFORMED"}, wantStatus: 401, wantReason: "key_rejected"},
		{name: "NOT_FOUND", verdict: keyresolve.Verdict{Reason: "NOT_FOUND"}, wantStatus: 401, wantReason: "key_rejected"},
		{name: "REVOKED", verdict: keyresolve.Verdict{Reason: "REVOKED"}, wantStatus: 401, wantReason: "key_rejected"},
		{name: "timeout", err: &keyresolve.UndecidedError{Kind: "timeout"}, wantStatus: 503, wantReason: "resolve_undecided", wantKind: "timeout"},
		{name: "unreachable", err: &keyresolve.UndecidedError{Kind: "unreachable"}, wantStatus: 503, wantReason: "resolve_undecided", wantKind: "unreachable"},
		{name: "status", err: &keyresolve.UndecidedError{Kind: "status", Status: 500}, wantStatus: 503, wantReason: "resolve_undecided", wantKind: "status"},
		{name: "parse", err: &keyresolve.UndecidedError{Kind: "parse"}, wantStatus: 503, wantReason: "resolve_undecided", wantKind: "parse"},
		{name: "형식_밖_passphrase", err: keyresolve.ErrInvalidPassphrase, wantStatus: 503, wantReason: "resolve_undecided", wantKind: "invalid_passphrase"},
		{name: "그_밖_오류", err: errors.New("x"), wantStatus: 503, wantReason: "resolve_undecided", wantKind: "error"},
	}
	for _, tc := range tests {
		f := newFixture(t, nil)
		f.resolve = func(context.Context, string) (keyresolve.Verdict, error) { return tc.verdict, tc.err }
		resp := f.postOK(testToken)
		if resp.status != tc.wantStatus {
			t.Errorf("%s: 상태 = %d, want %d", tc.name, resp.status, tc.wantStatus)
			continue
		}
		if n := f.applyCalls.Load(); n != 0 {
			t.Errorf("%s: Apply 호출 = %d, want 0", tc.name, n)
		}
		if l := f.lastEndSignal(); l.Reason != tc.wantReason || l.Kind != tc.wantKind || l.StreamID != testToken {
			t.Errorf("%s: end_signal 줄 = %+v", tc.name, l)
		}
	}
}

// 파생 값이 틀리면 401 mismatch 이고 Apply 를 부르지 않는다.
func TestMismatchedBearerIs401(t *testing.T) {
	f := newFixture(t, nil)
	if resp := f.post(signalBody(testToken, 420, 1000), "Bearer "+wrongBearer); resp.status != http.StatusUnauthorized {
		t.Fatalf("상태 = %d, want 401", resp.status)
	}
	if f.applyCalls.Load() != 0 {
		t.Fatalf("Apply 를 불렀다")
	}
	if l := f.lastEndSignal(); l.Reason != "mismatch" {
		t.Fatalf("end_signal 줄 = %+v", l)
	}
}

// 같은 키의 같은 1초 창 둘째 요청은 인증 뒤 429 다(설계 3절 7).
func TestPerKeyLimitIs429AfterAuth(t *testing.T) {
	f := newFixture(t, nil)
	if resp := f.postOK(testToken); resp.status != http.StatusAccepted {
		t.Fatalf("첫 요청 = %d, want 202", resp.status)
	}
	if resp := f.postOK(testToken); resp.status != http.StatusTooManyRequests {
		t.Fatalf("둘째 요청 = %d, want 429", resp.status)
	}
	if n := f.resolveCalls.Load(); n != 2 {
		t.Errorf("resolve 호출 = %d, want 2(429 는 인증 뒤)", n)
	}
	if n := f.applyCalls.Load(); n != 1 {
		t.Errorf("Apply 호출 = %d, want 1", n)
	}
	if l := f.lastEndSignal(); l.Reason != "rate_key" || l.Status != 429 {
		t.Errorf("end_signal 줄 = %+v", l)
	}
	if resp := f.postOK(testTokenB); resp.status != http.StatusAccepted {
		t.Errorf("다른 키 = %d, want 202", resp.status)
	}
}

// 표지 규칙(설계 7절 P13 · M6 11절): Apply 오류면 커밋 결과를 몰라 Changed 를 부르고 PostIntent 는 부르지 않는다.
// 성공 + 효과면 Changed, 성공 + 미적용이면 둘 다 없다. RevokeSession 이 있으면 PostIntent 다. 미적용 가운데 contended
// 만 503 이다(플러그인이 재시도) — 커밋 전에 접혀 표지도 없다. bad_time 은 다시 보내도 같아 202 다. 변이 = contended
// 매핑 제거 → 202 라 FAIL.
func TestMarkersFollowApplyResult(t *testing.T) {
	tests := []struct {
		name        string
		res         session.IntentionalResult
		err         error
		wantStatus  int
		wantReason  string
		wantChanged int
		wantPosted  []session.RevokeIntent
	}{
		{name: "오류", err: errors.New("pg: 연결 끊김"), wantStatus: 503, wantReason: "apply_failed", wantChanged: 1},
		{name: "효과", res: session.IntentionalResult{Effect: "upgraded"}, wantStatus: 202, wantReason: "accepted", wantChanged: 1},
		{name: "미적용", res: session.IntentionalResult{Skip: "round_closed"}, wantStatus: 202, wantReason: "accepted"},
		{name: "미적용_시각_거부", res: session.IntentionalResult{Skip: "bad_time"}, wantStatus: 202, wantReason: "accepted"},
		{name: "미적용_경합", res: session.IntentionalResult{SessionID: "S1", Skip: "contended"}, wantStatus: 503, wantReason: "contended"},
		{
			name: "분리_계승_해제", res: session.IntentionalResult{Effect: "round_split", RevokeSession: "S9"},
			wantStatus: 202, wantReason: "accepted", wantChanged: 1,
			wantPosted: []session.RevokeIntent{{StreamID: testToken, SessionID: "S9"}},
		},
	}
	for _, tc := range tests {
		f := newFixture(t, nil)
		f.apply = func(context.Context, session.IntentionalEnd) (session.IntentionalResult, error) {
			return tc.res, tc.err
		}
		resp := f.postOK(testToken)
		if resp.status != tc.wantStatus {
			t.Errorf("%s: 상태 = %d, want %d", tc.name, resp.status, tc.wantStatus)
		}
		changed, posted, _ := f.snapshot()
		if len(changed) != tc.wantChanged {
			t.Errorf("%s: Changed = %v, want %d 번", tc.name, changed, tc.wantChanged)
		}
		if len(posted) != len(tc.wantPosted) || (len(posted) == 1 && posted[0] != tc.wantPosted[0]) {
			t.Errorf("%s: PostIntent = %v, want %v", tc.name, posted, tc.wantPosted)
		}
		if l := f.lastEndSignal(); l.Reason != tc.wantReason {
			t.Errorf("%s: end_signal 줄 = %+v", tc.name, l)
		}
	}
}

// 실 Apply 는 Apply 시한(1.5s)에 걸린 위상 잠금 대기를 오류가 아니라 Skip contended 로 돌려준다. 수신부는 그것도
// 503 apply_timeout 으로 돌려 플러그인 재시도를 남긴다(POK-472 P4 와 같은 응답). 변이 = contended 매핑 제거 → 202 라 FAIL.
func TestContendedAtApplyDeadlineIs503ApplyTimeout(t *testing.T) {
	f := newFixture(t, nil)
	f.apply = func(ctx context.Context, _ session.IntentionalEnd) (session.IntentionalResult, error) {
		<-ctx.Done()
		return session.IntentionalResult{Skip: "contended"}, nil
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
	if changed, _, _ := f.snapshot(); len(changed) != 0 {
		t.Errorf("Changed = %v, want 없음(커밋 전에 접혔다)", changed)
	}
}

// 응답 본문에는 Apply 오류 원문(stream_id · pg 오류)이 없다 — 고정 문구다(M6-3a 보안 검수 ③). 원문은 서버 로그에만 간다.
func TestErrorBodiesAreFixed(t *testing.T) {
	f := newFixture(t, nil)
	f.apply = func(context.Context, session.IntentionalEnd) (session.IntentionalResult, error) {
		return session.IntentionalResult{}, errors.New(`4D 적용 확정 실패 stream_id="` + testToken + `": ERROR: deadlock detected (SQLSTATE 40P01)`)
	}
	resp := f.postOK(testToken)
	if resp.status != http.StatusServiceUnavailable {
		t.Fatalf("상태 = %d, want 503", resp.status)
	}
	if strings.Contains(resp.body, testToken) || strings.Contains(resp.body, "SQLSTATE") || strings.Contains(resp.body, "deadlock") {
		t.Fatalf("응답 본문에 오류 원문이 있다")
	}
	if l := f.lastEndSignal(); l.Level != "ERROR" || !strings.Contains(f.log.String(), "SQLSTATE") {
		t.Fatalf("서버 로그에 apply_failed 원문이 없다: %+v", l)
	}
	want := map[int]string{
		400: `{"error":"bad_request"}`, 401: `{"error":"unauthorized"}`, 429: `{"error":"rate_limited"}`,
		503: `{"error":"unavailable"}`,
	}
	got := map[int]string{503: strings.TrimSpace(resp.body)}
	g := newFixture(t, nil)
	got[400] = strings.TrimSpace(g.post(`x`, "").body)
	got[401] = strings.TrimSpace(g.post(signalBody(testToken, 1, 1), "").body)
	g.postOK(testTokenB)
	got[429] = strings.TrimSpace(g.postOK(testTokenB).body)
	for code, w := range want {
		if got[code] != w {
			t.Errorf("%d 본문 = %q, want %q", code, got[code], w)
		}
	}
}
