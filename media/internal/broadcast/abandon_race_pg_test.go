package broadcast

// started 버림 ↔ ended 생성 교차 실 PG 시험(POK-196 M6-8 B4 · 설계 M6 15.5 X5 · D63). 두 트랜잭션이 어느 순서로
// 겹쳐도 「started 를 버리면 ended 도 내지 않는다」가 커밋 뒤에 성립해야 한다 — 하나라도 어긋나면 ended 가 pending
// 에 고착해 selectDue 가 영영 고르지 않고 lifecycle_backlog 가 꺼지지 않는다.

import (
	"context"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// waitLockedOrDone 은 이 시험 DB 에서 어떤 접속이 행 잠금을 기다리거나 done 이 닫힐 때까지 기다린다. 앞 트랜잭션을
// 커밋하기 전에 뒤 문장이 「막혔는지 · 이미 끝났는지」를 확정해 두려는 것이다 — 그래야 고루틴 순서와 상관없이 같은
// 교차를 잰다.
func waitLockedOrDone(t *testing.T, pool *pgxpool.Pool, done <-chan struct{}) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		select {
		case <-done:
			return
		default:
		}
		var waiting int
		if err := pool.QueryRow(context.Background(), `
			SELECT pg_catalog.count(*) FROM pg_catalog.pg_stat_activity
			 WHERE datname = pg_catalog.current_database() AND wait_event_type = 'Lock'`).Scan(&waiting); err != nil {
			t.Fatalf("잠금 대기 세기: %v", err)
		}
		if waiting > 0 {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatal("뒤 문장이 5초 안에 끝나지도 잠금을 기다리지도 않았다")
}

// 완결 평가가 ended 를 넣고 커밋하기 전에 발행기가 started 를 버리면, 커밋 뒤 ended 도 started_abandoned 여야 한다.
// 잡는 결함: 버림이 문장 하나면 그 스냅숏에 커밋 전 ended 가 없어 started 만 바뀐다(db M1 재현). started 를 잠그지
// 않아도 같다 — 버림이 기다리지 않고 지나간다.
func TestAbandonWaitsForEndedEnqueue(t *testing.T) {
	pool := newTestPool(t)
	completeRound(t, pool)
	ctx := context.Background()
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("Begin: %v", err)
	}
	defer tx.Rollback(ctx)
	if ok, err := EnqueueEndedIfComplete(ctx, tx, openGate(), "S-A"); err != nil || !ok {
		t.Fatalf("EnqueueEndedIfComplete = %v · %v, want true", ok, err)
	}

	done := make(chan struct{})
	var abandonErr error
	go func() {
		defer close(done)
		_, abandonErr = abandon(ctx, pool, outboxRow{eventID: "S-A.started", round: "S-A", sequence: 1},
			reasonInvalid)
	}()
	waitLockedOrDone(t, pool, done)
	if err := tx.Commit(ctx); err != nil {
		t.Fatalf("Commit: %v", err)
	}
	<-done

	if abandonErr != nil {
		t.Fatalf("abandon: %v", abandonErr)
	}
	for id, want := range map[string][2]string{
		"S-A.started": {"abandoned", reasonInvalid},
		"S-A.ended":   {"abandoned", "started_abandoned"},
	} {
		r, ok := readOutbox(t, pool, id)
		if !ok || r.state != want[0] || r.lastError != want[1] {
			t.Errorf("%s 있음 · 상태 · 사유 = %v · %s · %q, want true · %s · %q",
				id, ok, r.state, r.lastError, want[0], want[1])
		}
	}
}

// 발행기가 started 를 버리고 커밋하기 전에 완결 평가가 돌면, 커밋 뒤 ended 행은 없어야 한다(평가가 버림을 기다렸다가
// abandoned 를 보고 넣지 않는다).
// 잡는 결함: started 를 읽기만 하면 평가가 커밋 전 스냅숏의 pending 을 보고 ended 를 넣어, 이미 지나간 버림이
// 그 행을 거두지 못한다.
func TestEndedEnqueueWaitsForStartedAbandon(t *testing.T) {
	pool := newTestPool(t)
	completeRound(t, pool)
	ctx := context.Background()
	abandonTx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("Begin: %v", err)
	}
	defer abandonTx.Rollback(ctx)
	if _, err := abandonTx.Exec(ctx, `
		UPDATE broadcast_lifecycle_outbox SET state = 'abandoned', last_error = $2
		 WHERE event_id = $1 AND state = 'pending'`, "S-A.started", reasonInvalid); err != nil {
		t.Fatalf("started 버림: %v", err)
	}

	done := make(chan struct{})
	var (
		enqueued   bool
		enqueueErr error
	)
	go func() {
		defer close(done)
		tx, err := pool.Begin(ctx)
		if err != nil {
			enqueueErr = err
			return
		}
		defer tx.Rollback(ctx)
		if enqueued, enqueueErr = EnqueueEndedIfComplete(ctx, tx, openGate(), "S-A"); enqueueErr == nil {
			enqueueErr = tx.Commit(ctx)
		}
	}()
	waitLockedOrDone(t, pool, done)
	if err := abandonTx.Commit(ctx); err != nil {
		t.Fatalf("Commit: %v", err)
	}
	<-done

	if enqueueErr != nil {
		t.Fatalf("EnqueueEndedIfComplete: %v", enqueueErr)
	}
	if enqueued {
		t.Error("EnqueueEndedIfComplete = true, want false(started 가 버려졌다)")
	}
	if r, ok := readOutbox(t, pool, "S-A.ended"); ok {
		t.Errorf("S-A.ended = %s · %q, want 행 없음", r.state, r.lastError)
	}
}
