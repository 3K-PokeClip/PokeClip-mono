package upload

import (
	"bytes"
	"context"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// logCapture 는 구조화 로그를 그대로 모은다.
// 로그 이름과 필드가 AC 명령이 grep 하는 계약이므로(설계 부록) 테스트도 문자열로 본다.
type logCapture struct {
	mu      sync.Mutex
	records []logRecord
}

type logRecord struct {
	level slog.Level
	msg   string
	attrs map[string]any
}

func newLogCapture() *logCapture { return &logCapture{} }

func (c *logCapture) handler() slog.Handler { return &captureHandler{c: c} }

func (c *logCapture) logger() *slog.Logger { return slog.New(c.handler()) }

// find 는 이름이 같은 로그를 전부 돌려준다.
func (c *logCapture) find(msg string) []logRecord {
	c.mu.Lock()
	defer c.mu.Unlock()
	var out []logRecord
	for _, r := range c.records {
		if r.msg == msg {
			out = append(out, r)
		}
	}
	return out
}

func (c *logCapture) count(msg string) int { return len(c.find(msg)) }

// one 은 정확히 1건일 때 그 1건을 돌려준다. 0건·2건 이상은 실패다.
func (c *logCapture) one(t *testing.T, msg string) logRecord {
	t.Helper()
	got := c.find(msg)
	if len(got) != 1 {
		t.Fatalf("%s 로그 = %d건, want 1건 (%s)", msg, len(got), c.dump())
	}
	return got[0]
}

func (c *logCapture) dump() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	out := "수집된 로그:"
	for _, r := range c.records {
		out += "\n  " + r.level.String() + " " + r.msg
	}
	return out
}

func (c *logCapture) reset() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.records = nil
}

type captureHandler struct {
	c     *logCapture
	attrs []slog.Attr
}

func (h *captureHandler) Enabled(context.Context, slog.Level) bool { return true }

func (h *captureHandler) Handle(_ context.Context, r slog.Record) error {
	attrs := map[string]any{}
	for _, a := range h.attrs {
		attrs[a.Key] = a.Value.Any()
	}
	r.Attrs(func(a slog.Attr) bool {
		attrs[a.Key] = a.Value.Any()
		return true
	})
	h.c.mu.Lock()
	defer h.c.mu.Unlock()
	h.c.records = append(h.c.records, logRecord{level: r.Level, msg: r.Message, attrs: attrs})
	return nil
}

func (h *captureHandler) WithAttrs(as []slog.Attr) slog.Handler {
	merged := make([]slog.Attr, 0, len(h.attrs)+len(as))
	merged = append(merged, h.attrs...)
	merged = append(merged, as...)
	return &captureHandler{c: h.c, attrs: merged}
}

func (h *captureHandler) WithGroup(string) slog.Handler { return h }

// fakeClock 은 테스트가 시간을 직접 민다. 브레이커의 lazy 전이와 백오프는 전부 시각 비교라
// 실제 시간을 기다리면 테스트가 느려지고 흔들린다.
type fakeClock struct {
	mu sync.Mutex
	t  time.Time
}

func newFakeClock() *fakeClock {
	return &fakeClock{t: time.Date(2026, 8, 3, 0, 0, 0, 0, time.UTC)}
}

func (c *fakeClock) now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}

func (c *fakeClock) advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.t = c.t.Add(d)
}

// --- 가짜 UploadStore -------------------------------------------------------
// 훅을 nil 로 두면 "정상 성공"이 기본 동작이다. 테스트는 필요한 훅만 바꿔 끼운다.

type markCall struct {
	streamID    string
	seq         int64
	expectBytes int64
}

type fakeUploadStore struct {
	mu sync.Mutex

	onMarkUploaded func(ctx context.Context, streamID string, seq, expectBytes int64) (bool, error)
	onMarkFailed   func(ctx context.Context, streamID string, seq, expectBytes int64) (bool, error)
	onPending      func(ctx context.Context, tailGraceSecs float64, limit int, after index.SweepCursor) ([]index.UploadTarget, index.SweepCursor, error)
	onBacklog      func(ctx context.Context) (int64, int64, int64, error)

	uploaded []markCall
	failed   []markCall
}

func (f *fakeUploadStore) MarkUploaded(ctx context.Context, streamID string, seq, expectBytes int64) (bool, error) {
	f.mu.Lock()
	f.uploaded = append(f.uploaded, markCall{streamID, seq, expectBytes})
	hook := f.onMarkUploaded
	f.mu.Unlock()
	if hook != nil {
		return hook(ctx, streamID, seq, expectBytes)
	}
	return true, nil
}

func (f *fakeUploadStore) MarkFailed(ctx context.Context, streamID string, seq, expectBytes int64) (bool, error) {
	f.mu.Lock()
	f.failed = append(f.failed, markCall{streamID, seq, expectBytes})
	hook := f.onMarkFailed
	f.mu.Unlock()
	if hook != nil {
		return hook(ctx, streamID, seq, expectBytes)
	}
	return true, nil
}

func (f *fakeUploadStore) PendingUploads(ctx context.Context, tailGraceSecs float64, limit int, after index.SweepCursor) ([]index.UploadTarget, index.SweepCursor, error) {
	f.mu.Lock()
	hook := f.onPending
	f.mu.Unlock()
	if hook != nil {
		return hook(ctx, tailGraceSecs, limit, after)
	}
	return nil, after, nil
}

func (f *fakeUploadStore) CountBacklog(ctx context.Context) (int64, int64, int64, error) {
	f.mu.Lock()
	hook := f.onBacklog
	f.mu.Unlock()
	if hook != nil {
		return hook(ctx)
	}
	return 0, 0, 0, nil
}

func (f *fakeUploadStore) markCalls() (uploaded, failed []markCall) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]markCall(nil), f.uploaded...), append([]markCall(nil), f.failed...)
}

// --- 가짜 Putter -----------------------------------------------------------

type putCall struct {
	key  string
	size int64
	body []byte
}

type fakePutter struct {
	mu    sync.Mutex
	fn    func(ctx context.Context, key string, body io.Reader, size int64) error
	calls []putCall
}

func (p *fakePutter) Put(ctx context.Context, key string, body io.Reader, size int64) error {
	p.mu.Lock()
	fn := p.fn
	p.mu.Unlock()

	// 계약 2번대로 처음부터 정확히 size 바이트만 읽는다. 가짜도 같은 규칙을 지켜야
	// "몇 바이트를 넘겼는가"를 테스트가 관측할 수 있다.
	buf := make([]byte, size)
	n, _ := io.ReadFull(body, buf)

	p.mu.Lock()
	p.calls = append(p.calls, putCall{key: key, size: size, body: buf[:n]})
	p.mu.Unlock()

	if fn != nil {
		return fn(ctx, key, body, size)
	}
	return nil
}

func (p *fakePutter) putCalls() []putCall {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]putCall(nil), p.calls...)
}

// --- 파일·대상 픽스처 -------------------------------------------------------

// newRoot 는 임시 녹화 루트와 os.Root 손잡이를 만든다.
func newRoot(t *testing.T) (root *os.Root, dir string) {
	t.Helper()
	dir = t.TempDir()
	r, err := os.OpenRoot(dir)
	if err != nil {
		t.Fatalf("os.OpenRoot 실패: %v", err)
	}
	t.Cleanup(func() { _ = r.Close() })
	return r, dir
}

// writeSegment 는 <루트>/<streamID>/<이름> 에 size 바이트 파일을 만든다.
func writeSegment(t *testing.T, dir, streamID, name string, size int) string {
	t.Helper()
	sub := filepath.Join(dir, streamID)
	if err := os.MkdirAll(sub, 0o755); err != nil {
		t.Fatalf("디렉토리 생성 실패: %v", err)
	}
	path := filepath.Join(sub, name)
	body := make([]byte, size)
	for i := range body {
		body[i] = byte('a' + i%26)
	}
	if err := os.WriteFile(path, body, 0o644); err != nil {
		t.Fatalf("파일 생성 실패: %v", err)
	}
	return path
}

var fixtureWall = time.Date(2026, 8, 3, 0, 0, 0, 0, time.UTC)

// newTarget 은 [0] 대상 검증을 통과하는 정상 대상이다.
func newTarget(streamID string, seq int64, path string, bytes int64, isTail bool) index.UploadTarget {
	return index.UploadTarget{
		StreamID:  streamID,
		Seq:       seq,
		S3Key:     index.S3Key(streamID, seq, fixtureWall),
		LocalPath: path,
		Bytes:     bytes,
		IsTail:    isTail,
	}
}

// waitFor 는 조건이 참이 될 때까지 짧게 폴링한다.
// 고정 sleep 은 느린 기계에서 흔들리고 빠른 기계에서 시간을 버린다.
func waitFor(t *testing.T, cond func() bool, what string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(2 * time.Millisecond)
	}
	t.Fatalf("%s 를 5초 안에 보지 못했다", what)
}

// syncBuffer 는 로그 고루틴과 테스트 고루틴이 함께 만지는 버퍼다.
type syncBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *syncBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *syncBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

func (b *syncBuffer) contains(s string) bool { return strings.Contains(b.String(), s) }

// jsonLogger 는 운영과 같은 JSON 핸들러다. 필드의 직렬화 형태가 계약인 테스트에 쓴다.
func jsonLogger(w io.Writer) *slog.Logger {
	return slog.New(slog.NewJSONHandler(w, &slog.HandlerOptions{Level: slog.LevelDebug}))
}

// armForSweepTest 는 고루틴을 띄우지 않고 수명 상태만 Started 로 만든다.
//
// 스위퍼 회차를 테스트가 직접 한 번씩 돌리기 위한 이음매다. 워커가 없어야 큐 포화
// 시나리오가 결정적이 된다 — 소비자가 있으면 "몇 건째에 막히는가"가 스케줄러에 달린다.
// 프로덕션 코드에 이 함수를 두지 않으려고 테스트 파일에 메서드로 둔다.
func (u *Uploader) armForSweepTest() {
	u.putCtx, u.putCancel = context.WithCancel(context.Background())
	u.markRoot, u.markCancel = context.WithCancel(context.Background())
	u.workerStop = make(chan struct{})
	u.accepting.Store(true)
	u.state.Store(int32(lifecycleStarted))
}
