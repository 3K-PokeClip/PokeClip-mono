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
