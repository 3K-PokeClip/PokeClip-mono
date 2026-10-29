package main

// 정산 루프 층 시험의 픽스처(POK-196 M6-3b 커밋 ③). 4.1 픽스처(offlineLoop — 실물 인덱서 · 실물 캐시 · 가짜 EndLive) 위에
// 정산 몫을 얹는다. 차선은 실물(publish.Lanes)이고 차선 작업 몸체(스캔 · 행 읽기 · 걸음 · 상태 읽기 · 해제)는 가짜다. 루프
// 고루틴 자리는 시험 고루틴이다 — hold 가 holdTicks case 의 되감기 몫을, settle 이 완료 case 를 부른다.

import (
	"cmp"
	"context"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/finalize"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
)

// fakeFinalWork 는 정산 차선 작업의 가짜다. 칸이 nil 이면 빈 결과다. 부른 기록과 동시에 도는 걸음 수의 최댓값을 적는다.
type fakeFinalWork struct {
	mu sync.Mutex
	// scanRows 는 다음 스캔이 돌려줄 행이다(부를 때마다 같은 값).
	scanRows   []index.EndingSession
	scanClosed []string
	scans      int
	// rows 는 빠른 길 행 읽기가 돌려줄 행이다(세션 id → 행).
	rows  map[string]index.EndingSession
	loads []string
	// stepOut 은 걸음 결과를 정한다(nil 이면 Done). gate 가 nil 이 아니면 걸음이 그 채널이 닫힐 때까지 선다. ctx 가 끝나서
	// 돌아올 때는 cancelDelay 만큼 더 머문다(취소 뒤 마저 끝나는 문장).
	stepOut           func(index.EndingSession, finalize.Decision) finalize.Outcome
	gate              chan struct{}
	cancelDelay       time.Duration
	steps             []finalize.Step
	inFlight, maxFly  int
	vodFly, maxVODFly int
	// playback 은 상태 읽기 결과다. readGate 가 nil 이 아니면 첫 읽기가 그 채널이 닫힐 때까지 선다. readsGate 가 nil 이
	// 아니면 모든 읽기가 그 채널이 닫힐 때까지 선다.
	playback  func(stream string) (index.PlaybackState, bool, error)
	readGate  chan struct{}
	readsGate chan struct{}
	reads     int
	revokeOut publish.RevokeOutcome
	revokes   []string
}

func (w *fakeFinalWork) work() finalizeWork {
	return finalizeWork{
		scan: func(context.Context, index.ScanCursor) finalScan {
			w.mu.Lock()
			defer w.mu.Unlock()
			w.scans++
			return finalScan{rows: append([]index.EndingSession(nil), w.scanRows...), closed: w.scanClosed}
		},
		load: func(_ context.Context, id string) finalLoad {
			w.mu.Lock()
			defer w.mu.Unlock()
			w.loads = append(w.loads, id)
			r, ok := w.rows[id]
			return finalLoad{sessionID: id, row: r, found: ok}
		},
		step: func(ctx context.Context, s index.EndingSession, d finalize.Decision) finalStep {
			w.mu.Lock()
			w.steps = append(w.steps, d.Step)
			w.inFlight++
			w.maxFly = max(w.maxFly, w.inFlight)
			if d.Step == finalize.StepFreezeVOD {
				w.vodFly++
				w.maxVODFly = max(w.maxVODFly, w.vodFly)
			}
			gate, out, linger := w.gate, finalize.OutcomeDone, w.cancelDelay
			if w.stepOut != nil {
				out = w.stepOut(s, d)
			}
			w.mu.Unlock()
			if gate != nil {
				select {
				case <-gate:
				case <-ctx.Done():
					time.Sleep(linger)
				}
			}
			w.mu.Lock()
			w.inFlight--
			if d.Step == finalize.StepFreezeVOD {
				w.vodFly--
			}
			w.mu.Unlock()
			return finalStep{row: s, d: d, out: out, progressed: out == finalize.OutcomeDone, notify: d.Step == finalize.StepMarkEnded}
		},
		playback: func(ctx context.Context, stream string) playbackRead {
			w.mu.Lock()
			w.reads++
			gate := cmp.Or(w.readGate, w.readsGate)
			w.readGate = nil
			read := w.playback
			w.mu.Unlock()
			r := playbackRead{streamID: stream}
			if read != nil {
				r.st, r.ok, r.err = read(stream)
			}
			if gate != nil {
				select {
				case <-gate:
				case <-ctx.Done():
				}
			}
			return r
		},
		revoke: func(_ context.Context, stream, id string) publish.RevokeOutcome {
			w.mu.Lock()
			defer w.mu.Unlock()
			w.revokes = append(w.revokes, stream+"/"+id)
			return w.revokeOut
		},
	}
}

func (w *fakeFinalWork) stepsSeen() []finalize.Step {
	w.mu.Lock()
	defer w.mu.Unlock()
	return append([]finalize.Step(nil), w.steps...)
}

// sinkLog 는 PlaybackSink 의 가짜다 — 보낸 값을 차례로 적는다.
type sinkLog struct {
	mu   sync.Mutex
	sent []sentState
}

type sentState struct {
	stream string
	st     index.PlaybackState
}

func (s *sinkLog) sink(stream string, st index.PlaybackState) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sent = append(s.sent, sentState{stream, st})
}

func (s *sinkLog) all() []sentState {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]sentState(nil), s.sent...)
}

// finalLoop 은 정산 루프 층 픽스처다.
type finalLoop struct {
	*offlineLoop
	lanes   *publish.Lanes
	work    *fakeFinalWork
	inbox   *session.PlaybackInbox
	intents *session.IntentInbox
	sunk    *sinkLog
}

// newFinalLoop 은 발행 모드 mode 의 정산 몫을 얹은 루프 픽스처다. 스캔 주기는 holdTick 하나(시험이 매 틱 스캔을 본다)다.
func newFinalLoop(t *testing.T, mode finalize.Mode) *finalLoop {
	t.Helper()
	f := &finalLoop{offlineLoop: newOfflineLoop(t, nil), lanes: publish.NewLanes(), work: &fakeFinalWork{rows: map[string]index.EndingSession{}},
		inbox: session.NewPlaybackInbox(), intents: session.NewIntentInbox(), sunk: &sinkLog{}}
	r := f.rewind
	r.lanes, r.holdEvery = f.lanes, time.Second
	r.fin = newFinalizer(finalizerOptions{
		Mode: mode, Every: time.Second, CloseAfter: 360 * time.Second, Work: f.work.work(),
		Inbox: f.inbox, Intents: f.intents, Sink: f.sunk.sink, PlaybackReadMax: 2,
	})
	return f
}

// hold 는 holdTicks case 의 되감기 몫을 now 로 한 번 돌린다.
func (f *finalLoop) hold(now time.Time) {
	f.t.Helper()
	f.rewind.holdTick(f.t.Context(), now)
}

// settle 은 정산 · 상태 전달 · 스트림 차선이 모두 빌 때까지 결과를 받아 반영한다.
func (f *finalLoop) settle(now time.Time) {
	f.t.Helper()
	for f.rewind.fin.busy() || f.streamBusy() {
		f.next(now)
	}
}

func (f *finalLoop) streamBusy() bool {
	for _, rec := range f.rewind.streams {
		if rec.running != nil {
			return true
		}
	}
	return false
}

// laneResultWait 는 차선 결과 하나를 기다리는 상한이다. 실 장부 몸체(newFinalizeWork)의 걸음은 잠금 대기 · 되돌림을 합쳐
// 2 × TxnDeadline 까지 정당하게 걸린다(index.EndLive 규약과 같다) — 그보다 짧게 기다리면 부하가 큰 기계에서 시험이 흔들린다
// (커밋 ④ 전체 실행 · 부하 평균 100 에서 5초 대기가 한 번 끊겼다).
const laneResultWait = 2*index.TxnDeadline + 5*time.Second

// next 는 결과 하나를 받아 반영하고 그 종류를 돌려준다.
func (f *finalLoop) next(now time.Time) publish.Kind {
	f.t.Helper()
	select {
	case res := <-f.rewind.done():
		f.rewind.complete(f.t.Context(), res, now)
		return res.Kind
	case <-time.After(laneResultWait):
		f.t.Fatalf("차선 결과가 %v 안에 오지 않았다", laneResultWait)
		return 0
	}
}

// endingRow 는 정산 스캔 행 하나다 — 기본은 오프라인으로 막 끝난 꼬리(dvr open).
func endingRow(id string, edit func(*index.EndingSession)) index.EndingSession {
	s := index.EndingSession{SessionID: id, StreamID: "demo", RoundID: "R-" + id, EndReason: "offline", DVRState: "open",
		VODState: "none", EndingAt: loopBase.Add(-time.Minute), LastEnd: loopBase.Add(-70 * time.Second), Now: loopBase}
	if edit != nil {
		edit(&s)
	}
	return s
}

// capRec 는 로그 한 줄의 등급과 속성이다.
type capRec struct {
	level slog.Level
	attrs map[string]any
}

// last 는 그 메시지의 마지막 기록이다(없으면 nil).
func (c *logCapture) last(msg string) *capRec {
	c.mu.Lock()
	defer c.mu.Unlock()
	for i := len(c.records) - 1; i >= 0; i-- {
		if r := c.records[i]; r.Message == msg {
			out := &capRec{level: r.Level, attrs: map[string]any{}}
			r.Attrs(func(a slog.Attr) bool {
				out.attrs[a.Key] = a.Value.Any()
				return true
			})
			return out
		}
	}
	return nil
}

// noEndedEnqueue 는 완결 평가 칸의 무동작 가짜다(게이트 꺼짐과 같다).
func noEndedEnqueue(context.Context, pgx.Tx, string) error { return nil }
