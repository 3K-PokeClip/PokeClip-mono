package ingestauth

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"maps"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/keyresolve"
)

// 처리기 시험의 합성 값이다. 주소는 문서용 대역(RFC 5737)이고, 자격 · 출입증 · passphrase 는 어떤 계정의 값도
// 아니다. 시험 실패 문구는 passphrase · 출입증 · API 비번을 찍지 않는다(프로필 4절).
var (
	testCallerIP = netip.MustParseAddr("192.0.2.10") // MediaMTX 의 제어망 주소 <A_mtx>
	testSelfIP   = netip.MustParseAddr("192.0.2.20") // 응답기 자신의 제어망 주소 <A_auth> — cut 의 출발지
)

const (
	testPublisherIP  = "198.51.100.7" // 송출자(OBS)의 주소 — publish 요청의 ip
	testAPIUser      = "ingest-auth"
	testAPIPass      = "synthetic-api-pass-0001"
	testResolveToken = "synthetic-resolve-token-0001"     // 4C 전용 출입증 헤더 값
	testPassphrase   = "Synthetic_Pass-phrase_0123456789" // 가짜 auth 가 돌려주는 passphrase
	testUserID       = 4242
)

// waveToken 은 i 번째 합성 토큰이다(토큰 꼴 — 26자 Crockford 대문자).
func waveToken(i int) string {
	return fmt.Sprintf("SYNTHET1CWAVE%013d", i)
}

// testConnID 는 n 번째 합성 연결 id 다(MediaMTX 가 싣는 꼴 — 소문자 UUID).
func testConnID(n int) string {
	return fmt.Sprintf("00000000-0000-4000-8000-%012x", n)
}

// handlerClock 은 처리기 시험의 Options.Now 다. manualClock 처럼 시험 · 가짜 auth 가 옮기고, onNextRead 로 다음 한
// 번의 읽기에 고리를 건다 — 고리가 패닉하거나 멈춰, 처리기 안의 패닉 갈래와 늦게 깨는 갈래를 인터페이스 없이
// 일으키는 자리다. 여러 고루틴이 함께 불러도 된다.
type handlerClock struct {
	*manualClock
	nextRead atomic.Pointer[func()]
}

// errInjectedPanic 은 시험이 처리기 안에서 일부러 던지는 패닉 값이다.
var errInjectedPanic = errors.New("ingestauth 시험: 시계가 일부러 패닉했다")

func newHandlerClock() *handlerClock {
	return &handlerClock{manualClock: newManualClock()}
}

func (c *handlerClock) now() time.Time {
	if hook := c.nextRead.Swap(nil); hook != nil {
		(*hook)()
	}
	return c.manualClock.now()
}

// onNextRead 는 다음 한 번의 now 가 시각을 돌려주기 전에 hook 을 부르게 한다.
func (c *handlerClock) onNextRead(hook func()) {
	c.nextRead.Store(&hook)
}

// authAnswer 는 가짜 auth 의 답 하나다.
type authAnswer struct {
	took   time.Duration // 답하기 전에 시험 시계를 이만큼 앞당긴다 — 처리기가 재는 resolve 시간이다
	status int           // HTTP 상태. 0 이면 200
	body   string
	hang   bool   // 요청 ctx 가 끝날 때까지 답하지 않는다(시한 시험)
	before func() // 시계를 앞당긴 뒤, 답하기 직전에 부른다(C2 순서 단언 · 패닉 걸기)
}

// answerUsable 은 쓸 수 있는 값이다 — valid:true · 형식 안 passphrase.
func answerUsable(took time.Duration) authAnswer {
	return authAnswer{took: took, body: fmt.Sprintf(`{"valid":true,"userId":%d,"passphrase":%q}`, testUserID, testPassphrase)}
}

// answerRejected 는 valid:false 다.
func answerRejected(reason string, took time.Duration) authAnswer {
	return authAnswer{took: took, body: fmt.Sprintf(`{"valid":false,"reason":%q}`, reason)}
}

// answerUndecided 는 판정 불가(HTTP 500)다.
func answerUndecided(took time.Duration) authAnswer {
	return authAnswer{took: took, status: http.StatusInternalServerError}
}

// answerOutOfFormat 은 valid:true 인데 passphrase 가 형식 밖(10자 미만)이다.
func answerOutOfFormat(took time.Duration) authAnswer {
	return authAnswer{took: took, body: fmt.Sprintf(`{"valid":true,"userId":%d,"passphrase":"short"}`, testUserID)}
}

// authCall 은 가짜 auth 가 받은 요청 하나다.
type authCall struct {
	token string
	at    time.Time     // 받은 시각(시험 시계)
	took  time.Duration // 답하기까지 앞당긴 시간
}

// end 는 그 resolve 가 끝난 시각(시험 시계)이다.
func (c authCall) end() time.Time {
	return c.at.Add(c.took)
}

// fakeAuth 는 4C 를 흉내 내는 가짜 auth 다(httptest — 실제 전송). 처리기는 실제 keyresolve.Client 로 부른다(설계
// 2-9 인터페이스 0). 토큰마다 답을 정하고, 답하기 전에 시험 시계를 그 답의 처리 시간만큼 앞당긴다 — 실제로
// 기다리지 않는다(설계 2-4 683행).
type fakeAuth struct {
	srv   *httptest.Server
	clock *handlerClock

	mu     sync.Mutex
	answer func(token string) authAnswer
	calls  []authCall
}

func newFakeAuth(t *testing.T, clock *handlerClock) *fakeAuth {
	t.Helper()
	a := &fakeAuth{clock: clock, answer: func(string) authAnswer { return answerUsable(0) }}
	release := make(chan struct{})
	a.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token, err := resolvedToken(r)
		if err != nil {
			t.Errorf("fake auth: %v", err)
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		ans := a.take(token)
		if ans.before != nil {
			ans.before()
		}
		if ans.hang {
			select {
			case <-r.Context().Done():
			case <-release:
			}
			return
		}
		if ans.status != 0 {
			w.WriteHeader(ans.status)
		}
		_, _ = io.WriteString(w, ans.body)
	}))
	t.Cleanup(func() {
		close(release) // 붙잡힌 답이 있어도 서버 닫기가 기다리다 멈추지 않게 먼저 푼다
		a.srv.Close()
	})
	return a
}

// resolvedToken 은 4C 요청 본문(정규형 streamid — #!::r=<토큰>,m=publish)의 토큰이다. 본문을 끝까지 읽는다 — 그래야
// 서버가 클라이언트의 끊김을 보고 요청 ctx 를 끝낸다. 출입증 헤더가 맞지 않으면 에러다(값은 싣지 않는다).
func resolvedToken(r *http.Request) (string, error) {
	raw, err := io.ReadAll(r.Body)
	if err != nil {
		return "", fmt.Errorf("4C 요청 본문을 읽지 못했다: %v", err)
	}
	if r.Header.Get(keyresolve.Header) != testResolveToken {
		return "", errors.New("4C 요청의 전용 출입증 헤더가 맞지 않는다")
	}
	var body struct {
		StreamID string `json:"streamid"`
	}
	if err := json.Unmarshal(raw, &body); err != nil {
		return "", fmt.Errorf("4C 요청 본문을 해석하지 못했다: %v", err)
	}
	rest, okPrefix := strings.CutPrefix(body.StreamID, "#!::r=")
	token, okSuffix := strings.CutSuffix(rest, ",m=publish")
	if !okPrefix || !okSuffix {
		return "", fmt.Errorf("streamid %q 가 정규형이 아니다", body.StreamID)
	}
	return token, nil
}

// take 는 token 의 답을 정하고, 받은 요청을 적고, 시험 시계를 앞당긴다. 시계는 패닉을 거는 now 를 거치지 않는다.
func (a *fakeAuth) take(token string) authAnswer {
	a.mu.Lock()
	ans := a.answer(token)
	a.calls = append(a.calls, authCall{token: token, at: a.clock.manualClock.now(), took: ans.took})
	a.mu.Unlock()
	a.clock.advance(ans.took)
	return ans
}

// setAnswer 는 토큰마다의 답을 바꾼다.
func (a *fakeAuth) setAnswer(answer func(token string) authAnswer) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.answer = answer
}

// answerAll 은 모든 토큰에 같은 답을 준다.
func (a *fakeAuth) answerAll(ans authAnswer) {
	a.setAnswer(func(string) authAnswer { return ans })
}

// callLog 는 지금까지 받은 요청을 받은 차례대로 돌려준다.
func (a *fakeAuth) callLog() []authCall {
	a.mu.Lock()
	defer a.mu.Unlock()
	return slices.Clone(a.calls)
}

// callCount 는 지금까지 받은 요청 수다.
func (a *fakeAuth) callCount() int {
	a.mu.Lock()
	defer a.mu.Unlock()
	return len(a.calls)
}

// newTestResolver 는 baseURL 의 4C 를 부르는 실제 클라이언트다.
func newTestResolver(t *testing.T, baseURL string) *keyresolve.Client {
	t.Helper()
	c, err := keyresolve.NewClient(keyresolve.ClientOptions{BaseURL: baseURL, Token: keyresolve.NewSecret(testResolveToken)})
	if err != nil {
		t.Fatalf("Setup: keyresolve.NewClient(BaseURL=%q) returned error: %v", baseURL, err)
	}
	return c
}

// handlerFixture 는 처리기 하나와 그 재료다 — 가짜 auth · 임시 거부 목록 · 임시 기록 · 시험 시계 · 로그 모음.
type handlerFixture struct {
	h        *handler
	srv      http.Handler // h.layers() — 바깥 층 · 가드 · 안쪽 처리기
	clock    *handlerClock
	auth     *fakeAuth
	logs     *logRecorder // 처리기와 거부 목록이 함께 쓰는 로그
	denyPath string
	connDir  string
}

// fixtureOptions 는 newHandlerFixture 의 선택값이다. 영값이면 Deadline 0(MaxDeadline) · 기록 1 MiB · 가짜 auth 다.
type fixtureOptions struct {
	deadline     time.Duration
	connMaxBytes int64
	resolver     *keyresolve.Client // nil 이면 가짜 auth 를 부르는 클라이언트
}

func newHandlerFixture(t *testing.T, o fixtureOptions) *handlerFixture {
	t.Helper()
	clock := newHandlerClock()
	auth := newFakeAuth(t, clock)
	resolver := o.resolver
	if resolver == nil {
		resolver = newTestResolver(t, auth.srv.URL)
	}
	log, logs := newLogRecorder()
	denyPath := filepath.Join(t.TempDir(), "deny-tokens")
	connMax := o.connMaxBytes
	if connMax == 0 {
		connMax = 1 << 20
	}
	connDir := t.TempDir()
	conns, _ := openTestConnLog(t, connDir, connMax, newFakeClock())
	h, err := newHandler(resolver, NewDenyList(denyPath, log), conns, Options{
		CallerIP: testCallerIP,
		SelfIP:   testSelfIP,
		APIUser:  testAPIUser,
		APIPass:  testAPIPass,
		Deadline: o.deadline,
		Now:      clock.now,
		Log:      log,
	})
	if err != nil {
		t.Fatalf("Setup: newHandler returned error: %v", err)
	}
	return &handlerFixture{h: h, srv: h.layers(), clock: clock, auth: auth, logs: logs, denyPath: denyPath, connDir: connDir}
}

// mtxAuthBody 는 MediaMTX 가 보내는 인증 요청 본문이다 — 포크 v1.21.1-pokeclip.2 internal/auth/manager.go 203–225행의
// 10필드 그대로다. ID 가 nil 이면 null 이다.
type mtxAuthBody struct {
	IP        string  `json:"ip"`
	User      string  `json:"user"`
	Password  string  `json:"password"`
	Token     string  `json:"token"`
	Action    string  `json:"action"`
	Path      string  `json:"path"`
	Protocol  string  `json:"protocol"`
	ID        *string `json:"id"`
	Query     string  `json:"query"`
	UserAgent string  `json:"userAgent"`
}

// publishBody 는 token · connID 의 SRT publish 인증 요청 본문이다. publish 가 읽지 않는 필드(user · password ·
// token · query · userAgent)에도 값을 채운다 — 처리기가 그 값에 기대지 않음을 함께 본다.
func publishBody(token, connID string) mtxAuthBody {
	id := connID
	return mtxAuthBody{
		IP:        testPublisherIP,
		User:      "streamid-user",
		Password:  "streamid-pass",
		Token:     "jwt-not-used",
		Action:    "publish",
		Path:      token,
		Protocol:  "srt",
		ID:        &id,
		Query:     "q=not-used",
		UserAgent: "obs-not-used",
	}
}

// apiBody 는 cut 이 Control API 를 부를 때 MediaMTX 가 보내는 api 인증 요청 본문이다(id 는 null).
func apiBody(ip, user, pass string) mtxAuthBody {
	return mtxAuthBody{IP: ip, User: user, Password: pass, Action: "api"}
}

// newAuthRequest 는 MediaMTX(CallerIP)가 보낸 인증 요청이다. body 가 string · []byte 면 그대로, 아니면 JSON 으로 싣는다.
func newAuthRequest(t *testing.T, body any) *http.Request {
	t.Helper()
	var raw []byte
	switch b := body.(type) {
	case string:
		raw = []byte(b)
	case []byte:
		raw = b
	default:
		var err error
		if raw, err = json.Marshal(b); err != nil {
			t.Fatalf("Setup: json.Marshal(%T) returned error: %v", body, err)
		}
	}
	r := httptest.NewRequest(http.MethodPost, "/mediamtx/auth", bytes.NewReader(raw))
	r.RemoteAddr = netip.AddrPortFrom(testCallerIP, 40000).String()
	r.Header.Set("Content-Type", "application/json")
	return r
}

// serve 는 r 을 처리기 전체(바깥 층 · 가드 · 안쪽)에 보내고 응답을 돌려준다.
func (f *handlerFixture) serve(r *http.Request) *httptest.ResponseRecorder {
	rr := httptest.NewRecorder()
	f.srv.ServeHTTP(rr, r)
	return rr
}

// publish 는 token · connID 의 SRT publish 인증을 처리기 전체에 보낸다.
func (f *handlerFixture) publish(t *testing.T, token, connID string) *httptest.ResponseRecorder {
	t.Helper()
	return f.serve(newAuthRequest(t, publishBody(token, connID)))
}

// servePanicking 은 r 을 처리기 전체에 보내고, 처리기가 바깥으로 던진 패닉 값을 돌려준다(없으면 nil).
func (f *handlerFixture) servePanicking(r *http.Request) (rr *httptest.ResponseRecorder, recovered any) {
	rr = httptest.NewRecorder()
	defer func() { recovered = recover() }()
	f.srv.ServeHTTP(rr, r)
	return rr, nil
}

// stalledPublish 는 본문의 첫 바이트만 보낸 publish 요청을 처리기 전체에 보내 놓고 돌아온다 — 안쪽 처리기는 나머지
// 본문을 기다리며 멈춘다. 돌아올 때 바깥 층은 이미 시작 시각을 쟀다(안쪽이 첫 바이트를 읽었다). finish 가 나머지
// 본문을 보낸다. served 는 바깥 ServeHTTP 가 돌아오면 닫히고, 그 뒤에 rr 을 읽는다.
func (f *handlerFixture) stalledPublish(t *testing.T, token, connID string) (rr *httptest.ResponseRecorder, served <-chan struct{}, finish func()) {
	t.Helper()
	body := mustJSON(t, publishBody(token, connID))
	pr, pw := io.Pipe()
	t.Cleanup(func() { pw.CloseWithError(errors.New("시험이 끝났다")) })
	r := newAuthRequest(t, "")
	r.Body = pr
	rr = httptest.NewRecorder()
	done := make(chan struct{})
	go func() {
		defer close(done)
		f.srv.ServeHTTP(rr, r)
	}()
	if _, err := pw.Write(body[:1]); err != nil {
		t.Fatalf("Setup: writing the first body byte returned error: %v", err)
	}
	finish = func() {
		if _, err := pw.Write(body[1:]); err != nil {
			t.Errorf("writing the rest of the body returned error: %v", err)
		}
		pw.Close()
	}
	return rr, done, finish
}

// publishAsync 는 publish 를 다른 고루틴에서 처리기 전체에 보낸다. served 가 닫힌 뒤에 rr 을 읽는다.
func (f *handlerFixture) publishAsync(t *testing.T, token, connID string) (rr *httptest.ResponseRecorder, served <-chan struct{}) {
	t.Helper()
	r := newAuthRequest(t, publishBody(token, connID))
	rr = httptest.NewRecorder()
	done := make(chan struct{})
	go func() {
		defer close(done)
		f.srv.ServeHTTP(rr, r)
	}()
	return rr, done
}

// waitClosed 는 ch 가 닫히기를 5초까지 기다린다. 안 닫히면 시험을 멈춘다(바깥 가드가 없으면 여기서 걸린다).
func waitClosed(t *testing.T, ch <-chan struct{}, what string) {
	t.Helper()
	select {
	case <-ch:
	case <-time.After(5 * time.Second):
		t.Fatalf("%s did not happen within 5s", what)
	}
}

// callsBetween 은 가짜 auth 가 [from, to) 에 받은 요청이다(시험 시계).
func (a *fakeAuth) callsBetween(from, to time.Time) []authCall {
	var got []authCall
	for _, c := range a.callLog() {
		if !c.at.Before(from) && c.at.Before(to) {
			got = append(got, c)
		}
	}
	return got
}

// writeDenyList 는 거부 목록 파일을 content 로 새로 쓴다.
func (f *handlerFixture) writeDenyList(t *testing.T, content string) {
	t.Helper()
	writeFile(t, f.denyPath, content)
}

// handlerEvent 는 처리기 · 거부 목록이 낸 slog JSON 한 줄이다. Keys 는 그 줄의 키(바이트 순)다.
type handlerEvent struct {
	Level         string `json:"level"`
	Msg           string `json:"msg"`
	Path          string `json:"path"`
	ConnID        string `json:"conn_id"`
	Reason        string `json:"reason"`
	ResolveStatus string `json:"resolve_status"`
	ResolveMS     int64  `json:"resolve_ms"`
	Wait          string `json:"wait"`
	UserID        int64  `json:"user_id"`
	Scope         string `json:"scope"`
	Cleared       int    `json:"cleared"`
	Status        int    `json:"status"`
	DownFor       int64  `json:"down_for"` // JSON 처리기는 time.Duration 을 ns 정수로 쓴다
	DeniedDuring  int    `json:"denied_during"`
	BudgetDenied  int    `json:"budget_denied"`
	RecheckDenied int    `json:"recheck_denied"`
	IP            string `json:"ip"`
	RemoteIP      string `json:"remote_ip"`
	Keys          []string
}

// has 는 e 에 key 가 있는지 본다.
func (e handlerEvent) has(key string) bool {
	_, found := slices.BinarySearch(e.Keys, key)
	return found
}

// handlerEvents 는 msg 가 name 인 이벤트를 낸 차례대로 돌려준다. name 이 비면 모두다.
func handlerEvents(t *testing.T, logs *logRecorder, name string) []handlerEvent {
	t.Helper()
	var got []handlerEvent
	sc := bufio.NewScanner(strings.NewReader(logs.String()))
	sc.Buffer(make([]byte, 0, 64<<10), 1<<20)
	for sc.Scan() {
		var e handlerEvent
		var keys map[string]any
		if err := errors.Join(json.Unmarshal(sc.Bytes(), &e), json.Unmarshal(sc.Bytes(), &keys)); err != nil {
			t.Fatalf("slog 출력 %q 을 해석하지 못했다: %v", sc.Text(), err)
		}
		e.Keys = slices.Sorted(maps.Keys(keys))
		if name == "" || e.Msg == name {
			got = append(got, e)
		}
	}
	return got
}

// eventNames 는 지금까지 낸 이벤트 이름을 차례대로 돌려준다.
func eventNames(t *testing.T, logs *logRecorder) []string {
	t.Helper()
	var names []string
	for _, e := range handlerEvents(t, logs, "") {
		names = append(names, e.Msg)
	}
	return names
}

// lastEvent 는 msg 가 name 인 마지막 이벤트다. 없으면 시험을 멈춘다.
func lastEvent(t *testing.T, logs *logRecorder, name string) handlerEvent {
	t.Helper()
	es := handlerEvents(t, logs, name)
	if len(es) == 0 {
		t.Fatalf("이벤트 %s 가 없다 — 낸 이벤트: %v", name, eventNames(t, logs))
	}
	return es[len(es)-1]
}

// innerEndEvents 는 안쪽 처리기가 publish 를 끝내며 내는 이벤트다 — 어느 행으로 끝나든 이 가운데 하나가 마지막
// 로그다.
var innerEndEvents = []string{
	"ingestauth_publish_late",
	"ingestauth_publish_denied",
	"ingestauth_publish_allowed",
	"ingestauth_passphrase_invalid",
}

// waitForInnerEnd 는 가드 뒤에 늦게 끝나는 안쪽 처리기가 publish 를 끝낼 때까지 기다리고, 그때 낸 끝 이벤트 이름을
// 돌려준다(그 고루틴은 TimeoutHandler 가 띄워 시험이 기다릴 다른 수단이 없다). 어느 행으로 끝나든 돌아오므로 뒤의
// 단언이 모두 돈다. 5초 안에 안 끝나면 시험을 멈춘다.
func waitForInnerEnd(t *testing.T, logs *logRecorder) string {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for {
		for _, name := range eventNames(t, logs) {
			if slices.Contains(innerEndEvents, name) {
				return name
			}
		}
		if time.Now().After(deadline) {
			t.Fatalf("안쪽 처리기가 5초 안에 publish 를 끝내지 않았다 — 낸 이벤트: %v", eventNames(t, logs))
		}
		time.Sleep(time.Millisecond)
	}
}

// entryOf 는 token 의 기억 항목 사본이다. 없으면 ok 가 거짓이다.
func entryOf(f *failedTokens, token string) (failedEntry, bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	e, ok := f.entries[token]
	if !ok {
		return failedEntry{}, false
	}
	return *e, true
}

// lastRecheckOf 는 마지막 재확인 시각이다.
func lastRecheckOf(f *failedTokens) time.Time {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.lastRecheck
}

// memorySize 는 기억 맵의 항목 수다(풀린 항목 포함).
func memorySize(f *failedTokens) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.entries)
}
