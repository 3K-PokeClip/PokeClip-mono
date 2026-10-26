package index

// 종료 문장의 media_indexer 권한 시험(POK-196 M6-3b 커밋 ①) — 정산 스캔 · 빠른 길 · 사슬 닫힘 · phase 4 · 꺼짐 빈 동결 ·
// VOD 실패 확정 · 회차 스캔 · 회차 닫힘 · L3 종료(커밋 ④)가 사이드카 계정 권한(stream_sessions SELECT · UPDATE · stream_segments SELECT ·
// advisory 잠금)만으로 돌고 search_path 덫을 부르지 않는다. 문장마다 역할 풀로 보냈는지 추적기로 확인한다.

import (
	"context"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

func TestMediaIndexerRoleRunsEndingStatements(t *testing.T) {
	admin := newTestPool(t)
	traps := pgtest.PlantSearchPathTraps(t, admin)
	role := pgtest.NewMediaIndexerRole(t, admin)
	t.Cleanup(func() { role.RequireNoneDenied(t) })
	ctx := context.Background()
	old := time.Now().Add(-time.Hour)
	putEnd(t, admin, endRow{id: "E1", stream: "role-end", endReason: "intentional", round: "R1", initUploaded: true, startedAt: old})
	putSeg(t, admin, "role-end", "E1", 1, old, false)
	putEnd(t, admin, endRow{id: "E2", stream: "role-fail", dvr: "frozen", vod: "freezing", round: "R2", roundClosedAt: old, initUploaded: true, startedAt: old})
	putEnd(t, admin, endRow{id: "E3", stream: "role-round", state: "ended", dvr: "frozen", vod: "frozen", endReason: "offline", round: "R3", startedAt: old})
	putEnd(t, admin, endRow{id: "G1", stream: "role-gap", state: "live", round: "RG", startedAt: old})

	rows, _, err := ListEndingSessions(ctx, role.Pool, ScanCursor{}, 50, testClose)
	if err != nil || len(rows) != 2 {
		t.Fatalf("ListEndingSessions = %d 행 %v, want 2(E1 dvr open · E2 vod freezing)", len(rows), err)
	}
	if _, found, err := LoadEndingSession(ctx, role.Pool, "E1"); err != nil || !found {
		t.Fatalf("LoadEndingSession(E1) = %v %v", found, err)
	}
	if ok, err := FreezeEmptyDVR(ctx, role.Pool, "E1"); err != nil || !ok {
		t.Fatalf("FreezeEmptyDVR(E1) = %v %v", ok, err)
	}
	c, ok, err := CloseChain(ctx, role.Pool, "E1", testClose)
	if err != nil || !ok {
		t.Fatalf("CloseChain(E1) = %v %v", ok, err)
	}
	if ok, err := FreezeEmptyVOD(ctx, role.Pool, c); err != nil || !ok {
		t.Fatalf("FreezeEmptyVOD = %v %v", ok, err)
	}
	var rec recordEnqueue
	if n, err := MarkChainEnded(ctx, role.Pool, c, testClose, rec.fn); err != nil || n != 1 {
		t.Fatalf("MarkChainEnded = %d %v", n, err)
	}
	c2, err := LoadChain(ctx, role.Pool, "role-fail", "E2")
	if err != nil {
		t.Fatal(err)
	}
	if ok, err := FailChainVOD(ctx, role.Pool, c2); err != nil || !ok {
		t.Fatalf("FailChainVOD = %v %v", ok, err)
	}
	rounds, err := ListClosableRounds(ctx, role.Pool, 50, testClose)
	if err != nil || len(rounds) != 1 || rounds[0].RoundID != "R3" {
		t.Fatalf("ListClosableRounds = %+v %v, want [R3]", rounds, err)
	}
	if closed, err := CloseRound(ctx, role.Pool, rounds[0], testClose, rec.fn); err != nil || !closed {
		t.Fatalf("CloseRound(R3) = %v %v", closed, err)
	}
	if ended, err := EndGapRun(ctx, role.Pool, "role-gap", "G1", testClose, rec.fn); err != nil || ended != "G1" {
		t.Fatalf("EndGapRun(G1) = %q %v", ended, err)
	}
	role.RequireSent(t, map[string]string{
		"listEndingSessionsSQL": listEndingSessionsSQL, "loadEndingSessionSQL": loadEndingSessionSQL,
		"freezeEmptyDVRSQL": freezeEmptyDVRSQL, "sessionStreamSQL": sessionStreamSQL, "setTxnLimitsSQL": setTxnLimitsSQL,
		"topologyLockSQL": topologyLockSQL, "lockSessionSQL": lockSessionSQL, "lockSessionsSQL": lockSessionsSQL,
		"chainMembersSQL": chainMembersSQL, "chainMembersReadySQL": chainMembersReadySQL, "closeChainSQL": closeChainSQL,
		"freezeEmptyVODSQL": freezeEmptyVODSQL, "membershipSQL": membershipSQL, "chainStatesSQL": chainStatesSQL,
		"markEndedSQL": markEndedSQL, "roundStreamSQL": roundStreamSQL, "closableRoundsSQL": closableRoundsSQL,
		"lockRoundRowsSQL": lockRoundRowsSQL, "closeRoundSQL": closeRoundSQL, "failChainVODSQL": failChainVODSQL,
		"endGapRunSQL": endGapRunSQL,
	})
	traps.RequireNotCalled(t)
}
