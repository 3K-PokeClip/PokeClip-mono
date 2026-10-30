package index

// 회차 닫힘(POK-196 M6-3b 커밋 ① — 설계 M6 15.2 회차 닫힘 · 3.3 소속 잠금 · 9절 D59 · D71). PG_DSN 미설정이면 전량
// skip 된다.

import (
	"context"
	"slices"
	"testing"
	"time"
)

// 회차 스캔 — 닫힘 조건이 지금 참인 열린 회차만 온다. 회차의 최신 세션(회차 안 선택 규칙 — live 가 있으면 그 live,
// 없으면 started_at 최대)이 끝났고 (intentional ∨ 표식 ∨ 회차 LastEnd 360초 초과)다. 최신이 ended(사슬이 먼저 끝남 —
// D59)여도 닫힌다.
func TestListClosableRoundsSelectsDueRounds(t *testing.T) {
	pool := newTestPool(t)
	now := time.Now()
	old := now.Add(-time.Hour)
	const s = "lcr"
	rows := []endRow{
		{id: "int", stream: s + "1", round: "R-int", endReason: "intentional", startedAt: now.Add(-2 * time.Minute)},
		{id: "mark", stream: s + "2", round: "R-mark", endReason: "offline", stopMarkedAt: now.Add(-time.Minute), startedAt: now.Add(-2 * time.Minute)},
		{id: "win", stream: s + "3", round: "R-win", endReason: "offline", startedAt: old},
		{id: "recent", stream: s + "4", round: "R-recent", endReason: "offline", startedAt: now.Add(-3 * time.Minute)},
		{id: "a", stream: s + "5", round: "R-live", endReason: "intentional", startedAt: old},
		{id: "b", stream: s + "5", round: "R-live", state: "live", startedAt: now.Add(-time.Minute)},
		{id: "closed", stream: s + "6", round: "R-closed", endReason: "intentional", roundClosedAt: now, startedAt: old},
		{id: "d59", stream: s + "7", round: "R-d59", state: "ended", dvr: "frozen", vod: "frozen", endReason: "init_mismatch", startedAt: old},
		{id: "legacy", stream: s + "8", endReason: "intentional", startedAt: old},
	}
	for _, r := range rows {
		putEnd(t, pool, r)
	}
	putSeg(t, pool, s+"4", "recent", 1, now.Add(-100*time.Second), true)

	got, err := ListClosableRounds(context.Background(), pool, 50, testClose)
	if err != nil {
		t.Fatal(err)
	}
	var ids []string
	for _, r := range got {
		ids = append(ids, r.RoundID)
	}
	want := []string{"R-d59", "R-int", "R-mark", "R-win"}
	if !slices.Equal(ids, want) {
		t.Fatalf("닫힘 대상 회차 = %q, want %q", ids, want)
	}
	for _, r := range got {
		if r.RoundID == "R-int" && (r.LatestSessionID != "int" || r.LatestState != "ending" || r.LatestEndReason != "intentional" || r.StreamID != s+"1") {
			t.Errorf("R-int 행 = %+v", r)
		}
	}
}

// 회차 닫힘 — 회차 전원 행에 같은 round_closed_at 을 쓰고, 같은 트랜잭션에서 완결 평가를 부른다.
func TestCloseRoundClosesAllRowsAndEvaluates(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	old := time.Now().Add(-time.Hour)
	const s = "cr-all"
	putEnd(t, pool, endRow{id: "A", stream: s, round: "R", endReason: "offline", dvr: "frozen", vod: "frozen", state: "ended", startedAt: old})
	putEnd(t, pool, endRow{id: "B", stream: s, round: "R", endReason: "intentional", startedAt: old.Add(time.Minute)})

	rounds, err := ListClosableRounds(ctx, pool, 50, testClose)
	if err != nil || len(rounds) != 1 {
		t.Fatalf("회차 스캔 = %+v err %v", rounds, err)
	}
	var rec recordEnqueue
	closed, err := CloseRound(ctx, pool, rounds[0], testClose, rec.fn)
	if err != nil || !closed {
		t.Fatalf("CloseRound = %v err %v, want 닫힘", closed, err)
	}
	var distinct, nulls int
	if err := pool.QueryRow(ctx, `SELECT pg_catalog.count(DISTINCT round_closed_at), pg_catalog.count(*) FILTER (WHERE round_closed_at IS NULL)
  FROM stream_sessions WHERE round_id = 'R'`).Scan(&distinct, &nulls); err != nil {
		t.Fatal(err)
	}
	if distinct != 1 || nulls != 0 {
		t.Fatalf("회차 전원 round_closed_at: 값 %d 가지 · NULL %d 행, want 1 · 0", distinct, nulls)
	}
	if got := rec.got(); !slices.Equal(got, []string{"R"}) {
		t.Fatalf("완결 평가 = %q, want [R]", got)
	}
}

// 잠금 뒤 재확인 — 스캔과 닫힘 사이에 그 회차에 새 송출이 합류하면(최신이 live) 닫지 않는다. 완결 평가는 그대로 부른다
// (닫히지 않은 회차라 아무것도 넣지 않는다).
func TestCloseRoundRechecksAfterLock(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	old := time.Now().Add(-time.Hour)
	const s = "cr-recheck"
	putEnd(t, pool, endRow{id: "A", stream: s, round: "R", endReason: "intentional", startedAt: old})
	rounds, err := ListClosableRounds(ctx, pool, 50, testClose)
	if err != nil || len(rounds) != 1 {
		t.Fatalf("회차 스캔 = %+v err %v", rounds, err)
	}
	putEnd(t, pool, endRow{id: "B", stream: s, round: "R", state: "live", startedAt: time.Now()})

	var rec recordEnqueue
	if closed, err := CloseRound(ctx, pool, rounds[0], testClose, rec.fn); err != nil || closed {
		t.Fatalf("CloseRound = %v err %v, want 닫지 않음(합류한 live)", closed, err)
	}
	if colsOf(t, pool, "A").roundClosed || colsOf(t, pool, "B").roundClosed {
		t.Fatal("live 가 든 회차가 닫혔다")
	}
}

// TryCloseRound — 이미 닫힌 회차는 건너뛰고 완결 평가만 한다(4D round_split 이 R 을 직접 닫은 뒤 부르는 꼴).
func TestTryCloseRoundOnClosedRoundOnlyEvaluates(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	old := time.Now().Add(-time.Hour)
	putEnd(t, pool, endRow{id: "A", stream: "tcr", round: "R", endReason: "offline", roundClosedAt: old, startedAt: old})

	var rec recordEnqueue
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(ctx)
	closed, err := TryCloseRound(ctx, tx, "R", testClose, rec.fn)
	if err != nil || closed {
		t.Fatalf("TryCloseRound = %v err %v, want 닫지 않음", closed, err)
	}
	if got := rec.got(); !slices.Equal(got, []string{"R"}) {
		t.Fatalf("완결 평가 = %q, want [R]", got)
	}
}

// D71 — 회차 닫힘이 소속 행(다른 회차의 live)을 소속 잠금으로 쥔 채 ③ 에서 기다리는 동안, 그 live 세션의 「계속」 조각
// INSERT 는 기다리지 않는다 — 소속 잠금이 FOR NO KEY UPDATE 라 FK 검사의 FOR KEY SHARE 와 충돌하지 않는다.
func TestCloseRoundMembershipLockDoesNotBlockSegmentInsertD71(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	now := time.Now()
	const s = "cr-d71"
	putEnd(t, pool, endRow{id: "A", stream: s, round: "R", endReason: "intentional", startedAt: now.Add(-time.Hour)})
	putEnd(t, pool, endRow{id: "L", stream: s, round: "RL", state: "live", startedAt: now.Add(-time.Minute)})
	rounds, err := ListClosableRounds(ctx, pool, 50, testClose)
	if err != nil || len(rounds) != 1 {
		t.Fatalf("회차 스캔 = %+v err %v", rounds, err)
	}

	release := holdSession(t, pool, "A")
	done := make(chan error, 1)
	go func() {
		var rec recordEnqueue
		_, err := CloseRound(ctx, pool, rounds[0], testClose, rec.fn)
		done <- err
	}()
	waitLockWaiters(t, pool, 1)
	start := time.Now()
	putSeg(t, pool, s, "L", 1, now, false)
	if took := time.Since(start); took > time.Second {
		t.Fatalf("live 조각 INSERT 가 %v 기다렸다 — 소속 잠금이 FK 잠금과 충돌한다", took)
	}
	release()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if !colsOf(t, pool, "A").roundClosed {
		t.Fatal("R 이 닫히지 않았다")
	}
}
