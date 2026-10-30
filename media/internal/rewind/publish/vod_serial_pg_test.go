package publish

// VOD 작성자 직렬화의 PG 통합 시험이다(POK-196 M6-2 c5 · kty ⑧ = (가) · cx r1 H1). 두 writer 가 서로 다른 장부
// 스냅숏으로 GAP 을 등재하면 같은 키에 다른 본문을 내 정상 판을 vod_conflict 로 거부한다. 그래서 등재 트랜잭션이 사슬
// 꼬리 행을 잠그고 장부를 다시 읽은 값으로 plan · 렌더한다(잠금 → 재독 → 등재 → 커밋 → PUT).

import (
	"context"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// blockLimit 는 멈춤 자리의 상한이다. 시험 본문이 어디서 서서 release 를 부르지 못해도 멈춘 writer 가 연결을 쥔 채
// 남지 않게 한다 — 풀 정리는 반환되지 않은 연결을 기다린다(hookedPool). 발행자의 문장 시한(index.TxnDeadline
// 10초)보다 짧게 둔다 — 멈춤이 그 시한으로 조용히 풀리기 전에 시험이 이 자리를 실패로 적는다.
const blockLimit = 5 * time.Second

// blockOnce 는 sql 을 처음 보낼 때 그 자리에서 멈추는 추적기다. reached 가 닫히면 멈췄고, release 를 부르면 보낸다
// (여러 번 불러도 된다). 문장의 ctx 가 끝나거나 blockLimit 이 지나도 멈춤이 풀린다 — 그때 그 문장은 끝난 ctx 로
// 서버에 닿지 않고, 상한이면 시험이 실패한다.
func blockOnce(t *testing.T, sql string) (hook stmtHook, reached chan struct{}, release func()) {
	reached, unblock := make(chan struct{}), make(chan struct{})
	var once sync.Once
	release = func() { once.Do(func() { close(unblock) }) }
	first := true
	hook = stmtHook{sql: sql, before: func(ctx context.Context, _ []any) context.Context {
		if !first {
			return ctx
		}
		first = false
		close(reached)
		select {
		case <-unblock:
		case <-ctx.Done():
		case <-time.After(blockLimit):
			t.Errorf("멈춤 자리가 %v 안에 풀리지 않았다 — 그 문장을 끝난 ctx 로 보낸다", blockLimit)
			canceled, cancel := context.WithCancel(ctx)
			cancel()
			return canceled
		}
		return ctx
	}}
	return hook, reached, release
}

// blockedWriter 는 sql 을 처음 보낼 때 멈추는 writer 「w-a」 다(blockOnce). 멈춤 풀기를 추적 풀보다 늦게 등록한다 —
// 정리는 등록의 역순이라, 본문이 중간 단언으로 끝나도 풀 정리가 멈춘 연결을 기다리기 전에 멈춤이 풀린다.
func blockedWriter(t *testing.T, p *Publisher, store *fakeStore, sql string) (a *Publisher, reached chan struct{}, release func()) {
	t.Helper()
	hook, reached, release := blockOnce(t, sql)
	a = newPublisher(t, hookedPool(t, p.pool, hook), store, "w-a", &logRecorder{})
	t.Cleanup(release)
	return a, reached, release
}

// waitReached 는 멈춤 자리에 닿기를 5초까지 기다린다.
func waitReached(t *testing.T, reached chan struct{}, what string) {
	t.Helper()
	select {
	case <-reached:
	case <-time.After(5 * time.Second):
		t.Fatalf("A 가 5초 안에 %s 에 닿지 않았다", what)
	}
}

// landSeg7 은 seq 7 의 ③ 이 닿았다고 장부에 적는다.
func landSeg7(t *testing.T, p *Publisher) {
	t.Helper()
	exec(t, p.pool, `UPDATE stream_segments SET playback_upload_state = 'uploaded' WHERE stream_id = $1 AND seq = 7`, fxStream)
}

// putThenLose 는 다음 PUT 한 번을 적용하고 결과를 잃게 한다(쓰고 확정 전에 죽은 writer).
func putThenLose(store *fakeStore) {
	store.setOnPut(func(c putCall) error {
		store.setOnPut(nil)
		if _, err := store.applyPut(c); err != nil {
			return err
		}
		return context.DeadlineExceeded
	})
}

// vod_serial_stale_snapshot_rereads(cx H1) — A 가 seq 7 미착지 스냅숏을 들고 멈춘 사이 ③ 이 닿고, B 가 GAP 없는 본문을
// IfAbsent 로 쓰고 확정 전에 죽는다. A 는 꼬리를 잠근 뒤 장부를 다시 읽어 B 와 같은 본문을 내 그 판을 채택한다 —
// 옛 스냅숏으로 vod_abandoned 를 적지 않는다.
func TestFreezeVODRereadsLedgerUnderTailLock(t *testing.T) {
	rows := chainRows()
	rows[7].state = "pending"
	p, store, chain := newVODChain(t, rows)
	a, reached, release := blockedWriter(t, p, store, vodLockTailSQL)
	done := make(chan FreezeOutcome, 1)
	go func() { done <- a.FreezeVOD(t.Context(), chain, true) }()
	waitReached(t, reached, "꼬리 잠금 문장")

	landSeg7(t, p)
	putThenLose(store)
	wantOutcome(t, p.FreezeVOD(t.Context(), chain, true), AxisVOD, ResultRetry, true)
	release()

	wantOutcome(t, <-done, AxisVOD, ResultFrozen, false)
	wantGapLedger(t, p, map[int64]string{})
	if puts := store.putCalls(); len(puts) != 2 {
		t.Errorf("PUT %d번, want 2(B 의 적용 · A 의 412)", len(puts))
	}
	body, _ := stored(store, vodChainKey)
	if strings.Contains(string(body), "#EXT-X-GAP") {
		t.Errorf("저장된 판에 GAP 줄이 있다:\n%s", body)
	}
	wantVODFrozen(t, p, store, ResultFrozen, 0, 9)
}

// vod_serial_tail_lock_orders_writers(cx H1) — A 가 꼬리를 잠그고 장부를 다시 읽은 뒤(seq 7 미착지) 등재 직전에 멈춘
// 사이 ③ 이 닿는다. B 는 A 가 커밋할 때까지 꼬리 잠금을 기다리고, 그 뒤 다시 읽은 장부(A 가 적은 GAP 원장 — 원장
// 우선)로 A 와 같은 본문을 낸다. 두 writer 가 다른 본문을 내 한쪽이 vod_conflict 로 끝나는 일이 없다.
func TestFreezeVODTailLockOrdersWriters(t *testing.T) {
	rows := chainRows()
	rows[7].state = "pending"
	p, store, chain := newVODChain(t, rows)
	a, reached, release := blockedWriter(t, p, store, vodMarkGapSQL)
	aDone, bDone := make(chan FreezeOutcome, 1), make(chan FreezeOutcome, 1)
	go func() { aDone <- a.FreezeVOD(t.Context(), chain, true) }()
	waitReached(t, reached, "GAP 등재 문장")

	landSeg7(t, p)
	go func() { bDone <- p.FreezeVOD(t.Context(), chain, true) }()
	waitLockWait(t, p.pool)
	release()

	wantOutcome(t, <-aDone, AxisVOD, ResultFrozenWithGaps, false)
	wantOutcome(t, <-bDone, AxisVOD, ResultFrozenWithGaps, false)
	wantGapLedger(t, p, map[int64]string{7: "S/settle_deadline/vod_abandoned"})
	wantVODFrozen(t, p, store, ResultFrozenWithGaps, 0, 9)
}

// 꼬리 행을 다른 트랜잭션이 쥐고 있으면 lock_timeout(5초) 뒤 재시도다(55P03 — 사유 contended). 장부도 문서도 그대로다.
func TestFreezeVODTailLockTimeoutRetries(t *testing.T) {
	p, store, chain := newVODChain(t, chainRows())
	release := lockRow(t, p.pool, "S")
	defer release()

	out := p.FreezeVOD(t.Context(), chain, false)

	wantOutcome(t, out, AxisVOD, ResultRetry, true)
	if out.Reason != "contended" || len(store.putCalls()) != 0 {
		t.Errorf("사유 %q · PUT %d번, want contended · 0", out.Reason, len(store.putCalls()))
	}
}

// vod_confirm_locks_in_round_order(db L2) — VOD 확정은 사슬 행을 회차 잠금(session lockRoundRows)과 같은 순서(started_at,
// session_id)로 먼저 잠근다. 확정 UPDATE 가 제 방문 순서(키 · 물리 순 — 여기서 P 다음 S)로 잠그면, 그 순서로 S 를 쥔
// 회차 잠금 쪽과 서로 기다려 교착(40P01)이 된다. 픽스처는 S 의 시작을 P 앞으로 옮겨 두 순서를 어긋나게 한다.
func TestFreezeVODConfirmLocksChainInRoundOrder(t *testing.T) {
	p, _, chain := newVODChain(t, chainRows())
	exec(t, p.pool, `UPDATE stream_sessions SET started_at = now() - interval '1 hour' WHERE session_id = 'S'`)
	ctx := context.Background()
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		t.Fatalf("회차 잠금 트랜잭션 시작 실패: %v", err)
	}
	defer tx.Rollback(ctx)
	const lockSQL = `SELECT 1 FROM stream_sessions WHERE session_id = $1 FOR NO KEY UPDATE`
	if _, err := tx.Exec(ctx, `SET LOCAL lock_timeout = '3s'`); err != nil {
		t.Fatal(err)
	}
	if _, err := tx.Exec(ctx, lockSQL, "S"); err != nil {
		t.Fatalf("회차 순서 첫 행 S 잠금 실패: %v", err)
	}
	v := &vodJob{p: p, chain: chain, key: vodChainKey}
	done := make(chan FreezeOutcome, 1)
	go func() { done <- v.confirm(ctx, ResultEmpty, nil, "", nil) }()
	waitLockWait(t, p.pool)

	_, err = tx.Exec(ctx, lockSQL, "P")
	if err != nil {
		t.Errorf("회차 순서 둘째 행 P 잠금 = %v, want 확정이 아무 행도 쥐지 않고 기다림(교착 없음)", err)
	}
	if err := tx.Rollback(ctx); err != nil {
		t.Errorf("회차 잠금 트랜잭션 되돌리기 실패: %v", err)
	}

	out := <-done
	wantOutcome(t, out, AxisVOD, ResultEmpty, false)
	if out.Err != nil {
		t.Errorf("확정 오류 = %v, want 없음", out.Err)
	}
}

// vod_tail_lock_lets_segments_land(cc c5 확인 L1 · D71) — 꼬리 잠금은 직렬화만 하면 되므로 FOR NO KEY UPDATE 다. 등재
// 트랜잭션이 꼬리를 쥔 동안 그 회차의 늦은 조각 INSERT(FK 검사 = FOR KEY SHARE)는 기다리지 않는다. 변이 = 꼬리 잠금을
// FOR UPDATE 로 → INSERT 가 막혀 시한 FAIL.
func TestFreezeVODTailLockDoesNotBlockSegmentInsert(t *testing.T) {
	rows := chainRows()
	rows[7].state = "pending"
	p, store, chain := newVODChain(t, rows)
	a, reached, release := blockedWriter(t, p, store, vodMarkGapSQL)
	done := make(chan FreezeOutcome, 1)
	go func() { done <- a.FreezeVOD(context.Background(), chain, true) }()
	waitReached(t, reached, "GAP 등재 문장")

	ictx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	started := time.Now()
	_, err := p.pool.Exec(ictx, `
		INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, session_id)
		VALUES ($1, 10, 0, now(), 4000, 'archive/str/000010.mp4', 'S')`, fxStream)
	release()

	if err != nil {
		t.Errorf("꼬리 잠금 중 조각 INSERT 실패(%v 뒤): %v — FK 잠금이 꼬리 잠금에 막혔다", time.Since(started), err)
	}
	wantOutcome(t, <-done, AxisVOD, ResultFrozenWithGaps, false)
}

// vod_reread_same_connection(cx c5 확인 설계 M · cc c5 확인 M1) — 꼬리를 쥔 등재 트랜잭션은 장부를 같은 연결로 다시
// 읽는다. 풀에서 연결을 하나 더 받으면 풀이 찼을 때 잠금을 쥔 채 연결을 기다려 서로 막힌다. 연결 하나짜리 풀에서도
// 동결이 끝난다. 변이 = 재독을 풀의 별도 연결로 → 연결 대기가 시한에 걸려 재시도로 FAIL.
func TestFreezeVODRereadsOnSingleConnectionPool(t *testing.T) {
	p, store, chain := newVODChain(t, chainRows())
	cfg := p.pool.Config()
	cfg.MaxConns = 1
	one, err := pgxpool.NewWithConfig(context.Background(), cfg)
	if err != nil {
		t.Fatalf("연결 하나짜리 풀 생성 실패: %v", err)
	}
	t.Cleanup(one.Close)
	a := newPublisher(t, one, store, "w-a", &logRecorder{})
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	out := a.FreezeVOD(ctx, chain, false)

	wantOutcome(t, out, AxisVOD, ResultFrozen, false)
	if out.Err != nil {
		t.Errorf("동결 오류 = %v, want 없음", out.Err)
	}
	wantVODFrozen(t, p, store, ResultFrozen, 0, 9)
}
