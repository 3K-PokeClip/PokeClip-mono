package mtxstate

// 자격(User · Pass) · AuthScheme 의 **새 API** 시험(POK-373). 동작 시험은 poller_auth_test.go 에 있다.

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// newAuthPoller 는 자격을 실은 폴러를 만든다.
func newAuthPoller(t *testing.T, url string) (*Poller, *logCapture) {
	t.Helper()
	logs := &logCapture{}
	p, err := NewPoller(Options{
		APIURL:       url,
		User:         testUser,
		Pass:         NewSecret(testPass),
		PollInterval: time.Second,
		BootWait:     200 * time.Millisecond,
		Log:          slog.New(logs),
	})
	if err != nil {
		t.Fatalf("NewPoller 실패: %v", err)
	}
	return p, logs
}

// 자격은 요청 **마다** 실린다 — 첫 요청에만 싣거나 한 번 조립한 요청을 다시 쓰면 다음 주기부터 401 이다.
// 비밀번호의 ':' 와 공백은 그대로 간다(Basic 은 첫 ':' 에서만 가른다).
func TestPollerSendsBasicAuthOnEveryRequest(t *testing.T) {
	const polls = 3
	var mu sync.Mutex
	var matched, total int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		user, pass, ok := r.BasicAuth()
		mu.Lock()
		total++
		if ok && user == testUser && pass == testPass {
			matched++
		}
		mu.Unlock()
		_, _ = w.Write([]byte(rawPublishing))
	}))
	t.Cleanup(srv.Close)

	p, logs := newAuthPoller(t, srv.URL)
	for range polls {
		p.pollOnce(context.Background())
	}

	mu.Lock()
	defer mu.Unlock()
	if total != polls || matched != polls {
		t.Errorf("요청 %d 회 중 자격이 맞은 것 %d 회, want %d/%d", total, matched, polls, polls)
	}
	if n := logs.count("mtxstate_poll_failed"); n != 0 {
		t.Errorf("자격이 맞는데 mtxstate_poll_failed 가 %d 건 떴다", n)
	}
}

// Options 와 그것을 담은 값은 fmt · slog(Text · JSON) · encoding/json 어디로 찍어도 비밀번호가 나오지
// 않는다 — 기동 로그 · 에러 문구에 통째로 넘겨도 새지 않게(ADR-018). 폴 실패 로그에도 없다.
func TestPollerOptionsPrintWithoutPassword(t *testing.T) {
	opt := Options{APIURL: "http://192.0.2.1:9997", User: testUser, Pass: NewSecret(testPass)}
	holder := struct{ Opt Options }{opt}
	hidden := struct{ opt Options }{opt}

	var outs []string
	for _, verb := range []string{"%v", "%+v", "%#v", "%s", "%q", "%x", "%d", "%p", "%w"} {
		outs = append(outs,
			fmt.Sprintf(verb, opt), fmt.Sprintf(verb, &opt),
			fmt.Sprintf(verb, holder), fmt.Sprintf(verb, hidden))
	}
	for _, newHandler := range []func(io.Writer) slog.Handler{
		func(w io.Writer) slog.Handler { return slog.NewTextHandler(w, nil) },
		func(w io.Writer) slog.Handler { return slog.NewJSONHandler(w, nil) },
	} {
		var buf bytes.Buffer
		slog.New(newHandler(&buf)).Info("probe", "opt", opt, "hidden", hidden)
		outs = append(outs, buf.String())
	}
	if b, err := json.Marshal(opt); err != nil {
		t.Errorf("json.Marshal(Options) 에러: %v", err)
	} else {
		outs = append(outs, string(b))
	}

	// 폴 실패 로그 — 401 과 일시 실패 둘 다 본다.
	for _, status := range []int{http.StatusUnauthorized, http.StatusServiceUnavailable} {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(status)
		}))
		p, logs := newAuthPoller(t, srv.URL)
		p.pollOnce(context.Background())
		srv.Close()
		outs = append(outs, logs.text())
	}

	// 원문만 보면 %x(16진) · Basic 헤더 값(base64)으로 찍힌 비밀번호를 놓친다.
	encodings := []string{
		testPass,
		hex.EncodeToString([]byte(testPass)),
		base64.StdEncoding.EncodeToString([]byte(testUser + ":" + testPass)),
	}
	for i, out := range outs {
		for j, enc := range encodings {
			if strings.Contains(out, enc) {
				t.Errorf("출력 %d 에 카나리아 발견(표기 %d, 출력 길이 %d)", i, j, len(out))
			}
		}
	}
}

// AuthScheme 은 기동 로그 · 폴 실패 로그의 auth 속성이다 — 자격을 실었는지(basic) 아닌지(none)만 말한다.
// 폴 실패 로그의 모든 줄에 같은 값이 실린다.
func TestAuthSchemeReportsBasicOrNone(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	t.Cleanup(srv.Close)

	basic, basicLogs := newAuthPoller(t, srv.URL)
	none, noneLogs := newPoller(t, srv.URL, time.Second)
	for _, c := range []struct {
		name string
		p    *Poller
		logs *logCapture
		want string
	}{
		{name: "자격 있음", p: basic, logs: basicLogs, want: "basic"},
		{name: "자격 없음", p: none, logs: noneLogs, want: "none"},
	} {
		if got := c.p.AuthScheme(); got != c.want {
			t.Errorf("%s: AuthScheme() = %q, want %q", c.name, got, c.want)
		}
		c.p.pollOnce(context.Background())
		if n := c.logs.countAttr("mtxstate_poll_failed", "auth", c.want); n != 1 {
			t.Errorf("%s: mtxstate_poll_failed{auth=%s} = %d 건, want 1", c.name, c.want, n)
		}
	}
}

// User 와 Pass 는 짝이다. 한쪽만 있으면 거부한다 — User 만이면 빈 비밀번호로 Basic 을 싣고, Pass 만이면
// 비밀번호가 조용히 버려진다. main 경로는 config 가 먼저 막지만, config 를 거치지 않는 조립(시험 · 후속
// 호출자)에 대한 회선 패키지의 보장이다(mtxctl NewClient 와 같은 자리).
func TestNewPollerRejectsUnpairedCredentials(t *testing.T) {
	for _, c := range []struct {
		name string
		opt  Options
	}{
		{name: "User 만", opt: Options{APIURL: "http://192.0.2.1:9997", User: testUser}},
		{name: "User 와 빈 Pass", opt: Options{APIURL: "http://192.0.2.1:9997", User: testUser, Pass: NewSecret("")}},
		{name: "Pass 만", opt: Options{APIURL: "http://192.0.2.1:9997", Pass: NewSecret(testPass)}},
	} {
		p, err := NewPoller(c.opt)
		if err == nil {
			t.Errorf("%s: NewPoller 가 짝이 어긋난 자격을 받았다(폴러 %p)", c.name, p)
			continue
		}
		if strings.Contains(err.Error(), testPass) {
			t.Errorf("%s: 에러에 카나리아 발견", c.name)
		}
	}
	if _, err := NewPoller(Options{APIURL: "http://192.0.2.1:9997", User: testUser, Pass: NewSecret(testPass)}); err != nil {
		t.Errorf("짝이 맞는 자격을 거부했다: %v", err)
	}
}

// countAttr 은 msg 신호 중 key 속성이 want 인 건수를 센다.
func (c *logCapture) countAttr(msg, key, want string) int {
	c.mu.Lock()
	defer c.mu.Unlock()
	n := 0
	for _, r := range c.records {
		if r.Message != msg {
			continue
		}
		r.Attrs(func(a slog.Attr) bool {
			if a.Key == key && a.Value.String() == want {
				n++
			}
			return true
		})
	}
	return n
}
