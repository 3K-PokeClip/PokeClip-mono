package main

// /dvr 하한 배선의 실 PG 시험(POK-525 계획 Ⓒ-6 · E10) — 운영 조립(startEdgeBounds → index.EdgeBoundsLedger)이 ARN 없이
// 뜨면 원장의 닫힌 미해제 행을 기동 때 ERROR 로 알리고, 유예(ReleaseSettle)가 지난 해제 행을 지운다(Ⓒ-6b). 실 AWS 는
// 부르지 않는다(ARN 빔 갈래).

import (
	"context"
	"log/slog"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
)

// edgeBoundsCleanupTimeout 은 시험 뒤 원장 비우기의 시한이다.
const edgeBoundsCleanupTimeout = 10 * time.Second

// truncateEdgeBounds 는 시험이 남긴 원장 행을 비운다(t.Cleanup 용). t.Context() 는 Cleanup 직전에 취소되므로 따로 시한을
// 둔 컨텍스트를 쓰고, 실패하면 시험을 실패로 둔다 — 남은 행이 다음 시험의 감시 신호를 바꾼다.
func truncateEdgeBounds(t *testing.T, pool *pgxpool.Pool) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), edgeBoundsCleanupTimeout)
	defer cancel()
	if _, err := pool.Exec(ctx, "TRUNCATE stream_edge_bounds"); err != nil {
		t.Errorf("원장 비우기(정리): %v", err)
	}
}

// 세션 행이 없는 원장 행(만료 · 탈퇴 — 닫힘으로 본다)이 있으면 기동 직후 set 한 줄(rows=1)이다. 그 행에 해제를 적은 뒤
// 다시 기동하면 줄이 없다 — 감시는 닫힌 미해제 행만 센다.
func TestStartEdgeBoundsWithoutARNReportsLedgerRowsPG(t *testing.T) {
	pool := newWireTestPool(t)
	ctx := t.Context()
	if _, err := pool.Exec(ctx, "TRUNCATE stream_edge_bounds"); err != nil {
		t.Fatalf("원장 비우기: %v", err)
	}
	t.Cleanup(func() { truncateEdgeBounds(t, pool) })
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

// edgeBoundsRow 는 원장 행 (stream, round) 의 하한 · 해제 여부다. 행이 없으면 found 거짓이다.
type edgeBoundsRow struct {
	found, released bool
	floor           int64
}

func readEdgeBoundsRow(ctx context.Context, t *testing.T, pool *pgxpool.Pool, streamID, roundID string) edgeBoundsRow {
	t.Helper()
	var r edgeBoundsRow
	err := pool.QueryRow(ctx, `
		SELECT pg_catalog.count(*) > 0, COALESCE(bool_or(released_at IS NOT NULL), false), COALESCE(max(dvr_floor), 0)
		  FROM stream_edge_bounds WHERE stream_id = $1 AND round_id = $2`, streamID, roundID).Scan(&r.found, &r.released, &r.floor)
	if err != nil {
		t.Fatalf("원장 행 읽기: %v", err)
	}
	return r
}

// Writer 없는 감시 한 번(기동 때 동기 확인)이 해제됐고 유예(120초)가 지난 행만 지운다. 유예 안의 해제 행 · 미해제 행은
// 남기고, 미해제 행은 올리지도 풀지도 않는다(하한 · released_at 그대로). 지운 수는 INFO rows 다.
func TestStartEdgeBoundsWithoutARNDeletesSettledReleasedRowsPG(t *testing.T) {
	pool := newWireTestPool(t)
	ctx := t.Context()
	if _, err := pool.Exec(ctx, "TRUNCATE stream_edge_bounds"); err != nil {
		t.Fatalf("원장 비우기: %v", err)
	}
	t.Cleanup(func() { truncateEdgeBounds(t, pool) })
	if _, err := pool.Exec(ctx, `
		INSERT INTO stream_edge_bounds (stream_id, round_id, dvr_floor, released_at) VALUES
		  ('s1', 'R-settled', 3, pg_catalog.now() - interval '200 seconds'),
		  ('s1', 'R-fresh', 4, pg_catalog.now() - interval '10 seconds'),
		  ('s1', 'R-open', 5, NULL)`); err != nil {
		t.Fatalf("원장 행 넣기: %v", err)
	}
	logs := &logCapture{}

	_, stop := startEdgeBounds(ctx, config.Config{}, pool, slog.New(logs))
	stop()

	if r := readEdgeBoundsRow(ctx, t, pool, "s1", "R-settled"); r.found {
		t.Errorf("유예가 지난 해제 행이 남았다 %+v — 판정식이 그 회차를 끝남 대기로 묶는다", r)
	}
	if r := readEdgeBoundsRow(ctx, t, pool, "s1", "R-fresh"); !r.found || !r.released || r.floor != 4 {
		t.Errorf("유예 안의 해제 행 = %+v, want 보존(released · floor 4)", r)
	}
	if r := readEdgeBoundsRow(ctx, t, pool, "s1", "R-open"); !r.found || r.released || r.floor != 5 {
		t.Errorf("미해제 행 = %+v, want 보존(미해제 · floor 5 — 올리지도 풀지도 않음)", r)
	}
	if n := logs.countLevel(slog.LevelInfo, signalReleasedRowsDeleted); n != 1 {
		t.Fatalf("%s INFO %d줄, want 1", signalReleasedRowsDeleted, n)
	}
	if a := logs.attrs(signalReleasedRowsDeleted); a["rows"] != int64(1) {
		t.Errorf("rows = %v, want 1", a["rows"])
	}
	if n := logs.count(signalUnreleasedCheckFailed); n != 0 {
		t.Errorf("%s %d줄, want 0", signalUnreleasedCheckFailed, n)
	}
}
