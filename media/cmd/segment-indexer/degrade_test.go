package main

// 워처 강등 국면의 loop 생존(f6a·f6b)과 재장착 복귀(f6f) — POK-168 r15a · ADR-063 결정 1.
// 조립 실패와 기동 실패는 assembleWatcher 가 한 값으로 합치므로, loop 관점의 두 국면은
// "강등 표식 + nil 채널 + 재장착 실패 지속"으로 동일하게 재현된다(각 실패점의 강등 판정
// 자체는 TestAssembleWatcherFailurePoints 가 가른다).

import (
	"context"
	"errors"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxhook"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// degradeLoop 은 워처 강등 국면의 loopDeps 를 만든다 — 채널 넷 nil, 재장착 주입.
func degradeLoop(f *loopFixture, rescanEvery time.Duration,
	reattach func() (*recording.Watcher, error)) {
	f.deps.watcherDone, f.deps.watcherErr = nil, nil
	f.deps.completed, f.deps.rescans = nil, nil
	f.deps.watcherDegraded = true
	f.deps.reattachWatcher = reattach
	f.deps.rescanEvery = rescanEvery
}

// f6a·f6b — 워처가 처음부터 없어도(조립·기동 실패) 프로세스는 죽지 않는다:
// nil 채널 select 가 정상 회전하고, 훅 유입이 계속 장부에 도달하며, 재장착 실패는
// 주기당 1회 WARN 으로 남는다.
func TestLoopSurvivesWatcherDegraded(t *testing.T) {
	f := newLoopFixture(t, true)
	degradeLoop(f, 30*time.Millisecond, func() (*recording.Watcher, error) {
		return nil, errors.New("여전히 실패")
	})
	cancel := f.run()

	// 훅 유입은 살아 있다(f6c 의 loop 측 절반 — 장부 도달).
	dir := filepath.Join(f.root, "demo")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	p := filepath.Join(dir, "2026-07-25_10-00-00-000000.mp4")
	if err := os.WriteFile(p, make([]byte, 100), 0o600); err != nil {
		t.Fatal(err)
	}
	select {
	case f.hookEv <- mtxhook.Event{
		Kind: mtxhook.KindSegmentComplete, StreamID: "demo",
		At: time.Now().UTC(), SegmentPath: p,
	}:
	case <-time.After(2 * time.Second):
		t.Fatal("강등 국면에서 루프가 훅 이벤트를 받지 않는다 — select 가 멈췄다")
	}
	waitFor(t, "강등 국면 훅 인덱싱", func() bool { return f.store.insertCount() == 1 })

	// 재장착 실패가 유계 재시도로 관측된다(주기당 1회).
	waitFor(t, "재장착 실패 WARN", func() bool {
		return f.logs.count("watcher_reattach_failed") >= 1
	})

	if err := f.stop(cancel); err != nil {
		t.Fatalf("강등 국면 loop 이 에러로 끝났다: %v", err)
	}
}

// f6f — 실패 주입이 풀리면 다음 재스캔 주기에 재장착되고 watcher_recovered 가 남는다.
func TestLoopReattachesWatcher(t *testing.T) {
	f := newLoopFixture(t, false)
	ctx, cancelWatcher := context.WithCancel(context.Background())
	t.Cleanup(cancelWatcher)

	attempts := 0
	degradeLoop(f, 30*time.Millisecond, func() (*recording.Watcher, error) {
		attempts++
		if attempts < 3 {
			return nil, errors.New("아직 실패")
		}
		return assembleWatcher(ctx, recording.DefaultWatcherOptions(f.root, slog.New(f.logs)))
	})
	cancel := f.run()

	waitFor(t, "재장착 완료", func() bool { return f.logs.count("watcher_recovered") == 1 })
	// 유계 재시도의 흔적 — 성공 전 실패 주기들이 WARN 으로 남아 있다.
	if f.logs.count("watcher_reattach_failed") < 2 {
		t.Fatalf("유계 재시도 흔적이 %d건이다(2건 이상 기대)", f.logs.count("watcher_reattach_failed"))
	}

	if err := f.stop(cancel); err != nil {
		t.Fatalf("loop() = %v, want nil", err)
	}
}

// 강등 판정의 두 실패점 — 조립(NewWatcher)과 기동(Start)이 각각 에러로 합쳐지고,
// 기동 실패 경로는 fsnotify 핸들을 닫는다(f6i 의 단위 절반 — Close 도달).
func TestAssembleWatcherFailurePoints(t *testing.T) {
	log := slog.New(&logCapture{})

	// 조립 실패 — 검증이 fsnotify 생성보다 앞서는 옵션 오류.
	bad := recording.DefaultWatcherOptions(t.TempDir(), log)
	bad.MaxWatchDirs = 0
	if _, err := assembleWatcher(context.Background(), bad); err == nil {
		t.Fatal("조립 실패가 에러로 오지 않았다")
	}

	// 기동 실패 — 루트 부재. Close 까지 도달해야 한다(누수 방지).
	gone := recording.DefaultWatcherOptions(filepath.Join(t.TempDir(), "none"), log)
	if _, err := assembleWatcher(context.Background(), gone); err == nil {
		t.Fatal("기동 실패가 에러로 오지 않았다")
	}
}
