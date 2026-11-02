package mtxstate

// 자격 · 전송 정책의 **동작** 시험(POK-373). 이 파일은 새 API(User · Pass · AuthScheme)를 쓰지 않는다 —
// 제품 코드만 기준 브랜치로 되돌린 트리에서도 컴파일되어, 구조가 빠진 코드에서 FAIL 하는지를 판별 신호로
// 보이게 하려는 것이다(음성 대조). 새 API 가 필요한 시험은 poller_basic_test.go 에 둔다.

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// text 는 담긴 기록 전부를 메시지 · 속성 값까지 한 문자열로 편다. 누설 시험이 「어디에도 없음」 을
// 보려면 속성 하나만 봐서는 부족하다.
func (c *logCapture) text() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	var b strings.Builder
	for _, r := range c.records {
		b.WriteString(r.Message)
		r.Attrs(func(a slog.Attr) bool {
			fmt.Fprintf(&b, " %s=%v", a.Key, a.Value.Any())
			return true
		})
		b.WriteString("\n")
	}
	return b.String()
}

// 401 은 일시 실패가 아니라 자격 고장이다 — 사람이 자격 설정을 고쳐야만 낫는다. 그래서 request_failed
// (WARN, 다음 주기가 고칠 수 있는 일)와 갈라 auth_rejected 를 ERROR 로 낸다. 본문은 진단에 싣지 않고,
// 직전 스냅샷은 다른 실패와 똑같이 유지한다.
func TestUnauthorizedIsAuthRejected(t *testing.T) {
	// 본문 카나리아 — 401 본문이 로그에 실리면 이 글자가 보인다.
	const bodyCanary = "body-canary-7f3a"
	var mu sync.Mutex
	reject := false
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		rj := reject
		mu.Unlock()
		if rj {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"status":"error","error":"authentication error ` + bodyCanary + `"}`))
			return
		}
		_, _ = w.Write([]byte(rawPublishing))
	}))
	t.Cleanup(srv.Close)

	p, logs := newPoller(t, srv.URL, time.Second)
	p.pollOnce(context.Background())
	kept := p.Latest(probeStream)

	mu.Lock()
	reject = true
	mu.Unlock()
	p.pollOnce(context.Background())

	if n := logs.countReason("mtxstate_poll_failed", "auth_rejected"); n != 1 {
		t.Errorf("mtxstate_poll_failed{reason=auth_rejected} = %d 건, want 1", n)
	}
	if n := logs.countReason("mtxstate_poll_failed", "request_failed"); n != 0 {
		t.Errorf("401 이 request_failed 로 %d 건 셌다 — 자격 고장이 일시 실패에 묻힌다", n)
	}
	if got := logs.levels("mtxstate_poll_failed"); len(got) != 1 || got[0] != slog.LevelError {
		t.Errorf("auth_rejected 등급 = %v, want [ERROR] 1건", got)
	}
	if strings.Contains(logs.text(), bodyCanary) {
		t.Error("401 응답 본문이 로그에 실렸다(카나리아 발견) — 본문은 싣지 않는다")
	}
	obs := p.Latest(probeStream)
	if !obs.Publishing || !obs.ObservedAt.Equal(kept.ObservedAt) {
		t.Errorf("401 이 직전 스냅샷을 건드렸다: ObservedAt %v → %v, Publishing=%v", kept.ObservedAt, obs.ObservedAt, obs.Publishing)
	}
}

// 자격을 설정하지 않으면 Authorization 헤더를 싣지 않는다(dev compose — MediaMTX 의 `user: any` 항목으로
// 붙는 지금 동작 그대로).
func TestPollerSendsNoAuthorizationWithoutUser(t *testing.T) {
	var mu sync.Mutex
	var headers []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		headers = append(headers, r.Header.Get("Authorization"))
		mu.Unlock()
		_, _ = w.Write([]byte(rawPublishing))
	}))
	t.Cleanup(srv.Close)

	p, _ := newPoller(t, srv.URL, time.Second)
	p.pollOnce(context.Background())
	p.pollOnce(context.Background())

	mu.Lock()
	defer mu.Unlock()
	if len(headers) != 2 {
		t.Fatalf("서버 도달 %d 회, want 2", len(headers))
	}
	for i, h := range headers {
		if h != "" {
			t.Errorf("요청 %d 에 Authorization 헤더가 실렸다(길이 %d) — 자격 미설정이면 비어야 한다", i, len(h))
		}
	}
}

// 리다이렉트를 따르지 않는다 — 따라가면 Go 는 같은 호스트에 Authorization 을 다시 싣는다. 3xx 는 200 이
// 아니므로 request_failed 다.
func TestRedirectIsNotFollowed(t *testing.T) {
	var mu sync.Mutex
	targetHits := 0
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		targetHits++
		mu.Unlock()
		_, _ = w.Write([]byte(rawPublishing))
	}))
	t.Cleanup(target.Close)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, target.URL+r.URL.RequestURI(), http.StatusFound)
	}))
	t.Cleanup(srv.Close)

	p, logs := newPoller(t, srv.URL, time.Second)
	p.pollOnce(context.Background())

	mu.Lock()
	hits := targetHits
	mu.Unlock()
	if hits != 0 {
		t.Errorf("리다이렉트 대상이 %d 회 요청을 받았다, want 0", hits)
	}
	if p.Latest(probeStream).EpochKnown {
		t.Error("리다이렉트를 따라가 관측이 성립했다")
	}
	if n := logs.countReason("mtxstate_poll_failed", "request_failed"); n != 1 {
		t.Errorf("mtxstate_poll_failed{reason=request_failed} = %d 건, want 1", n)
	}
}

// 전송은 http.DefaultTransport 의 복제본에서 프록시만 끈 것이다(화이트박스).
//
// 프록시를 끄는 이유: Basic 자격이 프록시를 거치지 않게 한다. 이것은 httptest 로 가를 수 없다 —
// ProxyFromEnvironment 는 첫 호출 값을 캐시하고 루프백 주소는 늘 직결이다. 그래서 필드를 직접 본다.
// 빈 Transport 를 쓰지 않는 이유: 다이얼 · TLS 악수 · 유휴 연결 상한을 통째로 잃는다. 기본값과 비교하는
// 필드는 그 상한들이다(지금 값은 10s · 90s · 100 · HTTP/2 시도 · 다이얼러 있음).
func TestPollerTransportIgnoresProxyAndKeepsDefaults(t *testing.T) {
	p, _ := newPoller(t, "http://192.0.2.1:9997", time.Second)

	tr, ok := p.client.Transport.(*http.Transport)
	if !ok {
		t.Fatalf("client.Transport = %T, want *http.Transport(DefaultTransport 의 복제본)", p.client.Transport)
	}
	if tr.Proxy != nil {
		t.Error("Transport.Proxy 가 설정돼 있다, want nil(환경 변수의 프록시를 타지 않는다)")
	}
	def := http.DefaultTransport.(*http.Transport)
	if tr.TLSHandshakeTimeout != def.TLSHandshakeTimeout {
		t.Errorf("TLSHandshakeTimeout = %v, want %v(DefaultTransport)", tr.TLSHandshakeTimeout, def.TLSHandshakeTimeout)
	}
	if tr.IdleConnTimeout != def.IdleConnTimeout {
		t.Errorf("IdleConnTimeout = %v, want %v(DefaultTransport)", tr.IdleConnTimeout, def.IdleConnTimeout)
	}
	if tr.MaxIdleConns != def.MaxIdleConns {
		t.Errorf("MaxIdleConns = %d, want %d(DefaultTransport)", tr.MaxIdleConns, def.MaxIdleConns)
	}
	if tr.ForceAttemptHTTP2 != def.ForceAttemptHTTP2 {
		t.Errorf("ForceAttemptHTTP2 = %v, want %v(DefaultTransport)", tr.ForceAttemptHTTP2, def.ForceAttemptHTTP2)
	}
	if tr.DialContext == nil {
		t.Error("DialContext 가 nil 이다 — 다이얼 상한이 사라졌다")
	}
	if tr == def {
		t.Error("DefaultTransport 를 그대로 쓴다 — 전역 값의 Proxy 를 바꾸면 프로세스 전체에 번진다")
	}
	if p.client.CheckRedirect == nil {
		t.Error("CheckRedirect 가 nil 이다, want 리다이렉트를 따르지 않는 정책")
	}
	if p.client.Timeout != 0 {
		t.Errorf("client.Timeout = %v, want 0(상한은 poll 마다 거는 컨텍스트 하나)", p.client.Timeout)
	}
}
