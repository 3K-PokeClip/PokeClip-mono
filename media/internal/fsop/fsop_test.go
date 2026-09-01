package fsop

import (
	"errors"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func discardLog() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func TestStatTNormalPathsPassThrough(t *testing.T) {
	// Arrange: 실재 파일 하나와 부재 경로 하나.
	dir := t.TempDir()
	p := filepath.Join(dir, "a.mp4")
	if err := os.WriteFile(p, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}

	// Act + Assert: 성공은 FileInfo, 정상 실패는 os 에러 그대로(ErrStalled 아님).
	fi, err := StatT(p, time.Second)
	if err != nil || fi.Size() != 1 {
		t.Fatalf("정상 stat 이 실패했다: fi=%v err=%v", fi, err)
	}
	_, err = StatT(filepath.Join(dir, "none"), time.Second)
	if !os.IsNotExist(err) {
		t.Fatalf("부재 경로는 os.IsNotExist 여야 한다: %v", err)
	}
	if errors.Is(err, ErrStalled) {
		t.Fatal("정상 실패가 ErrStalled 로 뭉개졌다 — 계약 3항 위반")
	}
}

func TestProbeTTimesOutAndDistinguishes(t *testing.T) {
	// Arrange: 영원히 안 돌아오는 프로브(멈춘 FS 의 대역).
	block := make(chan struct{})
	t.Cleanup(func() { close(block) }) // 버려진 워커를 테스트 종료 때 풀어 준다
	hung := func(string) (int64, error) { <-block; return 0, nil }

	// Act
	start := time.Now()
	_, err := ProbeT("/hung", 30*time.Millisecond, hung)

	// Assert: 상한 안에 ErrStalled 로 돌아온다.
	if !errors.Is(err, ErrStalled) {
		t.Fatalf("타임아웃이 ErrStalled 가 아니다: %v", err)
	}
	if waited := time.Since(start); waited > time.Second {
		t.Fatalf("상한을 넘겨 기다렸다: %s", waited)
	}

	// 정상 프로브는 값 그대로.
	d, err := ProbeT("/ok", time.Second, func(string) (int64, error) { return 4000, nil })
	if err != nil || d != 4000 {
		t.Fatalf("정상 프로브 통과 실패: d=%d err=%v", d, err)
	}
}

func TestLatchTripAndReset(t *testing.T) {
	l := NewLatch(discardLog())
	if l.Tripped() {
		t.Fatal("초기 상태가 트립이다")
	}

	l.Trip("/p", "measure")
	l.Trip("/other", "hook") // 멱등 — 최초 좌표 유지
	if !l.Tripped() {
		t.Fatal("Trip 후에도 미트립이다")
	}

	// 응답하는 root 로 Reset → 해제.
	root := t.TempDir()
	if !l.Reset(root, time.Second) || l.Tripped() {
		t.Fatal("응답하는 FS 에서 Reset 이 해제하지 못했다")
	}

	// 미트립 상태의 Reset 은 프로브 없이 참이다.
	if !l.Reset(root, time.Nanosecond) {
		t.Fatal("미트립 Reset 이 거짓이다")
	}
}
