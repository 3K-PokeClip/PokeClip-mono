package janitor

// 시험 대역 — 장부(가짜 Ledger) · 파일시스템(가짜 remove) · 시계 · 로그 수집기.
// 가짜 장부는 RemoveUnderRowLock 의 계약(잠금 → remove → clear 면 NULL → COMMIT)을 그대로 흉내 내고,
// 각 단계에 갈고리를 둔다 — 실 PG 로는 결정적으로 만들 수 없는 「COMMIT 만 무응답」 등을 만든다.

import (
	"context"
	"fmt"
	"io/fs"
	"log/slog"
	"os"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

const testRoot = "/recordings"

// testSegName 은 seq 의 조각 이름이다 — 경로 검증(recording 조각 이름 규칙 — Q2)을 통과하는 꼴이다.
func testSegName(seq int64) string { return fmt.Sprintf("2026-10-19_00-00-00-%06d.mp4", seq) }

func testPath(stream string, seq int64) string {
	return fmt.Sprintf("%s/%s/%s", testRoot, stream, testSegName(seq))
}

func testRel(stream string, seq int64) string {
	return stream + "/" + testSegName(seq)
}

// opLog 는 장부 · FS 조작의 차례다(D02 — 「remove s/1」 · 「null s/1」).
type opLog struct {
	mu  sync.Mutex
	ops []string
}

func (l *opLog) add(op string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.ops = append(l.ops, op)
}

func (l *opLog) snapshot() []string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return slices.Clone(l.ops)
}

type fakeRow struct {
	seg  index.LocalSegment
	path *string // nil = NULL
}

// fakeLedger 는 Ledger 의 시험 구현이다. 후보 = local_path 가 NULL 이 아닌 행 전부(판정 SQL 은 T1 이
// 시험한다). 행 순서는 (stream_id, seq) 다.
type fakeLedger struct {
	mu   sync.Mutex
	rows map[rowKey]*fakeRow
	log  *opLog

	schemaReady    bool
	schemaErr      error
	schemaCalls    int
	deletableErr   error
	deletableCalls int
	invalid        []index.InvalidCutoff
	invalidErr     error
	invalidCalls   int
	retained       int64 // AbandonedLocalRetained 가 돌려줄 값
	retainedErr    error
	lockCalls      int
	clearCalls     int
	acting         bool // 잠금 아래에서 remove 가 도는 중이다(D33 「진행 중」)

	// lockHook 은 선잠금을 대신한다 — handled 면 그 결과 · 오류를 그대로 돌려준다(remove 미호출).
	lockHook func(k rowKey) (res index.RowLockResult, err error, handled bool)
	// commitHook 은 remove 뒤 COMMIT 을 대신한다 — 오류면 StageCommit 오류(장부 미갱신)다.
	commitHook func(ctx context.Context, k rowKey, cleared bool) error
	// clearHook 은 회수 ClearLocalPath 를 대신한다.
	clearHook func(ctx context.Context, k rowKey) (cleared bool, err error, handled bool)
}

func newFakeLedger(log *opLog) *fakeLedger {
	return &fakeLedger{rows: make(map[rowKey]*fakeRow), log: log, schemaReady: true}
}

// add 는 후보 행 하나를 넣는다. due = 외부 삭제 나이 지남(ExternalDeleteDue).
func (l *fakeLedger) add(stream string, seq int64, due bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	p := testPath(stream, seq)
	l.rows[rowKey{stream, seq}] = &fakeRow{
		seg: index.LocalSegment{StreamID: stream, Seq: seq, LocalPath: p,
			GraceBasis: time.Date(2026, 10, 19, 0, 0, 0, 0, time.UTC), ExternalDeleteDue: due},
		path: &p,
	}
}

// pathOf 는 행의 local_path 다(행 없음 · NULL 이면 거짓).
func (l *fakeLedger) pathOf(k rowKey) (string, bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	r, ok := l.rows[k]
	if !ok || r.path == nil {
		return "", false
	}
	return *r.path, true
}

func (l *fakeLedger) setPath(k rowKey, p *string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.rows[k].path = p
}

func (l *fakeLedger) drop(k rowKey) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.rows, k)
}

// compact 는 NULL 행을 지운다 — 후보가 될 수 없는 행이라 결과가 같고, 긴 시험의 조회 비용만 줄인다.
func (l *fakeLedger) compact() {
	l.mu.Lock()
	defer l.mu.Unlock()
	for k, r := range l.rows {
		if r.path == nil {
			delete(l.rows, k)
		}
	}
}

func (l *fakeLedger) nonNullCount() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	n := 0
	for _, r := range l.rows {
		if r.path != nil {
			n++
		}
	}
	return n
}

func (l *fakeLedger) SchemaReady(context.Context) (bool, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.schemaCalls++
	return l.schemaReady, l.schemaErr
}

func (l *fakeLedger) DeletableSegments(_ context.Context, _ index.LocalCleanupPolicy, limit int, after index.LocalCursor) ([]index.LocalSegment, index.LocalCursor, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.deletableCalls++
	if l.deletableErr != nil {
		return nil, after, l.deletableErr
	}
	keys := make([]rowKey, 0, len(l.rows))
	for k, r := range l.rows {
		if r.path != nil && (k.streamID > after.StreamID || (k.streamID == after.StreamID && k.seq > after.Seq)) {
			keys = append(keys, k)
		}
	}
	slices.SortFunc(keys, func(a, b rowKey) int {
		if a.streamID != b.streamID {
			if a.streamID < b.streamID {
				return -1
			}
			return 1
		}
		return int(a.seq - b.seq)
	})
	if len(keys) > limit {
		keys = keys[:limit]
	}
	segs := make([]index.LocalSegment, 0, len(keys))
	for _, k := range keys {
		seg := l.rows[k].seg
		seg.LocalPath = *l.rows[k].path
		segs = append(segs, seg)
	}
	if len(segs) == 0 {
		return segs, after, nil
	}
	last := segs[len(segs)-1]
	return segs, index.LocalCursor{StreamID: last.StreamID, Seq: last.Seq}, nil
}

// markAbandoned 는 행을 ③ 포기 갈래로 고른 행으로 표시한다.
func (l *fakeLedger) markAbandoned(k rowKey) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.rows[k].seg.Abandoned = true
}

func (l *fakeLedger) AbandonedLocalRetained(context.Context) (int64, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.retained, l.retainedErr
}

func (l *fakeLedger) InvalidCutoffs(context.Context) ([]index.InvalidCutoff, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.invalidCalls++
	return slices.Clone(l.invalid), l.invalidErr
}

func (l *fakeLedger) RemoveUnderRowLock(ctx context.Context, streamID string, seq int64, path string, remove func() bool) (index.RowLockResult, error) {
	k := rowKey{streamID, seq}
	l.mu.Lock()
	l.lockCalls++
	hook := l.lockHook
	l.mu.Unlock()
	if hook != nil {
		if res, err, handled := hook(k); handled {
			return res, err
		}
	}
	l.mu.Lock()
	r, ok := l.rows[k]
	switch {
	case !ok:
		l.mu.Unlock()
		return index.RowLockResult{Outcome: index.RowLockGone}, nil
	case r.path == nil || *r.path != path:
		l.mu.Unlock()
		return index.RowLockResult{Outcome: index.RowLockPathChanged}, nil
	}
	l.mu.Unlock()

	l.mu.Lock()
	l.acting = true
	l.mu.Unlock()
	res := index.RowLockResult{Acted: true, Cleared: remove()}
	l.mu.Lock()
	l.acting = false
	l.mu.Unlock()
	if l.commitHook != nil {
		if err := l.commitHook(ctx, k, res.Cleared); err != nil {
			res.Stage = index.StageCommit
			return res, err
		}
	}
	res.Outcome = index.RowLockKept
	if res.Cleared {
		l.mu.Lock()
		if r, ok := l.rows[k]; ok {
			r.path = nil
		}
		l.mu.Unlock()
		l.log.add("null " + testRel(streamID, seq))
		res.Outcome = index.RowLockCleared
	}
	return res, nil
}

func (l *fakeLedger) ClearLocalPath(ctx context.Context, streamID string, seq int64, deletedPath string) (bool, error) {
	k := rowKey{streamID, seq}
	l.mu.Lock()
	l.clearCalls++
	hook := l.clearHook
	l.mu.Unlock()
	if hook != nil {
		if cleared, err, handled := hook(ctx, k); handled {
			return cleared, err
		}
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	r, ok := l.rows[k]
	if !ok || r.path == nil || *r.path != deletedPath {
		return false, nil
	}
	r.path = nil
	l.log.add("null " + testRel(streamID, seq))
	return true, nil
}

// fakeFS 는 루트 기준 상대 경로 집합이다. hook 이 handled 면 그 오류를 돌려주고 파일은 그대로다.
type fakeFS struct {
	mu      sync.Mutex
	files   map[string]bool
	log     *opLog
	calls   int
	hook    func(rel string) (err error, handled bool)
	removed []string // remove 가 nil 로 끝난 경로(삭제 성공 집합)
}

func newFakeFS(log *opLog) *fakeFS { return &fakeFS{files: make(map[string]bool), log: log} }

func (f *fakeFS) put(rel string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.files[rel] = true
}

func (f *fakeFS) exists(rel string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.files[rel]
}

// unlink 는 버려진 워커의 늦은 삭제를 흉내 낸다.
func (f *fakeFS) unlink(rel string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	delete(f.files, rel)
}

func (f *fakeFS) callCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.calls
}

func (f *fakeFS) remove(rel string) error {
	f.mu.Lock()
	f.calls++
	hook := f.hook
	f.mu.Unlock()
	f.log.add("remove " + rel)
	if hook != nil {
		if err, handled := hook(rel); handled {
			return err
		}
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if !f.files[rel] {
		return &fs.PathError{Op: "remove", Path: rel, Err: fs.ErrNotExist}
	}
	delete(f.files, rel)
	f.removed = append(f.removed, rel)
	return nil
}

type fakeClock struct {
	mu sync.Mutex
	t  time.Time
}

func newFakeClock() *fakeClock { return &fakeClock{t: time.Date(2026, 10, 19, 1, 0, 0, 0, time.UTC)} }

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

// logRec 은 수집한 로그 한 줄이다. 속성은 문자열로 펴 둔다.
type logRec struct {
	level slog.Level
	msg   string
	attrs map[string]string
}

// logSink 는 min 이상 등급만 모으는 slog 처리기다(D32 — WARN 처리기 · D44 — INFO 처리기).
type logSink struct {
	min   slog.Level
	mu    *sync.Mutex
	recs  *[]logRec
	attrs []slog.Attr
}

func newLogSink(min slog.Level) *logSink {
	return &logSink{min: min, mu: &sync.Mutex{}, recs: &[]logRec{}}
}

func (s *logSink) Enabled(_ context.Context, l slog.Level) bool { return l >= s.min }

func (s *logSink) Handle(_ context.Context, r slog.Record) error {
	rec := logRec{level: r.Level, msg: r.Message, attrs: map[string]string{}}
	for _, a := range s.attrs {
		rec.attrs[a.Key] = a.Value.String()
	}
	r.Attrs(func(a slog.Attr) bool {
		rec.attrs[a.Key] = a.Value.String()
		return true
	})
	s.mu.Lock()
	defer s.mu.Unlock()
	*s.recs = append(*s.recs, rec)
	return nil
}

func (s *logSink) WithAttrs(as []slog.Attr) slog.Handler {
	return &logSink{min: s.min, mu: s.mu, recs: s.recs, attrs: append(slices.Clone(s.attrs), as...)}
}

func (s *logSink) WithGroup(string) slog.Handler { return s }

// all 은 msg 줄 전부다(빈 msg 면 모든 줄).
func (s *logSink) all(msg string) []logRec {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []logRec
	for _, r := range *s.recs {
		if msg == "" || r.msg == msg {
			out = append(out, r)
		}
	}
	return out
}

// last 는 msg 의 마지막 줄이다.
func (s *logSink) last(t *testing.T, msg string) logRec {
	t.Helper()
	recs := s.all(msg)
	if len(recs) == 0 {
		t.Fatalf("로그 %q 가 없다", msg)
	}
	return recs[len(recs)-1]
}

// rig 은 한 시험의 janitor 와 대역 묶음이다.
type rig struct {
	j      *Janitor
	ledger *fakeLedger
	fs     *fakeFS
	clock  *fakeClock
	log    *logSink
	ops    *opLog
}

// newRig 은 가짜 장부 · FS · 시계를 끼운 janitor 를 만든다. tune 은 Options 를 고친다.
func newRig(t *testing.T, tune func(*Options)) *rig {
	t.Helper()
	ops := &opLog{}
	sink := newLogSink(slog.LevelDebug)
	opt := Options{
		Root:        testRoot,
		Interval:    30 * time.Second,
		Batch:       200,
		MaxPerCycle: 2000,
		FSOpTimeout: 5 * time.Second,
		RunID:       "run-1",
		Log:         slog.New(sink),
	}
	if tune != nil {
		tune(&opt)
	}
	ledger := newFakeLedger(ops)
	root, err := os.OpenRoot(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { root.Close() })
	j, err := New(opt, ledger, root)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	ffs, clock := newFakeFS(ops), newFakeClock()
	j.remove, j.now = ffs.remove, clock.now
	return &rig{j: j, ledger: ledger, fs: ffs, clock: clock, log: sink, ops: ops}
}

// row 는 장부 행과 그 파일을 함께 만든다. file = 파일 실재.
func (r *rig) row(stream string, seq int64, due, file bool) rowKey {
	r.ledger.add(stream, seq, due)
	if file {
		r.fs.put(testRel(stream, seq))
	}
	return rowKey{stream, seq}
}

// cycle 은 한 주기를 돈다.
func (r *rig) cycle(t *testing.T) {
	t.Helper()
	r.j.runCycle(context.Background())
}
