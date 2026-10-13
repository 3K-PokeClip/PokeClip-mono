package ingestauth

import (
	"bytes"
	"cmp"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/keyresolve"
)

// validOptions 는 NewHandler 가 받는 선택값이다 — 시험이 한 칸씩 망가뜨린다.
func validOptions() Options {
	return Options{CallerIP: testCallerIP, SelfIP: testSelfIP, APIUser: testAPIUser, APIPass: NewSecret(testAPIPass)}
}

// NewHandler 는 쓸 수 없는 선택값이면 에러다(설계 2-6 「필수값이 비면 기동 실패」). 빈 자격을 받으면 SelfIP 의 누구나
// action=api 를 지나고, nil 인자를 받으면 첫 요청에서 패닉한다. 에러에는 자격 값을 싣지 않는다.
func TestNewHandlerRejectsBadOptions(t *testing.T) {
	resolver := newTestResolver(t, "http://auth.test:8082")
	deny := NewDenyList(filepath.Join(t.TempDir(), "deny-tokens"), discardLogger())
	conns, _ := openTestConnLog(t, t.TempDir(), 1<<20, newFakeClock())
	tests := []struct {
		name     string
		resolver *keyresolve.Client
		deny     *DenyList
		conns    *ConnLog
		edit     func(*Options)
	}{
		{name: "resolver_nil", deny: deny, conns: conns},
		{name: "deny_nil", resolver: resolver, conns: conns},
		{name: "conns_nil", resolver: resolver, deny: deny},
		{name: "CallerIP_영값", resolver: resolver, deny: deny, conns: conns, edit: func(o *Options) { o.CallerIP = netip.Addr{} }},
		{name: "SelfIP_영값", resolver: resolver, deny: deny, conns: conns, edit: func(o *Options) { o.SelfIP = netip.Addr{} }},
		{name: "APIUser_빈_값", resolver: resolver, deny: deny, conns: conns, edit: func(o *Options) { o.APIUser = "" }},
		{name: "APIPass_빈_값", resolver: resolver, deny: deny, conns: conns, edit: func(o *Options) { o.APIPass = NewSecret("") }},
		{name: "APIPass_영값", resolver: resolver, deny: deny, conns: conns, edit: func(o *Options) { o.APIPass = Secret{} }},
		{name: "Deadline_음수", resolver: resolver, deny: deny, conns: conns, edit: func(o *Options) { o.Deadline = -time.Millisecond }},
		{name: "Deadline_MaxDeadline_초과", resolver: resolver, deny: deny, conns: conns, edit: func(o *Options) { o.Deadline = MaxDeadline + time.Nanosecond }},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			opt := validOptions()
			if tc.edit != nil {
				tc.edit(&opt)
			}
			h, err := NewHandler(tc.resolver, tc.deny, tc.conns, opt)
			if err == nil {
				t.Fatalf("NewHandler with %s = nil error, want error", tc.name)
			}
			if h != nil {
				t.Errorf("NewHandler with %s returned a non-nil handler with the error", tc.name)
			}
			if strings.Contains(err.Error(), testAPIPass) {
				t.Errorf("NewHandler with %s: error carries the API password", tc.name)
			}
		})
	}
	t.Run("Deadline_MaxDeadline_그대로", func(t *testing.T) {
		opt := validOptions()
		opt.Deadline = MaxDeadline
		if _, err := NewHandler(resolver, deny, conns, opt); err != nil {
			t.Errorf("NewHandler with Deadline = MaxDeadline returned error: %v", err)
		}
	})
}

// Deadline 0 은 MaxDeadline, Now nil 은 time.Now, Log nil 은 slog.Default() 다(설계 2-4 Options).
func TestNewHandlerAppliesDefaults(t *testing.T) {
	resolver := newTestResolver(t, "http://auth.test:8082")
	deny := NewDenyList(filepath.Join(t.TempDir(), "deny-tokens"), discardLogger())
	conns, _ := openTestConnLog(t, t.TempDir(), 1<<20, newFakeClock())
	h, err := newHandler(resolver, deny, conns, validOptions())
	if err != nil {
		t.Fatalf("newHandler with defaults returned error: %v", err)
	}
	if h.deadline != MaxDeadline {
		t.Errorf("deadline with Options.Deadline 0 = %v, want MaxDeadline %v", h.deadline, MaxDeadline)
	}
	if h.log != slog.Default() {
		t.Errorf("logger with Options.Log nil is not slog.Default()")
	}
	if d := time.Since(h.now()); d < 0 || d > time.Minute {
		t.Errorf("now() with Options.Now nil is %v away from time.Now, want time.Now", d)
	}
}

// NewHandler 가 돌려준 처리기는 그대로 답하고, 바깥 시한 가드로 싸여 있다 — 본문을 끝까지 보내지 않는 요청은 Deadline
// + 50ms 에 가드의 503("timeout")을 받는다. 시험의 나머지는 같은 조립을 newHandler · layers 로 쓰므로, NewHandler 가
// 가드 · 차감 없이 안쪽을 내보내는 실수는 이 시험만 잡는다.
func TestNewHandlerServesAuthRequests(t *testing.T) {
	resolver := newTestResolver(t, "http://auth.test:8082")
	deny := NewDenyList(filepath.Join(t.TempDir(), "deny-tokens"), discardLogger())
	conns, _ := openTestConnLog(t, t.TempDir(), 1<<20, newFakeClock())
	log, logs := newLogRecorder()
	opt := validOptions()
	opt.Log = log
	opt.Deadline = guardDeadline
	srv, err := NewHandler(resolver, deny, conns, opt)
	if err != nil {
		t.Fatalf("NewHandler returned error: %v", err)
	}
	f := &handlerFixture{srv: srv}
	if rr := f.serve(newAuthRequest(t, apiBody(testSelfIP.String(), testAPIUser, testAPIPass))); rr.Code != http.StatusOK {
		t.Errorf("api request from SelfIP with the right credentials through NewHandler = %d, want 200", rr.Code)
	}
	rr, served, finish := f.stalledPublish(t, testTokenA, testConnID(1))
	waitClosed(t, served, "the guard's 503 through NewHandler")
	if rr.Code != http.StatusServiceUnavailable || rr.Body.String() != "timeout" {
		t.Errorf("stalled publish through NewHandler = %d with a %d-byte body (is \"timeout\": %t), want 503 \"timeout\" from the guard", rr.Code, rr.Body.Len(), rr.Body.String() == "timeout")
	}
	finish()
	waitForInnerEnd(t, logs) // 늦게 깬 안쪽 처리기가 끝난 뒤에 시험을 마친다
}

// TCP 피어가 CallerIP 가 아니면 본문을 읽기 전에 403(본문 없음)이다(판정표 첫 행). 포트를 떼고 IPv4-mapped IPv6 는
// IPv4 로 견준다. zone 이 붙은 주소는 풀지 않는다 — zone 은 그 주소의 일부라 zone 없는 CallerIP 와 다르다.
func TestHandlerChecksCaller(t *testing.T) {
	tests := []struct {
		name       string
		remoteAddr string
		wantCode   int
		wantRemote string // caller_denied 의 remote_ip — 403 일 때만
	}{
		{name: "CallerIP", remoteAddr: "192.0.2.10:40000", wantCode: http.StatusOK},
		{name: "IPv4-mapped_CallerIP", remoteAddr: "[::ffff:192.0.2.10]:40000", wantCode: http.StatusOK},
		{name: "zone_붙은_IPv4-mapped_CallerIP", remoteAddr: "[::ffff:192.0.2.10%eth0]:40000", wantCode: http.StatusForbidden, wantRemote: "::ffff:192.0.2.10%eth0"},
		{name: "다른_IPv4", remoteAddr: "192.0.2.11:40000", wantCode: http.StatusForbidden, wantRemote: "192.0.2.11"},
		{name: "SelfIP", remoteAddr: "192.0.2.20:40000", wantCode: http.StatusForbidden, wantRemote: "192.0.2.20"},
		{name: "IPv6", remoteAddr: "[2001:db8::10]:40000", wantCode: http.StatusForbidden, wantRemote: "2001:db8::10"},
		{name: "해석_불가", remoteAddr: "not-an-address", wantCode: http.StatusForbidden, wantRemote: "not-an-address"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f := newHandlerFixture(t, fixtureOptions{})
			r := newAuthRequest(t, mtxAuthBody{IP: testPublisherIP, Action: "read"})
			r.RemoteAddr = tc.remoteAddr
			rr := f.serve(r)
			if rr.Code != tc.wantCode {
				t.Fatalf("read request from TCP peer %q = %d, want %d", tc.remoteAddr, rr.Code, tc.wantCode)
			}
			if rr.Body.Len() != 0 {
				t.Errorf("response to TCP peer %q has a body of %d bytes, want none", tc.remoteAddr, rr.Body.Len())
			}
			denied := handlerEvents(t, f.logs, "ingestauth_caller_denied")
			if tc.wantCode != http.StatusForbidden {
				if len(denied) != 0 {
					t.Errorf("caller_denied events for TCP peer %q = %d, want 0", tc.remoteAddr, len(denied))
				}
				return
			}
			if len(denied) != 1 || denied[0].Level != "WARN" || denied[0].RemoteIP != tc.wantRemote {
				t.Errorf("caller_denied events for TCP peer %q = %+v, want one WARN with remote_ip %q", tc.remoteAddr, denied, tc.wantRemote)
			}
		})
	}
	t.Run("본문보다_먼저", func(t *testing.T) {
		f := newHandlerFixture(t, fixtureOptions{})
		r := newAuthRequest(t, "not json")
		r.RemoteAddr = "192.0.2.11:40000"
		if rr := f.serve(r); rr.Code != http.StatusForbidden {
			t.Errorf("unparsable body from a non-caller = %d, want 403 (the peer check comes before the body)", rr.Code)
		}
		if got := handlerEvents(t, f.logs, "ingestauth_request_invalid"); len(got) != 0 {
			t.Errorf("request_invalid events for a non-caller = %d, want 0", len(got))
		}
	})
}

// 본문을 받지 못하면 400(본문 없음)이고 request_invalid 의 reason 은 닫힌 집합이다(판정표 둘째 행). 16 KiB 를 넘는
// 본문 · JSON 객체가 아님 · 필드 꼴 틀림(ip 해석 실패 · id 가 소문자 UUID 가 아님) · 읽기 실패.
func TestHandlerRejectsInvalidRequest(t *testing.T) {
	big := publishBody(testTokenA, testConnID(1))
	big.UserAgent = strings.Repeat("x", 16<<10)
	upperID := strings.ToUpper(testConnID(0xabc)) // 16진 글자가 있어야 대문자 꼴이 소문자 꼴과 다르다
	emptyID := ""
	badIDBody := publishBody(testTokenA, testConnID(1))
	badIDBody.ID = &upperID
	emptyIDBody := publishBody(testTokenA, testConnID(1))
	emptyIDBody.ID = &emptyID
	tests := []struct {
		name       string
		body       any
		wantReason string
	}{
		{name: "JSON_아님", body: "not json", wantReason: "malformed"},
		{name: "JSON_배열", body: "[]", wantReason: "malformed"},
		{name: "JSON_null", body: "null", wantReason: "malformed"},
		{name: "빈_본문", body: "", wantReason: "malformed"},
		{name: "필드_꼴_틀림", body: `{"ip":"198.51.100.7","action":5}`, wantReason: "malformed"},
		{name: "뒤에_군더더기", body: `{"ip":"198.51.100.7","action":"read"} {}`, wantReason: "malformed"},
		{name: "16KiB_초과", body: big, wantReason: "too_large"},
		{name: "ip_해석_불가", body: mtxAuthBody{IP: "not-an-ip", Action: "read"}, wantReason: "bad_ip"},
		{name: "ip_없음", body: mtxAuthBody{Action: "read"}, wantReason: "bad_ip"},
		{name: "id_대문자", body: badIDBody, wantReason: "bad_conn_id"},
		{name: "id_빈_문자열", body: emptyIDBody, wantReason: "bad_conn_id"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f := newHandlerFixture(t, fixtureOptions{})
			rr := f.serve(newAuthRequest(t, tc.body))
			if err := requestInvalidMismatch(t, f, rr, tc.wantReason); err != nil {
				t.Errorf("auth request %s: %v", tc.name, err)
			}
		})
	}
	t.Run("읽기_실패", func(t *testing.T) {
		f := newHandlerFixture(t, fixtureOptions{})
		r := newAuthRequest(t, "")
		r.Body = failingBody{}
		rr := f.serve(r)
		if err := requestInvalidMismatch(t, f, rr, "read_error"); err != nil {
			t.Errorf("auth request whose body read fails: %v", err)
		}
	})
}

// failingBody 는 읽기마다 실패하는 요청 본문이다.
type failingBody struct{}

func (failingBody) Read([]byte) (int, error) { return 0, errors.New("synthetic read failure") }
func (failingBody) Close() error             { return nil }

// requestInvalidMismatch 는 rr · 로그 · 가짜 auth 가 「400 · 본문 없음 · request_invalid 하나(WARN · wantReason) · auth
// 호출 0」과 다른 점을 돌려준다. 같으면 nil 이다.
func requestInvalidMismatch(t *testing.T, f *handlerFixture, rr *httptest.ResponseRecorder, wantReason string) error {
	t.Helper()
	var errs []error
	if rr.Code != http.StatusBadRequest {
		errs = append(errs, fmt.Errorf("response = %d, want 400", rr.Code))
	}
	if rr.Body.Len() != 0 {
		errs = append(errs, fmt.Errorf("400 has a body of %d bytes, want none", rr.Body.Len()))
	}
	got := handlerEvents(t, f.logs, "ingestauth_request_invalid")
	if len(got) != 1 || got[0].Level != "WARN" || got[0].Reason != wantReason {
		errs = append(errs, fmt.Errorf("request_invalid events = %+v, want one WARN with reason %q", got, wantReason))
	}
	if n := f.auth.callCount(); n != 0 {
		errs = append(errs, fmt.Errorf("fake auth calls = %d, want 0", n))
	}
	return errors.Join(errs...)
}

// user · password 는 action=api 에서만 해석 · 보관한다(설계 2-4 「읽지도 기록하지도 않는 필드」 — publish 의 user ·
// password). publish 는 그 둘의 JSON 꼴이 틀려도(숫자 · 객체) 판정표의 다음 행으로 가고, 해석한 요청에 그 값을 쥐지
// 않는다. api 는 둘을 견주므로 꼴이 틀리면 본문 해석 실패(400 · malformed)다.
func TestHandlerReadsCredentialsOnlyForAPI(t *testing.T) {
	t.Run("publish_꼴_틀린_자격", func(t *testing.T) {
		f := newHandlerFixture(t, fixtureOptions{})
		raw := mustJSON(t, publishBody(testTokenA, testConnID(1)))
		raw = bytes.Replace(raw, []byte(`"user":"streamid-user"`), []byte(`"user":5`), 1)
		raw = bytes.Replace(raw, []byte(`"password":"streamid-pass"`), []byte(`"password":{"s":1}`), 1)
		if !bytes.Contains(raw, []byte(`"user":5`)) || !bytes.Contains(raw, []byte(`"password":{"s":1}`)) {
			t.Fatalf("Setup: the publish body does not carry the non-string credentials")
		}
		if rr := f.serve(newAuthRequest(t, raw)); rr.Code != http.StatusOK {
			t.Errorf("publish whose user · password are not JSON strings = %d, want 200 (publish 는 그 둘을 해석하지 않는다)", rr.Code)
		}
	})
	t.Run("publish_자격_보관_안_함", func(t *testing.T) {
		req, invalid, ok := readRequest(bytes.NewReader(mustJSON(t, publishBody(testTokenA, testConnID(1)))))
		if !ok {
			t.Fatalf("readRequest(publish) = invalid %q, want a request", invalid)
		}
		if req.user != "" || req.password != "" {
			t.Errorf("readRequest(publish) kept credentials of lengths %d · %d, want none", len(req.user), len(req.password))
		}
	})
	t.Run("api_꼴_틀린_자격", func(t *testing.T) {
		f := newHandlerFixture(t, fixtureOptions{})
		raw := mustJSON(t, apiBody(testSelfIP.String(), testAPIUser, testAPIPass))
		raw = bytes.Replace(raw, []byte(`"user":"`+testAPIUser+`"`), []byte(`"user":5`), 1)
		if !bytes.Contains(raw, []byte(`"user":5`)) {
			t.Fatalf("Setup: the api body does not carry the non-string user")
		}
		rr := f.serve(newAuthRequest(t, raw))
		if err := requestInvalidMismatch(t, f, rr, "malformed"); err != nil {
			t.Errorf("api request whose user is not a JSON string: %v", err)
		}
	})
}

// 본문은 16 KiB 까지 받는다 — 정확히 16 KiB 인 본문은 판정표의 다음 행으로 간다(설계 2-4 「16 KiB 까지만 읽는다」).
func TestHandlerReadsBodyUpTo16KiB(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{})
	body := mtxAuthBody{IP: testPublisherIP, Action: "read"}
	raw := mustJSON(t, body)
	body.UserAgent = strings.Repeat("x", 16<<10-len(raw))
	raw = mustJSON(t, body)
	if len(raw) != 16<<10 {
		t.Fatalf("Setup: body is %d bytes, want %d", len(raw), 16<<10)
	}
	if rr := f.serve(newAuthRequest(t, raw)); rr.Code != http.StatusOK {
		t.Errorf("read request of exactly 16 KiB = %d, want 200", rr.Code)
	}
}

// action=api 는 출발지(본문의 ip)가 SelfIP(cut)이고 자격이 맞을 때만 200(본문 없음)이고, 그 밖은 401(본문 없음 ·
// api_denied — ip)이다(판정표 api 두 행). 자격은 상수 시간으로 견준다 — 길이가 달라도 같은 길을 탄다. 본문 ip 도 TCP
// 피어와 같은 규칙으로 견준다 — IPv4-mapped 는 풀고 zone 붙은 주소는 풀지 않는다.
func TestHandlerAPI(t *testing.T) {
	tests := []struct {
		name     string
		body     mtxAuthBody
		wantCode int
		wantIP   string // api_denied 의 ip. 비면 본문 ip 를 Unmap 한 값
	}{
		{name: "SelfIP_맞는_자격", body: apiBody("192.0.2.20", testAPIUser, testAPIPass), wantCode: http.StatusOK},
		{name: "IPv4-mapped_SelfIP", body: apiBody("::ffff:192.0.2.20", testAPIUser, testAPIPass), wantCode: http.StatusOK},
		{name: "zone_붙은_IPv4-mapped_SelfIP", body: apiBody("::ffff:192.0.2.20%eth0", testAPIUser, testAPIPass), wantCode: http.StatusUnauthorized, wantIP: "::ffff:192.0.2.20%eth0"},
		{name: "틀린_사용자", body: apiBody("192.0.2.20", "ingest-autH", testAPIPass), wantCode: http.StatusUnauthorized},
		{name: "틀린_비번", body: apiBody("192.0.2.20", testAPIUser, testAPIPass[:len(testAPIPass)-1]+"2"), wantCode: http.StatusUnauthorized},
		{name: "더_긴_비번", body: apiBody("192.0.2.20", testAPIUser, testAPIPass+"0"), wantCode: http.StatusUnauthorized},
		{name: "빈_자격", body: apiBody("192.0.2.20", "", ""), wantCode: http.StatusUnauthorized},
		{name: "자격_바꿔_실음", body: apiBody("192.0.2.20", testAPIPass, testAPIUser), wantCode: http.StatusUnauthorized},
		{name: "다른_출발지", body: apiBody("192.0.2.30", testAPIUser, testAPIPass), wantCode: http.StatusUnauthorized},
		{name: "CallerIP_출발지", body: apiBody("192.0.2.10", testAPIUser, testAPIPass), wantCode: http.StatusUnauthorized},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f := newHandlerFixture(t, fixtureOptions{})
			rr := f.serve(newAuthRequest(t, tc.body))
			if rr.Code != tc.wantCode {
				t.Fatalf("api request %s = %d, want %d", tc.name, rr.Code, tc.wantCode)
			}
			if rr.Body.Len() != 0 {
				t.Errorf("api response %s has a body of %d bytes, want none", tc.name, rr.Body.Len())
			}
			denied := handlerEvents(t, f.logs, "ingestauth_api_denied")
			allowed := handlerEvents(t, f.logs, "ingestauth_api_allowed")
			if tc.wantCode == http.StatusOK {
				if len(denied) != 0 || len(allowed) != 1 || allowed[0].Level != "DEBUG" {
					t.Errorf("api %s: api_denied %d · api_allowed %+v, want 0 · one DEBUG", tc.name, len(denied), allowed)
				}
				return
			}
			wantIP := tc.wantIP
			if wantIP == "" {
				wantIP = netip.MustParseAddr(tc.body.IP).Unmap().String()
			}
			if len(denied) != 1 || denied[0].Level != "WARN" || denied[0].IP != wantIP || len(allowed) != 0 {
				t.Errorf("api %s: api_denied %+v · api_allowed %d, want one WARN with ip %q · 0", tc.name, denied, len(allowed), wantIP)
			}
			if f.auth.callCount() != 0 {
				t.Errorf("api %s: fake auth calls = %d, want 0", tc.name, f.auth.callCount())
			}
		})
	}
}

// 설정 주소(CallerIP · SelfIP)도 TCP 피어 · 본문 ip 와 같은 규칙으로 견준다 — IPv4-mapped 로 준 설정값은 IPv4 로 풀고,
// zone 이 붙은 설정값은 풀지 않아 zone 없는 피어 · ip 와 다르다.
func TestHandlerComparesConfiguredAddresses(t *testing.T) {
	readBody := mtxAuthBody{IP: testPublisherIP, Action: "read"}
	tests := []struct {
		name     string
		opts     fixtureOptions
		remote   string // TCP 피어. 비면 192.0.2.10:40000
		body     mtxAuthBody
		wantCode int
	}{
		{name: "IPv4-mapped_CallerIP", opts: fixtureOptions{callerIP: netip.MustParseAddr("::ffff:192.0.2.10")}, body: readBody, wantCode: http.StatusOK},
		{name: "IPv4-mapped_SelfIP", opts: fixtureOptions{selfIP: netip.MustParseAddr("::ffff:192.0.2.20")}, body: apiBody("192.0.2.20", testAPIUser, testAPIPass), wantCode: http.StatusOK},
		{name: "zone_CallerIP_같은_zone_피어", opts: fixtureOptions{callerIP: netip.MustParseAddr("::ffff:192.0.2.10%eth0")}, remote: "[::ffff:192.0.2.10%eth0]:40000", body: readBody, wantCode: http.StatusOK},
		{name: "zone_CallerIP_zone_없는_피어", opts: fixtureOptions{callerIP: netip.MustParseAddr("::ffff:192.0.2.10%eth0")}, body: readBody, wantCode: http.StatusForbidden},
		{name: "zone_SelfIP_zone_없는_ip", opts: fixtureOptions{selfIP: netip.MustParseAddr("::ffff:192.0.2.20%eth0")}, body: apiBody("192.0.2.20", testAPIUser, testAPIPass), wantCode: http.StatusUnauthorized},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f := newHandlerFixture(t, tc.opts)
			r := newAuthRequest(t, tc.body)
			r.RemoteAddr = cmp.Or(tc.remote, "192.0.2.10:40000")
			if rr := f.serve(r); rr.Code != tc.wantCode {
				t.Errorf("%s request from TCP peer %q = %d, want %d", tc.body.Action, r.RemoteAddr, rr.Code, tc.wantCode)
			}
		})
	}
}

// action=read 는 설정에서 제외하므로 오지 않는다. 오면 지금 동작(무인증 읽기)을 유지한다 — 200 · 본문 없음 · auth
// 호출 0(판정표 read 행). SRT 읽기는 경로 잠금값이 막는다(설계 2-8).
func TestHandlerAllowsRead(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{})
	body := publishBody(testTokenA, testConnID(1))
	body.Action = "read"
	rr := f.serve(newAuthRequest(t, body))
	if rr.Code != http.StatusOK || rr.Body.Len() != 0 {
		t.Errorf("read request = %d with a %d-byte body, want 200 without a body", rr.Code, rr.Body.Len())
	}
	if n := f.auth.callCount(); n != 0 {
		t.Errorf("fake auth calls for a read request = %d, want 0", n)
	}
	if names := eventNames(t, f.logs); len(names) != 0 {
		t.Errorf("events for a read request = %v, want none", names)
	}
}

// mustJSON 은 v 의 JSON 이다.
func mustJSON(t *testing.T, v any) []byte {
	t.Helper()
	raw, err := json.Marshal(v)
	if err != nil {
		t.Fatalf("Setup: json.Marshal(%T) returned error: %v", v, err)
	}
	return raw
}
