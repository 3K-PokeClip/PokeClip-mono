package edgebounds

// Writer 시험의 공용 준비물 — 시험 시계 · 로그 기록기 · 호출 기록 감싸개 · 기본 Options.

import (
	"context"
	"log/slog"
	"slices"
	"sync"
	"testing"
	"time"
)

// testClock 은 시험이 손으로 돌리는 시계다.
type testClock struct {
	mu sync.Mutex
	t  time.Time
}

func newTestClock() *testClock {
	return &testClock{t: time.Date(2026, 11, 4, 12, 0, 0, 0, time.UTC)}
}

func (c *testClock) now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}

func (c *testClock) advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.t = c.t.Add(d)
}

// logRecord 는 Writer 가 낸 로그 한 줄이다.
type logRecord struct {
	level slog.Level
	msg   string
	attrs map[string]any
}

// logRecorder 는 로그 줄을 모으는 slog.Handler 다. 여러 고루틴이 함께 써도 된다.
type logRecorder struct {
	mu   sync.Mutex
	recs []logRecord
}

func (r *logRecorder) Enabled(context.Context, slog.Level) bool { return true }

func (r *logRecorder) Handle(_ context.Context, rec slog.Record) error {
	attrs := map[string]any{}
	rec.Attrs(func(a slog.Attr) bool {
		attrs[a.Key] = a.Value.Any()
		return true
	})
	r.mu.Lock()
	defer r.mu.Unlock()
	r.recs = append(r.recs, logRecord{level: rec.Level, msg: rec.Message, attrs: attrs})
	return nil
}

func (r *logRecorder) WithAttrs([]slog.Attr) slog.Handler { return r }
func (r *logRecorder) WithGroup(string) slog.Handler      { return r }

func (r *logRecorder) logger() *slog.Logger { return slog.New(r) }

// named 는 msg 가 name 인 줄의 사본이다.
func (r *logRecorder) named(name string) []logRecord {
	r.mu.Lock()
	defer r.mu.Unlock()
	var out []logRecord
	for _, rec := range r.recs {
		if rec.msg == name {
			out = append(out, rec)
		}
	}
	return out
}

// messages 는 낸 줄의 msg 차례다.
func (r *logRecorder) messages() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	var out []string
	for _, rec := range r.recs {
		out = append(out, rec.msg)
	}
	return out
}

// testOptions 는 설계 3-3 의 기본값이다(FlushEvery 300초 · ReleaseEvery 30초 · ReleaseSettle 120초 · Margin 60초).
func testOptions(now func() time.Time, rec *logRecorder) Options {
	if rec == nil {
		rec = &logRecorder{}
	}
	return Options{
		FlushEvery:    300 * time.Second,
		ReleaseEvery:  30 * time.Second,
		ReleaseSettle: 120 * time.Second,
		Margin:        60 * time.Second,
		CallTimeout:   5 * time.Second,
		SizeWarnBytes: 4 << 20,
		WriterID:      "box-a",
		Log:           rec.logger(),
		Now:           now,
	}
}

func newTestWriter(t *testing.T, l Ledger, s Store, opt Options) *Writer {
	t.Helper()
	w, err := New(l, s, opt)
	if err != nil {
		t.Fatalf("Setup: New returned error: %v", err)
	}
	return w
}

// callLog 은 원장 · KVS 호출의 차례를 적는다. 호출이 겹치면(한 고루틴이 아니면) overlap 이 선다.
type callLog struct {
	mu       sync.Mutex
	calls    []string
	at       []time.Time
	inFlight int
	overlap  bool
	now      func() time.Time
}

func (c *callLog) enter(name string) func() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.calls = append(c.calls, name)
	if c.now != nil {
		c.at = append(c.at, c.now())
	}
	c.inFlight++
	if c.inFlight > 1 {
		c.overlap = true
	}
	return func() {
		c.mu.Lock()
		defer c.mu.Unlock()
		c.inFlight--
	}
}

func (c *callLog) snapshot() []string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return slices.Clone(c.calls)
}

// recLedger · recStore 는 호출을 callLog 에 적고 안쪽 구현에 넘긴다.
type recLedger struct {
	Ledger
	log *callLog
}

func (r recLedger) Raise(ctx context.Context, rows []Floor) ([]Floor, error) {
	defer r.log.enter("Raise")()
	return r.Ledger.Raise(ctx, rows)
}

func (r recLedger) ClosedUnreleased(ctx context.Context, limit int) ([]Floor, error) {
	defer r.log.enter("ClosedUnreleased")()
	return r.Ledger.ClosedUnreleased(ctx, limit)
}

func (r recLedger) MarkReleased(ctx context.Context, rows []Floor) error {
	defer r.log.enter("MarkReleased")()
	return r.Ledger.MarkReleased(ctx, rows)
}

func (r recLedger) DeleteReleased(ctx context.Context, olderThan time.Duration) (int64, error) {
	defer r.log.enter("DeleteReleased")()
	return r.Ledger.DeleteReleased(ctx, olderThan)
}

type recStore struct {
	Store
	log *callLog
}

func (r recStore) Describe(ctx context.Context) (Stat, error) {
	defer r.log.enter("Describe")()
	return r.Store.Describe(ctx)
}

func (r recStore) Update(ctx context.Context, ifMatch string, puts []Entry, deletes []string) (string, error) {
	defer r.log.enter("Update")()
	return r.Store.Update(ctx, ifMatch, puts, deletes)
}

// tracked 는 Writer 가 기억하는 회차 이력 수다.
func (w *Writer) tracked() int {
	w.mu.Lock()
	defer w.mu.Unlock()
	return len(w.rounds)
}
