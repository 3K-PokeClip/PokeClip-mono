package main

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// healthcheck 하위 명령의 시험이다. /healthz 가 200 이면 0, 그 밖은 늘 1 이다 — docker 는 2 를 예약해 둔다.
// 가짜 healthz 는 GET /healthz 만 받는다.

// fakeHealthz 는 GET /healthz 에 answer 로 답하는 가짜다. 다른 경로 · 메서드는 404 다.
func fakeHealthz(t *testing.T, answer http.HandlerFunc) string {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.URL.Path != "/healthz" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		answer(w, r)
	}))
	t.Cleanup(srv.Close)
	return srv.Listener.Addr().String()
}

func TestCheckHealthExitCodes(t *testing.T) {
	for _, tc := range []struct {
		name       string
		answer     http.HandlerFunc
		want       int
		wantStderr string // stderr 에 있어야 하는 조각(빈 값이면 stderr 가 비어야 한다)
	}{
		{
			name:   "200",
			answer: func(w http.ResponseWriter, _ *http.Request) { io.WriteString(w, "ok\n") },
			want:   0,
		},
		{
			name: "503_과_까닭",
			answer: func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(http.StatusServiceUnavailable)
				io.WriteString(w, "synthetic deny list failure\n")
			},
			want:       1,
			wantStderr: "synthetic deny list failure",
		},
		{
			name:       "500",
			answer:     func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusInternalServerError) },
			want:       1,
			wantStderr: "500",
		},
	} {
		t.Run(tc.name, func(t *testing.T) {
			code, stderr := runHealthcheck(t, fakeHealthz(t, tc.answer))
			if code != tc.want {
				t.Errorf("healthcheck against %s = %d, want %d; stderr: %s", tc.name, code, tc.want, stderr)
			}
			if tc.wantStderr == "" && stderr != "" {
				t.Errorf("healthcheck against %s wrote to stderr: %q, want nothing", tc.name, stderr)
			}
			if !strings.Contains(stderr, tc.wantStderr) {
				t.Errorf("healthcheck against %s stderr = %q, want it to carry %q", tc.name, stderr, tc.wantStderr)
			}
		})
	}
}

func TestCheckHealthFailsWhenNothingListens(t *testing.T) {
	ln := listenLoopback(t)
	addr := ln.Addr().String()
	ln.Close()
	if code, stderr := runHealthcheck(t, addr); code != 1 || stderr == "" {
		t.Errorf("healthcheck against a closed port = %d (stderr %q), want 1 with a reason", code, stderr)
	}
}

// healthz 가 답하지 않으면 healthcheck 는 시한(여기서는 부르는 쪽 ctx)에 1 로 끝난다.
func TestCheckHealthFailsWhenHealthzHangs(t *testing.T) {
	release := make(chan struct{})
	addr := fakeHealthz(t, func(_ http.ResponseWriter, r *http.Request) {
		select {
		case <-release:
		case <-r.Context().Done():
		}
	})
	t.Cleanup(func() { close(release) }) // 서버 닫기보다 먼저 돈다(Cleanup 은 거꾸로 돈다)
	ctx, cancel := context.WithTimeout(t.Context(), 200*time.Millisecond)
	defer cancel()
	start := time.Now()
	stderr := newOutputLog()
	code := run(ctx, site{healthAddr: addr}, []string{"healthcheck"}, getenvFrom(nil), io.Discard, stderr)
	if code != 1 {
		t.Errorf("healthcheck against a hanging healthz = %d, want 1", code)
	}
	if elapsed := time.Since(start); elapsed > 3*time.Second {
		t.Errorf("healthcheck against a hanging healthz returned after %v, want soon after its context ended", elapsed)
	}
}
