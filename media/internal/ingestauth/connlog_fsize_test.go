//go:build darwin || linux

package ingestauth

import (
	"bytes"
	"flag"
	"maps"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"syscall"
	"testing"
	"time"
)

// fileSizeChildEnv 가 1 인 프로세스는 파일 크기 상한을 거는 시험의 자식이다. 상한(RLIMIT_FSIZE)은 프로세스 전체에
// 걸리므로, 시험 프로세스에서 걸면 그 구간에 시험 틀이 쓰는 파일(cmd/go 가 캐시 가능 실행에만 넘기는 시험 기록 · 출력을
// 파일로 받는 실행)의 쓰기가 EFBIG 로 깨져 시험이 모두 통과해도 그 실행이 실패한다. 자식은 시험 기록 인자 없이 띄우고
// 출력은 파이프로 받는다.
const fileSizeChildEnv = "INGESTAUTH_FILE_SIZE_CHILD"

// fileSizeChildTimeout 은 자식 프로세스의 시험 시한이다. 자식은 시험 하나만 돌고 곧바로 끝난다 — 멈추면 이 시한에
// 고루틴 덤프를 남기고 끝난다.
const fileSizeChildTimeout = time.Minute

// inFileSizeChild 는 지금 프로세스가 파일 크기 상한 시험의 자식인지 본다. 자식이 아니면 이 시험을 자식 프로세스에서
// 다시 돌려 그 결과를 이 시험의 결과로 삼고 거짓을 돌려준다 — 부르는 시험은 그때 곧바로 돌아간다. go test -cover 면
// 자식에 같은 -test.gocoverdir 를 넘겨, 자식이 남긴 계수를 이 프로세스의 프로파일이 합치게 한다.
func inFileSizeChild(t *testing.T) bool {
	t.Helper()
	if os.Getenv(fileSizeChildEnv) == "1" {
		return true
	}
	exe, err := os.Executable()
	if err != nil {
		t.Fatalf("Setup: os.Executable returned error: %v", err)
	}
	args := []string{"-test.run=^" + regexp.QuoteMeta(t.Name()) + "$", "-test.v", "-test.timeout=" + fileSizeChildTimeout.String()}
	if f := flag.Lookup("test.gocoverdir"); f != nil && f.Value.String() != "" {
		args = append(args, "-test.gocoverdir="+f.Value.String())
	}
	cmd := exec.Command(exe, args...)
	cmd.Env = append(os.Environ(), fileSizeChildEnv+"=1")
	// 자식의 출력은 이 시험 자신의 보고(실패 문구)다 — 이 시험들은 비밀 값을 다루지 않는다.
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("child process of %s failed: %v\n%s", t.Name(), err, out)
	}
	if !bytes.Contains(out, []byte("--- PASS: "+t.Name()+" (")) {
		t.Fatalf("child process of %s has no PASS line — 그 시험을 돌지 않았다:\n%s", t.Name(), out)
	}
	return false
}

// 덜 쓰인 줄: 디스크가 찬 것처럼 Append 의 쓰기가 줄 가운데서 끊기면 그 Append 는 에러다.
// 그 뒤 성공한 Append 의 줄은 앞 조각에 붙지 않고 제 줄로 남아 LoadAllowed 에 읽힌다 — 붙으면 cut 이
// 허용된 연결을 놓친다. 부분 쓰기는 파일 크기 상한(RLIMIT_FSIZE)으로 실제로 일으킨다. Go 런타임은
// SIGXFSZ 를 무시하므로 쓰기는 EFBIG 로 끝난다(macOS · 리눅스 실측). 상한 구간은 자식 프로세스에서만 돈다.
func TestConnLogAppendAfterTornWrite(t *testing.T) {
	if !inFileSizeChild(t) {
		return // 본문은 자식 프로세스가 돌았다
	}
	dir := t.TempDir()
	c, _ := openTestConnLog(t, dir, 1<<20, newFakeClock())
	current := filepath.Join(dir, connLogName)
	before, torn, after := testConnID(1), testConnID(2), testConnID(3)
	if err := c.Append(testTokenA, before); err != nil {
		t.Fatalf("Setup: Append returned error: %v", err)
	}
	fi, err := os.Stat(current)
	if err != nil {
		t.Fatalf("Setup: %v", err)
	}

	restore := limitFileSize(t, uint64(fi.Size())+10) // 다음 줄은 앞 10바이트만 쓰인다
	tornErr := c.Append(testTokenA, torn)
	restore()
	if tornErr == nil {
		t.Fatalf("Append past the file size limit = nil, want error")
	}
	if err := c.Append(testTokenB, after); err != nil {
		t.Fatalf("Append after the torn write returned error: %v", err)
	}

	got, err := LoadAllowed(dir)
	if err != nil {
		t.Fatalf("LoadAllowed(%q) returned error: %v", dir, err)
	}
	// torn 은 쓰기에 실패해 401 이었으므로 기록에 없어야 맞다.
	if want := map[string]string{before: testTokenA, after: testTokenB}; !maps.Equal(got.Tokens, want) {
		t.Errorf("LoadAllowed(%q).Tokens = %v, want %v — 성공한 줄이 조각에 붙어 읽히지 않았다", dir, got.Tokens, want)
	}
}

// 새 세대 만들기 실패: 회전이 현재 세대를 직전 세대로 넘긴 뒤 새 세대의 시작 표시를
// 쓰지 못하면 그 Append 는 에러이고, 현재 세대가 없는 동안 Check 도 에러다. 다음 Append 가 새 세대부터
// 다시 만든다 — 넘긴 직전 세대를 덮지 않는다. 크기 상한 0 은 빈 파일 만들기는 두고 시작 표시 쓰기만 막는다.
// 상한 구간은 자식 프로세스에서만 돈다.
func TestConnLogRecoversFromFailedNewGeneration(t *testing.T) {
	if !inFileSizeChild(t) {
		return // 본문은 자식 프로세스가 돌았다
	}
	dir := t.TempDir()
	c, _ := openTestConnLog(t, dir, 1, newFakeClock()) // maxBytes 1 — Append 하나가 회전 하나다
	before, failed, after := testConnID(0), testConnID(1), testConnID(2)
	if err := c.Append(testTokenA, before); err != nil {
		t.Fatalf("Setup: Append returned error: %v", err)
	}

	restore := limitFileSize(t, 0)
	failedErr := c.Append(testTokenA, failed)
	restore()
	if failedErr == nil {
		t.Fatalf("Append whose new generation could not be started = nil, want error")
	}
	if err := c.Check(); err == nil {
		t.Errorf("Check() without a current generation = nil, want error")
	}

	if err := c.Append(testTokenB, after); err != nil {
		t.Fatalf("Append after the failed rotation returned error: %v", err)
	}
	if err := c.Check(); err != nil {
		t.Errorf("Check() after recovery = %v, want nil", err)
	}
	got, err := LoadAllowed(dir)
	if err != nil {
		t.Fatalf("LoadAllowed(%q) returned error: %v", dir, err)
	}
	if want := map[string]string{before: testTokenA, after: testTokenB}; !maps.Equal(got.Tokens, want) {
		t.Errorf("LoadAllowed(%q).Tokens = %v, want %v", dir, got.Tokens, want)
	}
	if lines := readLines(t, filepath.Join(dir, connLogName)); len(lines) != 2 || !lines[0].isStartMark() {
		t.Errorf("current generation after recovery = %+v, want a start mark then the line of the Append after recovery", lines)
	}
}

// limitFileSize 는 이 프로세스가 파일을 limit 바이트 넘게 키우지 못하게 한다. 돌려준 함수가 상한을
// 되돌린다 — 프로세스 전체에 걸리므로 inFileSizeChild 의 자식 프로세스에서만 부른다(시험 틀의 파일과 떼어 둔다).
func limitFileSize(t *testing.T, limit uint64) (restore func()) {
	t.Helper()
	if os.Getenv(fileSizeChildEnv) != "1" {
		t.Fatalf("Setup: limitFileSize 는 파일 크기 상한 시험의 자식 프로세스에서만 부른다(inFileSizeChild)")
	}
	var old syscall.Rlimit
	if err := syscall.Getrlimit(syscall.RLIMIT_FSIZE, &old); err != nil {
		t.Fatalf("Setup: getrlimit(RLIMIT_FSIZE) returned error: %v", err)
	}
	lowered := old
	lowered.Cur = limit
	if err := syscall.Setrlimit(syscall.RLIMIT_FSIZE, &lowered); err != nil {
		t.Fatalf("Setup: setrlimit(RLIMIT_FSIZE, %d) returned error: %v", limit, err)
	}
	restored := false
	restore = func() {
		if restored {
			return
		}
		restored = true
		if err := syscall.Setrlimit(syscall.RLIMIT_FSIZE, &old); err != nil {
			t.Errorf("Cleanup: setrlimit(RLIMIT_FSIZE) could not restore %d: %v", old.Cur, err)
		}
	}
	t.Cleanup(restore)
	return restore
}
