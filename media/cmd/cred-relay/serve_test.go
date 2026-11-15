package main

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"strings"
	"syscall"
	"testing"
	"time"
)

// serve 를 고루틴으로 돌리고 끝난 종료 코드를 받는 채널을 돌려준다.
func startServe(t *testing.T, ctx context.Context, s site, env map[string]string, stdout, stderr *lockedBuffer) <-chan int {
	t.Helper()
	done := make(chan int, 1)
	go func() { done <- run(ctx, s, nil, getenvFrom(env), stdout, stderr) }()
	return done
}

func waitExit(t *testing.T, done <-chan int) int {
	t.Helper()
	select {
	case code := <-done:
		return code
	case <-time.After(10 * time.Second):
		t.Fatal("serve 가 10초 안에 끝나지 않았다")
		return -1
	}
}

// 설정이 꼴 밖이면 리스너를 열지 않고 cred_relay_failed reason=config 로 종료 1 이다. 줄에는 변수 이름만 싣는다.
// 듣는 주소는 IPv4 링크 로컬(169.254.0.0/16)만 받는다 — 모든 주소 · 루프백 · 사설 주소 · IPv6 는 거부다.
func TestServeRejectsConfig(t *testing.T) {
	dir := defaultCallerDir(t)
	for _, tc := range []struct {
		name    string
		mutate  func(env map[string]string)
		wantEnv string
	}{
		{name: "listen_없음", mutate: func(e map[string]string) { delete(e, envListen) }, wantEnv: envListen},
		{name: "listen_꼴_밖", mutate: func(e map[string]string) { e[envListen] = "relay.local:9911" }, wantEnv: envListen},
		{name: "listen_포트_0", mutate: func(e map[string]string) { e[envListen] = "169.254.170.2:0" }, wantEnv: envListen},
		{name: "listen_모든_주소", mutate: func(e map[string]string) { e[envListen] = "0.0.0.0:9911" }, wantEnv: envListen},
		{name: "listen_모든_주소_v6", mutate: func(e map[string]string) { e[envListen] = "[::]:9911" }, wantEnv: envListen},
		{name: "listen_루프백", mutate: func(e map[string]string) { e[envListen] = "127.0.0.1:9911" }, wantEnv: envListen},
		{name: "listen_사설_주소", mutate: func(e map[string]string) { e[envListen] = "10.0.1.5:9911" }, wantEnv: envListen},
		{name: "listen_v6_링크_로컬", mutate: func(e map[string]string) { e[envListen] = "[fe80::1]:9911" }, wantEnv: envListen},
		{name: "dir_없음", mutate: func(e map[string]string) { delete(e, envCallerDir) }, wantEnv: envCallerDir},
		{name: "dir_상대_경로", mutate: func(e map[string]string) { e[envCallerDir] = "run/secrets/cred-relay" }, wantEnv: envCallerDir},
		{name: "dir_정리_안_됨", mutate: func(e map[string]string) { e[envCallerDir] = "/run/secrets/../cred-relay" }, wantEnv: envCallerDir},
		{name: "callers_없음", mutate: func(e map[string]string) { delete(e, envCallers) }, wantEnv: envCallers},
		{name: "callers_빈_이름", mutate: func(e map[string]string) { e[envCallers] = "indexer," }, wantEnv: envCallers},
		{name: "callers_꼴_밖", mutate: func(e map[string]string) { e[envCallers] = "Indexer" }, wantEnv: envCallers},
		{name: "callers_겹침", mutate: func(e map[string]string) { e[envCallers] = "indexer,indexer" }, wantEnv: envCallers},
	} {
		t.Run(tc.name, func(t *testing.T) {
			env := relayEnv("169.254.170.2:9911", dir)
			tc.mutate(env)
			listened := false
			s := site{listen: func(string, string) (net.Listener, error) { listened = true; return nil, syscall.EINVAL }}
			var stdout, stderr lockedBuffer
			if code := run(t.Context(), s, nil, getenvFrom(env), &stdout, &stderr); code != 1 {
				t.Errorf("run = %d, want 1", code)
			}
			if listened {
				t.Error("설정 거부인데 리스너를 열려 했다")
			}
			got := events(t, stdout.String(), "cred_relay_failed")
			if len(got) != 1 || got[0]["reason"] != "config" || got[0]["env"] != tc.wantEnv {
				t.Errorf("cred_relay_failed = %v, want 한 줄 reason=config env=%s", got, tc.wantEnv)
			}
		})
	}
}

// 토큰 디렉터리 · 파일을 받을 수 없으면 cred_relay_failed reason=token_file 로 종료 1 이다.
func TestServeRejectsTokenFile(t *testing.T) {
	for _, tc := range []struct {
		name string
		dir  func(t *testing.T) string
	}{
		{name: "디렉터리_없음", dir: func(t *testing.T) string { return t.TempDir() + "/missing" }},
		{name: "janitor_파일_없음", dir: func(t *testing.T) string {
			return writeCallerDir(t, map[string]string{"indexer": canaryIndexerToken})
		}},
		{name: "두_줄", dir: func(t *testing.T) string {
			return writeCallerDir(t, map[string]string{"indexer": canaryIndexerToken + "\n" + canaryWrongToken, "janitor": canaryJanitorToken})
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var stdout, stderr lockedBuffer
			s := site{listen: func(string, string) (net.Listener, error) {
				t.Error("리스너를 열려 했다")
				return nil, syscall.EINVAL
			}}
			if code := run(t.Context(), s, nil, getenvFrom(relayEnv("169.254.170.2:9911", tc.dir(t))), &stdout, &stderr); code != 1 {
				t.Errorf("run = %d, want 1", code)
			}
			got := events(t, stdout.String(), "cred_relay_failed")
			if len(got) != 1 || got[0]["reason"] != "token_file" || got[0]["env"] != envCallerDir {
				t.Errorf("cred_relay_failed = %v, want 한 줄 reason=token_file env=%s", got, envCallerDir)
			}
			assertNoCanary(t, "stdout", stdout.String())
			assertNoCanary(t, "stderr", stderr.String())
		})
	}
}

// 리스너를 열지 못하면(주소 사용 중 · 주소 없음) cred_relay_failed reason=listen addr=<설정 주소> 로 종료 1 이다.
// 링크 로컬 주소는 시험 기계에 없으므로 운영 net.Listen 대신 실패하는 listen 을 준다.
func TestServeListenFailure(t *testing.T) {
	var asked string
	s := site{imdsEndpoint: "http://127.0.0.1:1", listen: func(_, address string) (net.Listener, error) {
		asked = address
		return nil, syscall.EADDRINUSE
	}}
	var stdout, stderr lockedBuffer
	code := run(t.Context(), s, nil, getenvFrom(relayEnv("169.254.170.2:9911", defaultCallerDir(t))), &stdout, &stderr)
	if code != 1 {
		t.Errorf("run = %d, want 1", code)
	}
	if asked != "169.254.170.2:9911" {
		t.Errorf("listen 주소 = %q, want 169.254.170.2:9911", asked)
	}
	got := events(t, stdout.String(), "cred_relay_failed")
	if len(got) != 1 || got[0]["reason"] != "listen" || got[0]["addr"] != "169.254.170.2:9911" {
		t.Errorf("cred_relay_failed = %v, want 한 줄 reason=listen addr=169.254.170.2:9911", got)
	}
	if n := len(events(t, stdout.String(), "cred_relay_started")); n != 0 {
		t.Errorf("cred_relay_started %d줄, want 0", n)
	}
}

// 조립 경로 전체 — 기동 줄 → 토큰 맞음 200 · 없음 401 → SIGTERM 이면 cred_relay_stopped 로 종료 0 이다.
func TestServeAnswersAndStopsOnTERM(t *testing.T) {
	imds := newFakeIMDS(t)
	ln := localListener(t)
	var stdout, stderr lockedBuffer
	done := startServe(t, context.Background(), testSite(ln, imds.srv.URL), relayEnv("169.254.170.2:9911", defaultCallerDir(t)), &stdout, &stderr)
	addr := ln.Addr().String()
	waitHealthy(t, addr)

	code, body := getCreds(t, addr, canaryIndexerToken)
	if code != http.StatusOK {
		t.Fatalf("토큰 맞음 = %d, want 200", code)
	}
	var creds map[string]string
	if err := json.Unmarshal([]byte(body), &creds); err != nil || creds["AccessKeyId"] != canaryAccessKey {
		t.Errorf("200 본문의 AccessKeyId 가 원천 자격이 아니다(err=%v)", err)
	}
	if code, _ := getCreds(t, addr, ""); code != http.StatusUnauthorized {
		t.Errorf("토큰 없음 = %d, want 401", code)
	}

	if err := syscall.Kill(syscall.Getpid(), syscall.SIGTERM); err != nil {
		t.Fatal(err)
	}
	if code := waitExit(t, done); code != 0 {
		t.Errorf("SIGTERM 뒤 종료 = %d, want 0", code)
	}
	logs := stdout.String()
	started := events(t, logs, "cred_relay_started")
	if len(started) != 1 || started[0]["addr"] != addr || started[0]["callers"] != "indexer,janitor" {
		t.Errorf("cred_relay_started = %v, want 한 줄 addr=%s callers=indexer,janitor", started, addr)
	}
	stopped := events(t, logs, "cred_relay_stopped")
	if len(stopped) != 1 || stopped[0]["graceful"] != true {
		t.Errorf("cred_relay_stopped = %v, want 한 줄 graceful=true", stopped)
	}
	if strings.Index(logs, "cred_relay_started") > strings.Index(logs, "cred_relay_stopped") {
		t.Error("cred_relay_stopped 가 cred_relay_started 보다 앞에 있다")
	}
	if stderr.String() != "" {
		t.Errorf("stderr 에 %d바이트를 썼다, want 0", len(stderr.String()))
	}
}

// 부르는 쪽 ctx 가 끝나도 같은 정상 종료다.
func TestServeStopsOnContextCancel(t *testing.T) {
	ln := localListener(t)
	ctx, cancel := context.WithCancel(context.Background())
	var stdout, stderr lockedBuffer
	done := startServe(t, ctx, testSite(ln, newFakeIMDS(t).srv.URL), relayEnv("169.254.170.2:9911", defaultCallerDir(t)), &stdout, &stderr)
	waitHealthy(t, ln.Addr().String())
	cancel()
	if code := waitExit(t, done); code != 0 {
		t.Errorf("ctx 취소 뒤 종료 = %d, want 0", code)
	}
	if n := len(events(t, stdout.String(), "cred_relay_stopped")); n != 1 {
		t.Errorf("cred_relay_stopped %d줄, want 1", n)
	}
}

// 서버가 스스로 멈추면(리스너가 닫힘) cred_relay_failed reason=listen 으로 종료 1 이다.
func TestServeFailsWhenListenerDies(t *testing.T) {
	ln := localListener(t)
	var stdout, stderr lockedBuffer
	done := startServe(t, context.Background(), testSite(ln, newFakeIMDS(t).srv.URL), relayEnv("169.254.170.2:9911", defaultCallerDir(t)), &stdout, &stderr)
	waitHealthy(t, ln.Addr().String())
	_ = ln.Close()
	if code := waitExit(t, done); code != 1 {
		t.Errorf("리스너가 닫힌 뒤 종료 = %d, want 1", code)
	}
	got := events(t, stdout.String(), "cred_relay_failed")
	if len(got) != 1 || got[0]["reason"] != "listen" {
		t.Errorf("cred_relay_failed = %v, want 한 줄 reason=listen", got)
	}
}
