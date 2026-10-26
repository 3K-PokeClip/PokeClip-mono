package control

// 시험 보조 — 주입 시계 · 로그 받기 · 서버 띄우기 · 요청 보내기. 판정은 각 시험이 직접 한다(단언 도우미를 두지 않는다).

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/status"
)

var t0 = time.Date(2026, 10, 25, 12, 0, 0, 0, time.UTC)

// fakeClock 은 시험이 움직이는 시계다. 여러 고루틴이 함께 읽는다.
type fakeClock struct {
	mu  sync.Mutex
	now time.Time
}

func newClock() *fakeClock { return &fakeClock{now: t0} }

func (c *fakeClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

func (c *fakeClock) Advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.now = c.now.Add(d)
}

// logSink 는 JSON 로그 줄을 모은다. 처리기 고루틴이 함께 쓴다.
type logSink struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (s *logSink) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.buf.Write(p)
}

func (s *logSink) logger() *slog.Logger { return slog.New(slog.NewJSONHandler(s, nil)) }

// events 는 msg 가 name 인 줄을 키-값 맵으로 돌려준다.
func (s *logSink) events(name string) []map[string]any {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []map[string]any
	for _, line := range strings.Split(s.buf.String(), "\n") {
		var m map[string]any
		if json.Unmarshal([]byte(line), &m) == nil && m["msg"] == name {
			out = append(out, m)
		}
	}
	return out
}

// countValue 는 name 줄 가운데 value 칸이 v 인 줄 수다.
func (s *logSink) countValue(name string, v float64) int {
	n := 0
	for _, e := range s.events(name) {
		if e["value"] == v {
			n++
		}
	}
	return n
}

// countWarnValue 는 name 줄 가운데 WARN 등급이고 value 칸이 v 인 줄 수다. 게이지의 세움 · 내림은 같은 등급이어야
// LOG_LEVEL=warn 에서도 내림이 걸러지지 않는다(관측 신호 등급 set/clear 대칭).
func (s *logSink) countWarnValue(name string, v float64) int {
	n := 0
	for _, e := range s.events(name) {
		if e["level"] == "WARN" && e["value"] == v {
			n++
		}
	}
	return n
}

// fixture 는 Holder · 시계 · 로그 · 한도를 묶은 시험 조립이다.
type fixture struct {
	holder *status.Holder
	clock  *fakeClock
	logs   *logSink
	opt    Options
}

func newFixture(rps int) *fixture {
	f := &fixture{holder: &status.Holder{}, clock: newClock(), logs: &logSink{}}
	log := f.logs.logger()
	f.opt = Options{
		Listen:      "127.0.0.1:0",
		AllowOrigin: "http://localhost:3000",
		Limiter:     NewLimiter(rps, DefaultLimiterCells, f.clock.Now, log),
		RoundLimits: DefaultRoundLimits(),
		Log:         log,
		Now:         f.clock.Now,
	}
	return f
}

// store 는 지금 시계로 Board 를 만들어 넣는다.
func (f *fixture) store(warm bool, entries map[string]status.Entry) {
	f.holder.Store(status.NewBoard(f.clock.Now(), warm, entries))
}

// start 는 routes 로 서버를 띄우고 시험 끝에 멈춘다. 기준 URL 을 돌려준다.
func (f *fixture) start(t *testing.T, routes ...Route) string {
	t.Helper()
	srv, err := Start(f.opt, routes...)
	if err != nil {
		t.Fatalf("Start = %v", err)
	}
	t.Cleanup(func() { srv.Shutdown() })
	return "http://" + srv.Addr().String()
}

// testClient 는 요청마다 시한이 있는 클라이언트다 — 처리기가 멈추는 회귀가 패키지 시한(10분)까지 붙잡지 않고 그 요청의
// 실패로 끝나게(cx c2 M2 · 결함 예방 스킬의 시험별 유한 상한).
var testClient = &http.Client{Timeout: testWait}

// testWait 는 시험이 한 사건(요청 하나 · 고루틴 신호 하나)을 기다리는 상한이다.
const testWait = 10 * time.Second

// waitFor 는 ch 가 닫히거나 값을 낼 때까지 testWait 만큼 기다린다. 넘으면 what 을 적고 시험을 멈춘다.
func waitFor[T any](t *testing.T, ch <-chan T, what string) {
	t.Helper()
	select {
	case <-ch:
	case <-time.After(testWait):
		t.Fatalf("%s 를 %v 안에 보지 못했다", what, testWait)
	}
}

type reply struct {
	code   int
	header http.Header
	body   string
}

func get(t *testing.T, url string, header ...string) reply {
	t.Helper()
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i+1 < len(header); i += 2 {
		req.Header.Set(header[i], header[i+1])
	}
	res, err := testClient.Do(req)
	if err != nil {
		t.Fatalf("GET %s = %v", url, err)
	}
	defer res.Body.Close()
	b, err := io.ReadAll(res.Body)
	if err != nil {
		t.Fatal(err)
	}
	return reply{code: res.StatusCode, header: res.Header, body: string(b)}
}

var (
	pdtFirst = time.Date(2026, 10, 25, 11, 0, 0, 0, time.UTC)
	pdtHead  = time.Date(2026, 10, 25, 11, 59, 58, 500_000_000, time.UTC)
	vodSt    = status.Status{Ready: true, Mode: status.ModeVOD, URL: "https://m.example/vod/s/R1/index.m3u8", FirstPDT: pdtFirst, HeadPDT: pdtHead}
)

func vodEntry(roundID string) status.Entry {
	return status.Entry{Stream: vodSt, Round: &status.RoundEntry{RoundID: roundID, Status: vodSt}}
}

// cellCount 는 l 의 계수기 칸 수다.
func cellCount(l *Limiter) int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.cells)
}

// cacheCount 는 회차 경로 r 의 1초 캐시 칸 수다.
func cacheCount(r Route) int {
	h := r.Handler.(*roundHandler)
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.cache)
}
