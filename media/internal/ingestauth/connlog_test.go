package ingestauth

import (
	"bytes"
	"maps"
	"os"
	"path/filepath"
	"slices"
	"strings"
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

	if err := c.Append(testTokenA, testConnID(1)); err != nil {
		t.Fatalf("Append returned error: %v", err)
	}
	rotated := readLines(t, filepath.Join(dir, connLogName))
	if len(rotated) != 2 || !rotated[0].isStartMark() || rotated[1].ID != testConnID(1) {
		t.Errorf("current generation after a rotation = %+v, want a start mark then the line of %s", rotated, testConnID(1))
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
	if err := c.Append(testTokenA, testConnID(1)); err != nil {
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
// 밖에서 찍으면 늦게 찍힌 줄이 먼저 쓰여 순서가 뒤집힌다(-race -count=20 으로 돈다). 판마다 시험 고루틴이 출발
// 장벽을 열고 일꾼 모두가 함께 한 번씩 Append 한다 — 한 일꾼이 제 몫을 다 끝낸 뒤 다음 일꾼이 도는 스케줄은
// 생기지 않는다.
func TestConnLogAppendStampsAtInsideLock(t *testing.T) {
	dir := t.TempDir()
	c, _ := openTestConnLog(t, dir, 1<<20, newFakeClock())
	const goroutines, rounds = 8, 50
	for round := range rounds {
		start := make(chan struct{})
		var wg sync.WaitGroup
		for g := range goroutines {
			wg.Go(func() {
				<-start
				if err := c.Append(testTokenA, testConnID(round*goroutines+g)); err != nil {
					t.Errorf("Append returned error: %v", err)
				}
			})
		}
		close(start)
		wg.Wait()
	}

	lines := readLines(t, filepath.Join(dir, connLogName))
	if want := 1 + goroutines*rounds; len(lines) != want {
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

	if err := c.Append(testTokenA, testConnID(1)); err != nil {
		t.Fatalf("Append returned error: %v", err)
	}
	after, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("os.ReadFile(%q) right after Append returned error: %v", path, err)
	}
	if !bytes.HasPrefix(after, before) {
		t.Fatalf("file after Append (%d bytes) does not start with the previous content (%d bytes)", len(after), len(before))
	}
	added, err := parseLines(after[len(before):])
	if err != nil || len(added) != 1 {
		t.Fatalf("bytes added by Append (%d bytes) = %d line(s) (%v), want exactly one complete line", len(after)-len(before), len(added), err)
	}
	if got := added[0]; !slices.Equal(got.Keys, []string{"at", "id", "path"}) || got.Path != testTokenA || got.ID != testConnID(1) {
		t.Errorf("line added by Append = %+v, want keys [at id path] with path %q and id %q", got, testTokenA, testConnID(1))
	}
}

// 토큰 꼴(streamkey.IsToken)이 아닌 토큰과 MediaMTX 연결 id 꼴(소문자 UUID)이 아닌 id 는 기록하지 않는다. 빈 값은 시작 표시와
// 구별되지 않고, 다른 꼴은 cut 이 받는 목록의 id 와 맞지 않거나 JSON 이 잘못된 UTF-8 을 U+FFFD 로 바꿔 다른 줄의 id 와 겹친다.
func TestConnLogAppendRejectsMalformedValues(t *testing.T) {
	tests := []struct {
		name, token, connID string
	}{
		{name: "빈_토큰", token: "", connID: testConnID(1)},
		{name: "빈_연결_id", token: testTokenA, connID: ""},
		{name: "소문자_토큰", token: strings.ToLower(testTokenA), connID: testConnID(1)},
		{name: "25자_토큰", token: testTokenA[:25], connID: testConnID(1)},
		{name: "토큰_끝_줄바꿈", token: testTokenA + "\n", connID: testConnID(1)},
		{name: "UUID_가_아닌_id", token: testTokenA, connID: "conn-1"},
		{name: "대문자_UUID", token: testTokenA, connID: strings.ToUpper(testConnID(10))},
		{name: "잘못된_UTF-8_id", token: testTokenA, connID: "conn-\xff"},
		{name: "id_끝_줄바꿈", token: testTokenA, connID: testConnID(1) + "\n"},
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

// 줄을 꾸미는 값은 기록에 들지 않는다 — 줄바꿈과 시작 표시 꼴을 담은 연결 id 는 연결 id 꼴이 아니라 Append 가 거부하고,
// 옛 시작 표시를 지어내 Since 를 끌어당기지 못한다.
func TestConnLogAppendKeepsValuesOnOneLine(t *testing.T) {
	dir := t.TempDir()
	c, _ := openTestConnLog(t, dir, 1<<20, newFakeClock())
	forged := testConnID(1) + "\n" + `{"at":"2000-01-01T00:00:00Z"}`
	if err := c.Append(testTokenA, forged); err == nil {
		t.Errorf("Append with a connection id carrying a newline and a start mark = nil, want error")
	}
	mark := readLines(t, filepath.Join(dir, connLogName))[0]

	got, err := LoadAllowed(dir)
	if err != nil {
		t.Fatalf("LoadAllowed(%q) returned error: %v", dir, err)
	}
	if !got.Since.Equal(mark.At) || len(got.Tokens) != 0 {
		t.Errorf("LoadAllowed(%q) = %+v, want Since %v and no tokens", dir, got, mark.At)
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

// 현재 세대를 지우거나 다른 파일 · 링크로 바꿔 끼우면 Append 는 쓰지 않고 에러다(처리기는 401 connlog_unwritable). 연 파일에
// 계속 쓰면 cut 이 읽지 않는 파일에 쓰게 되고, 회전이 끼운 파일을 직전 세대로 넘기면 Check 가 healthy 로 돌아와 Since 가 그
// 파일의 at 으로 끌려간다(설계 3-4). 그래서 Append 는 회전 전에 연 파일이 경로의 파일(링크를 따르지 않고 본다)인지 보고,
// 다르면 거듭 에러이고 회전하지 않는다 — 끼운 파일을 넘기거나 비우지 않는다. Check 도 계속 에러다.
func TestConnLogAppendRefusesReplacedCurrentGeneration(t *testing.T) {
	tests := []struct {
		name     string
		maxBytes int64 // 1 이면 Append 마다 회전할 차례다
		replace  func(t *testing.T, dir string)
	}{
		{name: "지움", maxBytes: 1 << 20, replace: func(t *testing.T, dir string) {
			removeFile(t, filepath.Join(dir, connLogName))
		}},
		{name: "옛_시작_표시의_파일로_바꿔_끼움", maxBytes: 1, replace: func(t *testing.T, dir string) {
			if err := replaceFile(filepath.Join(dir, connLogName), `{"at":"2000-01-01T00:00:00Z"}`+"\n"); err != nil {
				t.Fatalf("Setup: %v", err)
			}
		}},
		{name: "링크로_바꿈", maxBytes: 1 << 20, replace: func(t *testing.T, dir string) {
			// 연 파일을 옆 이름으로 옮기고 그 자리에 그 파일로 가는 링크를 둔다 — 링크를 따르면 같은 파일이다.
			moved := filepath.Join(dir, "moved.jsonl")
			if err := os.Rename(filepath.Join(dir, connLogName), moved); err != nil {
				t.Fatalf("Setup: %v", err)
			}
			symlink(t, moved, filepath.Join(dir, connLogName))
		}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			c, rec := openTestConnLog(t, dir, tc.maxBytes, newFakeClock())
			if err := c.Append(testTokenA, testConnID(1)); err != nil {
				t.Fatalf("Setup: Append returned error: %v", err)
			}
			rotatedBefore := len(rec.events(t, "ingestauth_connlog_rotated"))
			tc.replace(t, dir)

			for i := range 2 {
				if err := c.Append(testTokenA, testConnID(2+i)); err == nil {
					t.Errorf("Append #%d after the current generation was replaced = nil, want error", i+1)
				}
			}
			if err := c.Check(); err == nil {
				t.Errorf("Check() after Appends on a replaced current generation = nil, want error")
			}
			if n := len(rec.events(t, "ingestauth_connlog_rotated")) - rotatedBefore; n != 0 {
				t.Errorf("rotations after the current generation was replaced = %d, want 0", n)
			}
		})
	}
}

// 회전이 실패해도 다음 Append 가 회복한다. 디렉터리에 쓸 수 없으면 직전 세대로 돌리지 못해
// Append 가 에러이고 기록은 그대로다 — 앞서 쓴 줄을 잃지 않는다. 권한을 되돌리면 다음 Append 가 돌리고 쓴다.
func TestConnLogRecoversFromFailedRotation(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root 는 권한 검사를 우회한다")
	}
	dir := t.TempDir()
	c, _ := openTestConnLog(t, dir, 1, newFakeClock())
	before, failed, after := testConnID(0), testConnID(1), testConnID(2)
	if err := c.Append(testTokenA, before); err != nil {
		t.Fatalf("Setup: Append returned error: %v", err)
	}
	chmod(t, dir, 0o500)
	if err := c.Append(testTokenA, failed); err == nil {
		t.Errorf("Append while the directory is read-only = nil, want error")
	}
	chmod(t, dir, 0o755)
	if err := c.Append(testTokenA, after); err != nil {
		t.Fatalf("Append after restoring the permission returned error: %v", err)
	}
	if err := c.Check(); err != nil {
		t.Errorf("Check() after recovery = %v, want nil", err)
	}
	got, err := LoadAllowed(dir)
	if err != nil {
		t.Fatalf("LoadAllowed(%q) returned error: %v", dir, err)
	}
	if want := map[string]string{before: testTokenA, after: testTokenA}; !maps.Equal(got.Tokens, want) {
		t.Errorf("LoadAllowed(%q).Tokens = %v, want %v", dir, got.Tokens, want)
	}
}

// 회전은 직전 세대로 넘긴 파일을 닫는다 — 회전마다 하나씩 쌓이면 1 MiB 마다 fd 가 샌다.
func TestConnLogRotationClosesRetiredFile(t *testing.T) {
	before, err := openFDs()
	if err != nil {
		t.Skipf("이 플랫폼에서 열린 fd 를 셀 수 없다: %v", err)
	}
	c, _ := openTestConnLog(t, t.TempDir(), 1, newFakeClock())
	const rotations = 50
	for i := range rotations {
		if err := c.Append(testTokenA, testConnID(i)); err != nil {
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
			if err := first.Append(testTokenA, testConnID(1)); err != nil {
				t.Fatalf("Setup: Append returned error: %v", err)
			}
			closeConnLog(first)
			appendBytes(t, path, tc.tail)

			second, _ := openTestConnLog(t, dir, 1<<20, newFakeClock())
			if err := second.Append(testTokenB, testConnID(2)); err != nil {
				t.Fatalf("Append after reopening returned error: %v", err)
			}
			got, err := LoadAllowed(dir)
			if err != nil {
				t.Fatalf("LoadAllowed(%q) returned error: %v", dir, err)
			}
			if want := map[string]string{testConnID(1): testTokenA, testConnID(2): testTokenB}; !maps.Equal(got.Tokens, want) {
				t.Errorf("LoadAllowed(%q).Tokens = %v, want %v", dir, got.Tokens, want)
			}
			// 두 시계 모두 09:00:00Z 에서 시작한다. 다시 열며 시작 표시를 또 쓰면 같은 줄이 둘이 된다.
			data, err := os.ReadFile(path)
			if err != nil {
				t.Fatalf("Setup: %v", err)
			}
			if marks := bytes.Count(data, []byte(`{"at":"2026-10-12T09:00:00Z"}`)); marks != 1 {
				t.Errorf("start marks in the current generation (%d bytes) = %d, want 1", len(data), marks)
			}
		})
	}
	t.Run("빈_현재_세대", func(t *testing.T) {
		dir := t.TempDir()
		writeFile(t, filepath.Join(dir, connLogName), "")
		setMode(t, filepath.Join(dir, connLogName), connLogFileMode) // 시작 표시를 쓰기 전에 끝난 이 기록 자신의 세대다
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

// 기록은 세대 파일 자리의 링크를 따르지 않는다 — 따르면 디렉터리 밖 파일을 비우고 그 파일에 쓴다. 기동 때 현재 세대가 링크면
// OpenConnLog 가 에러(기동 실패)이고, 새 세대를 만들 자리에 링크가 있으면 그 Append 가 에러다. 링크가 가리키는 파일은 그대로다.
func TestConnLogRefusesSymlinkedGeneration(t *testing.T) {
	const outsideContent = "outside\n"
	tests := []struct {
		name string
		call string // 실패 문구에 싣는 부른 것
		// try 는 dir 의 현재 세대 자리에 outside 로 가는 링크를 둔 뒤 기록을 열거나 써 본 에러다.
		try func(t *testing.T, dir, outside string) error
	}{
		{
			name: "기동_때_현재_세대가_링크",
			call: "OpenConnLog with the current generation as a symlink",
			try: func(t *testing.T, dir, outside string) error {
				symlink(t, outside, filepath.Join(dir, connLogName))
				c, err := OpenConnLog(dir, 1<<20, newFakeClock().Now)
				if err == nil {
					closeConnLog(c)
				}
				return err
			},
		},
		{
			name: "새_세대_자리에_링크",
			call: "Append with a symlink where the new generation goes",
			try: func(t *testing.T, dir, outside string) error {
				c, _ := openTestConnLog(t, dir, 1<<20, newFakeClock())
				closeConnLog(c) // 회전이 새 세대를 만들지 못한 상태 — 다음 Append 가 새 세대부터 만든다
				removeFile(t, filepath.Join(dir, connLogName))
				symlink(t, outside, filepath.Join(dir, connLogName))
				return c.Append(testTokenA, testConnID(1))
			},
		},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			outside := filepath.Join(t.TempDir(), "outside.jsonl")
			writeFile(t, outside, outsideContent)
			setMode(t, outside, connLogFileMode) // 모드 단언에는 걸리지 않는 파일 — 링크를 따르지 않는 것만 본다
			if err := tc.try(t, dir, outside); err == nil {
				t.Errorf("%s = nil error, want error", tc.call)
			}
			data, err := os.ReadFile(outside)
			if err != nil {
				t.Fatalf("os.ReadFile(%q) returned error: %v", outside, err)
			}
			if string(data) != outsideContent {
				t.Errorf("file behind the symlink has %d bytes after the call, want the original %d bytes (링크를 따라 비우거나 썼다)", len(data), len(outsideContent))
			}
		})
	}
}

// symlink 는 newname 에 oldname 으로 가는 심볼릭 링크를 만든다.
func symlink(t *testing.T, oldname, newname string) {
	t.Helper()
	if err := os.Symlink(oldname, newname); err != nil {
		t.Fatalf("Setup: os.Symlink(%q, %q) returned error: %v", oldname, newname, err)
	}
}

// 연 세대 파일에 connLogFileMode 밖의 권한(그룹 · 다른 사용자)이 있으면 쓰지 않는다 — 쓰는 응답기와 읽는 cut 이 같은 계정이다
// (설계 2-8). 이어 쓰는 현재 세대는 OpenConnLog 가 에러(기동 실패)이고, 새 세대를 만들 자리에 남은 파일은 그 Append 가 에러다.
// 두 열기 모두 만들 때의 모드를 받지 않는 파일(이어 쓰기 · 남은 파일 비우고 다시 쓰기)일 수 있어 연 뒤에 본다.
func TestConnLogRefusesGenerationWithLooseMode(t *testing.T) {
	tests := []struct {
		name    string
		mode    os.FileMode
		newGen  bool // 참이면 새 세대를 만들 자리에 남은 파일, 거짓이면 이어 쓰는 현재 세대
		wantErr bool
	}{
		{name: "이어_쓰기_0600", mode: 0o600},
		{name: "이어_쓰기_0640", mode: 0o640, wantErr: true},
		{name: "이어_쓰기_0604", mode: 0o604, wantErr: true},
		{name: "새_세대_자리에_남은_0644", mode: 0o644, newGen: true, wantErr: true},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			path := filepath.Join(dir, connLogName)
			var err error
			if tc.newGen {
				c, _ := openTestConnLog(t, dir, 1<<20, newFakeClock())
				closeConnLog(c) // 회전이 새 세대를 만들지 못한 상태 — 다음 Append 가 남은 파일을 비우고 새 세대를 쓴다
				setMode(t, path, tc.mode)
				err = c.Append(testTokenA, testConnID(1))
			} else {
				writeFile(t, path, `{"at":"2026-10-12T08:00:00Z"}`+"\n")
				setMode(t, path, tc.mode)
				var c *ConnLog
				c, err = OpenConnLog(dir, 1<<20, newFakeClock().Now)
				if err == nil {
					closeConnLog(c)
				}
			}
			if (err != nil) != tc.wantErr {
				t.Errorf("opening a generation file of mode %v: err = %v, want error presence = %v", tc.mode, err, tc.wantErr)
			}
		})
	}
}

// setMode 는 path 의 모드를 mode 로 둔다(umask 와 무관하다).
func setMode(t *testing.T, path string, mode os.FileMode) {
	t.Helper()
	if err := os.Chmod(path, mode); err != nil {
		t.Fatalf("Setup: os.Chmod(%q, %v) returned error: %v", path, mode, err)
	}
}
