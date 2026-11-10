package main

// 바퀴 끝 기억 정리(pruneUnseen)와 늦게 반영된 스캔 결과(POK-549 PR 단계 · cx C5 M). 신구조 칸(vodChainOnly · waitWarned)을
// 보는 시험이라 변이로 교정한다. finalize_round_test.go 의 가짜(roundWorld · roundLoop)를 그대로 쓰고 스캔 차선 작업만 보류
// 문으로 감싼다.

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/finalize"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
)

// cx C5 M — 스캔 한 바퀴(쪽 하나 · T 없음)가 도는 사이 빠른 길의 T 걸음이 물러섬 기억(vodChainOnly)이나 오래 기다림
// 기억(waitWarned)을 남기면, 그 뒤에 반영된 스캔 결과의 바퀴 끝은 T 의 기억을 지우지 않는다 — 걸음 결과가 T 를 그 바퀴에
// 본 것으로 센다. T 걸음은 틱 1 에 나서 둘째 바퀴 끝(틱 30)을 넘겨 돌고 셋째 바퀴 스캔(틱 60) 결과를 보류한 사이에 끝난다 —
// 행 읽기가 아니라 걸음 결과만이 셋째 바퀴의 생존 근거다. T 를 다시 보지 않는 바퀴가 한 번 더 끝나면 지운다(맵이 자라지
// 않는다). 고치기 전(6fcef57b)은 보류한 스캔 결과가 기억을 지워 FAIL.
func TestStaleScanKeepsMemoryMadeDuringScan(t *testing.T) {
	fallback := publish.FreezeOutcome{Axis: publish.AxisVOD, Result: publish.ResultRetry, Retry: true,
		Reason: publish.ReasonUnsettled, Scope: publish.ScopeChain, Fallback: publish.FallbackRoundRender}
	tests := []struct {
		name string
		out  finalStep
		has  func(*finalizer) bool
	}{
		{name: "물러섬_기억", out: finalStep{freeze: fallback, out: outcomeOf(fallback)},
			has: func(f *finalizer) bool { _, ok := f.vodChainOnly["T"]; return ok }},
		{name: "오래_기다림_기억", out: roundWaitStep(publish.ReasonRoundOpen),
			has: func(f *finalizer) bool { return f.waitWarned["T"] }},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			w := newRoundWorld(1)
			// T 는 스캔 쪽에 없고 빠른 길 행 읽기에만 있다 — 스캔이 T 를 제외한 것과 같다.
			w.rows["T"] = roundRow("T", func(s *index.EndingSession) { s.EndingAt = s.Now.Add(-2*index.SettleWindow - time.Second) })
			w.scanWake[0] = []roundWake{wakeT}
			w.out = func(index.EndingSession, finalize.Decision, bool) finalStep { return tt.out }
			w.park("T")
			l := newRoundLoop(t, w, 30*time.Second)
			fin := l.rewind.fin
			gate, scan := make(chan struct{}), fin.work.scan
			var calls atomic.Int32
			fin.work.scan = func(ctx context.Context, after index.ScanCursor) finalScan {
				if calls.Add(1) == 3 {
					select {
					case <-gate:
					case <-ctx.Done():
					}
				}
				return scan(ctx, after)
			}

			l.run(60) // 틱 0 첫 바퀴가 T 를 깨움 · 틱 1 T 걸음(섬) · 틱 30 둘째 바퀴 끝(T 는 도는 걸음이라 남는다)

			// 틱 60 — 셋째 바퀴 스캔을 낸 뒤 결과를 보류한 채 T 걸음을 끝낸다(기억이 스캔 시작 뒤에 생긴다).
			now := l.now()
			l.hold(now)
			if !fin.scanning {
				t.Fatal("틱 60 에 셋째 바퀴 스캔이 돌지 않는다(시험 전제)")
			}
			w.release("T")
			for _, run := fin.running["T"]; run; _, run = fin.running["T"] {
				l.next(now)
			}
			if !tt.has(fin) {
				t.Fatal("T 걸음 결과가 기억을 남기지 않았다(시험 전제)")
			}
			close(gate)
			l.settleUnparked(now)
			l.tick++

			if !tt.has(fin) {
				t.Fatal("보류했던 스캔 결과의 바퀴 끝이 그 스캔 시작 뒤에 생긴 T 의 기억을 지웠다")
			}
			if _, ok := fin.attempts["T"]; !ok {
				t.Error("보류했던 스캔 결과의 바퀴 끝이 T 의 장애 횟수 기억을 지웠다")
			}

			l.run(31) // 틱 61–91 — 틱 90 넷째 바퀴도 T 를 보지 않는다

			if tt.has(fin) {
				t.Error("T 를 보지도 걸음을 내지도 않은 바퀴가 끝났는데 기억이 남았다")
			}
			if _, ok := fin.attempts["T"]; ok {
				t.Error("T 를 보지도 걸음을 내지도 않은 바퀴가 끝났는데 장애 횟수 기억이 남았다")
			}
		})
	}
}

// cx fix1 L1 — 스캔 결과에 오류가 이어져 바퀴 끝(pruneUnseen)이 오지 않는 동안 빠른 길에서 ended 로 끝난(StepMarkEnded 섬)
// 세션은 forget 이 생존 표시(seen)까지 지운다 — 끝난 세션 id 가 seen 에 쌓이지 않는다. 고치기 전(5456416f)은 걸음 결과가 적은
// seen 이 남아 FAIL.
func TestForgetClearsSeenWhileScanFails(t *testing.T) {
	w := newRoundWorld(1)
	// T 는 스캔 쪽에 없고 빠른 길 행 읽기에만 있다 — VOD 종국이라 다음 걸음은 StepMarkEnded 다.
	w.rows["T"] = roundRow("T", func(s *index.EndingSession) { s.VODState = "frozen" })
	w.scanWake[0] = []roundWake{wakeT}
	l := newRoundLoop(t, w, time.Second)
	fin := l.rewind.fin
	scan := fin.work.scan
	fin.work.scan = func(ctx context.Context, after index.ScanCursor) finalScan {
		out := scan(ctx, after)
		out.err = errors.New("다른 회차 CloseRound 실패")
		return out
	}

	l.run(3) // 틱 0 오류 바퀴가 T 를 깨움 · 틱 1 T 걸음 StepMarkEnded(섬) · 틱 2 도 오류 바퀴

	if s := w.stepsOf("T"); len(s) != 1 || s[0].d.Step != finalize.StepMarkEnded {
		t.Fatalf("T 걸음 = %+v, want StepMarkEnded 한 번(시험 전제)", s)
	}
	if fin.seen["T"] {
		t.Error("ended 로 끝난 T 가 seen 에 남았다 — 오류 바퀴가 이어지면 끝난 세션 id 가 쌓인다")
	}
	if _, ok := fin.attempts["T"]; ok {
		t.Error("ended 로 끝난 T 의 장애 횟수 기억이 남았다")
	}
}
