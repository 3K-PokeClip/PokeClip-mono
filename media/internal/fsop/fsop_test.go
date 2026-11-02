package fsop

import (
	"bytes"
	"errors"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
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

// fs_degraded 의 세움(Trip)과 내림(Reset)은 같은 ERROR 다.
//
// 잡는 결함: 내림이 INFO 로 내려가면 LOG_LEVEL=error 운영에서는 세움만 보이고 해소가
// 보이지 않아 게이지가 영원히 1 로 읽힌다(set/clear 같은 등급 — rewind_cutoff_absent 선례).
func TestLatchDegradedSignalKeepsErrorLevelOnClear(t *testing.T) {
	var buf bytes.Buffer
	log := slog.New(slog.NewTextHandler(&buf, &slog.HandlerOptions{Level: slog.LevelError}))
	l := NewLatch(log)

	l.Trip("/p", "measure")
	if !l.Reset(t.TempDir(), time.Second) {
		t.Fatal("응답하는 FS 에서 Reset 이 해제하지 못했다")
	}

	var got []string
	for _, line := range strings.Split(strings.TrimSpace(buf.String()), "\n") {
		if strings.Contains(line, "msg=fs_degraded ") {
			got = append(got, line)
		}
	}
	if len(got) != 2 {
		t.Fatalf("ERROR 만 받을 때 fs_degraded = %d줄, want 2(세움 · 내림): %q", len(got), buf.String())
	}
	for i, want := range []string{"value=1", "value=0"} {
		if !strings.Contains(got[i], "level=ERROR") || !strings.Contains(got[i], want) {
			t.Errorf("fs_degraded %d번째 줄 = %q, want level=ERROR %s", i+1, got[i], want)
		}
	}
}

// writeTemp 는 내용이 정해진 임시 파일을 만든다.
func writeTemp(t *testing.T, content string) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), "seg.mp4")
	if err := os.WriteFile(p, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	return p
}

func TestReadTOpensPassesReaderAndCloses(t *testing.T) {
	// Arrange: 판독 함수는 받은 리더를 붙잡아 둔다 — 반환 뒤 닫혔는지 보려고.
	p := writeTemp(t, "mtxi-header")
	var got io.ReadSeeker
	read := func(r io.ReadSeeker) (string, error) {
		got = r
		b, err := io.ReadAll(r)
		return string(b), err
	}

	// Act
	v, err := ReadT(p, time.Second, read)

	// Assert: 값은 그대로, 파일은 fsop 가 닫았다(계약 2항 — 핸들을 시간 경계 너머로 넘기지 않는다).
	if err != nil || v != "mtxi-header" {
		t.Fatalf("ReadT = %q, %v; want %q, nil", v, err, "mtxi-header")
	}
	if _, err := got.Read(make([]byte, 1)); !errors.Is(err, os.ErrClosed) {
		t.Errorf("반환 뒤 리더 읽기 = %v, want os.ErrClosed — 파일이 열린 채 남았다", err)
	}
}

func TestReadTTimesOutAndLateWorkerClosesFile(t *testing.T) {
	// Arrange: 멈춘 FS 의 대역 — 풀어 줄 때까지 돌아오지 않는 판독.
	p := writeTemp(t, "x")
	release, done := make(chan struct{}), make(chan io.ReadSeeker, 1)
	hung := func(r io.ReadSeeker) (int, error) {
		<-release
		done <- r
		return 1, nil
	}

	// Act
	start := time.Now()
	_, err := ReadT(p, 30*time.Millisecond, hung)

	// Assert: 상한 안에 ErrStalled 로 돌아온다(계약 3항).
	if !errors.Is(err, ErrStalled) {
		t.Fatalf("타임아웃이 ErrStalled 가 아니다: %v", err)
	}
	if waited := time.Since(start); waited > time.Second {
		t.Fatalf("상한을 넘겨 기다렸다: %s", waited)
	}

	// 버려진 워커는 끝까지 가서 fd 를 회수한다 — 늦은 송신이 막히지 않고(버퍼 1) 파일을 닫는다.
	close(release)
	r := <-done
	deadline := time.Now().Add(time.Second)
	for {
		_, err := r.Read(make([]byte, 1))
		if errors.Is(err, os.ErrClosed) {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("늦은 워커가 파일을 닫지 않았다: 마지막 읽기 = %v", err)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func TestReadTPropagatesOpenAndReadFailures(t *testing.T) {
	errBadHeader := errors.New("머리말 해석 실패")
	called := false
	read := func(io.ReadSeeker) (int, error) {
		called = true
		return 0, errBadHeader
	}

	// 열기 실패는 os 오류 그대로이고 판독 함수는 불리지 않는다.
	_, err := ReadT(filepath.Join(t.TempDir(), "none.mp4"), time.Second, read)
	if !os.IsNotExist(err) || errors.Is(err, ErrStalled) {
		t.Errorf("열기 실패 = %v, want os.IsNotExist(ErrStalled 아님)", err)
	}
	if called {
		t.Error("열지 못한 파일로 판독 함수를 불렀다")
	}

	// 판독 실패는 판독 함수의 오류 그대로다 — 정상 실패가 ErrStalled 로 뭉개지지 않는다(계약 3항).
	_, err = ReadT(writeTemp(t, "x"), time.Second, read)
	if !errors.Is(err, errBadHeader) || errors.Is(err, ErrStalled) {
		t.Errorf("판독 실패 = %v, want errBadHeader(ErrStalled 아님)", err)
	}
}
