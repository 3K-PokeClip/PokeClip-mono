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
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxstate"
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

	// seeds 는 "적격 seed 는 주조된다"를 흉내 낸다(실제 판정은 SQL 이 한다).
	seeds bool

	insertCalls     int
	loadCursorCalls int
	// lastSeed 는 마지막 Insert 에 동봉된 주조 판정 입력이다(buildSeed 검증용).
	lastSeed index.Seed
	// lastSource 는 마지막 Insert 에 동봉된 세션 결정 입력이다(sessionOp 배선 검증용).
	lastSource index.SessionSource
}

type updateTailCall struct {
	streamID   string
	seq        int64
	durationMS int32
	bytes      int64
}

// insertCallCount 는 Insert 시도 횟수다(장벽 검증 — 시도 자체가 없어야 하는 국면용).
func (s *fakeStore) insertCallCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.insertCalls
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
			UploadState: r.UploadState, S3Key: r.S3Key,
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

func (s *fakeStore) Insert(_ context.Context, r index.Record, seed index.Seed, src index.SessionSource) (index.InsertOutcome, index.SeedResult, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.insertCalls++
	s.lastSeed = seed
	s.lastSource = src

	if len(s.insertErrs) > 0 {
		err := s.insertErrs[0]
		s.insertErrs = s.insertErrs[1:]
		if err != nil {
			return index.InsertInserted, index.SeedResult{}, err
		}
	}

	if s.alwaysSeqConflict {
		return index.InsertSeqConflict, index.SeedResult{}, nil
	}

	for _, existing := range s.rows[r.StreamID] {
		if existing.LocalPath == r.LocalPath {
			return index.InsertDuplicatePath, index.SeedResult{}, nil
		}
	}
	for _, existing := range s.rows[r.StreamID] {
		if existing.Seq == r.Seq {
			return index.InsertSeqConflict, index.SeedResult{}, nil
		}
	}
	s.rows[r.StreamID] = append(s.rows[r.StreamID], r)
	if s.seeds && seed.Eligible {
		return index.InsertInserted, index.SeedResult{Seeded: true}, nil
	}
	return index.InsertInserted, index.SeedResult{Decline: index.DeclineNoCorroboration}, nil
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
// fake observer — 폴러 대신 관측 스냅샷을 직접 주입한다(계획 단계 4 "스냅샷 직접 주입").
// 호출 횟수를 세는 이유: 조각 하나에 채취가 정확히 1회여야 재시도 경로에서 판정이 갈리지
// 않는다(Seed 재사용 규약과 같은 근거).
// ---------------------------------------------------------------------------

type fakeObserver struct {
	mu    sync.Mutex
	obs   map[string]mtxstate.Observation
	calls int
}

func newObserver() *fakeObserver {
	return &fakeObserver{obs: map[string]mtxstate.Observation{}}
}

func (o *fakeObserver) set(streamID string, obs mtxstate.Observation) {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.obs[streamID] = obs
}

func (o *fakeObserver) Latest(streamID string) mtxstate.Observation {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.calls++
	return o.obs[streamID]
}

func (o *fakeObserver) callCount() int {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.calls
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
	t        *testing.T
	root     string
	store    *fakeStore
	probe    *probeStub
	adopter  *fakeAdopter
	upload   *fakeUploadRequester
	observer *fakeObserver
	logs     *logCapture
	ix       *Indexer
	opt      Options
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
	// 관측 축의 두 창은 config 기본값 그대로다(OBS_FRESH 30초 · OBS_BACKFILL 60초) —
	// 판정 경계를 재는 케이스가 프로덕션과 같은 수치 위에 서야 한다.
	opt.ObsFresh = 30 * time.Second
	opt.ObsBackfill = 60 * time.Second
	// 보류 수명주기 테스트가 실제 시간을 기다려야 하므로 ms 급으로 줄인다.
	// 여기서 줄이지 않으면 (c) 계열이 held 맵 직접 주입에 의존하게 되고,
	// 그러면 ReleaseHeldTails 의 판정 자체가 검증되지 않는다.
	opt.TailHold = 20 * time.Millisecond
	return opt
}

func newFixture(t *testing.T, probeVals ...int64) *fixture {
	t.Helper()
	f := &fixture{
		t:        t,
		root:     t.TempDir(),
		store:    newFakeStore(),
		probe:    newProbe(probeVals...),
		adopter:  &fakeAdopter{},
		upload:   &fakeUploadRequester{accept: true},
		observer: newObserver(),
		logs:     &logCapture{},
		opt:      testOptions(),
	}
	f.reload()
	return f
}

// reload 는 Options 를 바꾼 뒤 인덱서를 다시 만든다.
func (f *fixture) reload() {
	f.ix = New(f.store, f.probe.fn, f.adopter, f.upload, f.observer, f.opt, slog.New(f.logs))
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

// ---------------------------------------------------------------------------
// fake UploadRequester — accepted 를 테스트가 직접 정한다.
// Disabled·격리·백오프·브레이커·큐 포화가 전부 false 하나로 오므로, 인덱서 쪽 계약을
// 확인하는 데는 이 bool 하나면 충분하다.
// ---------------------------------------------------------------------------

type fakeUploadRequester struct {
	mu     sync.Mutex
	accept bool
	got    []index.UploadTarget
}

func (f *fakeUploadRequester) RequestUpload(t index.UploadTarget) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.got = append(f.got, t)
	return f.accept
}

func (f *fakeUploadRequester) targets() []index.UploadTarget {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]index.UploadTarget(nil), f.got...)
}
