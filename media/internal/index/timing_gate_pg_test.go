package index

// 기동 검사 CheckTimingGate(POK-196 PDT 생산자 c11 · 설계 r7 9.3)의 실 PG 시험. PG_DSN 미설정이면 skip 이다.

import (
	"context"
	"errors"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// 이 이미지(판 목록 NULL · 2)는 게이트가 NULL 이든 스위치 · 래치가 2 이든 기동 검사를 지난다(설계 9.3 「c0 는 늘 통과」).
func TestCheckTimingGatePassesThisImage(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	if err := CheckTimingGate(ctx, pool); err != nil {
		t.Fatalf("게이트 NULL = %v, want nil", err)
	}
	setGateSwitch(t, pool, &versionTwo)
	if err := CheckTimingGate(ctx, pool); err != nil {
		t.Fatalf("스위치 · 래치 2 = %v, want nil", err)
	}
}

// 판 목록에 2 가 없는 이미지(판 2 읽기를 지운 앞날 이미지의 꼴 — P13 연습 8)는 래치가 2 면 기동 검사에서 거부된다. 래치만
// 남고 스위치를 NULL 로 내려도 거부다 — 판 2 행이 장부에 남아 있기 때문이다. 잡는 결함(m17 의 짝): 기동 검사가 스위치만
// 보면 스위치를 내린 뒤 판 2 를 못 읽는 이미지가 뜬다.
func TestCheckTimingGateRefusesLatchOutsideReadable(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	legacy := []*int16{nil}
	if err := checkTimingGate(ctx, pool, legacy); err != nil {
		t.Fatalf("게이트 NULL · 판 목록 NULL = %v, want nil", err)
	}
	setGateSwitch(t, pool, &versionTwo)
	setGateSwitch(t, pool, nil)

	err := checkTimingGate(ctx, pool, legacy)

	if !errors.Is(err, ErrTimingDowngrade) {
		t.Fatalf("래치 2 · 스위치 NULL · 판 목록 NULL = %v, want ErrTimingDowngrade", err)
	}
}

// 기동 검사 문장(timingGateSQL)은 media_indexer 권한으로 돈다(설계 9.3 권한 · 역할 시험). 음성: 게이트 SELECT 를 거두면
// 기동 검사가 42501 로 실패한다 — 사이드카는 그 오류로 종료 코드 1 이다(fail-closed · 읽기 실패도 기동을 멈춘다).
func TestMediaIndexerRoleRunsBootTimingGateCheck(t *testing.T) {
	admin := newTestPool(t)
	traps := pgtest.PlantSearchPathTraps(t, admin)
	role := pgtest.NewMediaIndexerRole(t, admin)
	ctx := context.Background()

	if err := CheckTimingGate(ctx, role.Pool); err != nil {
		t.Fatalf("media_indexer 기동 검사 = %v, want nil", err)
	}
	role.RequireSent(t, map[string]string{"timingGateSQL": timingGateSQL})
	traps.RequireNotCalled(t)

	role.Revoke(t, "SELECT", "stream_timing_gate")
	err := CheckTimingGate(ctx, role.Pool)
	if err == nil || !pgtest.IsInsufficientPrivilege(err) {
		t.Fatalf("게이트 SELECT 회수 뒤 기동 검사 = %v, want 42501", err)
	}
	role.RequireDenied(t, "timingGateSQL", timingGateSQL)
}
