//go:build unix

package secretfile

import (
	"errors"
	"os"
	"path/filepath"
	"syscall"
	"testing"
	"time"
)

// FIFO 는 막히지 않고 거부한다. 쓰는 쪽이 없는 FIFO 를 막는 열기(O_RDONLY 만)로 열면 기동이 영원히 멈춘다.
// 비차단으로 열고 fstat 으로 가르므로, 확인과 열기 사이에 경로가 FIFO 로 바뀌는 경쟁의 결과 상태도 이
// 시험 하나가 판별한다(열 때 경로가 FIFO 인 것과 자극이 같다).
func TestReadSecretFileRejectsFIFOWithoutBlocking(t *testing.T) {
	path := filepath.Join(t.TempDir(), "fifo")
	if err := syscall.Mkfifo(path, 0o600); err != nil {
		t.Fatalf("FIFO 생성 실패: %v", err)
	}

	done := make(chan error, 1)
	go func() {
		_, err := Read(secretEnv, path, Options{ShowPath: true})
		done <- err
	}()

	select {
	case err := <-done:
		if !errors.Is(err, ErrNotRegularFile) {
			t.Errorf("FIFO 거부 사유가 일반 파일 검사가 아니다: %v", err)
		}
	case <-time.After(2 * time.Second):
		unblockFIFO(t, path, func() {}, done)
		t.Fatal("FIFO 에서 2초 넘게 막혔다 — 비차단으로 열어야 한다")
	}
}

// 비밀번호 한 줄이 들어 있는 FIFO 도 거부하고, 사유가 일반 파일 검사 갈래인지까지 본다. 쓰는 쪽 없는 빈
// FIFO(위 시험)는 이 검사가 없어도 빈 내용 거부가 대신 걸려 검사의 유무를 가리지 못한다. 그래서 바이트를
// 먼저 써 둔 FIFO 두 가지를 쓴다. 이 검사를 지운 트리에서 재면:
//   - 쓰는 쪽이 붙어 있음: "pw\n" 을 읽은 뒤 다음 읽기가 darwin 에서는 EAGAIN(읽기 에러로 거부되지만
//     사유가 다르다), linux 에서는 막혀 2초 상한으로 FAIL 한다(Go 가 linux 에서는 FIFO 를 epoll 에
//     올려 EAGAIN 을 돌려주지 않고 기다린다).
//   - 쓰는 쪽은 떠나고 바이트만 남음(다른 읽는 쪽이 FIFO 를 붙잡아 버퍼가 남는다): 두 플랫폼 모두
//     "pw\n" 뒤 EOF — 비밀번호 "pw" 로 받아들여진다. 이 검사만이 막는 자극이다.
//
// 비차단 열기를 지운 트리(M22)에서는 둘째 사례의 열기가 쓰는 쪽을 기다리며 막혀 2초 상한으로 FAIL 한다.
func TestReadSecretFileRejectsFIFOHoldingPassword(t *testing.T) {
	for _, c := range []struct {
		name        string
		writerStays bool
	}{
		{name: "쓰는 쪽이 붙어 있음", writerStays: true},
		{name: "쓰는 쪽은 떠나고 바이트만 남음", writerStays: false},
	} {
		t.Run(c.name, func(t *testing.T) {
			path, release := fifoHoldingPassword(t, c.writerStays)

			done := make(chan error, 1)
			go func() {
				_, err := Read(secretEnv, path, Options{ShowPath: true})
				done <- err
			}()

			select {
			case err := <-done:
				if !errors.Is(err, ErrNotRegularFile) {
					t.Errorf("FIFO 거부 사유가 일반 파일 검사가 아니다(받아들였으면 nil): %v", err)
				}
			case <-time.After(2 * time.Second):
				unblockFIFO(t, path, release, done)
				t.Fatal("FIFO 에서 2초 넘게 막혔다 — 비차단으로 열고 일반 파일 검사로 읽기 전에 거부해야 한다")
			}
		})
	}
}

// fifoHoldingPassword 는 "pw\n" 이 버퍼에 들어 있는 FIFO 를 만든다. O_RDWR 는 FIFO 에서 막히지 않고
// 쓰는 쪽으로 남는다. writerStays 가 거짓이면 비차단 읽는 쪽 하나로 FIFO 를 붙잡은 채 쓰는 쪽을 닫는다 —
// 열린 끝이 하나라도 있으면 버퍼는 남고, 쓰는 쪽이 없으니 다음 읽는 쪽은 바이트 뒤에 EOF 를 본다.
// release 는 남은 쓰는 쪽을 닫는다(없으면 아무것도 안 한다). 남은 손잡이는 시험이 끝날 때 닫힌다.
func fifoHoldingPassword(t *testing.T, writerStays bool) (path string, release func()) {
	t.Helper()
	path = filepath.Join(t.TempDir(), "fifo")
	if err := syscall.Mkfifo(path, 0o600); err != nil {
		t.Fatalf("FIFO 생성 실패: %v", err)
	}
	w, err := os.OpenFile(path, os.O_RDWR, 0)
	if err != nil {
		t.Fatalf("FIFO 쓰는 쪽 열기 실패: %v", err)
	}
	t.Cleanup(func() { _ = w.Close() })
	if _, err := w.Write([]byte("pw\n")); err != nil {
		t.Fatalf("FIFO 쓰기 실패: %v", err)
	}
	if writerStays {
		return path, func() { _ = w.Close() }
	}
	hold, err := os.OpenFile(path, os.O_RDONLY|syscall.O_NONBLOCK, 0)
	if err != nil {
		t.Fatalf("FIFO 붙잡는 읽는 쪽 열기 실패: %v", err)
	}
	t.Cleanup(func() { _ = hold.Close() })
	if err := w.Close(); err != nil {
		t.Fatalf("FIFO 쓰는 쪽 닫기 실패: %v", err)
	}
	return path, func() {}
}

// unblockFIFO 는 시간 초과로 막힌 Read 을 풀고 끝나기를 기다린다 — 막는 코드에서도 고루틴이
// 남지 않게 하는 정리다. O_RDWR 열기는 FIFO 에서 막히지 않고 쓰는 쪽을 붙여 막힌 열기를 풀고, release 는
// 남은 쓰는 쪽을 닫아 막힌 읽기를 EOF 로 끝낸다. 그래도 2초 안에 끝나지 않으면 더 기다리지 않는다 —
// 고루틴 하나가 남는 편이 패키지 전체가 시간 초과로 멈추는 것보다 낫다.
func unblockFIFO(t *testing.T, path string, release func(), done <-chan error) {
	t.Helper()
	if f, err := os.OpenFile(path, os.O_RDWR, 0); err == nil {
		_ = f.Close()
	}
	release()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Error("정리 뒤에도 Read 이 2초 안에 끝나지 않았다 — 고루틴 하나를 남기고 넘어간다")
	}
}
