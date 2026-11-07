package main

// 회차 VOD 의 루프 몫 — 깨우기 · 깨우기 병합(wakeFast) · 물러섬 기억 · 회차 신호(POK-549 C5 · 설계 r4 2.4 · 3.2 · 8절 ·
// R14 · R19 · R24 · R25 · R27 · R29 · 계획 P3 · P4). 새 칸(stepChain · wake · vodChainOnly · waitWarned)을 부르는 신구조
// 시험이라 음성 대조가 아니라 변이(M12 · M17 · M19 · M20 · M23 · M24)로 교정한다. 기존 픽스처(newFinalLoop)는 그대로 쓰고
// 차선 작업 몸체만 이 파일의 가짜(roundWorld)로 갈아 끼운다. 장부 쪽(runFinalStep · scanFinalize 의 깨우기 대상)은 실 PG
// 시험이다 — PG_DSN 이 없으면 그 시험만 skip 된다.

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/finalize"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
)

// roundStepRec 은 가짜 걸음 한 번의 기록이다 — 몇 번째 틱에 어느 차선 작업(chain = stepChain)으로 무슨 판정을 받았는가.
type roundStepRec struct {
	id    string
	d     finalize.Decision
	chain bool
	tick  int
}

// roundWorld 는 회차 시험의 가짜 차선 작업이다. 스캔은 쪽 크기 finalizeScanLimit 의 커서 순회다 — 쪽마다 채움 행(후속이
// 있어 StepWait)으로 채우고 place 로 정한 행을 그 쪽 앞머리에 둔다. 걸음 결과는 out 이 정하고(row · d 는 이쪽이 채운다),
// park 한 세션의 걸음은 release 때까지 선다.
type roundWorld struct {
	mu    sync.Mutex
	pages [][]index.EndingSession
	rows  map[string]index.EndingSession
	// scanWake 는 스캔 차례(0부터) → 그 스캔이 닫은 회차의 깨우기다.
	scanWake map[int][]roundWake
	scans    int
	tick     int
	loads    []roundStepRec
	steps    []roundStepRec
	gates    map[string]chan struct{}
	out      func(s index.EndingSession, d finalize.Decision, chain bool) finalStep
}

func newRoundWorld(pages int) *roundWorld {
	w := &roundWorld{rows: map[string]index.EndingSession{}, scanWake: map[int][]roundWake{}, gates: map[string]chan struct{}{}}
	for p := range pages {
		page := make([]index.EndingSession, 0, finalizeScanLimit)
		for i := range finalizeScanLimit {
			page = append(page, endingRow(fmt.Sprintf("fill-%d-%02d", p, i), func(s *index.EndingSession) {
				s.StreamID, s.DVRState, s.VODState, s.HasSuccessor = fmt.Sprintf("fill-%d", p), "frozen", "freezing", true
			}))
		}
		w.pages = append(w.pages, page)
	}
	return w
}

// place 는 행들을 쪽 p 의 앞머리에 둔다(쪽 크기는 그대로 — 채움 행을 밀어낸다). 행 읽기도 그 행을 돌려준다(drop 전까지).
func (w *roundWorld) place(p int, rows ...index.EndingSession) {
	w.pages[p] = append(slices.Clone(rows), w.pages[p][:finalizeScanLimit-len(rows)]...)
	for _, r := range rows {
		w.rows[r.SessionID] = r
	}
}

// park 는 세션 id 의 다음 걸음들을 release 때까지 세운다.
// drop 은 행 읽기에서 세션 id 를 지운다 — 섰던 앞 사슬이 ended 로 빠진 것과 같다(다시 걸음을 내지 않는다).
func (w *roundWorld) drop(id string) {
	w.mu.Lock()
	defer w.mu.Unlock()
	delete(w.rows, id)
}

func (w *roundWorld) park(id string) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.gates[id] = make(chan struct{})
}

func (w *roundWorld) release(id string) {
	w.mu.Lock()
	defer w.mu.Unlock()
	close(w.gates[id])
	delete(w.gates, id)
}

func (w *roundWorld) parked(id string) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	_, ok := w.gates[id]
	return ok
}

func (w *roundWorld) work() finalizeWork {
	return finalizeWork{
		scan: func(_ context.Context, after index.ScanCursor) finalScan {
			w.mu.Lock()
			defer w.mu.Unlock()
			p := 0
			for i, page := range w.pages {
				if after.SessionID == page[len(page)-1].SessionID {
					p = i + 1
				}
			}
			out := finalScan{rows: slices.Clone(w.pages[p]), full: true, wake: w.scanWake[w.scans]}
			if p+1 < len(w.pages) {
				last := w.pages[p][len(w.pages[p])-1]
				out.next = index.ScanCursor{EndingAt: last.EndingAt, SessionID: last.SessionID}
			}
			w.scans++
			return out
		},
		load: func(_ context.Context, id string) finalLoad {
			w.mu.Lock()
			defer w.mu.Unlock()
			w.loads = append(w.loads, roundStepRec{id: id, tick: w.tick})
			r, ok := w.rows[id]
			return finalLoad{sessionID: id, row: r, found: ok}
		},
		step: func(ctx context.Context, s index.EndingSession, d finalize.Decision) finalStep {
			return w.run(ctx, s, d, false)
		},
		stepChain: func(ctx context.Context, s index.EndingSession, d finalize.Decision) finalStep {
			return w.run(ctx, s, d, true)
		},
		playback: func(_ context.Context, stream string) playbackRead { return playbackRead{streamID: stream} },
	}
}

func (w *roundWorld) run(ctx context.Context, s index.EndingSession, d finalize.Decision, chain bool) finalStep {
	w.mu.Lock()
	w.steps = append(w.steps, roundStepRec{id: s.SessionID, d: d, chain: chain, tick: w.tick})
	gate, out := w.gates[s.SessionID], w.out
	w.mu.Unlock()
	if gate != nil {
		select {
		case <-gate:
		case <-ctx.Done():
		}
	}
	res := finalStep{out: finalize.OutcomeDone, progressed: true}
	if out != nil {
		res = out(s, d, chain)
	}
	res.row, res.d = s, d
	return res
}

// stepsOf 는 세션 id 의 걸음 기록이다.
func (w *roundWorld) stepsOf(id string) []roundStepRec {
	w.mu.Lock()
	defer w.mu.Unlock()
	var out []roundStepRec
	for _, r := range w.steps {
		if r.id == id {
			out = append(out, r)
		}
	}
	return out
}

// loadsOf 는 세션 id 의 빠른 길 행 읽기 기록이다.
func (w *roundWorld) loadsOf(id string) []roundStepRec {
	w.mu.Lock()
	defer w.mu.Unlock()
	var out []roundStepRec
	for _, r := range w.loads {
		if r.id == id {
			out = append(out, r)
		}
	}
	return out
}

// roundLoop 은 정산 루프 픽스처(newFinalLoop) 위에 roundWorld 를 얹은 것이다. 시계는 loopBase 에서 1초 틱이다.
type roundLoop struct {
	*finalLoop
	world *roundWorld
	tick  int
}

// newRoundLoop 은 발행 모드 정산 루프에 가짜 world 를 얹는다. every 는 스캔 주기다(운영 30초 — 스캔 재방문이 깨우기와 갈린다).
func newRoundLoop(t *testing.T, w *roundWorld, every time.Duration) *roundLoop {
	t.Helper()
	f := newFinalLoop(t, finalize.ModePublishing)
	f.rewind.fin.every, f.rewind.fin.work = every, w.work()
	return &roundLoop{finalLoop: f, world: w}
}

func (l *roundLoop) now() time.Time { return loopBase.Add(time.Duration(l.tick) * time.Second) }

// step 은 다음 틱 하나다 — holdTick 을 돌리고 park 한 걸음 밖의 결과를 모두 반영한 뒤 I-fast 를 단언한다(R24 ⑦). 첫 부름이
// 틱 0 이다.
func (l *roundLoop) step() {
	l.t.Helper()
	l.world.mu.Lock()
	l.world.tick = l.tick
	l.world.mu.Unlock()
	now := l.now()
	l.hold(now)
	l.settleUnparked(now)
	assertIFast(l.t, l.rewind.fin)
	l.tick++
}

// run 은 n 틱을 돌린다.
func (l *roundLoop) run(n int) {
	l.t.Helper()
	for range n {
		l.step()
	}
}

// release 는 park 한 걸음을 풀고 그 결과까지 지금 틱 시각으로 반영한다(다음 step 은 그 다음 틱이다).
func (l *roundLoop) release(id string) {
	l.t.Helper()
	l.world.release(id)
	now := loopBase.Add(time.Duration(l.tick-1) * time.Second)
	l.settle(now)
	assertIFast(l.t, l.rewind.fin)
}

// settleUnparked 는 park 한 걸음을 뺀 차선이 모두 빌 때까지 결과를 받아 반영한다.
func (l *roundLoop) settleUnparked(now time.Time) {
	l.t.Helper()
	for {
		fin := l.rewind.fin
		busy := fin.scanning || len(fin.loading) > 0 || len(fin.reading) > 0 || l.streamBusy()
		for id := range fin.running {
			busy = busy || !l.world.parked(id)
		}
		if !busy {
			return
		}
		l.next(now)
	}
}

// assertIFast 는 불변식 I-fast 다 — 빠른 길 항목과 재시도 예약이 둘 다 있으면 항목은 예약보다 이르지 않다(설계 2.4).
func assertIFast(t *testing.T, fin *finalizer) {
	t.Helper()
	for id, e := range fin.fast {
		if at, ok := fin.retryAt[id]; ok && e.due.Before(at) {
			t.Fatalf("I-fast 위반 %s: fast.due %v < retryAt %v", id, e.due, at)
		}
	}
}

// 회차 R 의 앞 사슬 S · 마지막 사슬 T 행 — 둘 다 dvr 종국 · vod freezing(StepFreezeVOD) · 같은 스트림이다.
func roundRow(id string, edit func(*index.EndingSession)) index.EndingSession {
	return endingRow(id, func(s *index.EndingSession) {
		s.RoundID, s.DVRState, s.VODState = "R", "frozen", "freezing"
		if edit != nil {
			edit(s)
		}
	})
}

// wakeT 는 마지막 사슬 T 깨우기다.
var wakeT = roundWake{stream: "demo", round: "R", session: "T"}

// doneWaking 은 섰고 wake 를 깨우는 앞 사슬 id 의 걸음 결과다 — id 는 행 읽기에서 빠진다.
func (w *roundWorld) doneWaking(id string, wake roundWake) finalStep {
	w.drop(id)
	return finalStep{out: finalize.OutcomeDone, progressed: true, wake: wake,
		freeze: publish.FreezeOutcome{Axis: publish.AxisVOD, Result: publish.ResultFrozen, Scope: publish.ScopeChain}}
}

// roundWaitStep 은 회차 대기(Retry · 사유 reason) 결과다 — outcomeOf 가 OutcomeUnsettled 로 센다.
func roundWaitStep(reason string) finalStep {
	fo := publish.FreezeOutcome{Axis: publish.AxisVOD, Result: publish.ResultRetry, Retry: true, Reason: reason, Scope: publish.ScopeRound}
	return finalStep{freeze: fo, out: outcomeOf(fo)}
}

// R24 ① · R14 cmd — 앞 사슬 S 의 VOD 종국(정산 창 뒤 abandon 동결 포함)이 마지막 사슬 T 를 깨운다. T 는 스캔 3쪽에 있어
// 스캔 재방문(60초 뒤)을 기다리지 않고 S 결과 뒤 2틱 안에 행을 읽고 걸음을 낸다. 같은 스트림 상한이 1 이라 앞서 거둔 S
// 행 읽기 뒤로 한 틱 밀릴 수 있다. 변이 M12(깨우기 제거) → T 걸음 0 으로 FAIL.
func TestFrontChainTerminalWakesLastChain(t *testing.T) {
	tests := []struct {
		name    string
		abandon bool
	}{
		{name: "정산_창_안", abandon: false},
		{name: "정산_창_뒤_abandon(R14)", abandon: true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			w := newRoundWorld(3)
			w.place(0, roundRow("S", func(s *index.EndingSession) {
				if tt.abandon {
					s.EndingAt = loopBase.Add(-index.SettleWindow - time.Minute)
				}
			}))
			w.place(2, roundRow("T", nil))
			w.out = func(s index.EndingSession, d finalize.Decision, _ bool) finalStep {
				if s.SessionID == "S" {
					return w.doneWaking("S", wakeT)
				}
				return finalStep{out: finalize.OutcomeDone, progressed: true}
			}
			l := newRoundLoop(t, w, 30*time.Second)

			l.run(3) // 틱 0 S 걸음 · 틱 1–2 깨우기

			s := w.stepsOf("S")
			if len(s) != 1 || s[0].d.Abandon != tt.abandon {
				t.Fatalf("S 걸음 = %+v, want 한 번 · Abandon %v", s, tt.abandon)
			}
			if got := w.stepsOf("T"); len(got) == 0 || got[0].tick > 2 || got[0].d.Step != finalize.StepFreezeVOD {
				t.Fatalf("T 걸음 = %+v, want 틱 2 안에 StepFreezeVOD(깨우기) — 스캔 3쪽 재방문은 틱 60", got)
			}
		})
	}
}

// R24 ② — 다른 스트림의 VOD 동결 O 가 전역 VOD 칸(1)을 쥔 동안 깨워진 T 는 칸을 기다렸다가 O 결과 다음 틱에 걸음을 낸다
// (깨우기가 칸 상한을 넘지 않고 잃히지도 않는다). 깨우는 앞 사슬 S 는 phase 4(ended — 깨우기 2)라 VOD 칸을 쓰지 않는다.
func TestWokenLastChainWaitsForVODSlot(t *testing.T) {
	w := newRoundWorld(3)
	w.place(0, endingRow("O", func(s *index.EndingSession) { s.StreamID, s.DVRState, s.VODState = "other", "frozen", "freezing" }),
		roundRow("S", func(s *index.EndingSession) { s.VODState = "freeze_failed" }))
	w.place(2, roundRow("T", nil))
	w.out = func(s index.EndingSession, d finalize.Decision, _ bool) finalStep {
		if s.SessionID == "S" {
			return w.doneWaking("S", wakeT)
		}
		w.drop(s.SessionID) // O 는 섰고 다시 걸음을 내지 않는다
		return finalStep{out: finalize.OutcomeDone, progressed: true}
	}
	w.park("O")
	l := newRoundLoop(t, w, 30*time.Second)

	l.run(4) // 틱 0 O 동결(섬) · S phase 4 · 틱 1–3 T 행 읽기마다 VOD 칸 없음
	if s := w.stepsOf("S"); len(s) != 1 || s[0].d.Step != finalize.StepMarkEnded {
		t.Fatalf("S 걸음 = %+v, want StepMarkEnded 한 번", s)
	}
	if got := w.stepsOf("T"); len(got) != 0 {
		t.Fatalf("VOD 칸이 찼는데 T 걸음 %+v", got)
	}
	if len(w.loadsOf("T")) == 0 {
		t.Fatal("깨운 T 를 한 번도 읽지 않았다")
	}
	l.release("O") // 틱 3 시각에 O 결과
	l.step()       // 틱 4

	if got := w.stepsOf("T"); len(got) != 1 || got[0].tick != 4 {
		t.Fatalf("T 걸음 = %+v, want O 결과 다음 틱(4)에 한 번", got)
	}
}

// R24 ③ — 회차 스캔이 회차를 닫으면(최신 세션 ending) 그 최신 T 를 깨운다. T 는 스캔 3쪽인데 다음 틱에 걸음을 낸다.
func TestRoundScanCloseWakesLatest(t *testing.T) {
	w := newRoundWorld(3)
	w.place(2, roundRow("T", nil))
	w.scanWake[0] = []roundWake{wakeT}
	l := newRoundLoop(t, w, 30*time.Second)

	l.run(2)

	if got := w.stepsOf("T"); len(got) != 1 || got[0].tick != 1 {
		t.Fatalf("T 걸음 = %+v, want 틱 1(회차 스캔 닫힘 깨우기)", got)
	}
}

// R24 ④(Y1) — T 가 틱 0 에 일시 오류 재시도(retryAt = 4초)를 받은 뒤 틱 1 에 앞 사슬 S 종국 깨우기가 와도 T 를 4초 전에
// 읽지 않고(읽고 버리면 스캔 재방문 90초까지 잊힌다), 4초 틱에 읽어 걸음을 낸다 — 스캔 커서가 T 쪽에 돌아오기 전이다.
// 변이 M19(wakeFast 를 queueFast(now) 로) → T 를 틱 2 에 읽어 FAIL.
func TestWakeKeepsRetryReservation(t *testing.T) {
	w := newRoundWorld(3)
	w.place(0, roundRow("T", nil), roundRow("S", nil))
	tCalls := 0
	w.out = func(s index.EndingSession, d finalize.Decision, _ bool) finalStep {
		if s.SessionID == "S" {
			return w.doneWaking("S", wakeT)
		}
		tCalls++
		if tCalls == 1 {
			fo := publish.FreezeOutcome{Axis: publish.AxisVOD, Result: publish.ResultRetry, Retry: true, Reason: "db"}
			return finalStep{freeze: fo, out: outcomeOf(fo)}
		}
		return roundWaitStep(publish.ReasonFrontPending)
	}
	l := newRoundLoop(t, w, 30*time.Second)

	l.run(4) // 틱 0 T 재시도 · S 는 같은 스트림 상한으로 빠른 길 · 틱 1 S 종국 깨우기 · 틱 2–3 예약 전

	if s := w.stepsOf("S"); len(s) != 1 || s[0].tick != 1 {
		t.Fatalf("S 걸음 = %+v, want 틱 1 한 번(깨우기가 재시도 예약과 겹쳐야 시험이 선다)", s)
	}
	if got := w.loadsOf("T"); len(got) != 0 {
		t.Fatalf("T 를 재시도 예약(틱 4) 전에 읽었다: %+v", got)
	}
	l.step() // 틱 4

	if got := w.loadsOf("T"); len(got) != 1 || got[0].tick != 4 {
		t.Fatalf("T 행 읽기 = %+v, want 틱 4 한 번", got)
	}
	if got := w.stepsOf("T"); len(got) != 2 || got[1].tick != 4 {
		t.Fatalf("T 걸음 = %+v, want 틱 0 · 틱 4(재시도 예약을 지킴 — 스캔 재방문 틱 90 전)", got)
	}
}

// R24 ⑤ · ⑥ — 도는 T 걸음 중에 회차 스캔 닫힘 깨우기가 먼저 온다(틱 30). T 가 재시도로 끝나면 예약이 항목을 덮어
// 4초 뒤(틱 34)에 읽고, 정산 대기(Unsettled)로 끝나면 깨우기 항목이 남아 다음 틱(31)에 읽는다. 변이 M12 → ⑥ FAIL.
func TestWakeDuringRunningLastChainStep(t *testing.T) {
	tests := []struct {
		name     string
		first    finalStep
		wantTick int
	}{
		{name: "재시도로_끝남", first: func() finalStep {
			fo := publish.FreezeOutcome{Axis: publish.AxisVOD, Result: publish.ResultRetry, Retry: true, Reason: "db"}
			return finalStep{freeze: fo, out: outcomeOf(fo)}
		}(), wantTick: 34},
		{name: "정산_대기로_끝남", first: roundWaitStep(publish.ReasonRoundOpen), wantTick: 31},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			w := newRoundWorld(3)
			w.place(0, roundRow("T", nil))
			w.scanWake[1] = []roundWake{wakeT}
			tCalls := 0
			w.out = func(index.EndingSession, finalize.Decision, bool) finalStep {
				tCalls++
				if tCalls == 1 {
					return tt.first
				}
				return roundWaitStep(publish.ReasonFrontPending)
			}
			w.park("T")
			l := newRoundLoop(t, w, 30*time.Second)

			l.run(31) // 틱 0 T 걸음(섬) · 틱 30 스캔이 회차를 닫아 T 를 깨움
			l.release("T")
			l.run(tt.wantTick - 30)

			if got := w.loadsOf("T"); len(got) != 1 || got[0].tick != tt.wantTick {
				t.Fatalf("T 행 읽기 = %+v, want 틱 %d 한 번", got, tt.wantTick)
			}
			if got := w.stepsOf("T"); len(got) != 2 || got[1].tick != tt.wantTick {
				t.Fatalf("T 걸음 = %+v, want 틱 0 · 틱 %d", got, tt.wantTick)
			}
		})
	}
}

// R27 — 깨우기 질의가 실패하면 WARN finalize_round_wake_failed 한 줄 뒤 스캔에 맡긴다: T 는 깨우기 없이 스캔 커서가 T 쪽에
// 닿는 틱 60 에 걸음을 낸다(정지가 아니라 지연뿐).
func TestWakeQueryFailureFallsBackToScan(t *testing.T) {
	w := newRoundWorld(3)
	w.place(0, roundRow("S", nil))
	w.place(2, roundRow("T", nil))
	w.out = func(s index.EndingSession, _ finalize.Decision, _ bool) finalStep {
		res := finalStep{out: finalize.OutcomeDone, progressed: true}
		if s.SessionID == "S" {
			w.drop("S")
			res.wake, res.wakeErr = roundWake{stream: "demo", round: "R"}, errors.New("pool closed")
		}
		return res
	}
	l := newRoundLoop(t, w, 30*time.Second)

	l.run(61)

	if n := l.logs.countLevel(slog.LevelWarn, "finalize_round_wake_failed"); n != 1 {
		t.Fatalf("finalize_round_wake_failed WARN %d 줄, want 1", n)
	}
	a := l.logs.attrs("finalize_round_wake_failed")
	if a["session_id"] != "S" || a["round"] != "R" || fmt.Sprint(a["err"]) != "pool closed" {
		t.Fatalf("finalize_round_wake_failed 속성 = %v, want session_id=S round=R err=pool closed", a)
	}
	if got := w.loadsOf("T"); len(got) != 0 {
		t.Fatalf("깨우기가 실패했는데 T 를 빠른 길로 읽었다: %+v", got)
	}
	if got := w.stepsOf("T"); len(got) != 1 || got[0].tick != 60 {
		t.Fatalf("T 걸음 = %+v, want 스캔 재방문 틱 60 에 한 번", got)
	}
}

// R27(4D) — 4D 가 회차를 닫은 경우처럼 깨우기가 없으면, round_open 대기를 받은 T 는 다음 스캔(30초)에서 다시 판정받아 회차
// 경로로 간다 — 대기가 걸음을 멈추지 않는다.
func TestRoundWaitWithoutWakeResumesOnNextScan(t *testing.T) {
	w := newRoundWorld(1)
	w.place(0, roundRow("T", nil))
	tCalls := 0
	w.out = func(_ index.EndingSession, _ finalize.Decision, _ bool) finalStep {
		tCalls++
		if tCalls == 1 {
			return roundWaitStep(publish.ReasonRoundOpen)
		}
		return finalStep{out: finalize.OutcomeDone, progressed: true,
			freeze: publish.FreezeOutcome{Axis: publish.AxisVOD, Result: publish.ResultFrozen, Scope: publish.ScopeRound}}
	}
	l := newRoundLoop(t, w, 30*time.Second)

	l.run(31)

	got := w.stepsOf("T")
	if len(got) != 2 || got[0].tick != 0 || got[1].tick != 30 || got[1].chain {
		t.Fatalf("T 걸음 = %+v, want 틱 0(round_open) · 틱 30(회차 경로 — step)", got)
	}
	if rec := l.logs.last("session_freeze"); rec == nil || rec.attrs["scope"] != publish.ScopeRound {
		t.Fatalf("session_freeze = %+v, want scope=round", rec)
	}
}

// R25 · K22 — 회차 경로가 물러서면(Fallback) 그 세션을 기억해, 다음 걸음부터 회차 판정 없는 stepChain 을 부르고 물러섬
// WARN 은 세션당 한 번이다. 물러섬 없는 정산 대기(unsettled — 회차 경로 그대로)는 기억하지 않아 step 을 계속 부른다.
// 변이 M17(기억 제거) → 걸음이 모두 step · WARN 3 줄로 FAIL.
func TestRoundFallbackIsRememberedPerSession(t *testing.T) {
	cause := errors.New("범위 안 다른 회차 행")
	tests := []struct {
		name      string
		fallback  string
		wantChain []bool
		wantWarn  int
	}{
		{name: "물러섬", fallback: publish.FallbackRoundRender, wantChain: []bool{false, true, true}, wantWarn: 1},
		{name: "물러섬_없는_정산_대기", fallback: "", wantChain: []bool{false, false, false}, wantWarn: 0},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			w := newRoundWorld(1)
			w.place(0, roundRow("T", nil))
			w.out = func(_ index.EndingSession, _ finalize.Decision, chain bool) finalStep {
				fo := publish.FreezeOutcome{Axis: publish.AxisVOD, Result: publish.ResultRetry, Retry: true,
					Reason: publish.ReasonUnsettled, Scope: publish.ScopeChain}
				if !chain && tt.fallback != "" {
					fo.Fallback, fo.FallbackCause, fo.FallbackErr = tt.fallback, "foreign_session", cause
				}
				if !chain && tt.fallback == "" {
					fo.Scope = publish.ScopeRound
				}
				return finalStep{freeze: fo, out: outcomeOf(fo)}
			}
			l := newRoundLoop(t, w, time.Second)

			l.run(3)

			var chains []bool
			for _, s := range w.stepsOf("T") {
				chains = append(chains, s.chain)
			}
			if !slices.Equal(chains, tt.wantChain) {
				t.Fatalf("T 걸음의 stepChain 여부 = %v, want %v", chains, tt.wantChain)
			}
			if n := l.logs.countLevel(slog.LevelWarn, "session_vod_round_fallback"); n != tt.wantWarn {
				t.Fatalf("session_vod_round_fallback WARN %d 줄, want %d", n, tt.wantWarn)
			}
			if tt.wantWarn == 0 {
				return
			}
			a := l.logs.attrs("session_vod_round_fallback")
			if a["session_id"] != "T" || a["round"] != "R" || a["reason"] != tt.fallback || a["cause"] != "foreign_session" || a["err"] != cause {
				t.Fatalf("session_vod_round_fallback 속성 = %v", a)
			}
		})
	}
}

// R29 cmd · K2 — 장애 횟수 VOD 2 인 마지막 사슬에서 회차 경로가 물러서고 사슬 경로가 정산 대기(unsettled)로 끝나면 장애로
// 세지 않는다(2 유지 · 다음도 StepFreezeVOD · session_freeze_retry 없음) — 회차 원인은 Fallback* 칸에만 있다. 대조: 사슬 경로가
// 일시 오류(db)면 3 이 되어 다음은 StepFailVOD 다.
func TestRoundFallbackUnsettledDoesNotCountAttempts(t *testing.T) {
	tests := []struct {
		name     string
		freeze   publish.FreezeOutcome
		wantVOD  int
		wantNext finalize.Step
		wantWarn int
	}{
		{name: "물러섬_정산_대기", freeze: publish.FreezeOutcome{Axis: publish.AxisVOD, Result: publish.ResultRetry, Retry: true,
			Reason: publish.ReasonUnsettled, Scope: publish.ScopeChain, Fallback: publish.FallbackRoundRender,
			FallbackCause: "foreign_session", FallbackErr: errors.New("foreign")},
			wantVOD: 2, wantNext: finalize.StepFreezeVOD, wantWarn: 0},
		{name: "사슬_경로_일시_오류", freeze: publish.FreezeOutcome{Axis: publish.AxisVOD, Result: publish.ResultRetry, Retry: true,
			Reason: "db", Scope: publish.ScopeChain},
			wantVOD: 3, wantNext: finalize.StepFailVOD, wantWarn: 1},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			w := newRoundWorld(1)
			row := roundRow("T", nil)
			w.place(0, row)
			w.out = func(index.EndingSession, finalize.Decision, bool) finalStep {
				return finalStep{freeze: tt.freeze, out: outcomeOf(tt.freeze)}
			}
			l := newRoundLoop(t, w, time.Second)
			l.rewind.fin.attempts["T"] = finalize.Attempts{VOD: 2}

			l.step()

			a := l.rewind.fin.attempts["T"]
			if a.VOD != tt.wantVOD {
				t.Fatalf("장애 횟수 VOD = %d, want %d", a.VOD, tt.wantVOD)
			}
			if d := finalize.Next(row, a, l.rewind.fin.opt); d.Step != tt.wantNext {
				t.Fatalf("다음 판정 = %v, want %v", d.Step, tt.wantNext)
			}
			if n := l.logs.count("session_freeze_retry"); n != tt.wantWarn {
				t.Fatalf("session_freeze_retry %d 줄, want %d", n, tt.wantWarn)
			}
		})
	}
}

// 오래 기다림 경보 — 회차 대기가 row.Now − row.EndingAt > 2 × 정산 창이면 세션당 한 번 WARN(wait = 대기 사유). 경계(정확히
// 2 × 정산 창)는 내지 않고, forget 뒤에는 다시 낼 수 있다. 변이 M20(신호 블록을 Unsettled 조기 반환 뒤로) → 0 줄로 FAIL.
func TestRoundWaitLongWarnsOncePerSession(t *testing.T) {
	tests := []struct {
		name   string
		age    time.Duration
		reason string
		want   int
	}{
		{name: "경계", age: 2 * index.SettleWindow, reason: publish.ReasonRoundOpen, want: 0},
		{name: "round_open", age: 2*index.SettleWindow + time.Second, reason: publish.ReasonRoundOpen, want: 1},
		{name: "front_pending", age: 2*index.SettleWindow + time.Second, reason: publish.ReasonFrontPending, want: 1},
		{name: "정산_대기는_아님", age: 2*index.SettleWindow + time.Second, reason: publish.ReasonUnsettled, want: 0},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			w := newRoundWorld(1)
			w.place(0, roundRow("T", func(s *index.EndingSession) { s.EndingAt = s.Now.Add(-tt.age) }))
			w.out = func(index.EndingSession, finalize.Decision, bool) finalStep { return roundWaitStep(tt.reason) }
			l := newRoundLoop(t, w, time.Second)

			l.run(3)

			if n := l.logs.countLevel(slog.LevelWarn, "session_vod_round_wait_long"); n != tt.want {
				t.Fatalf("session_vod_round_wait_long WARN %d 줄(걸음 %d), want %d", n, len(w.stepsOf("T")), tt.want)
			}
			if tt.want == 0 {
				return
			}
			if a := l.logs.attrs("session_vod_round_wait_long"); a["session_id"] != "T" || a["round"] != "R" || a["wait"] != tt.reason {
				t.Fatalf("session_vod_round_wait_long 속성 = %v, want session_id=T round=R wait=%s", a, tt.reason)
			}
			l.rewind.fin.forget("T")
			l.step()
			if n := l.logs.countLevel(slog.LevelWarn, "session_vod_round_wait_long"); n != 2 {
				t.Fatalf("forget 뒤 session_vod_round_wait_long %d 줄, want 2(기억을 지웠다)", n)
			}
		})
	}
}

// R19 · K26 — 정산 포기 경보(session_settle_abandoned)는 회차 경로에서 마지막 사슬 범위에 GAP 줄이 있을 때만 낸다(앞 사슬
// GAP 은 앞 사슬 동결 때 이미 냈다). 사슬 경로는 지금 조건 그대로다. session_freeze 에는 scope 를 싣는다.
func TestSettleAbandonedFollowsLastChainGaps(t *testing.T) {
	tests := []struct {
		name     string
		scope    string
		lastGaps bool
		want     int
	}{
		{name: "회차_앞_사슬만_GAP", scope: publish.ScopeRound, lastGaps: false, want: 0},
		{name: "회차_마지막_사슬_GAP", scope: publish.ScopeRound, lastGaps: true, want: 1},
		{name: "사슬_경로", scope: publish.ScopeChain, lastGaps: false, want: 1},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			l := newRoundLoop(t, newRoundWorld(1), time.Second)
			out := finalStep{row: roundRow("T", nil), d: finalize.Decision{Step: finalize.StepFreezeVOD, Abandon: true},
				out: finalize.OutcomeDone, progressed: true, freeze: publish.FreezeOutcome{Axis: publish.AxisVOD,
					Result: publish.ResultFrozenWithGaps, Scope: tt.scope, LastChainHasGaps: tt.lastGaps}}

			l.rewind.noteStep(out, finalize.Attempts{})

			if n := l.logs.countLevel(slog.LevelWarn, "session_settle_abandoned"); n != tt.want {
				t.Fatalf("session_settle_abandoned WARN %d 줄, want %d", n, tt.want)
			}
			if rec := l.logs.last("session_freeze"); rec == nil || rec.attrs["scope"] != tt.scope {
				t.Fatalf("session_freeze = %+v, want scope=%s", rec, tt.scope)
			}
		})
	}
}

// cc C5 L2 — session_freeze 의 scope 는 범위를 정한 결과(VOD)에만 싣는다 — DVR 걸음 · 판정 전에 끝난 결과(Scope 빈 값)에는
// 그 속성이 없다. 고치기 전(6fcef57b)은 빈 scope 를 실어 FAIL.
func TestSessionFreezeOmitsEmptyScope(t *testing.T) {
	tests := []struct {
		name   string
		step   finalize.Step
		freeze publish.FreezeOutcome
	}{
		{name: "DVR_섬", step: finalize.StepFreezeDVR, freeze: publish.FreezeOutcome{Axis: publish.AxisDVR, Result: publish.ResultFrozen}},
		{name: "VOD_판정_전_실패", step: finalize.StepFreezeVOD,
			freeze: publish.FreezeOutcome{Axis: publish.AxisVOD, Result: publish.ResultFailed, Reason: "chain_changed"}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			l := newRoundLoop(t, newRoundWorld(1), time.Second)

			l.rewind.noteStep(finalStep{row: roundRow("T", nil), d: finalize.Decision{Step: tt.step}, out: finalize.OutcomeDone,
				progressed: true, freeze: tt.freeze}, finalize.Attempts{})

			rec := l.logs.last("session_freeze")
			if rec == nil || rec.attrs["result"] != tt.freeze.Result {
				t.Fatalf("session_freeze = %+v, want result=%s", rec, tt.freeze.Result)
			}
			if v, ok := rec.attrs["scope"]; ok {
				t.Fatalf("session_freeze scope = %q, want 속성 없음(범위를 정하지 않은 결과)", v)
			}
		})
	}
}

// P4 · M24 — ended(phase 4 섰음) 뒤 forget 이 물러섬 기억 · 오래 기다림 기억도 지운다(장기 실행 맵이 자라지 않는다).
func TestForgetClearsRoundMemory(t *testing.T) {
	w := newRoundWorld(1)
	w.place(0, roundRow("T", func(s *index.EndingSession) { s.VODState = "frozen" }))
	l := newRoundLoop(t, w, time.Second)
	fin := l.rewind.fin
	fin.vodChainOnly["T"], fin.waitWarned["T"] = publish.FallbackRoundRender, true

	l.step()

	if s := w.stepsOf("T"); len(s) != 1 || s[0].d.Step != finalize.StepMarkEnded {
		t.Fatalf("T 걸음 = %+v, want StepMarkEnded 한 번", s)
	}
	if _, ok := fin.vodChainOnly["T"]; ok {
		t.Error("ended 뒤 vodChainOnly 에 T 가 남았다")
	}
	if fin.waitWarned["T"] {
		t.Error("ended 뒤 waitWarned 에 T 가 남았다")
	}
}

// P3 · M23 — 운영 조립은 물러섬 뒤 차선 작업(stepChain)까지 채운다 — 비면 물러선 세션의 다음 걸음이 nil 호출로 멈춘다.
func TestFinalizeWorkAssemblesStepChain(t *testing.T) {
	w := newFinalizeWork(nil, nil, index.CloseOptions{}, noEndedEnqueue)
	if w.step == nil || w.stepChain == nil {
		t.Fatalf("운영 조립 step nil=%v · stepChain nil=%v, want 둘 다 채움", w.step == nil, w.stepChain == nil)
	}
}

// 깨우기 1 · 2 대상(실 장부) — 회차 R 의 앞 사슬 A 가 VOD 종국 문장(FailVOD · 빈 동결)을 세우거나 phase 4 를 세우면 회차
// 최신 ending 세션 T 를 싣는다. 걸음 세션이 곧 최신이면 대상이 없고(round 만), 문장이 서지 않으면 묻지 않는다. 회차 없는
// 옛 세션(M6 전)은 묻지 않는다(K14).
func TestRunFinalStepCarriesRoundWake(t *testing.T) {
	tests := []struct {
		name     string
		id       string
		vod, dvr string
		round    string
		step     finalize.Step
		want     roundWake
	}{
		{name: "앞_사슬_FailVOD", id: "A", vod: "freezing", dvr: "frozen", round: "R", step: finalize.StepFailVOD, want: wakeT},
		{name: "앞_사슬_빈_동결", id: "A", vod: "freezing", dvr: "frozen", round: "R", step: finalize.StepEmptyFreezeVOD, want: wakeT},
		{name: "앞_사슬_phase4", id: "A", vod: "freeze_failed", dvr: "frozen", round: "R", step: finalize.StepMarkEnded, want: wakeT},
		{name: "문장이_서지_않음", id: "A", vod: "freeze_failed", dvr: "frozen", round: "R", step: finalize.StepFailVOD},
		{name: "걸음_세션이_최신", id: "T", vod: "freezing", dvr: "frozen", round: "R", step: finalize.StepFailVOD,
			want: roundWake{stream: "demo", round: "R"}},
		{name: "회차_없음(K14)", id: "A", vod: "freezing", dvr: "frozen", round: "", step: finalize.StepFailVOD},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			pool := newWireTestPool(t)
			old := time.Now().Add(-time.Hour)
			execSQL(t, pool, `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, end_reason, ending_at, round_id, init_uploaded_at,
                             dvr_state, vod_state)
VALUES ('A', 'demo', $1, 'ending', 'intentional', $1, NULLIF($3::text, ''), $1, $4, $5),
       ('T', 'demo', $2, 'ending', 'intentional', $2, NULLIF($3::text, ''), $2, 'frozen', 'freezing')`,
				old, old.Add(time.Minute), tt.round, tt.dvr, tt.vod)
			deps := finalDeps{pool: pool, close: restartClose, enqueue: noEndedEnqueue}
			s := index.EndingSession{SessionID: tt.id, StreamID: "demo", RoundID: tt.round}

			res := runFinalStep(t.Context(), deps, s, finalize.Decision{Step: tt.step})

			if res.err != nil || res.wakeErr != nil {
				t.Fatalf("걸음 오류 %v · 깨우기 오류 %v", res.err, res.wakeErr)
			}
			if res.wake != tt.want {
				t.Fatalf("깨우기 = %+v, want %+v", res.wake, tt.want)
			}
		})
	}
}

// 깨우기 1(실 장부 · 발행자) — 앞 사슬 A(행 없음)의 VOD 동결이 서면(빈 동결) T 를 깨운다. 물러섬 기억 차선(stepChain)도 같다.
func TestFreezeVODDoneCarriesRoundWake(t *testing.T) {
	pool := newWireTestPool(t)
	old := time.Now().Add(-time.Hour)
	execSQL(t, pool, `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, end_reason, ending_at, round_id, init_uploaded_at,
                             dvr_state, vod_state, round_closed_at)
VALUES ('A', 'demo', $1, 'ending', 'intentional', $1, 'R', $1, 'frozen', 'freezing', $1),
       ('B', 'demo', $1, 'ending', 'intentional', $1, 'R', $1, 'frozen', 'freezing', $1),
       ('T', 'demo', $2, 'ending', 'intentional', $2, 'R', $2, 'frozen', 'freezing', $2)`, old, old.Add(time.Minute))
	w := newFinalizeWork(pool, newLedgerPublisher(t, pool), restartClose, noEndedEnqueue)
	d := finalize.Decision{Step: finalize.StepFreezeVOD}

	for _, c := range []struct {
		id   string
		step func(context.Context, index.EndingSession, finalize.Decision) finalStep
	}{{"A", w.step}, {"B", w.stepChain}} {
		res := c.step(t.Context(), index.EndingSession{SessionID: c.id, StreamID: "demo", RoundID: "R"}, d)
		if res.out != finalize.OutcomeDone || res.wake != wakeT || res.wakeErr != nil {
			t.Fatalf("%s 동결 = (%v, %+v, 깨우기 %+v · 오류 %v), want Done · 깨우기 T", c.id, res.out, res.freeze, res.wake, res.wakeErr)
		}
	}
}

// P3 판별 — stepChain 은 회차 판정 없이 사슬 경로로 간다: 열린 회차에 행 있는 앞 사슬 A 가 있으면 step(FreezeVOD)은 회차
// 대기(round_open)인데 stepChain(FreezeChainVOD)은 사슬 범위로 동결한다. 변이(stepChain 이 FreezeVOD 를 부름) → FAIL.
func TestStepChainSkipsRoundGate(t *testing.T) {
	pool := newWireTestPool(t)
	old := time.Now().Add(-time.Hour)
	execSQL(t, pool, `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, end_reason, ending_at, round_id, init_uploaded_at,
                             dvr_state, vod_state)
VALUES ('A', 'demo', $1, 'ending', 'intentional', $1, 'R', $1, 'frozen', 'freezing'),
       ('T', 'demo', $2, 'ending', 'intentional', $2, 'R', $2, 'frozen', 'freezing')`, old, old.Add(time.Minute))
	execSQL(t, pool, `
INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, upload_state, session_id)
VALUES ('demo', 1, 0, $1, 4000, 'k1', 'uploaded', 'A')`, old)
	w := newFinalizeWork(pool, newLedgerPublisher(t, pool), restartClose, noEndedEnqueue)
	s, d := index.EndingSession{SessionID: "T", StreamID: "demo", RoundID: "R"}, finalize.Decision{Step: finalize.StepFreezeVOD}

	if res := w.step(t.Context(), s, d); res.freeze.Reason != publish.ReasonRoundOpen {
		t.Fatalf("step 동결 = %+v, want 회차 대기 round_open(시험 전제)", res.freeze)
	}
	if res := w.stepChain(t.Context(), s, d); res.freeze.Scope != publish.ScopeChain || res.freeze.Reason == publish.ReasonRoundOpen {
		t.Fatalf("stepChain 동결 = %+v, want 사슬 범위(회차 판정 없음)", res.freeze)
	}
}

// 깨우기 3(실 장부) — 회차 스캔이 회차를 닫으면 최신 세션이 ending 일 때만 그 세션을 깨우기 대상으로 싣는다.
func TestScanFinalizeWakesClosedRoundLatest(t *testing.T) {
	tests := []struct {
		name   string
		latest string
		want   []roundWake
	}{
		{name: "최신_ending", latest: "ending", want: []roundWake{wakeT}},
		{name: "최신_ended", latest: "ended", want: nil},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			pool := newWireTestPool(t)
			old := time.Now().Add(-time.Hour)
			execSQL(t, pool, `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, end_reason, ending_at, ended_at, round_id,
                             init_uploaded_at, dvr_state, vod_state)
VALUES ('A', 'demo', $1, 'ending', 'intentional', $1, NULL, 'R', $1, 'frozen', 'freezing'),
       ('T', 'demo', $2, $3::text, 'intentional', $2, CASE WHEN $3::text = 'ended' THEN $2::timestamptz END, 'R', $2, 'frozen',
        CASE WHEN $3::text = 'ended' THEN 'freeze_failed' ELSE 'freezing' END)`, old, old.Add(time.Minute), tt.latest)

			out := scanFinalize(t.Context(), pool, index.ScanCursor{}, restartClose, noEndedEnqueue)

			if out.err != nil || !slices.Equal(out.closed, []string{"demo"}) {
				t.Fatalf("스캔 = (닫음 %v, 오류 %v), want 회차 R 닫음", out.closed, out.err)
			}
			if !slices.Equal(out.wake, tt.want) {
				t.Fatalf("깨우기 = %+v, want %+v", out.wake, tt.want)
			}
		})
	}
}
