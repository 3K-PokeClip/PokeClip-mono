package ingestauth

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"maps"
	"os"
	"runtime"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"
)

// 시험 값은 모두 합성이다. 토큰은 토큰 꼴(26자 Crockford 대문자 — streamkey.IsToken)만 맞춘 값이고,
// 어떤 계정의 키도 아니다.
const (
	testTokenA = "SYNTHET1CTEST000000000000A"
	testTokenB = "SYNTHET1CTEST000000000000B"
)

// logEvent 는 시험이 보는 slog JSON 한 줄이다. 이 패키지가 내는 필드만 담는다.
type logEvent struct {
	Level   string `json:"level"`
	Msg     string `json:"msg"`
	Denied  int    `json:"denied"`
	Added   int    `json:"added"`
	Removed int    `json:"removed"`
	Reason  string `json:"reason"`
	Lines   int    `json:"lines"`
	Bytes   int64  `json:"bytes"`
}

// logRecorder 는 slog JSON 출력을 모은다. 여러 고루틴이 함께 써도 된다.
type logRecorder struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

// newLogRecorder 는 모든 등급을 rec 로 모으는 로거를 만든다.
func newLogRecorder() (*slog.Logger, *logRecorder) {
	rec := &logRecorder{}
	return slog.New(slog.NewJSONHandler(rec, &slog.HandlerOptions{Level: slog.LevelDebug})), rec
}

func (r *logRecorder) Write(p []byte) (int, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.buf.Write(p)
}

// String 은 지금까지 모은 출력 전체다.
func (r *logRecorder) String() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.buf.String()
}

// events 는 msg 가 name 인 이벤트를 낸 차례대로 돌려준다.
func (r *logRecorder) events(t *testing.T, name string) []logEvent {
	t.Helper()
	var got []logEvent
	sc := bufio.NewScanner(bytes.NewBufferString(r.String()))
	for sc.Scan() {
		var e logEvent
		if err := json.Unmarshal(sc.Bytes(), &e); err != nil {
			t.Fatalf("slog 출력 %q 을 해석하지 못했다: %v", sc.Text(), err)
		}
		if e.Msg == name {
			got = append(got, e)
		}
	}
	return got
}

// discardLogger 는 출력을 버리는 로거다 — 신호를 보지 않는 시험이 쓴다.
func discardLogger() *slog.Logger {
	return slog.New(slog.DiscardHandler)
}

// writeFile 은 path 를 content 로 새로 쓴다.
func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatalf("Setup: %s 를 쓰지 못했다: %v", path, err)
	}
}

// removeFile 은 path 를 지운다.
func removeFile(t *testing.T, path string) {
	t.Helper()
	if err := os.Remove(path); err != nil {
		t.Fatalf("Setup: %s 를 지우지 못했다: %v", path, err)
	}
}

// chmod 는 path 의 모드를 바꾸고, 시험이 끝나면 t.TempDir 정리가 되도록 0o755 로 되돌린다.
func chmod(t *testing.T, path string, mode os.FileMode) {
	t.Helper()
	if err := os.Chmod(path, mode); err != nil {
		t.Fatalf("Setup: %s 의 모드를 %v 로 바꾸지 못했다: %v", path, mode, err)
	}
	t.Cleanup(func() {
		if err := os.Chmod(path, 0o755); err != nil {
			t.Errorf("Cleanup: %s 의 모드를 되돌리지 못했다: %v", path, err)
		}
	})
}

// fakeClock 은 부를 때마다 1ms 씩 가는 시계다. 줄마다 at 이 달라 줄 순서와 at 순서를 견줄 수 있다.
// 여러 고루틴이 함께 불러도 된다.
type fakeClock struct {
	mu   sync.Mutex
	next time.Time
}

func newFakeClock() *fakeClock {
	return &fakeClock{next: time.Date(2026, 10, 12, 9, 0, 0, 0, time.UTC)}
}

// Now 는 다음 시각을 돌려준다. 돌려주기 전에 다른 고루틴에 차례를 넘긴다 — 시각을 받은 뒤 잠금을
// 잡기까지의 틈을 넓혀, at 을 잠금 밖에서 찍는 구현이면 줄 순서가 뒤집히게 한다.
func (c *fakeClock) Now() time.Time {
	c.mu.Lock()
	t := c.next
	c.next = t.Add(time.Millisecond)
	c.mu.Unlock()
	runtime.Gosched()
	return t
}

// openTestConnLog 는 dir 에 ConnLog 를 열고, 그 회전 신호를 rec 로 모은다. 시험이 끝나면 연 파일을 닫는다.
func openTestConnLog(t *testing.T, dir string, maxBytes int64, clock *fakeClock) (*ConnLog, *logRecorder) {
	t.Helper()
	c, err := OpenConnLog(dir, maxBytes, clock.Now)
	if err != nil {
		t.Fatalf("Setup: OpenConnLog(%q, %d) returned error: %v", dir, maxBytes, err)
	}
	log, rec := newLogRecorder()
	c.log = log
	t.Cleanup(func() { closeConnLog(c) })
	return c, rec
}

// closeConnLog 는 c 가 연 현재 세대 파일을 닫는다. ConnLog 에는 Close 가 없다(응답기 프로세스 수명 동안
// 연다) — 시험마다 연 파일이 쌓이지 않게 하려는 시험 도우미다.
func closeConnLog(c *ConnLog) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.f != nil {
		c.f.Close() // 시험이 끝난 뒤의 정리다 — 닫기 실패가 판정을 바꾸지 않는다
		c.f = nil
	}
}

// recordedLine 은 시험이 기록 파일을 LoadAllowed 와 따로 해석한 줄이다. Keys 는 그 줄의 JSON 키(바이트 순)다.
type recordedLine struct {
	At   time.Time `json:"at"`
	Path string    `json:"path"`
	ID   string    `json:"id"`
	Keys []string  `json:"-"`
}

// isStartMark 는 l 이 시작 표시(at 만 있는 줄)인지 본다.
func (l recordedLine) isStartMark() bool {
	return slices.Equal(l.Keys, []string{"at"}) && !l.At.IsZero()
}

// parseLines 는 기록 파일 내용의 줄을 모두 해석한다. 온전한 파일만 받는다 — 줄바꿈으로 끝나지 않거나
// JSON 이 아닌 줄이 있으면 에러다.
func parseLines(data []byte) ([]recordedLine, error) {
	var lines []recordedLine
	for raw := range strings.Lines(string(data)) {
		if !strings.HasSuffix(raw, "\n") {
			return nil, fmt.Errorf("line %q does not end in a newline", raw)
		}
		var l recordedLine
		var keys map[string]any
		if err := errors.Join(json.Unmarshal([]byte(raw), &l), json.Unmarshal([]byte(raw), &keys)); err != nil {
			return nil, fmt.Errorf("line %q is not a JSON object: %v", raw, err)
		}
		l.Keys = slices.Sorted(maps.Keys(keys))
		lines = append(lines, l)
	}
	return lines, nil
}

// readLines 는 path 의 줄을 모두 해석한다.
func readLines(t *testing.T, path string) []recordedLine {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("Setup: %v", err)
	}
	lines, err := parseLines(data)
	if err != nil {
		t.Fatalf("%s: %v", path, err)
	}
	return lines
}

// lastLineOf 는 path 의 마지막 줄이다. 고루틴 안에서 부르므로 t 를 받지 않고 에러를 돌려준다.
func lastLineOf(path string) (recordedLine, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return recordedLine{}, err
	}
	lines, err := parseLines(data)
	if err != nil {
		return recordedLine{}, err
	}
	if len(lines) == 0 {
		return recordedLine{}, fmt.Errorf("%s has no lines", path)
	}
	return lines[len(lines)-1], nil
}
