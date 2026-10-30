package main

// L2′ × 실 장부(POK-196 M6-3b 커밋 ④b). 조립 손잡이(fa.stallSealEnd — 운영 index.EndStallSeal)로 봉인이 선 live 회차를 끝낸다.

import (
	"log/slog"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/finalize"
)

// 스위치를 켠 조립으로 봉인이 선(dvr frozen) live 회차를 끝낸다 — 장부의 사유는 stall_seal · 회차는 열린 채(재접속 창 안 합류 ·
// 계승)이고, 종료 문장은 동결 축을 건드리지 않는다(봉인한 frozen 그대로).
func TestStallSealEndsLedgerSessionThroughAssembly(t *testing.T) {
	pool := newWireTestPool(t)
	execSQL(t, pool, `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, round_id, dvr_state)
VALUES ('Z', 'l2p-pg', $1, 'live', 'RZ', 'frozen')`, time.Now().Add(-time.Hour))
	f := newLedgerFinalLoop(t, pool, finalize.ModeOff)
	fa := finalizeAssembly{close: index.CloseOptions{Window: 300 * time.Second, Slack: 60 * time.Second, Log: slog.New(slog.DiscardHandler)},
		enqueue: noEndedEnqueue}
	f.rewind.endStallSeal = fa.stallSealEnd(true, pool)
	f.rewind.stream("l2p-pg").stallSeal = stallSeal{sealed: "Z"}

	f.drive(loopBase, 3)

	var state, reason, dvr string
	var closed bool
	if err := pool.QueryRow(t.Context(), `SELECT state, end_reason, dvr_state, round_closed_at IS NOT NULL FROM stream_sessions WHERE session_id = 'Z'`).
		Scan(&state, &reason, &dvr, &closed); err != nil {
		t.Fatal(err)
	}
	if state != "ending" || reason != "stall_seal" || closed || dvr != "frozen" {
		t.Fatalf("Z = (%s, %s, dvr %s, 회차 닫힘 %v), want (ending, stall_seal, frozen, 열림)", state, reason, dvr, closed)
	}
}
