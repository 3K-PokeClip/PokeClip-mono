package index

// 정산 스캔의 발행 흔적 판독 인덱스(POK-196 M6-3b 커밋 ④c — c3 db M1). endingRowSelectSQL 의 ③ 흔적 LATERAL(uploaded 한
// 행)은 그 세션 조각 가운데 uploaded 하나를 찾는다. (stream_id, session_id, seq) 인덱스로는 uploaded 가 없는 세션에서 그
// 세션 조각을 모두 읽는다 — 30초마다 ending 수 × 조각 수다(실측 300 × 5000 = 150만 행 460–810ms). 부분 인덱스
// stream_segments_uploaded_session_idx 는 uploaded 행만 담아 그 판독을 인덱스 탐색 한 번으로 줄인다(7.8ms).

import (
	"context"
	"slices"
	"strings"
	"testing"
)

// 인덱스가 ddl.go 정의대로 있다(운영 사본 · 40_verify 는 prod_sql 시험이 대조한다).
func TestUploadedTraceIndexDefinition(t *testing.T) {
	pool := newTestPool(t)
	var def string
	err := pool.QueryRow(context.Background(),
		`SELECT pg_catalog.pg_get_indexdef(pg_catalog.to_regclass('stream_segments_uploaded_session_idx'))`).Scan(&def)
	const want = "ON public.stream_segments USING btree (stream_id, session_id) WHERE (playback_upload_state = 'uploaded'::text)"
	if err != nil || !strings.HasSuffix(def, want) {
		t.Fatalf("인덱스 = (%q, %v), want 끝이 %q", def, err, want)
	}
}

// traceSeedSessions · traceSeedSegments 는 계획 시험의 장부 크기다 — ending 300 · 세션마다 조각 2000(uploaded 없음 — 판독이
// 가장 비싼 모양). 작은 표에서는 계획기가 순차 훑기를 고른다.
const (
	traceSeedSessions = 300
	traceSeedSegments = 2000
)

// 정산 스캔(listEndingSessionsSQL)의 ③ 흔적 판독이 부분 인덱스를 탄다. 변이 = 인덱스 제거 → FAIL.
func TestEndingScanTraceUsesUploadedIndex(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	if _, err := pool.Exec(ctx, `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, end_reason, ending_at)
SELECT 'tr-' || g, 'trace-' || g, now() - interval '2 hours', 'ending', 'offline', now() - interval '1 hour'
  FROM pg_catalog.generate_series(0, $1::int - 1) g`, traceSeedSessions); err != nil {
		t.Fatalf("세션 시드 실패: %v", err)
	}
	if _, err := pool.Exec(ctx, `
INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, session_id)
SELECT 'trace-' || s, g, g * 4000, now() - interval '2 hours' + g * interval '4 seconds', 4000, 'k/' || s || '/' || g, 'tr-' || s
  FROM pg_catalog.generate_series(0, $1::int - 1) s, pg_catalog.generate_series(0, $2::int - 1) g`,
		traceSeedSessions, traceSeedSegments); err != nil {
		t.Fatalf("조각 시드 실패: %v", err)
	}
	if _, err := pool.Exec(ctx, `ANALYZE stream_segments, stream_sessions`); err != nil {
		t.Fatalf("ANALYZE 실패: %v", err)
	}
	var plan []byte
	err := pool.QueryRow(ctx, `EXPLAIN (FORMAT JSON) `+listEndingSessionsSQL,
		endingArgs(ScanCursor{}, 50, testClose)...).Scan(&plan)
	if err != nil {
		t.Fatalf("EXPLAIN 실패: %v", err)
	}
	if nodes := planScanNodes(t, plan); !slices.Contains(nodes, "stream_segments_uploaded_session_idx") {
		t.Fatalf("정산 스캔의 ③ 흔적 판독이 stream_segments_uploaded_session_idx 를 타지 않는다 — 계획 노드 %v", nodes)
	}
}
