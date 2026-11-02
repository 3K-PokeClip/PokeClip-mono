package main

// L3 × 실 장부(POK-196 M6-3b 커밋 ④). 조립 손잡이(fa.gapRunEnd — 운영 index.EndGapRun)로 live 회차를 끝내고, 그 회차가
// 빠른 길로 정산에 들어가는지 본다.

import (
	"log/slog"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/finalize"
)

// 스위치를 켠 조립으로 GAP 셋이 선 live 회차를 끝낸다 — 장부의 사유는 gap_run · 회차는 열린 채(재접속 창 안 합류 · 계승)이고,
// 같은 틱의 빠른 길이 그 행을 읽어 동결을 낸다(꺼짐 판이라 빈 동결).
func TestGapRunEndsLedgerSessionThroughAssembly(t *testing.T) {
	pool := newWireTestPool(t)
	execSQL(t, pool, `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, round_id)
VALUES ('L', 'l3-pg', $1, 'live', 'RL')`, time.Now().Add(-time.Hour))
	f := newLedgerFinalLoop(t, pool, finalize.ModeOff)
	fa := finalizeAssembly{close: index.CloseOptions{Window: 300 * time.Second, Slack: 60 * time.Second, Log: slog.New(slog.DiscardHandler)},
		enqueue: noEndedEnqueue}
	f.rewind.endGapRun = fa.gapRunEnd(true, pool)
	for k := int64(20); k < 23; k++ {
		stoodGap(f.rewind, "l3-pg", "L", k)
	}

	f.drive(loopBase, 3)

	var state, reason, dvr string
	var closed bool
	if err := pool.QueryRow(t.Context(), `SELECT state, end_reason, dvr_state, round_closed_at IS NOT NULL FROM stream_sessions WHERE session_id = 'L'`).
		Scan(&state, &reason, &dvr, &closed); err != nil {
		t.Fatal(err)
	}
	if state != "ending" || reason != "gap_run" || closed {
		t.Fatalf("L = (%s, %s, 회차 닫힘 %v), want (ending, gap_run, 열림)", state, reason, closed)
	}
	if dvr != "frozen" {
		t.Fatalf("L dvr = %s, want frozen — 빠른 길이 정산에 넣지 않았다", dvr)
	}
}
