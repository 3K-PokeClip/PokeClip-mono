package broadcast

// 계약9 운영 판정 질의 실 PG 시험(sgd 설계 6절 · 8절 J12′). media/README.md 「계약9 운영 판정」 절의 D1 · D2 · D4
// 블록을 표지 사이에서 그대로 뽑아 돌린다. README 의 SQL 이 운영 판정의 정본이라, 그 술어가 보정(missingEndedSQL) ·
// 보정 창(repairHorizon)과 갈라지면 이 시험이 깨진다. 시각은 전부 DB now() 기준 상대값이다(repair_pg_test.go 와 같다).

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// readmePath 는 이 패키지 디렉터리에서 본 media/README.md 다.
var readmePath = filepath.Join("..", "..", "README.md")

// opsBlock 은 README src 에서 운영 질의 name(D1 · D2 · D4)의 SQL 을 뽑는다. 표지
// <!-- lifecycle-ops:<name>:begin --> · <!-- lifecycle-ops:<name>:end --> 가 하나씩이고 그 사이가 sql 코드 블록 하나여야
// 한다. 어긋나면 까닭을 오류로 돌려준다.
func opsBlock(src, name string) (string, error) {
	begin, end := "<!-- lifecycle-ops:"+name+":begin -->", "<!-- lifecycle-ops:"+name+":end -->"
	for _, marker := range []string{begin, end} {
		if n := strings.Count(src, marker); n != 1 {
			return "", fmt.Errorf("표지 %q 개수 = %d, want 1", marker, n)
		}
	}
	from, to := strings.Index(src, begin)+len(begin), strings.Index(src, end)
	if to < from {
		return "", errors.New("끝 표지가 시작 표지보다 앞에 있다")
	}
	const fence = "```"
	sql, ok := strings.CutPrefix(strings.TrimSpace(src[from:to]), fence+"sql\n")
	if !ok {
		return "", errors.New("표지 사이가 sql 코드 블록으로 시작하지 않는다")
	}
	if sql, ok = strings.CutSuffix(sql, "\n"+fence); !ok || strings.Contains(sql, fence) {
		return "", errors.New("표지 사이가 sql 코드 블록 하나로 끝나지 않는다")
	}
	return sql, nil
}

// opsQuery 는 README 의 운영 질의 name 이다.
func opsQuery(t *testing.T, name string) string {
	t.Helper()
	src, err := os.ReadFile(readmePath)
	if err != nil {
		t.Fatalf("README 읽기: %v", err)
	}
	sql, err := opsBlock(string(src), name)
	if err != nil {
		t.Fatalf("README 운영 질의 %s: %v", name, err)
	}
	return sql
}

// opsVerdict 는 운영 질의 셋의 값이다. 정상이면 모두 0 이다.
type opsVerdict struct {
	d1, d2, d4Complete, d4Open int64
}

// beginReadOnly 는 운영 질의를 돌릴 읽기 전용 트랜잭션이다 — README 블록에 쓰기가 섞이면 25006 으로 실패한다.
func beginReadOnly(t *testing.T, pool *pgxpool.Pool) pgx.Tx {
	t.Helper()
	tx, err := pool.BeginTx(context.Background(), pgx.TxOptions{AccessMode: pgx.ReadOnly})
	if err != nil {
		t.Fatalf("읽기 전용 트랜잭션 시작: %v", err)
	}
	return tx
}

// runOps 는 README 의 D1 · D2 · D4 를 읽기 전용 트랜잭션 하나에서 그대로 돌린다.
func runOps(t *testing.T, pool *pgxpool.Pool) opsVerdict {
	t.Helper()
	ctx := context.Background()
	tx := beginReadOnly(t, pool)
	defer tx.Rollback(ctx)
	var v opsVerdict
	for _, q := range []struct {
		name string
		dest []any
	}{
		{"D1", []any{&v.d1}},
		{"D2", []any{&v.d2}},
		{"D4", []any{&v.d4Complete, &v.d4Open}},
	} {
		if err := tx.QueryRow(ctx, opsQuery(t, q.name)).Scan(q.dest...); err != nil {
			t.Fatalf("운영 질의 %s 실행: %v", q.name, err)
		}
	}
	return v
}

// J12′(a) — 정상 정리(ended 발행 7일 뒤 started · ended 를 함께 지움)된 회차는 세 질의 어디에도 들지 않는다. 셋 모두
// 「started 행이 살아 있음」 에서 출발하기 때문이다 — 세션은 남아 있다.
// 잡는 결함: D1 을 세션 기준(완결 ∧ ended 행 없음)으로 세면 정리된 회차를 밀린 끝 알림으로 센다(V27a).
func TestOpsQueriesIgnoreSettledRounds(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	seedRepairRound(t, pool, repairRound{id: "R-S", kind: roundComplete, start: dbNow(t, pool).Add(-time.Hour)})
	if !enqueueEnded(t, pool, openGate(), "R-S") {
		t.Fatal("닫는 쪽이 ended 를 넣지 않았다")
	}
	p, _, sig := newRepairPublisher(t, pool)
	p.tick(ctx)
	mustExec(t, pool, `UPDATE broadcast_lifecycle_outbox SET published_at = pg_catalog.now() - interval '8 days'
		WHERE event_id = $1 AND state = 'published'`, EndedEventID("R-S"))
	if n, err := deleteSettled(ctx, pool, settledAge); err != nil || n != 2 {
		t.Fatalf("deleteSettled = %d err %v, want started · ended 2행", n, err)
	}

	got := runOps(t, pool)
	p.repairEvery = 0 // 둘째 tick 도 보정을 돈다 — 주기 안이면 보정을 건너뛰어 아래 repaired 0 이 실패할 수 없다
	p.tick(ctx)

	if got != (opsVerdict{}) {
		t.Errorf("운영 질의 = %+v, want 모두 0 — 정리된 회차를 셌다", got)
	}
	if n := repairedCount(sig); n != 0 {
		t.Errorf("WARN lifecycle_ended_repaired %d 줄, want 0", n)
	}
}

// J12′(b) — 완결된 지 10분이 넘었는데 ended 가 없는 회차를 D1 이 세고, 보정 한 번 뒤 0 이 된다. 완결된 지 10분이 안 된
// 회차는 세지 않는다(문턱).
// 잡는 결함: 보정이 없으면 D1 이 영영 0 으로 돌아오지 않는다(P13 · P31 · J5′).
func TestOpsD1CountsMissingEndedUntilRepaired(t *testing.T) {
	pool := newTestPool(t)
	now := dbNow(t, pool)
	// seedRepairRound 의 완결 시각은 start + 11분이다 — start + 1분에 합류한 세션의 round_closed_at(+10분). R-OLD 의
	// 완결은 now − 10분 30초라 문턱 위 30초이고, 문턱이 11분으로 밀리면 세지 않는다.
	seedRepairRound(t, pool, repairRound{id: "R-OLD", kind: roundComplete, start: now.Add(-21*time.Minute - 30*time.Second)})
	seedRepairRound(t, pool, repairRound{id: "R-NEW", kind: roundComplete, start: now.Add(-15 * time.Minute)})
	p, _, _ := newRepairPublisher(t, pool)

	before := runOps(t, pool)
	p.tick(context.Background())
	after := runOps(t, pool)

	if before != (opsVerdict{d1: 1}) {
		t.Errorf("보정 전 운영 질의 = %+v, want D1 1(R-OLD) · 나머지 0", before)
	}
	if after != (opsVerdict{}) || !hasEnded(t, pool, "R-OLD") {
		t.Errorf("보정 뒤 운영 질의 = %+v · R-OLD ended 있음 %v, want 모두 0 · 있음", after, hasEnded(t, pool, "R-OLD"))
	}
}

// J12′(c) — 만든 지 10분이 넘은 pending 행을 D2 가 세고, 발행 뒤 0 이 된다. 만든 지 10분이 안 된 pending 은 세지 않는다.
// 잡는 결함: D2 가 state 를 보지 않으면 발행된 행도 밀린 발행으로 센다(V27b).
func TestOpsD2CountsStalePendingUntilPublished(t *testing.T) {
	pool := newTestPool(t)
	now := dbNow(t, pool)
	for _, id := range []string{"R-OLD", "R-NEW"} {
		seedRepairRound(t, pool, repairRound{id: id, kind: roundComplete, start: now.Add(-time.Hour)})
		if !enqueueEnded(t, pool, openGate(), id) {
			t.Fatalf("닫는 쪽이 %s 의 ended 를 넣지 않았다", id)
		}
	}
	mustExec(t, pool, `UPDATE broadcast_lifecycle_outbox SET created_at = pg_catalog.now() - interval '11 minutes'
		WHERE event_id = $1`, EndedEventID("R-OLD"))
	p, _, _ := newRepairPublisher(t, pool)

	before := runOps(t, pool)
	p.tick(context.Background())
	after := runOps(t, pool)

	if before != (opsVerdict{d2: 1}) {
		t.Errorf("발행 전 운영 질의 = %+v, want D2 1(R-OLD) · 나머지 0", before)
	}
	if after != (opsVerdict{}) {
		t.Errorf("발행 뒤 운영 질의 = %+v, want 모두 0", after)
	}
}

// J12′(d) — 회차 시작이 보정 창(repairHorizon) 밖이고 ended 가 없는 회차를 D4 가 완결 · 미완결로 나눠 세고, 보정은
// 거두지 않는다. 창 안 1시간인 완결 회차는 D4 가 아니라 D1 쪽이고 보정이 거둔다 — D4 경계가 repairHorizon 과 같다.
// 잡는 결함: 보정 창이 없으면 창 밖 회차를 보정한다(V25). README 경계가 보정 창과 다르면 경계 두 점이 갈린다(V27d).
func TestOpsD4CountsRoundsOutsideRepairHorizon(t *testing.T) {
	pool := newTestPool(t)
	now := dbNow(t, pool)
	outside, inside := now.Add(-repairHorizon-time.Hour), now.Add(-repairHorizon+time.Hour)
	seedRepairRound(t, pool, repairRound{id: "R-OUT", kind: roundComplete, start: outside})
	seedRepairRound(t, pool, repairRound{id: "R-LIVE", kind: roundNoSession, start: outside})
	seedRepairSession(t, pool, repairStream("R-LIVE"), "R-LIVE", "R-LIVE", "live", outside, false)
	seedRepairRound(t, pool, repairRound{id: "R-IN", kind: roundComplete, start: inside})
	p, _, sig := newRepairPublisher(t, pool)

	before := runOps(t, pool)
	p.tick(context.Background())
	after := runOps(t, pool)

	if before != (opsVerdict{d1: 1, d4Complete: 1, d4Open: 1}) {
		t.Errorf("보정 전 운영 질의 = %+v, want D1 1(R-IN) · D4 완결 1(R-OUT) · D4 미완결 1(R-LIVE)", before)
	}
	if after != (opsVerdict{d4Complete: 1, d4Open: 1}) {
		t.Errorf("보정 뒤 운영 질의 = %+v, want D4 완결 1 · D4 미완결 1 · 나머지 0", after)
	}
	if hasEnded(t, pool, "R-OUT") || !hasEnded(t, pool, "R-IN") || repairedCount(sig) != 1 {
		t.Errorf("ended 있음 R-OUT %v · R-IN %v · repaired %d, want 없음 · 있음 · 1",
			hasEnded(t, pool, "R-OUT"), hasEnded(t, pool, "R-IN"), repairedCount(sig))
	}
}

// J12′(e) — started 가 abandoned 인 회차는 창 안이든 밖이든 세 질의 어디에도 들지 않는다.
// 잡는 결함: D1 · D4 가 started 상태를 보지 않으면 버린 회차를 센다(V27c).
func TestOpsQueriesIgnoreAbandonedStarted(t *testing.T) {
	pool := newTestPool(t)
	now := dbNow(t, pool)
	seedRepairRound(t, pool, repairRound{id: "R-AIN", kind: roundComplete, start: now.Add(-time.Hour), started: "abandoned"})
	seedRepairRound(t, pool, repairRound{id: "R-AOUT", kind: roundComplete, start: now.Add(-repairHorizon - time.Hour),
		started: "abandoned"})

	if got := runOps(t, pool); got != (opsVerdict{}) {
		t.Errorf("운영 질의 = %+v, want 모두 0 — 버린 회차를 셌다", got)
	}
}

// J12′(f) — 문턱을 0 으로 둔 D1 의 회차 집합은 보정 후보(selectMissingEnded)와 같다. 사례는 J10′(b) 표(완결 아닌 후보
// 21개 · 완결 둘)에, 세션은 완결인데 후보가 아닌 회차 셋(started 정리됨 · abandoned · 창 밖)을 더했다. 모두 완결된 지
// 10분이 넘었다.
// 잡는 결함: README D1 이나 보정 술어 한쪽만 바뀌면 두 집합이 갈린다(V14 · V25 · V27a).
func TestOpsD1MatchesRepairCandidates(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	now := dbNow(t, pool)
	kinds := []repairKind{roundOpen, roundEnding, roundNoSession}
	for i := 0; i <= repairBatch; i++ {
		seedRepairRound(t, pool, repairRound{id: fmt.Sprintf("R-I%02d", i), kind: kinds[i%len(kinds)],
			start: now.Add(-3*time.Hour + time.Duration(i)*time.Minute)})
	}
	for i, id := range []string{"R-C1", "R-C2"} {
		seedRepairRound(t, pool, repairRound{id: id, kind: roundComplete, start: now.Add(-time.Hour + time.Duration(i)*time.Minute)})
	}
	seedRepairRound(t, pool, repairRound{id: "R-SET", kind: roundComplete, start: now.Add(-time.Hour)})
	mustExec(t, pool, "DELETE FROM broadcast_lifecycle_outbox WHERE round_id = 'R-SET'")
	seedRepairRound(t, pool, repairRound{id: "R-AB", kind: roundComplete, start: now.Add(-time.Hour), started: "abandoned"})
	seedRepairRound(t, pool, repairRound{id: "R-OUT", kind: roundComplete, start: now.Add(-repairHorizon - time.Hour)})
	d1 := opsQuery(t, "D1")
	for _, s := range []struct{ old, repl string }{
		{"SELECT pg_catalog.count(*)", "SELECT o.round_id"},
		{"interval '10 minutes'", "interval '0 minutes'"},
	} {
		if n := strings.Count(d1, s.old); n != 1 {
			t.Fatalf("README D1 의 %q 가 %d 번, want 1", s.old, n)
		}
		d1 = strings.Replace(d1, s.old, s.repl, 1)
	}

	tx := beginReadOnly(t, pool)
	defer tx.Rollback(ctx)
	rows, err := tx.Query(ctx, d1)
	if err != nil {
		t.Fatalf("문턱 0 D1 실행: %v", err)
	}
	fromREADME, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		t.Fatalf("문턱 0 D1 읽기: %v", err)
	}
	fromRepair, err := selectMissingEnded(ctx, pool, 1000)
	if err != nil {
		t.Fatalf("selectMissingEnded: %v", err)
	}

	slices.Sort(fromREADME)
	slices.Sort(fromRepair)
	if !slices.Equal(fromREADME, fromRepair) || !slices.Equal(fromRepair, []string{"R-C1", "R-C2"}) {
		t.Errorf("문턱 0 D1 = %v · 보정 후보 = %v, want 둘 다 [R-C1 R-C2]", fromREADME, fromRepair)
	}
}
