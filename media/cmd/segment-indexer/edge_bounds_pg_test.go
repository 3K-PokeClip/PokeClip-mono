package main

// /dvr 하한 배선의 실 PG 시험(POK-525 계획 Ⓒ-6 · E10) — 운영 조립(startEdgeBounds → index.EdgeBoundsLedger)이 ARN 없이
// 뜨면 원장의 닫힌 미해제 행을 기동 때 ERROR 로 알린다. 실 AWS 는 부르지 않는다(ARN 빔 갈래).

import (
	"log/slog"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
)

// 세션 행이 없는 원장 행(만료 · 탈퇴 — 닫힘으로 본다)이 있으면 기동 직후 set 한 줄(rows=1)이다. 그 행에 해제를 적은 뒤
// 다시 기동하면 줄이 없다 — 감시는 닫힌 미해제 행만 센다.
func TestStartEdgeBoundsWithoutARNReportsLedgerRowsPG(t *testing.T) {
	pool := newWireTestPool(t)
	ctx := t.Context()
	if _, err := pool.Exec(ctx, "TRUNCATE stream_edge_bounds"); err != nil {
		t.Fatalf("원장 비우기: %v", err)
	}
	t.Cleanup(func() { _, _ = pool.Exec(t.Context(), "TRUNCATE stream_edge_bounds") })
	if _, err := pool.Exec(ctx,
		"INSERT INTO stream_edge_bounds (stream_id, round_id, dvr_floor) VALUES ('s1', 'R1', 3)"); err != nil {
		t.Fatalf("원장 행 넣기: %v", err)
	}
	logs := &logCapture{}

	observe, stop := startEdgeBounds(ctx, config.Config{}, pool, slog.New(logs))
	stop()

	if observe != nil {
		t.Error("ARN 빔인데 observe 가 있다")
	}
	if n := logs.countLevel(slog.LevelError, signalUnreleasedWithoutWriter); n != 1 {
		t.Fatalf("%s ERROR %d줄, want 1", signalUnreleasedWithoutWriter, n)
	}
	if a := logs.attrs(signalUnreleasedWithoutWriter); a["rows"] != int64(1) {
		t.Errorf("rows = %v, want 1", a["rows"])
	}

	if _, err := pool.Exec(ctx, "UPDATE stream_edge_bounds SET released_at = pg_catalog.now()"); err != nil {
		t.Fatalf("해제 적기: %v", err)
	}
	again := &logCapture{}
	_, stop = startEdgeBounds(ctx, config.Config{}, pool, slog.New(again))
	stop()
	if n := again.count(signalUnreleasedWithoutWriter); n != 0 {
		t.Errorf("해제된 행만 남았는데 %s %d줄, want 0", signalUnreleasedWithoutWriter, n)
	}
}
