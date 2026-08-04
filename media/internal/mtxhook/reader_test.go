package mtxhook

import (
	"context"
	"log/slog"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
	"time"
)

// ---------------------------------------------------------------------------
// 픽스처
// ---------------------------------------------------------------------------

// logCapture 는 "침묵 실패가 아님"을 테스트가 직접 확인할 수 있게 한다.
type logCapture struct {
	mu      sync.Mutex
	records []slog.Record
}

func (c *logCapture) Enabled(context.Context, slog.Level) bool { return true }

func (c *logCapture) Handle(_ context.Context, r slog.Record) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.records = append(c.records, r)
	return nil
}

func (c *logCapture) WithAttrs([]slog.Attr) slog.Handler { return c }
func (c *logCapture) WithGroup(string) slog.Handler      { return c }

func (c *logCapture) count(msg string) int {
	c.mu.Lock()
	defer c.mu.Unlock()
	n := 0
	for _, r := range c.records {
		if r.Message == msg {
			n++
		}
	}
	return n
}

type readerFixture struct {
	t     *testing.T
	spool string
	logs  *logCapture
	r     *Reader
}

// newReaderFixture 는 스풀 파일을 만들지 않은 채 Reader 만 준비한다.
// 스풀 생성 여부를 테스트마다 다르게 잡기 위해서다.
func newReaderFixture(t *testing.T, maxLineBytes int) *readerFixture {
	t.Helper()
	f := &readerFixture{
		t:     t,
		spool: filepath.Join(t.TempDir(), "events.jsonl"),
		logs:  &logCapture{},
	}
	r, err := NewReader(ReaderOptions{
		SpoolPath:    f.spool,
		PollInterval: 5 * time.Millisecond,
		MaxLineBytes: maxLineBytes,
		Log:          slog.New(f.logs),
	})
	if err != nil {
		t.Fatalf("NewReader 실패: %v", err)
	}
	f.r = r
	return f
}

// start 는 Reader 를 띄우고 t.Cleanup 으로 반드시 멈춘다.
func (f *readerFixture) start() {
	f.t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	if err := f.r.Start(ctx); err != nil {
		cancel()
		f.t.Fatalf("Start 실패: %v", err)
	}
	f.t.Cleanup(func() {
		cancel()
		select {
		case <-f.r.Done():
		case <-time.After(2 * time.Second):
			f.t.Error("Reader 가 ctx 취소 후에도 멈추지 않았다")
		}
	})
}

// write 는 스풀에 문자열을 그대로 덧붙인다(개행은 호출자가 넣는다).
func (f *readerFixture) write(s string) {
	f.t.Helper()
	fh, err := os.OpenFile(f.spool, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		f.t.Fatalf("스풀 열기 실패: %v", err)
	}
	defer fh.Close()
	if _, err := fh.WriteString(s); err != nil {
		f.t.Fatalf("스풀 쓰기 실패: %v", err)
	}
}

// replace 는 스풀을 짧은 내용으로 통째로 갈아 끼운다(트렁케이트 재현).
func (f *readerFixture) replace(s string) {
	f.t.Helper()
	if err := os.WriteFile(f.spool, []byte(s), 0o644); err != nil {
		f.t.Fatalf("스풀 교체 실패: %v", err)
	}
}

// next 는 이벤트 1개를 기다린다. 오지 않으면 실패다.
func (f *readerFixture) next() Event {
	f.t.Helper()
	select {
	case ev, ok := <-f.r.Events():
		if !ok {
			f.t.Fatal("Events 채널이 닫혔다")
		}
		return ev
	case <-time.After(2 * time.Second):
		f.t.Fatal("이벤트가 오지 않았다")
		return Event{}
	}
}

// waitFor 는 조건이 참이 될 때까지 폴링한다.
func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("%s: 시간 안에 성립하지 않았다", what)
}

// line 은 온전한 스풀 줄(개행 포함)을 만든다.
func line(kind, path string, nano int64) string {
	return `{"kind":"` + kind + `","at_unix_nano":` + strconv.FormatInt(nano, 10) + `,"MTX_PATH":"` + path + `"}` + "\n"
}

// ---------------------------------------------------------------------------
// 테스트
// ---------------------------------------------------------------------------

// Start 는 동기이며 반환 시점에 시작 오프셋이 확정돼 있어야 한다.
// 과거 이벤트를 재생하면 이미 지나간 세션 경계로 엉뚱한 조각에 불연속이 붙는다.
func TestStartFixesOffsetAtEOFAndSkipsPastEvents(t *testing.T) {
	f := newReaderFixture(t, 0)
	f.write(line("online", "past", 1784000000000000000))
	f.start()

	f.write(line("online", "fresh", 1784000000000000001))

	ev := f.next()
	if ev.StreamID != "fresh" {
		t.Fatalf("StreamID = %q, want %q — Start 이전 줄이 재생됐다", ev.StreamID, "fresh")
	}
}

// 스풀이 아직 없는 것은 정상이다(새 볼륨의 첫 송출 전). 소음이 되지 않게 WARN 은 1회뿐이고,
// 생긴 뒤에는 **처음부터** 읽는다.
func TestMissingSpoolWarnsOnceThenReadsFromStart(t *testing.T) {
	f := newReaderFixture(t, 0)
	f.start()

	waitFor(t, "hook_spool_missing WARN", func() bool { return f.logs.count("hook_spool_missing") >= 1 })
	time.Sleep(50 * time.Millisecond) // 여러 폴 주기를 지나 보낸다

	f.write(line("online", "demo", 1784000000000000000))
	if ev := f.next(); ev.StreamID != "demo" {
		t.Errorf("StreamID = %q, want demo", ev.StreamID)
	}
	if n := f.logs.count("hook_spool_missing"); n != 1 {
		t.Errorf("hook_spool_missing = %d회, want 1회", n)
	}
}

// 부재 경고의 1회 억제는 **회복하면 풀려야** 한다(hook_spool_not_regular 와 대칭).
// 억제가 영구히 남으면 운영 중 스풀이 사라진 사고(볼륨 해제·오삭제)가 로그 한 줄 없이
// 지나가고, 훅 채널이 조용히 죽는다 — 무징후 강등은 이 설계가 제일 피하려는 실패 모드다.
func TestMissingSpoolWarnsAgainAfterSpoolRecovers(t *testing.T) {
	f := newReaderFixture(t, 0)
	f.start()

	waitFor(t, "첫 hook_spool_missing WARN", func() bool { return f.logs.count("hook_spool_missing") >= 1 })

	// 스풀이 생겨 정상으로 회복된다.
	f.write(line("online", "demo", 1784000000000000000))
	if ev := f.next(); ev.StreamID != "demo" {
		t.Fatalf("StreamID = %q, want demo", ev.StreamID)
	}

	// 다시 사라진다 — 이번 부재도 경고돼야 한다.
	if err := os.Remove(f.spool); err != nil {
		t.Fatalf("스풀 삭제 실패: %v", err)
	}
	waitFor(t, "회복 후 두 번째 hook_spool_missing WARN", func() bool {
		return f.logs.count("hook_spool_missing") >= 2
	})
}

// T13 — 훅 write 와 폴링이 겹치는 순간의 부분 줄을 버리면 그 이벤트가 사라진다.
// 개행이 없는 꼬리는 carry 에 남겨 다음 폴에서 이어 붙인다.
func TestPartialLineIsCarriedToNextPoll(t *testing.T) {
	f := newReaderFixture(t, 0)
	f.start()

	full := `{"kind":"segcomplete","at_unix_nano":1784000000000000000,"MTX_PATH":"demo","MTX_SEGMENT_PATH":"/r/demo/x.mp4"}` + "\n"
	head, tail := full[:40], full[40:]

	f.write(head)
	time.Sleep(30 * time.Millisecond) // 개행 없는 상태로 폴을 여러 번 지나 보낸다
	f.write(tail)

	ev := f.next()
	if ev.Kind != KindSegmentComplete || ev.StreamID != "demo" {
		t.Errorf("이어 붙인 줄 파싱 결과 = %+v", ev)
	}
	if n := f.logs.count("hook_line_invalid"); n != 0 {
		t.Errorf("hook_line_invalid = %d회, want 0 — 부분 줄을 버렸다", n)
	}
}

// T14 — 크기가 오프셋보다 줄었으면 파일이 갈렸다는 뜻이다. 그대로 두면 영원히 못 읽는다.
func TestTruncatedSpoolResetsOffset(t *testing.T) {
	f := newReaderFixture(t, 0)
	f.write(line("online", "first", 1784000000000000000))
	f.write(line("online", "second", 1784000000000000001))
	f.start()
	f.write(line("online", "third", 1784000000000000002))
	if ev := f.next(); ev.StreamID != "third" {
		t.Fatalf("StreamID = %q, want third", ev.StreamID)
	}

	f.replace(line("online", "after", 1784000000000000003))

	if ev := f.next(); ev.StreamID != "after" {
		t.Errorf("StreamID = %q, want after", ev.StreamID)
	}
	if n := f.logs.count("hook_spool_truncated"); n != 1 {
		t.Errorf("hook_spool_truncated = %d회, want 1회", n)
	}
}

// T15 — 손상된 줄이 무한히 자라면 메모리를 먹는다. 상한을 넘으면 그 줄만 버리고
// 다음 개행부터 정상 재개해야 한다(스트림 전체를 멈추지 않는다).
func TestOverlongLineIsDroppedAndReadingResumes(t *testing.T) {
	// 상한은 정상 줄(70B)보다는 넉넉하고 손상 줄(300B)보다는 작아야 한다.
	// 상한을 정상 줄보다 작게 잡으면 멀쩡한 줄까지 버려져 테스트가 제 뜻을 잃는다.
	f := newReaderFixture(t, 128)
	f.start()

	garbage := make([]byte, 300)
	for i := range garbage {
		garbage[i] = 'x'
	}
	f.write(string(garbage) + "\n")
	f.write(line("online", "demo", 1784000000000000000))

	if ev := f.next(); ev.StreamID != "demo" {
		t.Errorf("StreamID = %q, want demo — 폐기 후 재개하지 못했다", ev.StreamID)
	}
	if n := f.logs.count("hook_line_overflow"); n < 1 {
		t.Errorf("hook_line_overflow = %d회, want 1회 이상", n)
	}
}

// 상한 초과 줄의 잔여가 **폴 경계를 넘어** 도착하면, 그 잔여를 carry 에 쌓으면 안 된다.
// 쌓으면 뒤이어 오는 첫 정상 줄이 쓰레기와 붙어 통째로 유실된다.
//
// 앞선 테스트(한 chunk 안에서 초과와 개행이 함께 오는 경우)는 이 누수를 잡지 못한다 —
// 반드시 write 를 폴 경계로 쪼개야 재현된다.
func TestOverflowRemnantAcrossPollsDoesNotEatNextLine(t *testing.T) {
	f := newReaderFixture(t, 128)
	f.start()

	// ① 상한을 넘는 쓰레기를 개행 없이 쓴다 → 초과 판정, 다음 개행까지 건너뛰기 시작.
	garbage := make([]byte, 300)
	for i := range garbage {
		garbage[i] = 'x'
	}
	f.write(string(garbage))
	waitFor(t, "hook_line_overflow", func() bool { return f.logs.count("hook_line_overflow") >= 1 })

	// ② 같은 줄의 잔여가 다음 폴에서 도착한다(상한보다 작아 초과 판정에 걸리지 않는다).
	f.write("남은쓰레기")
	time.Sleep(30 * time.Millisecond)

	// ③ 개행으로 그 줄이 끝나고, 곧바로 정상 줄이 온다.
	f.write("\n" + line("online", "demo", 1784000000000000000))

	ev := f.next()
	if ev.StreamID != "demo" {
		t.Errorf("StreamID = %q, want demo — 정상 줄이 잔여 쓰레기와 붙었다", ev.StreamID)
	}
	if n := f.logs.count("hook_line_invalid"); n != 0 {
		t.Errorf("hook_line_invalid = %d회, want 0회 — 정상 줄이 오염됐다", n)
	}
}

// 파싱 실패 줄은 그 줄만 버린다. 하나 때문에 뒤의 정상 줄까지 멈추면 안 된다.
func TestInvalidLineIsSkipped(t *testing.T) {
	f := newReaderFixture(t, 0)
	f.start()

	f.write("이건 JSON 이 아니다\n")
	f.write(line("online", "demo", 1784000000000000000))

	if ev := f.next(); ev.StreamID != "demo" {
		t.Errorf("StreamID = %q, want demo", ev.StreamID)
	}
	if n := f.logs.count("hook_line_invalid"); n != 1 {
		t.Errorf("hook_line_invalid = %d회, want 1회", n)
	}
}

// ctx 취소는 정상 종료다. Done 이 닫히고 Wait 은 nil 을 돌려줘야 한다 —
// 여기서 에러를 돌려주면 main 이 훅 사망으로 오인해 ERROR 를 남긴다.
func TestCancelStopsReaderCleanly(t *testing.T) {
	f := newReaderFixture(t, 0)
	ctx, cancel := context.WithCancel(context.Background())
	if err := f.r.Start(ctx); err != nil {
		cancel()
		t.Fatalf("Start 실패: %v", err)
	}
	cancel()

	select {
	case <-f.r.Done():
	case <-time.After(2 * time.Second):
		t.Fatal("Done 이 닫히지 않았다")
	}
	if err := f.r.Wait(); err != nil {
		t.Errorf("Wait() = %v, want nil(정상 종료)", err)
	}
	if _, ok := <-f.r.Events(); ok {
		t.Error("Events 채널이 닫히지 않았다")
	}
}

// Start 의 stat 실패(ENOENT 외 — EACCES·ENOTDIR 등)는 **에러가 아니라 강등**이다.
// 에러로 올리면 run() 이 그대로 종료해 인덱싱 전체가 멈춘다 = GH4(훅이 죽어도 현행 동작
// 유지) 정면 위반. poll 쪽 강등 규약과 같게 맞춘다.
func TestStartDegradesWhenSpoolStatFails(t *testing.T) {
	// 경로 중간에 파일을 끼워 ENOTDIR 을 만든다(권한과 달리 root 에서도 재현된다).
	dir := t.TempDir()
	blocker := filepath.Join(dir, "notadir")
	if err := os.WriteFile(blocker, []byte("x"), 0o600); err != nil {
		t.Fatalf("파일 생성 실패: %v", err)
	}
	logs := &logCapture{}
	r, err := NewReader(ReaderOptions{
		SpoolPath:    filepath.Join(blocker, "events.jsonl"),
		PollInterval: 5 * time.Millisecond,
		Log:          slog.New(logs),
	})
	if err != nil {
		t.Fatalf("NewReader 실패: %v", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if err := r.Start(ctx); err != nil {
		t.Fatalf("Start() = %v, want nil — stat 실패로 프로세스를 끝내려 했다", err)
	}
	waitFor(t, "hook_spool_stat_failed WARN", func() bool { return logs.count("hook_spool_stat_failed") >= 1 })
}

// 스풀에 읽기 권한이 없어도 프로세스는 살아 있어야 한다(WARN 후 자연 강등).
func TestUnreadableSpoolDegradesWithoutKillingProcess(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root 는 권한 검사를 우회한다")
	}
	f := newReaderFixture(t, 0)
	f.write(line("online", "demo", 1784000000000000000))
	if err := os.Chmod(f.spool, 0o000); err != nil {
		t.Fatalf("chmod 실패: %v", err)
	}
	t.Cleanup(func() { _ = os.Chmod(f.spool, 0o600) })

	f.start()
	waitFor(t, "hook_spool_open_failed WARN", func() bool { return f.logs.count("hook_spool_open_failed") >= 1 })

	// 강등이지 사망이 아니다 — Reader 고루틴은 계속 돈다.
	select {
	case <-f.r.Done():
		t.Fatal("권한 오류로 Reader 가 죽었다 — 자연 강등이어야 한다")
	case <-time.After(50 * time.Millisecond):
	}
	if err := f.r.Wait(); err != nil {
		t.Errorf("Wait() = %v, want nil", err)
	}
}

// 스풀 경로가 비면 Reader 를 만들 수 없다 — 호출자가 아예 만들지 않아야 한다는 계약을
// 코드로 못 박는다. 조용히 도는 무의미한 고루틴을 만들지 않기 위해서다.
func TestNewReaderRejectsEmptySpoolPath(t *testing.T) {
	if _, err := NewReader(ReaderOptions{Log: slog.Default()}); err == nil {
		t.Fatal("빈 SpoolPath 를 받아들였다")
	}
}

// 결정 ⑤ "드롭 금지"를 **행동으로** 고정한다.
//
// cap 단언만으로는 non-blocking send(select+default)로 바꾸는 변경을 잡지 못한다.
// 버퍼를 1로 좁히고 소비를 멈춘 채 3줄을 흘린 뒤, 소비를 재개했을 때 3건이 **전부**
// 도착해야 한다. 드롭 구현이면 2건이 사라진다.
func TestSlowConsumerBlocksInsteadOfDropping(t *testing.T) {
	spool := filepath.Join(t.TempDir(), "events.jsonl")
	logs := &logCapture{}
	r, err := NewReader(ReaderOptions{
		SpoolPath:    spool,
		PollInterval: 5 * time.Millisecond,
		EventBufSize: 1, // 소비가 멈추면 곧바로 가득 찬다
		Log:          slog.New(logs),
	})
	if err != nil {
		t.Fatalf("NewReader 실패: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	if err := r.Start(ctx); err != nil {
		cancel()
		t.Fatalf("Start 실패: %v", err)
	}
	t.Cleanup(func() {
		cancel()
		<-r.Done()
	})

	// 소비자가 멈춰 있는 동안(아직 아무도 Events 를 읽지 않았다) 3줄을 쓴다.
	fh, err := os.OpenFile(spool, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		t.Fatalf("스풀 열기 실패: %v", err)
	}
	want := []string{"aaa", "bbb", "ccc"}
	for i, id := range want {
		if _, err := fh.WriteString(line("online", id, int64(1784000000000000000+i))); err != nil {
			t.Fatalf("스풀 쓰기 실패: %v", err)
		}
	}
	fh.Close()
	time.Sleep(50 * time.Millisecond) // Reader 가 버퍼를 채우고 블록하게 둔다

	// 이제 소비를 재개한다. 3건이 순서대로 전부 와야 한다.
	for _, wantID := range want {
		select {
		case ev, ok := <-r.Events():
			if !ok {
				t.Fatal("Events 채널이 닫혔다")
			}
			if ev.StreamID != wantID {
				t.Fatalf("StreamID = %q, want %q — 이벤트가 드롭됐다", ev.StreamID, wantID)
			}
		case <-time.After(2 * time.Second):
			t.Fatalf("%q 가 오지 않았다 — 드롭됐다", wantID)
		}
	}
}

// 기본 버퍼는 256 이다(결정 ⑤의 확정값).
func TestDefaultEventBufSize(t *testing.T) {
	f := newReaderFixture(t, 0)
	if got := cap(f.r.Events()); got != DefaultEventBufSize {
		t.Errorf("기본 Events 버퍼 = %d, want %d", got, DefaultEventBufSize)
	}
	if DefaultEventBufSize != 256 {
		t.Errorf("DefaultEventBufSize = %d, want 256", DefaultEventBufSize)
	}
}

// 상한과 **정확히 같은** 길이의 줄은 정상이다(경계 포함).
// `>` 를 `>=` 로 바꾸는 변경은 멀쩡한 줄을 버리기 시작하는데, 초과/미만만 보는
// 테스트로는 잡히지 않는다.
func TestLineExactlyAtMaxLineBytesIsAccepted(t *testing.T) {
	const streamID = "demo"
	body := line("online", streamID, 1784000000000000000)
	exact := len(body) - 1 // 개행은 줄 길이에 포함되지 않는다

	f := newReaderFixture(t, exact)
	f.start()
	f.write(body)

	if ev := f.next(); ev.StreamID != streamID {
		t.Errorf("StreamID = %q, want %q", ev.StreamID, streamID)
	}
	if n := f.logs.count("hook_line_overflow"); n != 0 {
		t.Errorf("hook_line_overflow = %d회, want 0회 — 상한과 같은 길이의 줄을 버렸다", n)
	}
}

// codex MEDIUM-1 — SpoolPath 가 마운트 오구성으로 일반 파일이 아니면(디렉토리 등)
// stat 은 성공하고 poll 이 계속 돌지만 아무 이벤트도 안 나오고 실패 로그도 없다.
// 이 무징후 정지는 ADR-027(훅은 죽어도 티가 나야 한다) 정신에 걸린다.
// 비정규 파일이면 WARN 을 남기고 강등하되 프로세스는 살아 있어야 한다.
func TestDirectorySpoolPathDegradesWithWarn(t *testing.T) {
	dir := t.TempDir()
	spool := filepath.Join(dir, "events.jsonl")
	if err := os.Mkdir(spool, 0o755); err != nil { // 파일이어야 할 자리가 디렉토리 = 마운트 오구성
		t.Fatalf("디렉토리 생성 실패: %v", err)
	}
	logs := &logCapture{}
	r, err := NewReader(ReaderOptions{SpoolPath: spool, PollInterval: 5 * time.Millisecond, Log: slog.New(logs)})
	if err != nil {
		t.Fatalf("NewReader 실패: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if err := r.Start(ctx); err != nil {
		t.Fatalf("Start() = %v, want nil — 비정규 파일로 프로세스를 끝내려 했다", err)
	}

	waitFor(t, "hook_spool_not_regular WARN", func() bool { return logs.count("hook_spool_not_regular") >= 1 })

	// 강등이지 사망이 아니다 — 이벤트는 없지만 죽지도 않는다.
	select {
	case ev, ok := <-r.Events():
		t.Fatalf("이벤트가 나왔다(있어선 안 됨): ev=%+v ok=%v", ev, ok)
	case <-r.Done():
		t.Fatal("Reader 가 죽었다 — 강등이어야 한다")
	case <-time.After(50 * time.Millisecond):
	}
	if err := r.Wait(); err != nil {
		t.Errorf("Wait() = %v, want nil", err)
	}
}

// codex MEDIUM-2 — Start 의 stat 이 ENOENT 외로 실패하면(EACCES·ENOTDIR) 예전에는
// offset 0 으로 시작해, 마운트가 복구된 뒤 그 전에 쌓여 있던 과거를 전량 재생했다.
// 이는 EOF 시작 계약("기존 파일은 EOF 부터, 과거 재생 없음") 위반이며 지나간 세션
// 경계를 다시 무장한다. 오프셋을 미확정으로 두고, 최초로 스풀을 정상 연 폴에서
// 그 시점 크기로 EOF 를 확정해야 한다.
func TestDegradedStartPinsEOFOnRecoveryWithoutReplayingPast(t *testing.T) {
	base := t.TempDir()
	blocker := filepath.Join(base, "blocker") // 파일로 경로 중간을 막아 ENOTDIR 유발
	if err := os.WriteFile(blocker, []byte("x"), 0o600); err != nil {
		t.Fatalf("blocker 생성 실패: %v", err)
	}
	spool := filepath.Join(blocker, "events.jsonl") // blocker 가 파일이라 stat → ENOTDIR

	logs := &logCapture{}
	r, err := NewReader(ReaderOptions{SpoolPath: spool, PollInterval: 5 * time.Millisecond, Log: slog.New(logs)})
	if err != nil {
		t.Fatalf("NewReader 실패: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(func() {
		cancel()
		<-r.Done()
	})
	if err := r.Start(ctx); err != nil {
		t.Fatalf("Start() = %v, want nil", err)
	}
	// 강등 폴링이 실제로 돌고 있음을 확인한다.
	waitFor(t, "degraded 폴링", func() bool {
		return logs.count("hook_spool_stat_failed")+logs.count("hook_spool_open_failed") >= 1
	})

	// 마운트가 고쳐진다: blocker 를 디렉토리로 바꾸고 그 안에 **과거 내용이 있는** 스풀을 만든다.
	if err := os.Remove(blocker); err != nil {
		t.Fatalf("blocker 제거 실패: %v", err)
	}
	if err := os.Mkdir(blocker, 0o755); err != nil {
		t.Fatalf("디렉토리 생성 실패: %v", err)
	}
	past := line("online", "past-a", 1784000000000000000) + line("online", "past-b", 1784000000000000001)
	if err := os.WriteFile(spool, []byte(past), 0o644); err != nil {
		t.Fatalf("스풀 생성 실패: %v", err)
	}

	// 폴이 EOF 를 확정할 시간을 준다(과거 내용을 건너뛴다).
	time.Sleep(60 * time.Millisecond)

	// 이제 새 줄을 덧붙인다 — 이것만 와야 한다.
	fh, err := os.OpenFile(spool, os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		t.Fatalf("append 열기 실패: %v", err)
	}
	if _, err := fh.WriteString(line("online", "fresh", 1784000000000000002)); err != nil {
		t.Fatalf("append 실패: %v", err)
	}
	fh.Close()

	select {
	case ev := <-r.Events():
		if ev.StreamID != "fresh" {
			t.Errorf("StreamID = %q, want fresh — 복구 후 과거를 재생했다", ev.StreamID)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("fresh 이벤트가 오지 않았다")
	}
}

// poll 의 IsRegular 검증이 **유일한 방어선**이 되는 경로를 독립적으로 고정한다.
// Start 시점엔 스풀이 없어(ENOENT) Start 의 비정규 분기를 타지 않고, 런타임에 그 자리가
// 디렉토리로 생기는 경우다 — poll 이 못 잡으면 무징후 정지가 된다.
func TestSpoolBecomingDirectoryAtRuntimeDegrades(t *testing.T) {
	dir := t.TempDir()
	spool := filepath.Join(dir, "events.jsonl") // 아직 없다 = Start 는 ENOENT 강등
	logs := &logCapture{}
	r, err := NewReader(ReaderOptions{SpoolPath: spool, PollInterval: 5 * time.Millisecond, Log: slog.New(logs)})
	if err != nil {
		t.Fatalf("NewReader 실패: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(func() {
		cancel()
		<-r.Done()
	})
	if err := r.Start(ctx); err != nil {
		t.Fatalf("Start() = %v, want nil", err)
	}
	waitFor(t, "hook_spool_missing", func() bool { return logs.count("hook_spool_missing") >= 1 })

	// 런타임에 그 자리에 디렉토리가 생긴다(마운트 오구성).
	if err := os.Mkdir(spool, 0o755); err != nil {
		t.Fatalf("디렉토리 생성 실패: %v", err)
	}

	waitFor(t, "hook_spool_not_regular WARN", func() bool { return logs.count("hook_spool_not_regular") >= 1 })

	// 강등이지 사망이 아니다.
	select {
	case <-r.Done():
		t.Fatal("Reader 가 죽었다 — 강등이어야 한다")
	case <-time.After(30 * time.Millisecond):
	}
}
