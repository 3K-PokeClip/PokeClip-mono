package endsignal

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/control"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/keyresolve"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/streamkey"
)

// 시험 값은 모두 합성이다. passphrase 와 파생 값은 계약4 3-1 의 시험 벡터다(실제 키가 아니다).
const (
	testToken      = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
	testTokenB     = "0123456789ABCDEFGHJKMNPQRS"
	testPassphrase = "EXAMPLE-4d-test-vector-not-a-key"
	testBearer     = "bf127d0f99ed7397ea578d019fb6e83e0c15d36032f24420fef4d0294ca3e398"
	// wrongBearer 는 꼴은 맞고 값이 틀린 파생 값이다.
	wrongBearer = "0000000000000000000000000000000000000000000000000000000000000000"
)

// testNow 는 고정 수신 시각이다.
var testNow = time.Date(2026, 10, 26, 12, 0, 0, 0, time.UTC)

// syncBuffer 는 여러 고루틴이 쓰는 로그 버퍼다.
type syncBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *syncBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *syncBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

// logLine 은 JSON 로그 한 줄의 단언 대상 칸이다.
type logLine struct {
	Level    string `json:"level"`
	Msg      string `json:"msg"`
	Status   int    `json:"status"`
	Reason   string `json:"reason"`
	StreamID string `json:"stream_id"`
	Kind     string `json:"kind"`
	Count    int    `json:"count"`
}

// lines 는 지금까지 쌓인 로그 줄이다.
func (b *syncBuffer) lines(t *testing.T) []logLine {
	t.Helper()
	var out []logLine
	for _, s := range strings.Split(strings.TrimSpace(b.String()), "\n") {
		if s == "" {
			continue
		}
		var l logLine
		if err := json.Unmarshal([]byte(s), &l); err != nil {
			t.Fatalf("로그 줄 해석 실패: %v", err)
		}
		out = append(out, l)
	}
	return out
}

// fixture 는 가짜 함수 칸을 끼운 Receiver 와 그것을 띄운 시험 서버다.
type fixture struct {
	t    *testing.T
	recv *Receiver
	srv  *httptest.Server
	log  *syncBuffer

	resolveCalls atomic.Int32
	applyCalls   atomic.Int32
	resolve      func(ctx context.Context, token string) (keyresolve.Verdict, error)
	apply        func(ctx context.Context, e session.IntentionalEnd) (session.IntentionalResult, error)

	mu      sync.Mutex
	applied []session.IntentionalEnd
	changed []string
	posted  []session.RevokeIntent
}

// validVerdict 는 시험 벡터 passphrase 를 돌려주는 허락 판정이다.
func validVerdict(t *testing.T, raw string) keyresolve.Verdict {
	t.Helper()
	p, err := streamkey.NewPassphrase(raw)
	if err != nil {
		t.Fatalf("NewPassphrase: %v", err)
	}
	return keyresolve.Verdict{Valid: true, UserID: 7, Passphrase: p}
}

// testOptions 는 운영 수치와 고정 시계를 쓰는 기본 Options 다. 함수 칸은 f 의 가짜로 간다.
func (f *fixture) testOptions() Options {
	clock := func() time.Time { return testNow }
	return Options{
		Resolve: func(ctx context.Context, token string) (keyresolve.Verdict, error) {
			f.resolveCalls.Add(1)
			return f.resolve(ctx, token)
		},
		Apply: func(ctx context.Context, e session.IntentionalEnd) (session.IntentionalResult, error) {
			f.applyCalls.Add(1)
			f.mu.Lock()
			f.applied = append(f.applied, e)
			f.mu.Unlock()
			return f.apply(ctx, e)
		},
		Changed: func(streamID string) {
			f.mu.Lock()
			defer f.mu.Unlock()
			f.changed = append(f.changed, streamID)
		},
		PostIntent: func(streamID, sessionID string) {
			f.mu.Lock()
			defer f.mu.Unlock()
			f.posted = append(f.posted, session.RevokeIntent{StreamID: streamID, SessionID: sessionID})
		},
		Now:            clock,
		Log:            slog.New(slog.NewJSONHandler(f.log, &slog.HandlerOptions{Level: slog.LevelDebug})),
		BodyTimeout:    time.Second,
		ResolveTimeout: time.Second,
		ApplyTimeout:   1500 * time.Millisecond,
		PerKey:         control.NewLimiter(1, control.DefaultLimiterCells, clock, nil),
		Global:         control.NewLimiter(10, 1, clock, nil),
	}
}

// newFixture 는 edit 로 고친 Options 로 Receiver 를 띄운다. 기본 가짜는 허락 판정 · marked 효과다.
func newFixture(t *testing.T, edit func(*Options)) *fixture {
	t.Helper()
	f := &fixture{t: t, log: &syncBuffer{}}
	f.resolve = func(context.Context, string) (keyresolve.Verdict, error) { return validVerdict(t, testPassphrase), nil }
	f.apply = func(context.Context, session.IntentionalEnd) (session.IntentionalResult, error) {
		return session.IntentionalResult{SessionID: "S1", Effect: "marked"}, nil
	}
	o := f.testOptions()
	if edit != nil {
		edit(&o)
	}
	recv, err := New(o)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	f.recv = recv
	f.srv = httptest.NewServer(recv.Route().Handler)
	t.Cleanup(f.srv.Close)
	return f
}

// signalBody 는 정상 본문이다.
func signalBody(token string, elapsedMs, durationMs int64) string {
	return fmt.Sprintf(`{"streamid":"#!::r=%s,m=publish","elapsedSinceStopMs":%d,"connectionDurationMs":%d}`,
		token, elapsedMs, durationMs)
}

// response 는 받은 응답의 상태와 본문이다.
type response struct {
	status int
	body   string
}

// post 는 본문과 Authorization 값(빈 값이면 싣지 않음)으로 요청 하나를 보낸다.
func (f *fixture) post(body, authorization string) response {
	f.t.Helper()
	req, err := http.NewRequest(http.MethodPost, f.srv.URL+"/api/ingest/end-signal", strings.NewReader(body))
	if err != nil {
		f.t.Fatalf("NewRequest: %v", err)
	}
	req.Header.Set("Content-Type", "application/json")
	if authorization != "" {
		req.Header.Set("Authorization", authorization)
	}
	client := &http.Client{Timeout: 5 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		f.t.Fatalf("요청 실패: %v", err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return response{status: resp.StatusCode, body: string(b)}
}

// postOK 는 정상 본문 · 맞는 Bearer 요청이다.
func (f *fixture) postOK(token string) response {
	f.t.Helper()
	return f.post(signalBody(token, 420, 1834000), "Bearer "+testBearer)
}

// snapshot 은 표지 · 해제 요청 · 적용 입력의 사본이다.
func (f *fixture) snapshot() (changed []string, posted []session.RevokeIntent, applied []session.IntentionalEnd) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.changed...), append([]session.RevokeIntent(nil), f.posted...),
		append([]session.IntentionalEnd(nil), f.applied...)
}

// lastEndSignal 은 마지막 end_signal 줄이다.
func (f *fixture) lastEndSignal() logLine {
	f.t.Helper()
	var last logLine
	found := false
	for _, l := range f.log.lines(f.t) {
		if l.Msg == "end_signal" {
			last, found = l, true
		}
	}
	if !found {
		f.t.Fatalf("end_signal 줄이 없다")
	}
	return last
}
