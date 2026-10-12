//go:build darwin || linux

package ingestauth

import (
	"maps"
	"os"
	"path/filepath"
	"syscall"
	"testing"
)

// 덜 쓰인 줄(결함 예측 P14): 디스크가 찬 것처럼 Append 의 쓰기가 줄 가운데서 끊기면 그 Append 는 에러다.
// 그 뒤 성공한 Append 의 줄은 앞 조각에 붙지 않고 제 줄로 남아 LoadAllowed 에 읽힌다 — 붙으면 cut 이
// 허용된 연결을 놓친다. 부분 쓰기는 파일 크기 상한(RLIMIT_FSIZE)으로 실제로 일으킨다. Go 런타임은
// SIGXFSZ 를 무시하므로 쓰기는 EFBIG 로 끝난다(macOS · 리눅스 실측).
func TestConnLogAppendAfterTornWrite(t *testing.T) {
	dir := t.TempDir()
	c, _ := openTestConnLog(t, dir, 1<<20, newFakeClock())
	current := filepath.Join(dir, connLogName)
	if err := c.Append(testTokenA, "conn-before"); err != nil {
		t.Fatalf("Setup: Append returned error: %v", err)
	}
	fi, err := os.Stat(current)
	if err != nil {
		t.Fatalf("Setup: %v", err)
	}

	restore := limitFileSize(t, uint64(fi.Size())+10) // 다음 줄은 앞 10바이트만 쓰인다
	tornErr := c.Append(testTokenA, "conn-torn")
	restore()
	if tornErr == nil {
		t.Fatalf("Append past the file size limit = nil, want error")
	}
	if err := c.Append(testTokenB, "conn-after"); err != nil {
		t.Fatalf("Append after the torn write returned error: %v", err)
	}

	got, err := LoadAllowed(dir)
	if err != nil {
		t.Fatalf("LoadAllowed(%q) returned error: %v", dir, err)
	}
	// conn-torn 은 쓰기에 실패해 401 이었으므로 기록에 없어야 맞다.
	if want := map[string]string{"conn-before": testTokenA, "conn-after": testTokenB}; !maps.Equal(got.Tokens, want) {
		t.Errorf("LoadAllowed(%q).Tokens = %v, want %v — 성공한 줄이 조각에 붙어 읽히지 않았다", dir, got.Tokens, want)
	}
}

// 새 세대 만들기 실패(결함 예측 P5 뒷부분): 회전이 현재 세대를 직전 세대로 넘긴 뒤 새 세대의 시작 표시를
// 쓰지 못하면 그 Append 는 에러이고, 현재 세대가 없는 동안 Check 도 에러다. 다음 Append 가 새 세대부터
// 다시 만든다 — 넘긴 직전 세대를 덮지 않는다. 크기 상한 0 은 빈 파일 만들기는 두고 시작 표시 쓰기만 막는다.
func TestConnLogRecoversFromFailedNewGeneration(t *testing.T) {
	dir := t.TempDir()
	c, _ := openTestConnLog(t, dir, 1, newFakeClock()) // maxBytes 1 — Append 하나가 회전 하나다
	if err := c.Append(testTokenA, "conn-0"); err != nil {
		t.Fatalf("Setup: Append returned error: %v", err)
	}

	restore := limitFileSize(t, 0)
	failedErr := c.Append(testTokenA, "conn-failed")
	restore()
	if failedErr == nil {
		t.Fatalf("Append whose new generation could not be started = nil, want error")
	}
	if err := c.Check(); err == nil {
		t.Errorf("Check() without a current generation = nil, want error")
	}

	if err := c.Append(testTokenB, "conn-1"); err != nil {
		t.Fatalf("Append after the failed rotation returned error: %v", err)
	}
	if err := c.Check(); err != nil {
		t.Errorf("Check() after recovery = %v, want nil", err)
	}
	got, err := LoadAllowed(dir)
	if err != nil {
		t.Fatalf("LoadAllowed(%q) returned error: %v", dir, err)
	}
	if want := map[string]string{"conn-0": testTokenA, "conn-1": testTokenB}; !maps.Equal(got.Tokens, want) {
		t.Errorf("LoadAllowed(%q).Tokens = %v, want %v", dir, got.Tokens, want)
	}
	if lines := readLines(t, filepath.Join(dir, connLogName)); len(lines) != 2 || !lines[0].isStartMark() {
		t.Errorf("current generation after recovery = %+v, want a start mark then the conn-1 line", lines)
	}
}

// limitFileSize 는 이 프로세스가 파일을 limit 바이트 넘게 키우지 못하게 한다. 돌려준 함수가 상한을
// 되돌린다 — 프로세스 전체에 걸리므로 시험은 상한이 걸린 동안 다른 파일을 쓰지 않는다.
func limitFileSize(t *testing.T, limit uint64) (restore func()) {
	t.Helper()
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
