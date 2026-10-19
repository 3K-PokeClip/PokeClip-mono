package fsop

import (
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// openTestRoot 는 임시 디렉터리를 루트로 연다. 루트는 t.Cleanup 이 닫는다.
func openTestRoot(t *testing.T) (*os.Root, string) {
	t.Helper()
	dir := t.TempDir()
	root, err := os.OpenRoot(dir)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { root.Close() })
	return root, dir
}

func TestRemoveWithTimesOutAndDistinguishes(t *testing.T) {
	// Arrange: 풀어 줄 때까지 돌아오지 않는 삭제(멈춘 FS 의 대역 — ProbeT 시험과 같은 형상).
	block := make(chan struct{})
	t.Cleanup(func() { close(block) }) // 버려진 워커를 시험 종료 때 풀어 준다
	hung := func(string) error { <-block; return nil }

	// Act
	start := time.Now()
	err := removeWith(hung, "a/seg.mp4", 30*time.Millisecond)

	// Assert: 상한 안에 ErrStalled 로 돌아온다(계약 3항).
	if !errors.Is(err, ErrStalled) {
		t.Fatalf("멈춘 삭제 = %v, want ErrStalled", err)
	}
	if waited := time.Since(start); waited > time.Second {
		t.Fatalf("상한을 넘겨 기다렸다: %s", waited)
	}

	// 정상 실패는 그 오류 그대로다 — ErrStalled 로 뭉개지지 않는다.
	errEIO := errors.New("입출력 오류")
	if err := removeWith(func(string) error { return errEIO }, "x", time.Second); !errors.Is(err, errEIO) || errors.Is(err, ErrStalled) {
		t.Fatalf("정상 실패 = %v, want errEIO(ErrStalled 아님)", err)
	}
}

func TestRemoveTRemovesFileInsideRoot(t *testing.T) {
	root, dir := openTestRoot(t)
	if err := os.MkdirAll(filepath.Join(dir, "a"), 0o755); err != nil {
		t.Fatal(err)
	}
	p := filepath.Join(dir, "a", "seg.mp4")
	if err := os.WriteFile(p, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}

	if err := RemoveT(root, "a/seg.mp4", time.Second); err != nil {
		t.Fatalf("RemoveT = %v, want nil", err)
	}
	if _, err := os.Stat(p); !errors.Is(err, fs.ErrNotExist) {
		t.Fatalf("삭제 뒤 stat = %v, want 파일 없음", err)
	}
}

// D38 — MediaMTX 가 빈 스트림 디렉터리를 지운 뒤에는 부모 디렉터리부터 없다. 그때도 「파일 없음」
// 이어야 호출자가 나이 규칙(K7)으로 판정한다 — 다른 오류로 오면 영구 실패로 분류된다.
func TestRemoveTReportsNotExistForMissingFileAndMissingParent(t *testing.T) {
	root, dir := openTestRoot(t)
	if err := os.MkdirAll(filepath.Join(dir, "a"), 0o755); err != nil {
		t.Fatal(err)
	}

	for _, name := range []string{"a/none.mp4", "gone/seg.mp4"} {
		err := RemoveT(root, name, time.Second)
		if !errors.Is(err, fs.ErrNotExist) || errors.Is(err, ErrStalled) {
			t.Errorf("RemoveT(%q) = %v, want fs.ErrNotExist(ErrStalled 아님)", name, err)
		}
	}
}

// D14c — 중간 디렉터리가 루트 밖을 가리키는 심링크면 거부한다. 밖의 파일은 무사해야 한다.
func TestRemoveTRefusesIntermediateSymlinkEscape(t *testing.T) {
	root, dir := openTestRoot(t)
	outside := t.TempDir()
	victim := filepath.Join(outside, "seg.mp4")
	if err := os.WriteFile(victim, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, filepath.Join(dir, "a")); err != nil {
		t.Fatal(err)
	}

	if err := RemoveT(root, "a/seg.mp4", time.Second); err == nil {
		t.Fatal("루트 밖으로 가는 심링크 경로의 삭제가 성공했다")
	}
	if _, err := os.Stat(victim); err != nil {
		t.Fatalf("루트 밖 파일이 사라졌다: %v", err)
	}
}

// Q1(t2 r1) — 루트 안의 심링크도 따라가지 않는다. 스트림 디렉터리 s1 이 같은 루트의 s2 를 가리키면
// os.Root 는 루트 밖이 아니라서 통과시킨다(t2r1-osroot-probe 실측) — 그러면 s1 행이 s2 파일을 지운다.
func TestRemoveTRefusesSymlinkedDirectoryInsideRoot(t *testing.T) {
	root, dir := openTestRoot(t)
	if err := os.MkdirAll(filepath.Join(dir, "s2"), 0o755); err != nil {
		t.Fatal(err)
	}
	victim := filepath.Join(dir, "s2", "seg.mp4")
	if err := os.WriteFile(victim, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink("s2", filepath.Join(dir, "s1")); err != nil {
		t.Fatal(err)
	}

	err := RemoveT(root, "s1/seg.mp4", time.Second)

	if _, serr := os.Stat(victim); serr != nil {
		t.Fatalf("s1 → s2 심링크를 따라가 다른 스트림 파일을 지웠다(RemoveT = %v · stat = %v)", err, serr)
	}
	if !errors.Is(err, ErrSymlinkInPath) {
		t.Fatalf("RemoveT = %v, want ErrSymlinkInPath", err)
	}
}

// Q1(t2 r1) — Lstat 과 OpenRoot 사이에 디렉터리가 심링크로 바뀌는 경쟁도 거부한다(os.SameFile 대조).
// 다른 고루틴이 s1 을 「빈 디렉터리 ↔ s2 를 가리키는 심링크」로 계속 바꾸는 동안 s1/<이름> 삭제를 되풀이해도
// s2 의 파일은 남아야 한다. 원본은 경쟁 창이 열려도 SameFile 이 거부하거나 연 디렉터리(빈 s1) 안에서만
// 지우므로 결정적으로 통과한다(변이 — SameFile 대조 제거 → 경쟁 창에서 s2 파일이 지워진다).
func TestRemoveTRefusesDirectorySwappedForSymlinkAfterCheck(t *testing.T) {
	root, dir := openTestRoot(t)
	const name = "seg.mp4"
	if err := os.MkdirAll(filepath.Join(dir, "s2"), 0o755); err != nil {
		t.Fatal(err)
	}
	victim := filepath.Join(dir, "s2", name)
	if err := os.WriteFile(victim, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	s1, asDir, asLink := filepath.Join(dir, "s1"), filepath.Join(dir, "s1-dir"), filepath.Join(dir, "s1-link")
	if err := os.Mkdir(asDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink("s2", asLink); err != nil {
		t.Fatal(err)
	}
	stop := make(chan struct{})
	var swapper sync.WaitGroup
	swapper.Go(func() {
		for {
			select {
			case <-stop:
				return
			default:
			}
			// 실패(이름이 잠시 없음)는 무시한다 — s1 이 디렉터리 · 심링크 · 부재를 오가는 것이 목적이다.
			_ = os.Rename(asDir, s1)
			_ = os.Rename(s1, asDir)
			_ = os.Rename(asLink, s1)
			_ = os.Rename(s1, asLink)
		}
	})
	defer swapper.Wait()
	defer close(stop)

	// 경쟁 창은 마이크로초라 한 번에 맞히기 어렵다 — 삭제를 여럿이 동시에 되풀이해 시도 수를 늘린다.
	deadline := time.Now().Add(3 * time.Second)
	var removers sync.WaitGroup
	var hit atomic.Bool
	for range swapRemovers {
		removers.Go(func() {
			for !hit.Load() && time.Now().Before(deadline) {
				_ = RemoveT(root, filepath.Join("s1", name), time.Second)
				if _, err := os.Stat(victim); err != nil {
					hit.Store(true)
				}
			}
		})
	}
	removers.Wait()
	if hit.Load() {
		t.Fatal("검사 뒤 심링크로 바뀐 s1 을 따라가 s2 파일을 지웠다")
	}
}

// swapRemovers 는 경쟁 시험에서 동시에 삭제를 되풀이하는 고루틴 수다.
const swapRemovers = 4
