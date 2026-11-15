package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// healthcheck 는 CRED_RELAY_LISTEN 의 /healthz 가 2xx 면 0, 그 밖(다른 상태 · 연결 실패 · 설정 꼴 밖)은 1 이다
// (docker 는 2 를 예약해 둔다).
func TestHealthcheck(t *testing.T) {
	answering := func(status int) string {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Method != http.MethodGet || r.URL.Path != "/healthz" {
				http.NotFound(w, r)
				return
			}
			w.WriteHeader(status)
		}))
		t.Cleanup(srv.Close)
		return strings.TrimPrefix(srv.URL, "http://")
	}
	// redirected 는 /healthz 를 다른 경로로 돌리고 그 경로는 200 인 서버다 — 리다이렉트를 따라가면 2xx 로 보인다.
	redirected := func() string {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path == "/healthz" {
				http.Redirect(w, r, "/elsewhere", http.StatusFound)
				return
			}
			w.WriteHeader(http.StatusOK)
		}))
		t.Cleanup(srv.Close)
		return strings.TrimPrefix(srv.URL, "http://")
	}
	refused := func() string {
		ln := localListener(t)
		addr := ln.Addr().String()
		_ = ln.Close()
		return addr
	}
	for _, tc := range []struct {
		name   string
		listen string
		want   int
	}{
		{name: "204", listen: answering(http.StatusNoContent), want: 0},
		{name: "200", listen: answering(http.StatusOK), want: 0},
		{name: "503", listen: answering(http.StatusServiceUnavailable), want: 1},
		{name: "302_뒤_200", listen: redirected(), want: 1},
		{name: "연결_거부", listen: refused(), want: 1},
		{name: "listen_없음", listen: "", want: 1},
		{name: "listen_꼴_밖", listen: "relay.local:9911", want: 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var stdout, stderr lockedBuffer
			code := run(t.Context(), site{}, []string{"healthcheck"}, getenvFrom(map[string]string{envListen: tc.listen}), &stdout, &stderr)
			if code != tc.want {
				t.Errorf("healthcheck = %d, want %d", code, tc.want)
			}
			if tc.want != 0 && stderr.String() == "" {
				t.Error("실패인데 stderr 에 까닭이 없다")
			}
			if stdout.String() != "" {
				t.Errorf("stdout 에 %d바이트를 썼다, want 0", len(stdout.String()))
			}
		})
	}
}

// 받기만 하고 답하지 않는 중계에 healthcheck 는 healthTimeout 에 1 로 끝난다 — compose 의 기본 시한(30초)까지
// 매달리지 않고 막힌 까닭을 stderr 에 남긴다(c3 cc).
func TestHealthcheckHonoursTimeout(t *testing.T) {
	listen := strings.TrimPrefix(silentIMDS(t), "http://")
	var stdout, stderr lockedBuffer
	exited := make(chan int, 1)
	start := time.Now()
	go func() {
		exited <- run(t.Context(), site{}, []string{"healthcheck"}, getenvFrom(map[string]string{envListen: listen}), &stdout, &stderr)
	}()
	select {
	case code := <-exited:
		if took := time.Since(start); code != 1 || took < healthTimeout {
			t.Errorf("답 없는 중계: healthcheck = %d (%v 뒤), want 1 (%v 이상 기다림)", code, took, healthTimeout)
		}
		if stderr.String() == "" {
			t.Error("실패인데 stderr 에 까닭이 없다")
		}
	case <-time.After(healthTimeout + 2*time.Second):
		t.Fatalf("답 없는 중계: healthcheck 가 %v 안에 끝나지 않았다", healthTimeout+2*time.Second)
	}
}

// healthcheck 는 serve 의 /healthz 에 0 이다(조립 경로). serve 는 링크 로컬 주소만 받으므로 미리 연 루프백
// 리스너로 듣게 하고, healthcheck 의 CRED_RELAY_LISTEN 은 그 리스너 주소다.
func TestHealthcheckAgainstServe(t *testing.T) {
	ln := localListener(t)
	var stdout, stderr lockedBuffer
	ctx, cancel := context.WithCancel(t.Context())
	done := startServe(ctx, t, testSite(ln, newFakeIMDS(t).srv.URL), relayEnv("169.254.170.2:9911", defaultCallerDir(t)), &stdout, &stderr)
	waitHealthy(t, ln.Addr().String())
	var hout, herr lockedBuffer
	if code := run(t.Context(), site{}, []string{"healthcheck"}, getenvFrom(map[string]string{envListen: ln.Addr().String()}), &hout, &herr); code != 0 {
		t.Errorf("healthcheck = %d, want 0 (stderr %q)", code, herr.String())
	}
	cancel()
	if code := waitExit(t, done); code != 0 {
		t.Errorf("serve 종료 = %d, want 0", code)
	}
}
