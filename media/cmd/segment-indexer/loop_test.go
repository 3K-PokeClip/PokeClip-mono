package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/indexer"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxhook"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// ---------------------------------------------------------------------------
// 픽스처 — 루프의 신호 처리만 보므로 DB·워처는 최소한의 가짜로 세운다.
// ---------------------------------------------------------------------------

type fakeStore struct {
	mu      sync.Mutex
	rows    []index.Record
	inserts int
}

func (s *fakeStore) LoadCursor(context.Context, string) (index.Cursor, error) {
	return index.Cursor{}, nil
}

func (s *fakeStore) ExistingPaths(context.Context, string) (map[string]struct{}, error) {
	return map[string]struct{}{}, nil
}

func (s *fakeStore) Insert(_ context.Context, r index.Record, _ index.Seed, _ index.SessionSource) (index.InsertOutcome, index.SeedResult, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.inserts++
	s.rows = append(s.rows, r)
	return index.InsertInserted, index.SeedResult{}, nil
}

func (s *fakeStore) UpdateTail(context.Context, string, int64, int32, int64) (bool, error) {
	return true, nil
}

func (s *fakeStore) insertCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.inserts
}

type logCapture struct {
	mu      sync.Mutex
	records []slog.Record
}

func (c *logCapture) Enabled(context.Context, slog.Level) bool { return true }

func (c *logCapture) Handle(_ context.Context, r slog.Record) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.records = append(c.records, r)
	return nil
}

func (c *logCapture) WithAttrs([]slog.Attr) slog.Handler { return c }
func (c *logCapture) WithGroup(string) slog.Handler      { return c }

func (c *logCapture) count(msg string) int {
	c.mu.Lock()
	defer c.mu.Unlock()
	n := 0
	for _, r := range c.records {
		if r.Message == msg {
			n++
		}
	}
	return n
}

// countLevel 은 레벨까지 맞는 기록만 센다. "로그가 났다"와 "ERROR 로 났다"는 다른 주장이다.
func (c *logCapture) countLevel(level slog.Level, msg string) int {
	c.mu.Lock()
	defer c.mu.Unlock()
	n := 0
	for _, r := range c.records {
		if r.Level == level && r.Message == msg {
			n++
		}
	}
	return n
}

// attrs 는 그 메시지의 마지막 기록에서 속성을 뽑는다.
func (c *logCapture) attrs(msg string) map[string]any {
	c.mu.Lock()
	defer c.mu.Unlock()
	var found *slog.Record
	for i := range c.records {
		if c.records[i].Message == msg {
			found = &c.records[i]
		}
	}
	if found == nil {
		return nil
	}
	out := map[string]any{}
	found.Attrs(func(a slog.Attr) bool {
		out[a.Key] = a.Value.Any()
		return true
	})
	return out
}

type noopAdopter struct{}

func (noopAdopter) Adopt(recording.Segment) {}

type loopFixture struct {
	t         *testing.T
	root      string
	store     *fakeStore
	logs      *logCapture
	deps      loopDeps
	completed chan recording.Segment
	rescans   chan struct{}
	watcher   chan struct{}
	hookEv    chan mtxhook.Event
	hookDone  chan struct{}
	err       chan error
	// hookWaitErr 는 Reader.Wait() 이 돌려줄 사유다. nil 이면 정상 종료를 뜻한다.
	hookWaitErr error
}

func newLoopFixture(t *testing.T, withHook bool) *loopFixture {
	t.Helper()
	f := &loopFixture{
		t:         t,
		root:      t.TempDir(),
		store:     &fakeStore{},
		logs:      &logCapture{},
		completed: make(chan recording.Segment),
		rescans:   make(chan struct{}),
		watcher:   make(chan struct{}),
		hookEv:    make(chan mtxhook.Event),
		hookDone:  make(chan struct{}),
		err:       make(chan error, 1),
	}
	log := slog.New(f.logs)

	opt := indexer.DefaultOptions()
	opt.SegmentRoot = f.root
	// up 은 nil 이다 — 이 파일이 보는 것은 루프의 신호 처리이지 업로드 정책이 아니다.
	// nil 이면 아무것도 요청하지 않는 기본값이 끼워진다(indexer.New).
	ix := indexer.New(f.store, func(string) (int64, error) { return 4000, nil }, noopAdopter{}, nil, nil, opt, log)

	f.deps = loopDeps{
		ix:          ix,
		root:        f.root,
		rescanEvery: time.Hour, // 테스트 중 주기 재스캔이 끼어들지 않게 한다
		log:         log,
		watcherDone: f.watcher,
		watcherErr:  func() error { return nil },
		completed:   f.completed,
		rescans:     f.rescans,
		armSweeper:  func() {}, // 완주 판정이 나도 이 파일의 관심사가 아니다 — no-op
		stallFactor: collectStallFactor,
	}
	if withHook {
		f.deps.hookEvents = f.hookEv
		f.deps.hookDone = f.hookDone
		f.deps.hookErr = func() error { return f.hookWaitErr }
	}
	// withHook 이 false 면 두 필드는 nil 채널로 남는다 — 그 case 는 영구 비활성이다.
	return f
}

// run 은 루프를 띄우고 종료 시 반환값을 err 채널로 준다.
func (f *loopFixture) run() context.CancelFunc {
	f.t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	go func() { f.err <- loop(ctx, f.deps) }()
	return cancel
}

// stop 은 루프를 끝내고 반환값을 확인한다.
func (f *loopFixture) stop(cancel context.CancelFunc) error {
	f.t.Helper()
	cancel()
	select {
	case err := <-f.err:
		return err
	case <-time.After(2 * time.Second):
		f.t.Fatal("루프가 ctx 취소 후에도 끝나지 않았다")
		return nil
	}
}

// feedSegment 는 워처 채널로 완성 세그먼트 1개를 흘리고 인덱싱을 기다린다.
func (f *loopFixture) feedSegment(name string) {
	f.t.Helper()
	dir := filepath.Join(f.root, "demo")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		f.t.Fatalf("디렉토리 생성 실패: %v", err)
	}
	p := filepath.Join(dir, name)
	if err := os.WriteFile(p, make([]byte, 100), 0o600); err != nil {
		f.t.Fatalf("파일 생성 실패: %v", err)
	}
	seg, err := recording.ParseSegmentPath(f.root, p)
	if err != nil {
		f.t.Fatalf("ParseSegmentPath 실패: %v", err)
	}
	seg.Reason = recording.ReasonNextFile

	before := f.store.insertCount()
	select {
	case f.completed <- seg:
	case <-time.After(2 * time.Second):
		f.t.Fatal("루프가 완성 세그먼트를 받지 않는다")
	}
	waitFor(f.t, "세그먼트 인덱싱", func() bool { return f.store.insertCount() > before })
}

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("%s: 시간 안에 성립하지 않았다", what)
}

// ---------------------------------------------------------------------------
// 테스트
// ---------------------------------------------------------------------------

// T16 — HOOK_SPOOL_PATH 가 비면 Reader 를 만들지 않으므로 두 채널이 nil 이다.
// Go 에서 nil 채널 case 는 **영구 비활성**이라 패닉도 busy loop 도 나지 않는다.
// 이 성질이 롤백 스위치를 안전하게 만든다.
func TestLoopRunsWithNilHookChannels(t *testing.T) {
	f := newLoopFixture(t, false)
	cancel := f.run()

	f.feedSegment("2026-07-25_10-00-00-000000.mp4")

	if err := f.stop(cancel); err != nil {
		t.Fatalf("loop() = %v, want nil", err)
	}
	if n := f.store.insertCount(); n != 1 {
		t.Errorf("INSERT = %d회, want 1회", n)
	}
}

// T17 — Reader 가 죽어도 프로세스를 끝내지 않는다(ADR-027 2항: 훅은 1차 신호이고
// 벽시계·파일 감시·주기 재스캔이 안전망으로 남는다. 여기서 죽이면 GH4 위반이다).
//
// ERROR 가 정확히 1회여야 한다는 것이 핵심이다. 닫힌 채널은 언제나 수신 가능하므로
// 그 case 를 비활성화하지 않으면 select 가 무한히 돌며 CPU 를 태운다.
func TestLoopSurvivesReaderDeathAndLogsOnce(t *testing.T) {
	f := newLoopFixture(t, true)
	f.hookWaitErr = errReaderDied
	cancel := f.run()

	close(f.hookDone)
	waitFor(t, "hook_reader_stopped ERROR", func() bool { return f.logs.count("hook_reader_stopped") >= 1 })

	// 훅이 죽은 뒤에도 파일 감시 경로는 그대로 돈다.
	f.feedSegment("2026-07-25_10-00-00-000000.mp4")

	if err := f.stop(cancel); err != nil {
		t.Fatalf("loop() = %v, want nil — Reader 사망으로 프로세스를 끝냈다", err)
	}
	if n := f.logs.countLevel(slog.LevelError, "hook_reader_stopped"); n != 1 {
		t.Errorf("hook_reader_stopped ERROR = %d회, want 1회 — 닫힌 채널 case 가 살아 있어 루프가 돌았다", n)
	}
	// 사유가 없으면 운영자는 왜 훅이 멈췄는지 알 길이 없다.
	if got := f.logs.attrs("hook_reader_stopped")["err"]; got == nil ||
		!strings.Contains(fmt.Sprint(got), errReaderDied.Error()) {
		t.Errorf("hook_reader_stopped.err = %v, want %q 포함", got, errReaderDied)
	}
	if n := f.store.insertCount(); n != 1 {
		t.Errorf("INSERT = %d회, want 1회 — 훅 사망 후 인덱싱이 멈췄다", n)
	}
}

// Events 채널이 닫히는 것(Reader 정상 종료)도 같은 규약이다 — 그 case 만 끄고 계속 간다.
func TestLoopDisablesClosedHookEventsChannel(t *testing.T) {
	f := newLoopFixture(t, true)
	cancel := f.run()

	close(f.hookEv)
	f.feedSegment("2026-07-25_10-00-00-000000.mp4")

	if err := f.stop(cancel); err != nil {
		t.Fatalf("loop() = %v, want nil", err)
	}
	if n := f.store.insertCount(); n != 1 {
		t.Errorf("INSERT = %d회, want 1회", n)
	}
}

// 훅 이벤트는 워처와 **같은 select** 에서 처리돼야 한다(D10).
// 다른 고루틴에서 부르면 seq 중복이 난다.
func TestLoopHandlesHookSegmentEvent(t *testing.T) {
	f := newLoopFixture(t, true)
	cancel := f.run()

	dir := filepath.Join(f.root, "demo")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatalf("디렉토리 생성 실패: %v", err)
	}
	p := filepath.Join(dir, "2026-07-25_10-00-00-000000.mp4")
	if err := os.WriteFile(p, make([]byte, 100), 0o600); err != nil {
		t.Fatalf("파일 생성 실패: %v", err)
	}

	select {
	case f.hookEv <- mtxhook.Event{
		Kind: mtxhook.KindSegmentComplete, StreamID: "demo",
		At: time.Now().UTC(), SegmentPath: p,
	}:
	case <-time.After(2 * time.Second):
		t.Fatal("루프가 훅 이벤트를 받지 않는다")
	}
	waitFor(t, "훅 세그먼트 인덱싱", func() bool { return f.store.insertCount() == 1 })

	if err := f.stop(cancel); err != nil {
		t.Fatalf("loop() = %v, want nil", err)
	}
}

// 워처 사망은 훅과 정반대 규약이다 — 감시자가 죽으면 실시간 INSERT 가 조용히 멈추므로
// 즉시 프로세스를 끝내고 재기동 후 Scan 이 밀린 것을 따라잡게 한다.
func TestLoopStopsWhenWatcherDies(t *testing.T) {
	f := newLoopFixture(t, true)
	cancel := f.run()
	defer cancel()

	close(f.watcher)

	select {
	case err := <-f.err:
		if err != nil {
			t.Fatalf("loop() = %v, want nil(정상 종료)", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("워처 사망에도 루프가 끝나지 않았다")
	}
}

var errReaderDied = errors.New("스풀 읽기 실패")

// Wait() 이 nil 이면 ctx 취소에 의한 정상 종료다. 이때 ERROR 를 찍으면 종료 때마다
// 가짜 장애 신호가 남는다 — select 가 ctx.Done 과 hookDone 중 어느 것을 뽑을지 모르기 때문에
// 정상 종료에서도 이 case 가 실행될 수 있다.
func TestLoopDoesNotErrorWhenReaderStopsCleanly(t *testing.T) {
	f := newLoopFixture(t, true)
	f.hookWaitErr = nil
	cancel := f.run()

	close(f.hookDone)
	waitFor(t, "hook_reader_stopped 기록", func() bool { return f.logs.count("hook_reader_stopped") >= 1 })

	if err := f.stop(cancel); err != nil {
		t.Fatalf("loop() = %v, want nil", err)
	}
	if n := f.logs.countLevel(slog.LevelError, "hook_reader_stopped"); n != 0 {
		t.Errorf("정상 종료인데 ERROR = %d회, want 0회", n)
	}
}
