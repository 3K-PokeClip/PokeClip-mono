package index

// 회차 VOD 읽기의 media_indexer 권한 시험(POK-549 C1 · 계획 K7 · P2) — 판정 재료 · 회차 장부 · 깨우기 대상의 문장이
// 사이드카 계정 권한(SELECT)만으로 돌고, search_path 덫을 부르지 않는다(내장 함수는 pg_catalog 로 한정). 문장마다 역할
// 풀로 보내 성공했는지 추적기로 확인한다.
//
//	문장               메서드                               표 · 동작
//	roundGateSQL       LoadRoundGate · LoadRoundLedger      sessions · segments · cutoffs SELECT
//	roundLatestSQL     RoundLatestEnding                    sessions SELECT
//	chainMembersSQL · chainRangeSQL · chainRowsSQL · rewindCutoffSQL · rewindSessionsSQL  LoadRoundLedger 가 사슬 장부와 함께 쓴다

import (
	"context"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

func TestMediaIndexerRoleReadsRoundLedger(t *testing.T) {
	admin := newTestPool(t)
	traps := pgtest.PlantSearchPathTraps(t, admin)
	role := pgtest.NewMediaIndexerRole(t, admin)
	t.Cleanup(func() { role.RequireNoneDenied(t) })
	const stream = "role-round"
	putRoundSessions(t, admin, stream,
		roundSession{id: "G0", round: "G0", at: 0, closed: true, state: "ended", vodState: "frozen", vodKey: "vod/s/G0/index.m3u8"},
		roundSession{id: "G1", round: "G0", at: 100, closed: true},
	)
	putRoundRows(t, admin, stream, "G0", 1, 2)
	putRoundRows(t, admin, stream, "G1", 3, 4)
	cutoffAt(t, admin, stream, 1)
	ctx := context.Background()

	g, err := LoadRoundGate(ctx, role.Pool, stream, "G0")
	if err != nil || len(g.Members) != 2 || g.Latest != "G1" || !g.Closed {
		t.Fatalf("LoadRoundGate = (%+v, %v), want 회원 2 · 최신 G1 · 닫힘", g, err)
	}
	l, err := LoadRoundLedger(ctx, role.Pool, Chain{StreamID: stream, Tail: "G1", SessionIDs: []string{"G1"}}, roundTestMaxRows)
	if err != nil || len(l.Rows) != 4 || len(l.Sessions) != 2 {
		t.Fatalf("LoadRoundLedger = (행 %d · 세션 %d, %v), want (4 · 2, nil)", len(l.Rows), len(l.Sessions), err)
	}
	if id, ok, err := RoundLatestEnding(ctx, role.Pool, stream, "G0", []string{"G0"}); err != nil || !ok || id != "G1" {
		t.Fatalf("RoundLatestEnding = (%q, %v, %v), want (G1, 참, nil)", id, ok, err)
	}
	role.RequireSent(t, map[string]string{
		"roundGateSQL": roundGateSQL, "roundLatestSQL": roundLatestSQL, "chainMembersSQL": chainMembersSQL,
		"chainRangeSQL": chainRangeSQL, "chainRowsSQL": chainRowsSQL, "rewindCutoffSQL": rewindCutoffSQL,
		"rewindSessionsSQL": rewindSessionsSQL,
	})
	traps.RequireNotCalled(t)
}
