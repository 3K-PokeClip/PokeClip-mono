package session

// 개시 · 4D 와 종료 문장(index.CloseChain · CloseRound)의 교차 배리어 시험(POK-196 M6-3b 커밋 ① — 설계 M6 3.3 · 15.2 ·
// 9절 D1 · D43 · D64 (나)(다)). 종료 문장은 index 에 있고 개시 · 4D 는 이 패키지에 있어 교차는 여기서 잰다.
//
// 배리어는 행 잠금이다(membership_pg_test.go 머리 주석): 한쪽이 같은 행을 쥔 채 커밋 전에 멈추면 다른 쪽은 그 행에서
// 기다리고, 커밋 뒤에는 새 판으로 다시 본다. 종료 문장을 ③ 에서 멈추게 하려면 다른 연결(T0)이 사슬 · 회차의 앞 행을 쥔다.

import (
	"context"
	"errors"
	"log/slog"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// crossClose 는 종료 문장의 운영 값이다(재접속 창 300초 · 닫힘 여유 60초).
var crossClose = index.CloseOptions{Window: 300 * time.Second, Slack: 60 * time.Second, Log: slog.New(slog.DiscardHandler)}

func noEnqueue(context.Context, pgx.Tx, string) error { return nil }

// endFrozen 은 세션의 DVR 을 닫고 init 을 확정한다 — 사슬 닫힘의 앞 조건.
func endFrozen(t *testing.T, pool *pgxpool.Pool, ids ...string) {
	t.Helper()
	for _, id := range ids {
		if _, err := pool.Exec(context.Background(),
			`UPDATE stream_sessions SET dvr_state = 'frozen', init_uploaded_at = pg_catalog.now() WHERE session_id = $1`, id); err != nil {
			t.Fatal(err)
		}
	}
}

type closeResult struct {
	ok  bool
	err error
}

func closeChainAsync(pool *pgxpool.Pool, tail string) <-chan closeResult {
	out := make(chan closeResult, 1)
	go func() {
		_, ok, err := index.CloseChain(context.Background(), pool, tail, crossClose)
		out <- closeResult{ok, err}
	}()
	return out
}

func vodOf(t *testing.T, pool *pgxpool.Pool, id string) string {
	t.Helper()
	var v string
	if err := pool.QueryRow(context.Background(), `SELECT vod_state FROM stream_sessions WHERE session_id = $1`, id).Scan(&v); err != nil {
		t.Fatal(err)
	}
	return v
}

// D1 — 계승 개시와 사슬 닫힘이 같은 꼬리에서 겹친다. 둘은 같은 행(꼬리 = 소속 행)을 잠그므로 「닫힌 사슬을 이어받음」과
// 「후속이 생긴 사슬을 닫음」이 함께 일어나지 않는다.
//
//	(가) 개시가 먼저 꼬리를 쥠 → 사슬 닫힘은 꼬리에서 기다림 → 개시 커밋 → 닫힘은 후속을 보고 닫지 않음
//	(나) 닫힘이 꼬리를 쥔 채 ③ 에서 멈춤 → 개시는 꼬리에서 기다림 → 닫힘 커밋 → 개시는 닫힌 사슬을 계승하지 않고 합류만
func TestInheritOpenAndChainCloseSerializeOnTailD1(t *testing.T) {
	ctx := context.Background()
	setup := func(t *testing.T) (*pgxpool.Pool, *Registry) {
		pool := newTestPool(t)
		r, _ := newRoundRegistry()
		seedLedger(t, pool, ledgerRow{id: "P", streamID: "demo", state: "ending", endReason: "offline", roundID: "P", startedAt: wall.Add(-2 * time.Hour)})
		seedLedger(t, pool, ledgerRow{id: "T", streamID: "demo", state: "ending", endReason: "offline", roundID: "P", inherits: "P", startedAt: wall.Add(-time.Hour)})
		endFrozen(t, pool, "P", "T")
		seedSegment(t, pool, "demo", "T", 1, wall.Add(-10*time.Second))
		return pool, r
	}
	in := Input{StreamID: "demo", Seq: 2, StartWallUTC: wall, DurationMS: 4000, Op: OpenOrCurrent}

	t.Run("가_개시_먼저", func(t *testing.T) {
		pool, r := setup(t)
		tx, err := pool.Begin(ctx)
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = tx.Rollback(ctx) }()
		d, err := r.Decide(ctx, tx, in, in.StartWallUTC)
		if err != nil || d.Outcome != OutcomeOpen {
			t.Fatalf("결정 = %+v err %v, want 개시", d, err)
		}
		c, err := r.Open(ctx, tx, d, in.StartWallUTC)
		if err != nil {
			t.Fatal(err)
		}
		closed := closeChainAsync(pool, "T")
		waitLockWaiter(t, pool)
		if err := tx.Commit(ctx); err != nil {
			t.Fatal(err)
		}
		if res := <-closed; res.err != nil || res.ok {
			t.Fatalf("CloseChain(T) = ok %v err %v, want 닫지 않음(후속 생김)", res.ok, res.err)
		}
		if got := roundOf(t, pool, c); strOf(got.inherits) != "T" || vodOf(t, pool, "T") != "none" {
			t.Fatalf("C 계승 %s · T vod %s, want T · none", strOf(got.inherits), vodOf(t, pool, "T"))
		}
	})

	t.Run("나_닫힘_먼저", func(t *testing.T) {
		pool, r := setup(t)
		commitP := holdRow(t, pool, "P", "")
		closed := closeChainAsync(pool, "T")
		waitLockWaiter(t, pool)
		opened := decideOpenAsync(pool, r, in)
		waitLockWaiters(t, pool, 2)
		commitP()
		if res := <-closed; res.err != nil || !res.ok {
			t.Fatalf("CloseChain(T) = ok %v err %v, want 닫힘", res.ok, res.err)
		}
		waitResult(t, opened)
		c := newestSession(t, pool, "demo")
		if got := roundOf(t, pool, c); got.inherits != nil || strOf(got.roundID) != "P" {
			t.Fatalf("C (계승, 회차) = (%s, %s), want (NULL, P) — 닫힌 사슬은 계승하지 않고 열린 회차에 합류만 한다", strOf(got.inherits), strOf(got.roundID))
		}
	})
}

// D43 — 개시(회차 합류)와 회차 닫힘이 겹친다. 둘은 같은 소속 행을 잠근다.
//
//	(가) 개시가 먼저 소속 행을 쥠 → 회차 닫힘은 소속 행에서 기다림 → 개시 커밋 → 닫힘은 새 최신(live)을 보고 닫지 않음
//	(나) 닫힘이 소속 행을 쥔 채 ③ 에서 멈춤 → 개시는 소속 행에서 기다림 → 닫힘 커밋 → 개시는 닫힌 회차에 붙지 않고 새 회차
func TestOpenAndRoundCloseSerializeOnMembershipD43(t *testing.T) {
	ctx := context.Background()
	setup := func(t *testing.T) (*pgxpool.Pool, *Registry, index.ClosableRound) {
		pool := newTestPool(t)
		r, _ := newRoundRegistry()
		seedLedger(t, pool, ledgerRow{id: "P", streamID: "demo", state: "ended", endReason: "offline", vodState: "frozen", roundID: "R", startedAt: wall.Add(-2 * time.Hour)})
		seedLedger(t, pool, ledgerRow{id: "A", streamID: "demo", state: "ending", endReason: "offline", roundID: "R", startedAt: wall.Add(-time.Hour)})
		seedSegment(t, pool, "demo", "A", 1, wall.Add(-10*time.Second))
		rounds, err := index.ListClosableRounds(ctx, pool, 50, crossClose)
		if err != nil || len(rounds) != 1 || rounds[0].RoundID != "R" {
			t.Fatalf("회차 스캔 = %+v err %v", rounds, err)
		}
		return pool, r, rounds[0]
	}
	in := Input{StreamID: "demo", Seq: 2, StartWallUTC: wall, DurationMS: 4000, Op: OpenOrCurrent}
	closeAsync := func(pool *pgxpool.Pool, rr index.ClosableRound) <-chan closeResult {
		out := make(chan closeResult, 1)
		go func() {
			ok, err := index.CloseRound(ctx, pool, rr, crossClose, noEnqueue)
			out <- closeResult{ok, err}
		}()
		return out
	}

	t.Run("가_개시_먼저", func(t *testing.T) {
		pool, r, rr := setup(t)
		tx, err := pool.Begin(ctx)
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = tx.Rollback(ctx) }()
		d, err := r.Decide(ctx, tx, in, in.StartWallUTC)
		if err != nil {
			t.Fatal(err)
		}
		c, err := r.Open(ctx, tx, d, in.StartWallUTC)
		if err != nil {
			t.Fatal(err)
		}
		closed := closeAsync(pool, rr)
		waitLockWaiter(t, pool)
		if err := tx.Commit(ctx); err != nil {
			t.Fatal(err)
		}
		if res := <-closed; res.err != nil || res.ok {
			t.Fatalf("CloseRound = ok %v err %v, want 닫지 않음(합류한 live)", res.ok, res.err)
		}
		if got := roundOf(t, pool, c); strOf(got.roundID) != "R" {
			t.Fatalf("C 회차 = %s, want R(합류)", strOf(got.roundID))
		}
	})

	t.Run("나_닫힘_먼저", func(t *testing.T) {
		pool, r, rr := setup(t)
		commitP := holdRow(t, pool, "P", "")
		closed := closeAsync(pool, rr)
		waitLockWaiter(t, pool)
		opened := decideOpenAsync(pool, r, in)
		waitLockWaiters(t, pool, 2)
		commitP()
		if res := <-closed; res.err != nil || !res.ok {
			t.Fatalf("CloseRound = ok %v err %v, want 닫힘", res.ok, res.err)
		}
		waitResult(t, opened)
		c := newestSession(t, pool, "demo")
		if got := roundOf(t, pool, c); strOf(got.roundID) != c || got.inherits != nil {
			t.Fatalf("C (회차, 계승) = (%s, %s), want (자기 회차, NULL) — 닫힌 회차에 붙었다", strOf(got.roundID), strOf(got.inherits))
		}
	})
}

// waitLockWaiters 는 행 잠금을 기다리는 백엔드가 n 개 이상이 될 때까지 기다린다.
func waitLockWaiters(t *testing.T, pool *pgxpool.Pool, n int) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		var got int
		if err := pool.QueryRow(context.Background(), `
SELECT pg_catalog.count(*) FROM pg_catalog.pg_stat_activity
 WHERE datname = pg_catalog.current_database() AND wait_event_type = 'Lock'`).Scan(&got); err != nil {
			t.Fatal(err)
		}
		if got >= n {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("잠금 대기 백엔드가 %d 개가 되지 않았다", n)
}

// d64Setup 은 A(R · 멈춘 연결) ← B(멈춘 뒤 합류 · R · A 계승 · 즉시 닫는 사유) 다. 4D 는 T=A · 합류한 B 를 새 회차로
// 떼려 하고, 사슬 닫힘은 B 꼬리의 사슬 [A B] 를 닫으려 한다.
func d64Setup(t *testing.T) *pgxpool.Pool {
	pool := newTestPool(t)
	seedLedger(t, pool, ledgerRow{id: "S-A", streamID: "demo", state: "ending", endReason: "offline", roundID: "S-R", startedAt: stopAt.Add(-time.Hour)})
	seedSegment(t, pool, "demo", "S-A", 1, stopAt.Add(-5*time.Second))
	seedLedger(t, pool, ledgerRow{id: "S-B", streamID: "demo", state: "ending", endReason: endReasonTDExceeded, roundID: "S-R", inherits: "S-A", startedAt: stopAt.Add(20 * time.Second)})
	seedSegment(t, pool, "demo", "S-B", 2, stopAt.Add(20*time.Second))
	endFrozen(t, pool, "S-A", "S-B")
	return pool
}

// realCloseRound 는 4D 함수 칸 CloseRound 에 실제 index.TryCloseRound 를 끼운 옵션이다(M6-3b 배선과 같은 꼴).
func realCloseRound() IntentOptions {
	o := (&intentSlots{}).options()
	o.CloseRound = func(ctx context.Context, tx pgx.Tx, round string) error {
		_, err := index.TryCloseRound(ctx, tx, round, crossClose, noEnqueue)
		return err
	}
	o.Log = slog.New(slog.DiscardHandler)
	return o
}

// D64 (나) — 4D round_split 이 먼저 커밋하면 B 는 새 회차라 A 를 계승해도 사슬 간선이 아니다. 그 뒤 사슬 닫힘은 B 꼬리의
// 사슬 [B] 만, A 꼬리의 사슬 [A] 만 닫는다 — 닫힌 사슬 안을 가르는 이동이 생기지 않는다.
func TestRoundSplitThenChainCloseKeepsChainsApartD64(t *testing.T) {
	pool := d64Setup(t)
	ctx := context.Background()
	res, err := ApplyIntentionalEnd(ctx, pool, endAt("demo"), realCloseRound())
	if err != nil || res.Effect != effectRoundSplit {
		t.Fatalf("4D = %+v err %v, want round_split", res, err)
	}
	for _, tail := range []string{"S-B", "S-A"} {
		c, ok, err := index.CloseChain(ctx, pool, tail, crossClose)
		if err != nil || !ok || len(c.SessionIDs) != 1 || c.SessionIDs[0] != tail {
			t.Fatalf("CloseChain(%s) = %+v ok %v err %v, want 사슬 [%s]", tail, c, ok, err, tail)
		}
	}
}

// D64 (다) — 4D 와 사슬 닫힘이 동시에 든다. 끝 상태는 언제나 둘 가운데 하나다(위상 잠금 · 같은 행 잠금 아래 직렬):
//
//	닫힘이 먼저  A · B freezing · B 회차 R 그대로 · 4D 는 round_split_blocked
//	4D 가 먼저   B 회차 = B · 사슬 닫힘은 B 사슬 [B] 만 · A vod none
func TestRoundSplitAndChainCloseConcurrentEndInOneOfTwoStatesD64(t *testing.T) {
	ctx := context.Background()
	for i := range 10 {
		pool := d64Setup(t)
		closed := closeChainAsync(pool, "S-B")
		res, err := ApplyIntentionalEnd(ctx, pool, endAt("demo"), realCloseRound())
		if err != nil {
			t.Fatalf("반복 %d: 4D 오류 %v", i, err)
		}
		cr := <-closed
		if cr.err != nil && !errors.Is(cr.err, index.ErrTopologyContended) {
			t.Fatalf("반복 %d: CloseChain 오류 %v", i, cr.err)
		}
		a, b, bRound := vodOf(t, pool, "S-A"), vodOf(t, pool, "S-B"), strOf(roundOf(t, pool, "S-B").roundID)
		closeFirst := res.Skip == skipRoundSplitBlocked && a == "freezing" && b == "freezing" && bRound == "S-R"
		splitFirst := res.Effect == effectRoundSplit && bRound == "S-B" && a == "none" && (b == "freezing" || !cr.ok)
		if !closeFirst && !splitFirst {
			t.Fatalf("반복 %d: 끝 상태가 두 직렬 결과 밖이다 — 4D %+v · 닫힘 ok %v · A %s · B %s · B 회차 %s", i, res, cr.ok, a, b, bRound)
		}
	}
}
