package publish

// VOD 작성자 직렬화의 PG 통합 시험이다(POK-196 M6-2 c5 · kty ⑧ = (가) · cx r1 H1). 두 writer 가 서로 다른 장부
// 스냅숏으로 GAP 을 등재하면 같은 키에 다른 본문을 내 정상 판을 vod_conflict 로 거부한다. 그래서 등재 트랜잭션이 사슬
// 꼬리 행을 잠그고 장부를 다시 읽은 값으로 plan · 렌더한다(잠금 → 재독 → 등재 → 커밋 → PUT).

import (
	"context"
	"strings"
	"testing"
	"time"
)

// blockOnce 는 sql 을 처음 보낼 때 그 자리에서 멈추는 추적기다. reached 가 닫히면 멈췄고, release 를 닫으면 보낸다.
func blockOnce(sql string) (hook stmtHook, reached, release chan struct{}) {
	reached, release = make(chan struct{}), make(chan struct{})
	first := true
	hook = stmtHook{sql: sql, before: func(ctx context.Context, _ []any) context.Context {
		if first {
			first = false
			close(reached)
			<-release
		}
		return ctx
	}}
	return hook, reached, release
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
	hook, reached, release := blockOnce(vodLockTailSQL)
	a := newPublisher(t, hookedPool(t, p.pool, hook), store, "w-a", &logRecorder{})
	done := make(chan FreezeOutcome, 1)
	go func() { done <- a.FreezeVOD(context.Background(), chain, true) }()
	waitReached(t, reached, "꼬리 잠금 문장")

	landSeg7(t, p)
	putThenLose(store)
	wantOutcome(t, p.FreezeVOD(t.Context(), chain, true), AxisVOD, ResultRetry, true)
	close(release)

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
	hook, reached, release := blockOnce(vodMarkGapSQL)
	a := newPublisher(t, hookedPool(t, p.pool, hook), store, "w-a", &logRecorder{})
	aDone, bDone := make(chan FreezeOutcome, 1), make(chan FreezeOutcome, 1)
	go func() { aDone <- a.FreezeVOD(context.Background(), chain, true) }()
	waitReached(t, reached, "GAP 등재 문장")

	landSeg7(t, p)
	go func() { bDone <- p.FreezeVOD(context.Background(), chain, true) }()
	waitLockWait(t, p.pool)
	close(release)

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
