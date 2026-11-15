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

// relayAnswering 은 중계 꼴 서버다. status 가 401 이면 제대로 막힌 창구다 — Authorization 이 없으면 401, 있으면
// 200 과 카나리아 자격이라, isolation-check 가 무엇이든 실어 물으면 relay=200 으로 드러난다. 그 밖 status 는
// 머리와 무관하게 그 상태와 카나리아 자격이다(뚫린 창구). 어느 쪽이든 200 본문을 출력에 싣지 않는지도 본다.
func relayAnswering(t *testing.T, status int) string {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.URL.Path != "/v1/credentials" {
			http.NotFound(w, r)
			return
		}
		answer := status
		if status == http.StatusUnauthorized && r.Header.Get("Authorization") != "" {
			answer = http.StatusOK
		}
		if answer == http.StatusUnauthorized {
			http.Error(w, `{"code":"Unauthorized"}`, http.StatusUnauthorized)
			return
		}
		w.WriteHeader(answer)
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
// 403 머리 뒤 본문이 기다림 상한을 넘겨도 같다(c3 cx) — 이미 받은 상태로 판정하고, 본문 지연을 차단으로 읽지
// 않는다.
func TestIsolationCheckNonTokenAnswerIsError(t *testing.T) {
	empty := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	t.Cleanup(empty.Close)
	stalled := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusForbidden)
		w.(http.Flusher).Flush()
		<-r.Context().Done() // 본문을 끝내지 않는다 — 묻는 쪽이 끊을 때까지
	}))
	t.Cleanup(stalled.Close)
	for name, endpoint := range map[string]string{"403": statusIMDS(t, http.StatusForbidden), "빈_200": empty.URL, "403_본문_지연": stalled.URL} {
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

// 리다이렉트는 따라가지 않고 처음 대상의 답으로 판정한다(c3 cx · 보안). IMDS 토큰 PUT 이 307 로 토큰을 내는 곳을
// 가리켜도 토큰이 아닌 답(error)이고, 창구 자리의 302 는 401 이 아니므로 「격리 안 됨」 이다 — 따라가면 각각
// reachable · 따라간 곳의 401(거짓 「격리됨」)로 보인다.
func TestIsolationCheckDoesNotFollowRedirects(t *testing.T) {
	redirecting := func(from, to string, status int, final http.HandlerFunc) string {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path == from {
				http.Redirect(w, r, to, status)
				return
			}
			final(w, r)
		}))
		t.Cleanup(srv.Close)
		return srv.URL
	}
	token := func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte(canaryIMDSToken)) }
	denied := func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, `{"code":"Unauthorized"}`, http.StatusUnauthorized)
	}
	for _, tc := range []struct {
		name, imds, relay, want string
		code                    int
	}{
		{
			name:  "imds_307",
			imds:  redirecting("/latest/api/token", "/token-elsewhere", http.StatusTemporaryRedirect, token),
			relay: relayAnswering(t, http.StatusUnauthorized),
			want:  "CRED ISOLATION imds_token=error relay=401\n",
			code:  2,
		},
		{
			name:  "relay_302",
			imds:  silentIMDS(t),
			relay: redirecting("/v1/credentials", "/denied-elsewhere", http.StatusFound, denied) + "/v1/credentials",
			want:  "CRED ISOLATION imds_token=blocked relay=302\n",
			code:  1,
		},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s := site{imdsEndpoint: tc.imds, relayURL: tc.relay, isolationWait: 300 * time.Millisecond}
			var stdout, stderr lockedBuffer
			code := run(t.Context(), s, []string{"isolation-check"}, getenvFrom(nil), &stdout, &stderr)
			if stdout.String() != tc.want {
				t.Errorf("출력 = %q, want %q", stdout.String(), tc.want)
			}
			if code != tc.code {
				t.Errorf("종료 = %d, want %d", code, tc.code)
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
