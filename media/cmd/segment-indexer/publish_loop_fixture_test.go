package main

// 발행 루프 층 시험의 픽스처(체크리스트 474 B-3 · 판단 J59 형 · J88). 4.1 픽스처(offlineLoop — 실물 인덱서 · 실물 캐시 · 가짜
// EndLive) 위에 발행 몫을 얹는다. 발행자는 실물이되 접속하지 않는 풀이라 사다리 평가와 로그 래퍼만 쓰고, 차선은 실물
// (publish.Lanes)이며 차선 작업 몸체(틱 · lazy 갱신 · 적재 · 감시) · Dirty · 업로더 손잡이는 가짜다. 루프 고루틴 자리는 시험
// 고루틴이다 — hold 가 holdTicks case 의 되감기 몫을, settle 이 완료 case 를 부른다. 작업 몸체만 차선 고루틴에서 돈다.

import (
	"context"
	"fmt"
	"log/slog"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/upload"
)

// loopBase 는 발행 루프 시험의 판정 시각 기준이다(holdTick 의 now — 실시계와 무관하다).
var loopBase = time.Date(2026, 10, 1, 12, 0, 0, 0, time.UTC)

// publishLoop 은 발행 루프 층 픽스처다.
type publishLoop struct {
	*offlineLoop
	pub   *publish.Publisher
	lanes *publish.Lanes
	work  *fakeWork
	dirty *fakeDirty
	ups   *fakeUploader
	// watched 는 지금까지 반영한 감시 결과 수다(settleWatch). audited 는 동결 감사 결과 수다(settleAudit).
	watched int
	audited int
}

// newPublishLoop 은 발행 몫을 얹은 루프 픽스처다 — 감시는 꺼져 있다(holdEvery 0 — 조립이 주기를 넘기지 않은 모양). 감시를 보는
// 시험이 켠다.
func newPublishLoop(t *testing.T) *publishLoop {
	t.Helper()
	f := &publishLoop{offlineLoop: newOfflineLoop(t, nil), lanes: publish.NewLanes(), dirty: &fakeDirty{}, ups: &fakeUploader{}}
	f.work = &fakeWork{ledgers: map[string]index.RewindLedger{}}
	pool, err := pgxpool.New(t.Context(), "postgres://user@127.0.0.1:1/none")
	if err != nil {
		t.Fatalf("풀 생성 실패(접속은 하지 않는다): %v", err)
	}
	t.Cleanup(pool.Close)
	opt := publish.DefaultOptions("w-loop", "https://media.example.com")
	opt.Log = slog.New(f.logs)
	if f.pub, err = publish.New(pool, nopPublishStore{}, opt); err != nil {
		t.Fatalf("발행자 생성 실패: %v", err)
	}
	r := f.rewind
	r.pub, r.lanes, r.work = f.pub, f.lanes, f.work.laneWork()
	r.peekDirty, r.ackDirty = f.dirty.peek, f.dirty.ack
	r.expedite, r.forgetSession = f.ups.expedite, f.ups.forget
	r.reconnectWindow, r.watchEvery, r.lazyRenewAfter = 300*time.Second, 30*time.Second, opt.LazyRenewAfter
	return f
}

// hold 는 holdTicks case 의 되감기 몫을 now 로 한 번 돌린다.
func (f *publishLoop) hold(now time.Time) {
	f.t.Helper()
	f.work.setNow(now)
	f.rewind.holdTick(f.t.Context(), now)
}

// settle 은 스트림 차선이 모두 빌 때까지 결과를 받아 완료 case 처럼 반영한다 — 반영이 새 작업(재실행 · 적재 뒤 틱 · 미룬
// 적재)을 내면 그것까지 받는다. 감시 결과가 먼저 오면 그것도 반영한다.
func (f *publishLoop) settle(now time.Time) {
	f.t.Helper()
	for f.busy() {
		f.next(now)
	}
}

// settleWatch 는 반영한 감시 결과가 모두 total 개가 될 때까지 결과를 반영한다 — 그 사이에 온 스트림 차선 결과도 반영한다
// (settle 이 감시 결과를 먼저 받았을 수 있어 개수로 센다).
func (f *publishLoop) settleWatch(now time.Time, total int) {
	f.t.Helper()
	for f.watched < total {
		f.next(now)
	}
}

// settleAudit 는 반영한 동결 감사 결과가 모두 total 개가 될 때까지 결과를 반영한다(settleWatch 와 같은 꼴).
func (f *publishLoop) settleAudit(now time.Time, total int) {
	f.t.Helper()
	for f.audited < total {
		f.next(now)
	}
}

// next 는 결과 하나를 받아 반영하고 그 종류를 돌려준다.
func (f *publishLoop) next(now time.Time) publish.Kind {
	f.t.Helper()
	select {
	case res := <-f.rewind.done():
		f.rewind.complete(f.t.Context(), res, now)
		switch res.Kind {
		case publish.KindWatch:
			f.watched++
		case publish.KindAudit:
			f.audited++
		}
		return res.Kind
	case <-time.After(5 * time.Second):
		f.t.Fatal("차선 결과가 5초 안에 오지 않았다")
		return 0
	}
}

// busy 는 루프의 in-flight 표에 도는 스트림 차선 작업이 있는가다.
func (f *publishLoop) busy() bool {
	for _, rec := range f.rewind.streams {
		if rec.running != nil {
			return true
		}
	}
	return false
}

// running 은 스트림 s 의 차선에서 도는 작업이다(없으면 nil).
func (f *publishLoop) running(s string) *laneJob {
	if rec := f.rewind.streams[s]; rec != nil {
		return rec.running
	}
	return nil
}

// put 은 스트림에 적재로 뷰를 세운다(적재 뒤 상태 — 루프 칸은 건드리지 않는다).
func (f *publishLoop) put(s string, l index.RewindLedger) {
	f.t.Helper()
	if applied, _ := f.cache.CompleteLoad(s, f.cache.BeginLoad(s, nil), l); !applied {
		f.t.Fatalf("%s 뷰 세우기 실패", s)
	}
}

// uploaded 는 스트림 s 의 ③ 업로드 원소를 Dirty 에 표시한다.
func (f *publishLoop) uploaded(s, sessionID string, seq int64) {
	f.dirty.mark(upload.DirtyEvent{Kind: upload.DirtyUploaded, StreamID: s, Axis: index.AxisPlayback, Seq: seq, SessionID: sessionID})
}

// ---------------------------------------------------------------------------
// 장부 픽스처
// ---------------------------------------------------------------------------

// segRow 는 회차 sid 의 seq 조각이다 — 길이 4초 · PDT 는 seq 에 비례하고 ③ 키가 있다. up 이면 ③ 가 올라갔다.
func segRow(s, sid string, seq int64, up bool) index.RewindRow {
	return index.RewindRow{
		Seq: seq, SessionID: sid, DurationMS: 4000,
		PlaybackPDT:      loopBase.Add(time.Duration(seq) * 4 * time.Second),
		PlaybackS3Key:    fmt.Sprintf("dvr/%s/seg/%06d.m4s", s, seq),
		PlaybackUploaded: up,
	}
}

// liveLedger 는 컷오프 0 인 스트림 s 의 장부다 — live · init 확정 회차 sid 의 조각 0..last 이고 upTo 까지 ③ 가 올라갔다.
func liveLedger(s, sid string, last, upTo int64) index.RewindLedger {
	l := index.RewindLedger{HasCutoff: true, Sessions: []index.RewindSession{{
		SessionID: sid, State: "live", InitUploaded: true, TargetDuration: 6,
	}}}
	for seq := int64(0); seq <= last; seq++ {
		l.Rows = append(l.Rows, segRow(s, sid, seq, seq <= upTo))
	}
	return l
}

// ---------------------------------------------------------------------------
// 가짜 부품
// ---------------------------------------------------------------------------

// tickCall 은 가짜 틱 한 번이 받은 입력이다.
type tickCall struct {
	st publish.State
	in publish.TickInput
}

// fakeWork 는 차선 작업 몸체의 가짜다 — 부른 입력을 적고 시험이 정한 결과를 돌려준다. 몸체는 차선 고루틴에서 돈다(락).
type fakeWork struct {
	mu      sync.Mutex
	now     time.Time
	ticks   []tickCall
	renews  []publish.State
	loads   []publish.LoadInput
	watches [][]index.WatchProbe
	audits  int
	// tickFn · renewFn 이 nil 이면 발행 성공 · 갱신 성공이다(publishedOutcome · renewedOutcome). loadFn 이 nil 이면 ledgers 의
	// 그 스트림 장부를 돌려준다. watchFn 이 nil 이면 빈 감시다.
	tickFn  func(st publish.State, in publish.TickInput) publish.Outcome
	renewFn func(st publish.State, sessionID string) publish.Outcome
	loadFn  func(in publish.LoadInput) publish.LoadOutcome
	watchFn func(probes []index.WatchProbe) publish.WatchOutcome
	ledgers map[string]index.RewindLedger
	// auditFn 이 nil 이면 빈 감사다.
	auditFn func() publish.AuditOutcome
	// gate 가 nil 이 아니면 틱 몸체가 그 채널을 받을 때까지 멈춘다(도는 틱을 만든다). loadGate · watchGate · auditGate 는 적재 ·
	// 감시 · 동결 감사 몸체의 같은 문이다.
	gate      chan struct{}
	loadGate  chan struct{}
	watchGate chan struct{}
	auditGate chan struct{}
}

func (w *fakeWork) laneWork() laneWork {
	return laneWork{tick: w.tick, renew: w.renew, load: w.load, watch: w.watch}
}

func (w *fakeWork) setNow(now time.Time) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.now = now
}

func (w *fakeWork) tick(_ context.Context, st publish.State, in publish.TickInput) publish.Outcome {
	w.mu.Lock()
	w.ticks = append(w.ticks, tickCall{st: st, in: in})
	fn, gate, now := w.tickFn, w.gate, w.now
	w.mu.Unlock()
	if gate != nil {
		<-gate
	}
	if fn != nil {
		return fn(st, in)
	}
	return publishedOutcome(st, in, now)
}

func (w *fakeWork) renew(_ context.Context, st publish.State, sessionID string) publish.Outcome {
	w.mu.Lock()
	w.renews = append(w.renews, st)
	fn, now := w.renewFn, w.now
	w.mu.Unlock()
	if fn != nil {
		return fn(st, sessionID)
	}
	st.RenewedAt = now
	return publish.Outcome{State: st}
}

func (w *fakeWork) load(_ context.Context, in publish.LoadInput) publish.LoadOutcome {
	w.mu.Lock()
	w.loads = append(w.loads, in)
	fn, l, gate := w.loadFn, w.ledgers[in.StreamID], w.loadGate
	w.mu.Unlock()
	if gate != nil {
		<-gate
	}
	if fn != nil {
		return fn(in)
	}
	return publish.LoadOutcome{Token: in.Token, RewindLedger: l}
}

func (w *fakeWork) watch(_ context.Context, probes []index.WatchProbe) publish.WatchOutcome {
	w.mu.Lock()
	w.watches = append(w.watches, probes)
	fn, gate := w.watchFn, w.watchGate
	w.mu.Unlock()
	if gate != nil {
		<-gate
	}
	if fn != nil {
		return fn(probes)
	}
	return publish.WatchOutcome{}
}

// audit 은 동결 감사 몸체다 — laneWork 에는 넣지 않는다(감사를 보는 시험만 rewind.work.audit 에 끼운다).
func (w *fakeWork) audit(context.Context) publish.AuditOutcome {
	w.mu.Lock()
	w.audits++
	fn, gate := w.auditFn, w.auditGate
	w.mu.Unlock()
	if gate != nil {
		<-gate
	}
	if fn != nil {
		return fn()
	}
	return publish.AuditOutcome{}
}

// auditCalls 는 지금까지 낸 동결 감사 수다.
func (w *fakeWork) auditCalls() int {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.audits
}

// open 은 문(gate · loadGate · watchGate · auditGate 가운데 하나를 가리키는 자리)을 비우고 그 문에서 멈춘 몸체를 풀어 준다.
func (w *fakeWork) open(gate *chan struct{}) {
	w.mu.Lock()
	ch := *gate
	*gate = nil
	w.mu.Unlock()
	close(ch)
}

// tickCalls 는 지금까지 부른 틱 입력이다.
func (w *fakeWork) tickCalls() []tickCall {
	w.mu.Lock()
	defer w.mu.Unlock()
	return slices.Clone(w.ticks)
}

// loadCalls 는 지금까지 낸 적재 입력이다.
func (w *fakeWork) loadCalls() []publish.LoadInput {
	w.mu.Lock()
	defer w.mu.Unlock()
	return slices.Clone(w.loads)
}

// renewCalls 는 지금까지 낸 lazy 갱신의 입력 상태다.
func (w *fakeWork) renewCalls() []publish.State {
	w.mu.Lock()
	defer w.mu.Unlock()
	return slices.Clone(w.renews)
}

// watchCalls 는 지금까지 낸 감시의 입력이다.
func (w *fakeWork) watchCalls() [][]index.WatchProbe {
	w.mu.Lock()
	defer w.mu.Unlock()
	return slices.Clone(w.watches)
}

// publishedOutcome 은 틱 하나가 목록을 발행한 결과다 — 세대를 하나 올리고 fence 를 쥐며(lease 갱신 시각 now), 입력 목록의
// 자기기술을 새 P 로 둔다(발행자 describe 와 같은 값). GAP 틱이면 GAP 이 섰다.
func publishedOutcome(st publish.State, in publish.TickInput, now time.Time) publish.Outcome {
	rows := in.Playlist.Rows
	st.Gen++
	st.FenceHeld, st.RenewedAt, st.ReconcileDue = true, now, false
	st.Prev = &publish.Manifest{Published: rewind.Published{
		Gen: st.Gen, MediaSequence: rows[0].Seq, PublishedSeq: rows[len(rows)-1].Seq, SegmentCount: len(rows),
		DiscontinuitySequence: in.DiscontinuitySequence,
	}, ETag: fmt.Sprintf("e%d", st.Gen)}
	out := publish.Outcome{State: st, Published: true}
	if in.GapCandidate != nil {
		k := *in.GapCandidate
		out.GapSeq = &k
	}
	return out
}

// fakeDirty 는 ③ · init 결과 이벤트 집합의 가짜다 — 같은 원소는 하나로 접히고 Ack 한 원소만 지운다(upload.Dirty 와 같은 규약).
type fakeDirty struct {
	mu     sync.Mutex
	events []upload.DirtyEvent
	acks   int
}

func (d *fakeDirty) mark(e upload.DirtyEvent) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if !slices.Contains(d.events, e) {
		d.events = append(d.events, e)
	}
}

func (d *fakeDirty) peek() []upload.DirtyEvent {
	d.mu.Lock()
	defer d.mu.Unlock()
	return slices.Clone(d.events)
}

func (d *fakeDirty) ack(events []upload.DirtyEvent) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.acks++
	d.events = slices.DeleteFunc(d.events, func(e upload.DirtyEvent) bool { return slices.Contains(events, e) })
}

// pending 은 아직 Ack 되지 않은 원소다.
func (d *fakeDirty) pending() []upload.DirtyEvent { return d.peek() }

// fakeUploader 는 업로더 손잡이(Expedite · ForgetSession)의 가짜다 — 부른 인자를 차례로 적는다.
type fakeUploader struct {
	mu        sync.Mutex
	expedited []string
	forgotten []string
}

func (u *fakeUploader) expedite(streamID string, seq int64) {
	u.mu.Lock()
	defer u.mu.Unlock()
	u.expedited = append(u.expedited, fmt.Sprintf("%s/%d", streamID, seq))
}

func (u *fakeUploader) forget(sessionID string) {
	u.mu.Lock()
	defer u.mu.Unlock()
	u.forgotten = append(u.forgotten, sessionID)
}

func (u *fakeUploader) expedites() []string {
	u.mu.Lock()
	defer u.mu.Unlock()
	return slices.Clone(u.expedited)
}

func (u *fakeUploader) forgets() []string {
	u.mu.Lock()
	defer u.mu.Unlock()
	return slices.Clone(u.forgotten)
}
