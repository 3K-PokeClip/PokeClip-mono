package session

// 회차 개시 규칙(M6 15.2 표)과 표식 분할(3.3 결정 순서 1)의 순수 판정 시험(POK-196 M6-3a 커밋 ①).
//
// 이 판정은 DB 를 보지 않는다 — 소속 잠금 뒤 다시 읽은 행을 받아 갈래 · 회차 · 표식 승계를 정한다.
// 잠금과 RETURNING 쪽은 membership_pg_test.go 가 잰다.

import (
	"log/slog"
	"testing"
	"time"
)

// newRoundRegistry 는 개시 규칙 시험용 레지스트리다 — 재접속 창 300초 · 표식 여유 8초(K8).
func newRoundRegistry() (*Registry, *signalLog) {
	sig := &signalLog{}
	return New(Options{
		FloorSlack:      time.Second,
		ObsFresh:        30 * time.Second,
		ReconnectWindow: 300 * time.Second,
		StopSlack:       8 * time.Second,
		Log:             slog.New(sig),
	}), sig
}

// 표식 분할(3.3 결정 1 · 15.2 표 1행)은 「표식 + 8초」 이상인 조각에서만 선다. 그 앞 조각은 그 live 에 붙는다 —
// 계약4 3-2 가 허용한 「남을 수 있다」 범위이고 남는 양은 8초 이하다(D54 기록). 분할은 live 를 intentional 로
// 끝내고 비계승 · 불연속 기준 0 · 새 회차 · 표식 없음으로 연다. TD 를 넘는 조각이어도 표식 분할이 먼저다.
// 잡는 결함: 여유를 빼면 멈춘 연결의 마지막 조각이 새 회차가 되고(D53), 사유를 td_exceeded 로 쓰면 회차 닫힘
// 조건(사유 intentional)이 서지 않는다.
func TestDecideSplitsAtStopMarkPlusSlack(t *testing.T) {
	mark := wall
	live := &liveSession{id: "S-live", startedAt: wall.Add(-time.Hour), targetDuration: 6, discontinuityBase: 5,
		stopMarkedAt: mark}
	tests := []struct {
		name       string
		start      time.Time
		durationMS int32
		want       Outcome
		wantReason string
	}{
		{"여유_1ms_전", mark.Add(8*time.Second - time.Millisecond), 4000, OutcomeCurrent, ""},
		{"정확히_표식_8초", mark.Add(8 * time.Second), 4000, OutcomeOpenFresh, endReasonIntentional},
		{"표식_8초_뒤_TD_초과", mark.Add(9 * time.Second), 9000, OutcomeOpenFresh, endReasonIntentional},
		{"여유_전_TD_초과는_TD_분할", mark.Add(time.Second), 9000, OutcomeOpenFresh, endReasonTDExceeded},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			r, _ := newRoundRegistry()
			in := Input{StreamID: "demo", Seq: 9, StartWallUTC: tt.start, DurationMS: tt.durationMS, Op: OpenOrCurrent}

			d := r.decide(live, in, tt.start)

			if d.Outcome != tt.want {
				t.Fatalf("갈래 = %v, want %v", d.Outcome, tt.want)
			}
			if tt.want != OutcomeOpenFresh {
				return
			}
			if d.EndingSessionID != "S-live" || d.plan.endingSessionID != "S-live" || d.plan.endReason != tt.wantReason {
				t.Errorf("(EndingSessionID, 끝낼 세션, 사유) = (%q, %q, %q), want (S-live, S-live, %s)",
					d.EndingSessionID, d.plan.endingSessionID, d.plan.endReason, tt.wantReason)
			}
			if tt.wantReason != endReasonIntentional {
				if !d.plan.roundFromEnding || d.plan.discontinuityBase != 5 {
					t.Errorf("TD 분할 (회차 승계, 불연속 기준) = (%v, %d), want (true, 5)", d.plan.roundFromEnding, d.plan.discontinuityBase)
				}
				return
			}
			if d.plan.roundFromEnding || d.plan.roundID != "" || d.plan.stopMarkedAt != nil ||
				d.plan.inheritsSession != "" || d.plan.discontinuityBase != 0 {
				t.Errorf("표식 분할 plan = %+v, want 새 회차 · 표식 없음 · 비계승 · 불연속 기준 0", *d.plan)
			}
		})
	}
}

// 표식 여유가 영값이면 표식 분할을 하지 않는다(Options 의 영값 = 안전 방향 — 「확실하지 않으면 적용하지 않는다」).
func TestDecideIgnoresStopMarkWhenSlackUnset(t *testing.T) {
	r, _ := newTestRegistry(time.Second, 30*time.Second)
	live := &liveSession{id: "S-live", startedAt: wall.Add(-time.Hour), targetDuration: 6, stopMarkedAt: wall}
	in := Input{StreamID: "demo", Seq: 9, StartWallUTC: wall.Add(time.Minute), DurationMS: 4000, Op: OpenOrCurrent}

	if d := r.decide(live, in, in.StartWallUTC); d.Outcome != OutcomeCurrent {
		t.Errorf("갈래 = %v, want current — 여유를 받지 못한 결정자는 표식으로 가르지 않는다", d.Outcome)
	}
}

// 15.2 개시 규칙 표(위에서 처음 맞는 행이 이긴다)의 live 없는 갈래 — 표 2 · 3 · 5 · 6 · 7행. 입력은 잠근 prev 다.
// 잡는 결함(행마다): 2행이 빠지면 init 불일치가 live 를 먼저 끝낸 뒤 멈춘 다음 첫 조각이 옛 회차에 합류한다(D60 (나)) /
// 3행이 빠지면 M6 전 세션의 NULL 회차를 새 세션이 물려받아 회차 없는 방송이 된다 / 5 · 6행의 표식 승계가 빠지면
// 그 뒤 조각에서 1행이 서지 않는다(D60 (가)) / 6행의 닫힘 조건이 빠지면 닫힌 회차에 새 세션이 붙는다(D9 · D69) /
// intentional 사유를 받아들이면 의도적으로 끝난 방송에 다음 방송이 합류한다.
func TestOpenFromPreviousFollowsRoundRuleTable(t *testing.T) {
	end := wall // prev 마지막 조각 끝
	mark := end.Add(-time.Second)
	prev := func(mut func(*previousSession)) previousSession {
		p := previousSession{id: "S-prev", state: "ending", endReason: "offline", vodState: "none",
			roundID: "S-round", lastEnd: end, targetDuration: 6, discontinuityBase: 2}
		if mut != nil {
			mut(&p)
		}
		return p
	}
	withMark := func(p *previousSession) { p.stopMarkedAt = mark }
	tests := []struct {
		name        string
		prev        previousSession
		start       time.Time
		wantRound   string // "" = 자기 → 새 회차
		wantInherit bool
		wantMark    bool // prev 표식 승계
		wantDecline string
	}{
		{"2행_표식_개시", prev(withMark), mark.Add(9 * time.Second), "", false, false, ""},
		{"2행_여유_전은_5행_계승", prev(withMark), mark.Add(7 * time.Second), "S-round", true, true, ""},
		{"3행_NULL_회차는_새_회차_계승은_그대로", prev(func(p *previousSession) { p.roundID = "" }), end.Add(time.Minute), "", true, false, ""},
		{"5행_계승_개시", prev(nil), end.Add(time.Minute), "S-round", true, false, ""},
		{"6행_init_불일치_재접속", prev(func(p *previousSession) { p.endReason = endReasonInitMismatch; withMark(p) }), end.Add(3 * time.Second), "S-round", false, true, ""},
		{"6행_ended_는_합류만", prev(func(p *previousSession) { p.state = "ended"; p.vodState = "frozen" }), end.Add(time.Minute), "S-round", false, false, ""},
		{"6행_닫힌_사슬은_합류만", prev(func(p *previousSession) { p.vodState = "freezing" }), end.Add(time.Minute), "S-round", false, false, declineChainClosed},
		{"7행_닫힌_회차", prev(func(p *previousSession) { p.roundClosed = true }), end.Add(time.Minute), "", false, false, declineRoundClosed},
		{"7행_intentional_로_끝남", prev(func(p *previousSession) { p.endReason = endReasonIntentional }), end.Add(time.Minute), "", false, false, ""},
		{"7행_창_밖", prev(nil), end.Add(300*time.Second + time.Millisecond), "", false, false, ""},
		{"7행_음수_공백", prev(nil), end.Add(-time.Millisecond), "", false, false, ""},
		{"7행_조각_없는_prev", prev(func(p *previousSession) { p.lastEnd = time.Time{} }), end.Add(time.Minute), "", false, false, ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			r, sig := newRoundRegistry()
			in := Input{StreamID: "demo", Seq: 30, StartWallUTC: tt.start, DurationMS: 4000, Op: OpenOrCurrent}

			d := r.openFromPrevious(in, tt.prev)

			if d.Outcome != OutcomeOpen || d.plan == nil {
				t.Fatalf("갈래 = %v · plan %v, want open · plan 있음", d.Outcome, d.plan != nil)
			}
			if d.BaseSessionID != "S-prev" {
				t.Errorf("기저 세션 = %q, want S-prev(상태 무관 직전 회차)", d.BaseSessionID)
			}
			if d.plan.roundID != tt.wantRound || d.plan.roundFromEnding {
				t.Errorf("회차 = %q(끝낸 행 승계 %v), want %q", d.plan.roundID, d.plan.roundFromEnding, tt.wantRound)
			}
			if got := d.plan.inheritsSession == "S-prev"; got != tt.wantInherit {
				t.Errorf("계승 = %v, want %v", got, tt.wantInherit)
			}
			if got := d.plan.stopMarkedAt != nil && d.plan.stopMarkedAt.Equal(mark); got != tt.wantMark {
				t.Errorf("표식 승계 = %v(%v), want %v", got, d.plan.stopMarkedAt, tt.wantMark)
			}
			if !tt.wantMark && d.plan.stopMarkedAt != nil {
				t.Errorf("표식 = %v, want NULL", *d.plan.stopMarkedAt)
			}
			gotDecline := ""
			if sig.fired("inherit_declined") {
				gotDecline, _ = sig.attrs("inherit_declined")["reason"].(string)
			}
			if gotDecline != tt.wantDecline {
				t.Errorf("inherit_declined reason = %q, want %q", gotDecline, tt.wantDecline)
			}
		})
	}
}
