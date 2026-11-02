package expiry

// 시험 가짜 — 장부 · 객체 저장소 · clip 만료 창구(httptest) · 로그 수집기.

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// fakeLedger 는 Ledger 의 시험 구현이다. 스트림마다 행 · 문서를 들고, 실패 · 무효 컷오프를 주입한다.
type fakeLedger struct {
	mu        sync.Mutex
	rows      map[string][]index.ExpiryRow
	docs      map[string][]index.ExpiryDocument
	invalid   map[string]bool
	marked    []string
	deleted   map[string][]int64
	markErr   error
	deleteErr error
	calls     []string // 걸음 순서(스트림:걸음)
}

func newFakeLedger() *fakeLedger {
	return &fakeLedger{rows: map[string][]index.ExpiryRow{}, docs: map[string][]index.ExpiryDocument{},
		invalid: map[string]bool{}, deleted: map[string][]int64{}}
}

// put 은 스트림에 대상 행 n 개를 넣는다(seq 0..n-1 · 키 둘).
func (l *fakeLedger) put(stream string, n int) {
	l.mu.Lock()
	defer l.mu.Unlock()
	for i := range n {
		l.rows[stream] = append(l.rows[stream], index.ExpiryRow{Seq: int64(i), S3Key: fmt.Sprintf("%s/a/%d", stream, i),
			PlaybackS3Key: fmt.Sprintf("%s/p/%d", stream, i), SessionID: stream + "-s"})
	}
}

func (l *fakeLedger) step(stream, name string) {
	l.calls = append(l.calls, stream+":"+name)
}

func (l *fakeLedger) MarkExpired(_ context.Context, q index.ExpiryQuery) (int64, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.step(q.StreamID, "mark")
	if l.markErr != nil {
		return 0, l.markErr
	}
	l.marked = append(l.marked, q.StreamID)
	return 1, nil
}

func (l *fakeLedger) CutoffValid(_ context.Context, stream string) (bool, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.step(stream, "cutoff")
	return !l.invalid[stream], nil
}

func (l *fakeLedger) Rows(_ context.Context, q index.ExpiryQuery) ([]index.ExpiryRow, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.step(q.StreamID, "rows")
	return slices.Clone(l.rows[q.StreamID]), nil
}

func (l *fakeLedger) Documents(_ context.Context, q index.ExpiryQuery) ([]index.ExpiryDocument, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.step(q.StreamID, "docs")
	return slices.Clone(l.docs[q.StreamID]), nil
}

func (l *fakeLedger) DeleteRows(_ context.Context, stream string, seqs []int64) (index.DeleteResult, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.step(stream, "delete")
	if l.deleteErr != nil {
		return index.DeleteResult{}, l.deleteErr
	}
	l.deleted[stream] = append(l.deleted[stream], seqs...)
	return index.DeleteResult{RowsDeleted: int64(len(seqs))}, nil
}

func (l *fakeLedger) deletedSeqs(stream string) []int64 {
	l.mu.Lock()
	defer l.mu.Unlock()
	return slices.Clone(l.deleted[stream])
}

func (l *fakeLedger) callLog() []string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return slices.Clone(l.calls)
}

// fakeObjects 는 Objects 의 시험 구현이다. fail 이 참을 돌려주는 키는 지우지 못한다.
type fakeObjects struct {
	mu      sync.Mutex
	deleted []string
	batches []int
	fail    func(key string) bool
	hook    func(ctx context.Context) error // 요청 자체 실패(취소 등)
}

func (o *fakeObjects) Delete(ctx context.Context, keys []string) (map[string]error, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.batches = append(o.batches, len(keys))
	if o.hook != nil {
		if err := o.hook(ctx); err != nil {
			return nil, err
		}
	}
	failed := map[string]error{}
	for _, k := range keys {
		if o.fail != nil && o.fail(k) {
			failed[k] = errors.New("AccessDenied")
			continue
		}
		o.deleted = append(o.deleted, k)
	}
	return failed, nil
}

func (o *fakeObjects) deletedKeys() []string {
	o.mu.Lock()
	defer o.mu.Unlock()
	return slices.Clone(o.deleted)
}

// clipItem 은 만료 창구 응답의 방송 한 줄이다(계약 6-2 칸 일곱 중 만료가 쓰는 넷).
type clipItem struct {
	StreamID       string     `json:"streamId"`
	IngestStreamID string     `json:"ingestStreamId"`
	SegmentsFrom   *time.Time `json:"segmentsFrom"`
	SegmentsUntil  *time.Time `json:"segmentsUntil"`
}

// fakeClip 은 clip 만료 창구다. pages 는 cursor("" = 처음) → (줄, 다음 표시, hasMore) 다.
type fakeClip struct {
	mu       sync.Mutex
	srv      *httptest.Server
	pages    map[string]clipPage
	requests []*http.Request
	status   int           // 0 이 아니면 그 상태로 답한다
	delay    time.Duration // 답 전 대기
	body     []byte        // nil 이 아니면 그대로 쓴다
	redirect bool          // 302 로 답한다
}

type clipPage struct {
	items []clipItem
	next  *string
	more  bool
}

func newFakeClip(t *testing.T) *fakeClip {
	t.Helper()
	c := &fakeClip{pages: map[string]clipPage{}}
	c.srv = httptest.NewServer(http.HandlerFunc(c.serve))
	t.Cleanup(c.srv.Close)
	return c
}

func (c *fakeClip) serve(w http.ResponseWriter, r *http.Request) {
	c.mu.Lock()
	c.requests = append(c.requests, r.Clone(context.Background()))
	status, delay, body, redirect := c.status, c.delay, c.body, c.redirect
	page := c.pages[r.URL.Query().Get("cursor")]
	c.mu.Unlock()
	if delay > 0 {
		select {
		case <-time.After(delay):
		case <-r.Context().Done():
			return
		}
	}
	if redirect && r.URL.Path != "/elsewhere" { // 따라가면 /elsewhere 가 정상 장으로 답한다 — 따르지 않아야 실패다
		http.Redirect(w, r, "/elsewhere", http.StatusFound)
		return
	}
	if status != 0 {
		w.WriteHeader(status)
		return
	}
	if body != nil {
		_, _ = w.Write(body)
		return
	}
	items := page.items
	if items == nil {
		items = []clipItem{}
	}
	_ = json.NewEncoder(w).Encode(map[string]any{"broadcasts": items, "nextCursor": page.next, "hasMore": page.more})
}

func (c *fakeClip) set(f func(c *fakeClip)) {
	c.mu.Lock()
	defer c.mu.Unlock()
	f(c)
}

func (c *fakeClip) reqs() []*http.Request {
	c.mu.Lock()
	defer c.mu.Unlock()
	return slices.Clone(c.requests)
}

func ptr[T any](v T) *T { return &v }

// item 은 구간 [from, until) 의 방송 한 줄이다(from 은 기준 시각 − 1시간).
func item(stream string) clipItem {
	until := time.Date(2026, 8, 1, 0, 0, 0, 0, time.UTC)
	return clipItem{StreamID: "S-" + stream, IngestStreamID: stream, SegmentsFrom: ptr(until.Add(-time.Hour)), SegmentsUntil: &until}
}

// logRec · logSink 는 slog 처리기다 — 줄마다 메시지 · 등급 · 속성을 문자열로 남긴다.
type logRec struct {
	level slog.Level
	msg   string
	attrs map[string]string
}

type logSink struct {
	mu   *sync.Mutex
	recs *[]logRec
	with []slog.Attr
}

func newLogSink() *logSink { return &logSink{mu: &sync.Mutex{}, recs: &[]logRec{}} }

func (s *logSink) Enabled(context.Context, slog.Level) bool { return true }

func (s *logSink) Handle(_ context.Context, r slog.Record) error {
	rec := logRec{level: r.Level, msg: r.Message, attrs: map[string]string{}}
	for _, a := range s.with {
		rec.attrs[a.Key] = a.Value.String()
	}
	r.Attrs(func(a slog.Attr) bool {
		rec.attrs[a.Key] = a.Value.Resolve().String()
		return true
	})
	s.mu.Lock()
	defer s.mu.Unlock()
	*s.recs = append(*s.recs, rec)
	return nil
}

func (s *logSink) WithAttrs(as []slog.Attr) slog.Handler {
	return &logSink{mu: s.mu, recs: s.recs, with: append(slices.Clone(s.with), as...)}
}

func (s *logSink) WithGroup(string) slog.Handler { return s }

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

// rig 은 만료기 하나와 그 가짜들이다.
type rig struct {
	e       *Expirer
	ledger  *fakeLedger
	objects *fakeObjects
	clip    *fakeClip
	log     *logSink
	changed []string
	now     time.Time
}

func newRig(t *testing.T, tune func(*Options)) *rig {
	t.Helper()
	r := &rig{ledger: newFakeLedger(), objects: &fakeObjects{}, clip: newFakeClip(t), log: newLogSink(),
		now: time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)}
	var mu sync.Mutex
	opt := DefaultOptions()
	opt.ClipURL = r.clip.srv.URL
	opt.Token = NewToken("tok-canary-7f3a")
	opt.Log = slog.New(r.log)
	opt.Now = func() time.Time { return r.now }
	opt.Changed = func(stream string) {
		mu.Lock()
		defer mu.Unlock()
		r.changed = append(r.changed, stream)
	}
	if tune != nil {
		tune(&opt)
	}
	e, err := New(r.ledger, r.objects, opt)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	r.e = e
	return r
}

// sweep 은 한 회차를 돈다.
func (r *rig) sweep(t *testing.T) {
	t.Helper()
	r.e.sweep(context.Background())
}
