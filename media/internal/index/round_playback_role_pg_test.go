package index

// 회차 상태 읽기 셋의 media_indexer 최소 권한 시험(POK-196 M6-3a 커밋 ④). 권한 목록 · 시험 꼴은
// media_indexer_role_pg_test.go 머리 주석과 같다 — 세 문장이 그 권한(sessions · segments · edge_bounds SELECT)만으로 돌고
// 검색 경로 함정을 부르지 않는다. edge_bounds 는 판정식의 하한 원장 줄(POK-525)이다 — 표가 비어도 실행 시작 때 문장 전체의 표 권한을 검사한다.
// 원장 행이 있는 회차(h)도 함께 심어, 그 줄이 행을 실제로 읽어 ending 을 내는 것까지 이 역할로 잰다.

import (
	"context"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

func TestRoundPlaybackReadsRunUnderMediaIndexerRole(t *testing.T) {
	admin := edgeBoundsPool(t)
	traps := pgtest.PlantSearchPathTraps(t, admin)
	role := pgtest.NewMediaIndexerRole(t, admin)
	t.Cleanup(func() { role.RequireNoneDenied(t) })
	now := time.Now().UTC()
	plantRound(t, admin, roundFixture{id: "R", stream: "s", state: "ended", roundID: "R", startedAt: now.Add(-time.Hour),
		endedAt: now.Add(-time.Minute), closedAt: now.Add(-time.Minute), vodState: "frozen", vodKey: "vod/s/R.m3u8", vodFirst: 1, vodLast: 1})
	plantPDT(t, admin, "s", "R", 1, now.Add(-time.Hour), 4000)
	plantFrozenRound(t, admin, "h", "H")
	plantEdgeBoundsRow(t, admin, "h", "H", time.Time{})
	ctx := context.Background()

	if st, err := LookupRoundPlayback(ctx, role.Pool, "s", "R"); err != nil || st.Phase != PhaseVOD {
		t.Errorf("LookupRoundPlayback = (%+v, %v), want VOD", st, err)
	}
	if st, ok, err := LoadStreamPlayback(ctx, role.Pool, "s"); err != nil || !ok || st.Phase != PhaseVOD {
		t.Errorf("LoadStreamPlayback = (%+v, %v, %v), want VOD", st, ok, err)
	}
	if st, err := LookupRoundPlayback(ctx, role.Pool, "h", "H"); err != nil || st.Phase != PhaseEnding {
		t.Errorf("원장 행 회차 LookupRoundPlayback = (%+v, %v), want Ending", st, err)
	}
	if st, ok, err := LoadStreamPlayback(ctx, role.Pool, "h"); err != nil || !ok || st.Phase != PhaseEnding {
		t.Errorf("원장 행 회차 LoadStreamPlayback = (%+v, %v, %v), want Ending", st, ok, err)
	}
	all, err := LoadPlaybackStates(ctx, role.Pool, 30*time.Minute)
	if err != nil || all["s"].Phase != PhaseVOD || all["h"].Phase != PhaseEnding {
		t.Errorf("LoadPlaybackStates = (%v, %v), want s VOD · h Ending", all, err)
	}
	role.RequireSent(t, map[string]string{
		"lookupRoundPlaybackSQL": lookupRoundPlaybackSQL, "loadStreamPlaybackSQL": loadStreamPlaybackSQL,
		"loadPlaybackStatesSQL": loadPlaybackStatesSQL,
	})
	traps.RequireNotCalled(t)
}

// 원장 SELECT 가 없는 계정(app_clip — 세션 · 조각 SELECT 는 있다)은 원장이 비어도 세 읽기 모두 42501 이다 — 판정식의 원장
// 줄 때문이다(round_playback.go 머리 주석). 이 읽기를 다른 계정에 다시 쓰면 여기서 드러난다.
func TestRoundPlaybackReadsDeniedWithoutEdgeBoundsSelect(t *testing.T) {
	admin := edgeBoundsPool(t)
	clip := clipRolePool(t, admin)
	plantFrozenRound(t, admin, "s", "R")
	ctx := context.Background()

	_, lookupErr := LookupRoundPlayback(ctx, clip, "s", "R")
	_, _, streamErr := LoadStreamPlayback(ctx, clip, "s")
	_, loadErr := LoadPlaybackStates(ctx, clip, 30*time.Minute)

	for name, err := range map[string]error{
		"LookupRoundPlayback": lookupErr, "LoadStreamPlayback": streamErr, "LoadPlaybackStates": loadErr,
	} {
		if !pgtest.IsInsufficientPrivilege(err) {
			t.Errorf("app_clip 의 %s 오류 = %v, want 42501", name, err)
		}
	}
	if _, err := clip.Exec(ctx, "SELECT count(*) FROM stream_sessions"); err != nil {
		t.Errorf("app_clip 의 세션 SELECT 오류 = %v, want nil(42501 은 원장 표 몫이어야 한다)", err)
	}
}
