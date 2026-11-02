package index

// 60일 만료 장부 쪽의 방어(POK-196 M6-4 c4) PG 통합 시험 — 0단계 상한 이중 방어 · 열린 세션 제외(보안 H1) ·
// 문서 사슬의 회차 간선(chainEdgeSQL — db H2) · FK 인덱스(db H1). PG_DSN 이 없으면 skip(REQUIRE_PG=1 이면 실패).

import (
	"context"
	"encoding/json"
	"slices"
	"strings"
	"testing"
	"time"
)

// 보안 H1 — clip 응답의 상한이 보존 기간 안을 가리켜도 0단계는 보존 기간이 안 된 조각의 세션을 표시하지 않고,
// ended 가 아닌 세션도 표시하지 않는다. 보존 기간을 넘긴 ended 세션만 표시한다.
func TestMarkExpiredIgnoresRecentRowsAndOpenSessions(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	stream := "exptest-guard"
	now := time.Now().UTC()
	expPutSession(t, pool, expSessionRow{id: "gd-old", stream: stream})
	expPutSession(t, pool, expSessionRow{id: "gd-recent", stream: stream, at: 1})
	expPutSession(t, pool, expSessionRow{id: "gd-live", stream: stream, state: "live", at: 2})
	expPutSeg(t, pool,
		expSegRow{stream: stream, seq: 0, session: "gd-old"},
		expSegRow{stream: stream, seq: 1, session: "gd-live"},
		expSegRow{stream: stream, seq: 2, session: "gd-recent", start: now.Add(-time.Hour)},
	)

	for _, until := range []time.Time{now.Add(24 * time.Hour), now.Add(-time.Minute)} {
		if _, err := MarkExpired(ctx, pool, expRange(stream, nil, until)); err != nil {
			t.Fatalf("MarkExpired(until=%v): %v", until, err)
		}
	}

	marked := expCount(t, pool, `SELECT count(*) FROM stream_sessions WHERE stream_id = $1 AND expired_at IS NOT NULL`, stream)
	old := expCount(t, pool, `SELECT count(*) FROM stream_sessions WHERE session_id = 'gd-old' AND expired_at IS NOT NULL`)
	if marked != 1 || old != 1 {
		t.Fatalf("표시된 세션 %d개 · gd-old %d (gd-old 하나만 기대 — 최근 조각 · live 세션은 표시하지 않는다)", marked, old)
	}
}

// ExpiryCeiling 은 보존 기간(60일)에서 여유(1일)를 뺀 시각이다 — 그보다 늦은 상한은 받지 않는다.
func TestExpiryCeilingIsRetentionMinusSlack(t *testing.T) {
	now := time.Date(2026, 10, 26, 12, 0, 0, 0, time.UTC)
	if got, want := ExpiryCeiling(now), now.Add(-59*24*time.Hour); !got.Equal(want) {
		t.Fatalf("ExpiryCeiling = %v, want %v", got, want)
	}
}

// db H2 — 문서 사슬은 chainEdgeSQL 로 잇는다. 같은 회차(또는 M6 전 NULL 회차) 계승만 따라가고, 늦은 회차 분리가
// 만든 회차를 가로지르는 간선은 앞(c→p) · 뒤(p→c) 어느 쪽으로도 따라가지 않는다.
func TestExpiryDocumentsChainSkipsCrossRoundEdges(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	stream := "exptest-xround"
	expPutSession(t, pool, expSessionRow{id: "xr-a", stream: stream, round: "xr-a"})
	expPutSession(t, pool, expSessionRow{id: "xr-same", stream: stream, round: "xr-a", inherits: "xr-a", vodKey: "vod/same", at: 1})
	expPutSession(t, pool, expSessionRow{id: "xr-b", stream: stream, round: "xr-b", inherits: "xr-a", vodKey: "vod/b", at: 2})
	expPutSession(t, pool, expSessionRow{id: "xr-p", stream: stream, round: "xr-p", vodKey: "vod/p", at: 3})
	expPutSession(t, pool, expSessionRow{id: "xr-c", stream: stream, round: "xr-c", inherits: "xr-p", at: 4})
	expPutSeg(t, pool,
		expSegRow{stream: stream, seq: 0, session: "xr-a"},
		expSegRow{stream: stream, seq: 1, session: "xr-same"},
		expSegRow{stream: stream, seq: 2, session: "xr-b"},
		expSegRow{stream: stream, seq: 3, session: "xr-p"},
		expSegRow{stream: stream, seq: 4, session: "xr-c"},
	)

	// 뒤 방향 — 범위가 xr-a 만 덮는다. 같은 회차의 후속(xr-same)은 사슬, 다른 회차의 후속(xr-b)은 아니다.
	got, err := ExpiryDocuments(ctx, pool, expRange(stream, nil, expAt(1)))
	if err != nil {
		t.Fatalf("ExpiryDocuments(뒤): %v", err)
	}
	if want := []ExpiryDocument{{Kind: ExpiryDocVOD, SessionID: "xr-same", Key: "vod/same"}}; !slices.Equal(got, want) {
		t.Fatalf("뒤 방향 문서\n got  %+v\n want %+v", got, want)
	}

	// 앞 방향 — 범위가 xr-c 만 덮는다. 다른 회차의 부모(xr-p)는 사슬이 아니다.
	from := expAt(4)
	got, err = ExpiryDocuments(ctx, pool, expRange(stream, &from, expAt(5)))
	if err != nil {
		t.Fatalf("ExpiryDocuments(앞): %v", err)
	}
	if len(got) != 0 {
		t.Fatalf("앞 방향 문서 %+v (빈 목록 기대 — 회차를 가로지르는 부모)", got)
	}
}

// fkIndexes 는 만료 삭제 트랜잭션의 FK 검사(RI)가 타는 인덱스다(db H1 — 승인 표면 아님).
var fkIndexes = []struct{ name, def string }{
	{"stream_segments_session_id_idx", "ON public.stream_segments USING btree (session_id) WHERE (session_id IS NOT NULL)"},
	{"stream_published_gaps_session_idx", "ON public.stream_published_gaps USING btree (session_id)"},
	{"stream_sessions_inherits_idx", "ON public.stream_sessions USING btree (inherits_session) WHERE (inherits_session IS NOT NULL)"},
}

// db H1 — 세션 행을 지울 때 FK 검사가 참조하는 표를 훑지 않도록 FK 열 인덱스 셋이 있다.
func TestFKIndexesExist(t *testing.T) {
	pool := newTestPool(t)
	for _, ix := range fkIndexes {
		var def string
		err := pool.QueryRow(context.Background(), `SELECT pg_catalog.pg_get_indexdef(pg_catalog.to_regclass($1))`, ix.name).Scan(&def)
		if err != nil || !strings.HasSuffix(def, ix.def) {
			t.Errorf("인덱스 %s = (%q, %v), want 끝이 %q", ix.name, def, err, ix.def)
		}
	}
}

// fkSeedRows 는 RI 계획 시험이 넣는 조각 행 수다(100만 — 작은 표에서는 계획기가 순차 훑기를 고른다).
const fkSeedRows = 1_000_000

// db H1 — 100만 행 장부에서 세션 행을 지울 때의 RI 질의(참조 행 KEY SHARE 잠금)가 인덱스로 간다. 인덱스가 없으면
// 순차 훑기다 — 500행 묶음마다 빈 세션 수만큼 100만 행을 훑는다.
func TestStreamSegmentsSessionRICheckUsesIndex(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	if _, err := pool.Exec(ctx, `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, ended_at)
SELECT 'ri-' || g, 'exptest-ri', now() - interval '70 days', 'ended', now() - interval '70 days'
  FROM pg_catalog.generate_series(0, 999) g`); err != nil {
		t.Fatalf("세션 시드 실패: %v", err)
	}
	if _, err := pool.Exec(ctx, `
INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, session_id)
SELECT 'exptest-ri', g, g * 4000, now() - interval '70 days' + g * interval '4 seconds', 4000, 'k/' || g,
       'ri-' || (g / 1000)
  FROM pg_catalog.generate_series(0, $1::bigint - 1) g`, fkSeedRows); err != nil {
		t.Fatalf("조각 시드 실패: %v", err)
	}
	if _, err := pool.Exec(ctx, `ANALYZE stream_segments`); err != nil {
		t.Fatalf("ANALYZE 실패: %v", err)
	}

	// RI 검사(ri_triggers.c RI_PLAN_CHECK_LOOKUPPK 의 참조 쪽 — 부모 삭제 때)와 같은 꼴이다.
	var plan []byte
	err := pool.QueryRow(ctx, `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT 1 FROM ONLY public.stream_segments x WHERE 'ri-500' OPERATOR(pg_catalog.=) session_id FOR KEY SHARE OF x`).Scan(&plan)
	if err != nil {
		t.Fatalf("EXPLAIN 실패: %v", err)
	}
	if nodes := planScanNodes(t, plan); !slices.Contains(nodes, "stream_segments_session_id_idx") {
		t.Fatalf("RI 질의가 stream_segments_session_id_idx 를 타지 않는다 — 계획 노드 %v", nodes)
	}
}

// planScanNodes 는 EXPLAIN JSON 계획에서 「노드 종류」와 「인덱스 이름」을 모은다.
func planScanNodes(t *testing.T, raw []byte) []string {
	t.Helper()
	var doc []struct {
		Plan map[string]any `json:"Plan"`
	}
	if err := json.Unmarshal(raw, &doc); err != nil || len(doc) != 1 {
		t.Fatalf("EXPLAIN JSON 해석 실패(%d): %v", len(doc), err)
	}
	var out []string
	var walk func(n map[string]any)
	walk = func(n map[string]any) {
		if s, ok := n["Node Type"].(string); ok {
			out = append(out, s)
		}
		if s, ok := n["Index Name"].(string); ok {
			out = append(out, s)
		}
		kids, _ := n["Plans"].([]any)
		for _, k := range kids {
			if m, ok := k.(map[string]any); ok {
				walk(m)
			}
		}
	}
	walk(doc[0].Plan)
	return out
}

// orphanOutboxSeedSessions 는 고아 outbox 삭제 계획 시험이 넣는 세션 행 수다(작은 표는 계획기가 순차 훑기를 고른다).
const orphanOutboxSeedSessions = 200_000

// db m68 M3 — 만료의 고아 outbox 삭제가 세션 표를 스트림 인덱스로 본다. 닫힌 회차에는 round_id 부분 인덱스
// (round_closed_at IS NULL)를 쓸 수 없어, 스트림 조건이 없으면 만료 트랜잭션이 잠금을 쥔 채 세션 표 전체를 훑는다.
func TestExpiryOrphanOutboxDeleteUsesStreamIndex(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	if _, err := pool.Exec(ctx, `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, ended_at, round_id, round_closed_at)
SELECT 'ob-' || g, 'exptest-ob-' || (g % 5000), now() - interval '70 days', 'ended', now() - interval '70 days',
       'ob-' || g, now() - interval '70 days'
  FROM pg_catalog.generate_series(0, $1::bigint - 1) g`, orphanOutboxSeedSessions); err != nil {
		t.Fatalf("세션 시드 실패: %v", err)
	}
	if _, err := pool.Exec(ctx, `ANALYZE stream_sessions`); err != nil {
		t.Fatalf("ANALYZE 실패: %v", err)
	}
	var plan []byte
	err := pool.QueryRow(ctx, `EXPLAIN (FORMAT JSON) `+expiryDeleteOutboxSQL,
		[]string{"ob-gone-1", "ob-gone-2"}).Scan(&plan)
	if err != nil {
		t.Fatalf("EXPLAIN 실패: %v", err)
	}
	nodes := planScanNodes(t, plan)
	if !slices.Contains(nodes, "stream_sessions_stream_idx") {
		t.Fatalf("고아 outbox 삭제가 stream_sessions_stream_idx 를 타지 않는다 — 계획 노드 %v", nodes)
	}
}
