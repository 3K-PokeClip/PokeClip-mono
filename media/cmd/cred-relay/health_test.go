package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
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

// healthcheck 는 serve 의 /healthz 에 0 이다(조립 경로). serve 는 링크 로컬 주소만 받으므로 미리 연 루프백
// 리스너로 듣게 하고, healthcheck 의 CRED_RELAY_LISTEN 은 그 리스너 주소다.
func TestHealthcheckAgainstServe(t *testing.T) {
	ln := localListener(t)
	var stdout, stderr lockedBuffer
	ctx, cancel := context.WithCancel(t.Context())
	done := startServe(t, ctx, testSite(ln, newFakeIMDS(t).srv.URL), relayEnv("169.254.170.2:9911", defaultCallerDir(t)), &stdout, &stderr)
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
