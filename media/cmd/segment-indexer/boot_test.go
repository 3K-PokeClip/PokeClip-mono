package main

// 기동 순서 검증(POK-195 M3 단계 4 — 설계 6.5.3 ⑶ · 계획 4.6).
//
// 여기서 재는 것은 **순서와 소유**다: 관측 폴러가 초기 수집보다 먼저 뜨고, 첫 관측을
// 기다린 뒤에야 나머지가 돌며, 폴 고루틴의 수명이 run() 에 묶여 있는가.
// 판정 자체(ⓐ2 자격)는 indexer 가, 파싱은 mtxstate 가 각자 자기 테스트로 잰다.

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/indexer"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxstate"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// 실측 응답 원문(F-34 · internal/mtxstate/state_test.go 와 같은 본문)에서 이 테스트가
// 쓰는 것만 옮겼다 — 배선이 "실물 JSON"을 통과하는지 보는 것이 목적이라 줄이지 않는다.
const bootRawPublishing = `{"itemCount":1,"pageCount":1,"items":[{"name":"probe-stream","confName":"all_others","ready":true,"readyTime":"2026-09-01T16:25:40.788540645Z","available":true,"availableTime":"2026-09-01T16:25:40.788540645Z","online":true,"onlineTime":"2026-09-01T16:25:40.78854881Z","source":{"type":"rtmpConn","id":"7b2e8365-e080-4b34-b87f-ada79ac77866"},"tracks":["H264"],"readers":[],"inboundBytes":262281,"outboundBytes":0,"inboundFramesInError":0,"bytesReceived":262281,"bytesSent":0}]}`

// bootLogCapture 는 신호를 담아 두는 로거다(테스트가 "무엇이 기록됐나"까지 본다).
//
// **락이 있는 이유**: 폴 고루틴이 mtxstate_poll_failed 를 쓰는 동안 테스트 고루틴이
// 같은 슬라이스를 읽는다. 다른 패키지의 같은 이름 헬퍼(internal/indexer·internal/mtxstate)도
// 같은 이유로 락을 든다.
type bootLogCapture struct {
	mu      sync.Mutex
	records []slog.Record
}

func (c *bootLogCapture) Enabled(context.Context, slog.Level) bool { return true }
func (c *bootLogCapture) Handle(_ context.Context, r slog.Record) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.records = append(c.records, r)
	return nil
}
func (c *bootLogCapture) WithAttrs([]slog.Attr) slog.Handler { return c }
func (c *bootLogCapture) WithGroup(string) slog.Handler      { return c }

func (c *bootLogCapture) has(msg string) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, r := range c.records {
		if r.Message == msg {
			return true
		}
	}
	return false
}

// levelOf 는 그 신호가 실린 등급들이다. 기동 신호의 등급도 계약이다 — Debug 로 내려가면
// 기본 LOG_LEVEL(info)에서 통째로 사라져 "관측이 꺼졌다"가 다시 무징후가 된다.
func (c *bootLogCapture) levelOf(msg string) []slog.Level {
	c.mu.Lock()
	defer c.mu.Unlock()
	var out []slog.Level
	for i := range c.records {
		if c.records[i].Message == msg {
			out = append(out, c.records[i].Level)
		}
	}
	return out
}

// attr 은 그 메시지의 마지막 기록에서 속성 하나를 뽑는다.
func (c *bootLogCapture) attr(msg, key string) (any, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	var out any
	var found bool
	for i := range c.records {
		if c.records[i].Message != msg {
			continue
		}
		c.records[i].Attrs(func(a slog.Attr) bool {
			if a.Key == key {
				out, found = a.Value.Any(), true
			}
			return true
		})
	}
	return out, found
}

// 1단계·2단계 — 폴러를 띄우고 첫 관측을 기다린 **뒤에** 관측자를 돌려준다.
// 기다리지 않으면 초기 수집이 관측 이력 0 위에서 돌아 그 주기의 스캔 유입이 통째로
// ⓐ2 fail-closed 된다(설계 6.5.3 ⑶ 이 2단계를 둔 이유가 이것이다).
func TestStartObserverWaitsForFirstObservation(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(bootRawPublishing))
	}))
	defer srv.Close()

	logs := &bootLogCapture{}
	observer, stop := startObserver(context.Background(), mtxstate.Options{
		APIURL: srv.URL, PollInterval: time.Second, BootWait: 3 * time.Second,
	}, slog.New(logs))
	defer stop()

	if observer == nil {
		t.Fatal("관측자가 nil 이다 — 폴러가 배선되지 않았다")
	}
	// 반환 시점에 이미 관측이 있어야 한다. 여기서 폴을 기다리면 그 자체로 계약 위반이다.
	obs := observer.Latest("probe-stream")
	if !obs.Publishing || !obs.EpochKnown {
		t.Fatalf("첫 관측을 기다리지 않고 돌아왔다: %+v", obs)
	}
	if obs.Tier != mtxstate.TierOnlineTime {
		t.Errorf("tier = %v, want TierOnlineTime(ⓘ)", obs.Tier)
	}
	if got, ok := logs.attr("mtxstate_started", "first_observation"); !ok || got != true {
		t.Errorf("mtxstate_started 의 first_observation = %v(있음=%v), want true", got, ok)
	}
	if got := logs.levelOf("mtxstate_started"); len(got) != 1 || got[0] != slog.LevelInfo {
		t.Errorf("mtxstate_started 등급 = %v, want [INFO] 1건", got)
	}
}

// 관측을 못 얻어도 기동은 계속된다 — 관측 고장은 강등이지 사망이 아니다(설계 S3).
// 그 상태의 관측은 EpochKnown=false 라 ⓐ2 만 닫힌다.
func TestStartObserverProceedsWhenFirstObservationTimesOut(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "boom", http.StatusInternalServerError)
	}))
	defer srv.Close()

	logs := &bootLogCapture{}
	start := time.Now()
	observer, stop := startObserver(context.Background(), mtxstate.Options{
		APIURL: srv.URL, PollInterval: time.Second, BootWait: 200 * time.Millisecond,
	}, slog.New(logs))
	defer stop()

	if elapsed := time.Since(start); elapsed > 2*time.Second {
		t.Fatalf("기동이 %v 지연됐다 — BootWait 이 상한이어야 한다", elapsed)
	}
	if observer == nil {
		t.Fatal("관측 실패가 관측자 자체를 없앴다 — 폴러는 계속 돌며 회복해야 한다")
	}
	if obs := observer.Latest("probe-stream"); obs.EpochKnown {
		t.Fatalf("관측 이력 0 인데 EpochKnown 이 참이다: %+v", obs)
	}
	if got, ok := logs.attr("mtxstate_started", "first_observation"); !ok || got != false {
		t.Errorf("mtxstate_started 의 first_observation = %v(있음=%v), want false", got, ok)
	}
}

// MTX_API_URL 빈 값 = 즉시 롤백 스위치. 폴러를 아예 만들지 않고 널 오브젝트로 간다.
// **신호를 남기는 것이 계약이다**: 이 상태에서는 주조가 조용히 멈추므로(설계 S4 무징후
// 금지), 로그가 없으면 "되감기가 안 된다"가 원인 없는 증상으로 나타난다.
func TestStartObserverDisabledWhenAPIURLEmpty(t *testing.T) {
	logs := &bootLogCapture{}
	observer, stop := startObserver(context.Background(), mtxstate.Options{}, slog.New(logs))
	defer stop()

	if observer != nil {
		t.Fatalf("APIURL 이 비었는데 관측자가 만들어졌다: %T", observer)
	}
	if !logs.has("mtxstate_disabled") {
		t.Error("관측이 꺼진 상태가 무징후다 — mtxstate_disabled 신호가 없다")
	}
	if got := logs.levelOf("mtxstate_disabled"); len(got) != 1 || got[0] != slog.LevelInfo {
		t.Errorf("mtxstate_disabled 등급 = %v, want [INFO] 1건", got)
	}
}

// stop 은 폴 고루틴 종료까지 기다린다(소유는 run()). 부모 ctx 가 살아 있어도 끝나야
// 한다 — 부모 취소에만 기대면 loop 이 에러로 빠져나온 경로에서 영원히 매달린다.
func TestStartObserverStopWaitsForPollerExit(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(bootRawPublishing))
	}))
	defer srv.Close()

	observer, stop := startObserver(context.Background(), mtxstate.Options{
		APIURL: srv.URL, PollInterval: time.Second, BootWait: 3 * time.Second,
	}, slog.New(&bootLogCapture{}))
	if observer == nil {
		t.Fatal("관측자가 nil 이다")
	}

	done := make(chan struct{})
	go func() { stop(); close(done) }()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("stop 이 반환하지 않는다 — 부모 ctx 취소에만 기대면 여기서 프로세스가 멈춘다")
	}
}

// 기동 순서(설계 6.5.3 ⑶)를 소스 위치로 확인한다.
//
// run() 은 DB·워처가 있어야 돌아 단위 테스트가 불가능하다(TestShutdownDeferRegistrationOrder
// 와 같은 사정). 이 회귀는 "한 줄을 위아래로 옮기는" 형태로 오므로 위치 검사가 정확히 잡는다.
func TestBootOrderPutsObserverBeforeInitialCollect(t *testing.T) {
	src, err := os.ReadFile("main.go")
	if err != nil {
		t.Fatalf("main.go 를 읽지 못했다: %v", err)
	}
	text := string(src)

	order := []string{
		"observer, stopObserver := startObserver(", // 1~2단계: 폴러 기동 + 첫 관측 대기
		"w, wErr := assembleWatcher(",              // 3~4단계: 워처 조립·기동(실패는 강등)
		"up.Start(ctx)",                            // 5단계: 업로더
		"ix.StartCollect(ctx, cfg.SegmentRoot)",    // 6단계: 초기 수집 발사
	}
	prev := -1
	for _, want := range order {
		at := strings.Index(text, want)
		if at < 0 {
			t.Fatalf("%q 를 찾지 못했다 — 기동 단계가 사라졌거나 이름이 바뀌었다", want)
		}
		if at <= prev {
			t.Fatalf("%q 의 위치가 앞선다. 기동 순서는 %v 여야 한다(설계 6.5.3 ⑶)", want, order)
		}
		prev = at
	}
}

// 폴 고루틴은 **정확히 한 번** 뜬다. Start 를 두 번 부르면 두 고루틴이 같은 done 채널을
// 닫아 close of closed channel 로 프로세스가 panic 한다.
func TestPollerStartedExactlyOnce(t *testing.T) {
	paths, err := os.ReadDir(".")
	if err != nil {
		t.Fatalf("소스 목록 수집 실패: %v", err)
	}
	var newPoller, starts, waits int
	for _, e := range paths {
		name := e.Name()
		if !strings.HasSuffix(name, ".go") || strings.HasSuffix(name, "_test.go") {
			continue
		}
		src, err := os.ReadFile(name)
		if err != nil {
			t.Fatalf("%s 읽기 실패: %v", name, err)
		}
		text := string(src)
		newPoller += strings.Count(text, "mtxstate.NewPoller(")
		starts += strings.Count(text, ".Start(pollCtx)")
		waits += strings.Count(text, "WaitFirstObservation(")
	}
	if newPoller != 1 {
		t.Errorf("mtxstate.NewPoller 호출이 %d곳이다 — 폴러의 소유자는 한 곳이어야 한다", newPoller)
	}
	if starts != 1 {
		t.Errorf("폴러 Start 호출이 %d곳이다 — 두 번 부르면 done 채널 이중 닫기로 panic 한다", starts)
	}
	if waits != 1 {
		t.Errorf("WaitFirstObservation 호출이 %d곳이다 — 첫 관측 대기는 기동 1회뿐이다", waits)
	}
}

// f6b_start_fail — 워처 **기동** 실패의 실제 경로를 조립부터 인덱서 주입까지 잇는다.
//
// **왜 여기인가**: 기동 실패는 `recording.Watcher` 안에서 일어나므로 indexer 패키지에서는
// 재현할 수 없고, 프로덕션에 고장 주입 이음매를 새로 뚫는 것은 원칙 5 위반이다. 실패를
// 실제로 만들 수 있는 자리는 조립부(cmd)뿐이라 그 결과 상태까지를 여기서 한 줄로 잇는다.
//
// 이 케이스가 세우는 사실 = **"Start 실패 → w=nil → 널 오브젝트 adopt"**.
// 그 상태에서 훅·스캔 유입이 그대로 주조되는지(f6c·f6d)는 실 장부가 필요해
// internal/indexer 의 TestPhaseF6DegradedWatcherStillSeeds 가 잇는다 — 두 테스트가
// 같은 국면의 앞뒤 절반이며, 서로를 이름으로 가리킨다.
func TestWatcherStartFailureFeedsNullObjectAdopter(t *testing.T) {
	logs := &logCapture{}
	log := slog.New(logs)

	// 기동 실패 입력 — NewWatcher 는 통과하고 addWatch(root) 가 실패한다(루트 부재).
	// 같은 입력의 "에러가 온다"까지는 TestAssembleWatcherFailurePoints 가 이미 잰다.
	gone := recording.DefaultWatcherOptions(filepath.Join(t.TempDir(), "none"), log)
	w, err := assembleWatcher(context.Background(), gone)
	if err == nil {
		t.Fatal("기동 실패가 에러로 오지 않았다 — 이 케이스의 전제가 깨졌다")
	}
	if w != nil {
		t.Fatalf("기동 실패인데 워처가 %T 로 돌아왔다 — 강등 판정이 성립하지 않는다", w)
	}

	// main.go 와 **같은 형태**로 넘긴다(겹 1): *recording.Watcher 를 그대로 넘기면
	// typed-nil 이 indexer.New 의 nil 가드(겹 2)를 통과해 Adopt 에서 죽는다.
	var adopt indexer.Adopter
	if w != nil {
		adopt = w
	}

	root := t.TempDir()
	store := &fakeStore{}
	opt := indexer.DefaultOptions()
	opt.SegmentRoot = root
	ix := indexer.New(store, func(string) (int64, error) { return 4000, nil },
		adopt, nil, nil, opt, log)

	// 방금 쓰인(유휴가 아닌) 파일의 Idle 판정은 워처에게 되돌려야 하는 조각이다.
	// 워처가 없으면 널 오브젝트가 받아 계수하고 버린다 — 프로세스는 죽지 않는다.
	seg := justWrittenSegment(t, root, "f6bstream")
	if err := ix.Handle(context.Background(), seg); err != nil {
		t.Fatalf("강등 국면 되돌림이 에러다 — 널 오브젝트가 아니라 nil 이 끼워졌다: %v", err)
	}

	if n := logs.countLevel(slog.LevelWarn, "adopt_dropped_degraded"); n != 1 {
		t.Errorf("adopt_dropped_degraded 가 %d건이다(1건 기대) — 되돌림이 널 오브젝트로 가지 않았다", n)
	}
	if n := store.insertCount(); n != 0 {
		t.Errorf("되돌려야 할 조각이 %d행 기록됐다 — 성장 중일 수 있는 파일이다", n)
	}
}

// justWrittenSegment 는 **방금 쓰인** 조각 파일을 만든다(mtime = 지금).
// H4 의 유휴 재검이 "아직 쓰이는 중"으로 보고 되돌림 경로로 보내는 것이 목적이다.
func justWrittenSegment(t *testing.T, root, streamID string) recording.Segment {
	t.Helper()
	dir := filepath.Join(root, streamID)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatalf("디렉토리 생성 실패: %v", err)
	}
	now := time.Now().UTC()
	name := fmt.Sprintf("%s-%06d.mp4", now.Format("2006-01-02_15-04-05"), now.Nanosecond()/1000)
	path := filepath.Join(dir, name)
	if err := os.WriteFile(path, make([]byte, 1000), 0o600); err != nil {
		t.Fatalf("파일 생성 실패: %v", err)
	}
	seg, err := recording.ParseSegmentPath(root, path)
	if err != nil {
		t.Fatalf("ParseSegmentPath 실패: %v", err)
	}
	seg.Reason = recording.ReasonIdle
	return seg
}

// ⓐ2 앵커 창 검증 — OBS_FRESH 가 트랜잭션 상한 이하면 **기동을 거부한다**.
//
// 왜 기동 거부인가: indexer 의 usableAnchor 는 `now−ObservedAt <= ObsFresh − TxnDeadline`
// 으로 판정한다(트랜잭션이 상한까지 늘어져도 관측이 아직 신선하도록 미리 뺀다). 두 값이
// 같거나 뒤집히면 우변이 0 이하가 되어 **어떤 관측도 앵커가 될 수 없고**, 스캔 유입 ⓐ2 가
// 영구 봉인된다. 폴은 성공하고 로그도 깨끗한데 주조만 안 되는 무징후 고장이라
// (config 의 교차 검증 4 와 같은 종류), 기동 때 값으로 잡는 것이 유일한 방어다.
//
// 현행 교차 검증(OBS_POLL < OBS_FRESH)은 이 조합을 통과시킨다 — 예: OBS_POLL=1s·OBS_FRESH=5s.
func TestObservationWindowMustExceedTxnDeadline(t *testing.T) {
	for name, c := range map[string]struct {
		obsFresh time.Duration
		wantErr  bool
	}{
		"상한 미만":      {index.TxnDeadline - time.Second, true},
		"상한과 같음(경계)": {index.TxnDeadline, true},
		"상한 초과(경계)":  {index.TxnDeadline + time.Millisecond, false},
		"기본값 30초":    {30 * time.Second, false},
	} {
		err := validateObservationWindow(c.obsFresh)
		if (err != nil) != c.wantErr {
			t.Errorf("%s(%v): err = %v, 기동 거부 기대 = %v", name, c.obsFresh, err, c.wantErr)
			continue
		}
		if !c.wantErr {
			continue
		}
		// 오류 문구에 두 값이 함께 있어야 한다 — 하나만 있으면 무엇과 비교해 고칠지 모른다.
		msg := err.Error()
		if !strings.Contains(msg, c.obsFresh.String()) || !strings.Contains(msg, index.TxnDeadline.String()) {
			t.Errorf("%s: 오류 문구에 두 값이 다 없다: %q", name, msg)
		}
	}
}
