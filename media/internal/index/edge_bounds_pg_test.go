package index

// /dvr 하한 원장(stream_edge_bounds — POK-525 계획 부록 A) 시험. 원장은 열린 회차의 하한 정본이고 KVS 는 사본이다 —
// Writer(rewind/edgebounds)가 이 문장 넷으로 올리고(Raise) · 닫힌 회차를 골라(ClosedUnreleased) · 해제를 적고
// (MarkReleased) · 유예 뒤 지운다(DeleteReleased). 여기서는 문장의 뜻(열린 회차만 · GREATEST · 해제 행 무변 · 세션
// 없음 = 닫힘)과 권한(media_indexer 넷 · app_clip 없음) · 운영 SQL 사본(30 · 40)을 잰다.
//
//	문장                   메서드              표 · 동작
//	raiseSQL               Raise               edge_bounds INSERT · UPDATE(ON CONFLICT) · SELECT(RETURNING) · sessions SELECT
//	closedUnreleasedSQL    ClosedUnreleased    edge_bounds · sessions SELECT
//	markReleasedSQL        MarkReleased        edge_bounds SELECT FOR UPDATE · UPDATE
//	deleteReleasedSQL      DeleteReleased      edge_bounds DELETE(+ SELECT)

import (
	"context"
	"errors"
	"fmt"
	"slices"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/edgebounds"
)

// edgeBoundsPool 은 원장 시험 전용 DB 의 풀이다. 패키지 공용 resetTables 는 원장 표를 비우지 않으므로 여기서 함께 비운다.
func edgeBoundsPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	return pgtest.Pool(t, "edgebounds", EnsureSchema, func(ctx context.Context, pool *pgxpool.Pool) error {
		if err := resetTables(ctx, pool); err != nil {
			return err
		}
		_, err := pool.Exec(ctx, "TRUNCATE stream_edge_bounds")
		return err
	})
}

// plantOpenRound 는 stream 의 열린 회차 round(머리 세션 하나 · live)다.
func plantOpenRound(t *testing.T, pool *pgxpool.Pool, stream, round string) {
	t.Helper()
	plantRound(t, pool, roundFixture{id: round, stream: stream, state: "live", roundID: round,
		startedAt: time.Now().UTC().Add(-time.Hour)})
}

// plantClosedRound 는 stream 의 닫힌 회차 round 다.
func plantClosedRound(t *testing.T, pool *pgxpool.Pool, stream, round string) {
	t.Helper()
	now := time.Now().UTC()
	plantRound(t, pool, roundFixture{id: round, stream: stream, state: "ended", roundID: round,
		startedAt: now.Add(-time.Hour), endedAt: now.Add(-time.Minute), closedAt: now.Add(-time.Minute)})
}

// edgeBoundsRow 는 원장 한 행의 하한 · 해제 시각이다(없으면 ok = false).
func edgeBoundsRow(t *testing.T, pool *pgxpool.Pool, stream, round string) (floor int64, released *time.Time, ok bool) {
	t.Helper()
	rows, err := pool.Query(context.Background(),
		`SELECT dvr_floor, released_at FROM stream_edge_bounds WHERE stream_id = $1 AND round_id = $2`, stream, round)
	if err != nil {
		t.Fatalf("원장 행 조회 실패: %v", err)
	}
	defer rows.Close()
	for rows.Next() {
		if err := rows.Scan(&floor, &released); err != nil {
			t.Fatalf("원장 행 읽기 실패: %v", err)
		}
		ok = true
	}
	if err := rows.Err(); err != nil {
		t.Fatalf("원장 행 조회 실패: %v", err)
	}
	return floor, released, ok
}

func floors(rows ...edgebounds.Floor) []edgebounds.Floor { return rows }

// E4 — 원장 값은 내려가지 않는다(GREATEST). 늦게 도착한 작은 값 · 다른 Writer 의 옛 값이 하한을 내리면 엣지가 이미
// 막은 조각을 다시 연다.
func TestEdgeBoundsLedgerRaiseNeverLowers(t *testing.T) {
	pool := edgeBoundsPool(t)
	plantOpenRound(t, pool, "s", "R")
	l := NewEdgeBoundsLedger(pool)
	ctx := context.Background()

	steps := []struct {
		give, want int64
	}{{10, 10}, {5, 10}, {20, 20}, {20, 20}}
	for _, st := range steps {
		got, err := l.Raise(ctx, floors(edgebounds.Floor{StreamID: "s", RoundID: "R", Value: st.give}))
		want := floors(edgebounds.Floor{StreamID: "s", RoundID: "R", Value: st.want})
		if err != nil || !slices.Equal(got, want) {
			t.Errorf("Raise(%d) = (%v, %v), want (%v, nil)", st.give, got, err, want)
		}
	}
}

// E1 — 닫힌 회차 · 세션 행이 없는 회차에는 행을 만들지도 올리지도 않는다. 이 조건이 빠지면 해제 뒤 늦은 올림이 키를
// 되살려 VOD 앞부분이 404 다.
func TestEdgeBoundsLedgerRaiseIgnoresClosedRound(t *testing.T) {
	pool := edgeBoundsPool(t)
	plantOpenRound(t, pool, "a", "RA")
	plantClosedRound(t, pool, "b", "RB")
	l := NewEdgeBoundsLedger(pool)

	got, err := l.Raise(context.Background(), floors(
		edgebounds.Floor{StreamID: "a", RoundID: "RA", Value: 3},
		edgebounds.Floor{StreamID: "b", RoundID: "RB", Value: 3},
		edgebounds.Floor{StreamID: "c", RoundID: "RC", Value: 3}, // 세션 행 없음
	))

	want := floors(edgebounds.Floor{StreamID: "a", RoundID: "RA", Value: 3})
	if err != nil || !slices.Equal(got, want) {
		t.Errorf("Raise = (%v, %v), want (%v, nil)", got, err, want)
	}
	for _, k := range [][2]string{{"b", "RB"}, {"c", "RC"}} {
		if _, _, ok := edgeBoundsRow(t, pool, k[0], k[1]); ok {
			t.Errorf("닫힌 · 세션 없는 회차 %s/%s 에 원장 행이 생겼다", k[0], k[1])
		}
	}
}

// 다른 스트림의 같은 회차 이름은 열린 회차로 치지 않는다 — 열린 회차 판정은 (stream, round) 둘 다 본다.
func TestEdgeBoundsLedgerRaiseMatchesStreamAndRound(t *testing.T) {
	pool := edgeBoundsPool(t)
	plantOpenRound(t, pool, "a", "R")
	l := NewEdgeBoundsLedger(pool)

	got, err := l.Raise(context.Background(), floors(edgebounds.Floor{StreamID: "other", RoundID: "R", Value: 3}))

	if err != nil || len(got) != 0 {
		t.Errorf("다른 스트림의 Raise = (%v, %v), want (빈 목록, nil)", got, err)
	}
}

// 해제된 행은 다시 열린 듯 보여도(같은 회차의 열린 세션) 올리지 않고 돌려주지 않는다 — 한 번 지운 키는 되살아나지
// 않는다(Writer 계약 3).
func TestEdgeBoundsLedgerRaiseLeavesReleasedRow(t *testing.T) {
	pool := edgeBoundsPool(t)
	plantOpenRound(t, pool, "s", "R")
	l := NewEdgeBoundsLedger(pool)
	ctx := context.Background()
	row := edgebounds.Floor{StreamID: "s", RoundID: "R", Value: 10}
	if _, err := l.Raise(ctx, floors(row)); err != nil {
		t.Fatalf("첫 Raise 오류 = %v", err)
	}
	if err := l.MarkReleased(ctx, floors(row)); err != nil {
		t.Fatalf("MarkReleased 오류 = %v", err)
	}
	_, releasedBefore, _ := edgeBoundsRow(t, pool, "s", "R")

	got, err := l.Raise(ctx, floors(edgebounds.Floor{StreamID: "s", RoundID: "R", Value: 50}))

	if err != nil || len(got) != 0 {
		t.Errorf("해제 뒤 Raise = (%v, %v), want (빈 목록, nil)", got, err)
	}
	floor, releasedAfter, ok := edgeBoundsRow(t, pool, "s", "R")
	if !ok || floor != 10 || releasedBefore == nil || releasedAfter == nil || !releasedAfter.Equal(*releasedBefore) {
		t.Errorf("해제 뒤 원장 행 = (%d, %v, %t), want (10, %v, true)", floor, releasedAfter, ok, releasedBefore)
	}
}

// 1 보다 작은 값(CHECK dvr_floor >= 1)은 문장에 싣기 전에 거른다 — 그 행 하나로 묶음 전체가 23514 가 되지 않는다.
// 다 걸러지면 문장을 보내지 않는다.
func TestEdgeBoundsLedgerRaiseDropsValuesBelowOne(t *testing.T) {
	pool := edgeBoundsPool(t)
	plantOpenRound(t, pool, "a", "RA")
	plantOpenRound(t, pool, "b", "RB")
	plantOpenRound(t, pool, "c", "RC")
	l := NewEdgeBoundsLedger(pool)
	ctx := context.Background()

	got, err := l.Raise(ctx, floors(
		edgebounds.Floor{StreamID: "a", RoundID: "RA", Value: 0},
		edgebounds.Floor{StreamID: "b", RoundID: "RB", Value: -7},
		edgebounds.Floor{StreamID: "c", RoundID: "RC", Value: 7},
	))
	none, noneErr := l.Raise(ctx, floors(edgebounds.Floor{StreamID: "a", RoundID: "RA", Value: 0}))

	want := floors(edgebounds.Floor{StreamID: "c", RoundID: "RC", Value: 7})
	if err != nil || !slices.Equal(got, want) {
		t.Errorf("Raise(0 · -7 · 7) = (%v, %v), want (%v, nil)", got, err, want)
	}
	if noneErr != nil || len(none) != 0 {
		t.Errorf("Raise(0 하나) = (%v, %v), want (빈 목록, nil)", none, noneErr)
	}
	if _, _, ok := edgeBoundsRow(t, pool, "a", "RA"); ok {
		t.Error("값 0 의 원장 행이 생겼다")
	}
}

// E13 — 세션 행이 지워진 회차(60일 만료 · 탈퇴 정리)와 닫힌 회차는 해제 대상이다. 열린 회차 · 이미 해제한 행은 아니다.
// (stream, round) 순 · limit 개다.
func TestEdgeBoundsLedgerClosedUnreleasedTreatsMissingSessionAsClosed(t *testing.T) {
	pool := edgeBoundsPool(t)
	ctx := context.Background()
	for _, s := range []string{"a", "b", "c", "d"} {
		plantOpenRound(t, pool, s, "R"+s)
	}
	l := NewEdgeBoundsLedger(pool)
	all := floors(
		edgebounds.Floor{StreamID: "a", RoundID: "Ra", Value: 1},
		edgebounds.Floor{StreamID: "b", RoundID: "Rb", Value: 2},
		edgebounds.Floor{StreamID: "c", RoundID: "Rc", Value: 3},
		edgebounds.Floor{StreamID: "d", RoundID: "Rd", Value: 4},
	)
	if _, err := l.Raise(ctx, all); err != nil {
		t.Fatalf("Raise 오류 = %v", err)
	}
	// a = 열림 그대로 · b = 회차 닫힘 · c = 세션 행 지움 · d = 닫힘 + 이미 해제.
	if _, err := pool.Exec(ctx, `
		UPDATE stream_sessions SET round_closed_at = pg_catalog.now() WHERE round_id IN ('Rb', 'Rd');
		DELETE FROM stream_sessions WHERE round_id = 'Rc'`); err != nil {
		t.Fatalf("회차 닫기 · 세션 지우기 실패: %v", err)
	}
	if err := l.MarkReleased(ctx, floors(all[3])); err != nil {
		t.Fatalf("MarkReleased 오류 = %v", err)
	}

	got, err := l.ClosedUnreleased(ctx, 10)
	first, firstErr := l.ClosedUnreleased(ctx, 1)

	if want := floors(all[1], all[2]); err != nil || !slices.Equal(got, want) {
		t.Errorf("ClosedUnreleased(10) = (%v, %v), want (%v, nil)", got, err, want)
	}
	if want := floors(all[1]); firstErr != nil || !slices.Equal(first, want) {
		t.Errorf("ClosedUnreleased(1) = (%v, %v), want (%v, nil)", first, firstErr, want)
	}
}

// limit 이 1 보다 작으면 문장을 보내기 전에 거절한다 — 음수 LIMIT 는 PG 오류이고 0 은 늘 빈 목록이라 부른 쪽 결함이다.
func TestEdgeBoundsLedgerClosedUnreleasedRejectsNonPositiveLimit(t *testing.T) {
	l := NewEdgeBoundsLedger(nil) // 문장을 보내면 nil 풀에서 멈춘다
	for _, limit := range []int{0, -1} {
		if got, err := l.ClosedUnreleased(context.Background(), limit); err == nil {
			t.Errorf("ClosedUnreleased(%d) = (%v, nil), want 오류", limit, got)
		}
	}
}

// MarkReleased 는 이미 해제한 행의 시각을 바꾸지 않는다(유예 120초가 다시 시작되지 않는다). DeleteReleased 는 해제 뒤
// olderThan 이 지난 행만 지운다 — 미해제 행 · 갓 해제한 행은 남는다.
func TestEdgeBoundsLedgerMarkAndDeleteReleased(t *testing.T) {
	pool := edgeBoundsPool(t)
	ctx := context.Background()
	plantOpenRound(t, pool, "a", "Ra")
	plantOpenRound(t, pool, "b", "Rb")
	plantOpenRound(t, pool, "c", "Rc")
	l := NewEdgeBoundsLedger(pool)
	a := edgebounds.Floor{StreamID: "a", RoundID: "Ra", Value: 1}
	b := edgebounds.Floor{StreamID: "b", RoundID: "Rb", Value: 1}
	c := edgebounds.Floor{StreamID: "c", RoundID: "Rc", Value: 1}
	if _, err := l.Raise(ctx, floors(a, b, c)); err != nil {
		t.Fatalf("Raise 오류 = %v", err)
	}
	if err := l.MarkReleased(ctx, floors(a, b)); err != nil {
		t.Fatalf("MarkReleased 오류 = %v", err)
	}
	_, firstMark, _ := edgeBoundsRow(t, pool, "a", "Ra")
	if err := l.MarkReleased(ctx, floors(a)); err != nil {
		t.Fatalf("두 번째 MarkReleased 오류 = %v", err)
	}
	_, secondMark, _ := edgeBoundsRow(t, pool, "a", "Ra")
	if firstMark == nil || secondMark == nil || !secondMark.Equal(*firstMark) {
		t.Errorf("두 번째 MarkReleased 뒤 해제 시각 = %v, want %v 그대로", secondMark, firstMark)
	}
	if _, err := pool.Exec(ctx, `UPDATE stream_edge_bounds SET released_at = released_at - interval '1 hour'
		WHERE stream_id = 'a'`); err != nil {
		t.Fatalf("해제 시각 당기기 실패: %v", err)
	}

	n, err := l.DeleteReleased(ctx, 30*time.Minute)

	if err != nil || n != 1 {
		t.Errorf("DeleteReleased(30m) = (%d, %v), want (1, nil)", n, err)
	}
	for _, k := range []struct {
		stream, round string
		want          bool
	}{{"a", "Ra", false}, {"b", "Rb", true}, {"c", "Rc", true}} {
		if _, _, ok := edgeBoundsRow(t, pool, k.stream, k.round); ok != k.want {
			t.Errorf("DeleteReleased 뒤 %s 행 있음 = %t, want %t", k.stream, ok, k.want)
		}
	}
	if err := l.MarkReleased(ctx, nil); err != nil {
		t.Errorf("MarkReleased(빈 목록) 오류 = %v, want nil", err)
	}
}

// olderThan 이 음수면 아직 해제 유예 안의 행(미래 시각 기준)까지 지우므로 거절한다.
func TestEdgeBoundsLedgerDeleteReleasedRejectsNegativeAge(t *testing.T) {
	l := NewEdgeBoundsLedger(nil) // 문장을 보내면 nil 풀에서 멈춘다
	if n, err := l.DeleteReleased(context.Background(), -time.Second); err == nil {
		t.Errorf("DeleteReleased(-1s) = (%d, nil), want 오류", n)
	}
}

// E4 동시성 몫 — 두 연결(상자 둘의 Writer)이 같은 회차들을 서로 반대 순서로 동시에 올려도 교착(40P01) 없이 끝나고,
// 원장 값은 둘이 보낸 값의 최댓값이다. 경합은 운에 맡기지 않는다 — 세 번째 연결이 가운데 행(R10)을 쥔 채(시작 장벽) 앞
// Writer 가 R00–R09 를 잡고 R10 에서 줄을 선 것을 본 뒤 뒤 Writer 를 보내고, 뒤 Writer 도 줄을 선 것을 본 뒤 놓는다.
// 잠금 순서가 고정이면 뒤 Writer 는 R00 에서 앞 Writer 를 기다리다 앞 Writer 가 커밋한 값 위에서 돈다. 변이 = GREATEST
// 제거 → 뒤 Writer 의 작은 값이 남아 FAIL · 문장의 ORDER BY 제거 → 뒤 Writer 가 R19–R11 을 쥐고 R10 에서 앞 Writer 와
// 서로 기다려 40P01 로 FAIL.
func TestEdgeBoundsLedgerConcurrentRaiseConverges(t *testing.T) {
	pool := edgeBoundsPool(t)
	ctx := context.Background()
	const rounds = 20
	var first, second, seed []edgebounds.Floor
	for i := range rounds {
		r := fmt.Sprintf("R%02d", i)
		plantOpenRound(t, pool, "s"+r, r)
		// 짝수 회차는 앞 Writer 가, 홀수 회차는 뒤 Writer 가 큰 값을 보낸다 — 어느 쪽이 덮어써도 틀린 회차가 남는다.
		big, small := int64(100+i), int64(10+i)
		if i%2 == 1 {
			big, small = small, big
		}
		first = append(first, edgebounds.Floor{StreamID: "s" + r, RoundID: r, Value: big})
		second = append(second, edgebounds.Floor{StreamID: "s" + r, RoundID: r, Value: small})
		seed = append(seed, edgebounds.Floor{StreamID: "s" + r, RoundID: r, Value: 1})
	}
	if _, err := NewEdgeBoundsLedger(pool).Raise(ctx, seed); err != nil {
		t.Fatalf("행 심기 Raise 오류 = %v", err)
	}
	firstPool, firstPID := singleConnPool(t, pool)
	secondPool, secondPID := singleConnPool(t, pool)
	holder := holdEdgeBoundsRows(t, pool, "sR10")
	errs := make(chan error, 2)
	raise := func(p *pgxpool.Pool, rows []edgebounds.Floor) {
		go func() {
			_, err := NewEdgeBoundsLedger(p).Raise(ctx, rows)
			errs <- err
		}()
	}

	raise(firstPool, first)
	waitLockWait(t, pool, firstPID)
	reversed := slices.Clone(second)
	slices.Reverse(reversed)
	raise(secondPool, reversed)
	waitLockWait(t, pool, secondPID)
	if err := holder.Commit(ctx); err != nil {
		t.Fatalf("시작 장벽 풀기 실패: %v", err)
	}

	for range 2 {
		if err := <-errs; err != nil {
			t.Errorf("동시 Raise 오류 = %v, want nil", err)
		}
	}
	for i, f := range first {
		want := max(f.Value, second[i].Value)
		if got, _, _ := edgeBoundsRow(t, pool, f.StreamID, f.RoundID); got != want {
			t.Errorf("%s 원장 값 = %d, want %d(두 Writer 의 최댓값)", f.RoundID, got, want)
		}
	}
}

// 해제 기록도 행 잠금을 (stream, round) 순으로 잡는다 — 두 상자의 Writer 가 겹치는 행을 다른 순서로 넘겨도 교착이 없다.
// 앞 열쇠(a)를 뒤 열쇠(z) 뒤에 넘기고 물리 순서도 z 먼저로 심어, 입력 순서 · 표 순서 어느 쪽을 따라도 z 를 먼저 잡게
// 해 둔다. z 를 다른 연결이 쥐었을 때 MarkReleased 가 기다리는 동안 a 는 이미 잠겨 있어야 한다(NOWAIT 이 55P03).
// 변이 = 문장의 ORDER BY … FOR UPDATE 제거 → z 에서 먼저 멈춰 a 가 비어 FAIL.
func TestEdgeBoundsLedgerMarkReleasedLocksInKeyOrder(t *testing.T) {
	pool := edgeBoundsPool(t)
	ctx := context.Background()
	l := NewEdgeBoundsLedger(pool)
	a := edgebounds.Floor{StreamID: "a", RoundID: "Ra", Value: 1}
	z := edgebounds.Floor{StreamID: "z", RoundID: "Rz", Value: 1}
	for _, f := range []edgebounds.Floor{z, a} {
		plantOpenRound(t, pool, f.StreamID, f.RoundID)
		if _, err := l.Raise(ctx, floors(f)); err != nil {
			t.Fatalf("%s Raise 오류 = %v", f.StreamID, err)
		}
	}
	writer, writerPID := singleConnPool(t, pool)
	holder := holdEdgeBoundsRows(t, pool, "z")
	done := make(chan error, 1)
	go func() { done <- NewEdgeBoundsLedger(writer).MarkReleased(ctx, floors(z, a)) }()
	waitLockWait(t, pool, writerPID)

	_, err := pool.Exec(ctx, `SELECT 1 FROM stream_edge_bounds WHERE stream_id = 'a' FOR UPDATE NOWAIT`)

	if !isSQLState(err, pgLockNotAvailable) {
		t.Errorf("z 를 기다리는 MarkReleased 옆에서 a 의 NOWAIT 잠금 오류 = %v, want 55P03(a 를 먼저 잡았다)", err)
	}
	if err := holder.Commit(ctx); err != nil {
		t.Fatalf("z 잠금 풀기 실패: %v", err)
	}
	if err := <-done; err != nil {
		t.Errorf("MarkReleased 오류 = %v, want nil", err)
	}
	for _, f := range []edgebounds.Floor{a, z} {
		if _, released, ok := edgeBoundsRow(t, pool, f.StreamID, f.RoundID); !ok || released == nil {
			t.Errorf("%s 해제 시각 = %v (행 있음 %t), want 적힘", f.StreamID, released, ok)
		}
	}
}

// singleConnPool 은 pool 과 같은 DB 의 연결 하나짜리 풀(상자 하나의 Writer 연결)과 그 연결의 백엔드 pid 다.
func singleConnPool(t *testing.T, pool *pgxpool.Pool) (*pgxpool.Pool, int32) {
	t.Helper()
	ctx := context.Background()
	cfg := pool.Config()
	cfg.MinConns, cfg.MaxConns = 0, 1
	p, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatalf("연결 하나짜리 풀 만들기 실패: %v", err)
	}
	t.Cleanup(p.Close)
	var pid int32
	if err := p.QueryRow(ctx, "SELECT pg_catalog.pg_backend_pid()").Scan(&pid); err != nil {
		t.Fatalf("백엔드 pid 조회 실패: %v", err)
	}
	return p, pid
}

// holdEdgeBoundsRows 는 streams 의 원장 행을 FOR UPDATE 로 쥔 트랜잭션이다 — 커밋할 때까지 그 행을 건드리는 문장은
// 줄을 선다. 시험이 커밋하지 않고 끝나면 되돌린다.
func holdEdgeBoundsRows(t *testing.T, pool *pgxpool.Pool, streams ...string) pgx.Tx {
	t.Helper()
	ctx := context.Background()
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("잠금 트랜잭션 시작 실패: %v", err)
	}
	t.Cleanup(func() {
		if err := tx.Rollback(context.Background()); err != nil && !errors.Is(err, pgx.ErrTxClosed) {
			t.Errorf("잠금 트랜잭션 되돌리기 실패: %v", err)
		}
	})
	if _, err := tx.Exec(ctx, `SELECT 1 FROM stream_edge_bounds WHERE stream_id = ANY($1) FOR UPDATE`, streams); err != nil {
		t.Fatalf("원장 행 잠금 실패: %v", err)
	}
	return tx
}

// waitLockWait 는 백엔드 pid 가 잠금을 기다리기 시작할 때까지 기다린다(5초).
func waitLockWait(t *testing.T, pool *pgxpool.Pool, pid int32) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for {
		var waiting bool
		if err := pool.QueryRow(context.Background(), `SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_stat_activity
			WHERE pid = $1 AND wait_event_type = 'Lock')`, pid).Scan(&waiting); err != nil {
			t.Fatalf("잠금 대기 조회 실패: %v", err)
		}
		if waiting {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("백엔드 %d 가 5초 안에 잠금 대기에 들지 않았다", pid)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// 열린 회차 판정은 stream_sessions_round_open_idx 부분 인덱스를 탄다(설계 3-3) — 세션 표가 커도 올림 · 해제 조회가
// 세션을 처음부터 훑지 않는다.
func TestEdgeBoundsLedgerOpenRoundCheckUsesPartialIndex(t *testing.T) {
	pool := edgeBoundsPool(t)
	ctx := context.Background()
	if _, err := pool.Exec(ctx, `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, ending_at, ended_at, round_id, round_closed_at)
SELECT 'p' || i, 'plan-' || i, pg_catalog.now(), 'ended', pg_catalog.now(), pg_catalog.now(), 'p' || i,
       CASE WHEN i % 50 = 0 THEN NULL ELSE pg_catalog.now() END
  FROM pg_catalog.generate_series(1, 20000) AS i;
INSERT INTO stream_edge_bounds (stream_id, round_id, dvr_floor)
SELECT 'plan-' || i, 'p' || i, 1 FROM pg_catalog.generate_series(1, 20) AS i;
ANALYZE stream_sessions; ANALYZE stream_edge_bounds`); err != nil {
		t.Fatalf("계획 픽스처 실패: %v", err)
	}
	cases := []struct {
		name string
		sql  string
		args []any
	}{
		{"raiseSQL", raiseSQL, []any{[]string{"plan-50"}, []string{"p50"}, []int64{3}}},
		{"closedUnreleasedSQL", closedUnreleasedSQL, []any{49}},
	}
	for _, tc := range cases {
		var plan []byte
		if err := pool.QueryRow(ctx, `EXPLAIN (FORMAT JSON) `+tc.sql, tc.args...).Scan(&plan); err != nil {
			t.Fatalf("%s EXPLAIN 실패: %v", tc.name, err)
		}
		if nodes := planScanNodes(t, plan); !slices.Contains(nodes, "stream_sessions_round_open_idx") {
			t.Errorf("%s 가 stream_sessions_round_open_idx 를 타지 않는다 — 계획 노드 %v\n%s", tc.name, nodes, plan)
		}
	}
}

// media_indexer 권한만으로 문장 넷이 돌고(42501 0) 검색 경로 덫을 부르지 않는다.
func TestEdgeBoundsLedgerRunsUnderMediaIndexerRole(t *testing.T) {
	admin := edgeBoundsPool(t)
	traps := pgtest.PlantSearchPathTraps(t, admin)
	role := pgtest.NewMediaIndexerRole(t, admin)
	t.Cleanup(func() { role.RequireNoneDenied(t) })
	plantOpenRound(t, admin, "s", "R")
	l := NewEdgeBoundsLedger(role.Pool)
	ctx := context.Background()
	row := edgebounds.Floor{StreamID: "s", RoundID: "R", Value: 4}

	if got, err := l.Raise(ctx, floors(row)); err != nil || !slices.Equal(got, floors(row)) {
		t.Errorf("Raise = (%v, %v), want (%v, nil)", got, err, floors(row))
	}
	if _, err := admin.Exec(ctx, `UPDATE stream_sessions SET round_closed_at = pg_catalog.now() WHERE round_id = 'R'`); err != nil {
		t.Fatalf("회차 닫기 실패: %v", err)
	}
	if got, err := l.ClosedUnreleased(ctx, 49); err != nil || !slices.Equal(got, floors(row)) {
		t.Errorf("ClosedUnreleased = (%v, %v), want (%v, nil)", got, err, floors(row))
	}
	if err := l.MarkReleased(ctx, floors(row)); err != nil {
		t.Errorf("MarkReleased 오류 = %v", err)
	}
	if n, err := l.DeleteReleased(ctx, 0); err != nil || n != 1 {
		t.Errorf("DeleteReleased(0) = (%d, %v), want (1, nil)", n, err)
	}
	role.RequireSent(t, map[string]string{
		"raiseSQL": raiseSQL, "closedUnreleasedSQL": closedUnreleasedSQL,
		"markReleasedSQL": markReleasedSQL, "deleteReleasedSQL": deleteReleasedSQL,
	})
	traps.RequireNotCalled(t)
}

// app_clip(3번 목록)은 원장 표에 아무 권한이 없다 — 탈퇴 정리는 세션 행을 지울 뿐이고 원장 행은 Writer 가 「세션 없음 =
// 닫힘」 으로 거둔다(설계 3-3 권한).
func TestEdgeBoundsLedgerDeniedToAppClip(t *testing.T) {
	admin := edgeBoundsPool(t)
	clip := clipRolePool(t, admin)
	ctx := context.Background()
	for _, sql := range []string{
		"SELECT count(*) FROM stream_edge_bounds",
		"INSERT INTO stream_edge_bounds (stream_id, round_id, dvr_floor) VALUES ('s', 'R', 1)",
		"DELETE FROM stream_edge_bounds",
	} {
		if _, err := clip.Exec(ctx, sql); !pgtest.IsInsufficientPrivilege(err) {
			t.Errorf("app_clip 의 %q 오류 = %v, want 42501", sql, err)
		}
	}
}

// 원장 표가 없는 DB 에서 기동 스키마 확인이 그 이름을 돌려준다 — 사이드카는 기동을 멈춘다(운영 SQL 1 을 먼저 적용).
func TestMissingLedgerSchemaNamesEdgeBounds(t *testing.T) {
	admin := edgeBoundsPool(t)
	ctx := context.Background()
	role := pgtest.NewMediaIndexerRole(t, admin)
	if _, err := admin.Exec(ctx, "DROP TABLE stream_edge_bounds"); err != nil {
		t.Fatalf("원장 표 지우기 실패: %v", err)
	}
	t.Cleanup(func() { // 풀보다 늦게 등록 — 풀이 닫히기 전에 돈다
		if err := EnsureSchema(context.Background(), admin); err != nil {
			t.Errorf("스키마 되돌리기 실패: %v", err)
		}
	})

	missing, err := MissingLedgerSchema(ctx, role.Pool)

	if want := []string{"stream_edge_bounds"}; err != nil || !slices.Equal(missing, want) {
		t.Errorf("원장 표 없는 DB 의 MissingLedgerSchema = (%q, %v), want (%q, nil)", missing, err, want)
	}
}

// replaceEdgeBoundsTable 은 원장 표를 ddl 로 갈아 끼우는 조작이다 — 소유자 · media_indexer 넷은 운영 SQL 과 같게 되돌려,
// 40 이 표 모양 어긋남만 식별자로 내는지 본다.
func replaceEdgeBoundsTable(ddl string) string {
	return "DROP TABLE stream_edge_bounds;\n" + ddl + ";\n" +
		"ALTER TABLE stream_edge_bounds OWNER TO media_owner;\n" +
		"GRANT SELECT, INSERT, UPDATE, DELETE ON stream_edge_bounds TO media_indexer"
}

// 운영 SQL 이 원장 표를 만들고 media_indexer 에 넷만 준다 — 40 이 그 권한 · 소유자 어긋남을 식별자로 잡는다. 표 모양
// (PK (stream_id, round_id) · CHECK dvr_floor >= 1 · 열의 NOT NULL)도 잡는다 — 같은 이름 다른 모양 표가 지나가면 올림의
// ON CONFLICT 가 42P10 으로 실패해 write_failing 으로만 드러난다(종결 r1 db 설계 M1).
func TestProdSQLVerifyCatchesEdgeBoundsDrift(t *testing.T) {
	env := newProdSQLEnv(t)
	env.mustApplyAll(t)
	tests := []struct {
		name   string
		tamper string
		want   []string
	}{
		{
			name:   "indexer delete revoked",
			tamper: "REVOKE DELETE ON stream_edge_bounds FROM media_indexer",
			want:   []string{"privilege:media_indexer:stream_edge_bounds:DELETE"},
		},
		{
			name:   "app_clip read granted",
			tamper: "GRANT SELECT ON stream_edge_bounds TO app_clip",
			want:   []string{"privilege:app_clip:stream_edge_bounds:SELECT"},
		},
		{
			name:   "janitor read granted",
			tamper: "GRANT SELECT ON stream_edge_bounds TO media_janitor",
			want:   []string{"privilege:media_janitor:stream_edge_bounds:SELECT"},
		},
		{
			name:   "table dropped",
			tamper: "DROP TABLE stream_edge_bounds",
			want:   []string{"owner:stream_edge_bounds"},
		},
		{
			name: "same name table without primary key",
			tamper: replaceEdgeBoundsTable(`CREATE TABLE stream_edge_bounds (
    stream_id   text        NOT NULL,
    round_id    text        NOT NULL,
    dvr_floor   bigint      NOT NULL CHECK (dvr_floor >= 1),
    released_at timestamptz,
    updated_at  timestamptz NOT NULL DEFAULT pg_catalog.now()
)`),
			want: []string{"primary_key:stream_edge_bounds"},
		},
		{
			name: "same name table without floor check",
			tamper: replaceEdgeBoundsTable(`CREATE TABLE stream_edge_bounds (
    stream_id   text        NOT NULL,
    round_id    text        NOT NULL,
    dvr_floor   bigint      NOT NULL,
    released_at timestamptz,
    updated_at  timestamptz NOT NULL DEFAULT pg_catalog.now(),
    PRIMARY KEY (stream_id, round_id)
)`),
			want: []string{"check:stream_edge_bounds.dvr_floor"},
		},
		{
			name: "primary key reordered",
			tamper: "ALTER TABLE stream_edge_bounds DROP CONSTRAINT stream_edge_bounds_pkey;\n" +
				"ALTER TABLE stream_edge_bounds ADD PRIMARY KEY (round_id, stream_id)",
			want: []string{"primary_key:stream_edge_bounds"},
		},
		{
			name: "floor check loosened",
			tamper: "ALTER TABLE stream_edge_bounds DROP CONSTRAINT stream_edge_bounds_dvr_floor_check;\n" +
				"ALTER TABLE stream_edge_bounds ADD CHECK (dvr_floor >= 0)",
			want: []string{"check:stream_edge_bounds.dvr_floor"},
		},
		{
			name:   "floor nullable",
			tamper: "ALTER TABLE stream_edge_bounds ALTER COLUMN dvr_floor DROP NOT NULL",
			want:   []string{"not_null:stream_edge_bounds.dvr_floor"},
		},
		{
			name:   "updated_at nullable",
			tamper: "ALTER TABLE stream_edge_bounds ALTER COLUMN updated_at DROP NOT NULL",
			want:   []string{"not_null:stream_edge_bounds.updated_at"},
		},
		{
			name:   "released_at not null",
			tamper: "ALTER TABLE stream_edge_bounds ALTER COLUMN released_at SET NOT NULL",
			want:   []string{"not_null:stream_edge_bounds.released_at"},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := env.verifyFailuresAfter(t, tt.tamper); !slices.Equal(got, tt.want) {
				t.Errorf("%s 뒤 40_verify.sql 실패 단언 = %q, want %q", tt.tamper, got, tt.want)
			}
		})
	}
}
