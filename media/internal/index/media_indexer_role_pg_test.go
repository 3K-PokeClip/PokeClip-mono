package index

// 사이드카 DB 계정 media_indexer 최소 권한 역할 시험 — index 패키지 몫(POK-311 C4).
//
// 운영 사이드카는 media_indexer 로 붙는다. 권한 목록은 pgtest.MediaIndexerGrantSQL 한 곳이고 README
// 「사이드카 DB 계정 media_indexer 권한」 블록과 같다(pgtest/role_test.go 가 단언). 여기서는 그 권한만 가진
// 시험 역할로 이 패키지의 사이드카 SQL 을 전부 실제로 돌려 ① 42501 이 없고 ② 상태가 기대대로 바뀌는지 잰다.
// 문장마다 「역할 풀로 보내 성공했다」를 pgx 추적기로 확인한다(Role.RequireSent) — 오류를 삼키는 갈래도 드러난다.
//
// 비시험 SQL 전수(사이드카 경로 — local_cleanup.go 는 janitor · ddl.go 는 DDL 역할 몫이라 뺀다):
//
//	문장                           메서드 · 갈래                          표 · 동작
//	store.go
//	  loadCursorSQL                LoadCursor                           segments · floors SELECT
//	  existingPathsSQL             ExistingPaths                        segments SELECT
//	  setTxnLimitsSQL              Insert · EndLive 첫 문장             (표 없음 — set_config)
//	  lockCutoffStreamSQL          Insert 주조 적격 갈래                 (표 없음 — advisory 잠금)
//	  openedSessionSQL             Insert 개시 갈래                      sessions SELECT
//	  basePDTSQL                   Insert 귀속 갈래(기저 세션 있음)      segments SELECT
//	  insertSeedSQL                Insert                               segments INSERT · cutoffs INSERT(ON CONFLICT DO NOTHING)
//	  cutoffExistsSQL              Insert 적격인데 미주조(진단)          cutoffs SELECT
//	  updateTailSQL                UpdateTail                           segments UPDATE(+ SELECT)
//	  endLiveSQL                   EndLive                              sessions UPDATE … RETURNING
//	upload_store.go
//	  markUploadedSQL · markFailedSQL         MarkUploaded · MarkFailed            segments UPDATE
//	  markPlaybackUploadedSQL                 MarkPlaybackUploaded                 segments UPDATE
//	  markPlaybackFailedSQL                   SettlePlaybackFailure(그 밖 사유)    segments UPDATE
//	  markPlaybackInitMismatchSQL             SettlePlaybackFailure(init_mismatch) segments UPDATE · sessions UPDATE
//	  markInitUploadedSQL                     MarkInitUploaded                     sessions SELECT FOR UPDATE · UPDATE
//	  pending{Archive,Playback,Init}UploadsSQL PendingUploads 축 셋                segments · sessions · cutoffs SELECT
//	  count{Archive,Playback,Init}BacklogSQL   CountBacklog 축 셋                  segments · sessions · cutoffs SELECT
//	rewind_read.go
//	  rewindCutoffSQL · rewindFloorSQL · rewindRowsSQL · rewindSessionsSQL  LoadRewindLedger(읽기 전용 RR)  cutoffs · segments · gaps · sessions SELECT
//	  rewindWatchSQL                          LoadRewindWatch                      sessions · cutoffs · segments · gaps SELECT
//	round_playback.go(M6-3a — 역할 시험은 round_playback_role_pg_test.go TestRoundPlaybackReadsRunUnderMediaIndexerRole)
//	  lookupRoundPlaybackSQL                  LookupRoundPlayback                  sessions · segments SELECT
//	  loadStreamPlaybackSQL                   LoadStreamPlayback                   sessions · segments SELECT
//	  loadPlaybackStatesSQL                   LoadPlaybackStates                   sessions · segments SELECT
//
//	expiry_store.go(M6-4 — 역할 시험은 expiry_store_pg_test.go TestExpiryStatementsRunUnderMediaIndexerRole)
//	  markExpiredSQL                          MarkExpired                          sessions UPDATE(+ segments SELECT)
//	  expiryCutoffValidSQL                    ExpiryCutoffValid                    cutoffs · segments SELECT
//	  expiryRowsSQL · expiryDocumentsSQL      ExpiryRows · ExpiryDocuments         segments · sessions SELECT
//	  expiryLockCutoffSQL                     DeleteExpired                        cutoffs SELECT FOR UPDATE
//	  expiryDeleteSQL                         DeleteExpired                        segments DELETE(+ 하한 트리거 — 정의자 권한)
//	  expiryEmptiedSessionsSQL                DeleteExpired                        sessions SELECT FOR UPDATE
//	  expiryDeleteGapsSQL                     DeleteExpired                        gaps DELETE
//	  expiryAdvanceCutoffSQL · expiryRemoveCutoffSQL  DeleteExpired                cutoffs UPDATE · DELETE
//	  expiryUnlinkSuccessorsSQL · expiryDeleteSessionsSQL  DeleteExpired           sessions UPDATE · DELETE
//	  expiryDeleteOutboxSQL                   DeleteExpired                        outbox DELETE
//
// M6-8 몫 선부여(ADR-0006 개정 2026-10-26): outbox SELECT · INSERT · UPDATE 는 목록에 있지만 이 패키지의 비시험 SQL 은
// 아직 쓰지 않는다 — 쓰는 문장과 그 역할 시험은 그 PR 이 들인다. stream_seq_floors SELECT 는 LoadCursor(하한 GREATEST)와 하한 검사 트리거
// (stream_segments BEFORE INSERT — 부른 쪽 권한)가 읽는다.
//
// 세션 결정 문장(currentLive · previousSession · endSession · openSession)은 session 패키지 역할 시험이, 발행자
// 문장(fence · P0 · P2′ · P4 · R3 · R4 · GAP)은 rewind/publish 역할 시험이 잰다. 여기 Insert 는 실물 결정자를
// 끼우므로 그 문장들도 이 역할로 함께 돈다.
//
// FOR UPDATE 주의: 사이드카의 FOR UPDATE 는 stream_sessions(markInitUploadedSQL)에 있고 UPDATE 권한이 필요하다.
// stream_segments 의 FOR UPDATE NOWAIT 은 janitor(local_cleanup.go) 몫이다.

import (
	"context"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// roleBase 는 역할 시험 조각의 기준 벽시계다 — 업로드 대상 조회의 꼬리 유예(0초)를 이미 지난 과거로 둔다.
func roleBase() time.Time { return time.Now().UTC().Truncate(time.Millisecond).Add(-10 * time.Minute) }

// roleInsertSeries 는 role 의 실물 store 로 stream 에 4초 조각 n 개(seq 0..n-1)를 주조 적격으로 넣고 첫 회차 id 를
// 돌려준다 — 첫 조각이 회차를 열고 컷오프를 주조한다.
func roleInsertSeries(t *testing.T, store Store, stream string, base time.Time, n int64) string {
	t.Helper()
	var session string
	for seq := range n {
		_, res, err := store.Insert(context.Background(),
			segAt(stream, seq, base.Add(time.Duration(seq)*4*time.Second), 4000), eligibleSeed(time.Now()), liveIngress())
		if err != nil {
			t.Fatalf("Insert seq %d 오류 = %v, want nil", seq, err)
		}
		if seq == 0 {
			session = res.SessionID
		}
	}
	return session
}

// 장부 기록 경로 — 커서 · 경로 적재, 회차 개시 + 주조, 귀속(기저 PDT), 기존 컷오프 진단, TD 분할, 꼬리 정정,
// 종료 전이, 종료 뒤 재개시가 media_indexer 권한만으로 돌고 장부가 기대대로 바뀐다. 앱 사용자가 public 에 심은
// search_path 덫은 하나도 불리지 않는다.
func TestMediaIndexerRoleRecordsSegments(t *testing.T) {
	admin := newTestPool(t)
	traps := pgtest.PlantSearchPathTraps(t, admin)
	role := pgtest.NewMediaIndexerRole(t, admin)
	t.Cleanup(func() { role.RequireNoneDenied(t) })
	store := newSessionStore(role.Pool)
	ctx := context.Background()
	stream, base := "role-rec", roleBase()

	if cur, err := store.LoadCursor(ctx, stream); err != nil || cur.Tail != nil {
		t.Fatalf("빈 스트림 LoadCursor = (%+v, %v), want (꼬리 없음, nil)", cur, err)
	}
	if paths, err := store.ExistingPaths(ctx, stream); err != nil || len(paths) != 0 {
		t.Fatalf("빈 스트림 ExistingPaths = (%d개, %v), want (0개, nil)", len(paths), err)
	}

	// 개시 + 주조(적격 · 직렬화 잠금).
	_, first, err := store.Insert(ctx, segAt(stream, 0, base, 4000), eligibleSeed(time.Now()), liveIngress())
	if err != nil || !first.Seeded || !first.SessionOpened {
		t.Fatalf("첫 Insert = (%+v, %v), want 주조 · 회차 개시", first, err)
	}
	// 귀속(기저 PDT) + 기존 컷오프 진단 — 진단 질의가 실패하면 DiagErr 로만 나오므로 함께 본다.
	_, second, err := store.Insert(ctx, segAt(stream, 1, base.Add(4*time.Second), 4000), eligibleSeed(time.Now()), liveIngress())
	if err != nil || second.Decline != DeclineSkipped || second.DiagErr != nil || second.SessionID != first.SessionID {
		t.Fatalf("둘째 Insert = (%+v, %v), want 같은 회차 귀속 · existing_cutoff · 진단 오류 없음", second, err)
	}
	if c := carrierOf(t, admin, stream, 1); c.PDT == nil || !c.PDT.Equal(base.Add(4*time.Second)) {
		t.Errorf("seq 1 playback_pdt = %v, want %v(기저 행 + 길이)", c.PDT, base.Add(4*time.Second))
	}
	// TD 분할 — 7초 조각이 TD 6 회차를 끝내고 새 회차를 연다.
	_, split, err := store.Insert(ctx, segAt(stream, 2, base.Add(8*time.Second), 7000), Seed{}, liveIngress())
	if err != nil || !split.SessionOpened || split.EndingSessionID != first.SessionID {
		t.Fatalf("TD 분할 Insert = (%+v, %v), want 새 회차 · 옛 회차 %s ending", split, err, first.SessionID)
	}
	if ok, err := store.UpdateTail(ctx, stream, 2, 7100, 2000); err != nil || !ok {
		t.Fatalf("UpdateTail = (%v, %v), want (true, nil)", ok, err)
	}
	cur, err := store.LoadCursor(ctx, stream)
	if err != nil || cur.NextSeq != 3 || cur.Tail.DurationMS != 7100 {
		t.Fatalf("LoadCursor = (%+v, %v), want 다음 seq 3 · 꼬리 7100ms", cur, err)
	}
	if paths, err := store.ExistingPaths(ctx, stream); err != nil || len(paths) != 3 {
		t.Fatalf("ExistingPaths = (%d개, %v), want (3개, nil)", len(paths), err)
	}
	ended, err := EndLive(ctx, role.Pool, stream, testClose, noopEnqueue)
	if err != nil || ended != split.SessionID {
		t.Fatalf("EndLive = (%q, %v), want (%q, nil)", ended, err, split.SessionID)
	}

	sessions := sessionsOf(t, admin, stream)
	if len(sessions) != 2 || sessions[0].State != "ending" || *sessions[0].EndReason != "td_exceeded" ||
		sessions[1].State != "ending" || *sessions[1].EndReason != "offline" {
		t.Errorf("회차 = %+v, want [ending(td_exceeded) ending(offline)]", sessions)
	}
	// 현 live 가 없는 조각 — 직전 회차 조회(session 패키지)가 그 회차 끝 조각의 끝 시각(길이 × interval)을 계산한다.
	_, reopened, err := store.Insert(ctx, segAt(stream, 3, base.Add(15100*time.Millisecond), 4000), Seed{}, liveIngress())
	if err != nil || !reopened.SessionOpened {
		t.Fatalf("종료 뒤 Insert = (%+v, %v), want 새 회차 개시", reopened, err)
	}
	role.RequireSent(t, map[string]string{
		"loadCursorSQL": loadCursorSQL, "existingPathsSQL": existingPathsSQL, "setTxnLimitsSQL": setTxnLimitsSQL,
		"lockCutoffStreamSQL": lockCutoffStreamSQL, "openedSessionSQL": openedSessionSQL, "basePDTSQL": basePDTSQL,
		"insertSeedSQL": insertSeedSQL, "cutoffExistsSQL": cutoffExistsSQL, "updateTailSQL": updateTailSQL,
		"endLiveSQL": endLiveSQL,
	})
	traps.RequireNotCalled(t)
}

// 업로드 장부 경로 — 축 셋의 대상 조회 · 잔량 집계와 CAS 여섯 갈래가 media_indexer 권한만으로 돌고 상태가
// 전이한다.
func TestMediaIndexerRoleRunsUploadLedger(t *testing.T) {
	admin := newTestPool(t)
	traps := pgtest.PlantSearchPathTraps(t, admin)
	role := pgtest.NewMediaIndexerRole(t, admin)
	t.Cleanup(func() { role.RequireNoneDenied(t) })
	stream := "role-up"
	session := roleInsertSeries(t, newSessionStore(role.Pool), stream, roleBase(), 3)
	up := NewUploadStore(role.Pool)
	ctx := context.Background()

	for _, c := range []struct {
		axis Axis
		want int
	}{{AxisArchive, 3}, {AxisPlayback, 3}, {AxisInit, 1}} {
		got, _, err := up.PendingUploads(ctx, c.axis, 0, 10, SweepCursor{})
		if err != nil || len(got) != c.want {
			t.Errorf("PendingUploads(%s) = (%d행, %v), want (%d행, nil)", c.axis, len(got), err, c.want)
		}
		if pending, _, _, err := up.CountBacklog(ctx, c.axis); err != nil || pending != int64(c.want) {
			t.Errorf("CountBacklog(%s) pending = (%d, %v), want (%d, nil)", c.axis, pending, err, c.want)
		}
	}

	marks := []struct {
		name string
		call func() (bool, error)
	}{
		{"MarkUploaded seq 0", func() (bool, error) { return up.MarkUploaded(ctx, stream, 0, 1000) }},
		{"MarkFailed seq 1", func() (bool, error) { return up.MarkFailed(ctx, stream, 1, 1001) }},
		{"MarkPlaybackUploaded seq 0", func() (bool, error) { return up.MarkPlaybackUploaded(ctx, stream, 0, 500) }},
		{"SettlePlaybackFailure seq 1(put_failed)", func() (bool, error) {
			return up.SettlePlaybackFailure(ctx, stream, 1, session, "put_failed")
		}},
		{"SettlePlaybackFailure seq 2(init_mismatch)", func() (bool, error) {
			return up.SettlePlaybackFailure(ctx, stream, 2, session, ReasonInitMismatch)
		}},
	}
	for _, m := range marks {
		if ok, err := m.call(); err != nil || !ok {
			t.Errorf("%s = (%v, %v), want (true, nil)", m.name, ok, err)
		}
	}
	if got, err := up.MarkInitUploaded(ctx, session, []byte{1, 2, 3}, "init/"+session+".mp4", 900, false); err != nil || got != InitMarkSuccess {
		t.Errorf("MarkInitUploaded = (%v, %v), want (Success, nil)", got, err)
	}

	if st, _ := stateOf(t, admin, stream, 0); st != UploadStateUploaded {
		t.Errorf("seq 0 upload_state = %s, want uploaded", st)
	}
	if st, _ := stateOf(t, admin, stream, 1); st != UploadStateFailed {
		t.Errorf("seq 1 upload_state = %s, want failed", st)
	}
	if s := sessionsOf(t, admin, stream); len(s) != 1 || s[0].State != "ending" || *s[0].EndReason != ReasonInitMismatch {
		t.Errorf("회차 = %+v, want ending(init_mismatch) — init 불일치 결속 CTE", s)
	}
	role.RequireSent(t, map[string]string{
		"markUploadedSQL": markUploadedSQL, "markFailedSQL": markFailedSQL,
		"markPlaybackUploadedSQL": markPlaybackUploadedSQL, "markPlaybackFailedSQL": markPlaybackFailedSQL,
		"markPlaybackInitMismatchSQL": markPlaybackInitMismatchSQL, "markInitUploadedSQL": markInitUploadedSQL,
		"pendingArchiveUploadsSQL": pendingArchiveUploadsSQL, "pendingPlaybackUploadsSQL": pendingPlaybackUploadsSQL,
		"pendingInitUploadsSQL": pendingInitUploadsSQL, "countArchiveBacklogSQL": countArchiveBacklogSQL,
		"countPlaybackBacklogSQL": countPlaybackBacklogSQL, "countInitBacklogSQL": countInitBacklogSQL,
	})
	traps.RequireNotCalled(t)
}

// 되감기 읽기 경로 — 장부 적재(읽기 전용 REPEATABLE READ)와 30초 감시 한 문장이 media_indexer 권한만으로 돈다.
func TestMediaIndexerRoleReadsRewindLedger(t *testing.T) {
	admin := newTestPool(t)
	traps := pgtest.PlantSearchPathTraps(t, admin)
	role := pgtest.NewMediaIndexerRole(t, admin)
	t.Cleanup(func() { role.RequireNoneDenied(t) })
	stream := "role-rw"
	session := roleInsertSeries(t, newSessionStore(role.Pool), stream, roleBase(), 3)
	ctx := context.Background()

	l, err := LoadRewindLedger(ctx, role.Pool, stream, LedgerBounds{Lookback: time.Hour, RowCap: 100})
	if err != nil || !l.HasCutoff || len(l.Rows) != 3 || len(l.Sessions) != 1 {
		t.Fatalf("LoadRewindLedger = (컷오프 %v · 행 %d · 회차 %d, %v), want (참 · 3 · 1, nil)", l.HasCutoff, len(l.Rows), len(l.Sessions), err)
	}
	w, err := LoadRewindWatch(ctx, role.Pool, []WatchProbe{{StreamID: stream, NextSeq: 3}}, time.Minute)
	if err != nil || len(w.Live) != 1 || w.Live[0].SessionID != session {
		t.Fatalf("LoadRewindWatch = (%+v, %v), want live 회차 %s 한 행", w, err, session)
	}
	role.RequireSent(t, map[string]string{
		"rewindCutoffSQL": rewindCutoffSQL, "rewindFloorSQL": rewindFloorSQL, "rewindRowsSQL": rewindRowsSQL,
		"rewindSessionsSQL": rewindSessionsSQL, "rewindWatchSQL": rewindWatchSQL,
	})
	traps.RequireNotCalled(t)
}

// 음성 대조 — 시험이 권한 경계를 실제로 재는지 본다. 목록의 권한을 표마다 하나씩 거두면 그 권한을 쓰는 메서드가
// 42501 이다(아래 줄마다 그것 없이는 막히는 메서드가 있다 — 그 권한이 군더더기가 아니라는 근거). 단 미리 준
// M6-4 · M6-8 몫(DELETE · stream_cutoffs UPDATE · outbox — 파일 머리 주석)은 아래에 줄이 없다. 이 표를 들일 때 그
// 권한을 쓰는 메서드가 없어 거둬도 막힐 호출이 없었다 — 그 권한들의 군더더기 여부는 이 시험이 재지 않는다.
func TestMediaIndexerRoleWithoutEachGrantIsDenied(t *testing.T) {
	ctx := context.Background()
	cases := []struct {
		privilege, table string
		sql              string // 거부돼야 하는 문장
		call             func(role *pgtest.Role, stream, session string) error
	}{
		{"SELECT", "stream_segments", loadCursorSQL, func(r *pgtest.Role, stream, _ string) error {
			_, err := newSessionStore(r.Pool).LoadCursor(ctx, stream)
			return err
		}},
		{"SELECT", "stream_seq_floors", loadCursorSQL, func(r *pgtest.Role, stream, _ string) error {
			_, err := newSessionStore(r.Pool).LoadCursor(ctx, stream)
			return err
		}},
		{"INSERT", "stream_segments", insertSeedSQL, func(r *pgtest.Role, stream, _ string) error {
			_, _, err := newSessionStore(r.Pool).Insert(ctx, segAt(stream, 3, roleBase().Add(12*time.Second), 4000), Seed{}, liveIngress())
			return err
		}},
		{"UPDATE", "stream_segments", markUploadedSQL, func(r *pgtest.Role, stream, _ string) error {
			_, err := NewUploadStore(r.Pool).MarkUploaded(ctx, stream, 0, 1000)
			return err
		}},
		{"SELECT", "stream_sessions", pendingInitUploadsSQL, func(r *pgtest.Role, _, _ string) error {
			_, _, err := NewUploadStore(r.Pool).PendingUploads(ctx, AxisInit, 0, 10, SweepCursor{})
			return err
		}},
		{"INSERT", "stream_sessions", "", func(r *pgtest.Role, _, _ string) error {
			_, _, err := newSessionStore(r.Pool).Insert(ctx, segAt("role-deny-new", 0, roleBase(), 4000), Seed{}, liveIngress())
			return err
		}},
		{"UPDATE", "stream_sessions", markInitUploadedSQL, func(r *pgtest.Role, _, session string) error {
			_, err := NewUploadStore(r.Pool).MarkInitUploaded(ctx, session, []byte{1}, "init/x.mp4", 1, false)
			return err
		}},
		{"SELECT", "stream_cutoffs", rewindCutoffSQL, func(r *pgtest.Role, stream, _ string) error {
			_, err := LoadRewindLedger(ctx, r.Pool, stream, LedgerBounds{Lookback: time.Hour, RowCap: 100})
			return err
		}},
		{"INSERT", "stream_cutoffs", insertSeedSQL, func(r *pgtest.Role, stream, _ string) error {
			_, _, err := newSessionStore(r.Pool).Insert(ctx, segAt(stream, 3, roleBase().Add(12*time.Second), 4000), eligibleSeed(time.Now()), liveIngress())
			return err
		}},
		{"SELECT", "stream_published_gaps", rewindRowsSQL, func(r *pgtest.Role, stream, _ string) error {
			_, err := LoadRewindLedger(ctx, r.Pool, stream, LedgerBounds{Lookback: time.Hour, RowCap: 100})
			return err
		}},
	}
	for _, c := range cases {
		t.Run(c.privilege+"_"+c.table, func(t *testing.T) {
			admin := newTestPool(t)
			role := pgtest.NewMediaIndexerRole(t, admin)
			stream := "role-deny"
			session := roleInsertSeries(t, newSessionStore(role.Pool), stream, roleBase(), 3)
			role.Revoke(t, c.privilege, c.table)

			err := c.call(role, stream, session)

			if !pgtest.IsInsufficientPrivilege(err) {
				t.Errorf("%s ON %s 회수 뒤 오류 = %v, want 42501", c.privilege, c.table, err)
			}
			if c.sql != "" {
				role.RequireDenied(t, c.table+" "+c.privilege, c.sql)
			}
		})
	}
}

// 음성 대조 — 목록 밖 권한(TRUNCATE · DDL · 하한 표 쓰기)은 처음부터 42501 이다. 사이드카의 DELETE 는 M6 만료 ·
// outbox 몫으로만 열렸고(ADR-0006 개정 2026-10-26), 표를 통째로 비우거나 스키마를 만들거나(ENSURE_SCHEMA=false)
// seq 하한을 내리지는 못한다 — EnsureSchema 를 이 역할로 부르면 막힌다.
// CREATE TABLE · EnsureSchema 의 42501 은 PG15+ 기본값 전제다 — PG15 부터 public 스키마 CREATE 가 PUBLIC 에서
// 빠졌다. PG14 이하이거나 그 권한을 되돌린 DB 에서는 이 역할도 표를 만들 수 있다(README 운영 DB 사전 확인).
func TestMediaIndexerRoleHasNoTruncateDDLOrFloorWrites(t *testing.T) {
	admin := newTestPool(t)
	role := pgtest.NewMediaIndexerRole(t, admin)
	ctx := context.Background()

	for _, s := range []string{
		"TRUNCATE stream_segments", "TRUNCATE stream_sessions",
		"TRUNCATE stream_cutoffs", "TRUNCATE stream_published_gaps",
		"TRUNCATE broadcast_lifecycle_outbox", "CREATE TABLE role_probe (x int)",
		"INSERT INTO stream_seq_floors (stream_id, next_seq) VALUES ('role-probe', 0)",
		"UPDATE stream_seq_floors SET next_seq = 0", "DELETE FROM stream_seq_floors",
	} {
		if _, err := role.Pool.Exec(ctx, s); !pgtest.IsInsufficientPrivilege(err) {
			t.Errorf("%q 오류 = %v, want 42501", s, err)
		}
	}
	if err := EnsureSchema(ctx, role.Pool); !pgtest.IsInsufficientPrivilege(err) {
		t.Errorf("EnsureSchema 오류 = %v, want 42501", err)
	}
}
