package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"log/slog"
	"maps"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/ingestauth"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/keyresolve"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/streamkey"
)

// 시험의 합성 값이다. 토큰은 토큰 꼴(26자 Crockford 대문자)만 맞춘 값이고, 출입증 · API 비번 · passphrase 는 어떤
// 계정의 값도 아니다 — 누설 단언의 카나리아다. 시험 실패 문구는 이 값을 찍지 않는다(프로필 4절). 주소는 문서용
// 대역(RFC 5737)이고, 실제 소켓이 필요한 시험만 루프백을 쓴다.
const (
	testToken        = "SYNTHET1CTEST000000000000A"
	testOtherToken   = "SYNTHET1CTEST000000000000B"
	testResolveToken = "synthetic-resolve-token-c6-0001"
	testAPIUser      = "ingest-auth"
	testAPIPass      = "synthetic-api-pass-c6-0001"
	testPassphrase   = "Synthetic_Pass-phrase_c6_0123456789"
	testPublisherIP  = "198.51.100.7"
	testUserID       = 4242
)

// secretCanaries 는 stdout · stderr 어디에도 나와서는 안 되는 값이다. passphrase 는 허용 응답 본문에만 실린다.
var secretCanaries = []string{testResolveToken, testAPIPass, testPassphrase}

// testConnID 는 n 번째 합성 연결 id 다(MediaMTX 가 싣는 꼴 — 소문자 UUID).
func testConnID(n int) string {
	return fmt.Sprintf("00000000-0000-4000-8000-%012x", n)
}

// getenvFrom 은 env 를 os.Getenv 처럼 읽는 함수다. 없는 이름은 빈 값이다.
func getenvFrom(env map[string]string) func(string) string {
	return func(name string) string { return env[name] }
}

// configEnv 는 설정 시험의 환경 변수 일곱이다(합성). 인증 리스너는 시험 기계에 없는 문서용 주소라, 검사를 빠뜨린
// 변이도 리스너를 열지 못하고 곧바로 끝난다(시험이 멈추지 않는다). 어느 주소에도 요청을 보내지 않는다.
func configEnv() map[string]string {
	return map[string]string{
		"INGEST_AUTH_LISTEN":       "192.0.2.20:8080",
		"INGEST_AUTH_CALLER_IP":    "192.0.2.10",
		"MTX_API_URL":              "http://192.0.2.10:9997",
		"MTX_API_USER":             testAPIUser,
		"MTX_API_PASS":             testAPIPass,
		"STREAM_KEY_RESOLVE_URL":   "http://192.0.2.30:8082",
		"STREAM_KEY_RESOLVE_TOKEN": testResolveToken,
	}
}

// responderEnv 는 루프백에서 도는 run 의 환경 변수다 — 인증 리스너는 임의 포트이고 호출자(MediaMTX 자리)는 시험
// 자신(127.0.0.1)이다. MTX_API_URL 은 run 이 부르지 않으므로 문서용 주소다.
func responderEnv(authURL string) map[string]string {
	env := configEnv()
	env["INGEST_AUTH_LISTEN"] = "127.0.0.1:0"
	env["INGEST_AUTH_CALLER_IP"] = "127.0.0.1"
	env["STREAM_KEY_RESOLVE_URL"] = authURL
	return env
}

// cutEnv 는 cut 의 환경 변수다 — Control API 는 mtxURL 이다.
func cutEnv(mtxURL string) map[string]string {
	env := configEnv()
	env["MTX_API_URL"] = mtxURL
	return env
}

// newTestSite 는 시험의 자리다 — 임시 거부 목록 파일(denyContent 로 쓴다) · 빈 기록 디렉터리 · 임의 포트의 healthz ·
// 넉넉한 cut 시한 · 운영과 같은 net.Listen.
func newTestSite(t *testing.T, denyContent string) site {
	t.Helper()
	s := site{
		denyList:   filepath.Join(t.TempDir(), "deny-tokens"),
		connLogDir: t.TempDir(),
		healthAddr: "127.0.0.1:0",
		cutTimeout: 30 * time.Second,
		listen:     net.Listen,
	}
	writeFile(t, s.denyList, denyContent)
	return s
}

// writeFile 은 path 를 content 로 새로 쓴다(0644).
func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatalf("Setup: could not write %s: %v", path, err)
	}
}

// setMode 는 path 의 모드를 mode 로 바꾸고, 시험이 끝나면 restore 로 되돌린다(t.TempDir 정리가 되게).
func setMode(t *testing.T, path string, mode, restore os.FileMode) {
	t.Helper()
	if err := os.Chmod(path, mode); err != nil {
		t.Fatalf("Setup: could not chmod %s to %v: %v", path, mode, err)
	}
	t.Cleanup(func() {
		if err := os.Chmod(path, restore); err != nil {
			t.Errorf("Cleanup: could not chmod %s back to %v: %v", path, restore, err)
		}
	})
}

// skipIfRoot 는 root 면 시험을 건너뛴다 — root 는 권한을 빼도 읽고 쓴다(internal/ingestauth 시험과 같은 관례).
func skipIfRoot(t *testing.T) {
	t.Helper()
	if os.Geteuid() == 0 {
		t.Skip("root reads and writes regardless of permission bits")
	}
}

// checkNoSecrets 는 outputs 어디에도 비밀 카나리아와 extra 가 없는지 본다. 빈 값은 건너뛴다(어디에나 들어 있다).
// 실패 문구에는 값 대신 차례와 길이만 싣는다.
func checkNoSecrets(t *testing.T, outputs []string, extra ...string) {
	t.Helper()
	for i, canary := range slices.Concat(secretCanaries, extra) {
		if canary == "" {
			continue
		}
		for j, out := range outputs {
			if strings.Contains(out, canary) {
				t.Errorf("output #%d carries secret canary #%d (length %d)", j, i, len(canary))
			}
		}
	}
}

// 잡은 출력(stdout · stderr · 기록)은 실패 문구에 그대로 싣지 않는다 — 누설 회귀가 생기면 그 출력에 합성 비밀이 실려 CI
// 기록으로 간다(프로필 4절). 실패 문구에는 줄 수 · 바이트 수 · 이벤트 이름과, 꼴이 확인된 값(이름 · 등급 · 토큰 · 연결
// id · IP 주소 · 포트 · 수 · 참거짓)만 싣는다. 그 밖의 값은 길이만 싣는다.
var (
	// nameShape 는 이벤트 · 사유 · 부류 · 필드 이름의 꼴이다 — 숫자가 없어 16진 · base64 비밀은 이 꼴이 아니다.
	nameShape   = regexp.MustCompile(`^[a-z][a-z_]*$`)
	connIDShape = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
	portShape   = regexp.MustCompile(`^[0-9]{1,5}$`)
	logLevels   = []string{"DEBUG", "INFO", "WARN", "ERROR"}
)

// safeToShow 는 출력에서 읽은 문자열 s 를 실패 문구에 그대로 실어도 되는 꼴인지 본다.
func safeToShow(s string) bool {
	if nameShape.MatchString(s) || connIDShape.MatchString(s) || portShape.MatchString(s) {
		return true
	}
	if streamkey.IsToken(s) || slices.Contains(logLevels, s) {
		return true
	}
	if _, err := netip.ParseAddr(s); err == nil {
		return true
	}
	_, err := netip.ParseAddrPort(s)
	return err == nil
}

// shown 은 출력에서 읽은 값 v 를 실패 문구에 싣는 꼴이다 — 수 · 참거짓과 꼴이 확인된 문자열은 그대로, 그 밖의 문자열은
// 길이만, 없는 값은 <absent> 다.
func shown(v any) string {
	switch v := v.(type) {
	case nil:
		return "<absent>"
	case float64, bool:
		return fmt.Sprint(v)
	case string:
		if safeToShow(v) {
			return strconv.Quote(v)
		}
		return fmt.Sprintf("<%d bytes>", len(v))
	}
	return fmt.Sprintf("<%T>", v)
}

// eventSummary 는 slog 줄 하나(해석한 꼴)를 실패 문구에 싣는 꼴이다 — 필드마다 shown 이고, 이름 꼴이 아닌 필드 이름은
// 길이만 싣는다.
func eventSummary(e map[string]any) string {
	var fields []string
	for _, key := range slices.Sorted(maps.Keys(e)) {
		name := key
		if !nameShape.MatchString(key) {
			name = fmt.Sprintf("<%d-byte key>", len(key))
		}
		fields = append(fields, name+"="+shown(e[key]))
	}
	return "{" + strings.Join(fields, " ") + "}"
}

// eventsSummary 는 slog 줄 여럿을 eventSummary 로 싣는 꼴이다.
func eventsSummary(events []map[string]any) string {
	parts := make([]string, 0, len(events))
	for _, e := range events {
		parts = append(parts, eventSummary(e))
	}
	return "[" + strings.Join(parts, " ") + "]"
}

// lineCount 는 s 의 줄 수다. 빈 출력은 0 줄이고, 끝 줄바꿈이 없는 마지막 조각도 한 줄이다.
func lineCount(s string) int {
	n := 0
	for range strings.Lines(s) {
		n++
	}
	return n
}

// outputSummary 는 잡은 출력 out 을 실패 문구에 싣는 꼴이다 — 줄 수 · 바이트 수와, slog JSON 줄이 있으면 그 이벤트
// 이름(shown)만 싣는다.
func outputSummary(out string) string {
	var events []string
	for line := range strings.Lines(out) {
		var e map[string]any
		if json.Unmarshal([]byte(line), &e) == nil {
			events = append(events, shown(e["msg"]))
		}
	}
	summary := fmt.Sprintf("%d line(s), %d byte(s)", lineCount(out), len(out))
	if len(events) > 0 {
		summary += ", events [" + strings.Join(events, " ") + "]"
	}
	return summary
}

// keepDefaultLogger 는 run 이 바꾸는 전역 로거를 시험이 끝나면 되돌린다. run 은 slog.SetDefault 를 부르고, 그것은
// log 패키지의 출력 · 플래그까지 바꾼다. run 을 부르는 시험은 이것을 먼저 부르고, 병렬로 돌지 않는다.
func keepDefaultLogger(t *testing.T) {
	t.Helper()
	prev, out, flags, prefix := slog.Default(), log.Writer(), log.Flags(), log.Prefix()
	t.Cleanup(func() {
		slog.SetDefault(prev)
		log.SetOutput(out)
		log.SetFlags(flags)
		log.SetPrefix(prefix)
	})
}

// outputLog 는 하위 명령의 출력(stdout · stderr)을 모은다. 여러 고루틴이 함께 써도 된다. 쓸 때마다 기다리는 쪽을
// 깨운다.
type outputLog struct {
	mu      sync.Mutex
	buf     bytes.Buffer
	changed chan struct{} // 쓸 때마다 닫고 새로 만든다
}

func newOutputLog() *outputLog {
	return &outputLog{changed: make(chan struct{})}
}

func (o *outputLog) Write(p []byte) (int, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	n, err := o.buf.Write(p)
	close(o.changed)
	o.changed = make(chan struct{})
	return n, err
}

// String 은 지금까지 모은 출력 전체다.
func (o *outputLog) String() string {
	s, _ := o.snapshot()
	return s
}

// snapshot 은 지금까지의 출력과, 다음 쓰기에 닫힐 채널이다.
func (o *outputLog) snapshot() (string, <-chan struct{}) {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.buf.String(), o.changed
}

// logEvents 는 out 의 slog JSON 줄 가운데 msg 가 name 인 것을 차례대로 해석한다. JSON 이 아닌 줄은 건너뛴다.
func logEvents(out, name string) []map[string]any {
	var events []map[string]any
	for line := range strings.Lines(out) {
		var e map[string]any
		if json.Unmarshal([]byte(line), &e) == nil && e["msg"] == name {
			events = append(events, e)
		}
	}
	return events
}

// waitForEvent 는 o 에 msg 가 name 인 줄이 나올 때까지 기다린다(상한 within). done 이 먼저 닫히면(하위 명령이
// 끝남) 기다리지 않고 실패한다.
func waitForEvent(t *testing.T, o *outputLog, name string, done <-chan struct{}, within time.Duration) map[string]any {
	t.Helper()
	timeout := time.After(within)
	for {
		out, changed := o.snapshot()
		if events := logEvents(out, name); len(events) > 0 {
			return events[0]
		}
		select {
		case <-changed:
		case <-done:
			if events := logEvents(o.String(), name); len(events) > 0 {
				return events[0]
			}
			t.Fatalf("run returned before logging %s", name)
		case <-timeout:
			t.Fatalf("%s did not appear within %v", name, within)
		}
	}
}

// runningResponder 는 시험이 고루틴으로 띄운 run 하위 명령이다.
type runningResponder struct {
	stdout, stderr *outputLog
	stop           context.CancelFunc
	done           chan struct{} // run 이 돌아오면 닫힌다
	code           int           // done 이 닫힌 뒤에만 읽는다
	listen, health string        // ingestauth_started 의 listen · health — 실제로 묶은 주소
}

// startResponder 는 s · env 로 run 을 띄우고 ingestauth_started 가 나올 때까지 기다린다(상한 5초). 시험이 끝나면
// 멈추고 돌아오기를 기다린다.
func startResponder(t *testing.T, s site, env map[string]string) *runningResponder {
	t.Helper()
	keepDefaultLogger(t)
	ctx, stop := context.WithCancel(t.Context())
	r := &runningResponder{stdout: newOutputLog(), stderr: newOutputLog(), stop: stop, done: make(chan struct{})}
	go func() {
		r.code = run(ctx, s, nil, getenvFrom(env), r.stdout, r.stderr)
		close(r.done)
	}()
	t.Cleanup(func() {
		stop()
		select {
		case <-r.done:
		case <-time.After(10 * time.Second):
			t.Errorf("run did not return within 10s after its context ended")
		}
	})
	started := waitForEvent(t, r.stdout, "ingestauth_started", r.done, 5*time.Second)
	r.listen, _ = started["listen"].(string)
	r.health, _ = started["health"].(string)
	// 두 주소는 시험이 다시 쓰고 실패 문구 · 다이얼 에러에 실린다 — IP:포트 꼴이 아니면 여기서 멈춘다.
	for name, addr := range map[string]string{"listen": r.listen, "health": r.health} {
		if _, err := netip.ParseAddrPort(addr); err != nil {
			t.Fatalf("ingestauth_started %s = %s, want <IP>:<port>", name, shown(started[name]))
		}
	}
	return r
}

// stopAndWait 는 run 의 ctx 를 끝내고(종료 신호와 같은 길) 종료 코드를 기다린다(상한 10초).
func (r *runningResponder) stopAndWait(t *testing.T) int {
	t.Helper()
	r.stop()
	select {
	case <-r.done:
		return r.code
	case <-time.After(10 * time.Second):
		t.Fatalf("run did not return within 10s after its context ended")
		return -1
	}
}

// runHealthcheck 는 addr 의 healthz 로 healthcheck 하위 명령을 돌린다. 종료 코드와 stderr 를 돌려준다.
func runHealthcheck(t *testing.T, addr string) (int, string) {
	t.Helper()
	stderr := newOutputLog()
	code := run(t.Context(), site{healthAddr: addr}, []string{"healthcheck"}, getenvFrom(nil), io.Discard, stderr)
	return code, stderr.String()
}

// publishBody 는 MediaMTX 가 SRT publish 인증에 싣는 본문이다(포크 auth/manager.go 203–225 — 10필드).
func publishBody(token, connID string) string {
	return fmt.Sprintf(`{"ip":%q,"user":"","password":"","token":"","action":"publish","path":%q,"protocol":"srt","id":%q,"query":"","userAgent":""}`,
		testPublisherIP, token, connID)
}

// apiBody 는 MediaMTX 가 Control API 요청의 인증에 싣는 본문이다 — ip 는 Control API 를 부른 쪽이다.
func apiBody(ip, user, pass string) string {
	return fmt.Sprintf(`{"ip":%q,"user":%q,"password":%q,"token":"","action":"api","path":"","protocol":"","id":null,"query":"","userAgent":""}`,
		ip, user, pass)
}

// postAuth 는 MediaMTX 처럼 addr 의 /mediamtx/auth 에 body 를 POST 한다 — 요청마다 새 연결이다(포크
// auth/manager.go 227–237). 고루틴 안에서도 부르므로 t 를 받지 않고 에러를 돌려준다.
func postAuth(ctx context.Context, addr, body string, header http.Header) (status int, respBody string, err error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, "http://"+addr+"/mediamtx/auth", strings.NewReader(body))
	if err != nil {
		return 0, "", err
	}
	req.Header.Set("Content-Type", "application/json")
	for name, values := range header {
		req.Header[name] = values
	}
	client := &http.Client{Transport: &http.Transport{DisableKeepAlives: true}}
	resp, err := client.Do(req)
	if err != nil {
		return 0, "", err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(resp.Body)
	return resp.StatusCode, string(b), err
}

// mustPostAuth 는 postAuth 가 실패하면 시험을 멈춘다(시험 고루틴에서만 부른다).
func mustPostAuth(t *testing.T, addr, body string, header http.Header) (int, string) {
	t.Helper()
	status, respBody, err := postAuth(t.Context(), addr, body, header)
	if err != nil {
		t.Fatalf("POST /mediamtx/auth to %s: %v", addr, err)
	}
	return status, respBody
}

// fakeAuth 는 4C 를 흉내 내는 가짜 auth 다(httptest — 처리기는 실제 keyresolve.Client 로 부른다, 설계 2-9). 전용
// 출입증 헤더가 맞으면 valid:true 와 합성 passphrase 를 돌려주고, 아니면 401 이다. delay 만큼 기다린 뒤 답한다 —
// 실제 시간이다(시한 · 우아한 종료 시험만 쓴다).
type fakeAuth struct {
	srv      *httptest.Server
	delay    atomic.Int64  // 답하기 전에 기다릴 시간(time.Duration)
	received chan struct{} // 요청을 받을 때마다 보낸다(가득 차면 버린다)
}

func newFakeAuth(t *testing.T) *fakeAuth {
	t.Helper()
	a := &fakeAuth{received: make(chan struct{}, 16)}
	a.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case a.received <- struct{}{}:
		default:
		}
		if r.Header.Get(keyresolve.Header) != testResolveToken {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		time.Sleep(time.Duration(a.delay.Load()))
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"valid":true,"userId":%d,"passphrase":%q}`, testUserID, testPassphrase)
	}))
	t.Cleanup(a.srv.Close)
	return a
}

// fakeConn 은 가짜 MediaMTX 의 SRT 연결 하나다.
type fakeConn struct {
	created time.Time
	state   string // idle · read · publish
	path    string // 등록 전에는 빈 값
}

// fakeMediaMTX 는 MediaMTX Control API 의 SRT 연결 부분을 흉내 낸다(httptest — cut 은 실제 mtxctl.Client 로
// 부른다). 원본(v1.21.1-pokeclip.2 — api.go 218–220 · paginate.go · srt/server.go 208–241)의 꼴을 따른다. Basic
// 자격이 틀리면 401 이고, 목록은 created 순으로 itemsPerPage 를 따라 나누며(빈 목록은 pageCount 0), kick 은 목록에서
// 곧바로 지우고 없는 id 는 404 다. 에러 본문은 원본의 JSON 꼴이다.
type fakeMediaMTX struct {
	srv        *httptest.Server
	user, pass string // 받는 Basic 자격 — 만든 뒤 바꾸지 않는다

	mu       sync.Mutex
	conns    map[string]fakeConn
	status   map[string]int  // 갈래(list · get · kick) → 이 상태로 답한다
	missing  map[string]bool // 갈래 → 경로가 없는 것처럼 gin 기본 404(text/plain — JSON 에러 꼴이 아님)로 답한다
	sticky   bool            // kick 이 200 을 내지만 연결을 지우지 않는다(확인 시한)
	hangList bool            // 목록 요청을 요청 ctx 가 끝날 때까지 붙잡는다(cut 시한)
	onGet    func()          // 조회 요청이 닿으면 답하기 전에 부른다 — 확인 단계에 든 것을 시험이 안다
	kicks    []string        // 200 으로 끝난 kick 의 id(차례대로)
	requests int             // 받은 요청 수
}

func newFakeMediaMTX(t *testing.T) *fakeMediaMTX {
	t.Helper()
	return newFakeMediaMTXFor(t, testAPIUser, testAPIPass)
}

// newFakeMediaMTXFor 는 Basic 자격 user · pass 만 받는 가짜 MediaMTX 다 — 누설 시험은 고유한 API 사용자를 카나리아로
// 쓴다.
func newFakeMediaMTXFor(t *testing.T, user, pass string) *fakeMediaMTX {
	t.Helper()
	m := &fakeMediaMTX{
		user: user, pass: pass,
		conns: make(map[string]fakeConn), status: make(map[string]int), missing: make(map[string]bool),
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v3/srtconns/list", m.list)
	mux.HandleFunc("GET /v3/srtconns/get/{id}", m.get)
	mux.HandleFunc("POST /v3/srtconns/kick/{id}", m.kick)
	m.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		m.mu.Lock()
		m.requests++
		m.mu.Unlock()
		if user, pass, ok := r.BasicAuth(); !ok || user != m.user || pass != m.pass {
			writeMTXError(w, http.StatusUnauthorized)
			return
		}
		mux.ServeHTTP(w, r)
	}))
	t.Cleanup(m.srv.Close)
	return m
}

// writeMTXError 는 원본의 에러 응답({"status":"error","error":…} — api.go 284–300)을 쓴다.
func writeMTXError(w http.ResponseWriter, status int) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	fmt.Fprintf(w, `{"status":"error","error":%q}`, http.StatusText(status))
}

// injected 는 갈래 route 에 넣은 실패가 있으면 그것으로 답하고 참을 돌려준다. 경로 없음이 먼저다 — 원본은 경로가 없으면
// 인증 뒤 gin 의 기본 404(text/plain 「404 page not found」)를 낸다(http.NotFound 와 같은 꼴).
func (m *fakeMediaMTX) injected(w http.ResponseWriter, r *http.Request, route string) bool {
	m.mu.Lock()
	missing := m.missing[route]
	status, ok := m.status[route]
	m.mu.Unlock()
	switch {
	case missing:
		http.NotFound(w, r)
	case ok:
		writeMTXError(w, status)
	}
	return missing || ok
}

// item 은 연결 하나의 Control API 항목이다(APISRTConn 가운데 절단과 사람이 보는 필드).
func (c fakeConn) item(id string) map[string]any {
	return map[string]any{
		"id":         id,
		"created":    c.created.Format(time.RFC3339Nano),
		"remoteAddr": testPublisherIP + ":40000",
		"state":      c.state,
		"path":       c.path,
		"query":      "",
		"user":       "",
	}
}

func (m *fakeMediaMTX) list(w http.ResponseWriter, r *http.Request) {
	if m.injected(w, r, "list") {
		return
	}
	m.mu.Lock()
	hang := m.hangList
	ids := slices.SortedFunc(maps.Keys(m.conns), func(a, b string) int {
		return m.conns[a].created.Compare(m.conns[b].created)
	})
	items := make([]map[string]any, 0, len(ids))
	for _, id := range ids {
		items = append(items, m.conns[id].item(id))
	}
	m.mu.Unlock()
	if hang {
		<-r.Context().Done()
		return
	}
	perPage := 100 // 원본 paginate.go 의 기본값
	if v, err := strconv.Atoi(r.URL.Query().Get("itemsPerPage")); err == nil && v > 0 {
		perPage = v
	}
	pages := (len(items) + perPage - 1) / perPage
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"itemCount": len(items), "pageCount": pages, "items": items[:min(perPage, len(items))]})
}

func (m *fakeMediaMTX) get(w http.ResponseWriter, r *http.Request) {
	m.mu.Lock()
	onGet := m.onGet
	m.mu.Unlock()
	if onGet != nil {
		onGet()
	}
	if m.injected(w, r, "get") {
		return
	}
	id := r.PathValue("id")
	m.mu.Lock()
	c, ok := m.conns[id]
	m.mu.Unlock()
	if !ok {
		writeMTXError(w, http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(c.item(id))
}

func (m *fakeMediaMTX) kick(w http.ResponseWriter, r *http.Request) {
	if m.injected(w, r, "kick") {
		return
	}
	id := r.PathValue("id")
	m.mu.Lock()
	_, ok := m.conns[id]
	if ok {
		m.kicks = append(m.kicks, id)
		if !m.sticky {
			delete(m.conns, id)
		}
	}
	m.mu.Unlock()
	if !ok {
		writeMTXError(w, http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	fmt.Fprint(w, `{"status":"ok"}`)
}

// add 는 연결 하나를 목록에 둔다.
func (m *fakeMediaMTX) add(id string, c fakeConn) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.conns[id] = c
}

// set 은 m.mu 를 잡고 edit 를 부른다 — 시험이 실패 주입을 바꾸는 자리다.
func (m *fakeMediaMTX) set(edit func(m *fakeMediaMTX)) {
	m.mu.Lock()
	defer m.mu.Unlock()
	edit(m)
}

// kicked 는 200 으로 끝난 kick 의 id 다(차례대로).
func (m *fakeMediaMTX) kicked() []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return slices.Clone(m.kicks)
}

// live 는 목록에 남은 연결의 id 다(바이트 순).
func (m *fakeMediaMTX) live() []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return slices.Sorted(maps.Keys(m.conns))
}

// requestCount 는 받은 요청 수다.
func (m *fakeMediaMTX) requestCount() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.requests
}

// openConnLog 는 dir 에 응답기와 같은 허용 연결 기록을 연다(실제 ConnLog — 줄 꼴을 시험이 따로 적지 않는다).
// maxBytes 를 크게 주면 회전하지 않는다. now 가 nil 이면 실제 시계다.
func openConnLog(t *testing.T, dir string, maxBytes int64, now func() time.Time) *ingestauth.ConnLog {
	t.Helper()
	c, err := ingestauth.OpenConnLog(dir, maxBytes, now)
	if err != nil {
		t.Fatalf("Setup: OpenConnLog(%s) returned error: %v", dir, err)
	}
	return c
}

// allow 는 c 에 token · connID 의 허용 줄을 적는다.
func allow(t *testing.T, c *ingestauth.ConnLog, token, connID string) {
	t.Helper()
	if err := c.Append(token, connID); err != nil {
		t.Fatalf("Setup: Append(%s, %s) returned error: %v", token, connID, err)
	}
}
