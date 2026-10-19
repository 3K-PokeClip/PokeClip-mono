package index

// 조건 1 시험 중 표 잠금 · 행 삭제 경로(계획 4-6 (k) · (l) — POK-369 T1).
// (k) EnsureSchema(W13): 정방향 대기 상한 · 역방향 StageCommit 55P03 · 이행 기동 교착.
// (l) clip 탈퇴 정리 DELETE(W16): 정방향 대기 상한 · 역방향 Busy · 커밋 뒤 Gone · 여러 행 시차 경합.
// 고정물 · 도우미는 local_cleanup_lock_pg_test.go 에 있다. 잠금 대기를 보는 도우미(표 잠금 대기 ·
// 대기 세션 수 · 행 잠금 탐침)는 이 파일에 있다.

import (
	"context"
	"os"
	"slices"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// lcDDLThroughUniqueIndex 는 EnsureSchema 의 앞부분 — 표 생성부터 local_path 고유 인덱스까지다.
func lcDDLThroughUniqueIndex(t *testing.T) string {
	t.Helper()
	const mark = "CREATE UNIQUE INDEX IF NOT EXISTS stream_segments_local_path_uq"
	at := strings.Index(schemaDDL, mark)
	if at < 0 {
		t.Fatal("schemaDDL 에 local_path 고유 인덱스 문장이 없다")
	}
	end := strings.Index(schemaDDL[at:], ";")
	return schemaDDL[:at+end+1]
}

// 4-6 (k) ① 정방향 · W13 — janitor 가 L 을 쥔 동안 EnsureSchema 는 ≤ 8s + 1s 안에 끝난다.
func TestRowLockEnsureSchemaWaitIsBounded(t *testing.T) {
	pool := newTestPool(t)
	for i, phase := range []string{"ⓐ", "ⓑ"} {
		t.Run(phase, func(t *testing.T) {
			fix := lcLockFixture(t, pool, "lc-lock-k1-"+strconv.Itoa(i))
			finish := lcEnterPhase(t, pool, fix, phase)
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()

			began := time.Now()
			err := EnsureSchema(ctx, pool)
			took := time.Since(began)

			if err != nil || took > RowLockTxnTimeout+time.Second {
				t.Fatalf("EnsureSchema = %v · %v, want nil · ≤ %v", err, took, RowLockTxnTimeout+time.Second)
			}
			t.Logf("기록: %s 중 EnsureSchema 대기 = %v", phase, took)
			if phase == "ⓐ" {
				if jr := finish(); jr.err != nil || jr.res.Outcome != RowLockCleared {
					t.Fatalf("janitor = (%+v, %v), want Cleared", jr.res, jr.err)
				}
			}
		})
	}
}

// 4-6 (k) ② 역방향 · W13 — EnsureSchema 의 CREATE UNIQUE INDEX IF NOT EXISTS 가 표 SHARE 를 쥔
// 트랜잭션이 열려 있으면, janitor 의 UPDATE(ROW EXCLUSIVE)는 lock_timeout 2s 에 55P03 이다.
// 파일은 이미 지웠으므로 StageCommit · Acted · Cleared(T2 가 부채 deleted 로 받는다). 회수
// ClearLocalPath 는 보유가 끝난 뒤 성공한다.
func TestRowLockUpdateTimesOutBehindSchemaIndexShareLock(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	lcPut(t, pool, lcRow{stream: "lc-lock-k2", seq: 0})
	holder, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("보유 트랜잭션 시작 실패: %v", err)
	}
	defer func() { _ = holder.Rollback(ctx) }()
	if _, err := holder.Exec(ctx, lcDDLThroughUniqueIndex(t)); err != nil {
		t.Fatalf("EnsureSchema 앞부분 실행 실패: %v", err)
	}
	modes := lcHeldTableLocks(t, holder)
	t.Logf("기록: CREATE UNIQUE INDEX IF NOT EXISTS 뒤 보유 표 잠금 = %v", modes)
	if !slices.Contains(modes, "ShareLock") {
		t.Fatalf("보유 표 잠금 = %v, want ShareLock 포함 — CREATE INDEX IF NOT EXISTS 가 SHARE 를 잡지 않는다면 W13 역방향 판정을 다시 하라", modes)
	}
	file := lcFileFixture(t)
	store := NewLocalCleanupStore(lcJanitorPool(t, pool, lcJanitorOpts{}))
	jctx, jcancel := context.WithTimeout(ctx, lcHoldDeadline)
	defer jcancel()

	res, err := store.RemoveUnderRowLock(jctx, "lc-lock-k2", 0, lcPath("lc-lock-k2", 0), func() bool {
		return os.Remove(file) == nil
	})

	if !IsLockTimeout(err) || res != (RowLockResult{Acted: true, Cleared: true, Stage: StageCommit}) {
		t.Fatalf("RemoveUnderRowLock = (%+v, %v), want (Acted · Cleared · StageCommit, 55P03)", res, err)
	}
	if lcFileExists(t, file) {
		t.Error("파일이 남았다 — act 가 돌지 않았다")
	}
	if p := lcLocalPath(t, pool, "lc-lock-k2", 0); p == nil {
		t.Error("커밋되지 않은 NULL 이 보인다")
	}
	_ = holder.Rollback(ctx)
	if ok, err := store.ClearLocalPath(ctx, "lc-lock-k2", 0, lcPath("lc-lock-k2", 0)); err != nil || !ok {
		t.Fatalf("회수 ClearLocalPath = (%v, %v), want (true, nil)", ok, err)
	}
}

// lcHeldTableLocks 는 tx 의 백엔드가 stream_segments 에 쥔 표 잠금 모드다.
func lcHeldTableLocks(t *testing.T, tx pgx.Tx) []string {
	t.Helper()
	rows, err := tx.Query(context.Background(), `
SELECT mode FROM pg_locks
 WHERE pid = pg_backend_pid() AND locktype = 'relation' AND granted
   AND relation = 'stream_segments'::regclass ORDER BY mode`)
	if err != nil {
		t.Fatalf("pg_locks 조회 실패: %v", err)
	}
	modes, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		t.Fatalf("pg_locks 스캔 실패: %v", err)
	}
	return modes
}

// lcMigratingPool 은 이행 기동 국면을 만든 전용 DB 다. CHECK 하나를 지워 두면 다음 EnsureSchema 가
// ALTER TABLE … ADD CONSTRAINT(ACCESS EXCLUSIVE)를 실제로 돈다. 정리 때 EnsureSchema 로 되돌린다.
func lcMigratingPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	pool := pgtest.Pool(t, "lcmigrate", EnsureSchema, resetTables)
	if _, err := pool.Exec(context.Background(),
		`ALTER TABLE stream_segments DROP CONSTRAINT IF EXISTS stream_segments_upload_state_chk`); err != nil {
		t.Fatalf("이행 국면 만들기 실패: %v", err)
	}
	t.Cleanup(func() { // 풀보다 늦게 등록 — 풀이 닫히기 전에 돈다
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := EnsureSchema(ctx, pool); err != nil {
			t.Errorf("이행 국면 되돌리기 실패: %v", err)
		}
	})
	return pool
}

// lcWaitForTableLockWait 는 stream_segments 표 잠금 mode 요청이 대기열에 선 것을 기다린다.
func lcWaitForTableLockWait(t *testing.T, pool *pgxpool.Pool, mode string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		var waiting bool
		err := pool.QueryRow(context.Background(), `
SELECT EXISTS (SELECT 1 FROM pg_locks
                WHERE locktype = 'relation' AND relation = 'stream_segments'::regclass
                  AND mode = $1 AND NOT granted)`, mode).Scan(&waiting)
		if err != nil {
			t.Fatalf("pg_locks 조회 실패: %v", err)
		}
		if waiting {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("5s 안에 %s 대기가 보이지 않는다", mode)
}

// lcProbe 는 탐침이 행 잠금을 얻은 시각(또는 실패)이다.
type lcProbe struct {
	at  time.Time
	err error
}

// lcStartLockProbe 는 다른 세션에서 그 행을 (기다려서) FOR UPDATE 로 잡고, 잡은 시각을 보낸다.
// 잡은 뒤 곧바로 되돌린다 — 뒤에 선 대기자를 오래 막지 않는다. 대기 상한은 12s 다.
func lcStartLockProbe(pool *pgxpool.Pool, stream string, seq int64) <-chan lcProbe {
	out := make(chan lcProbe, 1)
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
		defer cancel()
		tx, err := pool.Begin(ctx)
		if err != nil {
			out <- lcProbe{err: err}
			return
		}
		_, err = tx.Exec(ctx, `SELECT 1 FROM stream_segments WHERE stream_id=$1 AND seq=$2 FOR UPDATE`, stream, seq)
		at := time.Now()
		_ = tx.Rollback(ctx)
		out <- lcProbe{at: at, err: err}
	}()
	return out
}

func lcAwaitProbe(t *testing.T, ch <-chan lcProbe) lcProbe {
	t.Helper()
	select {
	case p := <-ch:
		return p
	case <-time.After(15 * time.Second):
		t.Fatal("탐침이 15s 안에 끝나지 않았다")
		return lcProbe{}
	}
}

// lcWaitLockWaiters 는 이 DB 에서 잠금을 기다리는 세션이 n 개 이상 될 때까지 기다린다(5s 상한).
func lcWaitLockWaiters(t *testing.T, pool *pgxpool.Pool, n int) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		var waiting int
		err := pool.QueryRow(context.Background(), `
SELECT count(*) FROM pg_stat_activity
 WHERE datname = current_database() AND wait_event_type = 'Lock'`).Scan(&waiting)
		if err != nil {
			t.Fatalf("pg_stat_activity 조회 실패: %v", err)
		}
		if waiting >= n {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("5s 안에 잠금 대기 세션 %d 개가 보이지 않는다", n)
}

// 4-6 (k) ③ · D42 · L26 — 이행 기동에서 EnsureSchema(SHARE 보유 → ACCESS EXCLUSIVE 요청)와
// janitor(ROW SHARE 보유 → UPDATE 의 ROW EXCLUSIVE 요청)가 서로를 기다리면 교착이다. PG 는 이
// 모양을 deadlock_timeout 을 기다리지 않고 **늦게 요청한 쪽**에서 곧바로 잡는다(조기 교착 탐지 —
// 새 요청자가 이미 기다리는 요청을 막으면서 그 요청자의 보유 잠금에 막히면 즉시 40P01).
//
//   - EnsureSchema 가 먼저 대기(실물 EnsureSchema 한 번 — 구조상 흔한 순서다. janitor 의 act
//     구간이 EnsureSchema 의 SHARE → ACCESS EXCLUSIVE 간격보다 훨씬 길어, 둘이 겹치면
//     ACCESS EXCLUSIVE 요청은 대개 janitor UPDATE 앞에 떨어진다): janitor 가 희생된다
//     → 40P01 · StageCommit · 파일 부재 · 장부 그대로 → 회수 ClearLocalPath 로 NULL. PASS/FAIL.
//   - janitor 가 먼저 대기(EnsureSchema 의 SHARE 와 ACCESS EXCLUSIVE 사이에 janitor UPDATE 가 끼는
//     좁은 창 — 같은 DDL 을 두 번에 나눠 흉내 낸다): EnsureSchema 가 희생된다 → 사이드카 기동 실패
//     · 재기동(L26). janitor 는 그 뒤 Cleared. PASS/FAIL 로 고정하고 L26 근거로 기록한다.
func TestRowLockMigrationDeadlockLateRequesterIsVictim(t *testing.T) {
	t.Run("EnsureSchema 가 먼저 대기 — janitor 희생", func(t *testing.T) {
		pool := lcMigratingPool(t)
		const s = "lc-dl-a"
		lcPut(t, pool, lcRow{stream: s, seq: 0})
		file := lcFileFixture(t)
		store := NewLocalCleanupStore(lcJanitorPool(t, pool, lcJanitorOpts{}))
		ctx, cancel := context.WithTimeout(context.Background(), lcHoldDeadline)
		h := lcStartHold(ctx, store, s, 0, lcPath(s, 0))
		t.Cleanup(func() { cancel(); <-h.finished })
		h.waitLocked(t)
		schemaErr := make(chan error, 1)
		go func() {
			ectx, ecancel := context.WithTimeout(context.Background(), 15*time.Second)
			defer ecancel()
			schemaErr <- EnsureSchema(ectx, pool)
		}()
		lcWaitForTableLockWait(t, pool, "AccessExclusiveLock")

		if err := os.Remove(file); err != nil { // act — 잠금을 쥔 채 지운다
			t.Fatalf("파일 삭제 실패: %v", err)
		}
		released := time.Now()
		h.release <- true
		jr := h.wait(t)
		took := time.Since(released)
		esErr := lcAwait(t, schemaErr)

		t.Logf("기록: janitor = (%+v, %v) · %v · EnsureSchema = %v", jr.res, jr.err, took, esErr)
		if !IsDeadlock(jr.err) || jr.res != (RowLockResult{Acted: true, Cleared: true, Stage: StageCommit}) || esErr != nil {
			t.Fatalf("(janitor, EnsureSchema) = ((%+v, %v), %v), want ((Acted · Cleared · StageCommit, 40P01), nil)", jr.res, jr.err, esErr)
		}
		if lcFileExists(t, file) {
			t.Error("파일이 남았다")
		}
		if p := lcLocalPath(t, pool, s, 0); p == nil {
			t.Fatal("janitor 가 희생됐는데 NULL 이 커밋됐다")
		}
		if ok, err := store.ClearLocalPath(context.Background(), s, 0, lcPath(s, 0)); err != nil || !ok {
			t.Fatalf("회수 ClearLocalPath = (%v, %v), want (true, nil)", ok, err)
		}
	})
	t.Run("janitor 가 먼저 대기 — EnsureSchema 희생", func(t *testing.T) {
		pool := lcMigratingPool(t)
		ctx := context.Background()
		const s = "lc-dl-b"
		lcPut(t, pool, lcRow{stream: s, seq: 0})
		store := NewLocalCleanupStore(lcJanitorPool(t, pool, lcJanitorOpts{}))
		hctx, cancel := context.WithTimeout(ctx, lcHoldDeadline)
		h := lcStartHold(hctx, store, s, 0, lcPath(s, 0))
		t.Cleanup(func() { cancel(); <-h.finished })
		h.waitLocked(t)
		schema, err := pool.Begin(ctx)
		if err != nil {
			t.Fatalf("EnsureSchema 흉내 트랜잭션 시작 실패: %v", err)
		}
		defer func() { _ = schema.Rollback(ctx) }()
		head := lcDDLThroughUniqueIndex(t)
		if _, err := schema.Exec(ctx, head); err != nil { // SHARE 를 쥔다
			t.Fatalf("EnsureSchema 앞부분 실패: %v", err)
		}
		h.release <- true
		lcWaitForTableLockWait(t, pool, "RowExclusiveLock") // janitor UPDATE 가 SHARE 뒤에 섰다

		_, esErr := schema.Exec(ctx, strings.TrimPrefix(schemaDDL, head)) // ACCESS EXCLUSIVE 요청
		_ = schema.Rollback(ctx)
		jr := h.wait(t)

		t.Logf("기록: EnsureSchema 뒷부분 = %v · janitor = (%+v, %v)", esErr, jr.res, jr.err)
		if !IsDeadlock(esErr) || jr.err != nil || jr.res.Outcome != RowLockCleared {
			t.Fatalf("(EnsureSchema, janitor) = (%v, (%+v, %v)), want (40P01, Cleared)", esErr, jr.res, jr.err)
		}
	})
}

// lcAwait 는 결과 채널을 15s 까지 기다린다.
func lcAwait(t *testing.T, ch <-chan error) error {
	t.Helper()
	select {
	case err := <-ch:
		return err
	case <-time.After(15 * time.Second):
		t.Fatal("15s 안에 끝나지 않았다")
		return nil
	}
}

// lcClipPurgeSQL 은 clip 탈퇴 정리의 행 삭제와 같은 형상이다(StreamerPurgeStore.java:176-183).
const lcClipPurgeSQL = `
DELETE FROM stream_segments s
 USING unnest($1::text[], $2::bigint[]) AS d(stream_id, seq)
 WHERE s.stream_id = d.stream_id AND s.seq = d.seq`

type lcExecer interface {
	Exec(ctx context.Context, sql string, args ...any) (pgconn.CommandTag, error)
}

func lcClipPurge(ctx context.Context, db lcExecer, stream string, seqs ...int64) (int64, error) {
	streams := make([]string, len(seqs))
	for i := range seqs {
		streams[i] = stream
	}
	tag, err := db.Exec(ctx, lcClipPurgeSQL, streams, seqs)
	return tag.RowsAffected(), err
}

type lcPurgeResult struct {
	deleted int64
	err     error
	took    time.Duration
}

func lcStartClipPurge(pool *pgxpool.Pool, stream string, seqs ...int64) chan lcPurgeResult {
	out := make(chan lcPurgeResult, 1)
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		defer cancel()
		began := time.Now()
		n, err := lcClipPurge(ctx, pool, stream, seqs...)
		out <- lcPurgeResult{n, err, time.Since(began)}
	}()
	return out
}

// 4-6 (l) 정방향 · D41 · W16 — janitor 가 쥔 행을 clip DELETE 가 기다려도 ≤ 8s + 1s 안에 끝나고
// 오류가 없다. ⓐ 는 janitor 커밋 뒤 다시 평가해 지우고, ⓑ 는 서버가 janitor 를 끝낸 뒤 지운다.
func TestRowLockClipPurgeWaitIsBounded(t *testing.T) {
	pool := newTestPool(t)
	for i, phase := range []string{"ⓐ", "ⓑ"} {
		t.Run(phase, func(t *testing.T) {
			fix := lcLockFixture(t, pool, "lc-lock-l-"+strconv.Itoa(i))
			finish := lcEnterPhase(t, pool, fix, phase)
			purge := lcStartClipPurge(pool, fix.stream, lcSeqL)
			const hold = time.Second
			if phase == "ⓐ" {
				time.Sleep(hold)
				if jr := finish(); jr.err != nil || jr.res.Outcome != RowLockCleared {
					t.Fatalf("janitor = (%+v, %v), want Cleared", jr.res, jr.err)
				}
			}
			r := <-purge

			limit := RowLockTxnTimeout + time.Second
			if phase == "ⓐ" {
				limit = hold + time.Second
			}
			if r.err != nil || r.deleted != 1 || r.took > limit {
				t.Fatalf("clip DELETE = (%d행, %v) · %v, want (1행, nil) · ≤ %v", r.deleted, r.err, r.took, limit)
			}
			t.Logf("기록: %s 중 clip DELETE 대기 = %v", phase, r.took)
		})
	}
}

// 4-6 (l) 역방향 · D41 — clip DELETE 가 쥔(미커밋) 행은 Busy · 파일 무접촉이고, 커밋 뒤에는 Gone 이다
// (PathChanged 가 아니다 — 경로 경합 WARN 을 헛켜지 않는다).
func TestRowLockSeesClipPurgeAsBusyThenGone(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	fix := lcLockFixture(t, pool, "lc-lock-l-rev")
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("DELETE 트랜잭션 시작 실패: %v", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if n, err := lcClipPurge(ctx, tx, fix.stream, lcSeqL); err != nil || n != 1 {
		t.Fatalf("미커밋 DELETE = (%d, %v), want (1, nil)", n, err)
	}
	store := NewLocalCleanupStore(lcJanitorPool(t, pool, lcJanitorOpts{}))

	busy, err := store.RemoveUnderRowLock(ctx, fix.stream, lcSeqL, fix.pathL, lcMustNotRemove(t))
	if err != nil || busy != (RowLockResult{Outcome: RowLockBusy}) {
		t.Fatalf("DELETE 미커밋 중 = (%+v, %v), want (Busy, nil)", busy, err)
	}
	if err := tx.Commit(ctx); err != nil {
		t.Fatalf("DELETE 커밋 실패: %v", err)
	}
	gone, err := store.RemoveUnderRowLock(ctx, fix.stream, lcSeqL, fix.pathL, lcMustNotRemove(t))
	if err != nil || gone != (RowLockResult{Outcome: RowLockGone}) {
		t.Fatalf("DELETE 커밋 뒤 = (%+v, %v), want (Gone, nil)", gone, err)
	}
}

// 4-6 (l) 여러 행 시차 경합(확인 패스 d5) — 한 DELETE 문장이 janitor 가 쥔 행 둘을 차례로 만나면
// 대기가 두 번 쌓이고, 합은 「겹친 janitor 행 수 × 보유」 + 1s 안이다.
//
// 실물 janitor 는 한 행을 놓은 뒤에 다음 행을 잡는다. 그 사이 DELETE 가 먼저 다음 행에 닿으면 두 번째
// 대기가 없다(경합 비결정 — 이전 반복 10/10 이 두 번째 행 Busy 였다). 그래서 두 행을 DELETE 전에 잡아
// 두고, DELETE 를 막은 보유자를 보유 뒤 풀고, DELETE 가 남은 보유자에 막힌 것을 본 뒤 보유 뒤 푼다.
// DELETE 가 어느 행을 먼저 찾아가는지(실행 계획)와 무관하게 두 번 기다린다.
func TestRowLockClipPurgeAcrossStaggeredRowLocks(t *testing.T) {
	pool := newTestPool(t)
	fix := lcLockFixture(t, pool, "lc-lock-l-multi")
	var pidL, pidU atomic.Uint32
	storeL := NewLocalCleanupStore(lcJanitorPool(t, pool, lcJanitorOpts{tracer: lcPIDTracer(&pidL)}))
	storeU := NewLocalCleanupStore(lcJanitorPool(t, pool, lcJanitorOpts{tracer: lcPIDTracer(&pidU)}))
	ctx, cancel := context.WithTimeout(context.Background(), lcHoldDeadline)
	defer cancel()
	const hold = time.Second
	holdL := lcStartHold(ctx, storeL, fix.stream, lcSeqL, fix.pathL)
	holdL.waitLocked(t)
	holdU := lcStartHold(ctx, storeU, fix.stream, lcSeqU, segAt(fix.stream, lcSeqU, time.Time{}, 0).LocalPath)
	holdU.waitLocked(t)
	purge := lcStartClipPurge(pool, fix.stream, lcSeqL, lcSeqU)

	firstPID := lcWaitPurgeBlocker(t, pool, 0) // 첫 대기
	first, second, secondPID, firstRow := holdL, holdU, pidU.Load(), "L"
	switch firstPID {
	case pidL.Load():
	case pidU.Load():
		first, second, secondPID, firstRow = holdU, holdL, pidL.Load(), "U"
	default:
		t.Fatalf("clip DELETE 를 막은 백엔드 = %d, want janitor 둘(%d · %d) 중 하나", firstPID, pidL.Load(), pidU.Load())
	}
	time.Sleep(hold)
	first.release <- true
	if jr := first.wait(t); jr.err != nil || jr.res.Outcome != RowLockCleared {
		t.Fatalf("먼저 푼 janitor = (%+v, %v), want Cleared", jr.res, jr.err)
	}
	if blocker := lcWaitPurgeBlocker(t, pool, firstPID); blocker != secondPID { // 두 번째 대기
		t.Fatalf("두 번째 대기의 막은 백엔드 = %d, want 남은 janitor %d", blocker, secondPID)
	}
	time.Sleep(hold)
	second.release <- true
	jr := second.wait(t)
	r := <-purge

	if jr.err != nil || jr.res.Outcome != RowLockCleared {
		t.Fatalf("나중에 푼 janitor = (%+v, %v), want Cleared", jr.res, jr.err)
	}
	if r.err != nil || r.deleted != 2 || r.took < 2*hold || r.took > 2*hold+time.Second {
		t.Fatalf("clip DELETE = (%d행, %v) · %v, want (2행, nil) · %v ≤ 대기 ≤ %v", r.deleted, r.err, r.took, 2*hold, 2*hold+time.Second)
	}
	t.Logf("기록: 두 행 시차 경합 clip DELETE 대기 = %v (먼저 막힌 행 %s)", r.took, firstRow)
}

// lcPIDTracer 는 선잠금 문장을 보낸 연결의 백엔드 PID 를 pid 에 남긴다.
func lcPIDTracer(pid *atomic.Uint32) lcTracer {
	return lcTracer{onStart: func(conn *pgx.Conn, sql string) {
		if sql == rowLockSQL {
			pid.Store(conn.PgConn().PID())
		}
	}}
}

// lcWaitPurgeBlocker 는 clip DELETE 가 except 아닌 백엔드에 막혀 기다릴 때까지 기다리고, 막은
// 백엔드의 PID 를 돌려준다(5s 상한).
func lcWaitPurgeBlocker(t *testing.T, pool *pgxpool.Pool, except uint32) uint32 {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		var blocker *int64
		err := pool.QueryRow(context.Background(), `
SELECT max(b) FROM pg_stat_activity, unnest(pg_blocking_pids(pid)) AS b
 WHERE query = $1 AND b <> $2`, lcClipPurgeSQL, int64(except)).Scan(&blocker)
		if err != nil {
			t.Fatalf("pg_stat_activity 조회 실패: %v", err)
		}
		if blocker != nil {
			return uint32(*blocker)
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("5s 안에 clip DELETE 가 백엔드(%d 제외)에 막힌 것이 보이지 않는다", except)
	return 0
}
