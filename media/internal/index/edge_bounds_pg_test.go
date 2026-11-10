package index

// /dvr 하한 원장(stream_edge_bounds — POK-525 계획 부록 A) 시험. 원장은 열린 회차의 하한 정본이고 KVS 는 사본이다 —
// Writer(rewind/edgebounds)가 이 문장 넷으로 올리고(Raise) · 닫힌 회차를 골라(ClosedUnreleased) · 해제를 적고
// (MarkReleased) · 유예 뒤 지운다(DeleteReleased). 여기서는 문장의 뜻(열린 회차만 · GREATEST · 해제 행 무변 · 세션
// 없음 = 닫힘)과 권한(media_indexer 넷 · app_clip 없음) · 운영 SQL 사본(30 · 40)을 잰다.
//
//	문장                   메서드              표 · 동작
//	raiseTxnLimitsSQL      Raise 문장마다 첫 문장  (표 없음 — set_config lock_timeout)
//	raiseSQL               Raise               edge_bounds INSERT · UPDATE(ON CONFLICT) · SELECT(RETURNING) ·
//	                                           sessions FOR SHARE
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
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/edgebounds"
)

// edgeBoundsPool 은 원장 시험 전용 DB 의 풀이다. 패키지 공용 resetTables 는 원장 표를 비우지 않으므로 여기서 함께
// 비운다.
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

// E3 — 회차 닫힘이 커밋된 뒤에는 그 회차에 원장 행이 새로 생기지 않는다. 닫힘 커밋 순간 원장 행이 안 보이면 판정식이
// 곧바로 vod 를 내므로, 그 뒤에 생긴 행(과 KVS 하한 키)은 이미 공개한 VOD 앞부분을 막는다(종결 r2 codex 설계 H1).
// 경합은 운에 맡기지 않는다 — 다른 연결이 묶음 앞 회차(RA)의 원장 행을 쥐어 Raise([RA, RB]) 를 RA 에서 세운 뒤, 원장
// 행이 없는 뒤 회차(RB · 동결 끝난 VOD)를 닫는다. 닫힘이 끝났으면(커밋) 그때의 원장 행 · 판정식을 적고, 잠금 대기에
// 들었으면 Raise 를 먼저 끝내게 둔다. 변이 = raiseSQL 의 FOR SHARE 제거 → 닫힘이 Raise 를 기다리지 않고 커밋해 판정식이
// vod 를 낸 뒤 RB 행이 생겨 FAIL.
func TestEdgeBoundsLedgerRaiseSerializesWithRoundClose(t *testing.T) {
	pool := edgeBoundsPool(t)
	ctx := context.Background()
	plantOpenRound(t, pool, "a", "RA")
	now := time.Now().UTC()
	plantRound(t, pool, roundFixture{id: "RB", stream: "b", state: "ended", roundID: "RB", vodState: "frozen",
		vodKey: "vod/b/RB/index.m3u8", startedAt: now.Add(-time.Hour), endedAt: now.Add(-time.Minute)})
	seed := edgebounds.Floor{StreamID: "a", RoundID: "RA", Value: 1}
	if _, err := NewEdgeBoundsLedger(pool).Raise(ctx, floors(seed)); err != nil {
		t.Fatalf("RA 행 심기 Raise 오류 = %v", err)
	}
	writer, writerPID := singleConnPool(t, pool)
	closer, closerPID := singleConnPool(t, pool)
	holder := holdEdgeBoundsRows(t, pool, "a")
	raised := make(chan error, 1)
	go func() {
		_, err := NewEdgeBoundsLedger(writer).Raise(ctx, floors(
			edgebounds.Floor{StreamID: "a", RoundID: "RA", Value: 5},
			edgebounds.Floor{StreamID: "b", RoundID: "RB", Value: 5}))
		raised <- err
	}()
	waitLockWait(t, pool, writerPID)
	closed := make(chan error, 1)
	go func() {
		_, err := closer.Exec(ctx, closeRoundSQL, "RB")
		closed <- err
	}()

	closedFirst := waitDoneOrLockWait(t, pool, closerPID, closed)
	var rowAtClose bool
	var phaseAtClose PlaybackPhase
	if closedFirst {
		_, _, rowAtClose = edgeBoundsRow(t, pool, "b", "RB")
		phaseAtClose = lookupPhase(t, pool, "b", "RB")
	}
	if err := holder.Commit(ctx); err != nil {
		t.Fatalf("RA 잠금 풀기 실패: %v", err)
	}
	if err := <-raised; err != nil {
		t.Fatalf("Raise 오류 = %v, want nil", err)
	}
	if !closedFirst {
		if err := <-closed; err != nil {
			t.Fatalf("회차 닫힘 오류 = %v, want nil", err)
		}
		_, _, rowAtClose = edgeBoundsRow(t, pool, "b", "RB")
		phaseAtClose = lookupPhase(t, pool, "b", "RB")
	}

	if _, _, rowAtEnd := edgeBoundsRow(t, pool, "b", "RB"); rowAtEnd && !rowAtClose {
		t.Errorf("닫힘 커밋 때 RB 원장 행 없음(판정식 vod = %t) → Raise 뒤 행 있음, want 닫힌 회차에 새 행 없음",
			phaseAtClose == PhaseVOD)
	}
	if !rowAtClose && phaseAtClose != PhaseVOD || rowAtClose && phaseAtClose != PhaseEnding {
		t.Errorf("닫힘 커밋 뒤 RB 판정식 = %d (원장 행 %t), want 행 있으면 ending(%d) · 없으면 vod(%d)",
			phaseAtClose, rowAtClose, PhaseEnding, PhaseVOD)
	}
}

// 닫힘이 회차 행을 먼저 잠갔으면(TryCloseRound 의 ③ 잠금 뒤 닫힘 UPDATE · 커밋 전) Raise 는 그 커밋을 기다렸다가
// 최신 행을 다시 보고(EvalPlanQual) 그 회차를 뺀다 — 닫힘 커밋 뒤에 원장 행이 생기지 않는다. 변이 = FOR SHARE 제거 →
// Raise 가 기다리지 않고 옛 스냅숏의 「열림」 으로 행을 만들어, 닫힘 커밋 뒤 닫힌 회차에 미해제 행이 남아 FAIL.
func TestEdgeBoundsLedgerRaiseWaitsForLockedRoundClose(t *testing.T) {
	pool := edgeBoundsPool(t)
	ctx := context.Background()
	plantOpenRound(t, pool, "b", "RB")
	writer, writerPID := singleConnPool(t, pool)
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("닫힘 트랜잭션 시작 실패: %v", err)
	}
	t.Cleanup(func() {
		if err := tx.Rollback(context.Background()); err != nil && !errors.Is(err, pgx.ErrTxClosed) {
			t.Errorf("닫힘 트랜잭션 되돌리기 실패: %v", err)
		}
	})
	for _, sql := range []string{lockRoundRowsSQL, closeRoundSQL} {
		if _, err := tx.Exec(ctx, sql, "RB"); err != nil {
			t.Fatalf("회차 잠금 · 닫힘 실패: %v", err)
		}
	}
	raised := make(chan error, 1)
	var got []edgebounds.Floor
	go func() {
		var err error
		got, err = NewEdgeBoundsLedger(writer).Raise(ctx, floors(edgebounds.Floor{StreamID: "b", RoundID: "RB", Value: 5}))
		raised <- err
	}()

	raisedFirst := waitDoneOrLockWait(t, pool, writerPID, raised)
	if err := tx.Commit(ctx); err != nil {
		t.Fatalf("회차 닫힘 커밋 실패: %v", err)
	}
	if !raisedFirst {
		if err := <-raised; err != nil {
			t.Fatalf("Raise 오류 = %v, want nil", err)
		}
	}

	if _, _, ok := edgeBoundsRow(t, pool, "b", "RB"); ok || len(got) != 0 {
		t.Errorf("닫힘 커밋 뒤 RB 원장 행 있음 = %t · Raise = %v (기다렸음 %t), want 행 없음 · 빈 목록", ok, got, !raisedFirst)
	}
}

// 한 스트림에 열린 회차가 둘일 때(옛 회차 RO 가 닫힘을 기다리는 사이 새 회차 RN 이 live) 회차 닫힘(TryCloseRound — ②
// 소속 행 = RN 의 live → ③ RO 의 회차 행)과 Raise([RO, RN]) 가 겹쳐도 교착(40P01)이 없다. 위상 트랜잭션끼리는 ① 위상
// 잠금으로 줄을 서 잠금 순서가 제각각이어도 되지만 Raise 는 그 밖이다 — 한 문장이 한 스트림의 두 회차 행을 쥐면 순환이
// 생긴다. 닫힘 쪽이 소속 행을 쥔 채 Raise 가 그 행에서 줄을 선 것을 본 뒤 RO 회차 행을 잠근다. 변이 = 스트림별 문장
// 나누기 제거 → Raise 가 RO 행을 FOR SHARE 로 쥔 채 RN 에서 기다려, 닫힘의 ③ 과 서로 기다리다 한쪽이 40P01 로 FAIL.
func TestEdgeBoundsLedgerRaiseNoDeadlockWithRoundCloseAcrossRounds(t *testing.T) {
	pool := edgeBoundsPool(t)
	ctx := context.Background()
	now := time.Now().UTC()
	plantRound(t, pool, roundFixture{id: "RO", stream: "s", state: "ended", roundID: "RO",
		startedAt: now.Add(-time.Hour), endedAt: now.Add(-time.Minute)})
	plantRound(t, pool, roundFixture{id: "RN", stream: "s", state: "live", roundID: "RN", startedAt: now.Add(-time.Second)})
	writer, writerPID := singleConnPool(t, pool)
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("닫힘 트랜잭션 시작 실패: %v", err)
	}
	t.Cleanup(func() {
		if err := tx.Rollback(context.Background()); err != nil && !errors.Is(err, pgx.ErrTxClosed) {
			t.Errorf("닫힘 트랜잭션 되돌리기 실패: %v", err)
		}
	})
	if _, err := tx.Exec(ctx, lockSessionSQL, "RN"); err != nil {
		t.Fatalf("소속 행 잠금 실패: %v", err)
	}
	raised := make(chan error, 1)
	go func() {
		_, err := NewEdgeBoundsLedger(writer).Raise(ctx, floors(
			edgebounds.Floor{StreamID: "s", RoundID: "RO", Value: 5},
			edgebounds.Floor{StreamID: "s", RoundID: "RN", Value: 5}))
		raised <- err
	}()
	waitLockWait(t, pool, writerPID)

	_, lockErr := tx.Exec(ctx, lockRoundRowsSQL, "RO")
	if lockErr == nil {
		_, lockErr = tx.Exec(ctx, closeRoundSQL, "RO")
	}
	if lockErr == nil {
		lockErr = tx.Commit(ctx)
	}

	if lockErr != nil {
		t.Errorf("닫힘의 ③ · 닫힘 · 커밋 오류 = %v, want nil", lockErr)
		if err := tx.Rollback(ctx); err != nil && !errors.Is(err, pgx.ErrTxClosed) {
			t.Fatalf("닫힘 트랜잭션 되돌리기 실패: %v", err)
		}
	}
	if err := <-raised; err != nil {
		t.Errorf("Raise 오류 = %v, want nil", err)
	}
	if _, _, ok := edgeBoundsRow(t, pool, "s", "RN"); !ok {
		t.Error("새 회차 RN 의 원장 행이 없다")
	}
}

// Raise 가 한 스트림의 세션 행에서 기다리는 동안 같은 문장에서 이미 쥔 다른 스트림의 세션 행 갱신(P0 · P4 · fence ·
// 위상)도 그 뒤에 줄을 선다 — 기다림이 번진다(종결 r3 M2). 올림은 raiseTxnLimitsSQL 의 lock_timeout 에서 55P03 으로
// 그만두고 쥔 행을 놓는다 — 번진 기다림도 그 안에서 끝난다. 끊긴 문장은 되돌려져 원장 행이 없고, 부르는 쪽(Writer)은
// 그 오류를 write_failing reason=failed pass=raise 로 알린 뒤 다음 올림 틱에 GREATEST 로 다시 맞춘다 — 잠금이 풀린 뒤
// 다시 부른 올림이 두 회차를 다 올린다.
//
// 다른 연결 C 가 sb 의 세션 행을 쥐어(FOR NO KEY UPDATE — 위상 · fence UPDATE 가 거는 잠금) Raise([sa, sb]) 를 sb 에서
// 세우고 sa 한 행 fence UPDATE 를 그 뒤에 세운다. 판정은 C 를 쥔 채로 한다 — Raise 가 55P03 으로 끝나고 sa UPDATE 가
// C 를 놓기 전에 끝나야 한다(번진 기다림을 끝낸 것이 C 의 커밋이 아니라 Raise 의 상한이다). sa UPDATE 를 세울 창은
// Raise 가 sb 에서 기다리는 500ms 안이라, 부하로 그 창을 놓치면(막힌 것을 보기 전에 Raise 가 끝남) 판정 없이 다시
// 한다. 시도마다 Raise 호출 시간이 lock_timeout 500ms 언저리(lockSpreadRaiseWait)인지도 본다 — 창을 놓친 시도도
// 500ms 를 기다린 뒤에 끝나므로, 그보다 빨리 끝난 시도는 놓친 창이 아니라 상한이 틀린 것이다. 변이 = 올림의
// lock_timeout 제거(set_config 제거 · 트랜잭션 밖 설정 · 0) → Raise 가 C 를 lockSpreadBound 넘게 기다려 FAIL · 값
// 키움(4s) → 상한에서 FAIL · 값 줄임(1ms) → 하한에서 FAIL.
func TestEdgeBoundsLedgerRaiseBoundsLockWaitSpread(t *testing.T) {
	pool := edgeBoundsPool(t)
	ctx := context.Background()
	plantOpenRound(t, pool, "a", "RA")
	plantOpenRound(t, pool, "b", "RB")
	r := &lockSpreadRig{pool: pool}
	r.writer, r.writerPID = singleConnPool(t, pool)
	r.updater, r.updaterPID = singleConnPool(t, pool)
	r.holder = holdSessionRow(t, pool, "RB")

	var got lockSpreadResult
	for i := range lockSpreadAttempts {
		got = r.attempt(t)
		if got.raiseTook < lockSpreadRaiseWaitMin || got.raiseTook >= lockSpreadRaiseWaitMax {
			t.Errorf("시도 %d: Raise 호출 = %v(%v), want [%v, %v) — sb 대기를 끊은 것이 lock_timeout 500ms 가 아니다",
				i+1, got.raiseTook, got.raiseErr, lockSpreadRaiseWaitMin, lockSpreadRaiseWaitMax)
			break
		}
		if got.blocked {
			break
		}
		t.Logf("시도 %d: sa UPDATE 가 Raise 에 막힌 것을 보기 전에 Raise 가 끝났다(%v) — 판정 없이 다시", i+1, got.raiseErr)
	}
	r.release(t)

	if !got.blocked {
		t.Fatalf("%d 번 모두 sa UPDATE 가 Raise 에 막히지 않았다 — 시험이 기다림 번짐을 만들지 못했다", lockSpreadAttempts)
	}
	var pgErr *pgconn.PgError
	if !errors.As(got.raiseErr, &pgErr) || pgErr.Code != pgLockNotAvailable {
		t.Errorf("sb 가 잠긴 동안 Raise 오류 = %v, want 55P03(lock_timeout)", got.raiseErr)
	}
	if !got.doneWhileHeld {
		t.Errorf("Raise · sa UPDATE 가 C 가 sb 를 쥔 %v 안에 끝나지 않았다 — 번진 기다림을 끝낸 것이 상한이 아니다", lockSpreadBound)
	}
	if got.updateErr != nil || got.waited >= lockSpreadBound {
		t.Errorf("sa UPDATE = (%v 기다림, %v), want (%v 미만, nil)", got.waited, got.updateErr, lockSpreadBound)
	}
	if _, _, ok := edgeBoundsRow(t, pool, "a", "RA"); ok && got.raiseErr != nil {
		t.Error("끊긴 Raise 의 sa 원장 행이 남았다, want 문장째 되돌림")
	}
	want := lockSpreadFloors()
	if got, err := NewEdgeBoundsLedger(r.writer).Raise(ctx, want); err != nil || !slices.Equal(got, want) {
		t.Errorf("잠금이 풀린 뒤 다시 부른 Raise = (%v, %v), want (%v, nil)", got, err, want)
	}
}

// lockSpreadAttempts 는 기다림 번짐 시험의 시도 상한이다. 한 시도는 Raise 가 sb 에서 기다리는 500ms 안에 sa UPDATE 를
// 그 뒤에 세워야 해서 부하가 크면 창을 놓칠 수 있다 — 놓친 시도는 판정하지 않고 다시 한다. 0–700ms 무작위 지연을 넣은
// 모사에서 한 시도가 놓칠 확률은 30% 안팎이라, 열 번 모두 놓칠 확률은 백만 분의 몇이다.
const lockSpreadAttempts = 10

// lockSpreadBound 는 번진 기다림의 상한이다 — 위상 트랜잭션의 lock_timeout(5초 — setTxnLimitsSQL). 번진 기다림이 여기
// 닿으면 위상 갱신이 ErrTopologyContended 를 낸다(raiseTxnLimitsSQL 이 막는 결과). 정상이면 기다림은 Raise 의 남은
// lock_timeout(500ms 미만) 안이라 열 배 여유다.
const lockSpreadBound = 5 * time.Second

// lockSpreadRaiseWaitMin · lockSpreadRaiseWaitMax 는 기다림 번짐 시험에서 Raise 호출 한 번이 걸려야 하는 범위다. sb
// 대기를 끊는 것은 raiseTxnLimitsSQL 의 lock_timeout(500ms)이라 호출은 그보다 짧을 수 없다(하한은 타이머 오차 여유
// 50ms). 그 위로는 왕복 몇 번(BEGIN · set_config · 되돌림)만 더해진다 — 상한은 네 배 여유이고 lockSpreadBound 아래다.
// 값이 작아지면(1ms) 하한에, 커지면(4s) 상한에 걸린다.
const (
	lockSpreadRaiseWaitMin = 450 * time.Millisecond
	lockSpreadRaiseWaitMax = 2 * time.Second
)

// lockSpreadFloors 는 기다림 번짐 시험의 올림 입력이다 — sa 를 먼저, sb 를 뒤에 잠근다(바이트 순).
func lockSpreadFloors() []edgebounds.Floor {
	return floors(edgebounds.Floor{StreamID: "a", RoundID: "RA", Value: 5}, edgebounds.Floor{StreamID: "b", RoundID: "RB", Value: 5})
}

// lockSpreadRig 는 기다림 번짐 시험의 연결들이다 — Writer 연결 · sa UPDATE 연결 · sb 를 쥔 트랜잭션 C.
type lockSpreadRig struct {
	pool                  *pgxpool.Pool
	writer, updater       *pgxpool.Pool
	writerPID, updaterPID int32
	holder                pgx.Tx
	released              bool
}

// lockSpreadResult 는 시도 한 번의 관측이다. blocked 가 거짓이면 창을 놓친 시도라 나머지는 판정하지 않는다.
type lockSpreadResult struct {
	blocked       bool // sa UPDATE 가 Raise 에 막힌 것을 보았다
	raiseErr      error
	raiseTook     time.Duration // Raise 호출이 걸린 시간 — 창을 놓친 시도에서도 잰다
	updateErr     error
	waited        time.Duration // sa UPDATE 가 기다린 시간
	doneWhileHeld bool          // Raise · sa UPDATE 가 C 가 sb 를 쥔 동안 끝났다
}

// attempt 는 Raise([sa, sb]) 를 sb 에서 세우고 sa UPDATE 를 그 뒤에 세운 다음, C 를 쥔 채 둘이 끝나기를 기다린다.
func (r *lockSpreadRig) attempt(t *testing.T) lockSpreadResult {
	t.Helper()
	ctx := context.Background()
	var raiseErr error
	var raiseTook time.Duration
	raiseDone := make(chan struct{})
	go func() {
		defer close(raiseDone)
		start := time.Now()
		_, raiseErr = NewEdgeBoundsLedger(r.writer).Raise(ctx, lockSpreadFloors())
		raiseTook = time.Since(start)
	}()
	if !pollUntil(t, r.pool, raiseDone, lockWaitingSQL, r.writerPID) {
		return lockSpreadResult{raiseErr: raiseErr, raiseTook: raiseTook}
	}
	var waited time.Duration
	var updateErr error
	updateDone := make(chan struct{})
	go func() {
		defer close(updateDone)
		uctx, cancel := context.WithTimeout(ctx, 2*lockSpreadBound)
		defer cancel()
		start := time.Now()
		_, updateErr = r.updater.Exec(uctx, `UPDATE stream_sessions SET writer_fence = NULL WHERE session_id = 'RA'`)
		waited = time.Since(start)
	}()
	blocked := pollUntil(t, r.pool, raiseDone, blockedBySQL, r.updaterPID, r.writerPID)
	held := r.waitHeld(t, raiseDone)
	held = r.waitHeld(t, updateDone) && held
	return lockSpreadResult{blocked: blocked, raiseErr: raiseErr, raiseTook: raiseTook, updateErr: updateErr,
		waited: waited, doneWhileHeld: held}
}

// waitHeld 는 C 가 sb 를 쥔 채 done 이 닫히기를 lockSpreadBound 까지 기다린다 — 그 안에 닫히면 참이다. 넘으면 C 를
// 놓고(커밋) 마저 기다려 거짓이다. 이미 놓았으면 기다리기만 한다.
func (r *lockSpreadRig) waitHeld(t *testing.T, done <-chan struct{}) bool {
	t.Helper()
	select {
	case <-done:
		return !r.released
	case <-time.After(lockSpreadBound):
	}
	r.release(t)
	<-done
	return false
}

// release 는 C 를 커밋해 sb 를 놓는다(한 번만).
func (r *lockSpreadRig) release(t *testing.T) {
	t.Helper()
	if r.released {
		return
	}
	r.released = true
	if err := r.holder.Commit(context.Background()); err != nil {
		t.Fatalf("sb 잠금 풀기 실패: %v", err)
	}
}

// holdSessionRow 는 세션 행 하나를 FOR NO KEY UPDATE(위상 · fence UPDATE 가 거는 잠금)로 쥔 트랜잭션이다. 시험이
// 커밋하지 않고 끝나면 되돌린다.
func holdSessionRow(t *testing.T, pool *pgxpool.Pool, sessionID string) pgx.Tx {
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
	if _, err := tx.Exec(ctx, `SELECT 1 FROM stream_sessions WHERE session_id = $1 FOR NO KEY UPDATE`, sessionID); err != nil {
		t.Fatalf("세션 행 %s 잠금 실패: %v", sessionID, err)
	}
	return tx
}

// raisePasses 는 스트림의 k 번째 회차를 k 번째 문장에 싣는다 — 같은 (stream, round) 는 같은 문장(둘이면 21000 그대로) ·
// 1 보다 작은 값은 빠짐 · 입력 순서 유지 · 스트림마다 회차가 하나면 문장 하나.
func TestRaisePassesPutsOneRoundPerStreamInEachStatement(t *testing.T) {
	f := func(stream, round string, v int64) edgebounds.Floor {
		return edgebounds.Floor{StreamID: stream, RoundID: round, Value: v}
	}
	tests := []struct {
		name string
		give []edgebounds.Floor
		want []raiseArgs
	}{
		{name: "empty", give: nil, want: nil},
		{name: "all below one", give: floors(f("a", "R1", 0), f("b", "R1", -1)), want: nil},
		{
			name: "one round per stream",
			give: floors(f("a", "R1", 1), f("b", "R2", 2)),
			want: []raiseArgs{{streams: []string{"a", "b"}, rounds: []string{"R1", "R2"}, values: []int64{1, 2}}},
		},
		{
			name: "two rounds of one stream",
			give: floors(f("a", "R1", 1), f("a", "R2", 2), f("b", "R3", 0), f("b", "R4", 4), f("a", "R1", 5)),
			want: []raiseArgs{
				{streams: []string{"a", "b", "a"}, rounds: []string{"R1", "R4", "R1"}, values: []int64{1, 4, 5}},
				{streams: []string{"a"}, rounds: []string{"R2"}, values: []int64{2}},
			},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := raisePasses(tt.give)
			eq := func(a, b raiseArgs) bool {
				return slices.Equal(a.streams, b.streams) && slices.Equal(a.rounds, b.rounds) &&
					slices.Equal(a.values, b.values)
			}
			if !slices.EqualFunc(got, tt.want, eq) {
				t.Errorf("raisePasses(%v) = %+v, want %+v", tt.give, got, tt.want)
			}
		})
	}
}

// 문장이 여럿으로 나뉘어도 Raise 는 (stream, round) 순 한 목록을 돌려준다(입력은 뒤섞음).
func TestEdgeBoundsLedgerRaiseAcrossPassesReturnsSorted(t *testing.T) {
	pool := edgeBoundsPool(t)
	now := time.Now().UTC()
	// a 의 옛 회차 R1 · R2 는 끝난 채 닫힘을 기다리고 R3 이 live 다(스트림마다 live 하나).
	for i, round := range []string{"R1", "R2"} {
		started := now.Add(time.Duration(i-3) * time.Hour)
		plantRound(t, pool, roundFixture{id: "a-" + round, stream: "a", state: "ended", roundID: round,
			startedAt: started, endedAt: started.Add(time.Minute)})
	}
	plantRound(t, pool, roundFixture{id: "a-R3", stream: "a", state: "live", roundID: "R3", startedAt: now.Add(-time.Minute)})
	plantRound(t, pool, roundFixture{id: "b-R1", stream: "b", state: "live", roundID: "R1", startedAt: now.Add(-time.Minute)})
	l := NewEdgeBoundsLedger(pool)

	got, err := l.Raise(context.Background(), floors(
		edgebounds.Floor{StreamID: "a", RoundID: "R3", Value: 3},
		edgebounds.Floor{StreamID: "b", RoundID: "R1", Value: 4},
		edgebounds.Floor{StreamID: "a", RoundID: "R1", Value: 1},
		edgebounds.Floor{StreamID: "a", RoundID: "R2", Value: 2},
	))

	want := floors(
		edgebounds.Floor{StreamID: "a", RoundID: "R1", Value: 1},
		edgebounds.Floor{StreamID: "a", RoundID: "R2", Value: 2},
		edgebounds.Floor{StreamID: "a", RoundID: "R3", Value: 3},
		edgebounds.Floor{StreamID: "b", RoundID: "R1", Value: 4},
	)
	if err != nil || !slices.Equal(got, want) {
		t.Errorf("Raise = (%v, %v), want (%v, nil)", got, err, want)
	}
}

// waitDoneOrLockWait 는 done 이 오거나(참 — 오류가 있으면 시험 실패) 백엔드 pid 가 잠금 대기에 들 때까지(거짓)
// 기다린다(5초). 거짓이면 done 은 아직 비어 있다.
func waitDoneOrLockWait(t *testing.T, pool *pgxpool.Pool, pid int32, done <-chan error) bool {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for {
		select {
		case err := <-done:
			if err != nil {
				t.Fatalf("백엔드 %d 의 문장 오류 = %v", pid, err)
			}
			return true
		default:
		}
		var waiting bool
		if err := pool.QueryRow(context.Background(), lockWaitingSQL, pid).Scan(&waiting); err != nil {
			t.Fatalf("잠금 대기 조회 실패: %v", err)
		}
		if waiting {
			return false
		}
		if time.Now().After(deadline) {
			t.Fatalf("백엔드 %d 가 5초 안에 끝나지도 잠금 대기에 들지도 않았다", pid)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// lookupPhase 는 회차 (stream, round) 의 판정식 국면이다.
func lookupPhase(t *testing.T, pool *pgxpool.Pool, stream, round string) PlaybackPhase {
	t.Helper()
	st, err := LookupRoundPlayback(context.Background(), pool, stream, round)
	if err != nil {
		t.Fatalf("판정식 조회 실패: %v", err)
	}
	return st.Phase
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
		if err := pool.QueryRow(context.Background(), lockWaitingSQL, pid).Scan(&waiting); err != nil {
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

// lockWaitingSQL 은 백엔드 $1 이 잠금을 기다리는 중인가다.
const lockWaitingSQL = `SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_stat_activity WHERE pid = $1 AND wait_event_type = 'Lock')`

// blockedBySQL 은 백엔드 $1 이 백엔드 $2 에 막혀 잠금을 기다리는 중인가다.
const blockedBySQL = `SELECT $2::int = ANY (pg_catalog.pg_blocking_pids($1::int))`

// pollUntil 은 query(참 · 거짓 한 값)가 참이 될 때까지 5ms 마다 묻는다 — 참이면 참이고, 그 전에 done 이 닫히면
// 거짓이다(기다리던 일이 끝나 더 볼 것이 없다). 10초 안에 어느 쪽도 아니면 실패한다.
func pollUntil(t *testing.T, pool *pgxpool.Pool, done <-chan struct{}, query string, args ...any) bool {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for {
		var ok bool
		if err := pool.QueryRow(context.Background(), query, args...).Scan(&ok); err != nil {
			t.Fatalf("잠금 대기 조회 실패: %v", err)
		}
		if ok {
			return true
		}
		select {
		case <-done:
			return false
		default:
		}
		if time.Now().After(deadline) {
			t.Fatal("잠금 대기 조회가 10초 안에 참도 끝남도 보지 못했다")
		}
		time.Sleep(5 * time.Millisecond)
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
		"raiseTxnLimitsSQL": raiseTxnLimitsSQL, "raiseSQL": raiseSQL, "closedUnreleasedSQL": closedUnreleasedSQL,
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

// replaceEdgeBoundsTable 은 원장 표를 ddl 로 갈아 끼우는 조작이다 — 소유자 · media_indexer 넷은 운영 SQL 과 같게
// 되돌려, 40 이 표 모양 어긋남만 식별자로 내는지 본다.
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
		// NOT VALID 는 40 의 두 조건(k.convalidated · 정의 문자열 — pg_get_constraintdef 가 끝에 NOT VALID 를 붙인다)이
		// 함께 잡는다. 한쪽만 지워도 이 사례는 잡힌다.
		{
			name: "floor check not validated",
			tamper: "ALTER TABLE stream_edge_bounds DROP CONSTRAINT stream_edge_bounds_dvr_floor_check;\n" +
				"ALTER TABLE stream_edge_bounds ADD CONSTRAINT stream_edge_bounds_dvr_floor_check\n" +
				"    CHECK (dvr_floor >= 1) NOT VALID",
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
