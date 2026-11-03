package session

// 결정이 장부에 넘기는 회차 · 하한 거부 두 칸의 순수 판정 시험(POK-196 PDT 생산자 c4a · 설계 r7 2.4 · 3절).
//
// 장부(index)는 이 두 칸으로 이어 계산 회차 R(k) 와 판 정하기를 정한다. RoundID 는 결정 시점에 이미 정해진 회차를
// 싣고, 새 회차를 여는 개시면 "" 다(그 회차 id 는 Open 이 돌려주는 새 session_id). FloorRejected 는 라이브 중 귀속
// 하한 거부 한 자리(registry.go decide)에서만 참이다.

import (
	"testing"
	"time"
)

// 계속 · 하한 거부 · 평시 비귀속 · TD 분할 · 표식 분할의 두 칸. 잡는 결함: 하한 거부를 평시 비귀속과 같은 결정으로
// 돌려주면 장부가 그 행에 라이브 회차를 실어(설계 2.4 「라이브 중 하한 거부 행」 표) 다음 행이 거짓으로 앞뛴다.
// TD 분할의 회차를 비우면 장부가 새 회차로 읽어 판을 게이트에서 다시 고른다(판 고정 위반).
func TestDecideCarriesRoundAndFloorRejection(t *testing.T) {
	open := &liveSession{id: "S-live", startedAt: wall, targetDuration: 6, roundID: "S-round"}
	closed := &liveSession{id: "S-live", startedAt: wall, targetDuration: 6, roundID: "S-round", roundClosed: true}
	legacy := &liveSession{id: "S-live", startedAt: wall, targetDuration: 6}
	marked := &liveSession{id: "S-live", startedAt: wall.Add(-time.Hour), targetDuration: 6, roundID: "S-round",
		stopMarkedAt: wall}
	tests := []struct {
		name      string
		live      *liveSession
		in        Input
		outcome   Outcome
		roundID   string
		floorRejd bool
	}{
		{"계속 — live 회차", open, Input{StartWallUTC: wall.Add(4 * time.Second), DurationMS: 4000, Op: OpenOrCurrent},
			OutcomeCurrent, "S-round", false},
		{"계속 — 회차 없는 live(M6 전)", legacy, Input{StartWallUTC: wall.Add(4 * time.Second), DurationMS: 4000, Op: OpenOrCurrent},
			OutcomeCurrent, "", false},
		{"귀속 하한 거부", open, Input{StartWallUTC: wall.Add(-2 * time.Second), DurationMS: 4000, Op: OpenOrCurrent},
			OutcomeNone, "", true},
		{"live 없음 · CurrentOnly", nil, Input{StartWallUTC: wall, DurationMS: 4000, Op: CurrentOnly},
			OutcomeNone, "", false},
		{"TD 분할 — 열린 회차를 잇는다", open, Input{StartWallUTC: wall.Add(4 * time.Second), DurationMS: 9000, Op: OpenOrCurrent},
			OutcomeOpenFresh, "S-round", false},
		{"TD 분할 — 닫힌 회차면 새 회차", closed, Input{StartWallUTC: wall.Add(4 * time.Second), DurationMS: 9000, Op: OpenOrCurrent},
			OutcomeOpenFresh, "", false},
		{"TD 분할 — 회차 없는 live 면 새 회차", legacy, Input{StartWallUTC: wall.Add(4 * time.Second), DurationMS: 9000, Op: OpenOrCurrent},
			OutcomeOpenFresh, "", false},
		{"표식 분할 — 새 회차", marked, Input{StartWallUTC: wall.Add(9 * time.Second), DurationMS: 4000, Op: OpenOrCurrent},
			OutcomeOpenFresh, "", false},
		{"live 없음 · 개시(직전 회차 없음)", nil, Input{StartWallUTC: wall, DurationMS: 4000, Op: OpenOrCurrent},
			OutcomeOpen, "", false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			r, _ := newRoundRegistry()
			tt.in.StreamID, tt.in.Seq = "demo", 9

			d := r.decide(tt.live, tt.in, tt.in.StartWallUTC)

			if d.Outcome != tt.outcome || d.RoundID != tt.roundID || d.FloorRejected != tt.floorRejd {
				t.Errorf("(갈래, RoundID, FloorRejected) = (%v, %q, %t), want (%v, %q, %t)",
					d.Outcome, d.RoundID, d.FloorRejected, tt.outcome, tt.roundID, tt.floorRejd)
			}
		})
	}
}

// live 없는 개시는 잠근 직전 회차로 회차를 정한다(15.2 표 2 · 3 · 5 · 6 · 7행) — 결정의 RoundID 는 plan 의 회차와 같아야
// 한다. 잡는 결함: 계승 · 합류의 RoundID 를 비우면 장부가 새 회차로 읽어 판을 다시 고른다.
func TestOpenFromPreviousCarriesRound(t *testing.T) {
	ending := previousSession{id: "S-prev", state: "ending", endReason: "offline", vodState: "none",
		roundID: "S-round", lastEnd: wall.Add(-10 * time.Second), targetDuration: 6}
	tests := []struct {
		name    string
		prev    previousSession
		roundID string
	}{
		{"5행 계승", ending, "S-round"},
		{"6행 합류(사슬 닫힘 · 회차 열림)", func() previousSession { p := ending; p.vodState = "freezing"; return p }(), "S-round"},
		{"7행 회차 닫힘", func() previousSession { p := ending; p.roundClosed = true; return p }(), ""},
		{"7행 재접속 창 밖", func() previousSession { p := ending; p.lastEnd = wall.Add(-time.Hour); return p }(), ""},
		{"3행 회차 없는 직전 세션", func() previousSession { p := ending; p.roundID = ""; return p }(), ""},
		{"2행 표식 개시", func() previousSession { p := ending; p.stopMarkedAt = wall.Add(-9 * time.Second); return p }(), ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			r, _ := newRoundRegistry()
			in := Input{StreamID: "demo", Seq: 9, StartWallUTC: wall, DurationMS: 4000, Op: OpenOrCurrent}

			d := r.openFromPrevious(in, tt.prev)

			if d.RoundID != tt.roundID || d.RoundID != d.plan.roundID {
				t.Errorf("(RoundID, plan 회차) = (%q, %q), want (%q, %q)", d.RoundID, d.plan.roundID, tt.roundID, tt.roundID)
			}
		})
	}
}
