package secretfile

// 비밀번호 파일 읽기(Read — POK-373) 시험. 파일 내용은 모두 합성이다.
// 실패 문구에는 파일 내용을 싣지 않는다(ADR-018) — 누설 시험은 「카나리아 발견」 만 적는다.

import (
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
)

// secretEnv 는 시험이 넘기는 env 이름이다 — 에러가 이 이름을 실어야 운영자가 어느 설정인지 안다.
const secretEnv = "MTX_API_PASS_FILE"

// writeSecret 은 dir 아래에 content 를 담은 파일을 mode 로 만든다. WriteFile 의 모드는 umask 를 거치므로
// Chmod 로 다시 박는다 — 그래야 other 비트 시험이 umask 에 따라 갈리지 않는다.
func writeSecret(t *testing.T, dir, name, content string, mode os.FileMode) string {
	t.Helper()
	path := filepath.Join(dir, name)
	if err := os.WriteFile(path, []byte(content), mode); err != nil {
		t.Fatalf("시험 파일 생성 실패: %v", err)
	}
	if err := os.Chmod(path, mode); err != nil {
		t.Fatalf("시험 파일 권한 설정 실패: %v", err)
	}
	return path
}

// 끝의 줄바꿈 하나(\n 또는 \r\n)만 걷는다 — `echo pw > file` 로 만든 파일이 401 영구 고장이 되지 않게.
// 앞뒤 공백 · ':' 는 비밀번호의 일부라 그대로 둔다(표준이 아니라 선택 — postgres 공식 이미지는 끝
// 줄바꿈을 전부 걷는다).
func TestReadSecretFileStripsOneTrailingNewline(t *testing.T) {
	dir := t.TempDir()
	for i, c := range []struct{ content, want string }{
		{content: "pw\n", want: "pw"},
		{content: "pw\r\n", want: "pw"},
		{content: "pw", want: "pw"},
		{content: " pw \n", want: " pw "},
		{content: "a:b c\n", want: "a:b c"},
	} {
		path := writeSecret(t, dir, "s"+string(rune('a'+i)), c.content, 0o600)
		got, err := Read(secretEnv, path, Options{ShowPath: true})
		if err != nil {
			t.Errorf("경우 %d: Read 에러 %v, want nil", i, err)
			continue
		}
		if got != c.want {
			t.Errorf("경우 %d: 반환값이 기대와 다르다(길이 %d, want 길이 %d)", i, len(got), len(c.want))
		}
	}
}

// 비면 · 여러 줄 · 제어 바이트(\r · NUL)가 섞이면 거부한다 — 빈 비밀번호로 Basic 을 싣거나 둘째 줄이
// 비밀번호에 섞이면 401 영구 고장이다. 끝 하나를 걷은 뒤 남은 줄바꿈도 거부한다. 크기 상한은 4096 바이트다
// (/dev/zero 같은 거대 입력이 메모리를 먹지 않게).
func TestReadSecretFileRejectsBadContent(t *testing.T) {
	dir := t.TempDir()
	for i, c := range []struct{ name, content string }{
		{name: "빈 파일", content: ""},
		{name: "줄바꿈뿐", content: "\n"},
		{name: "CRLF 뿐", content: "\r\n"},
		{name: "두 줄", content: "pw\nmore\n"},
		{name: "끝 줄바꿈 둘", content: "pw\n\n"},
		{name: "가운데 CR", content: "p\rw"},
		{name: "끝 CR 하나", content: "pw\r"},
		{name: "NUL", content: "p\x00w"},
		{name: "4097 바이트", content: strings.Repeat("x", 4097)},
	} {
		path := writeSecret(t, dir, "b"+string(rune('a'+i)), c.content, 0o600)
		if _, err := Read(secretEnv, path, Options{ShowPath: true}); err == nil {
			t.Errorf("%s: Read 이 받았다", c.name)
		}
	}
	path := writeSecret(t, dir, "max", strings.Repeat("x", 4096), 0o600)
	if got, err := Read(secretEnv, path, Options{ShowPath: true}); err != nil || len(got) != 4096 {
		t.Errorf("4096 바이트: 길이 %d, 에러 %v, want 4096 · nil", len(got), err)
	}
}

// other 권한 비트가 하나라도 있으면 거부한다 — 상자의 아무 계정이나 읽는 비밀번호 파일이다. group 은
// 읽기만 허용한다(root:10001 0440 배치 — 사이드카 UID 10001 이 group 으로 읽는다). group 쓰기는 아래 시험.
func TestReadSecretFileRejectsOtherPermissionBits(t *testing.T) {
	dir := t.TempDir()
	for _, mode := range []os.FileMode{0o644, 0o604, 0o602, 0o601, 0o444} {
		path := writeSecret(t, dir, "o"+mode.String(), "pw\n", mode)
		_, err := Read(secretEnv, path, Options{ShowPath: true})
		if err == nil {
			t.Errorf("모드 %#o: Read 이 받았다", mode)
			continue
		}
		if !strings.Contains(err.Error(), secretEnv) || !strings.Contains(err.Error(), path) {
			t.Errorf("모드 %#o: 에러에 env 이름이나 경로가 없다: %v", mode, err)
		}
	}
	for _, mode := range []os.FileMode{0o600, 0o400, 0o640, 0o440} {
		path := writeSecret(t, dir, "g"+mode.String(), "pw\n", mode)
		if _, err := Read(secretEnv, path, Options{ShowPath: true}); err != nil {
			t.Errorf("모드 %#o: Read 에러 %v, want nil", mode, err)
		}
	}
}

// group 쓰기 비트가 있으면 거부한다 — 같은 그룹의 계정이 비밀번호를 바꿔 끼울 수 있다. other 비트
// 거부와 같은 꼴이다. group 읽기(0640 · 0440)는 받는다.
func TestReadSecretFileRejectsGroupWriteBit(t *testing.T) {
	dir := t.TempDir()
	for _, mode := range []os.FileMode{0o660, 0o620} {
		path := writeSecret(t, dir, "w"+mode.String(), "pw\n", mode)
		_, err := Read(secretEnv, path, Options{ShowPath: true})
		if err == nil {
			t.Errorf("모드 %#o: Read 이 받았다", mode)
			continue
		}
		if !strings.Contains(err.Error(), secretEnv) || !strings.Contains(err.Error(), path) {
			t.Errorf("모드 %#o: 에러에 env 이름이나 경로가 없다: %v", mode, err)
		}
	}
	for _, mode := range []os.FileMode{0o640, 0o440} {
		path := writeSecret(t, dir, "r"+mode.String(), "pw\n", mode)
		if _, err := Read(secretEnv, path, Options{ShowPath: true}); err != nil {
			t.Errorf("모드 %#o: Read 에러 %v, want nil", mode, err)
		}
	}
}

// 일반 파일만 받는다 — 디렉터리 · 없는 파일 · 대상 없는 링크는 기동 실패다. 거부 사유까지 본다.
// 디렉터리는 일반 파일 검사 갈래(ErrNotRegularFile)여야 한다 — 그 검사를 지운 트리에서는 다른 거부가 대신
// 걸려 「거부됐다」 만으로는 검사가 있는지 가려지지 않는다(t.TempDir 의 하위 폴더는 0755 라 darwin ·
// linux 모두 other 비트 거부에 걸린다 — 실측). 없는 파일 · 대상 없는 링크는 열기에서 끝나므로(fstat 전)
// 사유는 「없음」 이고, 열기 실패라 경로를 싣지 않는다(아래 TestReadSecretFileOpenErrorOmitsPath).
func TestReadSecretFileRejectsNonRegular(t *testing.T) {
	dir := t.TempDir()
	dangling := filepath.Join(dir, "dangling")
	if err := os.Symlink(filepath.Join(dir, "none"), dangling); err != nil {
		t.Fatalf("링크 생성 실패: %v", err)
	}
	for _, c := range []struct {
		name, path string
		want       error
		wantPath   bool
	}{
		{name: "디렉터리", path: dir, want: ErrNotRegularFile, wantPath: true},
		{name: "없는 파일", path: filepath.Join(dir, "missing"), want: fs.ErrNotExist},
		{name: "대상 없는 링크", path: dangling, want: fs.ErrNotExist},
	} {
		_, err := Read(secretEnv, c.path, Options{ShowPath: true})
		if err == nil {
			t.Errorf("%s: Read 이 받았다", c.name)
			continue
		}
		if !errors.Is(err, c.want) {
			t.Errorf("%s: 거부 사유가 다르다: %v, want errors.Is(%v)", c.name, err, c.want)
		}
		if !strings.Contains(err.Error(), secretEnv) {
			t.Errorf("%s: 에러에 env 이름이 없다: %v", c.name, err)
		}
		if got := strings.Contains(err.Error(), c.path); got != c.wantPath {
			t.Errorf("%s: 에러에 경로가 실렸나 = %v, want %v: %v", c.name, got, c.wantPath, err)
		}
	}
}

// 열기 실패는 경로를 싣지 않고 원인만 싣는다 — 비밀번호를 MTX_API_PASS_FILE 자리에 잘못 넣으면 그 값이 곧
// 경로다. '/' 로 시작하는 값은 config 의 절대 경로 검사를 지나 여기까지 오고, *fs.PathError 를 그대로
// 감싸면 값이 stderr 로 나간다. 원인 판별(errors.Is(fs.ErrNotExist))은 살아 있어야 한다. 실패 문구에는
// 원문 대신 「카나리아 발견」 만 적는다.
func TestReadSecretFileOpenErrorOmitsPath(t *testing.T) {
	const canary = "opencanary7q"
	_, err := Read(secretEnv, "/"+canary, Options{ShowPath: true})
	if err == nil {
		t.Fatal("Read 이 받았다")
	}
	if strings.Contains(err.Error(), canary) {
		t.Error("열기 실패 에러에 카나리아 발견")
	}
	if !errors.Is(err, fs.ErrNotExist) {
		t.Error("열기 실패의 원인이 사라졌다 — errors.Is(fs.ErrNotExist) 가 거짓이다")
	}
	if !strings.Contains(err.Error(), secretEnv) {
		t.Error("열기 실패 에러에 env 이름이 없다")
	}
}

// 대상이 일반 파일인 심볼릭 링크는 받는다 — 비밀 마운트(쿠버네티스 · 일부 런타임)는 링크로 놓인다.
func TestReadSecretFileFollowsSymlinkToRegularFile(t *testing.T) {
	dir := t.TempDir()
	target := writeSecret(t, dir, "target", "pw\n", 0o400)
	link := filepath.Join(dir, "link")
	if err := os.Symlink(target, link); err != nil {
		t.Fatalf("링크 생성 실패: %v", err)
	}
	got, err := Read(secretEnv, link, Options{ShowPath: true})
	if err != nil || got != "pw" {
		t.Errorf("링크: 길이 %d, 에러 %v, want 길이 2 · nil", len(got), err)
	}
}

// 거부 에러에는 내용이 없다 — env 이름 · 경로 · 모드만. 내용이 거부 사유인 갈래(여러 줄 · NUL · 크기)에서도
// 같다. 실패 문구에는 원문 대신 「카나리아 발견」 만 적는다.
func TestReadSecretFileErrorsCarryNoContent(t *testing.T) {
	const canary = "filecanary9z"
	dir := t.TempDir()
	for i, c := range []struct {
		name, content string
		mode          os.FileMode
	}{
		{name: "두 줄", content: canary + "\n" + canary + "\n", mode: 0o600},
		{name: "NUL", content: canary + "\x00" + canary, mode: 0o600},
		{name: "크기 초과", content: strings.Repeat(canary, 400), mode: 0o600},
		{name: "other 비트", content: canary + "\n", mode: 0o644},
	} {
		path := writeSecret(t, dir, "c"+string(rune('a'+i)), c.content, c.mode)
		_, err := Read(secretEnv, path, Options{ShowPath: true})
		if err == nil {
			t.Errorf("%s: Read 이 받았다", c.name)
			continue
		}
		if strings.Contains(err.Error(), canary) {
			t.Errorf("%s: 에러에 카나리아 발견", c.name)
		}
		if !strings.Contains(err.Error(), secretEnv) || !strings.Contains(err.Error(), path) {
			t.Errorf("%s: 에러에 env 이름이나 경로가 없다", c.name)
		}
	}
}

// ShowPath 가 거짓이면 어느 갈래에서도 경로를 싣지 않는다 — 응답기(ingest-auth)는 계약 3-2 에 따라
// 열기 실패뿐 아니라 열린 뒤의 거부(종류 · 권한 · 크기 · 내용)에서도 경로 대신 env 이름과 원인만 싣는다.
// 경로의 디렉터리 이름에 카나리아를 넣어 본다. 대조 행: 같은 입력을 ShowPath 참으로 읽으면 열린 뒤
// 갈래에 경로가 실린다(사이드카 문구 유지). 실패 문구에는 원문 대신 「카나리아 발견」 만 적는다.
func TestReadHidesPathWhenShowPathFalse(t *testing.T) {
	const canary = "pathcanary5k"
	dir := filepath.Join(t.TempDir(), canary)
	if err := os.Mkdir(dir, 0o700); err != nil {
		t.Fatalf("시험 폴더 생성 실패: %v", err)
	}
	sub := filepath.Join(dir, "sub")
	if err := os.Mkdir(sub, 0o700); err != nil {
		t.Fatalf("시험 폴더 생성 실패: %v", err)
	}
	for _, c := range []struct {
		name, path string
		opened     bool // 열린 뒤의 갈래인가 — ShowPath 참이면 경로가 실려야 한다
	}{
		{name: "없는 파일", path: filepath.Join(dir, "missing")},
		{name: "디렉터리", path: sub, opened: true},
		{name: "other 비트", path: writeSecret(t, dir, "other", "pw\n", 0o604), opened: true},
		{name: "group 쓰기", path: writeSecret(t, dir, "gw", "pw\n", 0o620), opened: true},
		{name: "빈 내용", path: writeSecret(t, dir, "empty", "", 0o600), opened: true},
		{name: "두 줄", path: writeSecret(t, dir, "two", "pw\nmore\n", 0o600), opened: true},
		{name: "4 KiB 초과", path: writeSecret(t, dir, "big", strings.Repeat("x", 4097), 0o600), opened: true},
	} {
		_, err := Read(secretEnv, c.path, Options{ShowPath: false})
		if err == nil {
			t.Errorf("%s: Read 가 받았다", c.name)
			continue
		}
		if strings.Contains(err.Error(), canary) {
			t.Errorf("%s: ShowPath 거짓인데 에러에 경로 카나리아 발견", c.name)
		}
		if !strings.Contains(err.Error(), secretEnv) {
			t.Errorf("%s: 에러에 env 이름이 없다", c.name)
		}
		_, err = Read(secretEnv, c.path, Options{ShowPath: true})
		if got := err != nil && strings.Contains(err.Error(), canary); got != c.opened {
			t.Errorf("%s: ShowPath 참에서 경로가 실렸나 = %v, want %v", c.name, got, c.opened)
		}
	}
}

// 열린 뒤 상태(Stat) · 읽기(Read) 실패의 에러도 ShowPath 가 거짓이면 경로를 싣지 않고 원인(errno)만 남긴다 — 실제 파일로는
// 이 두 실패를 일으킬 수 없어 Read 가 그 에러를 감싸는 자리(Options.cause)에 합성 *fs.PathError 를 넣어 본다. 원인
// 판별(errors.Is)은 두 갈래 모두 살아 있어야 하고, ShowPath 참이면 지금처럼 경로가 남는다(사이드카 문구 유지).
func TestOptionsCauseHidesPathWhenShowPathFalse(t *testing.T) {
	const canary = "/causecanary8m/secret"
	for _, op := range []string{"stat", "read"} {
		err := &fs.PathError{Op: op, Path: canary, Err: syscall.EIO}
		hidden := Options{ShowPath: false}.cause(err)
		if strings.Contains(hidden.Error(), canary) {
			t.Errorf("%s: ShowPath 거짓인데 에러에 경로 카나리아 발견", op)
		}
		if !errors.Is(hidden, syscall.EIO) {
			t.Errorf("%s: ShowPath 거짓에서 원인이 사라졌다 — errors.Is(EIO) 가 거짓이다", op)
		}
		shown := Options{ShowPath: true}.cause(err)
		if !strings.Contains(shown.Error(), canary) || !errors.Is(shown, syscall.EIO) {
			t.Errorf("%s: ShowPath 참에서 경로 · 원인이 남지 않았다", op)
		}
	}
}
