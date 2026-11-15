package main

import (
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// silentIMDS 는 연결은 받되 아무것도 답하지 않는 리스너다 — 홉 1 로 토큰 PUT 응답이 버려지는 칸의 모의다.
func silentIMDS(t *testing.T) string {
	t.Helper()
	ln := localListener(t)
	var mu sync.Mutex
	var conns []net.Conn
	t.Cleanup(func() {
		mu.Lock()
		defer mu.Unlock()
		for _, c := range conns {
			_ = c.Close()
		}
	})
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			mu.Lock()
			conns = append(conns, c)
			mu.Unlock()
		}
	}()
	return "http://" + ln.Addr().String()
}

// answeringIMDS 는 IMDSv2 토큰 PUT(TTL 머리 필수)에 카나리아 토큰을 내는 서버다. 그 밖 요청은 400 이다.
func answeringIMDS(t *testing.T) string {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPut || r.URL.Path != "/latest/api/token" || r.Header.Get("X-aws-ec2-metadata-token-ttl-seconds") == "" {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		_, _ = w.Write([]byte(canaryIMDSToken))
	}))
	t.Cleanup(srv.Close)
	return srv.URL
}

// statusIMDS 는 토큰 PUT 에 status 와 카나리아 본문을 내는 서버다(토큰이 아닌 답 — 판정 못 함).
func statusIMDS(t *testing.T, status int) string {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, canaryIMDSBody, status)
	}))
	t.Cleanup(srv.Close)
	return srv.URL
}

// refusedURL 은 듣는 쪽이 없는 루프백 주소다(연결 거부).
func refusedURL(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := ln.Addr().String()
	_ = ln.Close()
	return "http://" + addr
}

// relayAnswering 은 중계 꼴 서버다. Authorization 이 없으면 401, 있으면 200 과 카나리아 자격이다 — isolation-check
// 가 토큰 없이 묻는지(200 이면 묻지 않은 것)와 200 본문을 출력에 싣지 않는지를 함께 본다.
func relayAnswering(t *testing.T, status int) string {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.URL.Path != "/v1/credentials" {
			http.NotFound(w, r)
			return
		}
		if r.Header.Get("Authorization") == "" && status == http.StatusUnauthorized {
			http.Error(w, `{"code":"Unauthorized"}`, http.StatusUnauthorized)
			return
		}
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(map[string]string{"AccessKeyId": canaryAccessKey, "SecretAccessKey": canarySecretKey, "Token": canarySessionToken})
	}))
	t.Cleanup(srv.Close)
	return srv.URL + "/v1/credentials"
}

// isolation-check 판정 세 갈래(IMDS 토큰 차단 · 도달 · 판정 못 함) × 중계(401 · 200 · 연결 거부)의 한 줄과 종료
// 코드다. 하나라도 「격리 안 됨」 이면 1, 그렇지 않고 판정 못 한 것이 있으면 2, 둘 다 기대대로면 0 이다.
func TestIsolationCheck(t *testing.T) {
	imds := map[string]func(t *testing.T) string{
		"blocked":   silentIMDS,
		"reachable": answeringIMDS,
		"error":     refusedURL,
	}
	relay := map[string]func(t *testing.T) string{
		"401":   func(t *testing.T) string { return relayAnswering(t, http.StatusUnauthorized) },
		"200":   func(t *testing.T) string { return relayAnswering(t, http.StatusOK) },
		"error": func(t *testing.T) string { return refusedURL(t) + "/v1/credentials" },
	}
	for _, tc := range []struct {
		imds, relay string
		want        int
	}{
		{imds: "blocked", relay: "401", want: 0},
		{imds: "blocked", relay: "200", want: 1},
		{imds: "blocked", relay: "error", want: 2},
		{imds: "reachable", relay: "401", want: 1},
		{imds: "reachable", relay: "200", want: 1},
		{imds: "reachable", relay: "error", want: 1},
		{imds: "error", relay: "401", want: 2},
		{imds: "error", relay: "200", want: 1},
		{imds: "error", relay: "error", want: 2},
	} {
		t.Run(tc.imds+"_"+tc.relay, func(t *testing.T) {
			s := site{imdsEndpoint: imds[tc.imds](t), relayURL: relay[tc.relay](t), isolationWait: 300 * time.Millisecond}
			var stdout, stderr lockedBuffer
			code := run(t.Context(), s, []string{"isolation-check"}, getenvFrom(nil), &stdout, &stderr)
			want := "CRED ISOLATION imds_token=" + tc.imds + " relay=" + tc.relay + "\n"
			if stdout.String() != want {
				t.Errorf("출력 = %q, want %q", stdout.String(), want)
			}
			if code != tc.want {
				t.Errorf("종료 = %d, want %d", code, tc.want)
			}
			assertNoCanary(t, "stdout", stdout.String())
			assertNoCanary(t, "stderr", stderr.String())
		})
	}
}

// IMDS 가 토큰이 아닌 답(403 · 빈 200)을 내면 판정 못 함(error)이다 — 칸이 막혔다고도 뚫렸다고도 하지 않는다.
func TestIsolationCheckNonTokenAnswerIsError(t *testing.T) {
	empty := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	t.Cleanup(empty.Close)
	for name, endpoint := range map[string]string{"403": statusIMDS(t, http.StatusForbidden), "빈_200": empty.URL} {
		t.Run(name, func(t *testing.T) {
			s := site{imdsEndpoint: endpoint, relayURL: relayAnswering(t, http.StatusUnauthorized), isolationWait: 300 * time.Millisecond}
			var stdout, stderr lockedBuffer
			code := run(t.Context(), s, []string{"isolation-check"}, getenvFrom(nil), &stdout, &stderr)
			if want := "CRED ISOLATION imds_token=error relay=401\n"; stdout.String() != want {
				t.Errorf("출력 = %q, want %q", stdout.String(), want)
			}
			if code != 2 {
				t.Errorf("종료 = %d, want 2", code)
			}
			assertNoCanary(t, "stdout", stdout.String())
		})
	}
}

// 차단 판정은 기다림 상한(isolationWait) 근처에서 끝난다 — 응답 없는 IMDS 에 매달리지 않는다.
func TestIsolationCheckBlockedHonoursWait(t *testing.T) {
	const wait = 300 * time.Millisecond
	s := site{imdsEndpoint: silentIMDS(t), relayURL: relayAnswering(t, http.StatusUnauthorized), isolationWait: wait}
	var stdout, stderr lockedBuffer
	start := time.Now()
	run(t.Context(), s, []string{"isolation-check"}, getenvFrom(nil), &stdout, &stderr)
	if took := time.Since(start); took < wait || took > wait+2*time.Second {
		t.Errorf("isolation-check 가 %v 걸렸다, want %v 이상 %v 이하", took, wait, wait+2*time.Second)
	}
	if !strings.Contains(stdout.String(), "imds_token=blocked") {
		t.Errorf("출력 = %q, want imds_token=blocked", stdout.String())
	}
}
