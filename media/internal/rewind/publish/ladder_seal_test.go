package publish

// 정체 사다리 L2′(정체 마감) 판정(POK-196 M6-3b 커밋 ④b — M4 r17 4.6.3 「stallAge ≥ D_act ∧ (GAP 부적격 ∨ 직전 틱 실패) ∧
// fence 유효 → E0–E5」 · kty 2026-10-27 결정 (나) — 봉인과 함께 회차를 ending 으로). fence 유효는 봉인 경로(Endlist 의 E0)가
// 본다. 판정은 입력 값만 본다.

import (
	"testing"
	"time"
)

// L2′ — 발행 이력이 있는 live 소유 회차의 stallAge 가 D_act(StallSealAfter)에 닿았는데 L2 가 설 수 없으면(머리 다음 행이
// 없다 · 부적격 · 직전 GAP 틱이 서지 않음) 봉인을 낸다. 행이 곧 적격이 되는 holding 이면 L2 를 기다린다. 스위치가 꺼졌으면
// (StallSealAfter 0) 내지 않는다. 변이 = 문턱 비교 제거 · holding 제외 제거 · 발행 이력 조건 제거 → 각각 FAIL.
func TestLadderSealsStalledOwnerL2Prime(t *testing.T) {
	const dAct = 5500 * time.Millisecond
	late := dueOf6.Add(time.Minute)  // 행 6 의 holdAge 가 GAP_HOLD 를 넘었다
	early := dueOf6.Add(time.Second) // 행 6 은 holding(holdAge 1초)
	tests := []struct {
		name       string
		after      time.Duration // StallSealAfter
		now        time.Time
		head       int64 // 사다리가 보는 행은 head + 1
		stall      time.Duration
		history    bool
		unstood    bool
		incomplete bool
		seal, gap  bool
	}{
		{"행_없음_문턱", dAct, late, 10, dAct, true, false, false, true, false},
		{"행_없음_문턱_앞", dAct, late, 10, dAct - time.Millisecond, true, false, false, false, false},
		{"스위치_끔", 0, late, 10, time.Minute, true, false, false, false, false},
		{"발행_이력_없음", dAct, late, 10, time.Minute, false, false, false, false, false},
		{"holding_은_L2_를_기다림", dAct, early, 5, time.Minute, true, false, false, false, false},
		{"적격_GAP_이_먼저", dAct, late, 5, time.Minute, true, false, false, false, true},
		{"직전_GAP_틱이_서지_않음", dAct, late, 5, time.Minute, true, true, false, true, false},
		{"부적격_행", dAct, late, 5, time.Minute, true, false, true, true, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			pub := newLadderPublisher(&logRecorder{})
			pub.opt.StallSealAfter = tt.after
			snap := stalledRows()
			if tt.incomplete {
				snap.rows[6].PlaybackS3Key = ""
			}
			in := ladderInput(snap, tt.now)
			in.Window.HeadSeq = tt.head
			if tt.history {
				in.PublishedAt = tt.now.Add(-tt.stall)
			}
			if tt.unstood {
				k := tt.head + 1
				in.UnstoodGapSeq = &k
			}

			_, step := pub.EvaluateLadder(t.Context(), LadderState{}, in)

			if step.Seal != tt.seal || step.Gap != tt.gap {
				t.Errorf("단계 = %+v, want Seal %v · Gap %v", step, tt.seal, tt.gap)
			}
		})
	}
}

// live 가 아닌 소유 회차에는 사다리가 꺼져 봉인도 없다(봉인은 정산 몫이다).
func TestLadderNoSealForEndingOwner(t *testing.T) {
	pub := newLadderPublisher(&logRecorder{})
	pub.opt.StallSealAfter = 5500 * time.Millisecond
	in := ladderInput(stalledRows(), dueOf6.Add(time.Minute))
	in.Window.HeadSeq = 10
	in.PublishedAt = in.Now.Add(-time.Minute)
	in.Owner.State = "ending"
	if _, step := pub.EvaluateLadder(t.Context(), LadderState{}, in); step.Seal {
		t.Fatalf("ending 소유 회차에 봉인을 냈다: %+v", step)
	}
}

// D_act 는 갱신 의무 − T_edge − T_pub 이다(M4 r17 4.6.5 — 9.0 − 1.5 − 2.0 = 5.5초). 봉인 문턱은 기본 0(꺼짐)이다.
func TestDefaultOptionsDActAndSealOff(t *testing.T) {
	o := DefaultOptions("w", fxBaseURL)
	if got := o.DAct(); got != 5500*time.Millisecond {
		t.Errorf("DAct = %v, want 5.5s", got)
	}
	if o.StallSealAfter != 0 {
		t.Errorf("StallSealAfter 기본 = %v, want 0(꺼짐)", o.StallSealAfter)
	}
}
