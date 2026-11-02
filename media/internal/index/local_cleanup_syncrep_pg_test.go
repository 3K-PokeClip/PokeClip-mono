package index

// 4-6 (d″) 결정적 시험(이월 C19 — T1 귀속 누락분). 커밋 단계의 동기 복제 대기를 결정적으로 만든다:
// 없는 동기 대기 서버 이름(synchronous_standby_names='nonexistent')을 걸면 동기 커밋은 복제 확인을
// 끝없이 기다린다(SyncRepWaitForLSN). janitor 의 잠금 트랜잭션은 2′(synchronous_commit off)라 그
// 대기를 건너뛰므로 COMMIT 이 곧 돌아와 행 잠금이 풀려야 한다.
//
// 이 설정은 그 PG 전체의 동기 커밋을 멈춘다 — 그래서 공유 시험 PG(PG_DSN)가 아니라 전용 실험 PG 의
// 관리 DSN(PG_DSN_SYNCREP)이 있을 때만 돈다. 없으면 skip 이고 REQUIRE_PG 와 무관하다(CI 기본 skip).

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// lcSyncRepEnv 는 전용 실험 PG 의 관리 DSN 을 담는 환경 변수다. ALTER SYSTEM 을 쓰므로 superuser 다.
const lcSyncRepEnv = "PG_DSN_SYNCREP"

// lcSyncRepBound 는 탐침 잠금 획득 · janitor COMMIT 반환의 판정 상한이다(계획 4-6 (d″) — 8s + 300ms).
const lcSyncRepBound = RowLockTxnTimeout + 300*time.Millisecond

// lcSyncRepPool 은 전용 실험 PG 의 시험 DB 풀이다. PG_DSN 과 같은 서버를 가리키면 멈춘다 — 공유
// PG 의 동기 커밋을 멈추지 않게.
func lcSyncRepPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	dsn := os.Getenv(lcSyncRepEnv)
	if dsn == "" {
		t.Skip(lcSyncRepEnv + " 미설정 — 전용 실험 PG 가 있어야 도는 시험이다(공유 PG 금지)")
	}
	syncCfg, err := pgx.ParseConfig(dsn)
	if err != nil {
		t.Fatalf("%s 해석 실패: %v", lcSyncRepEnv, err)
	}
	if shared := os.Getenv("PG_DSN"); shared != "" {
		if sharedCfg, err := pgx.ParseConfig(shared); err == nil &&
			sharedCfg.Host == syncCfg.Host && sharedCfg.Port == syncCfg.Port {
			t.Fatalf("%s 가 PG_DSN 과 같은 서버(%s:%d)다 — 공유 PG 의 동기 커밋을 멈춘다", lcSyncRepEnv, syncCfg.Host, syncCfg.Port)
		}
	}
	t.Setenv("PG_DSN", dsn) // 시험 DB 부트스트랩(이름 가드 · 소유 표식)을 그대로 쓴다
	return newTestPool(t)
}

// lcArmSyncRep 은 동기 복제 대기를 켜고 원복(RESET + reload)을 t.Cleanup 에 건다. 켜기 전 값이
// 비어 있어야 한다 — 전용 PG 의 착수 전 값은 기본값이라 원복 = RESET 이다. 켜진 것은 동기 커밋 하나가
// 실제로 복제 대기에 멈추는 것으로 확인한다(설정 반영은 비동기다 — reload 뒤 checkpointer 가 반영).
func lcArmSyncRep(t *testing.T, pool *pgxpool.Pool) {
	t.Helper()
	ctx := context.Background()
	var before string
	if err := pool.QueryRow(ctx, `SHOW synchronous_standby_names`).Scan(&before); err != nil {
		t.Fatalf("synchronous_standby_names 조회 실패: %v", err)
	}
	if before != "" {
		t.Fatalf("synchronous_standby_names = %q — 전용 실험 PG 가 아니다(원복할 착수 전 값이 기본이 아니다)", before)
	}
	t.Cleanup(func() {
		for _, s := range []string{`ALTER SYSTEM RESET synchronous_standby_names`, `SELECT pg_reload_conf()`} {
			if _, err := pool.Exec(ctx, s); err != nil {
				t.Errorf("동기 복제 설정 원복 실패(%s): %v", s, err)
			}
		}
	})
	for _, s := range []string{`ALTER SYSTEM SET synchronous_standby_names = 'nonexistent'`, `SELECT pg_reload_conf()`} {
		if _, err := pool.Exec(ctx, s); err != nil {
			t.Fatalf("동기 복제 설정 실패(%s): %v", s, err)
		}
	}
	lcAwaitSyncCommitStalls(t, pool)
}

// lcSyncRepControl 은 양성 대조용 행이다(lcArmSyncRep 전에 커밋해 둔다).
const lcSyncRepControl = "lc-syncrep-ctl"

// lcAwaitSyncCommitStalls 는 동기 커밋 하나(대조 행의 local_path 를 그대로 다시 쓰는 자동 커밋
// UPDATE)가 복제 대기(SyncRep)에 멈추는 것을 보고 그 세션을 끊는다 — 자극이 실제로 걸렸다는 양성
// 대조다. 데이터 쓰기가 없는 xid 만의 커밋(SELECT pg_current_xact_id())은 멈추지 않았다(2026-10-20
// 실측 — close/c19-debug.txt).
func lcAwaitSyncCommitStalls(t *testing.T, pool *pgxpool.Pool) {
	t.Helper()
	ctx := context.Background()
	conn := lcSingleConn(t, pool)
	pid := lcBackendPID(t, conn)
	done := make(chan error, 1)
	go func() {
		_, err := conn.Exec(ctx, `UPDATE stream_segments SET local_path = local_path WHERE stream_id = $1 AND seq = 0`, lcSyncRepControl)
		done <- err
	}()
	waited := lcAwaitSyncRepWait(t, pool, pid)
	lcTerminate(t, pool, pid)
	<-done
	if !waited {
		t.Fatal("동기 커밋이 5s 안에 SyncRep 대기에 들지 않았다 — 자극 미도달")
	}
}

// lcSingleConn 은 pool 과 같은 DB 에 붙은 단독 연결이다(풀 밖 — 끊겨도 풀에 영향이 없다).
func lcSingleConn(t *testing.T, pool *pgxpool.Pool) *pgx.Conn {
	t.Helper()
	conn, err := pgx.ConnectConfig(context.Background(), pool.Config().ConnConfig.Copy())
	if err != nil {
		t.Fatalf("단독 연결 실패: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close(context.Background()) })
	return conn
}

type lcQuerier interface {
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

func lcBackendPID(t *testing.T, q lcQuerier) int32 {
	t.Helper()
	var pid int32
	if err := q.QueryRow(context.Background(), `SELECT pg_backend_pid()`).Scan(&pid); err != nil {
		t.Fatalf("pg_backend_pid 조회 실패: %v", err)
	}
	return pid
}

// lcAwaitSyncRepWait 는 pid 세션이 복제 대기(wait_event IPC/SyncRep)에 들 때까지 5s 기다린다.
func lcAwaitSyncRepWait(t *testing.T, pool *pgxpool.Pool, pid int32) bool {
	t.Helper()
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); time.Sleep(10 * time.Millisecond) {
		var typ, ev *string
		if err := pool.QueryRow(context.Background(),
			`SELECT wait_event_type, wait_event FROM pg_stat_activity WHERE pid = $1`, pid).Scan(&typ, &ev); err != nil {
			t.Fatalf("pg_stat_activity 조회 실패(pid %d): %v", pid, err)
		}
		if typ != nil && ev != nil && *typ == "IPC" && *ev == "SyncRep" {
			return true
		}
	}
	return false
}

// lcTerminate 는 pid 세션을 끊는다(계획 4-6 (d″) — 이름이 아니라 시험이 받아 둔 pid 로).
func lcTerminate(t *testing.T, pool *pgxpool.Pool, pid int32) {
	t.Helper()
	if _, err := pool.Exec(context.Background(), `SELECT pg_terminate_backend($1)`, pid); err != nil {
		t.Errorf("세션 끊기 실패(pid %d): %v", pid, err)
	}
}

// 4-6 (d″) — 동기 복제 대기가 걸린 PG 에서도 janitor 의 잠금 트랜잭션은 COMMIT 을 곧 돌려받고 행
// 잠금을 푼다. 탐침은 janitor 가 쥔 행을 기다리다 ≤ 8s + 300ms 안에 잡아야 한다(변이 — 2′
// synchronous_commit 문장 제거 → janitor COMMIT 이 복제 대기에 멈춰 탐침이 대기 상한 12s 로 FAIL).
func TestRowLockCommitDoesNotWaitForSyncReplication(t *testing.T) {
	pool := lcSyncRepPool(t)
	const stream = "lc-syncrep"
	// 고정물은 설정 전에 커밋한다 — 고정물 INSERT 도 동기 커밋이다.
	lcPut(t, pool, lcRow{stream: stream, seq: 0}, lcRow{stream: lcSyncRepControl, seq: 0})
	lcArmSyncRep(t, pool)

	jpool := lcJanitorPool(t, pool, lcJanitorOpts{maxConns: 1})
	jpid := lcBackendPID(t, jpool) // maxConns 1 — RemoveUnderRowLock 이 쓸 바로 그 연결이다
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	h := lcStartHold(ctx, NewLocalCleanupStore(jpool), stream, 0, lcPath(stream, 0))
	// jpool.Close(먼저 등록)보다 먼저 돌아야 한다 — 복제 대기에 멈춘 janitor 연결을 pid 로 끊어야 풀이
	// 닫힌다. lcArmSyncRep 의 설정 원복보다도 먼저다(계획 4-6 (d″) — 끊고 나서 원복).
	t.Cleanup(func() {
		cancel()
		select {
		case <-h.finished:
		default:
			lcTerminate(t, pool, jpid)
			<-h.finished
		}
	})
	h.waitLocked(t)

	began := time.Now()
	probed := lcStartLockProbe(pool, stream, 0) // FOR UPDATE → ROLLBACK, 대기 상한 12s
	lcWaitLockWaiters(t, pool, 1)               // 탐침이 janitor 잠금 뒤에 줄 선 뒤에 COMMIT 하게
	h.release <- true
	released := time.Now()

	pr := lcAwaitProbe(t, probed)
	if waited := pr.at.Sub(began); pr.err != nil || waited > lcSyncRepBound {
		t.Fatalf("탐침 = (대기 %v, %v), want 오류 없이 ≤ %v — janitor 가 COMMIT 단계에서 잠금을 쥔 채 멈췄다",
			waited, pr.err, lcSyncRepBound)
	}
	jr := h.wait(t)
	if took := time.Since(released); jr.err != nil || jr.res.Outcome != RowLockCleared || took > lcSyncRepBound {
		t.Fatalf("janitor = (%+v, %v · 해제 뒤 %v), want Cleared · ≤ %v", jr.res, jr.err, took, lcSyncRepBound)
	}
	if p := lcLocalPath(t, pool, stream, 0); p != nil {
		t.Errorf("local_path = %q, want NULL(비동기 커밋이 반영됐어야 한다)", *p)
	}
	t.Logf("기록: 동기 복제 대기 중 탐침 대기 %v · janitor 해제 뒤 반환 %v", pr.at.Sub(began), time.Since(released))
}
