package indexer

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// ---------------------------------------------------------------------------
// fake Store — 프로덕션 패키지가 아니라 이 테스트 파일 안에만 둔다(계획의 추상화 상한).
// PK(stream_id, seq) 와 UNIQUE(stream_id, local_path) 를 실제 DDL 과 같은 규칙으로 흉내 낸다.
// ---------------------------------------------------------------------------

type fakeStore struct {
	mu   sync.Mutex
	rows map[string][]index.Record // stream_id -> 행들

	// 주입 가능한 고장. 값이 있으면 앞에서부터 하나씩 소비한다.
	insertErrs []error
	// alwaysSeqConflict 는 "다른 쓰기자가 계속 seq 를 선점한다"를 재현한다.
	alwaysSeqConflict bool
	updateTailOK      *bool
	updateTail        []updateTailCall

	insertCalls     int
	loadCursorCalls int
}

type updateTailCall struct {
	streamID   string
	seq        int64
	durationMS int32
	bytes      int64
}

func newFakeStore() *fakeStore {
	return &fakeStore{rows: map[string][]index.Record{}}
}

func (s *fakeStore) seed(recs ...index.Record) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, r := range recs {
		s.rows[r.StreamID] = append(s.rows[r.StreamID], r)
	}
}

func (s *fakeStore) LoadCursor(_ context.Context, streamID string) (index.Cursor, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.loadCursorCalls++

	rows := s.rows[streamID]
	if len(rows) == 0 {
		return index.Cursor{}, nil
	}
	tailIdx := 0
	for i, r := range rows {
		if r.Seq > rows[tailIdx].Seq {
			tailIdx = i
		}
	}
	r := rows[tailIdx]
	return index.Cursor{
		NextSeq: r.Seq + 1,
		Tail: &index.TailRow{
			Seq: r.Seq, StartPTSMS: r.StartPTSMS, StartWallUTC: r.StartWallUTC,
			DurationMS: r.DurationMS, Bytes: r.Bytes, LocalPath: r.LocalPath,
			UploadState: r.UploadState,
		},
	}, nil
}

func (s *fakeStore) ExistingPaths(_ context.Context, streamID string) (map[string]struct{}, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := map[string]struct{}{}
	for _, r := range s.rows[streamID] {
		out[r.LocalPath] = struct{}{}
	}
	return out, nil
}

func (s *fakeStore) Insert(_ context.Context, r index.Record) (index.InsertOutcome, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.insertCalls++

	if len(s.insertErrs) > 0 {
		err := s.insertErrs[0]
		s.insertErrs = s.insertErrs[1:]
		if err != nil {
			return index.InsertInserted, err
		}
	}

	if s.alwaysSeqConflict {
		return index.InsertSeqConflict, nil
	}

	for _, existing := range s.rows[r.StreamID] {
		if existing.LocalPath == r.LocalPath {
			return index.InsertDuplicatePath, nil
		}
	}
	for _, existing := range s.rows[r.StreamID] {
		if existing.Seq == r.Seq {
			return index.InsertSeqConflict, nil
		}
	}
	s.rows[r.StreamID] = append(s.rows[r.StreamID], r)
	return index.InsertInserted, nil
}

func (s *fakeStore) UpdateTail(_ context.Context, streamID string, seq int64, durationMS int32, bytes int64) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.updateTail = append(s.updateTail, updateTailCall{streamID, seq, durationMS, bytes})

	if s.updateTailOK != nil {
		return *s.updateTailOK, nil
	}
	for i, r := range s.rows[streamID] {
		if r.Seq == seq && r.UploadState == index.UploadStatePending {
			s.rows[streamID][i].DurationMS = durationMS
			s.rows[streamID][i].Bytes = bytes
			return true, nil
		}
	}
	return false, nil
}

func (s *fakeStore) records(streamID string) []index.Record {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := append([]index.Record(nil), s.rows[streamID]...)
	return out
}

// ---------------------------------------------------------------------------
// fake probe — 호출 순서대로 값을 돌려준다. 마지막 값은 계속 반복된다.
// growTo 가 설정되면 프로브가 파일을 키운다 = "재는 동안에도 쓰이고 있는" 상황 재현.
// ---------------------------------------------------------------------------

type probeStub struct {
	mu     sync.Mutex
	vals   []int64
	err    error
	calls  int
	growTo string
	grown  int
}

func newProbe(vals ...int64) *probeStub { return &probeStub{vals: vals} }

func (p *probeStub) fn(path string) (int64, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.calls++
	if p.growTo != "" && p.grown < 3 {
		p.grown++
		_ = os.WriteFile(p.growTo, make([]byte, 100*(p.grown+1)), 0o600)
	}
	if p.err != nil {
		return 0, p.err
	}
	if len(p.vals) == 0 {
		return 4000, nil
	}
	i := min(p.calls-1, len(p.vals)-1)
	return p.vals[i], nil
}

// ---------------------------------------------------------------------------
// fake adopter — 워처 대신 되돌려받은 세그먼트를 기록한다.
// ---------------------------------------------------------------------------

type fakeAdopter struct {
	mu   sync.Mutex
	segs []recording.Segment
}

func (a *fakeAdopter) Adopt(seg recording.Segment) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.segs = append(a.segs, seg)
}

func (a *fakeAdopter) count() int {
	a.mu.Lock()
	defer a.mu.Unlock()
	return len(a.segs)
}

// ---------------------------------------------------------------------------
// 로그 캡처 — "침묵 실패가 아님"을 테스트가 직접 확인할 수 있게 한다.
// ---------------------------------------------------------------------------

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

func (c *logCapture) count(level slog.Level, msg string) int {
	c.mu.Lock()
	defer c.mu.Unlock()
	n := 0
	for _, r := range c.records {
		if r.Level == level && r.Message == msg {
			n++
		}
	}
	return n
}

// attrs 는 그 메시지의 **마지막** 기록에서 속성을 키-값 맵으로 뽑는다.
// "로그가 났다"가 아니라 "무슨 값이 실렸다"까지 봐야 defer 인자 평가 시점 같은 함정이 잡힌다.
func (c *logCapture) attrs(msg string) map[string]any {
	c.mu.Lock()
	defer c.mu.Unlock()
	var found *slog.Record
	for i := range c.records {
		if c.records[i].Message == msg {
			found = &c.records[i]
		}
	}
	if found == nil {
		return nil
	}
	out := map[string]any{}
	found.Attrs(func(a slog.Attr) bool {
		out[a.Key] = a.Value.Any()
		return true
	})
	return out
}

func (c *logCapture) errorCount() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	n := 0
	for _, r := range c.records {
		if r.Level >= slog.LevelError {
			n++
		}
	}
	return n
}

// ---------------------------------------------------------------------------
// 픽스처
// ---------------------------------------------------------------------------

type fixture struct {
	t       *testing.T
	root    string
	store   *fakeStore
	probe   *probeStub
	adopter *fakeAdopter
	logs    *logCapture
	ix      *Indexer
	opt     Options
}

// testOptions 는 기본값의 의미는 그대로 두고 시간 상수만 짧게 줄인다.
// 타이밍 의존 테스트를 sleep 이 아니라 주입으로 빠르게 만든다.
func testOptions() Options {
	opt := DefaultOptions()
	opt.IdleTimeout = 80 * time.Millisecond
	opt.InsertRetryBase = time.Millisecond
	opt.Settle = recording.SettleOptions{
		PollInterval: 5 * time.Millisecond,
		SettleWait:   10 * time.Millisecond,
		MaxSettle:    60 * time.Millisecond,
	}
	return opt
}

func newFixture(t *testing.T, probeVals ...int64) *fixture {
	t.Helper()
	f := &fixture{
		t:       t,
		root:    t.TempDir(),
		store:   newFakeStore(),
		probe:   newProbe(probeVals...),
		adopter: &fakeAdopter{},
		logs:    &logCapture{},
		opt:     testOptions(),
	}
	f.ix = New(f.store, f.probe.fn, f.adopter, f.opt, slog.New(f.logs))
	return f
}

// reload 는 Options 를 바꾼 뒤 인덱서를 다시 만든다.
func (f *fixture) reload() {
	f.ix = New(f.store, f.probe.fn, f.adopter, f.opt, slog.New(f.logs))
}

// makeFile 은 실제 파일을 만들고 mtime 을 충분히 과거로 돌린다.
// H4(유휴 커밋 전 mtime 재검)가 기본적으로 통과하도록 하기 위해서다.
func (f *fixture) makeFile(streamID, name string, size int) string {
	f.t.Helper()
	dir := filepath.Join(f.root, streamID)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		f.t.Fatalf("디렉토리 생성 실패: %v", err)
	}
	p := filepath.Join(dir, name)
	if err := os.WriteFile(p, make([]byte, size), 0o600); err != nil {
		f.t.Fatalf("파일 생성 실패: %v", err)
	}
	f.touch(p, time.Now().Add(-time.Hour))
	return p
}

func (f *fixture) touch(path string, at time.Time) {
	f.t.Helper()
	if err := os.Chtimes(path, at, at); err != nil {
		f.t.Fatalf("mtime 조정 실패: %v", err)
	}
}

// segment 는 파일을 만들고 그 경로를 파싱해 Segment 를 만든다.
func (f *fixture) segment(streamID, name string, size int, reason recording.CompletionReason) recording.Segment {
	f.t.Helper()
	p := f.makeFile(streamID, name, size)
	seg, err := recording.ParseSegmentPath(f.root, p)
	if err != nil {
		f.t.Fatalf("ParseSegmentPath 실패: %v", err)
	}
	seg.Reason = reason
	return seg
}

func (f *fixture) handle(seg recording.Segment) error {
	f.t.Helper()
	return f.ix.Handle(context.Background(), seg)
}

func (f *fixture) mustHandle(seg recording.Segment) {
	f.t.Helper()
	if err := f.handle(seg); err != nil {
		f.t.Fatalf("Handle 실패: %v", err)
	}
}

// segName 은 recordPath 형식의 파일명을 만든다.
func segName(base time.Time, offset time.Duration) string {
	t := base.Add(offset).UTC()
	return fmt.Sprintf("%s-%06d.mp4", t.Format("2006-01-02_15-04-05"), t.Nanosecond()/1000)
}

var errStoreDown = errors.New("DB 사망")

var baseWall = time.Date(2026, 7, 25, 10, 0, 0, 0, time.UTC)
