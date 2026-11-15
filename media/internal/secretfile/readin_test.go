//go:build unix

package secretfile

// 루트 안 이름으로 읽기(ReadIn — POK-574) 시험. 파일 내용은 모두 합성이다. FIFO 사례가 secretfile_unix_test.go
// 의 도우미를 쓰므로 이 파일도 unix 에서만 돈다. 실패 문구에는 파일 내용을 싣지 않는다(ADR-018).

import (
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// openTestRoot 는 dir 을 os.Root 로 열고 시험이 끝날 때 닫는다.
func openTestRoot(t *testing.T, dir string) *os.Root {
	t.Helper()
	root, err := os.OpenRoot(dir)
	if err != nil {
		t.Fatalf("루트 열기 실패: %v", err)
	}
	t.Cleanup(func() { _ = root.Close() })
	return root
}

// ReadIn 은 Read 와 같은 검사(일반 파일 · other 비트 · group 쓰기 · 4096 바이트 · 한 줄)를 루트 안 이름에
// 건다. 루트 밖으로 나가는 이름(`..` · 루트 밖을 가리키는 링크)은 os.Root 가 거부한다. 탈출 두 사례는 루트
// 밖에 받아들일 만한 비밀번호 파일을 실제로 둔다 — 경로를 이어 붙여 여는 구현이면 그 파일을 읽어 받아들인다.
// 루트 안 다른 이름을 가리키는 링크도 거부한다(ErrLinkName) — 대상(ok)은 받아들일 만한 파일이라, 연 파일과
// 이름을 대조하지 않는 구현이면 받아들인다. 여는 사이 이름이 링크로 바뀌는 경합의 결과 상태도 이 사례와 같다
// (연 파일 ≠ 이름이 가리키는 것). 링크 대조는 마지막 요소만이다 — 중간 요소가 루트 안 링크면 따라가 받는다.
func TestReadInAppliesReadChecksInsideRoot(t *testing.T) {
	parent := t.TempDir()
	writeSecret(t, parent, "outside", "pw\n", 0o400)
	dir := filepath.Join(parent, "root")
	if err := os.Mkdir(dir, 0o700); err != nil {
		t.Fatalf("시험 폴더 생성 실패: %v", err)
	}
	if err := os.Mkdir(filepath.Join(dir, "sub"), 0o700); err != nil {
		t.Fatalf("시험 폴더 생성 실패: %v", err)
	}
	if err := os.Symlink(filepath.Join("..", "outside"), filepath.Join(dir, "escape")); err != nil {
		t.Fatalf("링크 생성 실패: %v", err)
	}
	writeSecret(t, dir, "ok", "pw\n", 0o400)
	if err := os.Symlink("ok", filepath.Join(dir, "alias")); err != nil {
		t.Fatalf("링크 생성 실패: %v", err)
	}
	writeSecret(t, filepath.Join(dir, "sub"), "inner", "pw\n", 0o400)
	if err := os.Symlink("sub", filepath.Join(dir, "via")); err != nil {
		t.Fatalf("링크 생성 실패: %v", err)
	}
	writeSecret(t, dir, "group-read", "pw\n", 0o440)
	writeSecret(t, dir, "other", "pw\n", 0o604)
	writeSecret(t, dir, "group-write", "pw\n", 0o620)
	writeSecret(t, dir, "big", strings.Repeat("x", 4097), 0o600)
	writeSecret(t, dir, "two-lines", "pw\nmore\n", 0o600)
	root := openTestRoot(t, dir)

	for _, c := range []struct {
		name, file, desc string
		want             string
		wantErr          bool
		wantIs           error // nil 이면 사유는 보지 않는다(이름 붙은 에러가 없는 갈래)
	}{
		{name: "regular", file: "ok", desc: "0400 한 줄", want: "pw"},
		{name: "group_read", file: "group-read", desc: "0440 한 줄", want: "pw"},
		{name: "directory", file: "sub", desc: "디렉터리", wantErr: true, wantIs: ErrNotRegularFile},
		{name: "other_bit", file: "other", desc: "other 읽기 비트", wantErr: true},
		{name: "group_write", file: "group-write", desc: "group 쓰기 비트", wantErr: true},
		{name: "over_4096", file: "big", desc: "4097 바이트", wantErr: true},
		{name: "two_lines", file: "two-lines", desc: "두 줄", wantErr: true},
		{name: "missing", file: "missing", desc: "없는 이름", wantErr: true, wantIs: fs.ErrNotExist},
		{name: "dotdot_escape", file: filepath.Join("..", "outside"), desc: "루트 밖 .. 이름", wantErr: true},
		{name: "link_escape", file: "escape", desc: "루트 밖을 가리키는 링크", wantErr: true},
		{name: "link_inside", file: "alias", desc: "루트 안 다른 이름을 가리키는 링크", wantErr: true, wantIs: ErrLinkName},
		{name: "link_in_middle", file: filepath.Join("via", "inner"), desc: "중간 요소가 루트 안 링크", want: "pw"},
	} {
		t.Run(c.name, func(t *testing.T) {
			got, err := ReadIn(root, secretEnv, c.file, Options{ShowPath: true})
			if gotErr := err != nil; gotErr != c.wantErr {
				t.Fatalf("%s: ReadIn 에러 %v, want 에러 있음 = %v", c.desc, err, c.wantErr)
			}
			if !c.wantErr {
				if got != c.want {
					t.Errorf("%s: 반환값이 기대와 다르다(길이 %d, want 길이 %d)", c.desc, len(got), len(c.want))
				}
				return
			}
			if c.wantIs != nil && !errors.Is(err, c.wantIs) {
				t.Errorf("%s: 거부 사유가 다르다: %v, want errors.Is(%v)", c.desc, err, c.wantIs)
			}
			if !strings.Contains(err.Error(), secretEnv) {
				t.Errorf("%s: 에러에 env 이름이 없다: %v", c.desc, err)
			}
		})
	}
}

// FIFO 는 막히지 않고 일반 파일 검사 갈래로 거부한다. 쓰는 쪽은 떠나고 바이트만 남은 FIFO 를 쓴다 — 이 검사가
// 없으면 "pw" 로 받아들여지는 자극이다(secretfile_unix_test.go 의 같은 사례 설명). 비차단으로 열지 않으면
// 쓰는 쪽을 기다리며 막혀 2초 상한으로 FAIL 한다.
func TestReadInRejectsFIFOWithoutBlocking(t *testing.T) {
	path, release := fifoHoldingPassword(t, false)
	root := openTestRoot(t, filepath.Dir(path))

	done := make(chan error, 1)
	go func() {
		_, err := ReadIn(root, secretEnv, filepath.Base(path), Options{ShowPath: true})
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
}

// 경로를 싣는 규칙은 Read 와 같다. 열기 실패는 ShowPath 와 무관하게 경로를 싣지 않고 원인만 싣는다 — 열기
// 실패 사례는 없는 파일 이름에 카나리아를 넣는다(os.Root 의 열기 에러는 루트 이름이 아니라 그 이름을 싣는다).
// 열린 뒤의 거부는 ShowPath 가 참일 때만 루트 경로와 이름을 싣는다 — 루트 디렉터리 이름의 카나리아로 본다.
// 실패 문구에는 원문 대신 「카나리아 발견」 만 적는다.
func TestReadInPathDisclosureFollowsShowPath(t *testing.T) {
	const canary = "rootcanary3w"
	const missing = "namecanary7k-missing"
	dir := filepath.Join(t.TempDir(), canary)
	if err := os.Mkdir(dir, 0o700); err != nil {
		t.Fatalf("시험 폴더 생성 실패: %v", err)
	}
	writeSecret(t, dir, "other", "pw\n", 0o604)
	root := openTestRoot(t, dir)

	for _, c := range []struct {
		name, file string
		showPath   bool
		wantPath   bool
	}{
		{name: "open_fail_shown", file: missing, showPath: true},
		{name: "open_fail_hidden", file: missing},
		{name: "opened_shown", file: "other", showPath: true, wantPath: true},
		{name: "opened_hidden", file: "other"},
	} {
		t.Run(c.name, func(t *testing.T) {
			_, err := ReadIn(root, secretEnv, c.file, Options{ShowPath: c.showPath})
			if err == nil {
				t.Fatalf("ReadIn(%q, ShowPath %v) 이 받았다", c.file, c.showPath)
			}
			if got := strings.Contains(err.Error(), canary); got != c.wantPath {
				t.Errorf("ReadIn(%q, ShowPath %v): 에러에 경로 카나리아 발견 = %v, want %v", c.file, c.showPath, got, c.wantPath)
			}
			if strings.Contains(err.Error(), missing) {
				t.Errorf("ReadIn(%q, ShowPath %v): 열기 실패 에러에 이름 카나리아 발견", c.file, c.showPath)
			}
			if c.wantPath && !strings.Contains(err.Error(), filepath.Join(dir, c.file)) {
				t.Errorf("ReadIn(%q, ShowPath 참): 에러에 루트 경로와 이름을 이은 경로가 없다", c.file)
			}
			if !strings.Contains(err.Error(), secretEnv) {
				t.Errorf("ReadIn(%q, ShowPath %v): 에러에 env 이름이 없다", c.file, c.showPath)
			}
		})
	}
}
