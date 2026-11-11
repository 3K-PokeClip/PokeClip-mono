package index

// 판 2 섞기 경로의 새 문장(p 조회 — prevRoundRowSQL)이 media_indexer 권한으로 돈다(POK-196 PDT 생산자 c4b · 설계 r7 9.3
// 권한 · media_indexer_role_pg_test.go 와 같은 꼴). PG_DSN 미설정이면 skip 이다.

import (
	"context"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// 판 2 회차를 열고 이어 쓰는 행 · 회차 안 비귀속 행이 media_indexer 권한만으로 들어가고 p 조회를 보낸다. 앱 사용자가
// public 에 심은 search_path 덫은 불리지 않는다.
func TestMediaIndexerRoleWritesV2Rows(t *testing.T) {
	admin := newTestPool(t)
	traps := pgtest.PlantSearchPathTraps(t, admin)
	role := pgtest.NewMediaIndexerRole(t, admin)
	t.Cleanup(func() { role.RequireNoneDenied(t) })
	store := newSessionStore(role.Pool)
	ctx := context.Background()
	stream := "role-v2"
	setGateSwitch(t, admin, &versionTwo)

	mustInsert(t, store, v2Seg(stream, 0, v2At(0), v2Facts(recorderX, 10, v2At(0)), true), liveIngress())
	cont := mustInsert(t, store, v2Seg(stream, 1, v2At(4000), v2Facts(recorderX, 11, v2At(4000)), true), liveIngress())
	if _, err := EndLive(ctx, role.Pool, stream, testClose, noopEnqueue); err != nil {
		t.Fatalf("EndLive 실패: %v", err)
	}
	u := mustInsert(t, store, v2Seg(stream, 2, v2At(8000), v2Facts(recorderX, 12, v2At(8000)), true), currentOnly())
	if cont.TimingBranch != BranchJoin || u.TimingBranch != BranchJoin || u.SessionID != "" {
		t.Errorf("(계속 갈래, 비귀속 갈래, 비귀속 세션) = (%s, %s, %q), want (join, join, \"\")", cont.TimingBranch, u.TimingBranch, u.SessionID)
	}
	role.RequireSent(t, map[string]string{"prevRoundRowSQL": prevRoundRowSQL, "lastAttributedSQL": lastAttributedSQL})
	traps.RequireNotCalled(t)
}
