//go:build unix

package main

import (
	"context"
	"os"
	"path/filepath"
	"syscall"
	"testing"
	"time"
)

// 비밀 파일 자리의 FIFO 는 기동을 멈추지 않고 설정 오류로 끝난다 — 쓰는 쪽이 없는 FIFO 를 막는 열기로 열면 기동이
// 영원히 멈춘다. 시험 안 상한은 2초다.
func TestRunRejectsSecretFIFOWithoutBlocking(t *testing.T) {
	keepDefaultLogger(t)
	path := filepath.Join(secretDir(t), "fifo")
	if err := syscall.Mkfifo(path, 0o600); err != nil {
		t.Fatalf("Setup: mkfifo: %v", err)
	}
	env := configEnv()
	delete(env, "MTX_API_PASS")
	env["MTX_API_PASS_FILE"] = path
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	done := make(chan int, 1)
	go func() { done <- run(ctx, newTestSite(t, ""), nil, getenvFrom(env), newOutputLog(), newOutputLog()) }()
	select {
	case code := <-done:
		if code != 2 {
			t.Errorf("run with a FIFO as MTX_API_PASS_FILE = %d, want 2", code)
		}
	case <-time.After(2 * time.Second):
		// 막힌 열기를 풀어 고루틴이 남지 않게 한다 — O_RDWR 열기는 FIFO 에서 막히지 않고 쓰는 쪽을 붙인다.
		if f, err := os.OpenFile(path, os.O_RDWR, 0); err == nil {
			_ = f.Close()
		}
		t.Fatal("run blocked over 2s on a FIFO as MTX_API_PASS_FILE — open it without blocking")
	}
}
