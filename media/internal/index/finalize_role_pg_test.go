package index

// 동결 렌더 장부 읽기의 media_indexer 권한 시험(POK-196 M6-1 커밋 ④) — 사슬 장부 · 회차 장부의 문장이 사이드카 계정
// 권한(SELECT)만으로 돌고, search_path 덫을 부르지 않는다(내장 함수는 pg_catalog 로 한정). 문장마다 역할 풀로 보내
// 성공했는지 추적기로 확인한다.
//
//	문장               메서드              표 · 동작
//	chainMembersSQL    LoadChainLedger     sessions SELECT(재귀)
//	chainRangeSQL      LoadChainLedger     segments SELECT
//	chainRowsSQL       LoadChainLedger     segments · gaps SELECT
//	sessionHeadSQL     LoadSessionLedger   segments SELECT
//	rewindCutoffSQL · rewindFloorSQL · rewindRowsSQL · rewindSessionsSQL  두 메서드가 함께 쓴다

import (
	"context"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

func TestMediaIndexerRoleReadsFreezeLedgers(t *testing.T) {
	admin := newTestPool(t)
	traps := pgtest.PlantSearchPathTraps(t, admin)
	role := pgtest.NewMediaIndexerRole(t, admin)
	t.Cleanup(func() { role.RequireNoneDenied(t) })
	const stream = "role-freeze"
	session := roleInsertSeries(t, newSessionStore(role.Pool), stream, roleBase(), 3)
	ctx := context.Background()

	c, err := LoadChainLedger(ctx, role.Pool, Chain{StreamID: stream, Tail: session}, chainTestMaxRows)
	if err != nil || len(c.Rows) != 3 || len(c.Sessions) != 1 || !c.HasCutoff {
		t.Fatalf("LoadChainLedger = (행 %d · 회차 %d · 컷오프 %v, %v), want (3 · 1 · 참, nil)", len(c.Rows), len(c.Sessions), c.HasCutoff, err)
	}
	s, err := LoadSessionLedger(ctx, role.Pool, stream, session, LedgerBounds{Lookback: time.Hour, RowCap: 100})
	if err != nil || len(s.Rows) != 3 || len(s.Sessions) != 1 {
		t.Fatalf("LoadSessionLedger = (행 %d · 회차 %d, %v), want (3 · 1, nil)", len(s.Rows), len(s.Sessions), err)
	}
	role.RequireSent(t, map[string]string{
		"chainMembersSQL": chainMembersSQL, "chainRangeSQL": chainRangeSQL, "chainRowsSQL": chainRowsSQL,
		"sessionHeadSQL": sessionHeadSQL, "rewindCutoffSQL": rewindCutoffSQL, "rewindFloorSQL": rewindFloorSQL,
		"rewindRowsSQL": rewindRowsSQL, "rewindSessionsSQL": rewindSessionsSQL,
	})
	traps.RequireNotCalled(t)
}
