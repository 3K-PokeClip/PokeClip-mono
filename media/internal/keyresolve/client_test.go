package keyresolve

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"maps"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// 시험 값은 모두 합성이다.
const (
	testToken        = "01ARZ3NDEKTSV4RRFFQ69G5FAV"       // 스트림 토큰(공개 식별자)
	testResolveToken = "synthetic-resolve-token-0001"     // 전용 출입증 헤더 값(비밀)
	testPassphrase   = "Synthetic_Pass-phrase_0123456789" // auth 가 돌려주는 passphrase(비밀)

	// validBody 는 auth 의 valid:true 응답(ResolveResponse — NON_NULL 이라 reason 이 없다)이다.
	validBody = `{"valid":true,"userId":42,"passphrase":"` + testPassphrase + `"}`
)

// fakeAuth 는 4C 를 흉내 내는 가짜 auth 다(실제 전송 — httptest). 받은 요청 수, 새 TCP 연결 수,
// 마지막 요청을 기록한다.
type fakeAuth struct {
	srv   *httptest.Server
	calls atomic.Int32
	conns atomic.Int32

	mu   sync.Mutex
	last seenRequest
}

// seenRequest 는 가짜 auth 가 받은 요청 가운데 시험이 보는 부분이다.
type seenRequest struct {
	method string
	path   string
	header http.Header
	body   []byte
}

// newFakeAuth 는 reply 로 답하는 가짜 auth 를 띄운다. reply 는 요청 본문을 다 읽은 뒤에 불린다 —
// 본문을 다 읽어야 서버가 클라이언트의 연결 끊김을 보고 r.Context() 를 끝낸다.
func newFakeAuth(t *testing.T, reply http.HandlerFunc) *fakeAuth {
	t.Helper()
	a := &fakeAuth{}
	a.srv = httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		a.calls.Add(1)
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Errorf("fake auth: reading the request body: %v", err)
		}
		a.mu.Lock()
		a.last = seenRequest{method: r.Method, path: r.URL.Path, header: r.Header.Clone(), body: body}
		a.mu.Unlock()
		reply(w, r)
	}))
	a.srv.Config.ConnState = func(_ net.Conn, s http.ConnState) {
		if s == http.StateNew {
			a.conns.Add(1)
		}
	}
	a.srv.Start()
	t.Cleanup(a.srv.Close)
	return a
}

func (a *fakeAuth) lastRequest() seenRequest {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.last
}

// replyJSON 은 status 와 body 로 답한다.
func replyJSON(status int, body string) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = io.WriteString(w, body)
	}
}

func newTestClient(t *testing.T, baseURL string) *Client {
	t.Helper()
	c, err := NewClient(ClientOptions{BaseURL: baseURL, Token: NewSecret(testResolveToken)})
	if err != nil {
		t.Fatalf("Setup: NewClient(BaseURL=%q) returned error: %v", baseURL, err)
	}
	return c
}

// 거부 에러에는 토큰 값이 없어야 한다. BaseURL 도 싣지 않는다 — 설정을 잘못 넣으면 그 자리에
// 토큰이 들어갈 수 있다(BaseURL_에_토큰 행). 실패 문구도 행의 값 대신 행 이름을 싣는다 — 값에 출입증이 들어 있다.
func TestNewClientRejectsBadOptions(t *testing.T) {
	token := NewSecret(testResolveToken)
	tests := []struct {
		name string
		opt  ClientOptions
	}{
		{name: "BaseURL_빈_값", opt: ClientOptions{Token: token}},
		{name: "Token_빈_값", opt: ClientOptions{BaseURL: "http://auth.test:8082"}},
		{name: "scheme_없음", opt: ClientOptions{BaseURL: "auth.test:8082", Token: token}},
		{name: "상대_경로", opt: ClientOptions{BaseURL: "/internal", Token: token}},
		{name: "scheme_ftp", opt: ClientOptions{BaseURL: "ftp://auth.test:8082", Token: token}},
		{name: "호스트_없음", opt: ClientOptions{BaseURL: "http://", Token: token}},
		{name: "포트만", opt: ClientOptions{BaseURL: "http://:8082", Token: token}},
		{name: "해석_불가", opt: ClientOptions{BaseURL: "http://auth test:8082", Token: token}},
		{name: "BaseURL_에_토큰", opt: ClientOptions{BaseURL: testResolveToken, Token: token}},
		// 사용자 정보는 요청마다 Basic 머리로 auth 에 가고, 질의는 auth 까지 가며, 둘 다 끝점 문자열에 남는다.
		{name: "사용자_정보", opt: ClientOptions{BaseURL: "http://user:" + testResolveToken + "@auth.test:8082", Token: token}},
		{name: "질의", opt: ClientOptions{BaseURL: "http://auth.test:8082?k=" + testResolveToken, Token: token}},
		{name: "빈_질의", opt: ClientOptions{BaseURL: "http://auth.test:8082/?", Token: token}},
		{name: "조각", opt: ClientOptions{BaseURL: "http://auth.test:8082#" + testResolveToken, Token: token}},
		// 머리 값으로 그대로 실을 수 없는 Token — Go 전송은 제어 문자가 든 머리를 보내지 않아 auth 에 닿지 못한 것
		// (unreachable)처럼 보이고, 앞뒤 공백은 받는 쪽이 걷어 다른 값이 된다(RFC 9110 5.5).
		{name: "Token_끝_줄바꿈", opt: ClientOptions{BaseURL: "http://auth.test:8082", Token: NewSecret(testResolveToken + "\n")}},
		{name: "Token_머리_주입", opt: ClientOptions{BaseURL: "http://auth.test:8082", Token: NewSecret(testResolveToken + "\r\nX-Injected: 1")}},
		{name: "Token_NUL", opt: ClientOptions{BaseURL: "http://auth.test:8082", Token: NewSecret(testResolveToken + "\x00")}},
		{name: "Token_가운데_탭", opt: ClientOptions{BaseURL: "http://auth.test:8082", Token: NewSecret(testResolveToken + "\tx")}},
		{name: "Token_DEL", opt: ClientOptions{BaseURL: "http://auth.test:8082", Token: NewSecret(testResolveToken + "\x7f")}},
		{name: "Token_앞_공백", opt: ClientOptions{BaseURL: "http://auth.test:8082", Token: NewSecret(" " + testResolveToken)}},
		{name: "Token_뒤_공백", opt: ClientOptions{BaseURL: "http://auth.test:8082", Token: NewSecret(testResolveToken + " ")}},
		{name: "Channel_모름", opt: ClientOptions{BaseURL: "http://auth.test:8082", Token: token, Channel: Channel(99)}},
		{name: "Channel_음수", opt: ClientOptions{BaseURL: "http://auth.test:8082", Token: token, Channel: Channel(-1)}},
	}
	for _, tc := range tests {
		c, err := NewClient(tc.opt)
		if err == nil {
			t.Errorf("NewClient(%s) returned nil error, want error", tc.name)
			continue
		}
		if c != nil {
			t.Errorf("NewClient(%s) returned a non-nil Client with the error", tc.name)
		}
		if n := strings.Count(err.Error(), testResolveToken); n > 0 {
			t.Errorf("NewClient(%s) error carries the resolve token %d time(s), want 0 (error length %d)", tc.name, n, len(err.Error()))
		}
	}
}

func TestNewClientAcceptsAbsoluteHTTPURL(t *testing.T) {
	for _, base := range []string{"http://auth.test:8082", "http://auth.test:8082/", "https://auth.test"} {
		c, err := NewClient(ClientOptions{BaseURL: base, Token: NewSecret(testResolveToken)})
		if err != nil {
			t.Errorf("NewClient(BaseURL=%q) returned error: %v", base, err)
			continue
		}
		if c == nil {
			t.Errorf("NewClient(BaseURL=%q) returned a nil Client", base)
		}
	}
}

// valid:true 면 Verdict 에 userId 와 passphrase 가 실린다.
func TestResolveReturnsValidVerdict(t *testing.T) {
	auth := newFakeAuth(t, replyJSON(http.StatusOK, validBody))
	c := newTestClient(t, auth.srv.URL)

	v, err := c.Resolve(t.Context(), testToken)
	if err != nil {
		t.Fatalf("Resolve(%q) returned error: %v", testToken, err)
	}
	if !v.Valid || v.UserID != 42 || v.Reason != "" {
		t.Errorf("Resolve(%q) = %+v, want Valid with UserID 42 and no Reason", testToken, v)
	}
	if got := v.Passphrase.Reveal(); got != testPassphrase {
		t.Errorf("Resolve(%q).Passphrase.Reveal() is not the passphrase in the response (length %d, want %d)", testToken, len(got), len(testPassphrase))
	}
}

// valid:false 는 거절이다. 모르는 이유 값도 거절이고, 거절 응답에 실려 온 값은 버린다.
func TestResolveReturnsRejection(t *testing.T) {
	tests := []struct {
		name       string
		body       string
		wantReason string
	}{
		{name: "MALFORMED", body: `{"valid":false,"reason":"MALFORMED"}`, wantReason: "MALFORMED"},
		{name: "NOT_FOUND", body: `{"valid":false,"reason":"NOT_FOUND"}`, wantReason: "NOT_FOUND"},
		{name: "REVOKED", body: `{"valid":false,"reason":"REVOKED"}`, wantReason: "REVOKED"},
		{name: "모르는_이유", body: `{"valid":false,"reason":"SOMETHING_NEW"}`, wantReason: "SOMETHING_NEW"},
		{name: "이유_없음", body: `{"valid":false}`, wantReason: ""},
		{
			name:       "값이_실린_거절",
			body:       `{"valid":false,"userId":42,"passphrase":"` + testPassphrase + `","reason":"REVOKED"}`,
			wantReason: "REVOKED",
		},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			auth := newFakeAuth(t, replyJSON(http.StatusOK, tc.body))
			c := newTestClient(t, auth.srv.URL)

			// 실패 문구에는 본문 대신 행 이름을 싣는다 — 값이_실린_거절 행의 본문에 passphrase 가 있다.
			v, err := c.Resolve(t.Context(), testToken)
			if err != nil {
				t.Fatalf("Resolve(%q) with the %s body returned error: %v", testToken, tc.name, err)
			}
			if v.Valid || v.Reason != tc.wantReason || v.UserID != 0 || !v.Passphrase.IsZero() {
				t.Errorf("Resolve(%q) with the %s body = %+v, want a rejection with Reason %q and no UserID or Passphrase",
					testToken, tc.name, v, tc.wantReason)
			}
		})
	}
}

// 4C 에는 정규형 streamid 하나를 JSON 으로 보낸다(설계 2-3 — MediaMTX 의 인증 요청에는 streamid 원문이
// 없다). 출입증은 전용 헤더에만 싣고 공용 X-Internal-Token 은 싣지 않는다(4C 개정).
func TestResolveSendsCanonicalRequest(t *testing.T) {
	for _, suffix := range []string{"", "/"} { // BaseURL 끝 슬래시가 있어도 경로는 같다
		auth := newFakeAuth(t, replyJSON(http.StatusOK, validBody))
		c := newTestClient(t, auth.srv.URL+suffix)

		if _, err := c.Resolve(t.Context(), testToken); err != nil {
			t.Fatalf("Resolve(%q) via BaseURL suffix %q returned error: %v", testToken, suffix, err)
		}
		got := auth.lastRequest()
		if got.method != http.MethodPost || got.path != "/internal/stream-keys/resolve" {
			t.Errorf("request line (BaseURL suffix %q) = %s %s, want POST /internal/stream-keys/resolve",
				suffix, got.method, got.path)
		}
		if ct := got.header.Get("Content-Type"); ct != "application/json" {
			t.Errorf("Content-Type = %q, want %q", ct, "application/json")
		}
		// 머리 값은 찍지 않는다 — 출입증이다. 값의 수와 같은지만 싣는다.
		if v := got.header.Values(Header); len(v) != 1 || v[0] != testResolveToken {
			firstIsToken := len(v) > 0 && v[0] == testResolveToken
			t.Errorf("%s has %d value(s) (the first is the resolve token: %t), want exactly the resolve token", Header, len(v), firstIsToken)
		}
		if v := got.header.Values("X-Internal-Token"); len(v) != 0 {
			t.Errorf("X-Internal-Token has %d value(s), want none", len(v))
		}
		var body map[string]string
		if err := json.Unmarshal(got.body, &body); err != nil {
			t.Fatalf("request body %s is not a JSON object of strings: %v", got.body, err)
		}
		if want := map[string]string{"streamid": "#!::r=" + testToken + ",m=publish"}; !maps.Equal(body, want) {
			t.Errorf("request body = %s, want %v", got.body, want)
		}
	}
}

// 연결은 다시 쓴다(설계 2-3 keep-alive). 요청마다 전송을 새로 만들거나 keep-alive 를 끄면
// 호출마다 새 TCP 연결이 생긴다.
func TestResolveReusesConnection(t *testing.T) {
	auth := newFakeAuth(t, replyJSON(http.StatusOK, validBody))
	c := newTestClient(t, auth.srv.URL)

	for i := range 2 {
		if _, err := c.Resolve(t.Context(), testToken); err != nil {
			t.Fatalf("Resolve call %d returned error: %v", i+1, err)
		}
	}
	if n := auth.conns.Load(); n != 1 {
		t.Errorf("new TCP connections for two Resolve calls = %d, want 1", n)
	}
}

// asUndecided 는 err 가 *UndecidedError 이면 그 값을, 아니면 nil 을 돌려준다.
func asUndecided(err error) *UndecidedError {
	var ue *UndecidedError
	if errors.As(err, &ue) {
		return ue
	}
	return nil
}

// newHangingAuth 는 요청을 받으면 prelude 를 부르고(nil 이면 건너뛴다) arrived 로 알린 뒤, 요청 ctx 가
// 끝날 때까지 더 답하지 않는 가짜 auth 다.
func newHangingAuth(t *testing.T, prelude func(http.ResponseWriter)) (auth *fakeAuth, arrived <-chan struct{}) {
	t.Helper()
	got := make(chan struct{}, 1)
	release := make(chan struct{})
	auth = newFakeAuth(t, func(w http.ResponseWriter, r *http.Request) {
		if prelude != nil {
			prelude(w)
		}
		select {
		case got <- struct{}{}:
		default:
		}
		select {
		case <-r.Context().Done():
		case <-release:
		}
	})
	// newFakeAuth 의 서버 닫기보다 먼저 돈다(정리는 등록의 역순) — 붙잡힌 처리기가 남아 있어도
	// 서버 닫기가 기다리다 멈추지 않는다.
	t.Cleanup(func() { close(release) })
	return auth, got
}

// 200 이 아니면 판단 불가(Kind "status")다. 본문이 판정처럼 보여도 해석하지 않고, 재시도하지 않는다.
func TestResolveNon200IsUndecidedStatus(t *testing.T) {
	for _, status := range []int{
		http.StatusUnauthorized, // 전용 토큰이 틀림
		http.StatusForbidden,    // 출발지 잠금
		http.StatusInternalServerError,
		http.StatusServiceUnavailable,
		http.StatusNoContent, // 2xx 라도 200 이 아니면 판정이 아니다
	} {
		t.Run(strconv.Itoa(status), func(t *testing.T) {
			auth := newFakeAuth(t, replyJSON(status, validBody))
			c := newTestClient(t, auth.srv.URL)

			_, err := c.Resolve(t.Context(), testToken)
			if ue := asUndecided(err); ue == nil || ue.Kind != "status" || ue.Status != status {
				t.Errorf("Resolve(%q) against HTTP %d: err = %v, want *UndecidedError{Kind: status, Status: %d}",
					testToken, status, err, status)
			}
			if n := auth.calls.Load(); n != 1 {
				t.Errorf("fake auth calls = %d, want 1 (no retry)", n)
			}
		})
	}
}

// 가짜 auth 가 요청 ctx 가 끝날 때까지 붙잡으면 Kind "timeout" 이다. 시한은 ctx 하나다. 취소로 끝나도
// timeout 이다 — 어느 쪽이든 부르는 쪽이 더 기다리지 않은 것이지 auth 에 닿지 못한 것이 아니다.
func TestResolveTimeoutIsUndecidedTimeout(t *testing.T) {
	t.Run("시한", func(t *testing.T) {
		auth, arrived := newHangingAuth(t, nil)
		c := newTestClient(t, auth.srv.URL)
		ctx, cancel := context.WithTimeout(t.Context(), 50*time.Millisecond)
		defer cancel()

		_, err := c.Resolve(ctx, testToken)
		if ue := asUndecided(err); ue == nil || ue.Kind != "timeout" {
			t.Errorf("Resolve(%q) past the ctx deadline: err = %v, want *UndecidedError{Kind: timeout}", testToken, err)
		}
		// 처리기가 모두 돌아온 뒤에 센다. 서버 닫기는 처리 중인 요청이 끝나길 기다리고, 붙잡힌 처리기는
		// 클라이언트가 연결을 끊으면 돌아온다. ctx 의 시한은 만들 때 정해져 요청이 닿기 전에 흐른다 — 느린 러너에서는
		// 요청이 auth 에 닿기 전에 끝날 수 있다. 그래서 닿았으면(arrived) 한 번, 아니면 0 번이 재시도 없음이다.
		auth.srv.Close()
		var want int32
		select {
		case <-arrived:
			want = 1
		default:
		}
		if n := auth.calls.Load(); n != want {
			t.Errorf("fake auth calls = %d, want %d (no retry — the request reached auth: %t)", n, want, want == 1)
		}
	})
	t.Run("취소", func(t *testing.T) {
		auth, arrived := newHangingAuth(t, nil)
		c := newTestClient(t, auth.srv.URL)
		ctx, cancel := context.WithCancel(t.Context())
		defer cancel()
		go func() {
			// 요청이 auth 에 닿은 뒤에 취소한다. 닿지 않은 채 시험이 끝나면 ctx 가 끝나 빠져나온다.
			select {
			case <-arrived:
				cancel()
			case <-ctx.Done():
			}
		}()

		_, err := c.Resolve(ctx, testToken)
		if ue := asUndecided(err); ue == nil || ue.Kind != "timeout" {
			t.Errorf("Resolve(%q) with a canceled ctx: err = %v, want *UndecidedError{Kind: timeout}", testToken, err)
		}
	})
}

// 연결이 안 되면(닫힌 리스너) Kind "unreachable" 이다.
func TestResolveConnectionFailureIsUndecidedUnreachable(t *testing.T) {
	srv := httptest.NewServer(http.NotFoundHandler())
	baseURL := srv.URL
	srv.Close()
	c := newTestClient(t, baseURL)

	_, err := c.Resolve(t.Context(), testToken)
	if ue := asUndecided(err); ue == nil || ue.Kind != "unreachable" {
		t.Errorf("Resolve(%q) against a closed listener: err = %v, want *UndecidedError{Kind: unreachable}", testToken, err)
	}
}

// roundTripFunc 는 함수를 http.RoundTripper 로 쓴다 — 시험이 실제 전송을 감싸 그 사이의 때를 안다.
type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) {
	return f(r)
}

// 200 머리를 보낸 뒤 본문에서 실패해도 Kind 는 같은 규칙이다 — 본문을 붙잡은 채 ctx 가 끝나면
// timeout, 본문 도중 연결이 끊기면 unreachable 이다.
func TestResolveBodyFailureAfter200(t *testing.T) {
	// writePartial 은 200 머리와 본문 앞부분을 내보낸다(Flush — 그래서 클라이언트는 본문 읽기에 들어간다).
	writePartial := func(w http.ResponseWriter) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, `{"valid":`)
		w.(http.Flusher).Flush()
	}

	t.Run("본문_붙잡음", func(t *testing.T) {
		auth, _ := newHangingAuth(t, writePartial)
		c := newTestClient(t, auth.srv.URL)
		ctx, cancel := context.WithCancel(t.Context())
		defer cancel()
		// 200 머리를 받은 뒤에 ctx 를 끝낸다 — 그래야 실패가 본문 읽기에서 난다. 요청 전에 시작한 시한이면 느린 러너에서
		// 머리를 받기 전에 끝나 본문 갈래를 밟지 않는다. 전송을 감싸 머리를 받은 때를 안다.
		base := c.httpClient.Transport
		gotHead := false
		c.httpClient.Transport = roundTripFunc(func(r *http.Request) (*http.Response, error) {
			resp, err := base.RoundTrip(r)
			if err == nil {
				gotHead = true
				cancel()
			}
			return resp, err
		})

		_, err := c.Resolve(ctx, testToken)
		if !gotHead {
			t.Fatalf("Resolve(%q) never received the 200 head, want the failure to come from reading the body", testToken)
		}
		if ue := asUndecided(err); ue == nil || ue.Kind != "timeout" {
			t.Errorf("Resolve(%q) with a stalled 200 body: err = %v, want *UndecidedError{Kind: timeout}", testToken, err)
		}
	})
	t.Run("본문_도중_끊김", func(t *testing.T) {
		auth := newFakeAuth(t, func(w http.ResponseWriter, _ *http.Request) {
			writePartial(w)
			panic(http.ErrAbortHandler) // 서버가 조용히 연결을 끊는다
		})
		c := newTestClient(t, auth.srv.URL)

		_, err := c.Resolve(t.Context(), testToken)
		if ue := asUndecided(err); ue == nil || ue.Kind != "unreachable" {
			t.Errorf("Resolve(%q) with a 200 body cut mid-way: err = %v, want *UndecidedError{Kind: unreachable}", testToken, err)
		}
	})
}

// 200 인데 계약대로 해석되지 않으면 Kind "parse" 다.
func TestResolveUnparsableBodyIsUndecidedParse(t *testing.T) {
	tests := []struct {
		name string
		body string
	}{
		{name: "JSON_아님", body: "<html>maintenance</html>"},
		{name: "빈_본문", body: ""},
		{name: "배열", body: `[{"valid":true}]`},
		{name: "valid_문자열", body: `{"valid":"true","userId":42,"passphrase":"` + testPassphrase + `"}`},
		{name: "valid_숫자", body: `{"valid":1,"reason":"NOT_FOUND"}`},
		{name: "valid_null", body: `{"valid":null,"reason":"NOT_FOUND"}`},
		{name: "valid_없음", body: `{"reason":"NOT_FOUND"}`},
		// 설계가 정하지 않은 자리: valid:true 인데 userId 가 없다. auth 는 늘 싣는다(ResolveResult.of).
		{name: "userId_없음", body: `{"valid":true,"passphrase":"` + testPassphrase + `"}`},
		{name: "userId_null", body: `{"valid":true,"userId":null,"passphrase":"` + testPassphrase + `"}`},
		{name: "userId_문자열", body: `{"valid":true,"userId":"42","passphrase":"` + testPassphrase + `"}`},
		{name: "passphrase_숫자", body: `{"valid":true,"userId":42,"passphrase":12345678901}`},
		{name: "뒤에_군더더기", body: `{"valid":false,"reason":"NOT_FOUND"} {}`},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			auth := newFakeAuth(t, replyJSON(http.StatusOK, tc.body))
			c := newTestClient(t, auth.srv.URL)

			// 실패 문구에는 본문 대신 행 이름을 싣는다 — 여러 행의 본문에 passphrase 가 있다.
			_, err := c.Resolve(t.Context(), testToken)
			if ue := asUndecided(err); ue == nil || ue.Kind != "parse" {
				t.Errorf("Resolve(%q) with the %s body: err = %v, want *UndecidedError{Kind: parse}", testToken, tc.name, err)
			}
		})
	}
}

// 본문은 4 KiB 까지만 읽는다(설계 2-3). 정확히 4096바이트(공백 채움)면 해석하고, 넘으면 Kind "parse" 다.
func TestResolveReadsBodyUpTo4KiB(t *testing.T) {
	exact := validBody + strings.Repeat(" ", 4096-len(validBody))

	t.Run("4096바이트", func(t *testing.T) {
		auth := newFakeAuth(t, replyJSON(http.StatusOK, exact))
		v, err := newTestClient(t, auth.srv.URL).Resolve(t.Context(), testToken)
		if err != nil || !v.Valid {
			t.Errorf("Resolve(%q) with a %d-byte body = %+v, %v; want a valid verdict", testToken, len(exact), v, err)
		}
	})
	t.Run("4097바이트", func(t *testing.T) {
		auth := newFakeAuth(t, replyJSON(http.StatusOK, exact+" "))
		_, err := newTestClient(t, auth.srv.URL).Resolve(t.Context(), testToken)
		if ue := asUndecided(err); ue == nil || ue.Kind != "parse" {
			t.Errorf("Resolve(%q) with a 4097-byte body: err = %v, want *UndecidedError{Kind: parse}", testToken, err)
		}
	})
}

// 에러 문구에는 응답 본문 · 출입증 토큰 · passphrase 가 없다(설계 6절 누설 규칙 2).
func TestResolveErrorsCarryNoBodyOrSecrets(t *testing.T) {
	const marker = "BODY-MARKER-only-in-the-response"
	tests := []struct {
		name   string
		status int
		body   string
	}{
		{name: "503", status: http.StatusServiceUnavailable, body: `{"error":"` + marker + `","passphrase":"` + testPassphrase + `"}`},
		{name: "401", status: http.StatusUnauthorized, body: marker},
		{name: "JSON_아님", status: http.StatusOK, body: marker + " " + testPassphrase},
		{name: "valid_타입", status: http.StatusOK, body: `{"valid":"` + marker + `","passphrase":"` + testPassphrase + `"}`},
		{name: "4KiB_넘음", status: http.StatusOK, body: validBody + strings.Repeat(" ", 4096) + marker},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			auth := newFakeAuth(t, replyJSON(tc.status, tc.body))
			c := newTestClient(t, auth.srv.URL)

			_, err := c.Resolve(t.Context(), testToken)
			if err == nil {
				t.Fatalf("Resolve(%q) with HTTP %d (%s, %d-byte body) returned nil error, want error",
					testToken, tc.status, tc.name, len(tc.body))
			}
			// 실패 문구에는 에러 원문 · 카나리아 값을 싣지 않는다 — 카나리아 이름 · 횟수 · 길이만.
			for _, canary := range []struct{ name, value string }{
				{name: "response body", value: marker},
				{name: "resolve token", value: testResolveToken},
				{name: "passphrase", value: testPassphrase},
			} {
				if n := strings.Count(err.Error(), canary.value); n > 0 {
					t.Errorf("Resolve(%q) with HTTP %d (%s) error carries the %s canary %d time(s), want 0 (error length %d)",
						testToken, tc.status, tc.name, canary.name, n, len(err.Error()))
				}
			}
		})
	}
}

// NewClient 를 지나도 끝점을 다시 해석하지 못하는 BaseURL(IPv6 영역 id 의 '[')이면 요청을 만들지 못한다. 그 에러는 판단
// 불가도 형식 밖도 아닌 그 밖의 에러이고, 문구에 끝점도 url.Parse 의 원인 문구도 싣지 않는다 — 부르는 쪽이 에러를
// 찍으면 BaseURL 이 로그에 남는다.
func TestResolveRequestBuildFailureCarriesNoEndpoint(t *testing.T) {
	c := newTestClient(t, "http://[fe80::1%25%20%5b]:8082")

	_, err := c.Resolve(t.Context(), testToken)
	if err == nil {
		t.Fatalf("Resolve(%q) with an endpoint that does not parse again returned nil error, want error", testToken)
	}
	if asUndecided(err) != nil || errors.Is(err, ErrInvalidPassphrase) {
		t.Errorf("Resolve(%q) with an endpoint that does not parse again: err = %v, want an error that is neither *UndecidedError nor ErrInvalidPassphrase", testToken, err)
	}
	for _, fragment := range []string{"fe80", "8082", "stream-keys", "IP-literal"} {
		if strings.Contains(err.Error(), fragment) {
			t.Errorf("Resolve(%q) error carries the endpoint fragment %q (error length %d)", testToken, fragment, len(err.Error()))
		}
	}
}

// valid:true 인데 passphrase 가 형식 밖이면 ErrInvalidPassphrase 다. auth 는 답했으므로 판단 불가가
// 아니다(설계 2-4 판정표 「passphrase 형식 밖」). 에러 문구에 그 값이 없다.
func TestResolveOutOfFormatPassphraseIsErrInvalidPassphrase(t *testing.T) {
	tests := []struct {
		name  string
		value string // 본문에 실은 passphrase. 비면 본문을 body 그대로 쓴다
		body  string
	}{
		{name: "없음", body: `{"valid":true,"userId":42}`},
		{name: "null", body: `{"valid":true,"userId":42,"passphrase":null}`},
		{name: "9자", value: "Syn-9char"},
		{name: "65자", value: strings.Repeat("Ab-_", 16) + "Z"},
		{name: "금지_글자", value: "Synthetic+pass_01"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			body := tc.body
			if tc.value != "" {
				body = `{"valid":true,"userId":42,"passphrase":"` + tc.value + `"}`
			}
			auth := newFakeAuth(t, replyJSON(http.StatusOK, body))
			c := newTestClient(t, auth.srv.URL)

			// 실패 문구에는 본문 · 에러 원문 대신 행 이름 · 횟수 · 길이를 싣는다 — 본문에 passphrase 가 있다.
			_, err := c.Resolve(t.Context(), testToken)
			if !errors.Is(err, ErrInvalidPassphrase) {
				t.Fatalf("Resolve(%q) with the %s passphrase: err = %v, want ErrInvalidPassphrase", testToken, tc.name, err)
			}
			if ue := asUndecided(err); ue != nil {
				t.Errorf("Resolve(%q) with the %s passphrase: err is also *UndecidedError %+v, want only ErrInvalidPassphrase",
					testToken, tc.name, ue)
			}
			if tc.value == "" {
				return
			}
			if n := strings.Count(err.Error(), tc.value); n > 0 {
				t.Errorf("Resolve(%q) error carries the %s passphrase %d time(s), want 0 (error length %d)", testToken, tc.name, n, len(err.Error()))
			}
		})
	}
}

// 리다이렉트를 따르지 않는다. 3xx 는 비-200 이라 Kind "status" 이고, 리다이렉트 대상에는 요청이 가지
// 않는다 — Go 의 http.Client 는 리다이렉트 때 사용자 정의 헤더(전용 토큰)를 새 주소에도 그대로 싣는다. 공용 창구
// (X-Internal-Token)에서도 같다.
func TestResolveDoesNotFollowRedirects(t *testing.T) {
	for _, channel := range []Channel{ChannelResolveToken, ChannelInternalToken} {
		for _, status := range []int{http.StatusTemporaryRedirect, http.StatusFound} {
			t.Run(channel.String()+"/"+strconv.Itoa(status), func(t *testing.T) {
				target := newFakeAuth(t, replyJSON(http.StatusOK, validBody))
				redirector := newFakeAuth(t, func(w http.ResponseWriter, r *http.Request) {
					http.Redirect(w, r, target.srv.URL+resolvePath, status)
				})
				c, err := NewClient(ClientOptions{BaseURL: redirector.srv.URL, Token: NewSecret(testResolveToken), Channel: channel})
				if err != nil {
					t.Fatalf("Setup: NewClient returned error: %v", err)
				}

				_, err = c.Resolve(t.Context(), testToken)
				if ue := asUndecided(err); ue == nil || ue.Kind != "status" || ue.Status != status {
					t.Errorf("Resolve(%q) against an HTTP %d redirect: err = %v, want *UndecidedError{Kind: status, Status: %d}",
						testToken, status, err, status)
				}
				if n := target.calls.Load(); n != 0 {
					t.Errorf("redirect target calls = %d, want 0 (following would carry the resolve token there)", n)
				}
			})
		}
	}
}

// 창구(Channel)는 출입증을 실을 머리 하나를 고른다 — 영값과 ChannelResolveToken 은 전용 머리(X-Stream-Key-Resolve-Token)
// 만, ChannelInternalToken 은 공용 머리(X-Internal-Token)만이다. 다른 쪽 머리는 없어야 한다 — 둘 다 실리면 auth 의 전용
// 체인(@Order(0))이 가져가 공용 토큰을 전용 값과 견주어 401 이다.
func TestResolveSendsChannelHeader(t *testing.T) {
	for _, tc := range []struct {
		name          string
		channel       Channel
		want, without string
	}{
		{name: "영값", want: Header, without: InternalHeader},
		{name: "resolve-token", channel: ChannelResolveToken, want: Header, without: InternalHeader},
		{name: "internal-token", channel: ChannelInternalToken, want: InternalHeader, without: Header},
	} {
		t.Run(tc.name, func(t *testing.T) {
			auth := newFakeAuth(t, replyJSON(http.StatusOK, validBody))
			c, err := NewClient(ClientOptions{BaseURL: auth.srv.URL, Token: NewSecret(testResolveToken), Channel: tc.channel})
			if err != nil {
				t.Fatalf("Setup: NewClient returned error: %v", err)
			}
			if _, err := c.Resolve(t.Context(), testToken); err != nil {
				t.Fatalf("Resolve returned error: %v", err)
			}
			got := auth.lastRequest()
			if v := got.header.Values(tc.want); len(v) != 1 || v[0] != testResolveToken {
				t.Errorf("%s has %d value(s) (the first is the resolve token: %t), want exactly the resolve token",
					tc.want, len(v), len(v) > 0 && v[0] == testResolveToken)
			}
			if v := got.header.Values(tc.without); len(v) != 0 {
				t.Errorf("%s has %d value(s), want none", tc.without, len(v))
			}
		})
	}
}

// 프록시 환경변수(HTTP_PROXY 등)를 타지 않는다 — VPC 안 직접 호출이고, 전용 토큰 헤더가 프록시를
// 거치면 안 된다. Go 는 루프백 주소에 프록시를 쓰지 않고(httpproxy useProxy) 환경변수도 프로세스에서
// 한 번만 읽으므로(envProxyOnce) httptest 로는 이 동작이 드러나지 않는다. 그래서 전송 설정을 직접 본다.
// 시한은 ctx 하나다 — http.Client.Timeout 을 따로 두지 않는다.
func TestNewClientUsesDirectTransportWithoutOwnTimeout(t *testing.T) {
	c := newTestClient(t, "http://auth.test:8082")

	tr, ok := c.httpClient.Transport.(*http.Transport)
	if !ok {
		t.Fatalf("Client transport = %T, want *http.Transport (nil falls back to http.DefaultTransport, which uses the proxy environment)",
			c.httpClient.Transport)
	}
	if tr.Proxy != nil {
		t.Error("Client transport has a Proxy func, want nil (direct connection)")
	}
	if c.httpClient.Timeout != 0 {
		t.Errorf("http.Client.Timeout = %v, want 0 (the deadline is the ctx alone)", c.httpClient.Timeout)
	}
}

// 연결 준비(TCP 다이얼 · TLS 악수)에는 상한이 있다. Go 전송은 연결 준비를 요청 ctx 의 취소에서 떼어 두므로, 상한이 없으면
// auth 가 SYN · TLS 를 붙잡을 때 Resolve 가 ctx 시한에 돌아온 뒤에도 다이얼 고루틴과 소켓이 남는다. 영값 Transport 는 시한
// 없는 다이얼러를 쓴다(DialContext 가 nil). 다이얼러의 Timeout 값 자체는 함수 값 밖에서 읽을 수 없어, 다이얼러가
// 끼워졌는지와 같은 상수를 쓰는 TLSHandshakeTimeout 을 본다.
func TestNewClientBoundsConnectionSetup(t *testing.T) {
	c := newTestClient(t, "http://auth.test:8082")

	tr, ok := c.httpClient.Transport.(*http.Transport)
	if !ok {
		t.Fatalf("Client transport = %T, want *http.Transport", c.httpClient.Transport)
	}
	if tr.DialContext == nil {
		t.Error("Transport.DialContext is nil, want a dialer with a timeout (the zero Transport dials without one)")
	}
	if got, want := tr.TLSHandshakeTimeout, 2*time.Second; got != want {
		t.Errorf("Transport.TLSHandshakeTimeout = %v, want %v", got, want)
	}
}

// 토큰 꼴이 아닌 값은 auth 에 묻지 않고 에러다(설계가 정하지 않은 자리 — 처리기는 IsToken 을 먼저 본다).
// token 으로 정규형 streamid 를 다시 만들므로, 쉼표가 든 값은 auth 가 다른 r 값을 읽게 만들 수 있다.
func TestResolveRejectsNonTokenWithoutCallingAuth(t *testing.T) {
	auth := newFakeAuth(t, replyJSON(http.StatusOK, validBody))
	c := newTestClient(t, auth.srv.URL)

	for _, token := range []string{
		"",
		"not-a-token",
		strings.ToLower(testToken),
		testToken + ",r=0123456789ABCDEFGHJKMNPQRS", // auth 의 StreamId.parse 는 마지막 r 을 읽는다
	} {
		v, err := c.Resolve(t.Context(), token)
		if err == nil {
			t.Errorf("Resolve(%q) = %+v, nil; want error", token, v)
			continue
		}
		if asUndecided(err) != nil || errors.Is(err, ErrInvalidPassphrase) {
			t.Errorf("Resolve(%q) err = %v, want an input error (neither *UndecidedError nor ErrInvalidPassphrase)", token, err)
		}
	}
	if n := auth.calls.Load(); n != 0 {
		t.Errorf("fake auth calls = %d, want 0", n)
	}
}
