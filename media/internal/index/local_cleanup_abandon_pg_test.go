package index

// janitor ③ 포기 갈래(POK-196 M6-4 c3 · 설계 7절 X5 · D52) PG 통합 시험. ③ 이 끝내 안 올라 vod_abandoned 로 등재된
// 조각은 세션 ended 뒤 24시간이 지나면 ② uploaded 일 때 지운다 — 재추출 기회가 다 지난 뒤다. ② 도 안 올라 지울
// 수 없는 행은 abandoned_local_retained 신호의 재료로 센다.

import (
	"context"
	"slices"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// lcAbandon 은 판정 시험의 포기 고정물이다 — 세션(ended · endedAgo 전 종료) · 컷오프 0 · seq 0(컷오프 행 · ③
// uploaded) · seq 1(③ pending · GAP 등재 상태 gapState) · ② 상태 upload.
func lcAbandon(t *testing.T, pool *pgxpool.Pool, stream string, endedAgo time.Duration, gapState, upload string) {
	t.Helper()
	ctx := context.Background()
	session := stream + "-s"
	lcPutSession(t, pool, lcSessionRow{id: session, stream: stream, state: "ended",
		endingAt: time.Now().UTC().Add(-endedAgo), endedAt: time.Now().UTC().Add(-endedAgo), initUploaded: true})
	lcPut(t, pool,
		lcRow{stream: stream, seq: 0, session: session, playback: "uploaded", playbackUploadedAt: time.Now().UTC().Add(-time.Hour)},
		lcRow{stream: stream, seq: 1, session: session, upload: upload})
	lcPutCutoff(t, pool, stream, 0)
	if _, err := pool.Exec(ctx, `INSERT INTO stream_published_gaps (stream_id, seq, session_id, reason, published_state)
	                             VALUES ($1, 1, $2, 'settle_deadline', $3)`, stream, session, gapState); err != nil {
		t.Fatalf("GAP 픽스처: %v", err)
	}
}

// lcAbandonPolicy 는 보호 ②(재접속 창)가 고정물을 막지 않는 정책이다 — 세션이 1시간 넘게 전에 끝났다.
var lcAbandonPolicy = LocalCleanupPolicy{Grace: time.Minute, ReconnectWindow: time.Minute, ExternalDeleteAge: 24 * time.Hour}

func lcDeletableSeqs(t *testing.T, pool *pgxpool.Pool, stream string) map[int64]bool {
	t.Helper()
	segs, _, err := NewLocalCleanupStore(pool).DeletableSegments(context.Background(), lcAbandonPolicy, 100, LocalCursor{})
	if err != nil {
		t.Fatalf("DeletableSegments: %v", err)
	}
	out := map[int64]bool{}
	for _, s := range segs {
		if s.StreamID == stream {
			out[s.Seq] = s.Abandoned
		}
	}
	return out
}

// D52 — 포기 등재 ∧ 세션 ended + 24시간 ∧ ② uploaded 면 ③ 없이도 지운다(Abandoned 표지). 하나라도 빠지면 남긴다.
func TestLocalCleanupAbandonedBranch(t *testing.T) {
	pool := newTestPool(t)
	cases := []struct {
		name     string
		endedAgo time.Duration
		gapState string
		upload   string
		want     bool
	}{
		{"포기 · 25시간 · ② uploaded → 지움", 25 * time.Hour, "vod_abandoned", "uploaded", true},
		{"포기 · 23시간 → 남김(재추출 기회)", 23 * time.Hour, "vod_abandoned", "uploaded", false},
		{"GAP recorded → 남김(포기 아님)", 25 * time.Hour, "recorded", "uploaded", false},
		{"② pending → 남김(② 먼저 금지)", 25 * time.Hour, "vod_abandoned", "pending", false},
	}
	for i, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			stream := "lcab-" + string(rune('a'+i))
			lcAbandon(t, pool, stream, c.endedAgo, c.gapState, c.upload)
			got := lcDeletableSeqs(t, pool, stream)
			abandoned, ok := got[1]
			if ok != c.want {
				t.Fatalf("seq 1 후보=%v (기대 %v) 전체 %v", ok, c.want, got)
			}
			if ok && !abandoned {
				t.Fatal("포기 갈래로 고른 행에 Abandoned 표지가 없다")
			}
			if a, ok := got[0]; !ok || a {
				t.Fatalf("③ uploaded 컷오프 행: 후보=%v Abandoned=%v (후보 · 표지 없음 기대)", ok, a)
			}
		})
	}
}

// 포기 갈래도 무효 컷오프 정지(fail-closed)를 따른다 — e1 의 로컬 축.
func TestLocalCleanupAbandonedBranchStopsOnInvalidCutoff(t *testing.T) {
	pool := newTestPool(t)
	stream := "lcab-invalid"
	lcAbandon(t, pool, stream, 25*time.Hour, "vod_abandoned", "uploaded")
	if _, err := pool.Exec(context.Background(), `UPDATE stream_cutoffs SET cutoff_seq = 99 WHERE stream_id = $1`, stream); err != nil {
		t.Fatalf("컷오프 무효화: %v", err)
	}
	if got := lcDeletableSeqs(t, pool, stream); len(got) != 0 {
		t.Fatalf("무효 컷오프 스트림에 후보가 있다: %v", got)
	}
}

// abandoned_local_retained 재료 — 포기 등재 ∧ ended + 24시간인데 ② 가 안 올라 지울 수 없는 행 수.
func TestAbandonedLocalRetainedCountsUndeletable(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	store := NewLocalCleanupStore(pool)
	lcAbandon(t, pool, "lcab-ret-a", 25*time.Hour, "vod_abandoned", "pending")
	lcAbandon(t, pool, "lcab-ret-b", 25*time.Hour, "vod_abandoned", "uploaded") // 지울 수 있으니 세지 않는다
	lcAbandon(t, pool, "lcab-ret-c", 23*time.Hour, "vod_abandoned", "pending")  // 24시간 전이라 세지 않는다
	n, err := store.AbandonedLocalRetained(ctx)
	if err != nil || n != 1 {
		t.Fatalf("AbandonedLocalRetained = %d err=%v (1 기대)", n, err)
	}
}

// janitor 계정(media_janitor 권한) 안에서 포기 갈래와 잔존 집계가 돈다 — GAP 원장 SELECT 선부여로 충분하다.
func TestLocalCleanupAbandonedUnderJanitorRole(t *testing.T) {
	admin := newTestPool(t)
	ctx := context.Background()
	lcAbandon(t, admin, "lcab-role", 25*time.Hour, "vod_abandoned", "uploaded")
	pool, _ := lcRolePool(t, admin)
	store := NewLocalCleanupStore(pool)
	segs, _, err := store.DeletableSegments(ctx, lcAbandonPolicy, 100, LocalCursor{})
	if err != nil {
		t.Fatalf("역할 DeletableSegments: %v", err)
	}
	if !slices.ContainsFunc(segs, func(s LocalSegment) bool { return s.StreamID == "lcab-role" && s.Seq == 1 && s.Abandoned }) {
		t.Fatalf("역할 실행에서 포기 행이 안 나왔다: %+v", segs)
	}
	if _, err := store.AbandonedLocalRetained(ctx); err != nil {
		t.Fatalf("역할 AbandonedLocalRetained: %v", err)
	}
}

// retainedSeedRows 는 잔존 집계 계획 시험이 넣는 조각 행 수다(작은 표는 계획기가 순차 훑기를 고른다).
const retainedSeedRows = 300_000

// db m64 M1 — 잔존 집계(janitor 주기마다)는 조각 표를 훑지 않는다. 계획기는 포기 등재(GAP)를 모아 조각 행을 기본
// 키로 찾는다. 시드는 로컬 파일이 하나도 안 지워진 꼴(정리가 멈춘 때 — 이 신호가 쓸모 있는 때)이다. 포기 등재가
// 조각의 10% 쯤이면 계획기가 해시 조인(조각 표 훑기)으로 바꾼다 — 그 비율은 등재가 드물다는 전제 밖이다.
func TestAbandonedLocalRetainedUsesSegmentKey(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	if _, err := pool.Exec(ctx, `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, ended_at)
VALUES ('rt-s', 'lctest-rt', now() - interval '3 days', 'ended', now() - interval '2 days')`); err != nil {
		t.Fatalf("세션 시드 실패: %v", err)
	}
	if _, err := pool.Exec(ctx, `
INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, session_id, upload_state,
                             local_path)
SELECT 'lctest-rt', g, g * 4000, now() - interval '3 days' + g * interval '4 seconds', 4000, 'k/' || g, 'rt-s',
       'uploaded', 'l/' || g
  FROM pg_catalog.generate_series(0, $1::bigint - 1) g`, retainedSeedRows); err != nil {
		t.Fatalf("조각 시드 실패: %v", err)
	}
	if _, err := pool.Exec(ctx, `
INSERT INTO stream_published_gaps (stream_id, seq, session_id, reason, published_state)
SELECT 'lctest-rt', g, 'rt-s', 'upload_stall', 'vod_abandoned' FROM pg_catalog.generate_series(0, 9) g`); err != nil {
		t.Fatalf("GAP 시드 실패: %v", err)
	}
	if _, err := pool.Exec(ctx, `ANALYZE stream_segments; ANALYZE stream_published_gaps; ANALYZE stream_sessions`); err != nil {
		t.Fatalf("ANALYZE 실패: %v", err)
	}
	var plan []byte
	err := pool.QueryRow(ctx, `EXPLAIN (FORMAT JSON) `+abandonedLocalRetainedSQL, AbandonedLocalRetention.Seconds()).Scan(&plan)
	if err != nil {
		t.Fatalf("EXPLAIN 실패: %v", err)
	}
	if nodes := planScanNodes(t, plan); !slices.Contains(nodes, "stream_segments_pkey") {
		t.Fatalf("잔존 집계가 조각 표를 기본 키로 찾지 않는다 — 계획 노드 %v", nodes)
	}
}
