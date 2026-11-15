package credrelay

// 시험 도우미 — IMDS 꼴 서버(fakeIMDS) · 원천 호출 기록(countingSource) · 로그 받기(logBuf) · 중계 한 벌(relay).
// 자격 · 토큰 값은 모두 합성이고, 누설 시험이 찾는 카나리아다.

import (
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
)

// 합성 비밀(카나리아). 로그 · 에러 · 오류 본문 어디에도 나오면 안 된다.
const (
	canaryAccessKey    = "ASIACANARYAKID7Q2W"
	canarySecretKey    = "canarySecretKey9x4v"
	canarySessionToken = "canarySessionTok3m8k"
	canaryIMDSToken    = "canaryImdsTok5p1z"
	canaryIndexerToken = "canaryIndexerTok6b2n"
	canaryJanitorToken = "canaryJanitorTok8d4h"
)

const (
	imdsTokenPath = "/latest/api/token"
	imdsRolesPath = "/latest/meta-data/iam/security-credentials/"
	testRoleName  = "media-role"
)

// roleCreds 는 IMDS 의 역할 자격 JSON 꼴이다.
type roleCreds struct {
	Code            string    `json:"Code"`
	AccessKeyID     string    `json:"AccessKeyId"`
	SecretAccessKey string    `json:"SecretAccessKey"`
	Token           string    `json:"Token"`
	Expiration      time.Time `json:"Expiration"`
}

// fakeIMDS 는 IMDSv2 꼴(토큰 PUT · 역할 목록 · 역할 자격)을 내는 서버다. 상태는 update 로만 바꾼다.
type fakeIMDS struct {
	srv *httptest.Server

	mu          sync.Mutex
	roles       string        // 역할 목록 본문
	creds       roleCreds     // 역할 자격(이름과 무관하게 같은 것)
	credsStatus int           // 0 이 아니면 역할 자격 요청에 이 상태만 낸다
	tokenStatus int           // 0 이 아니면 토큰 PUT 에 이 상태만 내고, 토큰 없는 요청(IMDSv1)에도 답한다
	bodyDelay   time.Duration // 머리는 바로, 본문은 이만큼 늦게(토큰 PUT · 역할 GET 둘 다 — 계획 E11)
	headerDelay time.Duration // 모든 요청에 머리를 이만큼 늦게
}

// newFakeIMDS 는 역할 하나 · 카나리아 자격 · 만료 expires 를 내는 서버를 띄운다.
func newFakeIMDS(t *testing.T, expires time.Time) *fakeIMDS {
	t.Helper()
	f := &fakeIMDS{
		roles: testRoleName,
		creds: roleCreds{Code: "Success", AccessKeyID: canaryAccessKey, SecretAccessKey: canarySecretKey, Token: canarySessionToken, Expiration: expires},
	}
	f.srv = httptest.NewServer(http.HandlerFunc(f.serve))
	t.Cleanup(f.srv.Close)
	return f
}

func (f *fakeIMDS) update(fn func(f *fakeIMDS)) {
	f.mu.Lock()
	defer f.mu.Unlock()
	fn(f)
}

// source 는 운영과 같은 생성자로 이 서버를 읽는 원천을 만든다(주소만 다르다).
func (f *fakeIMDS) source(now func() time.Time) aws.CredentialsProvider {
	return NewIMDSRoleSource(NewIMDSClient(f.srv.URL), now)
}

func (f *fakeIMDS) serve(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	roles, creds, credsStatus, tokenStatus, delay, headerDelay := f.roles, f.creds, f.credsStatus, f.tokenStatus, f.bodyDelay, f.headerDelay
	f.mu.Unlock()

	if headerDelay > 0 {
		select {
		case <-r.Context().Done():
			return
		case <-time.After(headerDelay):
		}
	}

	if r.Method == http.MethodPut && r.URL.Path == imdsTokenPath {
		if tokenStatus != 0 {
			w.WriteHeader(tokenStatus)
			return
		}
		w.Header().Set("X-Aws-Ec2-Metadata-Token-Ttl-Seconds", "21600")
		writeDelayed(w, r, delay, []byte(canaryIMDSToken))
		return
	}
	if r.Method != http.MethodGet || !strings.HasPrefix(r.URL.Path, imdsRolesPath) {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	// 토큰 PUT 을 막은 상태(tokenStatus)는 IMDSv1 만 되는 상자 꼴이라 토큰 없는 요청에도 답한다.
	if tokenStatus == 0 && r.Header.Get("X-Aws-Ec2-Metadata-Token") != canaryIMDSToken {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	if r.URL.Path == imdsRolesPath {
		writeDelayed(w, r, delay, []byte(roles))
		return
	}
	if credsStatus != 0 {
		w.WriteHeader(credsStatus)
		return
	}
	body, err := json.Marshal(creds)
	if err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	writeDelayed(w, r, delay, body)
}

// writeDelayed 는 머리를 바로 보내고 본문을 delay 뒤에 보낸다. 상대가 먼저 끊으면 기다리지 않고 끝낸다(서버
// 닫기가 늦은 처리기를 기다리지 않게).
func writeDelayed(w http.ResponseWriter, r *http.Request, delay time.Duration, body []byte) {
	if delay > 0 {
		w.WriteHeader(http.StatusOK)
		if fl, ok := w.(http.Flusher); ok {
			fl.Flush()
		}
		select {
		case <-r.Context().Done():
			return
		case <-time.After(delay):
		}
	}
	_, _ = w.Write(body)
}

// countingSource 는 원천 호출 시각(주입 시계)을 적는다 — 원천 호출 수 · 시각 단언(P27 · P28)에 쓴다.
type countingSource struct {
	inner aws.CredentialsProvider
	now   func() time.Time

	mu    sync.Mutex
	calls []time.Time
}

func (c *countingSource) Retrieve(ctx context.Context) (aws.Credentials, error) {
	c.mu.Lock()
	c.calls = append(c.calls, c.now())
	c.mu.Unlock()
	return c.inner.Retrieve(ctx)
}

func (c *countingSource) callTimes() []time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return append([]time.Time(nil), c.calls...)
}

// logBuf 는 JSON 로그를 받아 두는 곳이다. 여러 고루틴이 함께 써도 된다.
type logBuf struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *logBuf) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *logBuf) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

// events 는 msg 가 name 인 기록을 차례대로 돌려준다.
func (b *logBuf) events(t *testing.T, name string) []map[string]any {
	t.Helper()
	var out []map[string]any
	for _, line := range strings.Split(strings.TrimSpace(b.String()), "\n") {
		if line == "" {
			continue
		}
		var rec map[string]any
		if err := json.Unmarshal([]byte(line), &rec); err != nil {
			t.Fatalf("로그 한 줄이 JSON 이 아니다: %v", err)
		}
		if rec["msg"] == name {
			out = append(out, rec)
		}
	}
	return out
}

// relay 는 가짜 시계 · IMDS 꼴 서버 · 운영 원천 · 토큰 둘(indexer · janitor)로 만든 처리기 한 벌이다.
type relay struct {
	clock *manualClock
	imds  *fakeIMDS
	src   *countingSource
	logs  *logBuf
	h     http.Handler
}

// newRelay 는 원천 만료가 시계 시작 + expiresIn 인 중계를 만든다.
func newRelay(t *testing.T, expiresIn time.Duration) *relay {
	t.Helper()
	clock := newManualClock()
	fake := newFakeIMDS(t, clock.start.Add(expiresIn))
	src := &countingSource{inner: fake.source(clock.now), now: clock.now}
	logs := &logBuf{}
	h := NewHandler(src, loadTestTokens(t), clock.now, newTestLogger(logs))
	return &relay{clock: clock, imds: fake, src: src, logs: logs, h: h}
}

func newTestLogger(b *logBuf) *slog.Logger {
	return slog.New(slog.NewJSONHandler(b, &slog.HandlerOptions{Level: slog.LevelDebug}))
}

// loadTestTokens 는 호출자 둘의 토큰 파일(0400)을 두고 LoadTokens 로 읽는다.
func loadTestTokens(t *testing.T) Tokens {
	t.Helper()
	dir := t.TempDir()
	writeToken(t, dir, "indexer", canaryIndexerToken)
	writeToken(t, dir, "janitor", canaryJanitorToken)
	tok, err := LoadTokens(dir, []string{"indexer", "janitor"})
	if err != nil {
		t.Fatalf("LoadTokens 실패: %v", err)
	}
	return tok
}

// writeToken 은 dir/name 에 content 를 0400 으로 둔다(끝 개행 없음 — 시작 스크립트와 같은 꼴).
func writeToken(t *testing.T, dir, name, content string) string {
	t.Helper()
	path := filepath.Join(dir, name)
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatalf("토큰 파일 쓰기 실패: %v", err)
	}
	if err := os.Chmod(path, 0o400); err != nil {
		t.Fatalf("토큰 파일 chmod 실패: %v", err)
	}
	return path
}

// get 은 GET /v1/credentials 를 token 으로 보낸다(빈 값이면 Authorization 없음).
func (r *relay) get(token string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodGet, CredentialsPath, nil)
	if token != "" {
		req.Header.Set("Authorization", token)
	}
	rec := httptest.NewRecorder()
	r.h.ServeHTTP(rec, req)
	return rec
}

// servedCreds 는 200 본문이다.
type servedCreds struct {
	AccessKeyID     string    `json:"AccessKeyId"`
	SecretAccessKey string    `json:"SecretAccessKey"`
	Token           string    `json:"Token"`
	Expiration      time.Time `json:"Expiration"`
}

// mustServe 는 rec 가 200 인지 보고 본문을 푼다.
func mustServe(t *testing.T, rec *httptest.ResponseRecorder, what string) servedCreds {
	t.Helper()
	if rec.Code != http.StatusOK {
		t.Fatalf("%s: 상태 %d, want 200", what, rec.Code)
	}
	var got servedCreds
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("%s: 200 본문을 풀지 못했다: %v", what, err)
	}
	return got
}
