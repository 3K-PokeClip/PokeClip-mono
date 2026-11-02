package index

// 회수 기록 표 vod_copy_intents(POK-525 ①-5a) PG 통합 시험 — Open · Claim · Close 와 권한 · 기동 확인 · 운영 SQL 확인.
// PG_DSN 이 없으면 skip(REQUIRE_PG=1 이면 실패).
//
// 기록은 외래키가 없어 만료(DeleteExpired)나 clip 탈퇴 정리가 조각 · 세션 행을 지워도 남는다 — 그 범위의 사본을
// 유예 재삭제(①-5b)가 거두려면 기록이 행보다 오래 살아야 한다.

import (
	"context"
	"slices"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// ciRange 는 시험 기록의 범위다.
func ciRange(stream string, first, last int64, sessions ...string) CopyIntentRange {
	return CopyIntentRange{StreamID: stream, FirstSeq: first, LastSeq: last, InitSessions: sessions}
}

// ciCount 는 stream 의 기록 수다.
func ciCount(t *testing.T, pool *pgxpool.Pool, stream string) int {
	t.Helper()
	return expCount(t, pool, `SELECT count(*) FROM vod_copy_intents WHERE stream_id = $1`, stream)
}

// ciMakeDue 는 기록 id 의 due_at 을 지금 앞으로 당긴다 — 유예가 지난 상태를 만든다.
func ciMakeDue(t *testing.T, pool *pgxpool.Pool, id int64) {
	t.Helper()
	if _, err := pool.Exec(context.Background(),
		`UPDATE vod_copy_intents SET due_at = pg_catalog.now() - interval '1 second' WHERE intent_id = $1`, id); err != nil {
		t.Fatalf("기한 당기기 실패: %v", err)
	}
}

// ciIDs 는 기록들의 id 다(오름차순).
func ciIDs(in []CopyIntent) []int64 {
	out := make([]int64, 0, len(in))
	for _, c := range in {
		out = append(out, c.ID)
	}
	slices.Sort(out)
	return out
}

// 기록 한 건의 수명 — Open 은 범위를 그대로 남기고 기한 전에는 Claim 에 잡히지 않는다. 기한이 지나면 Claim 이 잡아
// 임대만큼 미루고(그동안 다시 잡히지 않음), Close 가 지운다.
func TestCopyIntentOpenClaimClose(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	const stream = "ci-life"

	opened, err := OpenCopyIntent(ctx, pool, ciRange(stream, 3, 9, "sess-a", "sess-b"), time.Hour)
	if err != nil {
		t.Fatalf("Open 오류 = %v", err)
	}
	early, earlyErr := ClaimCopyIntents(ctx, pool, 10)
	ciMakeDue(t, pool, opened.ID)
	claimed, claimErr := ClaimCopyIntents(ctx, pool, 10)
	again, againErr := ClaimCopyIntents(ctx, pool, 10)
	closeErr := CloseCopyIntent(ctx, pool, opened.ID)
	left := ciCount(t, pool, stream)

	want := ciRange(stream, 3, 9, "sess-a", "sess-b")
	if opened.ID <= 0 || opened.StreamID != want.StreamID || opened.FirstSeq != 3 || opened.LastSeq != 9 ||
		!slices.Equal(opened.InitSessions, want.InitSessions) {
		t.Errorf("Open = %+v, want 범위 %+v · 양수 id", opened, want)
	}
	if earlyErr != nil || len(early) != 0 {
		t.Errorf("기한 전 Claim = (%+v, %v), want (빈 목록, nil)", early, earlyErr)
	}
	if claimErr != nil || len(claimed) != 1 || claimed[0].ID != opened.ID ||
		!slices.Equal(claimed[0].InitSessions, want.InitSessions) || claimed[0].LastSeq != 9 {
		t.Fatalf("기한 뒤 Claim = (%+v, %v), want 기록 %d 하나", claimed, claimErr, opened.ID)
	}
	if d := time.Until(claimed[0].DueAt); d < CopyIntentLease-time.Minute || d > CopyIntentLease+time.Minute {
		t.Errorf("Claim 뒤 기한 = 지금 + %v, want 지금 + 임대 %v 근처", d, CopyIntentLease)
	}
	if againErr != nil || len(again) != 0 {
		t.Errorf("임대 중 Claim = (%+v, %v), want (빈 목록, nil)", again, againErr)
	}
	if closeErr != nil || left != 0 {
		t.Errorf("Close = %v · 남은 기록 %d, want nil · 0", closeErr, left)
	}
}

// 기한은 발급 때 DB 시계로 정한다 — due_at = now() + 넘긴 기간(같은 트랜잭션의 now()와 정확히 같다).
func TestCopyIntentOpenSetsDueFromDBClock(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	const hold = 95*time.Minute + 750*time.Millisecond
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	var now time.Time
	if err := tx.QueryRow(ctx, `SELECT pg_catalog.now()`).Scan(&now); err != nil {
		t.Fatal(err)
	}

	got, err := openCopyIntent(ctx, tx, ciRange("ci-due", 0, 0), hold)

	if err != nil || !got.DueAt.Equal(now.Add(hold)) {
		t.Errorf("Open 기한 = (%v, %v), want now() + %v = %v", got.DueAt, err, hold, now.Add(hold))
	}
}

// 기한 경계 — Claim 은 due_at ≤ now() 만 잡는다. now() + 1µs 는 안 잡고, now() · now() − 1µs 는 잡는다. 잡힌 기록의 새
// 기한은 now() + 임대다. 한 트랜잭션 안이라 now() 가 움직이지 않는다.
func TestClaimCopyIntentsDueBoundary(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	tests := []struct {
		name   string
		offset string // now() 에 더하는 interval
		want   bool
	}{
		{name: "1µs 뒤", offset: "1 microsecond", want: false},
		{name: "지금", offset: "0", want: true},
		{name: "1µs 앞", offset: "-1 microsecond", want: true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			tx, err := pool.Begin(ctx)
			if err != nil {
				t.Fatal(err)
			}
			defer func() { _ = tx.Rollback(ctx) }()
			opened, err := openCopyIntent(ctx, tx, ciRange("ci-edge", 0, 1), time.Hour)
			if err != nil {
				t.Fatal(err)
			}
			var now time.Time
			if err := tx.QueryRow(ctx, `UPDATE vod_copy_intents SET due_at = pg_catalog.now() + $2::interval
				WHERE intent_id = $1 RETURNING pg_catalog.now()`, opened.ID, tt.offset).Scan(&now); err != nil {
				t.Fatal(err)
			}

			got, err := claimCopyIntents(ctx, tx, 10, CopyIntentLease)

			if err != nil || (len(got) == 1) != tt.want {
				t.Fatalf("Claim = (%+v, %v), want 잡힘 %t", got, err, tt.want)
			}
			if tt.want && (got[0].ID != opened.ID || !got[0].DueAt.Equal(now.Add(CopyIntentLease))) {
				t.Errorf("잡힌 기록 = %+v, want id %d · 기한 now() + 임대 %v", got[0], opened.ID, now.Add(CopyIntentLease))
			}
		})
	}
}

// 두 연결이 겹쳐 Claim 해도 같은 기록을 나눠 갖지 않는다 — 먼저 잡은 쪽의 행 잠금을 다른 쪽은 건너뛴다(SKIP LOCKED).
// 기다리지도 않는다.
func TestClaimCopyIntentsTwoConnectionsDoNotOverlap(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	const stream = "ci-race"
	var opened []int64
	for i := range int64(3) {
		c, err := OpenCopyIntent(ctx, pool, ciRange(stream, i, i), time.Hour)
		if err != nil {
			t.Fatal(err)
		}
		ciMakeDue(t, pool, c.ID)
		opened = append(opened, c.ID)
	}
	first, err := pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = first.Rollback(ctx) }()
	held, heldErr := claimCopyIntents(ctx, first, 1, CopyIntentLease)

	wctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	other, otherErr := ClaimCopyIntents(wctx, pool, 10)
	if err := first.Commit(ctx); err != nil {
		t.Fatal(err)
	}

	if heldErr != nil || len(held) != 1 {
		t.Fatalf("첫 연결 Claim = (%+v, %v), want 하나", held, heldErr)
	}
	all := append(ciIDs(held), ciIDs(other)...)
	slices.Sort(all)
	if otherErr != nil || slices.Contains(ciIDs(other), held[0].ID) || !slices.Equal(all, opened) {
		t.Errorf("둘째 연결 Claim = (%v, %v) · 첫 연결 %v, want 겹치지 않고 합이 %v", ciIDs(other), otherErr, ciIDs(held), opened)
	}
}

// 잡고 닫지 않은 기록(일꾼이 죽거나 한 회 시한을 넘김)은 임대가 지나면 다시 잡힌다.
func TestClaimCopyIntentsReclaimsAfterLease(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	opened, err := OpenCopyIntent(ctx, pool, ciRange("ci-lease", 0, 4), time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	ciMakeDue(t, pool, opened.ID)
	if got, err := ClaimCopyIntents(ctx, pool, 10); err != nil || len(got) != 1 {
		t.Fatalf("첫 Claim = (%+v, %v), want 하나", got, err)
	}
	if _, err := pool.Exec(ctx, `UPDATE vod_copy_intents SET due_at = due_at - $2::interval - interval '1 second'
		WHERE intent_id = $1`, opened.ID, CopyIntentLease); err != nil {
		t.Fatalf("임대 경과 만들기 실패: %v", err)
	}

	got, err := ClaimCopyIntents(ctx, pool, 10)

	if err != nil || !slices.Equal(ciIDs(got), []int64{opened.ID}) {
		t.Errorf("임대 뒤 Claim = (%v, %v), want [%d]", ciIDs(got), err, opened.ID)
	}
}

// 쪽 크기 — Claim 은 limit 개까지만 잡고, 기한이 이른 것부터 잡는다.
func TestClaimCopyIntentsHonorsLimitOldestDueFirst(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	var ids []int64
	for i := range int64(3) {
		c, err := OpenCopyIntent(ctx, pool, ciRange("ci-limit", i, i), time.Hour)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := pool.Exec(ctx, `UPDATE vod_copy_intents SET due_at = pg_catalog.now() - $2::bigint * interval '1 minute'
			WHERE intent_id = $1`, c.ID, 3-i); err != nil {
			t.Fatal(err)
		}
		ids = append(ids, c.ID)
	}

	got, err := ClaimCopyIntents(ctx, pool, 2)

	if err != nil || !slices.Equal(ciIDs(got), ids[:2]) {
		t.Errorf("Claim(2) = (%v, %v), want 기한이 이른 둘 %v", ciIDs(got), err, ids[:2])
	}
}

// 경계 검증 — 빈 스트림 · 뒤집힌 범위 · 음수 seq · 빈 세션 이름 · 양수가 아닌 기간은 문장을 보내기 전에 거절한다.
// 표의 CHECK 는 같은 범위 규칙을 DB 에서도 지킨다. Claim 의 쪽 크기도 양수여야 한다.
func TestCopyIntentRejectsInvalidInput(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	tests := []struct {
		name string
		r    CopyIntentRange
		hold time.Duration
	}{
		{name: "빈 스트림", r: ciRange("", 0, 1), hold: time.Hour},
		{name: "뒤집힌 범위", r: ciRange("ci-bad", 5, 4), hold: time.Hour},
		{name: "음수 seq", r: ciRange("ci-bad", -1, 4), hold: time.Hour},
		{name: "빈 세션 이름", r: ciRange("ci-bad", 0, 4, "s", ""), hold: time.Hour},
		{name: "기간 0", r: ciRange("ci-bad", 0, 4), hold: 0},
		{name: "음수 기간", r: ciRange("ci-bad", 0, 4), hold: -time.Second},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got, err := OpenCopyIntent(ctx, pool, tt.r, tt.hold); err == nil {
				t.Errorf("Open(%+v, %v) = %+v, want 오류", tt.r, tt.hold, got)
			}
		})
	}
	if n := ciCount(t, pool, "ci-bad"); n != 0 {
		t.Errorf("거절된 Open 이 기록 %d 개를 남겼다", n)
	}
	if _, err := ClaimCopyIntents(ctx, pool, 0); err == nil {
		t.Error("Claim(0) 오류 = nil, want 오류")
	}
	_, err := pool.Exec(ctx, `INSERT INTO vod_copy_intents (stream_id, first_seq, last_seq, due_at)
		VALUES ('ci-bad', 5, 4, pg_catalog.now())`)
	if !isSQLState(err, "23514") {
		t.Errorf("뒤집힌 범위 직접 INSERT 오류 = %v, want 23514(CHECK)", err)
	}
}

// 세션 목록이 비면 빈 배열로 남는다(NULL 아님) — 유예 재삭제가 init 짝 없음을 따로 가르지 않게.
func TestCopyIntentStoresEmptyInitSessionsAsEmptyArray(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	opened, err := OpenCopyIntent(ctx, pool, ciRange("ci-noinit", 0, 2), time.Hour)
	if err != nil {
		t.Fatal(err)
	}

	n := expCount(t, pool, `SELECT count(*) FROM vod_copy_intents
		WHERE intent_id = $1 AND init_sessions IS NOT NULL AND pg_catalog.cardinality(init_sessions) = 0`, opened.ID)

	if n != 1 || opened.InitSessions == nil || len(opened.InitSessions) != 0 {
		t.Errorf("빈 세션 목록 행 = %d · Open 결과 %#v, want 1 · 빈 슬라이스", n, opened.InitSessions)
	}
}

// 만료가 그 스트림의 조각 · 세션 행을 지워도 기록은 남는다 — 외래키가 없다.
func TestCopyIntentSurvivesExpiryDeleteRows(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	const stream = "ci-expiry"
	expPutSession(t, pool, expSessionRow{id: "ci-exp-a", stream: stream})
	expPutSeg(t, pool, expSegRow{stream: stream, seq: 0, session: "ci-exp-a"}, expSegRow{stream: stream, seq: 1, session: "ci-exp-a"})
	if _, err := OpenCopyIntent(ctx, pool, ciRange(stream, 0, 1, "ci-exp-a"), time.Hour); err != nil {
		t.Fatal(err)
	}

	res, err := DeleteExpired(ctx, pool, stream, []int64{0, 1})

	if err != nil || res.RowsDeleted != 2 || !slices.Equal(res.SessionsDeleted, []string{"ci-exp-a"}) {
		t.Fatalf("DeleteExpired = (%+v, %v), want 행 2 · 세션 ci-exp-a", res, err)
	}
	if n := ciCount(t, pool, stream); n != 1 {
		t.Errorf("만료 뒤 기록 = %d, want 1", n)
	}
}

// clip 탈퇴 정리의 묶음 삭제 문장(app_clip)은 이 표를 건드리지 않는다 — 조각 행이 지워져도 기록은 남고, app_clip 은
// 이 표에 아무 권한이 없다.
func TestCopyIntentUntouchedByClipPurgeAndClipHasNoAccess(t *testing.T) {
	admin := newTestPool(t)
	ctx := context.Background()
	const stream = "ci-purge"
	for seq := range int64(3) {
		lcPut(t, admin, lcRow{stream: stream, seq: seq})
	}
	if _, err := OpenCopyIntent(ctx, admin, ciRange(stream, 0, 2), time.Hour); err != nil {
		t.Fatal(err)
	}
	clip := clipRolePool(t, admin)

	n, purgeErr := lcClipPurge(ctx, clip, stream, 0, 1, 2)

	if purgeErr != nil || n != 3 {
		t.Fatalf("탈퇴 정리 = (%d, %v), want (3, nil)", n, purgeErr)
	}
	if got := ciCount(t, admin, stream); got != 1 {
		t.Errorf("탈퇴 정리 뒤 기록 = %d, want 1", got)
	}
	for _, s := range []string{
		"SELECT count(*) FROM vod_copy_intents",
		"INSERT INTO vod_copy_intents (stream_id, first_seq, last_seq, due_at) VALUES ('x', 0, 0, now())",
		"UPDATE vod_copy_intents SET due_at = now()",
		"DELETE FROM vod_copy_intents",
	} {
		if _, err := clip.Exec(ctx, s); !pgtest.IsInsufficientPrivilege(err) {
			t.Errorf("app_clip %q 오류 = %v, want 42501", s, err)
		}
	}
}

// 사이드카 계정(media_indexer)으로 세 문장이 42501 없이 돌고, public 에 심은 덫을 부르지 않는다. 표를 통째로 비우는
// TRUNCATE 는 막힌다.
func TestCopyIntentStatementsRunUnderMediaIndexerRole(t *testing.T) {
	admin := newTestPool(t)
	ctx := context.Background()
	traps := pgtest.PlantSearchPathTraps(t, admin)
	role := pgtest.NewMediaIndexerRole(t, admin)
	t.Cleanup(func() { role.RequireNoneDenied(t) })

	opened, err := OpenCopyIntent(ctx, role.Pool, ciRange("ci-role", 0, 3, "ci-role-s"), time.Hour)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	ciMakeDue(t, admin, opened.ID)
	claimed, err := ClaimCopyIntents(ctx, role.Pool, 4)
	if err != nil || len(claimed) != 1 {
		t.Fatalf("Claim = (%+v, %v), want 하나", claimed, err)
	}
	if err := CloseCopyIntent(ctx, role.Pool, opened.ID); err != nil {
		t.Fatalf("Close: %v", err)
	}

	role.RequireSent(t, map[string]string{
		"openCopyIntentSQL": openCopyIntentSQL, "claimCopyIntentsSQL": claimCopyIntentsSQL, "closeCopyIntentSQL": closeCopyIntentSQL,
	})
	traps.RequireNotCalled(t)
	probe := pgtest.NewMediaIndexerRole(t, admin) // 거부 기록이 위 역할의 RequireNoneDenied 에 섞이지 않게 따로 연다
	if _, err := probe.Pool.Exec(ctx, "TRUNCATE vod_copy_intents"); !pgtest.IsInsufficientPrivilege(err) {
		t.Errorf("TRUNCATE 오류 = %v, want 42501", err)
	}
}

// 기동 스키마 확인은 회수 기록 표가 없으면 그 이름을 돌려준다 — 운영 SQL 이 빠진 DB 에서 사이드카가 기동을 멈춘다.
func TestMissingLedgerSchemaNamesCopyIntentsTable(t *testing.T) {
	admin := pgtest.Pool(t, "ledgercheck", EnsureSchema, resetTables)
	ctx := context.Background()
	role := pgtest.NewMediaIndexerRole(t, admin)
	if _, err := admin.Exec(ctx, `DROP TABLE vod_copy_intents`); err != nil {
		t.Fatalf("표 지우기 실패: %v", err)
	}
	t.Cleanup(func() { // 풀보다 늦게 등록 — 풀이 닫히기 전에 돈다
		if err := EnsureSchema(context.Background(), admin); err != nil {
			t.Errorf("스키마 되돌리기 실패: %v", err)
		}
	})

	got, err := MissingLedgerSchema(ctx, role.Pool)

	if err != nil || !slices.Equal(got, []string{"vod_copy_intents"}) {
		t.Errorf("표 없는 DB 의 MissingLedgerSchema = (%q, %v), want ([vod_copy_intents], nil)", got, err)
	}
}

// 운영 SQL — 10 ~ 40 을 적용하면 회수 기록 표가 media_owner 소유로 생기고, 40 은 이 표의 권한 · 색인이 어긋나면 그
// 단언 하나로 실패한다(사이드카 권한 회수 · app_clip 에 부여 · 기한 색인 삭제).
func TestProdSQLVerifyCatchesCopyIntentDrift(t *testing.T) {
	env := newProdSQLEnv(t)
	env.mustApplyAll(t)
	tests := []struct {
		name   string
		tamper string
		want   []string
	}{
		{
			name:   "indexer delete revoked",
			tamper: "REVOKE DELETE ON vod_copy_intents FROM media_indexer",
			want:   []string{"privilege:media_indexer:vod_copy_intents:DELETE"},
		},
		{
			name:   "clip select granted",
			tamper: "GRANT SELECT ON vod_copy_intents TO app_clip",
			want:   []string{"privilege:app_clip:vod_copy_intents:SELECT"},
		},
		{
			name:   "due index dropped",
			tamper: "DROP INDEX vod_copy_intents_due_idx",
			want:   []string{"index:vod_copy_intents_due_idx"},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := env.verifyFailuresAfter(t, tt.tamper); !slices.Equal(got, tt.want) {
				t.Errorf("%s 뒤 40_verify.sql 실패 단언 = %q, want %q", tt.tamper, got, tt.want)
			}
		})
	}
}
