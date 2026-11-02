package publish

// 사이드카 DB 계정 media_indexer 최소 권한 역할 시험 — 되감기 발행자 몫(POK-311 C4).
//
// 권한 목록 · 시험 꼴은 index/media_indexer_role_pg_test.go 머리 주석과 같다. 발행자는 사이드카 프로세스 안에서
// 따로 만든 풀(cmd/segment-indexer/rewind_assembly.go)로 붙지만 계정은 같은 media_indexer 다. 이 패키지의
// 비시험 SQL 전수:
//
//	문장                메서드 · 갈래                                       표 · 동작
//	fenceAcquireSQL     Tick 첫 걸음(fence 획득)                            sessions UPDATE
//	fenceRenewSQL       RenewFence(lazy 갱신)                               sessions UPDATE
//	fenceReleaseSQL     Release(shutdown 반납)                              sessions UPDATE
//	p0ReserveSQL        Tick P0 · GAP 트랜잭션 첫 문장                      sessions UPDATE … RETURNING
//	p2RevokeSQL         Tick P2′ · RevokeInheritance(계승 취소)             sessions UPDATE … RETURNING
//	p4ConfirmSQL        Tick P4                                             sessions UPDATE · gaps UPDATE(recorded → put_confirmed)
//	r3ReconcileSQL      화해 R3(객체 있음)                                  sessions UPDATE … RETURNING
//	r4ClearETagSQL      화해 R4(객체 없음) · 동결 준비 · RevokeInheritance  sessions UPDATE … RETURNING
//	gapRecordSQL        GAP 트랜잭션(원장 INSERT)                           sessions · segments · cutoffs · gaps SELECT · gaps INSERT(ON CONFLICT DO NOTHING)
//	reserveTerminalSQL  FreezeDVR · Endlist F0 · E0(terminal 예약)          sessions UPDATE … RETURNING
//	dvrStateSQL         예약 · FailDVR 0행의 까닭                           sessions SELECT
//	confirmFrozenSQL    FreezeDVR · Endlist F4 · E5(동결 확정)              sessions UPDATE · gaps UPDATE(recorded → put_confirmed)
//	confirmEmptySQL     빈 동결 확정                                        sessions UPDATE
//	failDVRSQL          RecoverOrphan · FailDVR(freeze_failed)              sessions UPDATE … RETURNING
//	vodTxnLimitsSQL     FreezeVOD 등재 트랜잭션 첫 문장(지역 상한)          set_config 만(표 없음)
//	vodLockTailSQL      FreezeVOD 등재(꼬리 행 잠금)                        sessions SELECT FOR NO KEY UPDATE
//	vodMarkGapSQL       FreezeVOD 등재(GAP 원장)                            gaps INSERT(ON CONFLICT DO NOTHING)
//	vodAbandonGapsSQL   FreezeVOD 등재(미착지 GAP 포기)                     gaps UPDATE · segments SELECT
//	vodLockChainSQL     FreezeVOD 확정 첫 문장(사슬 행 잠금)                sessions SELECT FOR NO KEY UPDATE
//	vodConfirmSQL       FreezeVOD 확정                                      sessions UPDATE
//	vodStateSQL         FreezeVOD 확정 0행의 까닭                           sessions SELECT
//	frozenDocsSQL       FrozenAuditor.Audit(감사 대상)                      sessions SELECT
//	repairClaimSQL      감사 복구 장부 CAS                                  sessions UPDATE … RETURNING
//	repairConfirmSQL    감사 복구 ETag 확정                                 sessions UPDATE
//
// 장부 적재 · 감시(LoadLedger · Watch)는 index.LoadRewindLedger · index.LoadRewindWatch 를, 동결 · 감사 재렌더는
// index.LoadSessionLedger 를, VOD 는 index.LoadChainLedger(첫 읽기) · index.ReadChainLedger(등재 트랜잭션 안 재독 — 같은
// 문장)를 부른다 — 그 문장은 index 역할 시험이 잰다. 이 시험이 역할로 보냈음을 단언하는(RequireSent) 문장은 위 표
// 앞 아홉과 감사 셋(frozenDocsSQL · repairClaimSQL · repairConfirmSQL)이다 — 나머지 동결 · VOD 문장은 표에만 있다.

import (
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// roleFix 는 media_indexer 시험 역할로 붙은 발행 루프와 그 곁의 것들이다. admin 은 같은 시험 DB 를 소유자 계정으로
// 보는 풀이다(픽스처 · 검증용).
type roleFix struct {
	l     *loop
	role  *pgtest.Role
	traps *pgtest.SearchPathTraps
	store *fakeStore
	admin *pgxpool.Pool
}

// newRoleLoop 은 픽스처 f 를 관리 풀로 넣고 media_indexer 시험 역할의 풀로 붙은 발행 루프를 만든다.
func newRoleLoop(t *testing.T, f *fixture) roleFix {
	t.Helper()
	admin := newPool(t)
	insertSessions(t, admin, f)
	traps := pgtest.PlantSearchPathTraps(t, admin)
	role := pgtest.NewMediaIndexerRole(t, admin)
	store := newFakeStore(t)
	l := &loop{t: t, pub: newPublisher(t, role.Pool, store, "w-me", &logRecorder{}), fx: f}
	return roleFix{l: l, role: role, traps: traps, store: store, admin: admin}
}

// 발행 경로 — 획득 · R4 화해 · P0 · P4(GAP 확정 포함) · GAP 트랜잭션 · 반납 · 다른 writer 의 획득 뒤 R3 화해 ·
// lazy 갱신이 media_indexer 권한만으로 돌고 DB 가 기대대로 바뀐다.
func TestMediaIndexerRolePublishes(t *testing.T) {
	f := soloFixture(20)
	f.rows[6].PlaybackUploaded = false
	rf := newRoleLoop(t, f)
	l, role, store, admin := rf.l, rf.role, rf.store, rf.admin
	t.Cleanup(func() { role.RequireNoneDenied(t) })
	ctx := t.Context()

	l.mustPublish(ctx, 0, 5)
	insertLedgerRow(t, admin, l.fx.rows[6], "pending")
	insertCutoff(t, admin, 0)
	if out := l.gapTick(ctx, 0, 8, 6); out.GapSeq == nil || *out.GapSeq != 6 || out.Err != nil {
		t.Fatalf("GAP 틱 = %+v, want GapSeq 6", out)
	}
	l.mustPublish(ctx, 0, 9)
	wantGapStates(t, readGaps(t, admin, fxStream), map[int64]string{6: "put_confirmed"})

	if err := l.pub.Release(ctx); err != nil {
		t.Fatalf("Release 오류 = %v, want nil", err)
	}
	if row := readSession(t, admin, "S"); row.fence != nil {
		t.Fatalf("반납 뒤 fence = %s, want NULL", strOrNil(row.fence))
	}
	peer := l.peer("w-two", &logRecorder{})
	// 영값 상태의 첫 틱은 획득 뒤 Head 가 본 판으로 R3 화해만 하고 물러난다(P 가 바뀌었다) — 다음 틱이 올린다.
	if first := peer.tick(ctx, 0, 10); first.Err != nil || !first.State.FenceHeld || first.State.Prev == nil {
		t.Fatalf("다른 writer 첫 틱 = %+v, want fence 획득 · R3 화해", first)
	}
	out := peer.mustPublish(ctx, 0, 10)
	if row := readSession(t, admin, "S"); strOrNil(row.fence) != "w-two" || row.pubSeq != 10 {
		t.Errorf("다른 writer 발행 뒤 행 = %+v, want fence w-two · published_seq 10", row)
	}
	renewed := peer.pub.RenewFence(ctx, State{FenceHeld: true, RenewedAt: out.State.RenewedAt.Add(-time.Hour)}, "S")
	if renewed.Err != nil || !renewed.State.FenceHeld {
		t.Errorf("RenewFence = %+v, want fence 유지", renewed)
	}
	if puts := store.putCalls(); len(puts) != 4 {
		t.Errorf("PUT %d번, want 4", len(puts))
	}
	role.RequireSent(t, map[string]string{
		"fenceAcquireSQL": fenceAcquireSQL, "fenceRenewSQL": fenceRenewSQL, "fenceReleaseSQL": fenceReleaseSQL,
		"p0ReserveSQL": p0ReserveSQL, "p4ConfirmSQL": p4ConfirmSQL, "r3ReconcileSQL": r3ReconcileSQL,
		"r4ClearETagSQL": r4ClearETagSQL, "gapRecordSQL": gapRecordSQL,
	})
	rf.traps.RequireNotCalled(t)
}

// 계승 취소 경로 — 직전 init 이 착지하지 않은 계승 회차의 첫 PUT 앞 P2′ 가 media_indexer 권한만으로 돈다.
func TestMediaIndexerRoleRevokesInheritance(t *testing.T) {
	rf := newRoleLoop(t, chainFixture(700))
	l, role := rf.l, rf.role
	t.Cleanup(func() { role.RequireNoneDenied(t) })
	l.fx.setInitUploaded("P", false)

	out := l.tick(t.Context(), 100, 610)

	if !out.Published || !out.Revoked || out.Err != nil {
		t.Fatalf("틱 = %+v, want 취소 뒤 발행", out)
	}
	if row := readSession(t, rf.admin, "S"); row.inherits != nil || row.base != 0 {
		t.Errorf("DB 계승 %s · base %d, want NULL · 0", strOrNil(row.inherits), row.base)
	}
	role.RequireSent(t, map[string]string{"p2RevokeSQL": p2RevokeSQL})
	rf.traps.RequireNotCalled(t)
}

// 동결 감사 경로 — 감사 대상 조회 · 복구 장부 CAS · 복구 ETag 확정이 media_indexer 권한만으로 돈다(보안 ML2 L-D). 꼬리
// 조각이 빠진 DVR 판을 감사가 고쳐 장부 ETag 가 새 판이 된다.
func TestMediaIndexerRoleAuditRepairs(t *testing.T) {
	rf := newRoleLoop(t, soloFixture(9))
	l, role, store, admin := rf.l, rf.role, rf.store, rf.admin
	t.Cleanup(func() { role.RequireNoneDenied(t) })
	ctx := t.Context()
	for _, r := range l.fx.rows {
		insertLedgerRow(t, admin, r, "uploaded")
	}
	insertCutoff(t, admin, 0)
	l.mustPublish(ctx, 0, 5)
	wantOutcome(t, l.pub.FreezeDVR(ctx, fxStream, "S"), AxisDVR, ResultFrozen, false)
	// 장부 ETag 를 본문 MD5 가 아닌 값으로 둔다 — 가짜 저장소의 ETag 는 본문 MD5 라 고친 판이 동결 때와 같은 ETag 다.
	exec(t, admin, `UPDATE stream_sessions SET dvr_etag = '"불투명"', manifest_etag = '"불투명"' WHERE session_id = 'S'`)
	closed, _ := stored(store, soloKey)
	truncateDVR(t, store, closed, 2)

	out := NewFrozenAuditor(l.pub).Audit(ctx)

	if out.Checked != 1 || out.Repaired != 1 || out.Err != nil {
		t.Fatalf("감사 = %+v, want 1건 확인 · 복구 1", out)
	}
	if _, etag := stored(store, soloKey); strOrNil(readSession(t, admin, "S").etag) != etag {
		t.Errorf("장부 manifest_etag 가 고친 판 %s 이 아니다", etag)
	}
	role.RequireSent(t, map[string]string{
		"frozenDocsSQL": frozenDocsSQL, "repairClaimSQL": repairClaimSQL, "repairConfirmSQL": repairConfirmSQL,
	})
	rf.traps.RequireNotCalled(t)
}

// 음성 대조 — 권한 하나를 거두면 그 권한을 쓰는 발행자 문장이 42501 이다.
func TestMediaIndexerRolePublishWithoutEachGrantIsDenied(t *testing.T) {
	t.Run("UPDATE_stream_sessions", func(t *testing.T) {
		rf := newRoleLoop(t, soloFixture(10))
		l, role, store := rf.l, rf.role, rf.store
		role.Revoke(t, "UPDATE", "stream_sessions")

		out := l.tick(t.Context(), 0, 5)

		if !pgtest.IsInsufficientPrivilege(out.Err) || len(store.putCalls()) != 0 {
			t.Errorf("틱 = %+v · PUT %d번, want 42501 · PUT 0", out, len(store.putCalls()))
		}
		role.RequireDenied(t, "fenceAcquireSQL", fenceAcquireSQL)
	})
	for _, c := range []struct{ privilege, sql string }{
		{"SELECT", gapRecordSQL},
		{"INSERT", gapRecordSQL},
		{"UPDATE", p4ConfirmSQL},
	} {
		t.Run(c.privilege+"_stream_published_gaps", func(t *testing.T) {
			f := soloFixture(20)
			f.rows[6].PlaybackUploaded = false
			rf := newRoleLoop(t, f)
			l, role, admin := rf.l, rf.role, rf.admin
			l.mustPublish(t.Context(), 0, 5)
			insertLedgerRow(t, admin, l.fx.rows[6], "pending")
			insertCutoff(t, admin, 0)
			if c.privilege == "UPDATE" { // P4 가 확정할 recorded 행을 미리 둔다
				insertGap(t, admin, fxStream, 6, "recorded")
				l.fx.markGap(6)
			}
			role.Revoke(t, c.privilege, "stream_published_gaps")

			if c.privilege == "UPDATE" {
				l.tick(t.Context(), 0, 8)
			} else {
				l.gapTick(t.Context(), 0, 8, 6)
			}

			role.RequireDenied(t, c.privilege+" stream_published_gaps", c.sql)
			if c.privilege != "UPDATE" {
				wantGapStates(t, readGaps(t, admin, fxStream), map[int64]string{})
			}
		})
	}
}
