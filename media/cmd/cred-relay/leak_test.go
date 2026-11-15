package main

import (
	"net"
	"net/http"
	"testing"
)

// 조립 경로(run → serve → credrelay → http.Server)의 stdout · stderr 에 비밀이 없다(c2 보안 검수의 c3 몫 · P10).
// 원천 실패(IMDS 오류 본문 · 경로가 카나리아) · 성공 · 거부 둘 · 404 · 405 · 서버 자신의 에러(Accept 실패 원문이
// 카나리아)를 모두 지난다. IMDS 실패는 캐시가 없는 첫 기동에서만 원천까지 가므로 기동을 둘로 나눈다. serve 는
// 자식 프로세스로 띄워 프로세스의 stdout · stderr 를 본다(c3 cx) — 기본 log · os.Stderr 로 새는 쓰기도 잡는다.
func TestNoSecretOnAssemblyPath(t *testing.T) {
	var stdout, stderr lockedBuffer
	imds := newFakeIMDS(t)

	imds.setFailing(true)
	serveProcess(t, imds.srv.URL, &stdout, &stderr, func(addr string) {
		if code, _ := getCreds(t, addr, canaryIndexerToken); code != http.StatusServiceUnavailable {
			t.Errorf("원천 실패 = %d, want 503", code)
		}
	})

	imds.setFailing(false)
	serveProcess(t, imds.srv.URL, &stdout, &stderr, func(addr string) {
		if code, _ := getCreds(t, addr, canaryJanitorToken); code != http.StatusOK {
			t.Errorf("토큰 맞음 = %d, want 200", code)
		}
		if code, _ := getCreds(t, addr, canaryWrongToken); code != http.StatusUnauthorized {
			t.Errorf("틀린 토큰 = %d, want 401", code)
		}
		for _, p := range []struct{ method, path string }{{http.MethodGet, "/nope"}, {http.MethodPost, "/v1/credentials"}} {
			req, _ := http.NewRequest(p.method, "http://"+addr+p.path, nil)
			req.Header.Set("Authorization", canaryIndexerToken)
			resp, err := testClient.Do(req)
			if err != nil {
				t.Fatal(err)
			}
			_ = resp.Body.Close()
		}
	})

	logs := stdout.String()
	for _, want := range []string{"cred_relay_started", "cred_source_failed", "cred_unavailable", "cred_served", "cred_denied", "cred_relay_server_error", "cred_relay_stopped"} {
		if len(events(t, logs, want)) == 0 {
			t.Errorf("갈래 %s 가 돌지 않았다 — 누설 시험이 그 갈래를 덮지 못한다", want)
		}
	}
	if got := events(t, logs, "cred_relay_server_error"); len(got) == 0 || got[0]["kind"] != "accept" {
		t.Errorf("cred_relay_server_error = %v, want kind=accept", got)
	}
	assertNoCanary(t, "stdout", logs)
	assertNoCanary(t, "stderr", stderr.String())
	if stderr.String() != "" {
		t.Errorf("프로세스 stderr 에 %d바이트를 썼다 — 서버 에러가 기본 log 로 샜다", len(stderr.String()))
	}
}

// flakyListener 는 첫 Accept 에서 일시 오류(원문에 카나리아)를 낸다 — http.Server 가 그 원문을 ErrorLog 에 쓴다.
type flakyListener struct {
	net.Listener
	failed bool
}

func (l *flakyListener) Accept() (net.Conn, error) {
	if !l.failed {
		l.failed = true
		return nil, temporaryError{}
	}
	return l.Listener.Accept()
}

type temporaryError struct{}

func (temporaryError) Error() string   { return "accept " + canaryAcceptError }
func (temporaryError) Timeout() bool   { return false }
func (temporaryError) Temporary() bool { return true }

var _ net.Error = temporaryError{}

// 서버 에러 기록기는 원문을 버리고 닫힌 종류만 남긴다 — 처리기 패닉의 값 · 스택에 무엇이 있든 싣지 않는다.
func TestServerErrorLogKeepsKindOnly(t *testing.T) {
	for _, tc := range []struct {
		msg, want string
	}{
		{msg: "http: panic serving 10.0.0.1:5: " + canarySecretKey + "\ngoroutine 1 [running]:\n", want: "panic"},
		{msg: "http: Accept error: " + canaryAcceptError + "; retrying in 5ms", want: "accept"},
		{msg: "http: superfluous response.WriteHeader call " + canaryIMDSBody, want: "other"},
	} {
		var stdout lockedBuffer
		srv := newServer("127.0.0.1:0", http.NotFoundHandler(), newLogger(&stdout))
		srv.ErrorLog.Print(tc.msg)
		got := events(t, stdout.String(), "cred_relay_server_error")
		if len(got) != 1 || got[0]["kind"] != tc.want {
			t.Errorf("ErrorLog(%.20q…) = %v, want 한 줄 kind=%s", tc.msg, got, tc.want)
		}
		assertNoCanary(t, "서버 에러 줄", stdout.String())
	}
}
