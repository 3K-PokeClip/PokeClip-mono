package main

// 회차 대기를 장애로 세지 않는다는 음성 대조 시험이다(POK-549 C3 · 설계 r4 10.5 R21 · 계획 K1). 기존 기호(outcomeOf ·
// finalizeWork.step · finalStep 칸 · FreezeOutcome 칸)만 쓰고 대기 사유는 문자열 리터럴이다 — 제품 코드를 기준(a9c39331)으로
// 되돌린 트리에서도 컴파일되고, 거기서는 회차 대기가 OutcomeRetry 로 세어져 단언에서 FAIL 한다.

import (
	"context"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/finalize"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
)

// round_wait_is_unsettled(R21 ①) — 회차 대기 재시도(front_pending · round_open)는 정산 대기(OutcomeUnsettled)다. 장애 재시도
// (db)는 그대로 OutcomeRetry 다.
func TestOutcomeOfRoundWaitIsUnsettled(t *testing.T) {
	tests := []struct {
		reason string
		want   finalize.Outcome
	}{
		{reason: "front_pending", want: finalize.OutcomeUnsettled},
		{reason: "round_open", want: finalize.OutcomeUnsettled},
		{reason: "unsettled", want: finalize.OutcomeUnsettled},
		{reason: "db", want: finalize.OutcomeRetry},
	}
	for _, tt := range tests {
		o := publish.FreezeOutcome{Axis: publish.AxisVOD, Result: publish.ResultRetry, Retry: true, Reason: tt.reason}
		if got := outcomeOf(o); got != tt.want {
			t.Errorf("outcomeOf(Retry %s) = %v, want %v", tt.reason, got, tt.want)
		}
	}
}

// round_wait_never_fails_vod(R21 ②) — 마지막 사슬 VOD 동결이 회차 대기를 MaxAttempts 보다 많이 내도 장애 횟수는 0 이고 다음
// 판정은 그대로 StepFreezeVOD 다(StepFailVOD 로 가 회차 VOD 를 통째로 잃지 않는다). session_freeze_retry 도 남지 않는다.
// 기준 트리는 StepFreezeVOD 세 번(MaxAttempts) 뒤 네 번째 걸음에서 StepFailVOD 로 간다.
func TestRoundWaitDoesNotCountVODAttempts(t *testing.T) {
	f := newFinalLoop(t, finalize.ModePublishing)
	row := endingRow("S1", func(s *index.EndingSession) { s.DVRState, s.VODState = "frozen", "freezing" })
	f.work.scanRows = []index.EndingSession{row}
	f.work.rows["S1"] = row
	var steps []finalize.Step
	f.rewind.fin.work.step = func(_ context.Context, s index.EndingSession, d finalize.Decision) finalStep {
		steps = append(steps, d.Step)
		fo := publish.FreezeOutcome{Axis: publish.AxisVOD, Result: publish.ResultRetry, Retry: true, Reason: "front_pending"}
		return finalStep{row: s, d: d, freeze: fo, out: outcomeOf(fo)}
	}
	maxAttempts := f.rewind.fin.opt.MaxAttempts
	now := loopBase
	for len(steps) <= maxAttempts {
		f.hold(now)
		f.settle(now)
		now = now.Add(time.Second)
		if now.Sub(loopBase) > time.Minute {
			t.Fatalf("1분 동안 걸음 %v — MaxAttempts+1(%d) 번에 못 닿았다", steps, maxAttempts+1)
		}
	}

	for i, s := range steps {
		if s != finalize.StepFreezeVOD {
			t.Fatalf("걸음 %d = %v(전체 %v), want 모두 StepFreezeVOD", i, s, steps)
		}
	}
	if a := f.rewind.fin.attempts["S1"]; a.VOD != 0 {
		t.Errorf("장애 횟수 VOD = %d, want 0", a.VOD)
	}
	if d := finalize.Next(row, f.rewind.fin.attempts["S1"], f.rewind.fin.opt); d.Step != finalize.StepFreezeVOD {
		t.Errorf("다음 판정 = %v, want StepFreezeVOD", d.Step)
	}
	if rec := f.logs.last("session_freeze_retry"); rec != nil {
		t.Errorf("session_freeze_retry = %+v, want 없음(회차 대기는 장애가 아니다)", rec)
	}
}
