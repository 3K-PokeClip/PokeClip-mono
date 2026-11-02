package main

// 60일 만료 조립(POK-196 M6-4 c2 · 설계 M6 7절 활성화 선행 조건 · D50 · D24).

import (
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/expiry"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// 켜는 조건 넷 — 주소 · 버킷 · 발행 몫(강등이면 끔) — 의 사유 표(권한은 DB 가 있어야 해 아래 PG 시험).
func TestExpiryGateReasons(t *testing.T) {
	for _, tt := range []struct {
		url, bucket string
		publishing  bool
		want        string
	}{
		{"", "b", true, "no_url"},
		{"http://clip", "", false, "no_bucket"},
		{"http://clip", "b", false, "publish_degraded"},
		{"http://clip", "b", true, ""},
	} {
		cfg := config.Config{}
		cfg.Expiry.ClipURL, cfg.S3.Bucket = tt.url, tt.bucket
		if got := expiryGate(cfg, tt.publishing); got != tt.want {
			t.Errorf("%+v → %q, want %q", tt, got, tt.want)
		}
	}
}

// expiryLog 는 JSON 처리기 로그와 그 줄들이다.
func expiryLog() (*slog.Logger, *bytes.Buffer) {
	var buf bytes.Buffer
	return slog.New(slog.NewJSONHandler(&buf, nil)), &buf
}

func logLines(t *testing.T, buf *bytes.Buffer, msg string) []map[string]any {
	t.Helper()
	var out []map[string]any
	for _, line := range strings.Split(strings.TrimSpace(buf.String()), "\n") {
		var m map[string]any
		if json.Unmarshal([]byte(line), &m) == nil && m["msg"] == msg {
			out = append(out, m)
		}
	}
	return out
}

// countingClip 은 요청 수만 세는 만료 창구다(빈 장).
func countingClip(t *testing.T) (*httptest.Server, *atomic.Int32) {
	t.Helper()
	var n atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		n.Add(1)
		_, _ = w.Write([]byte(`{"broadcasts":[],"nextCursor":null,"hasMore":false}`))
	}))
	t.Cleanup(srv.Close)
	return srv, &n
}

// D50 — 운영 권한이 아직 없는(옛 권한) 계정으로 기동하면 만료를 켜지 않는다: ERROR 한 줄 · 창구 요청 0.
func TestStartExpiryDisabledWithoutPrivileges(t *testing.T) {
	admin := newWireTestPool(t)
	role := pgtest.NewMediaIndexerRole(t, admin)
	role.Revoke(t, "DELETE", "stream_sessions")
	clip, requests := countingClip(t)
	cfg := config.Config{}
	cfg.Expiry.ClipURL, cfg.S3.Bucket = clip.URL, "b"
	log, buf := expiryLog()

	stop := startExpiry(t.Context(), cfg, role.Pool, true, func(string) {}, log)
	stop()
	lines := logLines(t, buf, "expiry_disabled")
	if len(lines) != 1 || lines[0]["reason"] != "privilege" || lines[0]["level"] != "ERROR" ||
		!strings.Contains(lines[0]["missing"].(string), "stream_sessions:DELETE") {
		t.Fatalf("expiry_disabled %v", lines)
	}
	if requests.Load() != 0 {
		t.Fatal("권한 없이 창구를 불렀다")
	}
}

// 켜짐 · 멈춤 — 권한 · 주소 · 버킷 · 발행 몫이 다 있으면 고루틴이 첫 회차를 돌고, stop 은 Run 이 돌아온 뒤에 돌아온다
// (풀 Close 보다 먼저 불리는 defer — D24). 토큰은 파일에서 읽는다(secretfile 규칙).
func TestStartExpiryRunsAndStops(t *testing.T) {
	admin := newWireTestPool(t)
	role := pgtest.NewMediaIndexerRole(t, admin)
	assemblyCredentialsEnv(t)
	s3 := newFakeS3(t, http.StatusOK)
	clip, requests := countingClip(t)
	cfg := assemblyCfg(t, s3.srv.URL)
	cfg.Expiry.ClipURL = clip.URL
	cfg.Expiry.TokenFile = filepath.Join(t.TempDir(), "clip-expiry-token")
	if err := os.WriteFile(cfg.Expiry.TokenFile, []byte("tok-canary-7f3a\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	log, buf := expiryLog()

	stop := startExpiry(t.Context(), cfg, role.Pool, true, func(string) {}, log)
	deadline := time.Now().Add(10 * time.Second)
	for requests.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	stop()
	after := requests.Load()
	if after == 0 {
		t.Fatalf("첫 회차가 돌지 않았다: %s", buf.String())
	}
	time.Sleep(100 * time.Millisecond)
	if requests.Load() != after {
		t.Fatal("stop 뒤에도 창구를 불렀다")
	}
	if len(logLines(t, buf, "expiry_started")) != 1 || strings.Contains(buf.String(), "tok-canary-7f3a") {
		t.Fatalf("기동 줄이 없거나 토큰이 찍혔다: %s", buf.String())
	}
}

// 토큰 파일을 못 읽으면 만료만 강등된다(사이드카는 계속 — ADR-063 결정 1). 오류 줄에 토큰은 없다.
func TestStartExpiryTokenFileFailureDegrades(t *testing.T) {
	admin := newWireTestPool(t)
	role := pgtest.NewMediaIndexerRole(t, admin)
	clip, requests := countingClip(t)
	cfg := config.Config{}
	cfg.Expiry.ClipURL, cfg.S3.Bucket = clip.URL, "b"
	cfg.Expiry.TokenFile = filepath.Join(t.TempDir(), "missing")
	log, buf := expiryLog()
	stop := startExpiry(t.Context(), cfg, role.Pool, true, func(string) {}, log)
	stop()
	if lines := logLines(t, buf, "expiry_disabled"); len(lines) != 1 || lines[0]["reason"] != "token_file" {
		t.Fatalf("expiry_disabled %v", lines)
	}
	if requests.Load() != 0 {
		t.Fatal("토큰 없이 창구를 불렀다")
	}
}

// 값 토큰(CLIP_EXPIRY_TOKEN)도 받는다(설계 7절 Z2).
func TestExpiryTokenFromValueOrFile(t *testing.T) {
	cfg := config.Config{}
	cfg.Expiry.Token, cfg.Expiry.HasToken = expiry.NewToken("v"), true
	if _, err := expiryToken(cfg); err != nil {
		t.Fatalf("값 토큰: %v", err)
	}
	cfg = config.Config{}
	if tok, err := expiryToken(cfg); err != nil || tok != (expiry.Token{}) {
		t.Fatalf("토큰 없음 = %v %v (영값 · 오류 없음 기대)", tok, err)
	}
}

// cc c5 — 권한 확인은 기동 DB 단계와 같은 상한(dbStepTimeout) 안에서 끝난다. 답하지 않는 DB 에서도 기동이 걸리지 않고
// 만료만 꺼진다(reason=privilege_check). 상한이 20초라 실주행 게이트(REQUIRE_PG=1)에서만 돈다.
func TestStartExpiryPrivilegeCheckIsBounded(t *testing.T) {
	if os.Getenv("REQUIRE_PG") != "1" {
		t.Skip("20초 시험 — REQUIRE_PG=1 실주행 게이트에서만 돈다")
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	go func() { // 받기만 하고 답하지 않는 DB
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			t.Cleanup(func() { _ = c.Close() })
		}
	}()
	pool, err := pgxpool.New(t.Context(), "postgres://u@"+ln.Addr().String()+"/db?sslmode=disable")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	cfg := config.Config{}
	cfg.Expiry.ClipURL, cfg.S3.Bucket = "http://127.0.0.1:1", "b"
	log, buf := expiryLog()

	done := make(chan struct{})
	go func() {
		defer close(done)
		startExpiry(context.Background(), cfg, pool, true, func(string) {}, log)()
	}()
	select {
	case <-done:
	case <-time.After(dbStepTimeout + 10*time.Second):
		t.Fatal("권한 확인이 상한 없이 걸려 있다")
	}
	if lines := logLines(t, buf, "expiry_disabled"); len(lines) != 1 || lines[0]["reason"] != "privilege_check" {
		t.Fatalf("expiry_disabled %v", lines)
	}
}
