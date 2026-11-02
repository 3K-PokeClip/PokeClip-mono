package index

// EndLive 의 회차 몫(POK-196 M6-3b 커밋 ③ — 설계 M6 3.4 「오프라인 전이와의 결속」 · 3.3 위상 잠금 · 9절 D74).
//
// 표식(stop_marked_at)이 있는 live 를 오프라인 판정이 끝내면 사유는 intentional 이고, 같은 트랜잭션에서 회차가 닫히고
// 완결이 평가된다(송출이 돌아오지 않은 의도적 종료). EndLive 트랜잭션은 상한 설정 바로 다음 문장에서 위상 잠금을 잡는다 —
// 그래서 위상 잠금을 쥔 다른 종료 문장 앞에서 lock_timeout(5초)에 끊긴다(D74).

import (
	"context"
	"slices"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
)

// noopEnqueue 는 완결 평가 칸의 무동작 가짜다.
func noopEnqueue(context.Context, pgx.Tx, string) error { return nil }

// 표식 갈래 — 사유 intentional · 회차 닫힘 · 완결 평가. 표식 없는 live 는 사유 offline 이고 회차는 열려 있다(360초 시한은
// 회차 스캔이 거둔다).
func TestEndLiveMarkedEndsIntentionalAndClosesRound(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	now := time.Now()
	putEnd(t, pool, endRow{id: "M", stream: "el-mark", state: "live", round: "RM", stopMarkedAt: now.Add(-time.Second), startedAt: now.Add(-time.Hour)})
	putEnd(t, pool, endRow{id: "O", stream: "el-off", state: "live", round: "RO", startedAt: now.Add(-time.Hour)})

	var rec recordEnqueue
	if id, err := EndLive(ctx, pool, "el-mark", testClose, rec.fn); err != nil || id != "M" {
		t.Fatalf("EndLive(표식) = %q %v", id, err)
	}
	if id, err := EndLive(ctx, pool, "el-off", testClose, rec.fn); err != nil || id != "O" {
		t.Fatalf("EndLive(표식 없음) = %q %v", id, err)
	}
	var reasonM, reasonO string
	if err := pool.QueryRow(ctx, `SELECT (SELECT end_reason FROM stream_sessions WHERE session_id='M'),
	                                      (SELECT end_reason FROM stream_sessions WHERE session_id='O')`).Scan(&reasonM, &reasonO); err != nil {
		t.Fatal(err)
	}
	if reasonM != "intentional" || reasonO != "offline" {
		t.Fatalf("사유 = (M %s, O %s), want (intentional, offline)", reasonM, reasonO)
	}
	if !colsOf(t, pool, "M").roundClosed || colsOf(t, pool, "O").roundClosed {
		t.Fatalf("회차 닫힘 = (M %v, O %v), want (닫힘, 열림)", colsOf(t, pool, "M").roundClosed, colsOf(t, pool, "O").roundClosed)
	}
	if got := rec.got(); !slices.Equal(got, []string{"RM"}) {
		t.Fatalf("완결 평가 = %q, want [RM](표식 갈래만)", got)
	}
}

// D74 — 위상 잠금을 쥔 쪽(사슬 · 회차 닫힘 · 4D)이 오래 놓지 않으면, 같은 스트림의 EndLive 는 위상 잠금을 lock_timeout(5초)
// 안에 못 잡고 실패로 돌아온다 — 루프(EndLive 를 동기로 부름)가 서는 시간이 한 번에 5초를 넘지 않는다. 잠금이 풀린 뒤 다음
// 시도는 선다(루프의 30초 쉼 offlinePausedUntil 은 기존 동작 — indexer 판정 시험 몫). 변이 = 위상 잠금을 상한 설정 앞에 둠
// → lock_timeout 이 안 걸려 TxnDeadline(10초)까지 기다려 「6초 안」 FAIL.
//
// 쥔 쪽은 위상 잠금만 쥐고 멈춘 트랜잭션이다. 종료 문장 자신이 ③ 에서 멈추면 그 대기도 같은 5초 상한에 먼저 끊겨 잠금을
// 놓으므로, 뒤에 온 EndLive 는 상한 직전에 잠금을 얻어 선다 — 그 판의 루프 점유도 5초 안이지만 「실패로 돌아옴」을 재지
// 못한다.
func TestEndLiveTopologyLockBoundedByLockTimeoutD74(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	putEnd(t, pool, endRow{id: "L", stream: "el-d74", state: "live", round: "RL", startedAt: time.Now().Add(-time.Hour)})
	holder, err := pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = holder.Rollback(ctx) }()
	if _, err := holder.Exec(ctx, topologyLockSQL, "el-d74"); err != nil {
		t.Fatalf("경쟁 위상 잠금 실패: %v", err)
	}

	start := time.Now()
	id, err := EndLive(ctx, pool, "el-d74", testClose, noopEnqueue)
	took := time.Since(start)
	if err == nil || id != "" || !isLockTimeout(err) {
		t.Fatalf("EndLive = (%q, %v), want lock_timeout 실패", id, err)
	}
	if took > 6*time.Second {
		t.Fatalf("EndLive 가 %v 기다렸다 — 위상 잠금이 lock_timeout(5초) 아래에 있지 않다", took)
	}
	if got := colsOf(t, pool, "L").state; got != "live" {
		t.Fatalf("L state = %s, want live(되돌림)", got)
	}
	if err := holder.Rollback(ctx); err != nil {
		t.Fatal(err)
	}
	if id, err := EndLive(ctx, pool, "el-d74", testClose, noopEnqueue); err != nil || id != "L" {
		t.Fatalf("잠금이 풀린 뒤 EndLive = (%q, %v), want (L, nil) — 다음 시도는 선다", id, err)
	}
}
