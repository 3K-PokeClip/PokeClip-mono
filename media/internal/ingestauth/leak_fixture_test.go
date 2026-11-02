package ingestauth

import (
	"bytes"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxctl"
)

// 누설 시험이 새로 심는 카나리아 값이다(합성 — 어떤 계정의 값도 아니다). 고정물의 passphrase · 출입증 · API 비번과
// 함께 출력 어디에도 나와서는 안 된다.
const (
	leakAPIUser         = "synthetic-api-user-leak-0001"     // 누설 고정물의 action=api 사용자 — 허용 행의 요청 user 로 흐른다
	leakRequestUser     = "synthetic-mtx-user-leak-0001"     // MediaMTX 요청의 user(streamid 의 u · api 행의 사용자)
	leakRequestPassword = "synthetic-mtx-password-leak-0001" // MediaMTX 요청의 password(streamid 의 s · api 행의 비번)
	leakRequestToken    = "synthetic-mtx-jwt-leak-0001"      // MediaMTX 요청의 token
	leakRequestQuery    = "synthetic-mtx-query-leak-0001"    // MediaMTX 요청의 query
	leakAuthReason      = "SYNTHETIC_AUTH_REASON_LEAK_0001"  // 4C valid:false 의 이유 — 응답 본문의 임의 문자열
	leakBadPassphrase   = "Synthetic.passphrase.leak.0001"   // auth 가 돌려준 형식 밖 passphrase('.' 은 받지 않는다)
	leakCallerPass      = "synthetic-caller-pass-leak-0001"  // 호출자 Basic 의 틀린 비번 — 거절 행의 Authorization 으로 흐른다
)

// leakCanary 는 출력 어디에도 나와서는 안 되는 합성 비밀 하나다. 실패 문구에는 name 만 싣는다.
type leakCanary struct {
	name  string
	value string
}

// leakCanaries 는 이 패키지 누설 시험의 카나리아다 — 누설 규칙의 passphrase · 출입증 · Basic 자격 · MediaMTX 요청의
// user(허용 행의 API 사용자 포함) · password · token · query · 응답 본문.
var leakCanaries = []leakCanary{
	{name: "passphrase", value: testPassphrase},
	{name: "형식 밖 passphrase", value: leakBadPassphrase},
	{name: "resolve 출입증", value: testResolveToken},
	{name: "API 사용자", value: leakAPIUser},
	{name: "API 비번", value: testAPIPass},
	{name: "Basic 자격", value: basicCredentials(leakAPIUser, testAPIPass)},
	{name: "틀린 Basic 자격", value: basicCredentials(leakRequestUser, leakRequestPassword)},
	{name: "MediaMTX 요청 user", value: leakRequestUser},
	{name: "MediaMTX 요청 password", value: leakRequestPassword},
	{name: "MediaMTX 요청 token", value: leakRequestToken},
	{name: "MediaMTX 요청 query", value: leakRequestQuery},
	{name: "4C 응답 이유", value: leakAuthReason},
	{name: "호출자 Basic 비번", value: testCallerPass},
	{name: "호출자 Basic 비번(16진)", value: hex.EncodeToString([]byte(testCallerPass))},
	{name: "호출자 Basic 자격", value: basicCredentials(testCallerUser, testCallerPass)},
	{name: "틀린 호출자 Basic 비번", value: leakCallerPass},
	{name: "틀린 호출자 Basic 비번(16진)", value: hex.EncodeToString([]byte(leakCallerPass))},
	{name: "틀린 호출자 Basic 자격", value: basicCredentials(testCallerUser, leakCallerPass)},
}

// basicCredentials 는 user · pass 의 Basic 자격 — Authorization 머리에 실리는 base64(RFC 7617 2절) — 이다. 머리째 새면
// 평문 카나리아로는 보이지 않는다.
func basicCredentials(user, pass string) string {
	return base64.StdEncoding.EncodeToString([]byte(user + ":" + pass))
}

// leakOutput 은 누설을 훑는 출력 자리 하나다. place 는 실패 문구에 싣는 자리 이름이다.
type leakOutput struct {
	place   string
	content string
}

// leakProblems 는 outputs 에서 찾은 카나리아와, responses 가운데 본문을 실은 비-2xx 응답(누설 규칙 3)을 실패 문구로
// 돌려준다. 문구에는 출력 자리 · 카나리아 이름 · 횟수(응답은 상태 · 본문 크기)만 싣는다.
func leakProblems(outputs []leakOutput, responses []*httptest.ResponseRecorder) []string {
	var problems []string
	for _, o := range outputs {
		for _, c := range leakCanaries {
			if n := strings.Count(o.content, c.value); n > 0 {
				problems = append(problems, fmt.Sprintf("%s carries the %s canary %d time(s), want 0", o.place, c.name, n))
			}
		}
	}
	for _, rr := range responses {
		if rr.Code/100 != 2 && rr.Body.Len() > 0 {
			problems = append(problems, fmt.Sprintf("a %d response carries a %d-byte body, want none (누설 규칙 3)", rr.Code, rr.Body.Len()))
		}
	}
	return problems
}

// leakEvent 는 slog JSON 한 줄 가운데 양성 대조가 보는 필드다.
type leakEvent struct {
	Level  string `json:"level"`
	Msg    string `json:"msg"`
	Reason string `json:"reason"`
	Wait   string `json:"wait"`
	Scope  string `json:"scope"`
}

// leakEvents 는 out 의 slog JSON 줄을 차례대로 해석한다. JSON 객체가 아닌 줄이 있으면 그 줄 번호만 담은 에러다 — 줄
// 내용은 싣지 않는다(카나리아가 들어 있을 수 있다).
func leakEvents(out string) ([]leakEvent, error) {
	var events []leakEvent
	n := 0
	for line := range strings.Lines(out) {
		n++
		var e leakEvent
		if err := json.Unmarshal([]byte(line), &e); err != nil {
			return nil, fmt.Errorf("slog line %d is not a JSON object", n)
		}
		events = append(events, e)
	}
	return events, nil
}

// hasEvent 는 events 가운데 want 의 비지 않은 필드가 모두 같은 이벤트가 있는지 본다.
func hasEvent(events []leakEvent, want leakEvent) bool {
	same := func(want, got string) bool { return want == "" || want == got }
	for _, e := range events {
		if e.Msg == want.Msg && same(want.Level, e.Level) && same(want.Reason, e.Reason) && same(want.Wait, e.Wait) && same(want.Scope, e.Scope) {
			return true
		}
	}
	return false
}

// leakPublishBody 는 token · connID 의 SRT publish 인증 본문이다(publishBody) — publish 가 읽지 않는 user · password ·
// token · query 에 카나리아를 싣는다(설계 2-4 「읽지도 기록하지도 않는 필드」).
func leakPublishBody(token, connID string) mtxAuthBody {
	b := publishBody(token, connID)
	b.User, b.Password, b.Token, b.Query = leakRequestUser, leakRequestPassword, leakRequestToken, leakRequestQuery
	return b
}

// leakAPIBody 는 ip · user · pass 의 api 인증 본문이다(apiBody) — api 행이 보지 않는 token · query 에도 카나리아를 싣는다.
func leakAPIBody(ip, user, pass string) mtxAuthBody {
	b := apiBody(ip, user, pass)
	b.Token, b.Query = leakRequestToken, leakRequestQuery
	return b
}

// leakFixture 는 누설 시험의 처리기 고정물이다 — handlerFixture 에 기록의 회전 로그를 처리기 로그와 같은 모음으로 잇고
// (고정물은 회전 로그를 버린다), 처리기가 낸 응답을 모두 모은다(누설 규칙 3). action=api 사용자는 카나리아 leakAPIUser
// 다 — 허용 행의 요청 user 도 카나리아로 흐른다(고정물의 testAPIUser 는 흔한 이름이라 카나리아로 쓸 수 없다).
type leakFixture struct {
	*handlerFixture

	mu   sync.Mutex
	sent []*httptest.ResponseRecorder // 처리기가 낸 응답(차례대로). 가짜 MediaMTX 의 고루틴에서도 더한다
}

func newLeakFixture(t *testing.T, o fixtureOptions) *leakFixture {
	t.Helper()
	o.apiUser = leakAPIUser
	f := newHandlerFixture(t, o)
	f.h.conns.log = f.h.log
	return &leakFixture{handlerFixture: f}
}

// newLeakMTXClient 는 누설 고정물의 API 자격(leakAPIUser · testAPIPass)으로 apiURL 의 Control API 를 부르는 실제
// 클라이언트다.
func newLeakMTXClient(t *testing.T, apiURL string) *mtxctl.Client {
	t.Helper()
	c, err := mtxctl.NewClient(mtxctl.Options{APIURL: apiURL, User: leakAPIUser, Pass: mtxctl.NewSecret(testAPIPass)})
	if err != nil {
		t.Fatalf("Setup: mtxctl.NewClient returned %T, want a client", err)
	}
	return c
}

// readFailsAfter 는 data 를 내준 뒤 읽기마다 실패하는 요청 본문이다 — 본문을 읽다가 실패하는 갈래(read_error)에
// 카나리아를 흘린다.
type readFailsAfter struct{ data []byte }

func (b *readFailsAfter) Read(p []byte) (int, error) {
	if len(b.data) == 0 {
		return 0, errors.New("synthetic read failure")
	}
	n := copy(p, b.data)
	b.data = b.data[n:]
	return n, nil
}

func (b *readFailsAfter) Close() error { return nil }

// send 는 r 을 처리기 전체(바깥 층 · 가드 · 안쪽)에 보내고 응답을 모은다.
func (f *leakFixture) send(r *http.Request) *httptest.ResponseRecorder {
	return f.record(f.serve(r))
}

// sendInner 는 r 을 바깥 층 · 가드 없이 안쪽 처리기에 보내고 응답을 모은다 — 가드가 이미 끝낸 요청(끝난 ctx)을 실제로
// 기다리지 않고 일으키는 자리다.
func (f *leakFixture) sendInner(r *http.Request) *httptest.ResponseRecorder {
	rr := httptest.NewRecorder()
	f.h.ServeHTTP(rr, r)
	return f.record(rr)
}

// publishLeaky 는 token · connID 의 SRT publish 를 비밀 필드에 카나리아를 실어 보낸다.
func (f *leakFixture) publishLeaky(t *testing.T, token, connID string) *httptest.ResponseRecorder {
	t.Helper()
	return f.send(newAuthRequest(t, leakPublishBody(token, connID)))
}

// authorizeAPI 는 가짜 MediaMTX 가 Control API 요청의 Basic 자격을 응답기의 api 행에 묻는 자리다(설계 3-4 2). 출발지는
// cut 이 도는 응답기 자신(SelfIP)이다. 가짜 MediaMTX 의 서버 고루틴에서 불리므로 t 를 받지 않는다.
func (f *leakFixture) authorizeAPI(user, pass string) bool {
	raw, err := json.Marshal(leakAPIBody(testSelfIP.String(), user, pass))
	if err != nil {
		return false // 문자열 필드뿐이라 일어나지 않는다
	}
	r := httptest.NewRequest(http.MethodPost, "/mediamtx/auth", bytes.NewReader(raw))
	r.RemoteAddr = netip.AddrPortFrom(testCallerIP, 40000).String()
	r.Header.Set("Content-Type", "application/json")
	r.SetBasicAuth(testCallerUser, testCallerPass)
	return f.send(r).Code == http.StatusOK
}

// record 는 rr 을 모으고 그대로 돌려준다.
func (f *leakFixture) record(rr *httptest.ResponseRecorder) *httptest.ResponseRecorder {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.sent = append(f.sent, rr)
	return rr
}

// responses 는 지금까지 모은 응답이다.
func (f *leakFixture) responses() []*httptest.ResponseRecorder {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]*httptest.ResponseRecorder(nil), f.sent...)
}

// outputs 는 f 의 출력 자리다 — 처리기 · 거부 목록 · 기록 회전의 slog 와 기록 디렉터리의 파일(세대 모두).
func (f *leakFixture) outputs(t *testing.T) []leakOutput {
	t.Helper()
	outputs := []leakOutput{{place: "slog", content: f.logs.String()}}
	entries, err := os.ReadDir(f.connDir)
	if err != nil {
		t.Fatalf("Setup: %v", err)
	}
	for _, e := range entries {
		if !e.Type().IsRegular() {
			continue
		}
		data, err := os.ReadFile(filepath.Join(f.connDir, e.Name()))
		if err != nil {
			t.Fatalf("Setup: %v", err)
		}
		outputs = append(outputs, leakOutput{place: "기록 파일 " + e.Name(), content: string(data)})
	}
	return outputs
}

// events 는 f 의 slog 이벤트다.
func (f *leakFixture) events(t *testing.T) []leakEvent {
	t.Helper()
	events, err := leakEvents(f.logs.String())
	if err != nil {
		t.Fatalf("%v", err)
	}
	return events
}

// isStartMarkLine 은 line 이 시작 표시(at 만 있는 JSON 객체)인지 본다.
func isStartMarkLine(line string) bool {
	var keys map[string]any
	if json.Unmarshal([]byte(line), &keys) != nil {
		return false
	}
	_, hasAt := keys["at"]
	return len(keys) == 1 && hasAt
}
