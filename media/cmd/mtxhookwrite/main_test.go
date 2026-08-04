package main_test

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxhook"
)

// binPath 는 TestMain 이 한 번 빌드해 둔 mtxhookwrite 바이너리다.
//
// 왜 실제 바이너리를 exec 하는가: 이 도구의 계약은 **프로세스 여러 개가 같은 파일에
// 동시에 덧붙일 때 줄이 섞이지 않는다**는 것이다. flock 은 프로세스 간 잠금이므로
// 같은 프로세스 안에서 함수를 여러 번 부르는 방식으로는 그 계약이 검증되지 않는다.
var binPath string

func TestMain(m *testing.M) {
	// os.Exit 은 defer 를 실행하지 않는다. 그래서 정리·종료를 한 곳에 모으고
	// 여기서는 코드만 받아 나간다 — 빌드가 실패하는 경로에서 임시 디렉토리가 새지 않게 한다.
	os.Exit(buildAndRun(m))
}

func buildAndRun(m *testing.M) int {
	dir, err := os.MkdirTemp("", "mtxhookwrite-bin")
	if err != nil {
		fmt.Fprintln(os.Stderr, "임시 디렉토리 생성 실패:", err)
		return 1
	}
	defer os.RemoveAll(dir)

	binPath = filepath.Join(dir, "mtxhookwrite")
	build := exec.Command("go", "build", "-o", binPath, ".")
	if out, err := build.CombinedOutput(); err != nil {
		fmt.Fprintf(os.Stderr, "mtxhookwrite 빌드 실패: %v\n%s", err, out)
		return 1
	}
	return m.Run()
}

// exitCode 는 자식 프로세스의 종료 코드를 꺼낸다. 훅은 성공 0 / 기록 포기 1 이다 —
// 다른 코드가 나오면 MediaMTX 로그에서 원인을 가릴 수 없다.
func exitCode(t *testing.T, err error) int {
	t.Helper()
	if err == nil {
		return 0
	}
	var exitErr *exec.ExitError
	if !errors.As(err, &exitErr) {
		t.Fatalf("종료 코드를 알 수 없는 실패다: %v", err)
	}
	return exitErr.ExitCode()
}

// runWriter 는 훅 프로세스 하나를 흉내 낸다. MediaMTX 는 훅에 MTX_* 를 환경변수로 넘긴다.
func runWriter(t *testing.T, spool, kind string, env map[string]string) (stderr string, err error) {
	t.Helper()
	return runWriterArgs(t, []string{"-kind", kind, "-spool", spool}, "", env)
}

// runWriterArgs 는 인자와 HOOK_SPOOL_PATH 를 직접 정하고 훅 프로세스를 돌린다.
// 프로덕션은 -spool 없이 도므로 그 형태를 그대로 재현할 수 있어야 한다.
func runWriterArgs(t *testing.T, args []string, spoolEnv string, env map[string]string) (stderr string, err error) {
	t.Helper()
	cmd := exec.Command(binPath, args...)
	cmd.Env = append(os.Environ(), "HOOK_SPOOL_PATH="+spoolEnv)
	for k, v := range env {
		cmd.Env = append(cmd.Env, k+"="+v)
	}
	var buf strings.Builder
	cmd.Stderr = &buf
	err = cmd.Run()
	return buf.String(), err
}

func readLines(t *testing.T, path string) []string {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("스풀 읽기 실패: %v", err)
	}
	if len(raw) == 0 {
		return nil
	}
	if raw[len(raw)-1] != '\n' {
		t.Fatalf("스풀 마지막 바이트가 개행이 아니다: %q", raw[len(raw)-1])
	}
	return strings.Split(strings.TrimSuffix(string(raw), "\n"), "\n")
}

// 정상 기록 1건이 Reader 의 ParseLine 을 그대로 통과해야 한다.
// writer 와 Reader 는 같은 스풀 계약의 양쪽이므로 여기서 함께 못 박는다.
func TestWrittenLineIsParsableByReader(t *testing.T) {
	spool := filepath.Join(t.TempDir(), "events.jsonl")

	if _, err := runWriter(t, spool, "online", map[string]string{
		"MTX_PATH":      "demo",
		"MTX_SOURCE_ID": "src-1",
	}); err != nil {
		t.Fatalf("정상 기록이 실패했다: %v", err)
	}

	lines := readLines(t, spool)
	if len(lines) != 1 {
		t.Fatalf("줄 수 = %d, 기대 1 (%q)", len(lines), lines)
	}
	ev, err := mtxhook.ParseLine([]byte(lines[0]))
	if err != nil {
		t.Fatalf("Reader 가 못 읽는 줄을 썼다: %v (줄=%q)", err, lines[0])
	}
	if ev.Kind != mtxhook.KindOnline {
		t.Errorf("Kind = %d, 기대 KindOnline", ev.Kind)
	}
	if ev.StreamID != "demo" {
		t.Errorf("StreamID = %q, 기대 demo", ev.StreamID)
	}
	if ev.SourceID != "src-1" {
		t.Errorf("SourceID = %q, 기대 src-1", ev.SourceID)
	}
	if d := time.Since(ev.At); d < 0 || d > time.Minute {
		t.Errorf("At = %s, 지금과 너무 멀다(경과 %v)", ev.At, d)
	}
}

// at_unix_nano 는 **정수**여야 한다. 소수점·따옴표가 붙으면 Reader 의 int64 언마샬이 깨진다.
func TestAtUnixNanoIsWrittenAsInteger(t *testing.T) {
	spool := filepath.Join(t.TempDir(), "events.jsonl")

	if _, err := runWriter(t, spool, "offline", map[string]string{"MTX_PATH": "demo"}); err != nil {
		t.Fatalf("정상 기록이 실패했다: %v", err)
	}

	var raw map[string]json.RawMessage
	if err := json.Unmarshal([]byte(readLines(t, spool)[0]), &raw); err != nil {
		t.Fatalf("JSON 이 아니다: %v", err)
	}
	at, ok := raw["at_unix_nano"]
	if !ok {
		t.Fatalf("at_unix_nano 키가 없다: %v", raw)
	}
	if !regexp.MustCompile(`^[0-9]+$`).Match(at) {
		t.Errorf("at_unix_nano = %s, 기대 부호·소수점·따옴표 없는 정수", at)
	}
}

// segcomplete 는 세그먼트 필드까지 실어야 한다 — 그것이 훅 채널의 존재 이유다.
func TestSegmentCompleteCarriesSegmentFields(t *testing.T) {
	spool := filepath.Join(t.TempDir(), "events.jsonl")

	if _, err := runWriter(t, spool, "segcomplete", map[string]string{
		"MTX_PATH":             "demo",
		"MTX_SEGMENT_PATH":     "/recordings/demo/2026-08-04_10-00-00-000000.mp4",
		"MTX_SEGMENT_DURATION": "4.008",
	}); err != nil {
		t.Fatalf("정상 기록이 실패했다: %v", err)
	}

	ev, err := mtxhook.ParseLine([]byte(readLines(t, spool)[0]))
	if err != nil {
		t.Fatalf("Reader 가 못 읽는 줄을 썼다: %v", err)
	}
	if ev.Kind != mtxhook.KindSegmentComplete {
		t.Fatalf("Kind = %d, 기대 KindSegmentComplete", ev.Kind)
	}
	if ev.SegmentPath != "/recordings/demo/2026-08-04_10-00-00-000000.mp4" {
		t.Errorf("SegmentPath = %q", ev.SegmentPath)
	}
	if ev.SegmentDurationMS != 4008 {
		t.Errorf("SegmentDurationMS = %d, 기대 4008", ev.SegmentDurationMS)
	}
}

// 4096B 를 넘는 줄은 **쓰지 않는다**. 반쯤 쓴 줄은 스풀 전체를 오염시키기 때문이다.
func TestOversizedLineIsNotWritten(t *testing.T) {
	spool := filepath.Join(t.TempDir(), "events.jsonl")

	stderr, err := runWriter(t, spool, "online", map[string]string{
		"MTX_PATH":      "demo",
		"MTX_SOURCE_ID": strings.Repeat("a", 5000),
	})
	if err == nil {
		t.Fatal("상한 초과인데 성공했다")
	}
	if code := exitCode(t, err); code != 1 {
		t.Errorf("종료 코드 = %d, 기대 1", code)
	}
	if strings.Count(strings.TrimSuffix(stderr, "\n"), "\n") != 0 {
		t.Errorf("stderr 가 1줄이 아니다: %q", stderr)
	}
	if stderr == "" {
		t.Error("기록을 포기했는데 stderr 가 비었다 — 무징후 유실이다")
	}
	if _, statErr := os.Stat(spool); !os.IsNotExist(statErr) {
		t.Errorf("기록을 포기했는데 스풀이 생겼다: %v (내용=%q)", statErr, readLines(t, spool))
	}
}

// 알 수 없는 kind 는 거부한다 — 스풀에 들어가면 Reader 가 그 줄을 버리게 된다.
func TestUnknownKindIsRejected(t *testing.T) {
	spool := filepath.Join(t.TempDir(), "events.jsonl")

	stderr, err := runWriter(t, spool, "ready", map[string]string{"MTX_PATH": "demo"})
	if err == nil {
		t.Fatal("알 수 없는 kind 인데 성공했다")
	}
	if code := exitCode(t, err); code != 1 {
		t.Errorf("종료 코드 = %d, 기대 1", code)
	}
	if !strings.Contains(stderr, "ready") {
		t.Errorf("stderr 에 거부 사유가 없다: %q", stderr)
	}
	if _, statErr := os.Stat(spool); !os.IsNotExist(statErr) {
		t.Errorf("거부했는데 스풀이 생겼다: %v", statErr)
	}
}

// flock 을 200ms 안에 못 잡으면 기록을 포기한다.
// 훅 프로세스가 잠금을 기다리며 체류하면 MediaMTX 안에 프로세스가 쌓인다.
func TestLockContentionAbortsAfterWaitLimit(t *testing.T) {
	spool := filepath.Join(t.TempDir(), "events.jsonl")
	holder, err := os.OpenFile(spool, os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		t.Fatalf("스풀 선점 실패: %v", err)
	}
	defer holder.Close()
	if err := syscall.Flock(int(holder.Fd()), syscall.LOCK_EX); err != nil {
		t.Fatalf("flock 선점 실패: %v", err)
	}
	defer syscall.Flock(int(holder.Fd()), syscall.LOCK_UN)

	start := time.Now()
	stderr, runErr := runWriter(t, spool, "online", map[string]string{"MTX_PATH": "demo"})
	elapsed := time.Since(start)

	if runErr == nil {
		t.Fatal("잠금이 잡혀 있는데 기록에 성공했다 — flock 을 잡지 않았다는 뜻이다")
	}
	if stderr == "" {
		t.Error("기록을 포기했는데 stderr 가 비었다")
	}
	if elapsed < 200*time.Millisecond {
		t.Errorf("경과 %v — 200ms 를 기다리지 않고 포기했다", elapsed)
	}
	// 상한을 3s로 넉넉히 잡는다. 벽시계 단언을 조이면 병렬 부하에서 프로세스 기동만으로
	// 1.5s를 넘겨 무작위로 깨진다(8병렬 실측). 상한값이 늘어나는 회귀를 막는 일은
	// TestLockWaitLimitIsTwoHundredMillis 의 상수 핀이 결정적으로 담당하므로,
	// 여기서는 "무한정 기다리지는 않는다"만 본다.
	if elapsed > 3*time.Second {
		t.Errorf("경과 %v — 대기 상한이 아예 걸리지 않는다", elapsed)
	}
	if code := exitCode(t, runErr); code != 1 {
		t.Errorf("종료 코드 = %d, 기대 1", code)
	}
	if lines := readLines(t, spool); len(lines) != 0 {
		t.Errorf("포기했는데 줄이 쓰였다: %q", lines)
	}
}

// 잠금은 **배타**(LOCK_EX)여야 한다.
//
// 왜 별도 테스트인가: 위 경합 테스트는 LOCK_SH 뮤턴트를 죽이지 못한다. 그 테스트는
// 배타 잠금을 쥐고 있으므로 writer 가 공유 잠금을 요청해도 어차피 EWOULDBLOCK 이라
// 똑같이 실패하기 때문이다. 그래서 여기서는 반대로 **공유 잠금**을 쥐고 시험한다 —
// 배타를 요구하는 writer 는 실패해야 하고, LOCK_SH 로 바뀐 writer 는 성공해 버린다.
// 그 순간 다중 스트림 동시 발화의 인터리브 방어가 통째로 사라진다.
func TestWriterDemandsExclusiveLockNotShared(t *testing.T) {
	spool := filepath.Join(t.TempDir(), "events.jsonl")
	holder, err := os.OpenFile(spool, os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		t.Fatalf("스풀 선점 실패: %v", err)
	}
	defer holder.Close()
	if err := syscall.Flock(int(holder.Fd()), syscall.LOCK_SH); err != nil {
		t.Fatalf("공유 잠금 선점 실패: %v", err)
	}
	defer syscall.Flock(int(holder.Fd()), syscall.LOCK_UN)

	// 픽스처가 헛돌지 않는지 먼저 확인한다 — 파일시스템이 flock 을 무시하면 아래 단언이
	// 무의미해진다. flock 은 열린 파일 서술자 단위라 같은 프로세스의 다른 fd 도 충돌한다.
	probe, err := os.OpenFile(spool, os.O_WRONLY, 0o644)
	if err != nil {
		t.Fatalf("probe 열기 실패: %v", err)
	}
	defer probe.Close()
	if err := syscall.Flock(int(probe.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); !errors.Is(err, syscall.EWOULDBLOCK) {
		syscall.Flock(int(probe.Fd()), syscall.LOCK_UN)
		t.Fatalf("공유 잠금 중인데 배타 잠금이 잡혔다(err=%v) — 이 파일시스템에서는 flock 이 안 듣는다", err)
	}

	_, runErr := runWriter(t, spool, "online", map[string]string{"MTX_PATH": "demo"})
	if runErr == nil {
		t.Fatal("공유 잠금이 걸려 있는데 기록에 성공했다 — 잠금이 배타가 아니다")
	}
	if lines := readLines(t, spool); len(lines) != 0 {
		t.Errorf("포기했는데 줄이 쓰였다: %q", lines)
	}
}

// 동시 8프로세스가 같은 스풀에 덧붙여도 줄이 섞이지 않는다(flock 계약).
// 줄이 섞이면 Reader 가 그 줄들을 버리고 그만큼 훅 이벤트가 무징후로 사라진다.
func TestConcurrentWritersDoNotInterleave(t *testing.T) {
	const writers = 8
	spool := filepath.Join(t.TempDir(), "events.jsonl")

	// 한 줄을 길게 만들어 write 가 한 번에 끝나지 않을 여지를 키운다(상한 4096 미만은 유지).
	padding := strings.Repeat("p", 3000)

	var wg sync.WaitGroup
	errs := make([]error, writers)
	for i := range writers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, errs[i] = runWriter(t, spool, "online", map[string]string{
				"MTX_PATH":      fmt.Sprintf("stream%d", i),
				"MTX_SOURCE_ID": padding,
			})
		}()
	}
	wg.Wait()

	for i, err := range errs {
		if err != nil {
			t.Fatalf("writer %d 실패: %v", i, err)
		}
	}

	lines := readLines(t, spool)
	if len(lines) != writers {
		t.Fatalf("줄 수 = %d, 기대 %d", len(lines), writers)
	}
	seen := make(map[string]bool, writers)
	for _, line := range lines {
		ev, err := mtxhook.ParseLine([]byte(line))
		if err != nil {
			t.Fatalf("섞인 줄이 있다: %v (줄 앞 120B=%q)", err, line[:min(len(line), 120)])
		}
		seen[ev.StreamID] = true
	}
	for i := range writers {
		if !seen[fmt.Sprintf("stream%d", i)] {
			t.Errorf("stream%d 의 줄이 없다", i)
		}
	}
}

// 스풀은 사이드카(다른 UID)가 읽어야 한다. umask 가 생성 모드를 깎으면
// root 소유 0600 이 되어 :ro 로 붙여도 Reader 가 못 읽는다 — 무징후 강등이다.
//
// **t.Parallel 을 쓰지 않는다**: umask 는 프로세스 전역이라 이 테스트가 도는 동안
// 같은 테스트 바이너리의 다른 테스트가 파일을 만들면 그쪽 권한까지 함께 깎인다.
func TestSpoolIsCreatedWorldReadableUnderRestrictiveUmask(t *testing.T) {
	spool := filepath.Join(t.TempDir(), "events.jsonl")

	old := syscall.Umask(0o077)
	defer syscall.Umask(old)

	if _, err := runWriter(t, spool, "online", map[string]string{"MTX_PATH": "demo"}); err != nil {
		t.Fatalf("정상 기록이 실패했다: %v", err)
	}

	fi, err := os.Stat(spool)
	if err != nil {
		t.Fatalf("스풀 stat 실패: %v", err)
	}
	if perm := fi.Mode().Perm(); perm != 0o644 {
		t.Errorf("스풀 모드 = %04o, 기대 0644", perm)
	}
}

// **이미 있는 파일의 권한은 건드리지 않는다.** 그 값은 운영자의 것이다 —
// 훅이 매번 chmod 로 덮으면 운영자가 좁혀 둔 권한이 조용히 원복된다.
// (뒤집어 말하면, 스풀이 이미 0600 으로 있으면 사이드카가 못 읽는다. 그 상태는
//
//	운영자가 만든 것이므로 도구가 말없이 고치지 않고 그대로 둔다.)
func TestExistingSpoolPermissionsAreLeftAlone(t *testing.T) {
	spool := filepath.Join(t.TempDir(), "events.jsonl")
	if err := os.WriteFile(spool, nil, 0o600); err != nil {
		t.Fatalf("스풀 선생성 실패: %v", err)
	}

	if _, err := runWriter(t, spool, "online", map[string]string{"MTX_PATH": "demo"}); err != nil {
		t.Fatalf("정상 기록이 실패했다: %v", err)
	}

	fi, err := os.Stat(spool)
	if err != nil {
		t.Fatalf("스풀 stat 실패: %v", err)
	}
	if perm := fi.Mode().Perm(); perm != 0o600 {
		t.Errorf("스풀 모드 = %04o, 기대 0600(선생성 권한 유지)", perm)
	}
	if lines := readLines(t, spool); len(lines) != 1 {
		t.Errorf("줄 수 = %d, 기대 1 — 권한을 안 건드려도 기록은 돼야 한다", len(lines))
	}
}

// 프로덕션 훅 명령에는 -spool 이 없다(infra/compose/mediamtx.yml). 그 형태에서
// HOOK_SPOOL_PATH 가 실제로 기록 위치를 정하는지 바이너리로 확인한다 —
// 이 분기가 깨져도 기본값 /hooks/events.jsonl 로 새어 나가 조용히 엉뚱한 곳에 쌓인다.
func TestSpoolEnvDecidesLocationWhenFlagIsAbsent(t *testing.T) {
	spool := filepath.Join(t.TempDir(), "events.jsonl")

	if _, err := runWriterArgs(t, []string{"-kind", "online"}, spool,
		map[string]string{"MTX_PATH": "demo"}); err != nil {
		t.Fatalf("정상 기록이 실패했다: %v", err)
	}

	lines := readLines(t, spool)
	if len(lines) != 1 {
		t.Fatalf("줄 수 = %d, 기대 1 — HOOK_SPOOL_PATH 가 무시됐다", len(lines))
	}
	ev, err := mtxhook.ParseLine([]byte(lines[0]))
	if err != nil {
		t.Fatalf("Reader 가 못 읽는 줄을 썼다: %v", err)
	}
	if ev.StreamID != "demo" {
		t.Errorf("StreamID = %q, 기대 demo", ev.StreamID)
	}
}

// -h 는 사용법을 보여 주고 정상 종료한다.
// 이미지에 바이너리가 실제로 실렸는지 확인하는 유일한 수단이다(베이스가 scratch 라 셸이 없다).
func TestHelpFlagExitsZero(t *testing.T) {
	cmd := exec.Command(binPath, "-h")
	var buf strings.Builder
	cmd.Stderr = &buf
	cmd.Stdout = &buf
	if err := cmd.Run(); err != nil {
		t.Fatalf("-h 가 실패했다: %v (출력=%q)", err, buf.String())
	}
	if !strings.Contains(buf.String(), "-kind") {
		t.Errorf("사용법에 -kind 가 없다: %q", buf.String())
	}
}
