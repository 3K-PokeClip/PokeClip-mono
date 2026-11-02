package main

// live 회차 종료 걸음(L3 · L2′)의 실패 처치(POK-196 M6-3b 커밋 ④b — ④ 검수 db M1 · M2 · cc L1). 종료 문장이 실패하면 결과를
// 모른다(시한 직후 · COMMIT 오류 뒤 실제 커밋) — 오프라인 전이의 offlineFailed 와 같이 그 스트림에 적재를 요구하고, 30초
// 동안 어느 스트림도 다시 부르지 않는다. 루프 ctx 가 끝나 난 실패는 아무것도 남기지 않는다.

import (
	"context"
	"errors"
	"log/slog"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/finalize"
)

// failing 은 n 번 연달아 실패하는 종료 문장 가짜다.
func failing(n int) *gapRunCalls {
	g := &gapRunCalls{}
	for range n {
		g.errs = append(g.errs, errors.New("statement_timeout"))
	}
	return g
}

// DB 가 멈춰 종료 문장이 실패하면, 한 틱에 첫 실패 하나로 멈추고 30초 동안 어느 스트림 · 어느 걸음도 다시 부르지 않는다 —
// 스트림마다 따로 쉬면 문턱에 닿은 스트림 수만큼 시한(약 2 × TxnDeadline)이 한 틱에 쌓인다(db M2). 변이 = 첫 실패 뒤 멈춤
// 제거 · 쉼을 스트림별로 · L2′ 걸음이 공용 쉼을 보지 않음 → 각각 FAIL.
func TestLiveEndFailurePausesEveryStreamAndStep(t *testing.T) {
	tests := []struct {
		name    string
		gapRun  []string // L3 셈이 문턱에 닿은 스트림
		sealed  []string // 봉인이 선 스트림
		wantMsg string
	}{
		{"L3_셋", []string{"a", "b", "c"}, nil, "gap_run_end_failed"},
		{"L2′_셋", nil, []string{"a", "b", "c"}, "stall_seal_end_failed"},
		{"L3_실패가_L2′_도_멈춤", []string{"a"}, []string{"b", "c"}, "gap_run_end_failed"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f := newFinalLoop(t, finalize.ModePublishing)
			g := failing(10)
			f.rewind.endGapRun, f.rewind.endStallSeal = g.end, g.end
			for _, s := range tt.gapRun {
				for k := int64(10); k < 13; k++ {
					stoodGap(f.rewind, s, "S-"+s, k)
				}
			}
			for _, s := range tt.sealed {
				f.rewind.stream(s).stallSeal = stallSeal{sealed: "S-" + s}
			}
			for _, d := range []time.Duration{0, time.Second, 29 * time.Second} {
				f.hold(loopBase.Add(d))
				f.settle(loopBase.Add(d))
			}
			if len(g.calls) != 1 {
				t.Fatalf("30초 안 종료 시도 = %v, want 한 번(첫 실패 뒤 루프 전체가 쉰다)", g.calls)
			}
			if n := f.logs.countLevel(slog.LevelError, tt.wantMsg); n != 1 {
				t.Errorf("%s ERROR = %d줄, want 1", tt.wantMsg, n)
			}
			f.hold(loopBase.Add(30 * time.Second))
			f.settle(loopBase.Add(30 * time.Second))
			if len(g.calls) != 2 {
				t.Fatalf("30초 뒤 종료 시도 = %v, want 두 번째 하나", g.calls)
			}
		})
	}
}

// 결과 모름 — 종료 문장이 실패했지만 서버는 커밋했다(장부의 회차는 ending). 걸음은 그 스트림에 적재를 요구해 같은 틱의
// 적재가 캐시를 ending 으로 맞춘다 — 재시도가 「이미 live 아님」 빈 값으로 끝나도 캐시가 live 에 머물지 않는다(db M1).
// 변이 = 실패 갈래의 적재 요구 제거 → FAIL.
func TestLiveEndUnknownOutcomeDemandsLoad(t *testing.T) {
	for _, step := range []string{"L3", "L2′"} {
		t.Run(step, func(t *testing.T) {
			f := newPublishLoop(t)
			f.put("s1", liveLedger("s1", "S", 3, 3))
			committed := liveLedger("s1", "S", 3, 3)
			committed.Sessions[0].State = "ending"
			f.work.ledgers["s1"] = committed
			g := failing(1)
			if step == "L3" {
				f.rewind.endGapRun = g.end
				for k := int64(4); k < 7; k++ {
					stoodGap(f.rewind, "s1", "S", k)
				}
			} else {
				f.rewind.endStallSeal = g.end
				f.rewind.stream("s1").stallSeal = stallSeal{sealed: "S"}
			}

			f.at(time.Second)

			if len(g.calls) != 1 {
				t.Fatalf("종료 시도 = %v, want 한 번", g.calls)
			}
			if owner, ok := f.cache.Owner("s1"); !ok || owner.State != "ending" {
				t.Fatalf("캐시 소유 회차 = %+v (%v), want ending — 결과 모름 뒤 적재를 요구하지 않았다", owner.RewindSession, ok)
			}
		})
	}
}

// 루프가 끝나는 중(ctx 취소)에 난 실패는 DB 의 상태를 말하지 않는다 — ERROR · 쉼 · 적재 요구를 남기지 않는다(오프라인 전이
// offlineFailed 와 같다 · cc L1 · db L1). 변이 = ctx 확인 제거 → FAIL.
func TestLiveEndFailureSilentWhenLoopStopping(t *testing.T) {
	f := newFinalLoop(t, finalize.ModePublishing)
	g := failing(1)
	f.rewind.endGapRun = g.end
	for k := int64(10); k < 13; k++ {
		stoodGap(f.rewind, "demo", "S1", k)
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()

	f.rewind.holdTick(ctx, loopBase)

	if len(g.calls) != 1 {
		t.Fatalf("종료 시도 = %v, want 한 번", g.calls)
	}
	if n := f.logs.count("gap_run_end_failed"); n != 0 {
		t.Errorf("gap_run_end_failed = %d줄, want 0(종료 중 침묵)", n)
	}
	if !f.rewind.liveEndRetryAt.IsZero() {
		t.Errorf("쉼 = %v, want 없음", f.rewind.liveEndRetryAt)
	}
}
