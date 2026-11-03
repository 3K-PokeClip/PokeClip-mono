package session

// 결정의 회차(Decision.RoundID)와 개시가 쓴 round_id 의 일치 — 실 PG 시험(POK-525 Ⓑ2-3 · 설계 r9.4 회차 정본 (가) ·
// 계획 r2 5절 E31 · P15).
//
// ⑹ 키의 회차 성분은 Decide 가 정한 RoundID 다. 개시 갈래에서 Open 이 실제로 쓴 round_id 와 그 값이 갈리면 키가
// 세션의 회차와 다른 접두로 저장된다. 그래서 갈래마다 두 값이 같은지 재고, 갈리면 Open 이 오류로 트랜잭션을
// 되돌리는지 잰다.

import (
	"context"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// decideThenOpen 은 결정 → (개시 갈래면) 개시를 한 트랜잭션에서 돌리고, 오류가 없으면 커밋한다. 개시 갈래가 아니면
// opened 는 "" 다. 시험 대상(Decide · Open)의 오류는 돌려주어 호출한 시험이 판정하고, 여기서 t.Fatal 로 끊는 것은
// 환경(트랜잭션 시작 · 커밋) 실패뿐이다.
func decideThenOpen(t *testing.T, pool *pgxpool.Pool, r *Registry, in Input) (d Decision, opened string, err error) {
	t.Helper()
	ctx := context.Background()
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("트랜잭션 시작 실패: %v", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if d, err = r.Decide(ctx, tx, in, in.StartWallUTC); err != nil {
		return d, "", fmt.Errorf("결정 실패: %w", err)
	}
	if d.Outcome == OutcomeOpen || d.Outcome == OutcomeOpenFresh {
		if opened, err = r.Open(ctx, tx, d, in.StartWallUTC); err != nil {
			return d, "", fmt.Errorf("개시 실패: %w", err)
		}
	}
	if err := tx.Commit(ctx); err != nil {
		t.Fatalf("트랜잭션 커밋 실패: %v", err)
	}
	return d, opened, nil
}

// 갈래마다 RoundID 가 정해진 값이다 — 개시 갈래는 Open 이 쓴 round_id 와 같고, 「새 회차를 여는가」 는
// RoundID == Open 이 돌려준 session_id 로 가려진다(PDT 가 이 판별식을 쓴다). 비귀속은 빈 값이다.
//
// 변이: TD 분할의 RoundID 를 새 세션 id 로 → TD_분할 FAIL · 표식 분할의 RoundID 를 끝낸 세션 회차로 → 표식_분할 FAIL ·
// 계속의 RoundID 를 빈 값으로 → live_합류 FAIL.
func TestDecisionRoundIDEqualsRoundWrittenByOpen(t *testing.T) {
	const stream = "demo"
	mark := wall.Add(-10 * time.Second) // 표식 + 8초 여유 = wall − 2초 ≤ wall
	at := func(seq int64, durationMS int32) Input {
		return Input{StreamID: stream, Seq: seq, StartWallUTC: wall, DurationMS: durationMS, Op: OpenOrCurrent}
	}
	liveRow := func(roundID string, marked time.Time) ledgerRow {
		return ledgerRow{id: "S-L", streamID: stream, state: "live", roundID: roundID, stopMarkedAt: marked, startedAt: wall.Add(-time.Hour)}
	}
	// prevRow 는 끝난 직전 회차 S-P 다 — 조각 하나(wall − 1분 시작 · 4초)를 함께 심어 계승 · 합류 창 안에 둔다.
	prevRow := func(endReason, roundID string, marked time.Time) ledgerRow {
		return ledgerRow{id: "S-P", streamID: stream, state: "ending", endReason: endReason, roundID: roundID,
			stopMarkedAt: marked, startedAt: wall.Add(-time.Hour)}
	}

	for _, c := range []struct {
		name    string
		seed    []ledgerRow
		in      Input
		outcome Outcome
		// round 는 기대 RoundID 다. newRound 면 무시하고 Open 이 돌려준 session_id 와 견준다.
		round    string
		newRound bool
	}{
		{name: "live_합류", seed: []ledgerRow{liveRow("S-R", time.Time{})}, in: at(2, 4000), outcome: OutcomeCurrent, round: "S-R"},
		{name: "live_합류_NULL회차", seed: []ledgerRow{liveRow("", time.Time{})}, in: at(2, 4000), outcome: OutcomeCurrent, round: ""},
		{name: "TD_분할", seed: []ledgerRow{liveRow("S-R", time.Time{})}, in: at(2, 9000), outcome: OutcomeOpenFresh, round: "S-R"},
		{name: "TD_분할_NULL회차", seed: []ledgerRow{liveRow("", time.Time{})}, in: at(2, 9000), outcome: OutcomeOpenFresh, newRound: true},
		{name: "표식_분할", seed: []ledgerRow{liveRow("S-R", mark)}, in: at(2, 4000), outcome: OutcomeOpenFresh, newRound: true},
		{name: "직전_합류_6행", seed: []ledgerRow{prevRow("init_mismatch", "S-R", time.Time{})}, in: at(2, 4000), outcome: OutcomeOpen, round: "S-R"},
		{name: "직전_계승_5행", seed: []ledgerRow{prevRow("offline", "S-R", time.Time{})}, in: at(2, 4000), outcome: OutcomeOpen, round: "S-R"},
		{name: "새_회차_첫_세션", in: at(2, 4000), outcome: OutcomeOpen, newRound: true},
		{name: "새_회차_표식_개시_2행", seed: []ledgerRow{prevRow("offline", "S-R", mark)}, in: at(2, 4000), outcome: OutcomeOpen, newRound: true},
		{name: "새_회차_NULL회차_3행", seed: []ledgerRow{prevRow("offline", "", time.Time{})}, in: at(2, 4000), outcome: OutcomeOpen, newRound: true},
		{name: "비귀속_live_없음", in: Input{StreamID: stream, Seq: 2, StartWallUTC: wall, DurationMS: 4000, Op: CurrentOnly}, outcome: OutcomeNone, round: ""},
		{name: "비귀속_하한_거부", seed: []ledgerRow{{id: "S-L", streamID: stream, state: "live", roundID: "S-R", startedAt: wall.Add(time.Hour)}},
			in: at(2, 4000), outcome: OutcomeNone, round: ""},
	} {
		t.Run(c.name, func(t *testing.T) {
			pool := newTestPool(t)
			r, _ := newRoundRegistry()
			for _, row := range c.seed {
				seedLedger(t, pool, row)
				if row.state != "live" {
					seedSegment(t, pool, stream, row.id, 1, wall.Add(-time.Minute))
				}
			}

			d, opened, err := decideThenOpen(t, pool, r, c.in)

			if err != nil {
				t.Fatalf("결정 → 개시 = %v, want 오류 없음", err)
			}
			if d.Outcome != c.outcome {
				t.Fatalf("갈래 = %s, want %s", d.Outcome, c.outcome)
			}
			want := c.round
			if c.newRound {
				want = opened
			}
			if d.RoundID != want {
				t.Errorf("RoundID = %q, want %q", d.RoundID, want)
			}
			if opened == "" {
				return
			}
			if got := strOf(roundOf(t, pool, opened).roundID); got != d.RoundID {
				t.Errorf("Open 이 쓴 round_id = %s, 결정의 RoundID = %q — 둘이 갈렸다", got, d.RoundID)
			}
			if isNew := d.RoundID == opened; isNew != c.newRound {
				t.Errorf("RoundID == 새 session_id(%q) = %v, want %v — 「새 회차를 여는가」 판별식이 틀렸다", opened, isNew, c.newRound)
			}
		})
	}
}

// Open 은 자기가 쓸 round_id 가 결정의 RoundID 와 다르면 오류를 내고, 그 트랜잭션은 되돌려진다 — 새 세션 행 0 ·
// 끝내려던 live 는 그대로 live 다. 변이: Open 의 대조 제거 → 세 사례 모두 FAIL.
func TestOpenRejectsRoundIDDifferentFromDecision(t *testing.T) {
	const stream = "demo"
	mark := wall.Add(-10 * time.Second)
	for _, c := range []struct {
		name     string
		seed     []ledgerRow
		in       Input
		tampered string
	}{
		{name: "TD_분할에_새_회차", seed: []ledgerRow{{id: "S-L", streamID: stream, state: "live", roundID: "S-R", startedAt: wall.Add(-time.Hour)}},
			in: Input{StreamID: stream, Seq: 2, StartWallUTC: wall, DurationMS: 9000, Op: OpenOrCurrent}, tampered: sessionID(stream, wall, 2)},
		{name: "표식_분할에_끝낸_회차", seed: []ledgerRow{{id: "S-L", streamID: stream, state: "live", roundID: "S-R", stopMarkedAt: mark, startedAt: wall.Add(-time.Hour)}},
			in: Input{StreamID: stream, Seq: 2, StartWallUTC: wall, DurationMS: 4000, Op: OpenOrCurrent}, tampered: "S-R"},
		{name: "새_회차에_빈_값", in: Input{StreamID: stream, Seq: 2, StartWallUTC: wall, DurationMS: 4000, Op: OpenOrCurrent}, tampered: ""},
	} {
		t.Run(c.name, func(t *testing.T) {
			pool := newTestPool(t)
			r, _ := newRoundRegistry()
			for _, row := range c.seed {
				seedLedger(t, pool, row)
			}
			ctx := context.Background()
			tx, err := pool.Begin(ctx)
			if err != nil {
				t.Fatalf("트랜잭션 시작 실패: %v", err)
			}
			d, err := r.Decide(ctx, tx, c.in, c.in.StartWallUTC)
			if err != nil {
				_ = tx.Rollback(ctx)
				t.Fatalf("결정 실패: %v", err)
			}
			d.RoundID = c.tampered

			id, err := r.Open(ctx, tx, d, c.in.StartWallUTC)
			_ = tx.Rollback(ctx)

			if err == nil {
				t.Fatalf("결정과 다른 회차(%q)로 개시가 성공했다 id=%q", c.tampered, id)
			}
			var rows int
			if err := pool.QueryRow(ctx, `SELECT count(*) FROM stream_sessions WHERE stream_id = $1`, stream).Scan(&rows); err != nil {
				t.Fatalf("세션 수 조회 실패: %v", err)
			}
			if rows != len(c.seed) {
				t.Errorf("세션 행 = %d, want %d(새 행 0)", rows, len(c.seed))
			}
			for _, row := range c.seed {
				if got := roundOf(t, pool, row.id).state; got != row.state {
					t.Errorf("세션 %s 상태 = %s, want %s(되돌려졌어야 한다)", row.id, got, row.state)
				}
			}
		})
	}
}

// live 인데 round_closed_at 이 있는 회차 위의 TD 분할 — 코드로는 생기지 않는 상태다. 회차 닫힘을 쓰는 문장은
// index/round_close.go 의 closeRoundSQL 하나이고, 그 앞 판정 closableRoundsSQL 은 소속 잠금 아래에서 최신(live 우선)이
// live 인 회차를 고르지 않는다(`l.state <> 'live'`). 개시는 닫힌 회차에 들지 않는다(openFromPrevious 의 !prev.roundClosed ·
// successorRound 의 roundClosed). 그래서 픽스처로 상태를 억지로 만들어, 생기면 어떻게 되는지를 실물로 고정한다(Ⓑ2-3 DB M1).
//
// 결과: Decide 는 잠근 live 의 round_id 를 RoundID 로 내고, Open 은 끝내는 UPDATE 의 RETURNING 이 닫힘을 보고 새 회차를
// 쓰려다 결정과 갈려 오류다(checkDecidedRound) — 트랜잭션이 되돌려져 새 세션 0 · live 그대로다. 같은 조각마다 되풀이되는
// fail-closed 이지 키가 세션과 다른 회차로 저장되는 길은 아니다. 변이: Open 의 대조 제거 → 새 회차로 개시가 성공해 FAIL.
func TestTDSplitOnLiveOfClosedRoundFailsClosed(t *testing.T) {
	const stream = "demo"
	pool := newTestPool(t)
	r, _ := newRoundRegistry()
	seed := ledgerRow{id: "S-L", streamID: stream, state: "live", roundID: "S-R", roundClosedAt: wall.Add(-time.Minute),
		startedAt: wall.Add(-time.Hour)}
	seedLedger(t, pool, seed)
	in := Input{StreamID: stream, Seq: 2, StartWallUTC: wall, DurationMS: 9000, Op: OpenOrCurrent}
	ctx := context.Background()
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("트랜잭션 시작 실패: %v", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	d, err := r.Decide(ctx, tx, in, in.StartWallUTC)
	if err != nil {
		t.Fatalf("결정 실패: %v", err)
	}
	id, openErr := r.Open(ctx, tx, d, in.StartWallUTC)
	_ = tx.Rollback(ctx)

	if d.Outcome != OutcomeOpenFresh || d.RoundID != "S-R" {
		t.Fatalf("결정 = (%s, RoundID %q), want (%s, \"S-R\")", d.Outcome, d.RoundID, OutcomeOpenFresh)
	}
	if openErr == nil || !strings.Contains(openErr.Error(), "결정의 RoundID") {
		t.Fatalf("Open = (%q, %v), want 결정과 회차가 갈린다는 오류", id, openErr)
	}
	var rows int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM stream_sessions WHERE stream_id = $1`, stream).Scan(&rows); err != nil {
		t.Fatalf("세션 수 조회 실패: %v", err)
	}
	if got := roundOf(t, pool, seed.id).state; rows != 1 || got != "live" {
		t.Errorf("세션 행 = %d · %s 상태 = %s, want 1 · live(되돌려졌어야 한다)", rows, seed.id, got)
	}
}
