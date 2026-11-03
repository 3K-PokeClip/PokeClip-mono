package session

// 결정의 회차 · 하한 거부 두 칸을 실 PG 로 잰다(POK-196 PDT 생산자 c4a). PG_DSN 미설정이면 skip 이다.

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
)

// 결정은 live 행의 round_id 를 읽어 싣는다(currentLiveSQL · lockLiveSQL) — 계속 갈래는 그 회차, 하한 거부는 회차 없이
// FloorRejected 다. 잡는 결함: 조회가 round_id 를 읽지 않으면 장부가 모든 귀속 행을 회차 없음으로 쓴다.
func TestDecideReadsLiveRoundFromLedger(t *testing.T) {
	pool := newTestPool(t)
	r, _ := newRoundRegistry()
	seedLedger(t, pool, ledgerRow{id: "S-live", streamID: "demo", state: "live", roundID: "S-round", startedAt: wall})
	ctx := context.Background()

	withTx(t, pool, func(tx pgx.Tx) {
		cur, err := r.Decide(ctx, tx, Input{StreamID: "demo", Seq: 2, StartWallUTC: wall.Add(4 * time.Second),
			DurationMS: 4000, Op: OpenOrCurrent}, wall.Add(4*time.Second))
		if err != nil || cur.Outcome != OutcomeCurrent || cur.RoundID != "S-round" || cur.FloorRejected {
			t.Errorf("계속 결정 = (%+v, %v), want 계속 · S-round · 하한 거부 아님", cur, err)
		}
		floor, err := r.Decide(ctx, tx, Input{StreamID: "demo", Seq: 3, StartWallUTC: wall.Add(-2 * time.Second),
			DurationMS: 4000, Op: OpenOrCurrent}, wall)
		if err != nil || floor.Outcome != OutcomeNone || floor.RoundID != "" || !floor.FloorRejected {
			t.Errorf("하한 거부 결정 = (%+v, %v), want 비귀속 · 회차 없음 · 하한 거부", floor, err)
		}
	})
}

// TD 분할의 Open 은 끝내는 live 의 RETURNING 회차가 결정이 실은 회차와 다르면 개시를 되돌린다(ErrLiveLost). 소속 잠금
// 아래에서는 생기지 않는 갈래라 같은 트랜잭션 안에서 회차를 바꿔 흉내 낸다. 잡는 결함: 대조를 빼면 장부 행이 결정의
// 회차로 판을 고르고 세션 표는 다른 회차에 들어 둘이 갈린다.
func TestOpenRefusesSplitWhenEndedRoundDiffersFromDecision(t *testing.T) {
	pool := newTestPool(t)
	r, _ := newRoundRegistry()
	seedLedger(t, pool, ledgerRow{id: "S-X", streamID: "demo", state: "live", roundID: "S-R", startedAt: wall.Add(-time.Hour)})
	ctx := context.Background()

	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("트랜잭션 시작 실패: %v", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	d, err := r.Decide(ctx, tx, Input{StreamID: "demo", Seq: 2, StartWallUTC: wall, DurationMS: 9000, Op: OpenOrCurrent}, wall)
	if err != nil || d.Outcome != OutcomeOpenFresh || d.RoundID != "S-R" {
		t.Fatalf("TD 분할 결정 = (%+v, %v), want 분할 · 회차 S-R", d, err)
	}
	if _, err := tx.Exec(ctx, `UPDATE stream_sessions SET round_id = 'S-other' WHERE session_id = 'S-X'`); err != nil {
		t.Fatalf("회차 바꾸기 흉내 실패: %v", err)
	}
	if _, err := r.Open(ctx, tx, d, wall); !errors.Is(err, ErrLiveLost) {
		t.Fatalf("Open 오류 = %v, want ErrLiveLost(결정 회차 S-R ≠ 끝낸 live 회차 S-other)", err)
	}
}
