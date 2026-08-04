package main_test

import (
	"encoding/json"
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
	dir, err := os.MkdirTemp("", "mtxhookwrite-bin")
	if err != nil {
		fmt.Fprintln(os.Stderr, "임시 디렉토리 생성 실패:", err)
		os.Exit(1)
	}
	defer os.RemoveAll(dir)

	binPath = filepath.Join(dir, "mtxhookwrite")
	build := exec.Command("go", "build", "-o", binPath, ".")
	if out, err := build.CombinedOutput(); err != nil {
		fmt.Fprintf(os.Stderr, "mtxhookwrite 빌드 실패: %v\n%s", err, out)
		os.Exit(1)
	}

	code := m.Run()
	os.RemoveAll(dir)
	os.Exit(code)
}

// runWriter 는 훅 프로세스 하나를 흉내 낸다. MediaMTX 는 훅에 MTX_* 를 환경변수로 넘긴다.
func runWriter(t *testing.T, spool, kind string, env map[string]string) (stderr string, err error) {
	t.Helper()
	cmd := exec.Command(binPath, "-kind", kind, "-spool", spool)
	cmd.Env = append(os.Environ(), "HOOK_SPOOL_PATH=")
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
	if elapsed > 3*time.Second {
		t.Errorf("경과 %v — 대기 상한이 걸리지 않는다", elapsed)
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
