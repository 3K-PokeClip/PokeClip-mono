package ingestauth

import (
	"bytes"
	"fmt"
	"maps"
	"os"
	"path/filepath"
	"slices"
	"sync"
	"testing"
)

// 시작 표시(설계 2-4 · G3-6 5): 빈 디렉터리에서 연 직후 LoadAllowed 의 Since 는 시작 표시의 at 이다.
// 세대 파일을 새로 만들 때마다 첫 줄은 시작 표시(at 만 있는 줄)다 — 회전한 새 세대도 그렇다.
func TestConnLogWritesStartMark(t *testing.T) {
	dir := t.TempDir()
	c, _ := openTestConnLog(t, dir, 1, newFakeClock()) // maxBytes 1 — Append 하나가 회전 하나다

	opened := readLines(t, filepath.Join(dir, connLogName))
	if len(opened) != 1 || !opened[0].isStartMark() {
		t.Fatalf("current generation right after OpenConnLog = %+v, want one start mark", opened)
	}
	got, err := LoadAllowed(dir)
	if err != nil {
		t.Fatalf("LoadAllowed(%q) returned error: %v", dir, err)
	}
	if !got.Since.Equal(opened[0].At) || len(got.Tokens) != 0 {
		t.Errorf("LoadAllowed(%q) = %+v, want Since %v (시작 표시의 at) and no tokens", dir, got, opened[0].At)
	}

	if err := c.Append(testTokenA, "conn-1"); err != nil {
		t.Fatalf("Append returned error: %v", err)
	}
	rotated := readLines(t, filepath.Join(dir, connLogName))
	if len(rotated) != 2 || !rotated[0].isStartMark() || rotated[1].ID != "conn-1" {
		t.Errorf("current generation after a rotation = %+v, want a start mark then the conn-1 line", rotated)
	}
	if !rotated[0].At.After(opened[0].At) {
		t.Errorf("start mark of the new generation at %v, want after %v", rotated[0].At, opened[0].At)
	}
	if prev := readLines(t, filepath.Join(dir, connLogPrevName)); len(prev) != 1 || !prev[0].At.Equal(opened[0].At) {
		t.Errorf("previous generation = %+v, want the first generation (its start mark only)", prev)
	}
}

// 회전은 ingestauth_connlog_rotated(INFO — bytes)를 낸다. bytes 는 직전 세대로 넘어간 파일의 크기다.
func TestConnLogLogsRotation(t *testing.T) {
	dir := t.TempDir()
	c, rec := openTestConnLog(t, dir, 1, newFakeClock())
	if err := c.Append(testTokenA, "conn-1"); err != nil {
		t.Fatalf("Append returned error: %v", err)
	}
	prev, err := os.Stat(filepath.Join(dir, connLogPrevName))
	if err != nil {
		t.Fatalf("Setup: %v", err)
	}
	want := []logEvent{{Level: "INFO", Msg: "ingestauth_connlog_rotated", Bytes: prev.Size()}}
	if got := rec.events(t, "ingestauth_connlog_rotated"); !slices.Equal(got, want) {
		t.Errorf("rotation events = %+v, want %+v", got, want)
	}
}

// 잠금 안 at(설계 2-4 · 3-4): 여러 고루틴이 Append 한 뒤 파일의 at 이 줄 순서대로 커진다. at 을 잠금
// 밖에서 찍으면 늦게 찍힌 줄이 먼저 쓰여 순서가 뒤집힌다(-race -count=20 으로 돈다).
func TestConnLogAppendStampsAtInsideLock(t *testing.T) {
	dir := t.TempDir()
	c, _ := openTestConnLog(t, dir, 1<<20, newFakeClock())
	const goroutines, perGoroutine = 8, 50
	var wg sync.WaitGroup
	for g := range goroutines {
		wg.Go(func() {
			for i := range perGoroutine {
				if err := c.Append(testTokenA, fmt.Sprintf("conn-%d-%d", g, i)); err != nil {
					t.Errorf("Append returned error: %v", err)
					return
				}
			}
		})
	}
	wg.Wait()

	lines := readLines(t, filepath.Join(dir, connLogName))
	if want := 1 + goroutines*perGoroutine; len(lines) != want {
		t.Fatalf("line count = %d, want %d (시작 표시 하나 + Append 마다 한 줄)", len(lines), want)
	}
	for i := 1; i < len(lines); i++ {
		if !lines[i].At.After(lines[i-1].At) {
			t.Fatalf("line %d at %v is not after line %d at %v — 줄 순서와 at 순서가 다르다", i, lines[i].At, i-1, lines[i-1].At)
		}
	}
}

// 착수 체크리스트 C2 쓰기 단언: Append 는 줄 하나를 쓰기 한 번으로 마치고, 반환 뒤 다른 파일 핸들로 곧바로
// 읽힌다(fsync 없이 — 같은 상자의 cut 은 페이지 캐시에서 읽는다). 쓰기 한 번은 구조로 지킨다 — 줄 전체를
// 한 버퍼로 만들어 O_APPEND 파일에 Write 한 번이다. 이 시험은 그 결과를 본다: 반환 직후 다른 핸들에 온전한
// 줄 하나가 더해져 있고, 그 줄의 키는 at · path · id 셋뿐이다(비밀 없음).
func TestConnLogAppendIsVisibleImmediately(t *testing.T) {
	dir := t.TempDir()
	c, _ := openTestConnLog(t, dir, 1<<20, newFakeClock())
	path := filepath.Join(dir, connLogName)
	before, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("Setup: %v", err)
	}

	if err := c.Append(testTokenA, "conn-1"); err != nil {
		t.Fatalf("Append returned error: %v", err)
	}
	after, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("os.ReadFile(%q) right after Append returned error: %v", path, err)
	}
	if !bytes.HasPrefix(after, before) {
		t.Fatalf("file after Append = %q, want it to start with the previous content %q", after, before)
	}
	added, err := parseLines(after[len(before):])
	if err != nil || len(added) != 1 {
		t.Fatalf("bytes added by Append = %q (%v), want exactly one complete line", after[len(before):], err)
	}
	if got := added[0]; !slices.Equal(got.Keys, []string{"at", "id", "path"}) || got.Path != testTokenA || got.ID != "conn-1" {
		t.Errorf("line added by Append = %+v, want keys [at id path] with path %q and id %q", got, testTokenA, "conn-1")
	}
}

// 빈 토큰 · 빈 연결 id 는 기록하지 않는다 — 그 줄은 시작 표시와 구별되지 않거나 id 대응이 없다.
func TestConnLogAppendRejectsEmptyValues(t *testing.T) {
	tests := []struct {
		name, token, connID string
	}{
		{name: "빈_토큰", token: "", connID: "conn-1"},
		{name: "빈_연결_id", token: testTokenA, connID: ""},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			c, _ := openTestConnLog(t, dir, 1<<20, newFakeClock())
			if err := c.Append(tc.token, tc.connID); err == nil {
				t.Errorf("Append(%q, %q) = nil, want error", tc.token, tc.connID)
			}
			if lines := readLines(t, filepath.Join(dir, connLogName)); len(lines) != 1 {
				t.Errorf("current generation = %+v, want the start mark only", lines)
			}
		})
	}
}

// 줄을 꾸미는 값도 한 줄로 남는다 — 줄바꿈과 시작 표시 꼴을 담은 연결 id 가 옛 시작 표시를 지어내
// Since 를 끌어당기지 못한다.
func TestConnLogAppendKeepsValuesOnOneLine(t *testing.T) {
	dir := t.TempDir()
	c, _ := openTestConnLog(t, dir, 1<<20, newFakeClock())
	forged := "conn-1\n" + `{"at":"2000-01-01T00:00:00Z"}`
	if err := c.Append(testTokenA, forged); err != nil {
		t.Fatalf("Append returned error: %v", err)
	}
	mark := readLines(t, filepath.Join(dir, connLogName))[0]

	got, err := LoadAllowed(dir)
	if err != nil {
		t.Fatalf("LoadAllowed(%q) returned error: %v", dir, err)
	}
	if !got.Since.Equal(mark.At) || got.Tokens[forged] != testTokenA || len(got.Tokens) != 1 {
		t.Errorf("LoadAllowed(%q) = %+v, want Since %v and only %q → %q", dir, got, mark.At, forged, testTokenA)
	}
}

// Check(설계 2-4 · 3-4 3): 현재 세대 경로가 연 파일과 같은지, 그 디렉터리에 다음 세대를 만들 수 있는지를
// 지금 다시 본다. Append 가 한 번도 없어도 그렇다 — 마지막 Append 의 결과를 쓰지 않는다.
func TestConnLogCheck(t *testing.T) {
	t.Run("새로_연_기록", func(t *testing.T) {
		c, _ := openTestConnLog(t, t.TempDir(), 1<<20, newFakeClock())
		if err := c.Check(); err != nil {
			t.Errorf("Check() right after OpenConnLog = %v, want nil", err)
		}
	})
	t.Run("디렉터리_쓰기_권한", func(t *testing.T) {
		if os.Geteuid() == 0 {
			t.Skip("root 는 권한 검사를 우회한다")
		}
		dir := t.TempDir()
		c, _ := openTestConnLog(t, dir, 1<<20, newFakeClock())
		chmod(t, dir, 0o500)
		if err := c.Check(); err == nil {
			t.Errorf("Check() on a read-only directory = nil, want error")
		}
		chmod(t, dir, 0o755)
		if err := c.Check(); err != nil {
			t.Errorf("Check() after restoring the permission = %v, want nil", err)
		}
	})
	t.Run("현재_세대를_바꿔_끼움", func(t *testing.T) {
		dir := t.TempDir()
		c, _ := openTestConnLog(t, dir, 1<<20, newFakeClock())
		if err := replaceFile(filepath.Join(dir, connLogName), "{}\n"); err != nil {
			t.Fatalf("Setup: %v", err)
		}
		if err := c.Check(); err == nil {
			t.Errorf("Check() after swapping the current generation = nil, want error")
		}
	})
	t.Run("현재_세대를_지움", func(t *testing.T) {
		dir := t.TempDir()
		c, _ := openTestConnLog(t, dir, 1<<20, newFakeClock())
		removeFile(t, filepath.Join(dir, connLogName))
		if err := c.Check(); err == nil {
			t.Errorf("Check() after removing the current generation = nil, want error")
		}
	})
}

// 회전이 실패해도 다음 Append 가 회복한다(결함 예측 P5). 디렉터리에 쓸 수 없으면 직전 세대로 돌리지 못해
// Append 가 에러이고 기록은 그대로다 — 앞서 쓴 줄을 잃지 않는다. 권한을 되돌리면 다음 Append 가 돌리고 쓴다.
func TestConnLogRecoversFromFailedRotation(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root 는 권한 검사를 우회한다")
	}
	dir := t.TempDir()
	c, _ := openTestConnLog(t, dir, 1, newFakeClock())
	if err := c.Append(testTokenA, "conn-0"); err != nil {
		t.Fatalf("Setup: Append returned error: %v", err)
	}
	chmod(t, dir, 0o500)
	if err := c.Append(testTokenA, "conn-failed"); err == nil {
		t.Errorf("Append while the directory is read-only = nil, want error")
	}
	chmod(t, dir, 0o755)
	if err := c.Append(testTokenA, "conn-1"); err != nil {
		t.Fatalf("Append after restoring the permission returned error: %v", err)
	}
	if err := c.Check(); err != nil {
		t.Errorf("Check() after recovery = %v, want nil", err)
	}
	got, err := LoadAllowed(dir)
	if err != nil {
		t.Fatalf("LoadAllowed(%q) returned error: %v", dir, err)
	}
	if want := map[string]string{"conn-0": testTokenA, "conn-1": testTokenA}; !maps.Equal(got.Tokens, want) {
		t.Errorf("LoadAllowed(%q).Tokens = %v, want %v", dir, got.Tokens, want)
	}
}

// 회전은 직전 세대로 넘긴 파일을 닫는다(결함 예측 P6) — 회전마다 하나씩 쌓이면 1 MiB 마다 fd 가 샌다.
func TestConnLogRotationClosesRetiredFile(t *testing.T) {
	before, err := openFDs()
	if err != nil {
		t.Skipf("이 플랫폼에서 열린 fd 를 셀 수 없다: %v", err)
	}
	c, _ := openTestConnLog(t, t.TempDir(), 1, newFakeClock())
	const rotations = 50
	for i := range rotations {
		if err := c.Append(testTokenA, fmt.Sprintf("conn-%d", i)); err != nil {
			t.Fatalf("Append returned error: %v", err)
		}
	}
	after, err := openFDs()
	if err != nil {
		t.Fatalf("openFDs() returned error: %v", err)
	}
	// 열린 기록 파일 하나(현재 세대)만큼은 늘 수 있다. 회전마다 새는 구현이면 rotations 만큼 는다.
	if after-before > 2 {
		t.Errorf("open fds grew from %d to %d over %d rotations, want at most 2 more", before, after, rotations)
	}
}

// openFDs 는 이 프로세스의 열린 fd 수다(/dev/fd — 리눅스 · macOS).
func openFDs() (int, error) {
	entries, err := os.ReadDir("/dev/fd")
	return len(entries), err
}

// 응답기가 다시 뜨면 현재 세대를 이어 쓴다 — 앞의 줄을 지우지 않고 시작 표시를 더 쓰지 않는다. 앞 프로세스가
// 줄 가운데서 끝났으면(끝이 줄바꿈이 아님) 다음 줄은 그 조각에 붙지 않는다. 현재 세대 파일이 비어 있으면
// 새 세대처럼 시작 표시부터 쓴다.
func TestOpenConnLogContinuesCurrentGeneration(t *testing.T) {
	tests := []struct {
		name string
		tail string // 첫 프로세스가 끝난 뒤 현재 세대 끝에 덧붙는 바이트
	}{
		{name: "온전한_끝"},
		{name: "덜_쓰인_끝", tail: `{"at":"2026-10-12T09:`},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			path := filepath.Join(dir, connLogName)
			first, _ := openTestConnLog(t, dir, 1<<20, newFakeClock())
			if err := first.Append(testTokenA, "conn-1"); err != nil {
				t.Fatalf("Setup: Append returned error: %v", err)
			}
			closeConnLog(first)
			appendBytes(t, path, tc.tail)

			second, _ := openTestConnLog(t, dir, 1<<20, newFakeClock())
			if err := second.Append(testTokenB, "conn-2"); err != nil {
				t.Fatalf("Append after reopening returned error: %v", err)
			}
			got, err := LoadAllowed(dir)
			if err != nil {
				t.Fatalf("LoadAllowed(%q) returned error: %v", dir, err)
			}
			if want := map[string]string{"conn-1": testTokenA, "conn-2": testTokenB}; !maps.Equal(got.Tokens, want) {
				t.Errorf("LoadAllowed(%q).Tokens = %v, want %v", dir, got.Tokens, want)
			}
			// 두 시계 모두 09:00:00Z 에서 시작한다. 다시 열며 시작 표시를 또 쓰면 같은 줄이 둘이 된다.
			data, err := os.ReadFile(path)
			if err != nil {
				t.Fatalf("Setup: %v", err)
			}
			if marks := bytes.Count(data, []byte(`{"at":"2026-10-12T09:00:00Z"}`)); marks != 1 {
				t.Errorf("start marks in %q = %d, want 1", data, marks)
			}
		})
	}
	t.Run("빈_현재_세대", func(t *testing.T) {
		dir := t.TempDir()
		writeFile(t, filepath.Join(dir, connLogName), "")
		openTestConnLog(t, dir, 1<<20, newFakeClock())
		if lines := readLines(t, filepath.Join(dir, connLogName)); len(lines) != 1 || !lines[0].isStartMark() {
			t.Errorf("empty current generation after OpenConnLog = %+v, want one start mark", lines)
		}
	})
}

// appendBytes 는 path 끝에 b 를 덧붙인다(앞 프로세스가 남긴 조각을 흉내 낸다).
func appendBytes(t *testing.T, path, b string) {
	t.Helper()
	if b == "" {
		return
	}
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_APPEND, 0)
	if err != nil {
		t.Fatalf("Setup: %v", err)
	}
	defer f.Close()
	if _, err := f.WriteString(b); err != nil {
		t.Fatalf("Setup: %v", err)
	}
}

// OpenConnLog 는 쓸 수 없으면 에러다(기동 실패) — 빈 디렉터리 이름 · 0 이하의 maxBytes · 없는 디렉터리.
func TestOpenConnLogRejectsUnusableArguments(t *testing.T) {
	tests := []struct {
		name     string
		dir      string
		maxBytes int64
	}{
		{name: "빈_디렉터리_이름", dir: "", maxBytes: 1 << 20},
		{name: "maxBytes_0", dir: t.TempDir(), maxBytes: 0},
		{name: "maxBytes_음수", dir: t.TempDir(), maxBytes: -1},
		{name: "없는_디렉터리", dir: filepath.Join(t.TempDir(), "missing"), maxBytes: 1 << 20},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			c, err := OpenConnLog(tc.dir, tc.maxBytes, nil)
			if err == nil {
				closeConnLog(c)
				t.Errorf("OpenConnLog(%q, %d, nil) = nil error, want error", tc.dir, tc.maxBytes)
			}
		})
	}
	t.Run("쓸_수_없는_디렉터리", func(t *testing.T) {
		if os.Geteuid() == 0 {
			t.Skip("root 는 권한 검사를 우회한다")
		}
		dir := t.TempDir()
		chmod(t, dir, 0o500)
		c, err := OpenConnLog(dir, 1<<20, nil)
		if err == nil {
			closeConnLog(c)
			t.Errorf("OpenConnLog(%q) on a read-only directory = nil error, want error", dir)
		}
	})
}
