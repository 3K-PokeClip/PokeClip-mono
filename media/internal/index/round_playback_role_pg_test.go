package index

// 회차 상태 읽기 셋의 media_indexer 최소 권한 시험(POK-196 M6-3a 커밋 ④). 권한 목록 · 시험 꼴은
// media_indexer_role_pg_test.go 머리 주석과 같다 — 세 문장이 그 권한(sessions · segments SELECT)만으로 돌고 검색 경로
// 함정을 부르지 않는다.

import (
	"context"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

func TestRoundPlaybackReadsRunUnderMediaIndexerRole(t *testing.T) {
	admin := newTestPool(t)
	traps := pgtest.PlantSearchPathTraps(t, admin)
	role := pgtest.NewMediaIndexerRole(t, admin)
	t.Cleanup(func() { role.RequireNoneDenied(t) })
	now := time.Now().UTC()
	plantRound(t, admin, roundFixture{id: "R", stream: "s", state: "ended", roundID: "R", startedAt: now.Add(-time.Hour),
		endedAt: now.Add(-time.Minute), closedAt: now.Add(-time.Minute), vodState: "frozen", vodKey: "vod/s/R.m3u8", vodFirst: 1, vodLast: 1})
	plantPDT(t, admin, "s", "R", 1, now.Add(-time.Hour), 4000)
	ctx := context.Background()

	if st, err := LookupRoundPlayback(ctx, role.Pool, "s", "R"); err != nil || st.Phase != PhaseVOD {
		t.Errorf("LookupRoundPlayback = (%+v, %v), want VOD", st, err)
	}
	if st, ok, err := LoadStreamPlayback(ctx, role.Pool, "s"); err != nil || !ok || st.Phase != PhaseVOD {
		t.Errorf("LoadStreamPlayback = (%+v, %v, %v), want VOD", st, ok, err)
	}
	if all, err := LoadPlaybackStates(ctx, role.Pool, 30*time.Minute); err != nil || all["s"].Phase != PhaseVOD {
		t.Errorf("LoadPlaybackStates = (%v, %v), want s VOD", all, err)
	}
	role.RequireSent(t, map[string]string{
		"lookupRoundPlaybackSQL": lookupRoundPlaybackSQL, "loadStreamPlaybackSQL": loadStreamPlaybackSQL,
		"loadPlaybackStatesSQL": loadPlaybackStatesSQL,
	})
	traps.RequireNotCalled(t)
}
