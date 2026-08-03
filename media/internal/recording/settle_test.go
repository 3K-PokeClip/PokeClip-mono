package recording

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func writeFile(t *testing.T, dir, name string, size int) string {
	t.Helper()
	p := filepath.Join(dir, name)
	if err := os.WriteFile(p, make([]byte, size), 0o600); err != nil {
		t.Fatalf("파일 생성 실패: %v", err)
	}
	return p
}

// 시나리오9-1 — 이미 안정된 파일에는 즉시 반환한다(정상 경로 추가 지연 0).
// mtime 나이가 SettleWait 이상이고 연속 두 stat 크기가 같으면 더 기다릴 이유가 없다.
func TestSettleReturnsImmediatelyForStableFile(t *testing.T) {
	p := writeFile(t, t.TempDir(), "seg.mp4", 1024)
	// 이미 한참 전에 마지막으로 쓰인 파일로 만든다.
	old := time.Now().Add(-time.Minute)
	if err := os.Chtimes(p, old, old); err != nil {
		t.Fatalf("mtime 조정 실패: %v", err)
	}

	opt := SettleOptions{PollInterval: 200 * time.Millisecond, SettleWait: time.Second, MaxSettle: 5 * time.Second}

	start := time.Now()
	fi, err := Settle(context.Background(), p, opt)
	elapsed := time.Since(start)

	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	if fi.Size() != 1024 {
		t.Errorf("Size = %d, want 1024", fi.Size())
	}
	if elapsed >= opt.PollInterval {
		t.Fatalf("%v 걸렸다 — 즉시 반환이 아니다", elapsed)
	}
}

// 시나리오9-2 — mtime 이 미래인 파일(시계 스큐)에는 즉시 반환하지 않고 폴링 경로를 탄다.
// 나이 계산 자체를 믿을 수 없기 때문이다.
func TestSettleFallsBackToPollingForFutureMtime(t *testing.T) {
	p := writeFile(t, t.TempDir(), "seg.mp4", 1024)
	future := time.Now().Add(time.Minute)
	if err := os.Chtimes(p, future, future); err != nil {
		t.Fatalf("mtime 조정 실패: %v", err)
	}

	opt := SettleOptions{PollInterval: 20 * time.Millisecond, SettleWait: 150 * time.Millisecond, MaxSettle: 3 * time.Second}

	start := time.Now()
	fi, err := Settle(context.Background(), p, opt)
	elapsed := time.Since(start)

	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	if fi.Size() != 1024 {
		t.Errorf("Size = %d, want 1024", fi.Size())
	}
	if elapsed < opt.SettleWait {
		t.Fatalf("%v 만에 반환했다 — SettleWait(%v) 만큼 폴링하지 않았다", elapsed, opt.SettleWait)
	}
}

// 시나리오9-3 — 계속 자라는 파일은 MaxSettle 초과 시 마지막 관측값과 함께 ErrSettleTimeout.
func TestSettleTimesOutOnGrowingFile(t *testing.T) {
	dir := t.TempDir()
	p := writeFile(t, dir, "seg.mp4", 100)

	stop := make(chan struct{})
	defer close(stop)
	go func() {
		size := 100
		for {
			select {
			case <-stop:
				return
			case <-time.After(10 * time.Millisecond):
				size += 100
				_ = os.WriteFile(p, make([]byte, size), 0o600)
			}
		}
	}()

	opt := SettleOptions{PollInterval: 10 * time.Millisecond, SettleWait: 200 * time.Millisecond, MaxSettle: 300 * time.Millisecond}

	fi, err := Settle(context.Background(), p, opt)
	if !errors.Is(err, ErrSettleTimeout) {
		t.Fatalf("err = %v, want ErrSettleTimeout", err)
	}
	if fi == nil {
		t.Fatal("마지막 관측값(FileInfo)이 nil 이다 — 호출자가 크기를 판정할 수 없다")
	}
}

// ctx 취소가 대기 중인 Settle 을 즉시 빠져나오게 한다(SIGTERM 처리의 전제).
func TestSettleHonorsContextCancel(t *testing.T) {
	p := writeFile(t, t.TempDir(), "seg.mp4", 1024)
	future := time.Now().Add(time.Minute) // 즉시 반환 경로를 막아 폴링으로 보낸다
	if err := os.Chtimes(p, future, future); err != nil {
		t.Fatalf("mtime 조정 실패: %v", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		time.Sleep(20 * time.Millisecond)
		cancel()
	}()

	opt := SettleOptions{PollInterval: 10 * time.Millisecond, SettleWait: 10 * time.Second, MaxSettle: 30 * time.Second}
	if _, err := Settle(ctx, p, opt); !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v, want context.Canceled", err)
	}
}

func TestSettleMissingFileReturnsError(t *testing.T) {
	opt := DefaultSettleOptions()
	if _, err := Settle(context.Background(), filepath.Join(t.TempDir(), "없다.mp4"), opt); err == nil {
		t.Fatal("에러를 기대했으나 nil")
	}
}
