package main

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/ingestauth"
)

// run 하위 명령의 시험이다 — 조립 · 두 리스너 · 서버 한도 · healthz · 우아한 종료 · 기본 로거의 차례(설계 2-6 · 2-8 ·
// 6절). 실제 소켓 위에서 가짜 auth 로 돈다. run 이 전역 기본 로거를 바꾸므로 병렬로 돌리지 않는다.

// wantAllowedBody 는 허용 응답 본문이다(설계 2-1 A2). 실패 문구에 찍지 않는다 — passphrase 가 들어 있다.
const wantAllowedBody = `{"srtPassphrase":"` + testPassphrase + `"}`

// listenLoopback 은 루프백 임의 포트의 리스너다. 시험이 끝나면 닫는다.
func listenLoopback(t *testing.T) net.Listener {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Setup: listen on loopback: %v", err)
	}
	t.Cleanup(func() { ln.Close() }) // 이미 닫혔으면 에러다 — 정리라 판정을 바꾸지 않는다
	return ln
}

// chmodNow 는 시험 도중 path 의 모드를 바꾼다.
func chmodNow(t *testing.T, path string, mode os.FileMode) {
	t.Helper()
	if err := os.Chmod(path, mode); err != nil {
		t.Fatalf("Setup: chmod %s to %v: %v", path, mode, err)
	}
}

func TestRunServesPublishAndStops(t *testing.T) {
	auth := newFakeAuth(t)
	s := newTestSite(t, "")
	r := startResponder(t, s, responderEnv(auth.srv.URL))

	started := logEvents(r.stdout.String(), "ingestauth_started")[0]
	if !strings.HasPrefix(r.listen, "127.0.0.1:") || r.listen == "127.0.0.1:0" {
		t.Errorf("ingestauth_started listen = %q, want the bound 127.0.0.1:<port> (only the configured IP)", r.listen)
	}
	if !strings.HasPrefix(r.health, "127.0.0.1:") || r.health == "127.0.0.1:0" {
		t.Errorf("ingestauth_started health = %q, want the bound 127.0.0.1:<port>", r.health)
	}
	wantFields := map[string]any{
		"level":       "INFO",
		"caller_ip":   "127.0.0.1",
		"auth_host":   strings.TrimPrefix(auth.srv.URL, "http://"),
		"deadline_ms": float64(1125),
	}
	for name, want := range wantFields {
		if got := started[name]; got != want {
			t.Errorf("ingestauth_started %s = %v, want %v", name, got, want)
		}
	}

	status, body := mustPostAuth(t, r.listen, publishBody(testToken, testConnID(1)), nil)
	if status != http.StatusOK || body != wantAllowedBody {
		t.Errorf("POST publish = %d (body is the allowed body: %t), want 200 with the allowed body", status, body == wantAllowedBody)
	}
	allowed, err := ingestauth.LoadAllowed(s.connLogDir)
	if err != nil {
		t.Fatalf("LoadAllowed(%s) returned error: %v", s.connLogDir, err)
	}
	if got := allowed.Tokens[testConnID(1)]; got != testToken {
		t.Errorf("connection log maps %s to %q, want %q", testConnID(1), got, testToken)
	}

	if code := r.stopAndWait(t); code != 0 {
		t.Errorf("run after its context ended = %d, want 0; stderr: %s", code, r.stderr.String())
	}
	stopped := logEvents(r.stdout.String(), "ingestauth_stopped")
	if len(stopped) != 1 || stopped[0]["level"] != "INFO" {
		t.Errorf("ingestauth_stopped events = %v, want one at INFO", stopped)
	}
	if _, _, err := postAuth(context.Background(), r.listen, publishBody(testToken, testConnID(2)), nil); err == nil {
		t.Errorf("POST after run returned succeeded, want the listener closed")
	}
	checkNoSecrets(t, []string{r.stdout.String(), r.stderr.String()})
}

// auth_host 는 URL 의 호스트:포트만이다 — 경로에 잘못 넣은 값이 기동 로그에 실리지 않는다(설계 6절 누설 규칙). 사용자
// 정보 · 질의가 든 URL 은 keyresolve.NewClient 가 거부해 설정 오류다(config_test 의 RESOLVE_URL_사용자_정보 행).
func TestRunStartedLogCarriesOnlyTheAuthHost(t *testing.T) {
	const canary = "synthetic-path-canary-0002"
	auth := newFakeAuth(t)
	env := responderEnv(auth.srv.URL + "/" + canary)
	r := startResponder(t, newTestSite(t, ""), env)
	if got, want := logEvents(r.stdout.String(), "ingestauth_started")[0]["auth_host"], strings.TrimPrefix(auth.srv.URL, "http://"); got != want {
		t.Errorf("ingestauth_started auth_host = %v, want %v", got, want)
	}
	checkNoSecrets(t, []string{r.stdout.String(), r.stderr.String()}, canary)
}

// 인증 처리기는 POST /mediamtx/auth 하나에만 있다(설계 2-6 · 2-8 authHTTPAddress). 다른 경로 · 메서드는 처리기에
// 닿지 않는다.
func TestRunAnswersOnlyTheAuthRoute(t *testing.T) {
	auth := newFakeAuth(t)
	r := startResponder(t, newTestSite(t, ""), responderEnv(auth.srv.URL))
	client := &http.Client{Transport: &http.Transport{}}
	for _, tc := range []struct {
		method, path string
		want         int
	}{
		{method: http.MethodGet, path: "/mediamtx/auth", want: http.StatusMethodNotAllowed},
		{method: http.MethodPost, path: "/mediamtx/other", want: http.StatusNotFound},
		{method: http.MethodPost, path: "/healthz", want: http.StatusNotFound},
	} {
		req, err := http.NewRequestWithContext(t.Context(), tc.method, "http://"+r.listen+tc.path, strings.NewReader(publishBody(testToken, testConnID(1))))
		if err != nil {
			t.Fatalf("NewRequest(%s %s): %v", tc.method, tc.path, err)
		}
		resp, err := client.Do(req)
		if err != nil {
			t.Fatalf("%s %s: %v", tc.method, tc.path, err)
		}
		resp.Body.Close()
		if resp.StatusCode != tc.want {
			t.Errorf("%s %s = %d, want %d", tc.method, tc.path, resp.StatusCode, tc.want)
		}
	}
	if events := logEvents(r.stdout.String(), "ingestauth_caller_denied"); len(events) != 0 {
		t.Errorf("requests outside the auth route reached the handler: %d caller_denied events", len(events))
	}
}

// 처리기 앞에 프록시 머리를 믿는 층이 없다 — 출발지는 TCP 피어뿐이다.
func TestRunIgnoresForwardedHeaders(t *testing.T) {
	auth := newFakeAuth(t)
	env := responderEnv(auth.srv.URL)
	env["INGEST_AUTH_CALLER_IP"] = "192.0.2.10" // 시험 자신(127.0.0.1)이 아니다
	r := startResponder(t, newTestSite(t, ""), env)
	forged := http.Header{
		"X-Forwarded-For": {"192.0.2.10"},
		"X-Real-Ip":       {"192.0.2.10"},
		"Forwarded":       {"for=192.0.2.10"},
	}
	if status, _ := mustPostAuth(t, r.listen, publishBody(testToken, testConnID(1)), forged); status != http.StatusForbidden {
		t.Errorf("POST publish from 127.0.0.1 with forwarded headers naming the caller = %d, want 403", status)
	}
}

// healthcheck 의 세 조건은 그때 다시 읽는다 — 거부 목록 · 기록 디렉터리의 권한을 빼면 비-200, 되돌리면 200(설계
// 착수 체크리스트 C2 · 2-6).
func TestRunHealthcheckRereadsDenyListAndConnLog(t *testing.T) {
	skipIfRoot(t)
	auth := newFakeAuth(t)
	s := newTestSite(t, testToken+"\n")
	r := startResponder(t, s, responderEnv(auth.srv.URL))
	t.Cleanup(func() {
		os.Chmod(s.denyList, 0o644)   // 실패한 단언 뒤에도 정리되게 — 에러는 판정을 바꾸지 않는다
		os.Chmod(s.connLogDir, 0o700) // 같다
	})
	steps := []struct {
		name string
		do   func()
		want int
	}{
		{name: "처음", do: func() {}, want: 0},
		{name: "거부_목록_권한_뺌", do: func() { chmodNow(t, s.denyList, 0o000) }, want: 1},
		{name: "거부_목록_권한_되돌림", do: func() { chmodNow(t, s.denyList, 0o644) }, want: 0},
		{name: "기록_디렉터리_쓰기_뺌", do: func() { chmodNow(t, s.connLogDir, 0o500) }, want: 1},
		{name: "기록_디렉터리_쓰기_되돌림", do: func() { chmodNow(t, s.connLogDir, 0o700) }, want: 0},
	}
	for _, step := range steps {
		step.do()
		if code, stderr := runHealthcheck(t, r.health); code != step.want {
			t.Errorf("after %s: healthcheck = %d, want %d; stderr: %s", step.name, code, step.want, stderr)
		}
	}
}

// healthz 가 읽은 토큰은 처리기의 합집합에 든다 — 둘은 같은 DenyList 를 쓴다(설계 3-4 1).
func TestHealthzSharesDenyListWithHandler(t *testing.T) {
	auth := newFakeAuth(t)
	s := newTestSite(t, testToken+"\n")
	r := startResponder(t, s, responderEnv(auth.srv.URL))
	if code, stderr := runHealthcheck(t, r.health); code != 0 {
		t.Fatalf("healthcheck = %d, want 0; stderr: %s", code, stderr)
	}
	writeFile(t, s.denyList, "") // 파일에서 지운다 — 한 번 읽은 토큰은 응답기 수명 동안 남는다
	if status, _ := mustPostAuth(t, r.listen, publishBody(testToken, testConnID(1)), nil); status != http.StatusUnauthorized {
		t.Errorf("POST publish for a token only healthz has read = %d, want 401", status)
	}
	denied := logEvents(r.stdout.String(), "ingestauth_publish_denied")
	if len(denied) != 1 || denied[0]["reason"] != "denylisted" {
		t.Errorf("ingestauth_publish_denied events = %v, want one with reason denylisted", denied)
	}
}

// 「수신 중」이 거짓이면 다른 두 조건이 온전해도 503 이다(설계 2-6).
func TestHealthzRequiresServing(t *testing.T) {
	s := newTestSite(t, testToken+"\n")
	deny := ingestauth.NewDenyList(s.denyList, slog.New(slog.DiscardHandler))
	conns := openConnLog(t, s.connLogDir, connLogMaxBytes, nil)
	var serving atomic.Bool
	h := healthz(&serving, deny, conns)
	for _, tc := range []struct {
		serving bool
		want    int
	}{
		{serving: false, want: http.StatusServiceUnavailable},
		{serving: true, want: http.StatusOK},
	} {
		serving.Store(tc.serving)
		req, err := http.NewRequestWithContext(t.Context(), http.MethodGet, "/healthz", nil)
		if err != nil {
			t.Fatalf("NewRequest: %v", err)
		}
		rec := &statusRecorder{header: make(http.Header)}
		h.ServeHTTP(rec, req)
		if rec.status != tc.want {
			t.Errorf("healthz with serving %t = %d, want %d", tc.serving, rec.status, tc.want)
		}
	}
}

// statusRecorder 는 처리기가 쓴 상태만 모으는 ResponseWriter 다. 아무것도 쓰지 않으면 200 이다(net/http 와 같다).
type statusRecorder struct {
	header http.Header
	status int
}

func (r *statusRecorder) Header() http.Header { return r.header }

func (r *statusRecorder) WriteHeader(status int) {
	if r.status == 0 {
		r.status = status
	}
}

func (r *statusRecorder) Write(p []byte) (int, error) {
	r.WriteHeader(http.StatusOK)
	return len(p), nil
}

// 기본 로거는 기록을 열기 전에 정한다 — 회전 로그가 run 의 JSON 로그로 나간다(ConnLog 문서).
func TestRunLogsConnLogRotationAsJSON(t *testing.T) {
	keepDefaultLogger(t)
	prior := newOutputLog()
	slog.SetDefault(slog.New(slog.NewJSONHandler(prior, nil)))
	auth := newFakeAuth(t)
	s := newTestSite(t, "")
	fillGeneration(t, s.connLogDir)
	r := startResponder(t, s, responderEnv(auth.srv.URL))
	if status, _ := mustPostAuth(t, r.listen, publishBody(testToken, testConnID(1)), nil); status != http.StatusOK {
		t.Fatalf("POST publish = %d, want 200", status)
	}
	if got := len(logEvents(r.stdout.String(), "ingestauth_connlog_rotated")); got != 1 {
		t.Errorf("run's log has %d ingestauth_connlog_rotated events, want 1", got)
	}
	if got := len(logEvents(prior.String(), "ingestauth_connlog_rotated")); got != 0 {
		t.Errorf("the logger before run got %d ingestauth_connlog_rotated events, want 0", got)
	}
}

// fillGeneration 은 dir 의 현재 세대를 1 MiB 넘게 채운다 — 응답기가 연 뒤 첫 허용에서 회전하게 한다. 줄은 실제
// ConnLog 가 쓴다(회전하지 않게 상한을 크게 준다).
func fillGeneration(t *testing.T, dir string) {
	t.Helper()
	at := time.Date(2026, 10, 13, 0, 0, 0, 123456789, time.UTC) // 소수 아홉 자리 — 줄마다 120 바이트
	c := openConnLog(t, dir, 1<<30, func() time.Time { return at })
	for i := range 8800 {
		allow(t, c, testOtherToken, testConnID(100+i))
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("Setup: ReadDir(%s): %v", dir, err)
	}
	var total int64
	for _, e := range entries {
		info, err := e.Info()
		if err != nil {
			t.Fatalf("Setup: stat %s: %v", e.Name(), err)
		}
		total += info.Size()
	}
	if total < 1<<20 {
		t.Fatalf("Setup: filled %d bytes, want at least 1 MiB", total)
	}
}

// 종료 신호(ctx 끝)에 처리 중 요청을 끊지 않고 마친다.
func TestRunFinishesInFlightRequestOnStop(t *testing.T) {
	auth := newFakeAuth(t)
	auth.delay.Store(int64(300 * time.Millisecond))
	r := startResponder(t, newTestSite(t, ""), responderEnv(auth.srv.URL))
	type result struct {
		status int
		body   string
		err    error
	}
	done := make(chan result, 1)
	go func() {
		status, body, err := postAuth(context.Background(), r.listen, publishBody(testToken, testConnID(1)), nil)
		done <- result{status: status, body: body, err: err}
	}()
	select {
	case <-auth.received:
	case <-time.After(5 * time.Second):
		t.Fatal("the fake auth did not receive the resolve within 5s")
	}
	if code := r.stopAndWait(t); code != 0 {
		t.Errorf("run = %d, want 0; stderr: %s", code, r.stderr.String())
	}
	res := <-done
	if res.err != nil || res.status != http.StatusOK || res.body != wantAllowedBody {
		t.Errorf("in-flight POST across the stop = %d, %v (allowed body: %t), want 200 with the allowed body",
			res.status, res.err, res.body == wantAllowedBody)
	}
}

// 두 서버의 한도다. 인증 처리기의 바깥 가드는 MaxDeadline + 50ms 다(설계 2-4).
func TestNewServerBoundsEveryPhase(t *testing.T) {
	srv := newServer(http.NotFoundHandler(), slog.New(slog.DiscardHandler))
	guard := ingestauth.MaxDeadline + 50*time.Millisecond
	if srv.ReadHeaderTimeout <= 0 {
		t.Errorf("ReadHeaderTimeout = %v, want > 0 (headers arrive before the caller check)", srv.ReadHeaderTimeout)
	}
	if srv.ReadTimeout <= guard {
		t.Errorf("ReadTimeout = %v, want > %v (the guard)", srv.ReadTimeout, guard)
	}
	if srv.WriteTimeout <= guard {
		t.Errorf("WriteTimeout = %v, want > %v (the guard) — a shorter one cuts answers near the deadline", srv.WriteTimeout, guard)
	}
	if srv.IdleTimeout <= 0 {
		t.Errorf("IdleTimeout = %v, want > 0", srv.IdleTimeout)
	}
	if srv.MaxHeaderBytes <= 0 || srv.MaxHeaderBytes >= http.DefaultMaxHeaderBytes {
		t.Errorf("MaxHeaderBytes = %d, want between 0 and %d", srv.MaxHeaderBytes, http.DefaultMaxHeaderBytes)
	}
	if srv.ErrorLog == nil {
		t.Errorf("ErrorLog = nil, want the server's own log routed to slog")
	}
}

// 머리를 끝내지 않는 연결은 머리 시한에 닫힌다 — 머리 단계는 출발지 검사 앞이다.
func TestRunClosesSlowHeaderConnections(t *testing.T) {
	auth := newFakeAuth(t)
	r := startResponder(t, newTestSite(t, ""), responderEnv(auth.srv.URL))
	conn, err := net.Dial("tcp", r.listen)
	if err != nil {
		t.Fatalf("dial %s: %v", r.listen, err)
	}
	defer conn.Close()
	if _, err := io.WriteString(conn, "POST /mediamtx/auth HTTP/1.1\r\nHost: ingest-auth.test\r\n"); err != nil {
		t.Fatalf("write partial headers: %v", err)
	}
	start := time.Now()
	if err := conn.SetReadDeadline(start.Add(5 * time.Second)); err != nil {
		t.Fatalf("SetReadDeadline: %v", err)
	}
	_, err = io.ReadAll(conn)
	elapsed := time.Since(start)
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		t.Fatalf("the server kept a connection with unfinished headers open for %v", elapsed)
	}
	if elapsed > 3*time.Second {
		t.Errorf("the server closed a connection with unfinished headers after %v, want within 3s", elapsed)
	}
}

// 큰 머리는 처리기에 닿기 전에 431 이다.
func TestRunRejectsOversizedHeaders(t *testing.T) {
	auth := newFakeAuth(t)
	r := startResponder(t, newTestSite(t, ""), responderEnv(auth.srv.URL))
	req, err := http.NewRequestWithContext(t.Context(), http.MethodPost, "http://"+r.listen+"/mediamtx/auth", strings.NewReader(publishBody(testToken, testConnID(1))))
	if err != nil {
		t.Fatalf("NewRequest: %v", err)
	}
	req.Header.Set("X-Pad", strings.Repeat("a", 32<<10))
	resp, err := (&http.Client{Transport: &http.Transport{}}).Do(req)
	if err != nil {
		t.Fatalf("POST with a 32 KiB header: %v", err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusRequestHeaderFieldsTooLarge {
		t.Errorf("POST with a 32 KiB header = %d, want 431", resp.StatusCode)
	}
}

// 시한 근처의 resolve(0.9초 — MaxDeadline 1.125초 안)도 200 으로 끝난다 — 서버의 쓰기 · 읽기 시한이 가드보다
// 길다.
func TestRunAnswersSlowResolveWithinDeadline(t *testing.T) {
	auth := newFakeAuth(t)
	auth.delay.Store(int64(900 * time.Millisecond))
	r := startResponder(t, newTestSite(t, ""), responderEnv(auth.srv.URL))
	status, body, err := postAuth(t.Context(), r.listen, publishBody(testToken, testConnID(1)), nil)
	if err != nil || status != http.StatusOK || body != wantAllowedBody {
		t.Errorf("POST publish with a 0.9s resolve = %d, %v (allowed body: %t), want 200 with the allowed body",
			status, err, body == wantAllowedBody)
	}
}

// 인증 서버가 스스로 멈추면 healthz 서버도 멈추고 돌아온다.
func TestServeUntilDoneStopsBothWhenOneServerFails(t *testing.T) {
	discard := slog.New(slog.DiscardHandler)
	authLn, healthLn := listenLoopback(t), listenLoopback(t)
	var serving atomic.Bool
	done := make(chan error, 1)
	go func() {
		done <- serveUntilDone(context.Background(), &serving,
			listening{srv: newServer(http.NotFoundHandler(), discard), ln: authLn},
			listening{srv: newServer(http.NotFoundHandler(), discard), ln: healthLn})
	}()
	deadline := time.Now().Add(5 * time.Second)
	for !serving.Load() {
		if time.Now().After(deadline) {
			t.Fatal("serveUntilDone did not start serving within 5s")
		}
		time.Sleep(5 * time.Millisecond)
	}
	authLn.Close() // 인증 서버의 Accept 가 실패한다
	select {
	case err := <-done:
		if err == nil {
			t.Errorf("serveUntilDone after the auth listener failed = nil, want an error")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("serveUntilDone did not return within 5s after the auth listener failed")
	}
	if serving.Load() {
		t.Errorf("serving = true after serveUntilDone returned")
	}
	if conn, err := net.Dial("tcp", healthLn.Addr().String()); err == nil {
		conn.Close()
		t.Errorf("the healthz listener still accepts after serveUntilDone returned")
	}
}

// 기동 실패는 1 이고 시작 로그 없이 돌아온다.
func TestRunFailsToStart(t *testing.T) {
	for _, tc := range []struct {
		name string
		edit func(t *testing.T, s *site, env map[string]string)
	}{
		{name: "healthz_주소를_이미_씀", edit: func(t *testing.T, s *site, _ map[string]string) {
			s.healthAddr = listenLoopback(t).Addr().String()
		}},
		{name: "기록_디렉터리_없음", edit: func(_ *testing.T, s *site, _ map[string]string) { s.connLogDir += "/missing" }},
		// 문서용 주소는 시험 기계에 없어 묶을 수 없다.
		{name: "인증_주소를_묶을_수_없음", edit: func(_ *testing.T, _ *site, env map[string]string) {
			env["INGEST_AUTH_LISTEN"] = "192.0.2.20:0"
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			keepDefaultLogger(t)
			auth := newFakeAuth(t)
			s := newTestSite(t, "")
			env := responderEnv(auth.srv.URL)
			tc.edit(t, &s, env)
			// 기동에 성공해 버리는 변이도 시험을 멈추지 않게 5초 상한을 둔다.
			ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
			defer cancel()
			stdout, stderr := newOutputLog(), newOutputLog()
			code := run(ctx, s, nil, getenvFrom(env), stdout, stderr)
			if code != 1 {
				t.Errorf("run = %d, want 1; stderr: %s", code, stderr.String())
			}
			if stderr.String() == "" {
				t.Errorf("run wrote no reason to stderr")
			}
			if events := logEvents(stdout.String(), "ingestauth_started"); len(events) != 0 {
				t.Errorf("run logged ingestauth_started although it failed to start")
			}
		})
	}
}

// LOG_LEVEL 은 run 의 로그 등급이다(선택 — 비면 info). api 행의 허용은 DEBUG 다. 처리기는 MTX_API_USER · PASS 로 api
// 행을 판정한다.
func TestRunHonorsLogLevelAndAPICredentials(t *testing.T) {
	for _, tc := range []struct {
		name      string
		level     string
		wantDebug bool
	}{
		{name: "기본_info", level: "", wantDebug: false},
		{name: "debug", level: "debug", wantDebug: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			auth := newFakeAuth(t)
			env := responderEnv(auth.srv.URL)
			if tc.level != "" {
				env["LOG_LEVEL"] = tc.level
			}
			r := startResponder(t, newTestSite(t, ""), env)
			if status, _ := mustPostAuth(t, r.listen, apiBody("127.0.0.1", testAPIUser, testAPIPass), nil); status != http.StatusOK {
				t.Errorf("POST api from SelfIP with the configured credentials = %d, want 200", status)
			}
			if status, _ := mustPostAuth(t, r.listen, apiBody("127.0.0.1", testAPIUser, "synthetic-wrong-pass"), nil); status != http.StatusUnauthorized {
				t.Errorf("POST api with a wrong password = %d, want 401", status)
			}
			if got := len(logEvents(r.stdout.String(), "ingestauth_api_allowed")) > 0; got != tc.wantDebug {
				t.Errorf("ingestauth_api_allowed logged = %t, want %t", got, tc.wantDebug)
			}
			checkNoSecrets(t, []string{r.stdout.String(), r.stderr.String()}, "synthetic-wrong-pass")
		})
	}
}
