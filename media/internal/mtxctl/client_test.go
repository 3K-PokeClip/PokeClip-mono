package mtxctl

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// 시험 값은 모두 합성이다. 자격은 어떤 계정의 값도 아니고, 주소는 문서용 대역(RFC 5737 · RFC 3849)과 httptest 의
// 루프백이다. 시험 실패 문구는 비번을 찍지 않는다(프로필 4절) — 자격은 맞았는지만 본다.
const (
	testUser = "ingest-auth"
	testPass = "synthetic-api-pass-0001"
	testID   = "0b1f2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d" // MediaMTX 연결 id 꼴(uuid.UUID 의 JSON — 소문자)
	testID2  = "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f"
	testID3  = "2d3e4f5a-6b7c-4d8e-9f0a-1b2c3d4e5f6a"
	testPath = "SYNTHET1CTEST000000000000A" // 토큰 = 경로 이름
)

// srtConnStatFields 는 APISRTConn 의 통계 필드 이름이다(포크 v1.21.1-pokeclip.2 internal/defs/api_srt.go 38–157행
// 그대로). 가짜 응답의 항목은 이 필드까지 모두 담는다 — 클라이언트가 실제 항목의 모든 필드를 견디는지 본다.
var srtConnStatFields = []string{
	"packetsSent", "packetsReceived", "packetsSentUnique", "packetsReceivedUnique", "packetsSendLoss",
	"packetsReceivedLoss", "packetsRetrans", "packetsReceivedRetrans", "packetsSentACK", "packetsReceivedACK",
	"packetsSentNAK", "packetsReceivedNAK", "packetsSentKM", "packetsReceivedKM", "usSndDuration",
	"packetsReceivedBelated", "packetsSendDrop", "packetsReceivedDrop", "packetsReceivedUndecrypt", "bytesReceived",
	"bytesSent", "bytesSentUnique", "bytesReceivedUnique", "bytesReceivedLoss", "bytesRetrans",
	"bytesReceivedRetrans", "bytesReceivedBelated", "bytesSendDrop", "bytesReceivedDrop", "bytesReceivedUndecrypt",
	"usPacketsSendPeriod", "packetsFlowWindow", "packetsFlightSize", "msRTT", "mbpsSendRate", "mbpsReceiveRate",
	"mbpsLinkCapacity", "bytesAvailSendBuf", "bytesAvailReceiveBuf", "mbpsMaxBW", "byteMSS", "packetsSendBuf",
	"bytesSendBuf", "msSendBuf", "msSendTsbPdDelay", "packetsReceiveBuf", "bytesReceiveBuf", "msReceiveBuf",
	"msReceiveTsbPdDelay", "packetsReorderTolerance", "packetsReceivedAvgBelatedTime", "packetsSendLossRate",
	"packetsReceivedLossRate", "outboundFramesDiscarded",
}

// srtConnItem 은 Control API 가 내는 SRT 연결 항목 하나(APISRTConn — defs/api_srt.go 26–158행의 61필드)다. created 는
// MediaMTX 가 쓰는 꼴(time.Time 의 JSON — RFC 3339)의 문자열로 받는다.
func srtConnItem(id, created, state, path string) map[string]any {
	item := map[string]any{
		"id":         id,
		"created":    created,
		"remoteAddr": "198.51.100.7:40000",
		"state":      state,
		"path":       path,
		"query":      "",
		"user":       "",
	}
	for i, name := range srtConnStatFields {
		item[name] = 1000 + i // 0 이 아닌 값 — 숫자 필드도 실제처럼 채운다
	}
	return item
}

// listBody 는 GET /v3/srtconns/list 의 응답 본문(APISRTConnList)이다.
func listBody(t *testing.T, pageCount int, items ...map[string]any) string {
	t.Helper()
	if items == nil {
		items = []map[string]any{} // 원본은 빈 목록도 [] 로 낸다(srt/server.go 209–211)
	}
	b, err := json.Marshal(map[string]any{"itemCount": len(items), "pageCount": pageCount, "items": items})
	if err != nil {
		t.Fatalf("Setup: json.Marshal of the list body returned error: %v", err)
	}
	return string(b)
}

// seenRequest 는 가짜 Control API 가 받은 요청 가운데 시험이 보는 부분이다. 자격은 맞았는지만 적는다.
type seenRequest struct {
	method  string
	path    string
	query   url.Values
	basicOK bool
}

// fakeAPI 는 MediaMTX Control API 를 흉내 내는 가짜다(실제 전송 — httptest). 받은 요청을 차례대로 적고, 새 TCP 연결
// 수를 센다.
type fakeAPI struct {
	srv   *httptest.Server
	conns atomic.Int32

	mu   sync.Mutex
	seen []seenRequest
}

// newFakeAPI 는 reply 로 답하는 가짜 Control API 를 띄운다.
func newFakeAPI(t *testing.T, reply http.HandlerFunc) *fakeAPI {
	t.Helper()
	a := &fakeAPI{}
	a.srv = httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		user, pass, ok := r.BasicAuth()
		a.mu.Lock()
		a.seen = append(a.seen, seenRequest{
			method:  r.Method,
			path:    r.URL.Path,
			query:   r.URL.Query(),
			basicOK: ok && user == testUser && pass == testPass,
		})
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

// requests 는 지금까지 받은 요청을 받은 차례대로 돌려준다.
func (a *fakeAPI) requests() []seenRequest {
	a.mu.Lock()
	defer a.mu.Unlock()
	return slices.Clone(a.seen)
}

// replyJSON 은 status 와 body 로 답한다.
func replyJSON(status int, body string) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = io.WriteString(w, body)
	}
}

// notFoundBody 는 원본이 없는 연결에 내는 본문이다(api.go 284–293 writeError · srt/server.go 25).
const notFoundBody = `{"status":"error","error":"connection not found"}`

// routeNotFoundBody 는 경로가 없거나 메서드가 다를 때 gin 이 내는 기본 404 본문이다 — text/plain(gin.go 33 · 759 ·
// 764–779). 인증 미들웨어를 지난 뒤에 난다. 판 올림으로 조회 · kick 경로가 사라지면 이 꼴이다.
const routeNotFoundBody = "404 page not found"

// replyText 는 status 와 text/plain 본문으로 답한다.
func replyText(status int, body string) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/plain")
		w.WriteHeader(status)
		_, _ = io.WriteString(w, body)
	}
}

// newTestClient 는 apiURL 의 Control API 를 시험 자격으로 부르는 클라이언트다.
func newTestClient(t *testing.T, apiURL string) *Client {
	t.Helper()
	c, err := NewClient(Options{APIURL: apiURL, User: testUser, Pass: NewSecret(testPass)})
	if err != nil {
		t.Fatalf("Setup: NewClient(APIURL=%q) returned error: %v", apiURL, err)
	}
	return c
}

// NewClient 는 설정을 기동 때 거른다(설계 2-5). APIURL 은 IP 리터럴 호스트의 http · https URL 이고 경로 · 사용자
// 정보 · 질의 · 조각이 없어야 한다 — 호스트 이름이면 Basic 자격이 DNS 가 가리키는 곳으로 가고, URL 의 자격은 에러
// 문구에 실린다. User · Pass 는 비면 안 되고 User 에는 ':' 가 없어야 한다(RFC 7617 2절 — Basic 은 첫 ':' 에서 가른다).
// 에러에는 어느 설정값도 싣지 않는다 — 자리를 잘못 넣으면 APIURL 자리에 비번이 들어갈 수 있다.
func TestNewClientRejectsBadOptions(t *testing.T) {
	valid := Options{APIURL: "http://192.0.2.1:9997", User: testUser, Pass: NewSecret(testPass)}
	tests := []struct {
		name string
		edit func(*Options)
	}{
		{name: "APIURL_빈_값", edit: func(o *Options) { o.APIURL = "" }},
		{name: "User_빈_값", edit: func(o *Options) { o.User = "" }},
		{name: "Pass_빈_값", edit: func(o *Options) { o.Pass = NewSecret("") }},
		{name: "Pass_영값", edit: func(o *Options) { o.Pass = Secret{} }},
		{name: "User_에_쌍점", edit: func(o *Options) { o.User = "ingest:auth" }},
		{name: "호스트_이름", edit: func(o *Options) { o.APIURL = "http://mediamtx:9997" }},
		{name: "scheme_없음", edit: func(o *Options) { o.APIURL = "//192.0.2.1:9997" }},
		{name: "scheme_ftp", edit: func(o *Options) { o.APIURL = "ftp://192.0.2.1:9997" }},
		{name: "호스트_없음", edit: func(o *Options) { o.APIURL = "http://:9997" }},
		{name: "사용자_정보", edit: func(o *Options) { o.APIURL = "http://" + testUser + ":" + testPass + "@192.0.2.1:9997" }},
		{name: "경로", edit: func(o *Options) { o.APIURL = "http://192.0.2.1:9997/v3" }},
		{name: "질의", edit: func(o *Options) { o.APIURL = "http://192.0.2.1:9997?x=" + testPass }},
		{name: "빈_질의", edit: func(o *Options) { o.APIURL = "http://192.0.2.1:9997/?" }},
		{name: "조각", edit: func(o *Options) { o.APIURL = "http://192.0.2.1:9997#" + testPass }},
		{name: "해석_불가", edit: func(o *Options) { o.APIURL = "http://[192.0.2.1:9997" }},
		{name: "다시_해석_불가_영역", edit: func(o *Options) { o.APIURL = "http://[fe80::1%25%20%5b]:9997" }},
		{name: "비번을_APIURL_자리에", edit: func(o *Options) { o.APIURL = testPass }},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			opt := valid
			tc.edit(&opt)
			c, err := NewClient(opt)
			if err == nil {
				t.Fatalf("NewClient(%s) = %p, nil error, want error", tc.name, c)
			}
			if msg := err.Error(); strings.Contains(msg, testPass) || (opt.APIURL != "" && strings.Contains(msg, opt.APIURL)) {
				t.Errorf("NewClient(%s) error carries a configured value (the password or APIURL)", tc.name)
			}
		})
	}
}

// IP 리터럴 호스트의 http · https URL 은 받는다 — 포트 · 끝 '/' · IPv6 · 영역 id 를 포함해.
func TestNewClientAcceptsIPLiteralURL(t *testing.T) {
	for _, apiURL := range []string{
		"http://192.0.2.1:9997",
		"http://192.0.2.1:9997/",
		"http://192.0.2.1",
		"https://192.0.2.1:9997",
		"http://[2001:db8::1]:9997",
		"http://[fe80::1%25eth0]:9997",
	} {
		if _, err := NewClient(Options{APIURL: apiURL, User: testUser, Pass: NewSecret(testPass)}); err != nil {
			t.Errorf("NewClient(APIURL=%q) returned error: %v", apiURL, err)
		}
	}
}

// 전송은 환경 변수의 프록시를 타지 않고(영값 Transport — Basic 자격이 프록시를 거치지 않는다), 리다이렉트를 따르지
// 않으며, 클라이언트 자체 시한을 두지 않는다(시한은 ctx 다 — keyresolve · mtxstate 와 같은 판단).
func TestNewClientUsesDirectTransportWithoutOwnTimeout(t *testing.T) {
	c := newTestClient(t, "http://192.0.2.1:9997")
	tr, ok := c.httpClient.Transport.(*http.Transport)
	if !ok {
		t.Fatalf("Transport = %T, want *http.Transport", c.httpClient.Transport)
	}
	if tr.Proxy != nil {
		t.Errorf("Transport.Proxy is set, want nil (no proxy from the environment)")
	}
	if c.httpClient.CheckRedirect == nil {
		t.Errorf("CheckRedirect is nil, want a policy that does not follow redirects")
	}
	if c.httpClient.Timeout != 0 {
		t.Errorf("http.Client.Timeout = %v, want 0 (the deadline is the ctx)", c.httpClient.Timeout)
	}
}

// 연결 준비(TCP 다이얼 · TLS 악수)에는 상한이 있다. Go 전송은 연결 준비를 요청 ctx 의 취소에서 떼어 두므로, 상한이 없으면
// Control API 가 SYN · TLS 를 붙잡을 때 요청은 부르는 쪽 ctx 가 끝나야 돌아오고 다이얼 고루틴과 소켓은 그 뒤에도 남는다.
// 영값 Transport 는 시한 없는 다이얼러를 쓴다(DialContext 가 nil). 다이얼러의 Timeout 값 자체는 함수 값 밖에서 읽을 수 없어,
// 다이얼러가 끼워졌는지와 같은 상수를 쓰는 TLSHandshakeTimeout 을 본다.
func TestNewClientBoundsConnectionSetup(t *testing.T) {
	c := newTestClient(t, "http://192.0.2.1:9997")

	tr, ok := c.httpClient.Transport.(*http.Transport)
	if !ok {
		t.Fatalf("Transport = %T, want *http.Transport", c.httpClient.Transport)
	}
	if tr.DialContext == nil {
		t.Error("Transport.DialContext is nil, want a dialer with a timeout (the zero Transport dials without one)")
	}
	if got, want := tr.TLSHandshakeTimeout, 2*time.Second; got != want {
		t.Errorf("Transport.TLSHandshakeTimeout = %v, want %v", got, want)
	}
}

// SRTConns 는 GET /v3/srtconns/list 를 한 번 부르고, itemsPerPage 에 받을 수 있는 가장 큰 값(2³¹−1 — 원본
// paginate.go 35행은 31비트까지 받는다)을 실어 어떤 목록도 한 페이지로 받는다(설계 2-5). 요청마다
// Basic 자격을 싣는다.
func TestSRTConnsRequestsOnePage(t *testing.T) {
	a := newFakeAPI(t, replyJSON(http.StatusOK, listBody(t, 0)))
	c := newTestClient(t, a.srv.URL)

	if _, err := c.SRTConns(t.Context()); err != nil {
		t.Fatalf("SRTConns() returned error: %v", err)
	}

	reqs := a.requests()
	if len(reqs) != 1 {
		t.Fatalf("SRTConns() sent %d requests, want 1", len(reqs))
	}
	r := reqs[0]
	if r.method != http.MethodGet || r.path != "/v3/srtconns/list" {
		t.Errorf("SRTConns() requested %s %s, want GET /v3/srtconns/list", r.method, r.path)
	}
	if got := r.query.Get("itemsPerPage"); got != "2147483647" {
		t.Errorf("SRTConns() itemsPerPage = %q, want %q (2³¹−1 — one page whatever the count)", got, "2147483647")
	}
	if got := r.query.Get("page"); got != "" && got != "0" {
		t.Errorf("SRTConns() page = %q, want the first page", got)
	}
	if !r.basicOK {
		t.Errorf("SRTConns() request does not carry the Basic credentials")
	}
}

// SRTConns 는 항목의 id · created · path · state 를 옮긴다. created 는 MediaMTX 가 쓴 시각대 그대로 해석한다 — 같은
// 순간이면 시각대가 달라도 같다. state 는 셋 다 받는다 — 등록 전 idle(path 빈 값) · 송출 publish · 읽기 read(path 참).
func TestSRTConnsDecodesItems(t *testing.T) {
	body := listBody(t, 1,
		srtConnItem(testID, "2026-10-12T09:00:00.123456789Z", "idle", ""),
		srtConnItem(testID2, "2026-10-12T18:00:01+09:00", "publish", testPath),
		srtConnItem(testID3, "2026-10-12T09:00:02Z", "read", testPath))
	a := newFakeAPI(t, replyJSON(http.StatusOK, body))
	c := newTestClient(t, a.srv.URL)

	got, err := c.SRTConns(t.Context())
	if err != nil {
		t.Fatalf("SRTConns() returned error: %v", err)
	}
	want := []SRTConn{
		{ID: testID, Created: time.Date(2026, 10, 12, 9, 0, 0, 123456789, time.UTC), Path: "", State: "idle"},
		{ID: testID2, Created: time.Date(2026, 10, 12, 9, 0, 1, 0, time.UTC), Path: testPath, State: "publish"},
		{ID: testID3, Created: time.Date(2026, 10, 12, 9, 0, 2, 0, time.UTC), Path: testPath, State: "read"},
	}
	if len(got) != len(want) {
		t.Fatalf("SRTConns() = %+v, want %+v", got, want)
	}
	for i := range want {
		g, w := got[i], want[i]
		if g.ID != w.ID || !g.Created.Equal(w.Created) || g.Path != w.Path || g.State != w.State {
			t.Errorf("SRTConns()[%d] = %+v, want %+v", i, g, w)
		}
	}
}

// 연결이 하나도 없으면 원본은 pageCount 0 · items [] 를 낸다(paginate.go 13–15) — 에러가 아니라 빈 목록이다.
func TestSRTConnsAcceptsEmptyList(t *testing.T) {
	a := newFakeAPI(t, replyJSON(http.StatusOK, listBody(t, 0)))
	c := newTestClient(t, a.srv.URL)

	got, err := c.SRTConns(t.Context())
	if err != nil || len(got) != 0 {
		t.Errorf("SRTConns() of an empty server = %+v, %v, want no connections and nil error", got, err)
	}
}

// 돌아온 pageCount 가 1 보다 크면 에러다 — 여러 페이지로 받으면 페이지 사이에 사라진 연결 때문에 뒤 항목이 앞으로
// 밀려 하나가 건너뛰어질 수 있다(설계 2-5 766행). 가짜는 다음 페이지를 물으면 남은 항목을 준다 —
// 페이지를 이어 받는 클라이언트면 에러 없이 끝난다.
func TestSRTConnsRejectsMultiplePages(t *testing.T) {
	first := listBody(t, 2, srtConnItem(testID, "2026-10-12T09:00:00Z", "idle", ""))
	second := listBody(t, 2, srtConnItem(testID2, "2026-10-12T09:00:01Z", "publish", testPath))
	a := newFakeAPI(t, func(w http.ResponseWriter, r *http.Request) {
		body := first
		if r.URL.Query().Get("page") == "1" {
			body = second
		}
		replyJSON(http.StatusOK, body)(w, r)
	})
	c := newTestClient(t, a.srv.URL)

	got, err := c.SRTConns(t.Context())

	var e *Error
	if !errors.As(err, &e) || e.Op != "list" || e.Kind != "pages" {
		t.Fatalf("SRTConns() with pageCount 2 = %+v, %v, want *Error{Op: list, Kind: pages}", got, err)
	}
	if got != nil {
		t.Errorf("SRTConns() with pageCount 2 returned %+v, want nil with the error", got)
	}
}

// 본문이 계약 꼴이 아니면 parse 에러다. items 가 없으면 빈 목록이 아니다 — 응답의 꼴이 바뀐 것을 「연결 0」으로
// 읽으면 거짓 성공이다. 항목에는 id(소문자 UUID)와 created 가 있어야 한다. created 가 영값인 항목을 넘기면 절단의
// 기록 밖 갈래가 「Since 앞 · 나이 무한」으로 읽어 끊는다. state 는 idle · read · publish 가운데 하나이고 idle 일 때만
// path 가 빈다(srt/conn.go 72–77 · 254–259 · 323–329행) — 판 올림으로 등록 전 연결의 state 가 새 값이 되거나 path 가
// 등록 전에 차면, 줄이 밀려난 판독 중 연결이 기록 밖 갈래에서 빠진다. itemCount 는 원본이 페이지를 나누기 전의 전체
// 수라(api_srt.go 21행) items 의 수와 같아야 한다 — 다르면 목록이 말없이 잘린 것이다.
func TestSRTConnsRejectsMalformedBody(t *testing.T) {
	okItem := srtConnItem(testID, "2026-10-12T09:00:00Z", "idle", "")
	without := func(key string) map[string]any {
		item := srtConnItem(testID2, "2026-10-12T09:00:00Z", "idle", "")
		delete(item, key)
		return item
	}
	item := func(state, path string) map[string]any {
		return srtConnItem(testID2, "2026-10-12T09:00:00Z", state, path)
	}
	// counted 는 itemCount 를 따로 정한 한 페이지 본문이다. itemCount 가 음수면 그 필드를 뺀다.
	counted := func(itemCount int, items ...map[string]any) string {
		body := map[string]any{"pageCount": 1, "items": items}
		if itemCount >= 0 {
			body["itemCount"] = itemCount
		}
		b, err := json.Marshal(body)
		if err != nil {
			t.Fatalf("Setup: json.Marshal of the list body returned error: %v", err)
		}
		return string(b)
	}
	tests := []struct {
		name string
		body string
	}{
		{name: "JSON_아님", body: "not json"},
		{name: "JSON_null", body: "null"},
		{name: "items_없음", body: `{"itemCount":0,"pageCount":0}`},
		{name: "items_null", body: `{"itemCount":0,"pageCount":0,"items":null}`},
		{name: "id_없음", body: listBody(t, 1, okItem, without("id"))},
		{name: "id_대문자", body: listBody(t, 1, srtConnItem(strings.ToUpper(testID2), "2026-10-12T09:00:00Z", "idle", ""))},
		{name: "id_꼴_아님", body: listBody(t, 1, srtConnItem("../config/global/patch", "2026-10-12T09:00:00Z", "idle", ""))},
		{name: "created_없음", body: listBody(t, 1, okItem, without("created"))},
		{name: "created_꼴_아님", body: listBody(t, 1, srtConnItem(testID2, "yesterday", "idle", ""))},
		{name: "state_모르는_값", body: listBody(t, 1, okItem, item("authenticating", ""))},
		{name: "state_빈_값", body: listBody(t, 1, okItem, item("", ""))},
		{name: "state_없음", body: listBody(t, 1, okItem, without("state"))},
		{name: "idle_인데_path", body: listBody(t, 1, okItem, item("idle", testPath))},
		{name: "publish_인데_빈_path", body: listBody(t, 1, okItem, item("publish", ""))},
		{name: "read_인데_빈_path", body: listBody(t, 1, okItem, item("read", ""))},
		{name: "itemCount_가_적음", body: counted(1, okItem, item("idle", ""))},
		{name: "itemCount_가_많음_잘린_목록", body: counted(3, okItem, item("idle", ""))},
		{name: "itemCount_없음", body: counted(-1, okItem)},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			a := newFakeAPI(t, replyJSON(http.StatusOK, tc.body))
			c := newTestClient(t, a.srv.URL)

			got, err := c.SRTConns(t.Context())

			var e *Error
			if !errors.As(err, &e) || e.Op != "list" || e.Kind != "parse" {
				t.Errorf("SRTConns() of %s = %+v, %v, want *Error{Op: list, Kind: parse}", tc.name, got, err)
			}
		})
	}
}

// 목록 본문은 64 MiB 까지 읽는다(항목 하나는 측정 샘플로 약 2.4 KB — 고정 상한이 아니다). 상한과 같은 크기는 받고, 한
// 바이트 넘으면 parse 에러다. 본문은 빈 목록 뒤를 공백으로 채운 온전한 JSON 이다.
func TestSRTConnsReadsBodyUpTo64MiB(t *testing.T) {
	empty := listBody(t, 0)
	for _, tc := range []struct {
		size    int
		wantErr bool
	}{
		{size: 64 << 20, wantErr: false},
		{size: 64<<20 + 1, wantErr: true},
	} {
		body := empty + strings.Repeat(" ", tc.size-len(empty))
		a := newFakeAPI(t, replyJSON(http.StatusOK, body))
		c := newTestClient(t, a.srv.URL)

		_, err := c.SRTConns(t.Context())

		var e *Error
		if gotErr := errors.As(err, &e) && e.Kind == "parse"; gotErr != tc.wantErr || (!tc.wantErr && err != nil) {
			t.Errorf("SRTConns() of a %d-byte body = %v, want parse error %t", tc.size, err, tc.wantErr)
		}
	}
}

// SRTConnExists 는 GET /v3/srtconns/get/{id} 다 — 200 이면 참, 연결 없음 404(Control API 의 에러 꼴 본문)면 거짓, 그 밖의
// 상태는 에러다. 경로가 없는 404(gin 기본 — text/plain)를 「사라짐」으로 읽으면 판 올림에서 확인이 거짓으로 끝난다.
func TestSRTConnExists(t *testing.T) {
	tests := []struct {
		name       string
		status     int
		body       string
		text       bool // 본문을 text/plain 으로 낸다(gin 기본 404)
		want       bool
		wantStatus int // 0 이면 에러 없음
	}{
		{name: "200", status: http.StatusOK, body: `{"id":"` + testID + `"}`, want: true},
		{name: "404", status: http.StatusNotFound, body: notFoundBody, want: false},
		{name: "404_경로_없음", status: http.StatusNotFound, body: routeNotFoundBody, text: true, wantStatus: http.StatusNotFound},
		{name: "404_빈_본문", status: http.StatusNotFound, body: "", wantStatus: http.StatusNotFound},
		{name: "404_에러_꼴_아닌_JSON", status: http.StatusNotFound, body: `{"status":"ok"}`, wantStatus: http.StatusNotFound},
		{name: "401", status: http.StatusUnauthorized, body: `{"status":"error","error":"authentication error"}`, wantStatus: http.StatusUnauthorized},
		{name: "500", status: http.StatusInternalServerError, body: `{"status":"error","error":"terminated"}`, wantStatus: http.StatusInternalServerError},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			reply := replyJSON(tc.status, tc.body)
			if tc.text {
				reply = replyText(tc.status, tc.body)
			}
			a := newFakeAPI(t, reply)
			c := newTestClient(t, a.srv.URL)

			got, err := c.SRTConnExists(t.Context(), testID)

			if tc.wantStatus == 0 {
				if err != nil || got != tc.want {
					t.Errorf("SRTConnExists(%q) answered %d = %t, %v, want %t, nil", testID, tc.status, got, err, tc.want)
				}
			} else {
				var e *Error
				if !errors.As(err, &e) || e.Op != "get" || e.Kind != "status" || e.Status != tc.wantStatus {
					t.Errorf("SRTConnExists(%q) answered %d = %t, %v, want *Error{Op: get, Kind: status, Status: %d}", testID, tc.status, got, err, tc.wantStatus)
				}
			}
			reqs := a.requests()
			if len(reqs) != 1 || reqs[0].method != http.MethodGet || reqs[0].path != "/v3/srtconns/get/"+testID || !reqs[0].basicOK {
				t.Errorf("SRTConnExists(%q) sent %+v, want one GET /v3/srtconns/get/%s with the Basic credentials", testID, reqs, testID)
			}
		})
	}
}

// KickSRTConn 은 POST /v3/srtconns/kick/{id} 다 — 200 이면 nil, 연결 없음 404(Control API 의 에러 꼴 본문)면
// ErrNotFound(이미 없다), 그 밖의 상태는 에러다. 경로가 없는 404(gin 기본 — text/plain)는 ErrNotFound 가 아니다 — 판
// 올림에서 kick 경로만 사라지면 끊지 못한 연결을 「이미 없음」으로 세어 거짓 성공이 된다.
func TestKickSRTConn(t *testing.T) {
	tests := []struct {
		name       string
		status     int
		body       string
		text       bool  // 본문을 text/plain 으로 낸다(gin 기본 404)
		wantErr    error // ErrNotFound 를 기대할 때
		wantStatus int   // 0 이고 wantErr 도 nil 이면 에러 없음
	}{
		{name: "200", status: http.StatusOK, body: `{"status":"ok"}`},
		{name: "404", status: http.StatusNotFound, body: notFoundBody, wantErr: ErrNotFound},
		{name: "404_경로_없음", status: http.StatusNotFound, body: routeNotFoundBody, text: true, wantStatus: http.StatusNotFound},
		{name: "404_빈_본문", status: http.StatusNotFound, body: "", wantStatus: http.StatusNotFound},
		{name: "404_에러_꼴_아닌_JSON", status: http.StatusNotFound, body: `{"status":"ok"}`, wantStatus: http.StatusNotFound},
		{name: "401", status: http.StatusUnauthorized, body: `{"status":"error","error":"authentication error"}`, wantStatus: http.StatusUnauthorized},
		{name: "500", status: http.StatusInternalServerError, body: `{"status":"error","error":"terminated"}`, wantStatus: http.StatusInternalServerError},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			reply := replyJSON(tc.status, tc.body)
			if tc.text {
				reply = replyText(tc.status, tc.body)
			}
			a := newFakeAPI(t, reply)
			c := newTestClient(t, a.srv.URL)

			err := c.KickSRTConn(t.Context(), testID)

			switch {
			case tc.wantErr != nil:
				if !errors.Is(err, tc.wantErr) {
					t.Errorf("KickSRTConn(%q) answered %d = %v, want %v", testID, tc.status, err, tc.wantErr)
				}
			case tc.wantStatus != 0:
				var e *Error
				if !errors.As(err, &e) || e.Op != "kick" || e.Kind != "status" || e.Status != tc.wantStatus || errors.Is(err, ErrNotFound) {
					t.Errorf("KickSRTConn(%q) answered %d = %v, want *Error{Op: kick, Kind: status, Status: %d}", testID, tc.status, err, tc.wantStatus)
				}
			default:
				if err != nil {
					t.Errorf("KickSRTConn(%q) answered %d = %v, want nil", testID, tc.status, err)
				}
			}
			reqs := a.requests()
			if len(reqs) != 1 || reqs[0].method != http.MethodPost || reqs[0].path != "/v3/srtconns/kick/"+testID || !reqs[0].basicOK {
				t.Errorf("KickSRTConn(%q) sent %+v, want one POST /v3/srtconns/kick/%s with the Basic credentials", testID, reqs, testID)
			}
		})
	}
}

// 404 의 본문을 읽다가 연결이 끊기면 Control API 의 에러 꼴인지 알 수 없다 — 조회 · kick 모두 「없음」으로 읽지 않고
// 그 상태의 에러다.
func TestClient404BodyCutShortIsNotGone(t *testing.T) {
	a := newFakeAPI(t, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Content-Length", "4096")
		w.WriteHeader(http.StatusNotFound)
		_, _ = io.WriteString(w, `{"status":"error","error":"connection not found"}`) // 약속한 길이보다 짧게 쓰고 끝낸다
	})
	c := newTestClient(t, a.srv.URL)

	kickErr := c.KickSRTConn(t.Context(), testID)
	_, getErr := c.SRTConnExists(t.Context(), testID)

	for _, tc := range []struct {
		op  string
		err error
	}{{op: "kick", err: kickErr}, {op: "get", err: getErr}} {
		var e *Error
		if !errors.As(tc.err, &e) || e.Op != tc.op || e.Kind != "status" || e.Status != http.StatusNotFound || errors.Is(tc.err, ErrNotFound) {
			t.Errorf("%s answered a 404 whose body is cut short = %v, want *Error{Op: %s, Kind: status, Status: 404}", tc.op, tc.err, tc.op)
		}
	}
}

// 조회 · kick 은 MediaMTX 연결 id 꼴(소문자 UUID)이 아닌 id 로는 요청을 내지 않는다. 그 id 는 허용 연결 기록(파일
// 내용)에서 온다 — '..' · '/' 가 든 값이 다른 경로로 가 404 를 받으면 「이미 없음」으로 읽히고, 다른 엔드포인트를 부를
// 수도 있다. 에러는 ErrNotFound 가 아니다.
func TestClientRejectsMalformedConnID(t *testing.T) {
	a := newFakeAPI(t, replyJSON(http.StatusNotFound, notFoundBody))
	c := newTestClient(t, a.srv.URL)
	for _, id := range []string{
		"",
		"..",
		"../../config/global/patch",
		testID + "/x",
		testID + "?itemsPerPage=1",
		strings.ToUpper(testID),
		"{" + testID + "}",
		testID[:35],
	} {
		if err := c.KickSRTConn(t.Context(), id); err == nil || errors.Is(err, ErrNotFound) {
			t.Errorf("KickSRTConn(%q) = %v, want an error other than ErrNotFound", id, err)
		}
		if got, err := c.SRTConnExists(t.Context(), id); err == nil {
			t.Errorf("SRTConnExists(%q) = %t, nil error, want an error", id, got)
		}
	}
	if reqs := a.requests(); len(reqs) != 0 {
		t.Errorf("malformed ids sent %d requests %+v, want none", len(reqs), reqs)
	}
}

// 에러 문구에는 요청 갈래와 상태 코드만 실린다 — 응답 본문(MediaMTX 의 에러 문구)도, 자격도 싣지 않는다(설계 2-5
// 768행 · 6절 누설 규칙).
func TestClientErrorsCarryOnlyStatus(t *testing.T) {
	const canary = "CANARY-mtxctl-response-body"
	a := newFakeAPI(t, replyJSON(http.StatusInternalServerError, `{"status":"error","error":"`+canary+` `+testPass+`"}`))
	c := newTestClient(t, a.srv.URL)

	_, listErr := c.SRTConns(t.Context())
	_, getErr := c.SRTConnExists(t.Context(), testID)
	kickErr := c.KickSRTConn(t.Context(), testID)

	// 실패 문구에는 에러 원문 · 카나리아 값을 싣지 않는다 — 갈래 · 카나리아 이름 · 횟수 · 길이만.
	for _, tc := range []struct {
		op  string
		err error
	}{{op: "list", err: listErr}, {op: "get", err: getErr}, {op: "kick", err: kickErr}} {
		if tc.err == nil {
			t.Errorf("%s against a Control API answering 500 returned nil error, want an error", tc.op)
			continue
		}
		msg := tc.err.Error()
		for _, c := range []struct{ name, value string }{
			{name: "response body", value: canary},
			{name: "API password", value: testPass},
			{name: "API user", value: testUser},
		} {
			if n := strings.Count(msg, c.value); n > 0 {
				t.Errorf("%s error carries the %s canary %d time(s), want 0 (error length %d)", tc.op, c.name, n, len(msg))
			}
		}
		if !strings.Contains(msg, "500") {
			t.Errorf("%s error does not carry the status code 500 (error length %d)", tc.op, len(msg))
		}
	}
}

// 연결 실패는 unreachable, ctx 가 끝나 못 받은 답은 timeout 갈래다. 붙잡는 서버라도 ctx 가 끝나면 곧 돌아온다. 에러
// 문구에는 원인 에러의 문구(요청 URL 을 담는다)가 실리지 않는다.
func TestClientTransportFailures(t *testing.T) {
	t.Run("unreachable", func(t *testing.T) {
		a := newFakeAPI(t, replyJSON(http.StatusOK, "{}"))
		c := newTestClient(t, a.srv.URL)
		a.srv.Close()

		_, listErr := c.SRTConns(t.Context())
		_, getErr := c.SRTConnExists(t.Context(), testID)

		for _, tc := range []struct {
			op  string
			err error
		}{{"list", listErr}, {"get", getErr}} {
			var e *Error
			if !errors.As(tc.err, &e) || e.Op != tc.op || e.Kind != "unreachable" {
				t.Errorf("%s against a closed server = %v, want *Error{Op: %s, Kind: unreachable}", tc.op, tc.err, tc.op)
				continue
			}
			if msg := tc.err.Error(); strings.Contains(msg, strings.TrimPrefix(a.srv.URL, "http://")) {
				t.Errorf("%s error carries the request URL (error length %d)", tc.op, len(msg))
			}
		}
	})
	t.Run("timeout", func(t *testing.T) {
		release := make(chan struct{})
		a := newFakeAPI(t, func(w http.ResponseWriter, r *http.Request) {
			select {
			case <-r.Context().Done():
			case <-release:
			}
		})
		t.Cleanup(func() { close(release) }) // 서버 닫기가 붙잡힌 답을 기다리다 멈추지 않게 먼저 푼다
		c := newTestClient(t, a.srv.URL)
		ctx, cancel := context.WithTimeout(t.Context(), 50*time.Millisecond)
		defer cancel()

		start := time.Now()
		err := c.KickSRTConn(ctx, testID)

		var e *Error
		if !errors.As(err, &e) || e.Op != "kick" || e.Kind != "timeout" {
			t.Errorf("KickSRTConn() against a hanging server = %v, want *Error{Op: kick, Kind: timeout}", err)
		}
		if took := time.Since(start); took > 2*time.Second {
			t.Errorf("KickSRTConn() with a 50ms ctx returned after %v, want soon after the ctx ended", took)
		}
	})
}

// 200 뒤 목록 본문을 읽다가 연결이 끊기면 unreachable 이다 — 덜 받은 목록을 해석하지 않는다.
func TestSRTConnsBodyFailureAfter200(t *testing.T) {
	a := newFakeAPI(t, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Length", "4096")
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, `{"itemCount":0,"pageCount":0,"items":[]}`) // 약속한 길이보다 짧게 쓰고 끝낸다
	})
	c := newTestClient(t, a.srv.URL)

	got, err := c.SRTConns(t.Context())

	var e *Error
	if !errors.As(err, &e) || e.Op != "list" || e.Kind != "unreachable" {
		t.Errorf("SRTConns() whose body is cut short = %+v, %v, want *Error{Op: list, Kind: unreachable}", got, err)
	}
}

// 리다이렉트를 따르지 않는다 — 따라가면 Basic 자격이 다른 곳으로 간다. 3xx 는 그 상태의 에러다.
func TestClientDoesNotFollowRedirects(t *testing.T) {
	other := newFakeAPI(t, replyJSON(http.StatusOK, listBody(t, 0)))
	a := newFakeAPI(t, func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, other.srv.URL+r.URL.RequestURI(), http.StatusTemporaryRedirect)
	})
	c := newTestClient(t, a.srv.URL)

	_, err := c.SRTConns(t.Context())

	var e *Error
	if !errors.As(err, &e) || e.Kind != "status" || e.Status != http.StatusTemporaryRedirect {
		t.Errorf("SRTConns() answered 307 = %v, want *Error{Kind: status, Status: 307}", err)
	}
	if n := len(other.requests()); n != 0 {
		t.Errorf("redirect target received %d requests, want 0", n)
	}
}

// 확인 루프는 같은 id 를 여러 번 조회한다. 본문을 비우고 닫아 TCP 연결 하나를 다시 쓴다.
func TestClientReusesConnection(t *testing.T) {
	a := newFakeAPI(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost:
			replyJSON(http.StatusNotFound, notFoundBody)(w, r)
		case strings.HasSuffix(r.URL.Path, "/list"):
			replyJSON(http.StatusOK, listBody(t, 1, srtConnItem(testID, "2026-10-12T09:00:00Z", "idle", "")))(w, r)
		default:
			replyJSON(http.StatusOK, `{"id":"`+testID+`"}`)(w, r)
		}
	})
	c := newTestClient(t, a.srv.URL)

	for range 5 {
		if _, err := c.SRTConns(t.Context()); err != nil {
			t.Fatalf("SRTConns() returned error: %v", err)
		}
		if _, err := c.SRTConnExists(t.Context(), testID); err != nil {
			t.Fatalf("SRTConnExists() returned error: %v", err)
		}
		if err := c.KickSRTConn(t.Context(), testID); !errors.Is(err, ErrNotFound) {
			t.Fatalf("KickSRTConn() = %v, want ErrNotFound", err)
		}
	}
	if n := a.conns.Load(); n != 1 {
		t.Errorf("15 sequential requests opened %d TCP connections, want 1", n)
	}
}

// Options 와 Client 는 fmt · slog(Text · JSON) · encoding/json 어디로 찍어도 비번이 나오지 않는다 — 기동 로그 ·
// 에러 문구에 통째로 넘겨도 새지 않게(설계 6절 누설 규칙 — API 비번). 다른 값의 공개 필드로 담겨 찍혀도 같다.
func TestOptionsAndClientHidePassword(t *testing.T) {
	opt := Options{APIURL: "http://192.0.2.1:9997", User: testUser, Pass: NewSecret(testPass)}
	c := newTestClient(t, opt.APIURL)
	holder := struct {
		Opt    Options
		Client *Client
		Value  Client
	}{opt, c, *c}

	var outs []string
	for _, verb := range []string{"%v", "%+v", "%#v", "%s", "%q", "%x", "%d"} {
		outs = append(outs,
			fmt.Sprintf(verb, opt), fmt.Sprintf(verb, &opt),
			fmt.Sprintf(verb, c), fmt.Sprintf(verb, *c),
			fmt.Sprintf(verb, holder))
	}
	for _, newHandler := range []func(io.Writer) slog.Handler{
		func(w io.Writer) slog.Handler { return slog.NewTextHandler(w, nil) },
		func(w io.Writer) slog.Handler { return slog.NewJSONHandler(w, nil) },
	} {
		var buf bytes.Buffer
		slog.New(newHandler(&buf)).Info("probe", "opt", opt, "client", c, "value", *c)
		outs = append(outs, buf.String())
	}
	if b, err := json.Marshal(opt); err != nil {
		t.Errorf("json.Marshal(Options) returned error: %v", err)
	} else {
		outs = append(outs, string(b))
	}

	// 원문만 보면 %x(16진) · Basic 헤더 값(base64)으로 찍힌 비번을 놓친다.
	encodings := []string{
		testPass,
		hex.EncodeToString([]byte(testPass)),
		base64.StdEncoding.EncodeToString([]byte(testUser + ":" + testPass)),
	}
	for i, out := range outs {
		for j, enc := range encodings {
			if strings.Contains(out, enc) {
				t.Errorf("output %d carries the password (encoding %d, output length %d)", i, j, len(out))
			}
		}
	}
}
