package main

// 시험 도우미 — IMDS 꼴 서버(fakeIMDS) · 토큰 디렉터리 · 로그 받기(lockedBuffer) · 미리 연 리스너를 주는 site.
// 자격 · 토큰 값은 모두 합성이고, 누설 시험이 찾는 카나리아다.

import (
	"bytes"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// 합성 비밀(카나리아). 로그 · stderr · isolation-check 출력 어디에도 나오면 안 된다.
const (
	canaryAccessKey    = "ASIACANARYCMDAK4R8"
	canarySecretKey    = "canaryCmdSecret7t3w"
	canarySessionToken = "canaryCmdSession2h6q"
	canaryIMDSToken    = "canaryCmdImdsTok9c5x"
	canaryIMDSBody     = "canaryCmdImdsBody1f7u" // IMDS 오류 응답 본문 · 경로 자리
	canaryIndexerToken = "canaryCmdIndexer3j8m"
	canaryJanitorToken = "canaryCmdJanitor5k2p"
	canaryWrongToken   = "canaryCmdWrongTok6n1v"
	canaryAcceptError  = "canaryCmdAcceptErr8g4y"
)

var canaries = []string{canaryAccessKey, canarySecretKey, canarySessionToken, canaryIMDSToken, canaryIMDSBody, canaryIndexerToken, canaryJanitorToken, canaryWrongToken, canaryAcceptError}

func assertNoCanary(t *testing.T, where, text string) {
	t.Helper()
	for _, c := range canaries {
		if strings.Contains(text, c) {
			t.Errorf("%s에서 카나리아 발견(길이 %d)", where, len(c))
		}
	}
}

// fakeIMDS 는 IMDSv2 꼴(토큰 PUT · 역할 목록 · 역할 자격)을 내는 서버다. failing 이면 역할 자격 요청에 500 과
// 카나리아 본문을 낸다. credsDelay 가 있으면 역할 자격 요청이 온 것을 credsAsked 에 알리고 본문을 그만큼 늦게
// 보낸다.
type fakeIMDS struct {
	srv *httptest.Server

	mu         sync.Mutex
	failing    bool
	credsDelay time.Duration
	credsAsked chan struct{}
}

func newFakeIMDS(t *testing.T) *fakeIMDS {
	t.Helper()
	f := &fakeIMDS{}
	f.srv = httptest.NewServer(http.HandlerFunc(f.serve))
	t.Cleanup(f.srv.Close)
	return f
}

func (f *fakeIMDS) setFailing(v bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.failing = v
}

// slowCreds 는 역할 자격 답을 d 만큼 늦추고, 역할 자격 요청이 오면 알리는 채널을 돌려준다.
func (f *fakeIMDS) slowCreds(d time.Duration) <-chan struct{} {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.credsDelay, f.credsAsked = d, make(chan struct{}, 1)
	return f.credsAsked
}

func (f *fakeIMDS) serve(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	failing, delay, asked := f.failing, f.credsDelay, f.credsAsked
	f.mu.Unlock()
	const rolesPath = "/latest/meta-data/iam/security-credentials/"
	switch {
	case r.Method == http.MethodPut && r.URL.Path == "/latest/api/token":
		// SDK 는 이 머리로 토큰 수명을 읽는다 — 없으면 토큰 받기가 실패한다.
		w.Header().Set("X-Aws-Ec2-Metadata-Token-Ttl-Seconds", "21600")
		_, _ = w.Write([]byte(canaryIMDSToken))
	case r.Header.Get("X-aws-ec2-metadata-token") != canaryIMDSToken:
		http.Error(w, "no token", http.StatusUnauthorized)
	case r.URL.Path == rolesPath:
		_, _ = w.Write([]byte("media-role"))
	case r.URL.Path == rolesPath+"media-role" && failing:
		http.Error(w, "internal "+canaryIMDSBody, http.StatusInternalServerError)
	case r.URL.Path == rolesPath+"media-role":
		if delay > 0 {
			// 머리는 바로 보내고 본문만 늦춘다 — 머리를 늦추면 IMDS 클라이언트의 응답 머리 상한(500ms)에 걸린다.
			w.WriteHeader(http.StatusOK)
			w.(http.Flusher).Flush()
			select {
			case asked <- struct{}{}:
			default:
			}
			time.Sleep(delay)
		}
		_ = json.NewEncoder(w).Encode(map[string]string{
			"Code":            "Success",
			"AccessKeyId":     canaryAccessKey,
			"SecretAccessKey": canarySecretKey,
			"Token":           canarySessionToken,
			"Expiration":      time.Now().Add(6 * time.Hour).UTC().Format(time.RFC3339),
		})
	default:
		http.NotFound(w, r)
	}
}

// writeCallerDir 는 호출자 토큰 디렉터리를 만든다 — 파일마다 0400 · 한 줄 · 끝 개행 없음(시작 스크립트 꼴).
func writeCallerDir(t *testing.T, tokens map[string]string) string {
	t.Helper()
	dir := filepath.Join(t.TempDir(), "cred-relay")
	if err := os.Mkdir(dir, 0o750); err != nil {
		t.Fatal(err)
	}
	for name, v := range tokens {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(v), 0o400); err != nil {
			t.Fatal(err)
		}
	}
	return dir
}

// defaultCallerDir 는 indexer · janitor 카나리아 토큰을 둔 디렉터리다.
func defaultCallerDir(t *testing.T) string {
	t.Helper()
	return writeCallerDir(t, map[string]string{"indexer": canaryIndexerToken, "janitor": canaryJanitorToken})
}

// relayEnv 는 serve 의 env 다(compose 렌더와 같은 이름 — 계획 E21).
func relayEnv(listen, dir string) map[string]string {
	return map[string]string{envListen: listen, envCallerDir: dir, envCallers: "indexer,janitor"}
}

func getenvFrom(env map[string]string) func(string) string {
	return func(k string) string { return env[k] }
}

// lockedBuffer 는 여러 고루틴이 쓰는 로그를 받는다.
type lockedBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *lockedBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *lockedBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

// events 는 JSON 로그 줄 가운데 msg 가 name 인 것들이다.
func events(t *testing.T, logs, name string) []map[string]any {
	t.Helper()
	var out []map[string]any
	for line := range strings.Lines(logs) {
		var m map[string]any
		if err := json.Unmarshal([]byte(line), &m); err != nil {
			t.Fatalf("로그 줄이 JSON 이 아니다: %v", err)
		}
		if m["msg"] == name {
			out = append(out, m)
		}
	}
	return out
}

// testSite 는 serve 가 미리 연 리스너 ln 을 쓰게 하는 site 다. IMDS 는 imdsURL 이다.
func testSite(ln net.Listener, imdsURL string) site {
	return site{
		imdsEndpoint:  imdsURL,
		relayURL:      "http://127.0.0.1:1" + "/v1/credentials",
		isolationWait: 300 * time.Millisecond,
		listen: func(network, address string) (net.Listener, error) {
			return ln, nil
		},
	}
}

// localListener 는 루프백 임의 포트 리스너다.
func localListener(t *testing.T) net.Listener {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	return ln
}

// testClient 는 시험의 HTTP 클라이언트다 — 받기만 하고 답하지 않는 리스너에 매달리지 않게 상한을 둔다.
var testClient = &http.Client{Timeout: 5 * time.Second}

// waitHealthy 는 addr 의 /healthz 가 204 를 낼 때까지 기다린다.
func waitHealthy(t *testing.T, addr string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		resp, err := testClient.Get("http://" + addr + "/healthz")
		if err == nil {
			_ = resp.Body.Close()
			if resp.StatusCode == http.StatusNoContent {
				return
			}
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("%s 의 /healthz 가 5초 안에 204 를 내지 않았다", addr)
}

// getCreds 는 token 을 Authorization 에 실어(빈 값이면 싣지 않음) 자격을 묻고 상태와 본문을 돌려준다.
func getCreds(t *testing.T, addr, token string) (int, string) {
	t.Helper()
	req, err := http.NewRequest(http.MethodGet, "http://"+addr+"/v1/credentials", nil)
	if err != nil {
		t.Fatal(err)
	}
	if token != "" {
		req.Header.Set("Authorization", token)
	}
	resp, err := testClient.Do(req)
	if err != nil {
		t.Fatalf("자격 요청 실패: %v", err)
	}
	defer resp.Body.Close()
	var b bytes.Buffer
	if _, err := b.ReadFrom(resp.Body); err != nil {
		t.Fatal(err)
	}
	return resp.StatusCode, b.String()
}
