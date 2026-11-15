package credrelay

import (
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// 본문이 있는 응답은 Content-Type 이 정확히 application/json 이고(매개변수 없음 — 계획 E13), 오류 본문은 고정
// 문구다. 인증은 원천 호출보다 먼저다 — 401 사례에서 원천 호출은 0 이다.
func TestErrorResponsesAreFixedJSON(t *testing.T) {
	for _, c := range []struct {
		name, method, path, auth string
		wantStatus               int
		wantBody                 string
	}{
		{name: "missing_token", method: http.MethodGet, path: CredentialsPath, wantStatus: http.StatusUnauthorized, wantBody: bodyUnauthorized},
		{name: "wrong_token", method: http.MethodGet, path: CredentialsPath, auth: "wrong", wantStatus: http.StatusUnauthorized, wantBody: bodyUnauthorized},
		{name: "unknown_path", method: http.MethodGet, path: "/latest/meta-data/", auth: canaryIndexerToken, wantStatus: http.StatusNotFound, wantBody: bodyNotFound},
		{name: "post_credentials", method: http.MethodPost, path: CredentialsPath, auth: canaryIndexerToken, wantStatus: http.StatusMethodNotAllowed, wantBody: bodyMethodNotAllowed},
		{name: "post_healthz", method: http.MethodPost, path: HealthPath, wantStatus: http.StatusMethodNotAllowed, wantBody: bodyMethodNotAllowed},
	} {
		t.Run(c.name, func(t *testing.T) {
			r := newRelay(t, 6*time.Hour)
			req := httptest.NewRequest(c.method, c.path, nil)
			if c.auth != "" {
				req.Header.Set("Authorization", c.auth)
			}
			rec := httptest.NewRecorder()
			r.h.ServeHTTP(rec, req)

			if rec.Code != c.wantStatus || rec.Body.String() != c.wantBody {
				t.Errorf("%s %s: (%d, %q), want (%d, %q)", c.method, c.path, rec.Code, rec.Body.String(), c.wantStatus, c.wantBody)
			}
			if got := rec.Header().Values("Content-Type"); len(got) != 1 || got[0] != "application/json" {
				t.Errorf("%s %s: Content-Type %q, want 정확히 application/json 하나", c.method, c.path, got)
			}
			if n := len(r.src.callTimes()); n != 0 {
				t.Errorf("%s %s: 원천 호출 %d번, want 0", c.method, c.path, n)
			}
		})
	}
}

// 200 은 Content-Type 정확히 application/json · Cache-Control no-store 다.
func TestServedHeaders(t *testing.T) {
	r := newRelay(t, 6*time.Hour)
	rec := r.get(canaryIndexerToken)
	mustServe(t, rec, "200")
	if got := rec.Header().Get("Content-Type"); got != "application/json" {
		t.Errorf("200 Content-Type %q, want application/json", got)
	}
	if got := rec.Header().Get("Cache-Control"); got != "no-store" {
		t.Errorf("200 Cache-Control %q, want no-store", got)
	}
}

// 원천이 처음부터 실패하면 503 고정 본문이고, 저하 줄은 내지 않는다.
func TestUnavailableWithoutLastValid(t *testing.T) {
	r := newRelay(t, 6*time.Hour)
	r.imds.update(func(f *fakeIMDS) { f.credsStatus = http.StatusNotFound })
	rec := r.get(canaryIndexerToken)
	if rec.Code != http.StatusServiceUnavailable || rec.Body.String() != bodyUnavailable {
		t.Errorf("원천 실패 · L 없음: (%d, %q), want (503, %q)", rec.Code, rec.Body.String(), bodyUnavailable)
	}
	if got := rec.Header().Get("Content-Type"); got != "application/json" {
		t.Errorf("503 Content-Type %q, want application/json", got)
	}
	if got := r.logs.events(t, "cred_unavailable"); len(got) != 1 || got[0]["caller"] != "indexer" {
		t.Errorf("cred_unavailable = %v, want caller=indexer 한 줄", got)
	}
	// 저하(마지막 유효분으로 버팀)는 L 이 있을 때만이다 — 줄 것이 없는 실패는 저하가 아니다.
	if got := r.logs.events(t, "cred_source_degraded"); len(got) != 0 {
		t.Errorf("L 없는 원천 실패에 cred_source_degraded = %v, want 없음", got)
	}
}

// /healthz 는 인증 없이 204 · 본문 0 · Content-Type 없음 · 로그 0 이다.
func TestHealthz(t *testing.T) {
	r := newRelay(t, 6*time.Hour)
	rec := httptest.NewRecorder()
	r.h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, HealthPath, nil))

	if rec.Code != http.StatusNoContent || rec.Body.Len() != 0 {
		t.Errorf("GET /healthz: (%d, 본문 %d바이트), want (204, 0)", rec.Code, rec.Body.Len())
	}
	if got := rec.Header().Values("Content-Type"); len(got) != 0 {
		t.Errorf("GET /healthz: Content-Type %q, want 없음", got)
	}
	if got := r.logs.String(); got != "" {
		t.Errorf("GET /healthz 가 로그를 냈다: %q", got)
	}
}

// 거부 줄은 10초에 1줄이고 다음 줄이 그사이 억제된 수를 싣는다(P17). 1초에 1,000번 거부해도 1줄이다.
func TestDeniedLogIsRateLimited(t *testing.T) {
	r := newRelay(t, 6*time.Hour)
	for i := 0; i < 1000; i++ {
		if rec := r.get("wrong"); rec.Code != http.StatusUnauthorized {
			t.Fatalf("틀린 토큰: 상태 %d, want 401", rec.Code)
		}
		r.clock.advance(time.Millisecond)
	}
	got := r.logs.events(t, "cred_denied")
	if len(got) != 1 || got[0]["reason"] != "mismatch" || got[0]["count"] != float64(1) {
		t.Fatalf("1초에 1,000번 거부: cred_denied = %v, want reason=mismatch count=1 한 줄", got)
	}

	r.clock.advance(denyLogInterval)
	r.get("")
	got = r.logs.events(t, "cred_denied")
	if len(got) != 2 || got[1]["reason"] != "missing" || got[1]["count"] != float64(1000) {
		t.Errorf("10초 뒤 거부: cred_denied 두 번째 줄 = %v, want reason=missing count=1000(억제 999 + 이번 1)", got)
	}
}

// 서버 상한(머리 1초 · 요청 3초 · 쓰기 3초 · 머리 8 KiB)과 keep-alive 끔(응답이 연결을 닫음).
func TestNewServerLimits(t *testing.T) {
	srv := NewServer("127.0.0.1:0", http.NotFoundHandler())
	if srv.ReadHeaderTimeout != readHeaderTimeout || srv.ReadTimeout != readTimeout || srv.WriteTimeout != writeTimeout || srv.MaxHeaderBytes != maxHeaderBytes {
		t.Errorf("NewServer 상한 = (%v, %v, %v, %d), want (%v, %v, %v, %d)", srv.ReadHeaderTimeout, srv.ReadTimeout, srv.WriteTimeout, srv.MaxHeaderBytes, readHeaderTimeout, readTimeout, writeTimeout, maxHeaderBytes)
	}
	base := startServer(t, NewServer("", newRelay(t, 6*time.Hour).h))
	resp, err := http.Get(base + HealthPath)
	if err != nil {
		t.Fatalf("GET /healthz 실패: %v", err)
	}
	resp.Body.Close()
	if !resp.Close {
		t.Error("응답이 연결을 닫지 않는다 — keep-alive 를 꺼야 한다")
	}
}

// 본문을 선언하고 보내지 않는 연결은 읽기 상한 안에 닫힌다(c2 보안 M). 처리기가 204 를 다 쓴 뒤에도 서버는
// 읽지 않은 본문을 마저 읽으려 기다리는데, 읽기 상한이 없으면 인증 없는 이웃이 연결 수만큼 고루틴 · fd 를
// 끝없이 붙들 수 있다. 쓰기 상한 · keep-alive 끔으로는 닫히지 않는다.
func TestNewServerClosesConnWithUnsentBody(t *testing.T) {
	base := startServer(t, NewServer("", newRelay(t, 6*time.Hour).h))
	conn, err := net.Dial("tcp", strings.TrimPrefix(base, "http://"))
	if err != nil {
		t.Fatalf("연결 실패: %v", err)
	}
	defer conn.Close()

	start := time.Now()
	if _, err := fmt.Fprintf(conn, "GET %s HTTP/1.1\r\nHost: relay\r\nContent-Length: 200000\r\n\r\n", HealthPath); err != nil {
		t.Fatalf("요청 머리 쓰기 실패: %v", err)
	}
	if err := conn.SetReadDeadline(start.Add(readTimeout + 2*time.Second)); err != nil {
		t.Fatalf("읽기 마감 설정 실패: %v", err)
	}
	if _, err := io.Copy(io.Discard, conn); err != nil {
		t.Fatalf("본문을 보내지 않는 연결이 %v 안에 닫히지 않았다: %v", time.Since(start).Round(time.Millisecond), err)
	}
}

// IMDS 가 머리는 바로 · 본문은 4초 늦게 보내도(계획 E11) 중계는 3초 안에 끝까지 답한다(P30). L 이 있으면
// 200(L) · 없으면 503. 원천 호출 상한(2초)이 없으면 원천이 4초 뒤에 끝나 쓰기 상한(3초)을 넘겨 연결이 끊긴다.
func TestSlowSourceStillAnswersWithinWriteTimeout(t *testing.T) {
	const slow = 4 * time.Second
	t.Run("with_last_valid", func(t *testing.T) {
		r := newRelay(t, 6*time.Hour)
		mustServe(t, r.get(canaryIndexerToken), "첫 요청")
		r.imds.update(func(f *fakeIMDS) { f.bodyDelay = slow })
		r.clock.advance(periodicReread)
		assertAnswersInTime(t, startServer(t, NewServer("", r.h)), http.StatusOK)
	})
	t.Run("without_last_valid", func(t *testing.T) {
		r := newRelay(t, 6*time.Hour)
		r.imds.update(func(f *fakeIMDS) { f.bodyDelay = slow })
		assertAnswersInTime(t, startServer(t, NewServer("", r.h)), http.StatusServiceUnavailable)
	})
}

func assertAnswersInTime(t *testing.T, base string, want int) {
	t.Helper()
	req, err := http.NewRequestWithContext(t.Context(), http.MethodGet, base+CredentialsPath, nil)
	if err != nil {
		t.Fatalf("요청 만들기 실패: %v", err)
	}
	req.Header.Set("Authorization", canaryIndexerToken)
	start := time.Now()
	resp, err := (&http.Client{Timeout: 10 * time.Second}).Do(req)
	if err != nil {
		t.Fatalf("느린 원천: 연결이 끊겼다(%v 뒤): %v", time.Since(start), err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	elapsed := time.Since(start)
	if err != nil {
		t.Fatalf("느린 원천: 본문을 끝까지 받지 못했다(%v 뒤): %v", elapsed, err)
	}
	if resp.StatusCode != want || len(body) == 0 || elapsed >= writeTimeout {
		t.Errorf("느린 원천: (%d, 본문 %d바이트, %v), want (%d, 본문 있음, < %v)", resp.StatusCode, len(body), elapsed, want, writeTimeout)
	}
}

// startServer 는 srv 를 127.0.0.1 의 빈 포트에서 돌리고 기본 URL 을 돌려준다. 시험이 끝나면 닫는다.
func startServer(t *testing.T, srv *http.Server) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen 실패: %v", err)
	}
	go func() { _ = srv.Serve(ln) }()
	t.Cleanup(func() { _ = srv.Close() })
	return "http://" + ln.Addr().String()
}
