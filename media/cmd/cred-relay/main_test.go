package main

import (
	"net"
	"syscall"
	"testing"
	"time"
)

// 사용법 오류는 종료 2 이고 아무것도 시작하지 않는다.
func TestRunRejectsUsage(t *testing.T) {
	for _, tc := range []struct {
		name string
		args []string
	}{
		{name: "모르는_하위_명령", args: []string{"run"}},
		{name: "serve_에_인자", args: []string{"serve", "extra"}},
		{name: "healthcheck_에_인자", args: []string{"healthcheck", "extra"}},
		{name: "isolation-check_에_인자", args: []string{"isolation-check", "extra"}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s := site{listen: func(string, string) (net.Listener, error) {
				t.Error("리스너를 열려 했다")
				return nil, syscall.EINVAL
			}}
			var stdout, stderr lockedBuffer
			if code := run(t.Context(), s, tc.args, getenvFrom(nil), &stdout, &stderr); code != 2 {
				t.Errorf("run(%q) = %d, want 2", tc.args, code)
			}
			if stderr.String() == "" {
				t.Errorf("run(%q) 가 stderr 에 사용법을 쓰지 않았다", tc.args)
			}
			if stdout.String() != "" {
				t.Errorf("run(%q) 가 stdout 에 썼다", tc.args)
			}
		})
	}
}

// 하위 명령 이름 serve 는 인자 없음과 같다(compose 는 인자 없이 띄운다).
func TestRunServeByName(t *testing.T) {
	var stdout, stderr lockedBuffer
	if code := run(t.Context(), site{}, []string{"serve"}, getenvFrom(nil), &stdout, &stderr); code != 1 {
		t.Errorf("run(serve) 설정 없음 = %d, want 1", code)
	}
	if got := events(t, stdout.String(), "cred_relay_failed"); len(got) != 1 || got[0]["reason"] != "config" {
		t.Errorf("cred_relay_failed = %v, want reason=config", got)
	}
}

// 운영 조립은 고정 주소만 쓴다 — IMDS 는 IPv4 링크 로컬, 창구는 compose 렌더의 CRED_RELAY_LISTEN 과 같은 자리,
// isolation-check 의 기다림 상한은 2초다(설계 2.5).
func TestDeployedSite(t *testing.T) {
	s := deployedSite()
	if s.imdsEndpoint != "http://169.254.169.254" {
		t.Errorf("imdsEndpoint = %q", s.imdsEndpoint)
	}
	if s.relayURL != "http://169.254.170.2:9911/v1/credentials" {
		t.Errorf("relayURL = %q", s.relayURL)
	}
	if s.isolationWait != 2*time.Second {
		t.Errorf("isolationWait = %v, want 2s", s.isolationWait)
	}
	if s.listen == nil {
		t.Error("listen 이 nil 이다")
	}
}
