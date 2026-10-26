package main

// 4D 종료 신호 수신부의 조립(POK-472 커밋 4 · 설계 2절 「설정 짝 규칙」 · 3절 「종료 계약」 · 8절 「조립」)의 시험이다.

import (
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/endsignal"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/keyresolve"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/status"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/streamkey"
)

// 시험 값은 합성이다. passphrase · 파생 값은 계약4 3-1 시험 벡터다.
const (
	wireToken      = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
	wirePassphrase = "EXAMPLE-4d-test-vector-not-a-key"
	wireBearer     = "bf127d0f99ed7397ea578d019fb6e83e0c15d36032f24420fef4d0294ca3e398"
	resolveCanary  = "resolve-token-canary-5d2e"
)

// lockedLog 는 여러 고루틴이 쓰는 JSON 로그 버퍼다.
type lockedLog struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (l *lockedLog) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.buf.Write(p)
}

// msgs 는 로그 줄의 (msg, reason) 차례다.
func (l *lockedLog) msgs(t *testing.T) []string {
	t.Helper()
	l.mu.Lock()
	defer l.mu.Unlock()
	var out []string
	for _, s := range strings.Split(strings.TrimSpace(l.buf.String()), "\n") {
		var line struct{ Msg, Reason string }
		if err := json.Unmarshal([]byte(s), &line); err != nil {
			continue
		}
		out = append(out, line.Msg+":"+line.Reason)
	}
	return out
}

func newLockedLogger() (*lockedLog, *slog.Logger) {
	l := &lockedLog{}
	return l, slog.New(slog.NewJSONHandler(l, &slog.HandlerOptions{Level: slog.LevelDebug}))
}

// 짝 규칙의 조립 쪽 — 둘 다 빈 값이면 클라이언트 없음, 둘 다 있으면 토큰 파일을 읽어 클라이언트를 만든다. 토큰 파일
// 읽기 실패 · 주소 꼴 실패는 기동 실패이고, 에러에 경로 · 내용이 없다(설계 6절 — secretfile 오류는 env 이름만).
func TestNewResolveClient(t *testing.T) {
	dir := t.TempDir()
	good := writeSecret(t, dir, "token", resolveCanary+"\n", 0o600)
	world := writeSecret(t, dir, "world", resolveCanary+"\n", 0o644)

	if c, err := newResolveClient(config.Config{}); c != nil || err != nil {
		t.Fatalf("빈 설정 = (%v, %v), want (nil, nil)", c, err)
	}
	if c, err := newResolveClient(config.Config{ResolveURL: "http://10.0.1.5:8082", ResolveTokenFile: good}); c == nil || err != nil {
		t.Fatalf("정상 설정 = (%v, %v), want 클라이언트", c, err)
	}
	for name, cfg := range map[string]config.Config{
		"없는_파일":    {ResolveURL: "http://10.0.1.5:8082", ResolveTokenFile: dir + "/missing-secret-path"},
		"other_비트": {ResolveURL: "http://10.0.1.5:8082", ResolveTokenFile: world},
		"주소_꼴":     {ResolveURL: "10.0.1.5:8082", ResolveTokenFile: good},
	} {
		c, err := newResolveClient(cfg)
		if err == nil || c != nil {
			t.Errorf("%s: (%v, %v), want 에러", name, c, err)
			continue
		}
		if msg := err.Error(); strings.Contains(msg, dir) || strings.Contains(msg, resolveCanary) {
			t.Errorf("%s: 에러에 경로 · 내용이 실렸다", name)
		}
	}
}

// 클라이언트가 없으면(설정 둘 다 빈 값) 수신부를 만들지 않고 WARN end_signal_disabled reason=no_resolve 다.
func TestNewEndSignalDisabledWithoutResolver(t *testing.T) {
	logs, log := newLockedLogger()
	recv, err := newEndSignal(nil, fakeApply(nil), func(string) {}, func(string, string) {}, log)
	if recv != nil || err != nil {
		t.Fatalf("= (%v, %v), want (nil, nil)", recv, err)
	}
	if got := logs.msgs(t); len(got) != 1 || got[0] != "end_signal_disabled:no_resolve" {
		t.Fatalf("로그 = %v, want [end_signal_disabled:no_resolve]", got)
	}
}

// fakeApply 는 hold 가 nil 이 아니면 그것을 부르고 marked 를 돌려주는 Apply 칸이다.
func fakeApply(hold func(ctx context.Context)) func(context.Context, session.IntentionalEnd) (session.IntentionalResult, error) {
	return func(ctx context.Context, _ session.IntentionalEnd) (session.IntentionalResult, error) {
		if hold != nil {
			hold(ctx)
		}
		if err := ctx.Err(); err != nil {
			return session.IntentionalResult{}, err
		}
		return session.IntentionalResult{Effect: "marked"}, nil
	}
}

// fakeResolver 는 시험 벡터 passphrase 를 돌려주는 resolve 칸이다.
func fakeResolver(t *testing.T) func(context.Context, string) (keyresolve.Verdict, error) {
	p, err := streamkey.NewPassphrase(wirePassphrase)
	if err != nil {
		t.Fatal(err)
	}
	return func(context.Context, string) (keyresolve.Verdict, error) {
		return keyresolve.Verdict{Valid: true, UserID: 1, Passphrase: p}, nil
	}
}

// freeListen 은 비어 있는 루프백 주소다.
func freeListen(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := ln.Addr().String()
	_ = ln.Close()
	return addr
}

// controlFixture 는 startControlWithEndSignal 로 띄운 제어면이다. hold 는 Apply 칸이 진입 뒤 부르는 함수다.
type controlFixture struct {
	listen  string
	logs    *lockedLog
	stop    func()
	entered chan struct{}
}

func newControlFixture(t *testing.T, withRecv bool, hold func(ctx context.Context)) *controlFixture {
	t.Helper()
	logs, log := newLockedLogger()
	f := &controlFixture{listen: freeListen(t), logs: logs, entered: make(chan struct{}, 1)}
	var recv *endsignal.Receiver
	if withRecv {
		var err error
		recv, err = newEndSignalReceiver(endSignalDeps{
			resolve: fakeResolver(t),
			apply: fakeApply(func(ctx context.Context) {
				f.entered <- struct{}{}
				if hold != nil {
					hold(ctx)
				}
			}),
			changed: func(string) {}, postIntent: func(string, string) {}, log: log,
		})
		if err != nil {
			t.Fatalf("newEndSignalReceiver: %v", err)
		}
	}
	cfg := config.Config{Control: config.ControlOptions{Listen: f.listen, StreamRPS: 10}}
	f.stop = startControlWithEndSignal(cfg, &status.Holder{}, log, nil, recv)
	return f
}

// post 는 정상 4D 요청 하나다.
func (f *controlFixture) post(t *testing.T) int {
	t.Helper()
	body := `{"streamid":"#!::r=` + wireToken + `,m=publish","elapsedSinceStopMs":420,"connectionDurationMs":1834000}`
	req, _ := http.NewRequest(http.MethodPost, "http://"+f.listen+"/api/ingest/end-signal", strings.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+wireBearer)
	resp, err := (&http.Client{Timeout: 6 * time.Second}).Do(req)
	if err != nil {
		return 0
	}
	resp.Body.Close()
	return resp.StatusCode
}

// indexOf 는 로그 차례에서 처음 나타나는 줄의 자리다(없으면 -1).
func indexOf(msgs []string, prefix string) int {
	for i, m := range msgs {
		if strings.HasPrefix(m, prefix) {
			return i
		}
	}
	return -1
}

// R9 · 종료 계약 — 진행 중 4D 처리기는 수신부 Close 로 먼저 끝나고(503 closing) 그 뒤에 제어면 서버가 멈춘다.
// 순서가 뒤집히면 서버 Shutdown 이 처리기를 기다리다 Apply 시한(apply_timeout)으로 끝난다.
func TestEndSignalClosesBeforeControlServerR9(t *testing.T) {
	f := newControlFixture(t, true, func(ctx context.Context) { <-ctx.Done() })
	got := make(chan int, 1)
	go func() { got <- f.post(t) }()
	<-f.entered
	f.stop()
	if code := <-got; code != http.StatusServiceUnavailable {
		t.Fatalf("진행 중 요청 = %d, want 503", code)
	}
	msgs := f.logs.msgs(t)
	closing, stopped := indexOf(msgs, "end_signal:closing"), indexOf(msgs, "control_server_stopped")
	if closing < 0 || stopped < 0 || closing > stopped {
		t.Fatalf("종료 차례 = %v, want end_signal:closing 이 control_server_stopped 보다 먼저", msgs)
	}
}

// 종료 시한 ctx 는 멈출 때 만든다 — 기동 뒤 2초가 지나 멈춰도 Close 가 1.5초를 다 받는다. 가짜 Apply 는 취소를 받고도
// 1초 더 걸린다(그동안 Close 가 기다려야 한다).
func TestEndSignalCloseDeadlineStartsAtStop(t *testing.T) {
	f := newControlFixture(t, true, func(ctx context.Context) {
		<-ctx.Done()
		time.Sleep(time.Second)
	})
	time.Sleep(2 * time.Second)
	got := make(chan int, 1)
	go func() { got <- f.post(t) }()
	<-f.entered
	f.stop()
	<-got
	msgs := f.logs.msgs(t)
	if i := indexOf(msgs, "end_signal_close_timeout"); i >= 0 {
		t.Fatalf("Close 가 시한을 다 받지 못했다: %v", msgs)
	}
	if closing, stopped := indexOf(msgs, "end_signal:closing"), indexOf(msgs, "control_server_stopped"); closing < 0 || closing > stopped {
		t.Fatalf("종료 차례 = %v", msgs)
	}
}

// 수신부가 없으면(설정 둘 다 빈 값) Route 를 등록하지 않는다 — 미등록 404(플러그인은 재시도하지 않는다).
func TestControlWithoutEndSignalIs404(t *testing.T) {
	f := newControlFixture(t, false, nil)
	defer f.stop()
	if code := f.post(t); code != http.StatusNotFound {
		t.Fatalf("상태 = %d, want 404", code)
	}
}

// 4D 계수기는 control_rate_limited 게이지를 세우지 않는다(cc M1) — 게이지는 세움 줄만 있고 내림이 없는데, 4D 계수기는
// 이 수신부만의 것이라 내리는 쪽(청소 고루틴)이 없다. 세우면 한 번 초과한 뒤 게이지가 영영 1 로 남는다. 키당 · 전체 상한을
// 둘 다 넘겨도(429 · 503) 그 줄이 0 이어야 한다 — 초과는 수신부 자신의 end_signal 줄이 센다.
func TestEndSignalLimitersRaiseNoRateGauge(t *testing.T) {
	f := newControlFixture(t, true, nil)
	defer f.stop()
	codes := map[int]int{}
	for range endSignalGlobalRPS + 2 {
		codes[f.post(t)]++
	}
	if codes[http.StatusTooManyRequests] == 0 || codes[http.StatusServiceUnavailable] == 0 {
		t.Fatalf("상태 분포 = %v, want 키당 초과 429 와 전체 초과 503 이 모두 있다", codes)
	}
	if i := indexOf(f.logs.msgs(t), "control_rate_limited"); i >= 0 {
		t.Fatalf("control_rate_limited 줄이 남았다(%d번째) — 4D 계수기가 내림 없는 게이지를 세웠다", i)
	}
}
