package session

// 결정이 장부에 넘기는 회차 · 새 회차 · 하한 거부 세 칸의 순수 판정 시험(POK-196 PDT 생산자 c4a · 설계 r7 2.4 · 3절).
//
// 장부(index)는 이 칸들로 이어 계산 회차 R(k) 와 판 정하기를 정한다. RoundID 의 갈래별 값은 POK-525 Ⓑ2 가 정본이다(개시는
// 비지 않는다 — 새 회차면 새 세션 id). NewRound 는 RoundID 가 새 세션 id 인 개시에서만 참이다 — 장부는 Open 앞에서 판을
// 골라야 해 Open 이 돌려줄 id 와 견줄 수 없으므로 결정이 같이 싣는다. FloorRejected 는 라이브 중 귀속 하한 거부 한
// 자리(registry.go decide)에서만 참이다.

import (
	"testing"
	"time"
)

// newRoundMarker 는 기대 RoundID 자리에서 「이 개시가 열 새 세션 id」 를 뜻한다.
const newRoundMarker = "<new>"

// checkRoundFields 는 결정 d 의 회차 칸들이 기대(갈래 · 회차 · 새 회차 · 하한 거부)와 같고, 불변식
// 「NewRound == (RoundID == 새 세션 id)」 가 서는지 본다. 개시 갈래가 아니면 NewRound 는 거짓이어야 한다.
func checkRoundFields(t *testing.T, d Decision, outcome Outcome, round string, newRound, floorRejected bool) {
	t.Helper()
	opens := d.Outcome == OutcomeOpen || d.Outcome == OutcomeOpenFresh
	newID := ""
	if opens {
		newID = d.plan.newSessionID()
	}
	if round == newRoundMarker {
		round = newID
	}
	if d.Outcome != outcome || d.RoundID != round || d.NewRound != newRound || d.FloorRejected != floorRejected {
		t.Errorf("(갈래, RoundID, NewRound, FloorRejected) = (%v, %q, %t, %t), want (%v, %q, %t, %t)",
			d.Outcome, d.RoundID, d.NewRound, d.FloorRejected, outcome, round, newRound, floorRejected)
	}
	if invariant := opens && d.RoundID == newID; d.NewRound != invariant {
		t.Errorf("NewRound = %t, RoundID == 새 세션 id(%q) = %t — 불변식이 깨졌다", d.NewRound, newID, invariant)
	}
}

// 계속 · 하한 거부 · 평시 비귀속 · TD 분할 · 표식 분할 · live 없는 개시의 세 칸. 잡는 결함: 하한 거부를 평시 비귀속과
// 같은 결정으로 돌려주면 장부가 그 행에 라이브 회차를 실어(설계 2.4 「라이브 중 하한 거부 행」 표) 다음 행이 거짓으로
// 앞뛴다. TD 분할에 NewRound 를 세우면 장부가 판을 게이트에서 다시 고른다(판 고정 위반). 새 회차 갈래에 NewRound 를
// 빠뜨리면 장부가 앞 회차 행을 이어 계산의 p 로 집는다.
func TestDecideCarriesRoundAndFloorRejection(t *testing.T) {
	open := &liveSession{id: "S-live", startedAt: wall, targetDuration: 6, roundID: "S-round"}
	legacy := &liveSession{id: "S-live", startedAt: wall, targetDuration: 6}
	marked := &liveSession{id: "S-live", startedAt: wall.Add(-time.Hour), targetDuration: 6, roundID: "S-round",
		stopMarkedAt: wall}
	tests := []struct {
		name      string
		live      *liveSession
		in        Input
		outcome   Outcome
		roundID   string
		newRound  bool
		floorRejd bool
	}{
		{"계속 — live 회차", open, Input{StartWallUTC: wall.Add(4 * time.Second), DurationMS: 4000, Op: OpenOrCurrent},
			OutcomeCurrent, "S-round", false, false},
		{"계속 — 회차 없는 live(M6 전)", legacy, Input{StartWallUTC: wall.Add(4 * time.Second), DurationMS: 4000, Op: OpenOrCurrent},
			OutcomeCurrent, "", false, false},
		{"귀속 하한 거부", open, Input{StartWallUTC: wall.Add(-2 * time.Second), DurationMS: 4000, Op: OpenOrCurrent},
			OutcomeNone, "", false, true},
		{"live 없음 · CurrentOnly", nil, Input{StartWallUTC: wall, DurationMS: 4000, Op: CurrentOnly},
			OutcomeNone, "", false, false},
		{"TD 분할 — 열린 회차를 잇는다", open, Input{StartWallUTC: wall.Add(4 * time.Second), DurationMS: 9000, Op: OpenOrCurrent},
			OutcomeOpenFresh, "S-round", false, false},
		{"TD 분할 — 회차 없는 live 면 새 회차", legacy, Input{StartWallUTC: wall.Add(4 * time.Second), DurationMS: 9000, Op: OpenOrCurrent},
			OutcomeOpenFresh, newRoundMarker, true, false},
		{"표식 분할 — 새 회차", marked, Input{StartWallUTC: wall.Add(9 * time.Second), DurationMS: 4000, Op: OpenOrCurrent},
			OutcomeOpenFresh, newRoundMarker, true, false},
		{"live 없음 · 개시(직전 회차 없음)", nil, Input{StartWallUTC: wall, DurationMS: 4000, Op: OpenOrCurrent},
			OutcomeOpen, newRoundMarker, true, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			r, _ := newRoundRegistry()
			tt.in.StreamID, tt.in.Seq = "demo", 9

			d := r.decide(tt.live, tt.in, tt.in.StartWallUTC)

			checkRoundFields(t, d, tt.outcome, tt.roundID, tt.newRound, tt.floorRejd)
		})
	}
}

// live 없는 개시는 잠근 직전 회차로 회차를 정한다(15.2 표 2 · 3 · 5 · 6 · 7행) — 결정의 RoundID 는 plan 의 회차(새 회차면
// 새 세션 id)와 같고, NewRound 는 직전 회차에 들지 않을 때만 참이다. 잡는 결함: 계승 · 합류가 RoundID 를 덮으며
// NewRound 를 되돌리지 않으면 장부가 이어 가는 회차를 새 회차로 읽어 판을 다시 고른다.
func TestOpenFromPreviousCarriesRound(t *testing.T) {
	ending := previousSession{id: "S-prev", state: "ending", endReason: "offline", vodState: "none",
		roundID: "S-round", lastEnd: wall.Add(-10 * time.Second), targetDuration: 6}
	tests := []struct {
		name     string
		prev     previousSession
		roundID  string
		newRound bool
	}{
		{"5행 계승", ending, "S-round", false},
		{"6행 합류(사슬 닫힘 · 회차 열림)", func() previousSession { p := ending; p.vodState = "freezing"; return p }(), "S-round", false},
		{"7행 회차 닫힘", func() previousSession { p := ending; p.roundClosed = true; return p }(), newRoundMarker, true},
		{"7행 재접속 창 밖", func() previousSession { p := ending; p.lastEnd = wall.Add(-time.Hour); return p }(), newRoundMarker, true},
		{"3행 회차 없는 직전 세션", func() previousSession { p := ending; p.roundID = ""; return p }(), newRoundMarker, true},
		{"2행 표식 개시", func() previousSession { p := ending; p.stopMarkedAt = wall.Add(-9 * time.Second); return p }(), newRoundMarker, true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			r, _ := newRoundRegistry()
			in := Input{StreamID: "demo", Seq: 9, StartWallUTC: wall, DurationMS: 4000, Op: OpenOrCurrent}

			d := r.openFromPrevious(in, tt.prev)

			checkRoundFields(t, d, OutcomeOpen, tt.roundID, tt.newRound, false)
		})
	}
}
