package index

// 행 잠금 삭제 트랜잭션 되돌림의 PG 통합 검증 — 부른 쪽 ctx 가 끝난 뒤에도 끝나지 않은 ctx 로 되돌려 연결을
// 지키는가(POK-388). PG_DSN 미설정이면 전량 skip 된다(REQUIRE_PG=1 인 CI 가 실주행 게이트다).

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// rowLockRollbackRun 은 RemoveUnderRowLock 한 번을 추적 풀에서 돌린 관측이다.
type rowLockRollbackRun struct {
	res      RowLockResult
	err      error
	probe    *rollbackProbe
	newConns int64 // 호출 뒤 같은 풀의 다음 문장까지 선 새 연결 수
	nowait   error // 호출 직후 다른 세션의 NOWAIT 잠금 결과
}

// runRowLockRollback 은 stream 의 행 하나를 두고 caller 를 부른 쪽 ctx 로 RemoveUnderRowLock 을 부른다.
// 풀은 연결 하나를 미리 세워 둔다 — 그 뒤 선 새 연결은 되돌림이 연결을 버렸다는 뜻이다. 새 연결은 같은 풀의
// 다음 문장이 선 뒤에 센다(최소 연결 수 0 인 풀은 버린 연결을 스스로 채우지 않는다).
func runRowLockRollback(t *testing.T, caller context.Context, stream string, probe *rollbackProbe, remove func() bool) rowLockRollbackRun {
	t.Helper()
	base := newTestPool(t)
	lcPut(t, base, lcRow{stream: stream, seq: 0})
	pool := pgtest.TracedPool(t.Context(), t, base, probe)
	if _, err := pool.Exec(t.Context(), "select 1"); err != nil {
		t.Fatalf("풀 데우기 실패: %v", err)
	}
	conns := pool.Stat().NewConnsCount()

	res, err := NewLocalCleanupStore(pool).RemoveUnderRowLock(caller, stream, 0, lcPath(stream, 0), remove)

	run := rowLockRollbackRun{res: res, err: err, probe: probe}
	run.nowait = lcTryLockRow(t.Context(), base, stream, 0) // 다른 풀 — 추적기를 거치지 않는다
	if _, err := pool.Exec(t.Context(), "select 1"); err != nil {
		t.Fatalf("호출 뒤 같은 풀의 다음 문장 실패: %v", err)
	}
	run.newConns = pool.Stat().NewConnsCount() - conns
	return run
}

// checkRollbackContext 는 되돌림 문장이 한 번 나갔고, 그 ctx 가 되돌리는 순간 + RowLockTxnTimeout 의 시한과
// 부른 쪽 ctx 의 값을 가졌는가다. 시한은 실패 시각과 되돌림 문장 시작 시각 사이에 만들었어야 그 구간에 든다
// (단조 시계끼리의 비교라 허용 오차가 없다).
func checkRollbackContext(t *testing.T, p *rollbackProbe, wantCaller string) {
	t.Helper()
	if p.rollbackStarts != 1 {
		t.Errorf("되돌림 문장 시작 = %d번, want 1번", p.rollbackStarts)
		return // 잴 되돌림 문장이 없거나 하나로 정해지지 않는다
	}
	d, lo, hi := p.rollbackDeadline, p.failedAt.Add(RowLockTxnTimeout), p.rollbackAt.Add(RowLockTxnTimeout)
	switch {
	case !p.hasDeadline:
		t.Errorf("되돌림 ctx 시한 = 없음, want 실패 뒤 [%v, %v](되돌리는 순간 + RowLockTxnTimeout)",
			RowLockTxnTimeout, hi.Sub(p.failedAt))
	case d.Before(lo) || d.After(hi):
		t.Errorf("되돌림 ctx 시한 = 실패 뒤 %v, want 실패 뒤 [%v, %v](되돌리는 순간 + RowLockTxnTimeout)",
			d.Sub(p.failedAt), RowLockTxnTimeout, hi.Sub(p.failedAt))
	}
	if p.rollbackCaller != wantCaller {
		t.Errorf("되돌림 ctx 의 부른 쪽 값 = %v, want %q(부른 쪽 ctx 의 값을 물려받는다)", p.rollbackCaller, wantCaller)
	}
}

// checkLiveRollback 은 되돌림 문장이 끝나지 않은 ctx 로 나가 성공했고, 연결을 버리지 않았고, 행 잠금을 풀었는가다.
func checkLiveRollback(t *testing.T, run rowLockRollbackRun, wantCaller string) {
	t.Helper()
	if len(run.probe.rollbackErrs) != 1 || run.probe.rollbackErrs[0] != nil {
		t.Errorf("되돌림 문장 끝 = %v, want [<nil>](끝나지 않은 ctx)", run.probe.rollbackErrs)
	}
	if run.newConns != 0 {
		t.Errorf("새 연결 = %d개, want 0개(되돌림이 연결을 버리지 않았다)", run.newConns)
	}
	checkRollbackContext(t, run.probe, wantCaller)
	if run.nowait != nil {
		t.Errorf("호출 직후 다른 세션의 NOWAIT = %v, want nil(되돌림이 행 잠금을 풀었다)", run.nowait)
	}
}

// 되돌림 ctx — 행 잠금 트랜잭션은 되돌리는 순간에 만든 끝나지 않은 ctx 로 되돌린다. 끝난 ctx 로 되돌리면 pgx 는
// ROLLBACK 을 보내지 못하고 그 연결을 버린다. 되돌림 자리는 둘이다 — 선잠금 문장이 실패한 뒤의 defer
// (lock_*: 선잠금 문장이 서버로 나가기 전에 ctx 가 끝난다) 와 remove 가 clear=false 를 돌려준 뒤의 finish
// (finish_*: remove 안에서 ctx 가 끝난다). 두 자리 모두 문장과 문장 사이라 서버의 트랜잭션은 되돌림을 받을 수
// 있는 채로 남는다. finish 갈래의 결과는 Kept 다 — 장부는 되돌림 상태와 같다(kty 결정 112). server_ended_session
// 은 서버가 세션을 이미 끝낸 갈래다(R7 — 아래 도우미).
func TestRowLockRollsBackWithLiveContext(t *testing.T) {
	waitDeadline := func(ctx context.Context, _ context.CancelFunc) { <-ctx.Done() }
	cancelNow := func(_ context.Context, cancel context.CancelFunc) { cancel() }
	kept := RowLockResult{Outcome: RowLockKept, Acted: true}
	cases := []struct {
		name string
		// timeout 은 부른 쪽 ctx 의 시한이다. end 는 그 ctx 를 끝낸다 — 시한을 기다리거나 cancel 을 부른다.
		timeout time.Duration
		end     func(ctx context.Context, cancel context.CancelFunc)
		atLock  bool // 참이면 선잠금 문장 시작에서, 거짓이면 remove 안에서 끝낸다
		wantRes RowLockResult
		wantErr error
	}{
		{"lock_deadline_spent", 300 * time.Millisecond, waitDeadline, true, RowLockResult{Stage: StageLock}, context.DeadlineExceeded},
		{"lock_caller_canceled", lcHoldDeadline, cancelNow, true, RowLockResult{Stage: StageLock}, context.Canceled},
		{"finish_deadline_spent", 300 * time.Millisecond, waitDeadline, false, kept, nil},
		{"finish_caller_canceled", lcHoldDeadline, cancelNow, false, kept, nil},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.WithValue(t.Context(), callerValueKey{}, tc.name), tc.timeout)
			defer cancel()
			probe := &rollbackProbe{bound: tc.timeout}
			remove := lcMustNotRemove(t)
			if tc.atLock {
				probe.injectSQL = rowLockSQL
				probe.inject = func(sctx context.Context) context.Context { tc.end(sctx, cancel); return sctx }
			} else {
				remove = func() bool { tc.end(ctx, cancel); probe.failedAt = time.Now(); return false }
			}

			run := runRowLockRollback(t, ctx, "lc-rb-"+tc.name, probe, remove)

			if tc.atLock && (probe.unbounded || probe.injects != 1) {
				t.Fatalf("선잠금 문장 주입 = %d번(시한 없음 %v), want 1번 — 주입이 없으면 아래 단언은 아무것도 재지 않는다",
					probe.injects, probe.unbounded)
			}
			if run.res != tc.wantRes || !errors.Is(run.err, tc.wantErr) { // wantErr 가 nil 이면 오류도 nil 이어야 같다
				t.Errorf("RemoveUnderRowLock() = (%+v, %v), want (%+v, %v)", run.res, run.err, tc.wantRes, tc.wantErr)
			}
			checkLiveRollback(t, run, tc.name)
		})
	}
	t.Run("server_ended_session", testRowLockRollbackAfterServerEndedSession)
}

// testRowLockRollbackAfterServerEndedSession 은 R7 이다 — 서버가 세션을 이미 끝낸 뒤의 되돌림 오류는 삼키지
// 않는다. remove 가 RowLockTxnTimeout 을 넘겨 돌면 서버가 transaction_timeout 으로 세션을 끝내고, clear=false 의
// 되돌림은 끝나지 않은 ctx 로 나가도 25P04 로 실패한다. 결과는 지금처럼 StageCommit 오류다(되돌림 ctx 를 바꾼
// 뒤에도 그대로인지 지키는 회귀 시험).
func testRowLockRollbackAfterServerEndedSession(t *testing.T) {
	const name = "server_ended_session"
	ctx, cancel := context.WithTimeout(context.WithValue(t.Context(), callerValueKey{}, name), RowLockTxnTimeout+5*time.Second)
	defer cancel()
	probe := &rollbackProbe{}
	remove := func() bool {
		time.Sleep(RowLockTxnTimeout + time.Second)
		probe.failedAt = time.Now()
		return false
	}

	run := runRowLockRollback(t, ctx, "lc-rb-"+name, probe, remove)

	t.Logf("기록: 되돌림 오류 = %v (SQLSTATE %s) · 새 연결 %d개", run.err, lcSQLState(run.err), run.newConns)
	if want := (RowLockResult{Acted: true, Stage: StageCommit}); !IsTxnTimeout(run.err) || run.res != want {
		t.Errorf("RemoveUnderRowLock() = (%+v, %v), want (%+v, 25P04 세션 종료)", run.res, run.err, want)
	}
	if len(probe.rollbackErrs) != 1 || probe.rollbackErrs[0] == nil {
		t.Errorf("되돌림 문장 끝 = %v, want 오류 하나(서버가 세션을 끝냈다)", probe.rollbackErrs)
	}
	checkRollbackContext(t, probe, name)
	if run.nowait != nil {
		t.Errorf("호출 직후 다른 세션의 NOWAIT = %v, want nil(서버가 잠금을 풀었다)", run.nowait)
	}
}
