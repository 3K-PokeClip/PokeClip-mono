package recording

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// 워처 테스트는 t.TempDir() 위에 진짜 파일을 만들어 시나리오를 재현한다.
// 판정은 time.Sleep 기반 단정이 아니라 채널 수신 + select 타임아웃으로 한다.

func testWatcherOptions(root string) WatcherOptions {
	return WatcherOptions{
		Root:        root,
		IdleTimeout: 120 * time.Millisecond,
		RescanEvery: time.Hour, // 주기 재스캔은 여기서 검증 대상이 아니다
		Settle: SettleOptions{
			PollInterval: 5 * time.Millisecond,
			SettleWait:   30 * time.Millisecond,
			MaxSettle:    300 * time.Millisecond,
		},
		FIFOWarnLen:  8,
		FIFOMaxLen:   64,
		MaxWatchDirs: 64,
		Log:          slog.New(slog.NewTextHandler(io.Discard, nil)),
	}
}

func startWatcher(t *testing.T, opt WatcherOptions) (*Watcher, context.Context, context.CancelFunc) {
	t.Helper()
	w, err := NewWatcher(opt)
	if err != nil {
		t.Fatalf("NewWatcher 실패: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	if err := w.Start(ctx); err != nil {
		t.Fatalf("Start 실패: %v", err)
	}
	t.Cleanup(func() {
		cancel()
		<-w.Done()
	})
	return w, ctx, cancel
}

// mkStream 은 스트림 디렉토리를 미리 만든다.
//
// Start 는 "반환 시점에 이미 존재하는 디렉토리"에 대해서만 무유실을 보장한다.
// Start 이후에 새로 생긴 디렉토리의 첫 파일은 watch 등록 전에 만들어질 수 있고,
// 그 창은 설계상 Rescans() 신호와 주기 재스캔이 복구한다(3절 3번). 따라서 워처 자체의
// 완성 판정을 검증하는 시나리오는 디렉토리를 Start 전에 만들어 그 창을 배제한다.
func mkStream(t *testing.T, root, streamID string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Join(root, streamID), 0o755); err != nil {
		t.Fatalf("디렉토리 생성 실패: %v", err)
	}
}

// segFile 은 스트림 디렉토리에 recordPath 형식 파일을 만든다.
func segFile(t *testing.T, root, streamID string, offset time.Duration, size int) string {
	t.Helper()
	dir := filepath.Join(root, streamID)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatalf("디렉토리 생성 실패: %v", err)
	}
	base := time.Date(2026, 7, 25, 10, 0, 0, 0, time.UTC).Add(offset)
	name := fmt.Sprintf("%s-%06d.mp4", base.Format("2006-01-02_15-04-05"), base.Nanosecond()/1000)
	p := filepath.Join(dir, name)
	if err := os.WriteFile(p, make([]byte, size), 0o600); err != nil {
		t.Fatalf("파일 생성 실패: %v", err)
	}
	return p
}

func recv(t *testing.T, ch <-chan Segment, within time.Duration) (Segment, bool) {
	t.Helper()
	select {
	case seg, ok := <-ch:
		return seg, ok
	case <-time.After(within):
		return Segment{}, false
	}
}

// 시나리오1 — Start 반환 후 생성된 파일은 반드시 emit 된다.
// Start 가 동기라서 "walk 종료 - watch 등록" 공백 구간이 구조적으로 없다.
func TestScenario01FileCreatedAfterStartIsEmitted(t *testing.T) {
	root := t.TempDir()
	mkStream(t, root, "s1")
	w, _, _ := startWatcher(t, testWatcherOptions(root))

	first := segFile(t, root, "s1", 0, 100)
	time.Sleep(20 * time.Millisecond)
	segFile(t, root, "s1", 4*time.Second, 100) // 후속 파일 생성 = first 완성 신호

	seg, ok := recv(t, w.Completed(), 2*time.Second)
	if !ok {
		t.Fatal("완성 세그먼트를 받지 못했다")
	}
	if seg.Path != first {
		t.Fatalf("emit 된 경로 = %q, want %q", seg.Path, first)
	}
	if seg.Reason != ReasonNextFile {
		t.Fatalf("Reason = %d, want ReasonNextFile", seg.Reason)
	}
	if seg.StreamID != "s1" {
		t.Fatalf("StreamID = %q, want s1", seg.StreamID)
	}
}

// 시나리오2 — 소비가 늦어도 이벤트를 잃지 않는다(내부 FIFO).
func TestScenario02NoLossWhenConsumerIsSlow(t *testing.T) {
	root := t.TempDir()
	mkStream(t, root, "s1")
	opt := testWatcherOptions(root)
	w, _, _ := startWatcher(t, opt)

	const files = 5
	paths := make([]string, 0, files)
	for i := range files {
		paths = append(paths, segFile(t, root, "s1", time.Duration(i)*4*time.Second, 100))
		time.Sleep(15 * time.Millisecond)
	}
	// 마지막 파일도 후속 파일 생성으로 확정시킨다.
	segFile(t, root, "s1", files*4*time.Second, 100)

	// 일부러 늦게 소비한다.
	time.Sleep(200 * time.Millisecond)

	got := map[string]bool{}
	for range files {
		seg, ok := recv(t, w.Completed(), 3*time.Second)
		if !ok {
			t.Fatalf("%d개만 받았다 — 유실이 있다: %v", len(got), got)
		}
		got[seg.Path] = true
	}
	for _, p := range paths {
		if !got[p] {
			t.Fatalf("%q 가 유실됐다", p)
		}
	}
}

// 시나리오3 — 새 하위 디렉토리 연속 생성 시 Rescans() 신호가 1개로 병합된다.
// eventLoop 이 이 send 로 블로킹되면 inotify 큐가 넘쳐 조용한 유실이 난다.
func TestScenario03RescanSignalsAreCoalesced(t *testing.T) {
	root := t.TempDir()
	w, _, _ := startWatcher(t, testWatcherOptions(root))

	for _, name := range []string{"a", "b", "c"} {
		if err := os.MkdirAll(filepath.Join(root, name), 0o755); err != nil {
			t.Fatalf("디렉토리 생성 실패: %v", err)
		}
	}

	// 세 이벤트가 모두 처리될 때까지 기다린 뒤 배수한다.
	// 병합은 "대기 중인 신호가 있으면 새 신호를 버린다"는 뜻이므로,
	// 처리 도중에 배수하면 그 뒤 신호가 새로 들어오는 것이 정상이다.
	time.Sleep(300 * time.Millisecond)

	select {
	case <-w.Rescans():
	case <-time.After(2 * time.Second):
		t.Fatal("Rescans 신호를 받지 못했다")
	}

	// 병합됐다면 두 번째 신호가 대기 중일 수 없다.
	select {
	case <-w.Rescans():
		t.Fatal("신호가 병합되지 않았다 — 여러 건이 쌓였다")
	case <-time.After(100 * time.Millisecond):
	}

	// 그리고 워처는 여전히 살아 있어야 한다(블로킹되지 않았다는 증거).
	mkStream(t, root, "s1")
	time.Sleep(100 * time.Millisecond) // 새 디렉토리 watch 등록 대기
	p := segFile(t, root, "s1", 0, 100)
	segFile(t, root, "s1", 4*time.Second, 100)
	seg, ok := recv(t, w.Completed(), 2*time.Second)
	if !ok || seg.Path != p {
		t.Fatalf("재스캔 신호 후 워처가 멈췄다 (ok=%v)", ok)
	}
}

// 시나리오4 — Adopt 로 되돌린 파일은 유휴 타이머로 확정된다.
func TestScenario04AdoptedFileIsConfirmedByIdleTimer(t *testing.T) {
	root := t.TempDir()
	mkStream(t, root, "s1")
	opt := testWatcherOptions(root)
	w, _, _ := startWatcher(t, opt)

	p := segFile(t, root, "s1", 0, 100)
	seg, err := ParseSegmentPath(root, p)
	if err != nil {
		t.Fatalf("ParseSegmentPath 실패: %v", err)
	}
	w.Adopt(seg)

	got, ok := recv(t, w.Completed(), 3*time.Second)
	if !ok {
		t.Fatal("유휴 확정을 받지 못했다")
	}
	if got.Path != p {
		t.Fatalf("경로 = %q, want %q", got.Path, p)
	}
	if got.Reason != ReasonIdle {
		t.Fatalf("Reason = %d, want ReasonIdle", got.Reason)
	}
}

// 시나리오5 — 쓰기 공백이 SettleWait 보다 짧으면 조기 확정하지 않는다.
func TestScenario05DoesNotConfirmEarlyDuringWriteGaps(t *testing.T) {
	root := t.TempDir()
	opt := testWatcherOptions(root)
	opt.Settle.SettleWait = 120 * time.Millisecond
	// MaxSettle 은 쓰기 구간보다 넉넉히 길게 둔다.
	// 그래야 "조기 확정하지 않는다"만 검증되고 타임아웃 경로와 뒤섞이지 않는다.
	opt.Settle.MaxSettle = 3 * time.Second
	opt.IdleTimeout = time.Hour // 유휴 확정이 끼어들지 않게 한다
	mkStream(t, root, "s1")
	w, _, _ := startWatcher(t, opt)

	p := segFile(t, root, "s1", 0, 100)
	segFile(t, root, "s1", 4*time.Second, 100) // p 를 완성 후보로 만든다

	// p 에 계속 조금씩 쓴다. 공백은 SettleWait 보다 짧다.
	writeUntil := time.Now().Add(300 * time.Millisecond)
	go func() {
		size := 100
		for time.Now().Before(writeUntil) {
			size += 100
			_ = os.WriteFile(p, make([]byte, size), 0o600)
			time.Sleep(30 * time.Millisecond)
		}
	}()

	seg, ok := recv(t, w.Completed(), 3*time.Second)
	if !ok {
		t.Fatal("완성 세그먼트를 받지 못했다")
	}
	if seg.Path != p {
		t.Fatalf("경로 = %q, want %q", seg.Path, p)
	}
	if time.Now().Before(writeUntil) {
		t.Fatal("쓰기가 끝나기 전에 확정했다 — 안정 폴링이 조기 확정했다")
	}
}

// 시나리오6 — 확정된 파일이 다시 자라면 ReasonRegrown 으로 재전달된다.
func TestScenario06RegrownFileIsRedelivered(t *testing.T) {
	root := t.TempDir()
	mkStream(t, root, "s1")
	opt := testWatcherOptions(root)
	w, _, _ := startWatcher(t, opt)

	p := segFile(t, root, "s1", 0, 100)
	seg, err := ParseSegmentPath(root, p)
	if err != nil {
		t.Fatalf("ParseSegmentPath 실패: %v", err)
	}
	w.Adopt(seg)

	first, ok := recv(t, w.Completed(), 3*time.Second)
	if !ok || first.Reason != ReasonIdle {
		t.Fatalf("유휴 확정 실패 (ok=%v reason=%d)", ok, first.Reason)
	}

	// 다 끝난 줄 알았는데 파일이 더 자랐다.
	if err := os.WriteFile(p, make([]byte, 500), 0o600); err != nil {
		t.Fatalf("파일 쓰기 실패: %v", err)
	}

	again, ok := recv(t, w.Completed(), 3*time.Second)
	if !ok {
		t.Fatal("재성장 전달을 받지 못했다")
	}
	if again.Reason != ReasonRegrown {
		t.Fatalf("Reason = %d, want ReasonRegrown", again.Reason)
	}
	if again.Path != p {
		t.Fatalf("경로 = %q, want %q", again.Path, p)
	}
}

// 시나리오7 — ctx 취소로 끝나면 Wait() 는 nil 이다(정상 종료).
func TestScenario07ContextCancelIsCleanShutdown(t *testing.T) {
	root := t.TempDir()
	w, err := NewWatcher(testWatcherOptions(root))
	if err != nil {
		t.Fatalf("NewWatcher 실패: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	if err := w.Start(ctx); err != nil {
		t.Fatalf("Start 실패: %v", err)
	}

	cancel()

	select {
	case <-w.Done():
	case <-time.After(2 * time.Second):
		t.Fatal("Done() 이 닫히지 않았다")
	}
	if err := w.Wait(); err != nil {
		t.Fatalf("Wait() = %v, want nil", err)
	}
}

// 시나리오8 — FIFO 가 상한을 넘으면 워처가 스스로 종료하고 Wait() 가 사유를 돌려준다.
// 무한정 쌓느니 죽고 재기동해 Scan 으로 따라잡는 편이 낫다(9절 L2).
func TestScenario08FIFOOverflowTerminatesWatcher(t *testing.T) {
	root := t.TempDir()
	opt := testWatcherOptions(root)
	opt.FIFOWarnLen = 2
	opt.FIFOMaxLen = 4
	opt.IdleTimeout = time.Hour
	mkStream(t, root, "s1")

	w, err := NewWatcher(opt)
	if err != nil {
		t.Fatalf("NewWatcher 실패: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if err := w.Start(ctx); err != nil {
		t.Fatalf("Start 실패: %v", err)
	}

	// Completed() 를 아무도 소비하지 않으므로 FIFO 가 계속 쌓인다.
	for i := range 12 {
		segFile(t, root, "s1", time.Duration(i)*4*time.Second, 100)
		time.Sleep(10 * time.Millisecond)
	}

	select {
	case <-w.Done():
	case <-time.After(3 * time.Second):
		t.Fatal("FIFO 상한을 넘었는데도 워처가 종료하지 않았다")
	}
	if err := w.Wait(); err == nil {
		t.Fatal("Wait() = nil, want 사유 있는 에러")
	}
}

// 시나리오10 — 두 스트림의 pending 슬롯과 완성 판정이 서로 독립적으로 동작한다.
// 상태가 전역 변수 하나로 뭉개져 있어도 단일 스트림 테스트는 전부 통과하므로 여기서 찌른다.
func TestScenario10StreamsAreIndependent(t *testing.T) {
	root := t.TempDir()
	mkStream(t, root, "s1")
	mkStream(t, root, "s2")
	w, _, _ := startWatcher(t, testWatcherOptions(root))

	a1 := segFile(t, root, "s1", 0, 100)
	time.Sleep(15 * time.Millisecond)
	b1 := segFile(t, root, "s2", 0, 100)
	time.Sleep(15 * time.Millisecond)

	// s1 에만 후속 파일을 만든다 → a1 만 ReasonNextFile 로 확정돼야 한다.
	segFile(t, root, "s1", 4*time.Second, 100)

	first, ok := recv(t, w.Completed(), 2*time.Second)
	if !ok {
		t.Fatal("첫 완성 세그먼트를 받지 못했다")
	}
	if first.Path != a1 || first.Reason != ReasonNextFile {
		t.Fatalf("첫 emit = %q(reason %d), want %q(ReasonNextFile) — 스트림 상태가 섞였다",
			first.Path, first.Reason, a1)
	}

	// 남은 두 파일(s1 의 두번째, s2 의 b1)은 후속 파일이 없어 유휴 타이머로 확정된다.
	// 두 스트림의 유휴 타이머는 서로 독립이므로 emit 순서는 정해져 있지 않다.
	// 순서가 아니라 "둘 다 각자의 사유로 나온다"를 판정한다.
	byPath := map[string]CompletionReason{}
	for range 2 {
		seg, ok := recv(t, w.Completed(), 3*time.Second)
		if !ok {
			t.Fatalf("유휴 확정을 받지 못했다 (지금까지 %v)", byPath)
		}
		byPath[seg.Path] = seg.Reason
	}
	if got, ok := byPath[b1]; !ok || got != ReasonIdle {
		t.Fatalf("s2 의 %q 가 ReasonIdle 로 나오지 않았다 (got=%d ok=%v)", b1, got, ok)
	}
	if len(byPath) != 2 {
		t.Fatalf("서로 다른 두 파일이 나와야 한다: %v", byPath)
	}
}

// Completed() 로 나오는 세그먼트에는 반드시 사유가 채워져 있어야 한다(H0 의 전제).
func TestWatcherNeverEmitsUnknownReason(t *testing.T) {
	root := t.TempDir()
	mkStream(t, root, "s1")
	w, _, _ := startWatcher(t, testWatcherOptions(root))

	segFile(t, root, "s1", 0, 100)
	time.Sleep(15 * time.Millisecond)
	segFile(t, root, "s1", 4*time.Second, 100)

	for range 2 {
		seg, ok := recv(t, w.Completed(), 3*time.Second)
		if !ok {
			return
		}
		if seg.Reason == ReasonUnknown {
			t.Fatalf("사유가 비어 있다: %q", seg.Path)
		}
	}
}

// ---------------------------------------------------------------------------
// 검수 지적 회귀 테스트
// ---------------------------------------------------------------------------

// 지적1 — 신규 디렉토리 CREATE 시 그 하위까지 재귀적으로 watch 등록한다.
// 최상위만 등록하면 live/kr/demo 처럼 깊은 트리가 한꺼번에 생길 때 안쪽이 감시 밖에 남는다.
func TestFixNewDirectoryIsWatchedRecursively(t *testing.T) {
	root := t.TempDir()
	w, _, _ := startWatcher(t, testWatcherOptions(root))

	deep := filepath.Join(root, "a", "b", "c")
	if err := os.MkdirAll(deep, 0o755); err != nil {
		t.Fatalf("디렉토리 생성 실패: %v", err)
	}

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if w.isWatched(deep) {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("최심부 디렉토리가 감시 등록되지 않았다: %q (등록된 수 %d)", deep, w.watchedCount())
}

// 지적1 보강 — Start 이후 새로 생긴 스트림 디렉토리의 파일도 결국 emit 된다.
// 실사용 경로(1단계 깊이)에서 watch 등록 -> 파일 감지가 이어지는지 확인한다.
func TestFixFileInNewStreamDirectoryIsEmitted(t *testing.T) {
	root := t.TempDir()
	w, _, _ := startWatcher(t, testWatcherOptions(root))

	mkStream(t, root, "s9")
	// 새 디렉토리의 watch 등록이 끝날 때까지 기다린다.
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) && !w.isWatched(filepath.Join(root, "s9")) {
		time.Sleep(10 * time.Millisecond)
	}

	first := segFile(t, root, "s9", 0, 100)
	time.Sleep(20 * time.Millisecond)
	segFile(t, root, "s9", 4*time.Second, 100)

	seg, ok := recv(t, w.Completed(), 3*time.Second)
	if !ok || seg.Path != first {
		t.Fatalf("새 디렉토리의 파일이 emit 되지 않았다 (ok=%v path=%q)", ok, seg.Path)
	}
}

// 지적2 — Adopt 는 StartWall 이 기존 pending 보다 늦을 때만 교체한다.
// 무조건 덮어쓰면 더 오래된 파일이 최신 pending 을 밀어내 최신 파일이 영구 유실된다.
func TestFixAdoptDoesNotDisplaceNewerPending(t *testing.T) {
	root := t.TempDir()
	opt := testWatcherOptions(root)
	// 되돌려받은 오래된 파일은 IdleTimeout 만큼 보류됐다가 풀린다(재검수 지적1-ii).
	// 그 창을 짧게 잡아 테스트가 끝까지 진행되게 한다.
	opt.IdleTimeout = 200 * time.Millisecond
	mkStream(t, root, "s1")

	// 오래된 파일은 Start 전에 만들어 둔다. 그래야 CREATE 이벤트 없이 존재만 하게 되어
	// "되돌려받은 오래된 파일" 상황을 순수하게 재현할 수 있다.
	olderPath := segFile(t, root, "s1", 0, 100)

	w, _, _ := startWatcher(t, opt)

	// Start 이후 만든 최신 파일이 pending 이 된다.
	newer := segFile(t, root, "s1", 8*time.Second, 100)
	time.Sleep(50 * time.Millisecond)

	older, err := ParseSegmentPath(root, olderPath)
	if err != nil {
		t.Fatalf("ParseSegmentPath 실패: %v", err)
	}
	w.Adopt(older)

	// 최신 파일이 pending 에서 밀려나지 않았다면, 먼저 나오는 것은 오래된 쪽이다.
	seg, ok := recv(t, w.Completed(), 3*time.Second)
	if !ok {
		t.Fatal("보류가 풀린 오래된 파일이 나오지 않았다")
	}
	if seg.Path != olderPath {
		t.Fatalf("emit = %q, want %q — 최신 pending 이 밀려났다 (newer=%q)", seg.Path, olderPath, newer)
	}
}

// 지적2 — Adopt 가 더 늦은 파일이면 교체하고, 밀려난 기존 pending 을 확정 후보로 push 한다.
func TestFixAdoptNewerReplacesAndFlushesOlder(t *testing.T) {
	root := t.TempDir()
	opt := testWatcherOptions(root)
	opt.IdleTimeout = time.Hour
	mkStream(t, root, "s1")

	newerPath := segFile(t, root, "s1", 8*time.Second, 100)
	w, _, _ := startWatcher(t, opt)

	// Start 이후 만든 오래된 파일이 pending 이 된다.
	olderPath := segFile(t, root, "s1", 0, 100)
	time.Sleep(80 * time.Millisecond)

	newer, err := ParseSegmentPath(root, newerPath)
	if err != nil {
		t.Fatalf("ParseSegmentPath 실패: %v", err)
	}
	w.Adopt(newer)

	// 더 늦은 쪽이 pending 을 차지하고, 밀려난 오래된 쪽이 확정 후보로 나온다.
	seg, ok := recv(t, w.Completed(), 2*time.Second)
	if !ok {
		t.Fatal("밀려난 오래된 파일이 확정 후보로 나오지 않았다")
	}
	if seg.Path != olderPath {
		t.Fatalf("emit = %q, want %q", seg.Path, olderPath)
	}
	if extra, ok := recv(t, w.Completed(), 300*time.Millisecond); ok {
		t.Fatalf("새로 pending 이 된 파일이 emit 됐다: %q", extra.Path)
	}
}

// 지적4 — 개별 디렉토리 감시 등록 실패는 전체 기동을 막지 않는다.
// 하드 에러로 두면 디렉토리 하나 때문에 프로세스가 crash loop 에 빠진다.
func TestFixWatchAddFailureDoesNotBlockStartup(t *testing.T) {
	root := t.TempDir()
	// 읽을 수 없는 디렉토리를 하나 만든다. WalkDir 이 그 안을 못 읽는다.
	blocked := filepath.Join(root, "blocked")
	if err := os.MkdirAll(filepath.Join(blocked, "inner"), 0o755); err != nil {
		t.Fatalf("디렉토리 생성 실패: %v", err)
	}
	if err := os.Chmod(blocked, 0o000); err != nil {
		t.Fatalf("권한 변경 실패: %v", err)
	}
	t.Cleanup(func() { _ = os.Chmod(blocked, 0o755) })

	mkStream(t, root, "s1")
	w, _, _ := startWatcher(t, testWatcherOptions(root))

	// 나머지 스트림은 정상 동작해야 한다.
	first := segFile(t, root, "s1", 0, 100)
	time.Sleep(20 * time.Millisecond)
	segFile(t, root, "s1", 4*time.Second, 100)

	seg, ok := recv(t, w.Completed(), 3*time.Second)
	if !ok || seg.Path != first {
		t.Fatalf("한 디렉토리 실패가 전체를 막았다 (ok=%v)", ok)
	}
}

// 지적4 — 감시 디렉토리 수 상한을 넘으면 신규 등록을 무시한다(자원 고갈 방지).
func TestFixWatchDirLimitIsEnforced(t *testing.T) {
	root := t.TempDir()
	opt := testWatcherOptions(root)
	opt.MaxWatchDirs = 2 // 루트 + 1개
	mkStream(t, root, "s1")

	w, err := NewWatcher(opt)
	if err != nil {
		t.Fatalf("NewWatcher 실패: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer func() { cancel(); <-w.Done() }()
	if err := w.Start(ctx); err != nil {
		t.Fatalf("Start 실패: %v", err)
	}

	for _, name := range []string{"s2", "s3", "s4"} {
		mkStream(t, root, name)
	}
	time.Sleep(300 * time.Millisecond)

	if got := w.watchedCount(); got > opt.MaxWatchDirs {
		t.Fatalf("감시 디렉토리 수 = %d, want <= %d", got, opt.MaxWatchDirs)
	}
}

// 지적3 — 화이트리스트를 통과하지 못한 스트림 디렉토리의 파일은 emit 되지 않는다.
func TestFixRejectedStreamIDIsNotEmitted(t *testing.T) {
	root := t.TempDir()
	mkStream(t, root, "s1")
	mkStream(t, root, "나쁜 이름")
	w, _, _ := startWatcher(t, testWatcherOptions(root))

	segFile(t, root, "나쁜 이름", 0, 100)
	time.Sleep(20 * time.Millisecond)
	segFile(t, root, "나쁜 이름", 4*time.Second, 100)

	if seg, ok := recv(t, w.Completed(), 500*time.Millisecond); ok {
		t.Fatalf("거부돼야 할 스트림의 파일이 emit 됐다: %q", seg.Path)
	}
}

// 지적9 — 주기 재스캔 타이머의 소유자는 main 루프 하나다.
// 워처가 자체 주기 타이머를 또 돌리면 같은 일이 두 번 예약된다.
func TestFixWatcherHasNoPeriodicRescanTimer(t *testing.T) {
	root := t.TempDir()
	opt := testWatcherOptions(root)
	opt.RescanEvery = 30 * time.Millisecond // 워처가 타이머를 갖고 있다면 금방 울린다
	w, _, _ := startWatcher(t, opt)

	select {
	case <-w.Rescans():
		t.Fatal("워처가 자체 주기 타이머로 재스캔 신호를 냈다 — 소유자는 main 루프 하나여야 한다")
	case <-time.After(300 * time.Millisecond):
	}
}

// MaxWatchDirs 가 0이면 감시가 통째로 죽으므로 생성 단계에서 거부한다.
func TestFixWatcherRejectsZeroWatchDirLimit(t *testing.T) {
	opt := testWatcherOptions(t.TempDir())
	opt.MaxWatchDirs = 0
	if _, err := NewWatcher(opt); err == nil {
		t.Fatal("MaxWatchDirs=0 인데 에러가 없다")
	}
}

// ---------------------------------------------------------------------------
// 재검수 지적 회귀 테스트
// ---------------------------------------------------------------------------

// 지적1-(i) 단위 — FIFO 는 같은 스트림 안에서 StartWall 오름차순을 유지한다.
// 늦게 밀려 들어온 오래된 항목이 뒤에 붙으면, 앞선 항목이 먼저 처리돼 커서가 전진하고
// 그 뒤에 나오는 오래된 항목은 전부 늦은 세그먼트로 폐기된다.
func TestFix2FIFOKeepsPerStreamWallOrder(t *testing.T) {
	base := time.Date(2026, 7, 25, 10, 0, 0, 0, time.UTC)
	seg := func(stream string, offset time.Duration) Segment {
		return Segment{StreamID: stream, Path: stream + offset.String(), StartWall: base.Add(offset)}
	}

	f := newSegmentFIFO()
	f.push(seg("s1", 9*time.Second))
	f.push(seg("s2", time.Second)) // 다른 스트림은 서로 순서를 강제하지 않는다
	f.push(seg("s1", 20*time.Second))
	f.push(seg("s1", time.Second)) // 뒤늦게 들어온 가장 오래된 항목

	ctx := context.Background()
	var s1Order []time.Duration
	for range 4 {
		got, ok := f.pop(ctx)
		if !ok {
			t.Fatal("pop 실패")
		}
		if got.StreamID == "s1" {
			s1Order = append(s1Order, got.StartWall.Sub(base))
		}
	}

	want := []time.Duration{time.Second, 9 * time.Second, 20 * time.Second}
	if len(s1Order) != len(want) {
		t.Fatalf("s1 항목 수 = %d, want %d", len(s1Order), len(want))
	}
	for i := range want {
		if s1Order[i] != want[i] {
			t.Fatalf("s1 순서 = %v, want %v", s1Order, want)
		}
	}
}

// 지적1-(i) 통합 — 밀려난 오래된 확정 후보가 FIFO 에 이미 있는 더 늦은 항목보다 먼저 나온다.
func TestFix2DisplacedOlderSegmentIsEmittedBeforeQueuedLaterOne(t *testing.T) {
	root := t.TempDir()
	opt := testWatcherOptions(root)
	opt.IdleTimeout = 150 * time.Millisecond
	mkStream(t, root, "s1")

	// 되돌려 넣을 파일들은 Start 전에 만들어 CREATE 이벤트가 끼어들지 않게 한다.
	oldest := segFile(t, root, "s1", 0, 100)
	newest := segFile(t, root, "s1", 30*time.Second, 100)

	w, _, _ := startWatcher(t, opt)

	// D(9s), E(20s) 를 만들어 FIFO 에 쌓는다. 소비는 아직 하지 않는다.
	segFile(t, root, "s1", 9*time.Second, 100)
	time.Sleep(30 * time.Millisecond)
	laterPath := segFile(t, root, "s1", 20*time.Second, 100)
	time.Sleep(30 * time.Millisecond)

	// 유휴로 pending(E)까지 확정시켜 pending 을 비운다.
	time.Sleep(250 * time.Millisecond)

	oldestSeg, err := ParseSegmentPath(root, oldest)
	if err != nil {
		t.Fatalf("ParseSegmentPath 실패: %v", err)
	}
	newestSeg, err := ParseSegmentPath(root, newest)
	if err != nil {
		t.Fatalf("ParseSegmentPath 실패: %v", err)
	}

	// 빈 pending 에 가장 오래된 파일을 올린 뒤, 더 늦은 파일로 밀어낸다.
	w.Adopt(oldestSeg)
	time.Sleep(30 * time.Millisecond)
	w.Adopt(newestSeg)
	// Adopt 는 채널로 넘길 뿐이라 eventLoop 이 처리하기 전에 소비를 시작하면
	// emitLoop 이 먼저 다음 항목을 꺼내 간다. 밀어내기가 FIFO 에 반영될 때까지 기다린다.
	time.Sleep(100 * time.Millisecond)

	// 이제 순서대로 받아 보면, 밀려난 oldest 가 laterPath 보다 먼저 나와야 한다.
	seenOldest, seenLater := -1, -1
	for i := range 4 {
		seg, ok := recv(t, w.Completed(), 3*time.Second)
		if !ok {
			break
		}
		switch seg.Path {
		case oldest:
			seenOldest = i
		case laterPath:
			seenLater = i
		}
	}
	if seenOldest < 0 {
		t.Fatal("밀려난 오래된 파일이 나오지 않았다")
	}
	if seenLater >= 0 && seenOldest > seenLater {
		t.Fatalf("순서 역전: oldest=%d later=%d — 오래된 쪽이 먼저 나와야 한다", seenOldest, seenLater)
	}
}

// 지적1-(ii) — 되돌려받은 파일이 현재 pending 보다 오래되면 즉시 재확정하지 않는다.
// 즉시 확정하면 아직 자라는 파일이 곧바로 emitLoop 의 Settle 을 붙잡고,
// 되돌아오고 다시 확정되기를 반복하며 직렬 처리 구간을 점유한다.
// 설계 H5 의 "재시도 간격은 IdleTimeout 이상"을 유휴 타이머 경로로 복원한다.
func TestFix2ReadoptedGrowingFileIsThrottled(t *testing.T) {
	root := t.TempDir()
	opt := testWatcherOptions(root)
	opt.IdleTimeout = 400 * time.Millisecond
	mkStream(t, root, "s1")
	growing := segFile(t, root, "s1", 0, 100)

	w, _, _ := startWatcher(t, opt)

	// Start 이후 만든 더 늦은 파일이 pending 을 차지한다.
	segFile(t, root, "s1", 20*time.Second, 100)
	time.Sleep(50 * time.Millisecond)

	seg, err := ParseSegmentPath(root, growing)
	if err != nil {
		t.Fatalf("ParseSegmentPath 실패: %v", err)
	}
	w.Adopt(seg)

	// IdleTimeout 이 지나기 전에는 다시 확정되면 안 된다.
	if got, ok := recv(t, w.Completed(), 200*time.Millisecond); ok && got.Path == growing {
		t.Fatal("되돌려받은 파일이 즉시 재확정됐다 — 스로틀이 없다")
	}

	// 그리고 결국은 나와야 한다. 버려지면 그 구간이 영구 부재가 된다.
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		got, ok := recv(t, w.Completed(), time.Second)
		if !ok {
			break
		}
		if got.Path == growing {
			return
		}
	}
	t.Fatal("되돌려받은 파일이 끝내 나오지 않았다")
}

// 지적3 — 디렉토리가 지워지면 감시 맵에서도 빠지고, 같은 이름으로 다시 생기면 재감시된다.
// 맵에 남아 있으면 addWatch 가 "이미 등록됨"으로 건너뛰어 재생성된 디렉토리가 감시 밖에 남는다.
func TestFix2RemovedDirectoryIsUnwatchedAndRewatchable(t *testing.T) {
	root := t.TempDir()
	opt := testWatcherOptions(root)
	mkStream(t, root, "s1")
	w, _, _ := startWatcher(t, opt)

	dir := filepath.Join(root, "s1")
	if !w.isWatched(dir) {
		t.Fatal("초기 감시 등록이 안 됐다")
	}
	before := w.watchedCount()

	if err := os.RemoveAll(dir); err != nil {
		t.Fatalf("디렉토리 삭제 실패: %v", err)
	}
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) && w.isWatched(dir) {
		time.Sleep(10 * time.Millisecond)
	}
	if w.isWatched(dir) {
		t.Fatal("삭제된 디렉토리가 감시 맵에 남아 있다")
	}
	if got := w.watchedCount(); got >= before {
		t.Fatalf("감시 수 = %d, want < %d (카운터가 줄지 않았다)", got, before)
	}

	// 같은 이름으로 다시 만들면 다시 감시돼야 한다.
	mkStream(t, root, "s1")
	deadline = time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) && !w.isWatched(dir) {
		time.Sleep(10 * time.Millisecond)
	}
	if !w.isWatched(dir) {
		t.Fatal("재생성된 디렉토리가 다시 감시되지 않는다")
	}
}

// 지적4 — 세그먼트로 파싱되지 않는 "파일" CREATE 는 전수 재스캔도 트리 walk 도 유발하지 않는다.
// 녹화 폴더에 임시 파일이 섞일 때마다 5분 주기 점검이 앞당겨지면 헛일이 쌓인다.
func TestFix2NonSegmentFileDoesNotTriggerRescan(t *testing.T) {
	root := t.TempDir()
	mkStream(t, root, "s1")
	w, _, _ := startWatcher(t, testWatcherOptions(root))

	if err := os.WriteFile(filepath.Join(root, "s1", "메모.txt"), []byte("x"), 0o600); err != nil {
		t.Fatalf("파일 생성 실패: %v", err)
	}

	select {
	case <-w.Rescans():
		t.Fatal("일반 파일 생성이 전수 재스캔을 유발했다")
	case <-time.After(300 * time.Millisecond):
	}
}
