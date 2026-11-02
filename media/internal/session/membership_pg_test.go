package session

// 소속 잠금 · 개시 재판정 · 15.2 개시 규칙의 실 PG 시험(POK-196 M6-3a 커밋 ① — 설계 M6 3.3 · 15.2 · 9절 D9 · D49 ·
// D54 · D60 · D69 · D71 · D73).
//
// 배리어는 훅 대신 행 잠금으로 만든다: 경쟁 쓰기가 소속 행을 먼저 잡고(커밋 전) 결정을 부르면, 결정의 무잠금
// 판정은 옛 판을 보고 소속 잠금에서 기다린다. 경쟁 쓰기가 커밋하면 잠근 읽기는 새 판을 본다(READ COMMITTED ·
// PG 17 13.2.1). 그래서 「판정 직후 멈춤 → 경쟁 커밋 → 재개」가 제품 코드에 시험용 갈래 없이 선다.

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// ledgerRow 는 회차 칸까지 심는 세션 픽스처다. 빈 문자열 · 영값은 NULL 이다.
type ledgerRow struct {
	id, streamID, state, endReason, vodState, roundID, inherits string
	startedAt, stopMarkedAt, roundClosedAt                      time.Time
	targetDuration                                              int32
}

func nullText(s string) any {
	if s == "" {
		return nil
	}
	return s
}

func nullTime(t time.Time) any {
	if t.IsZero() {
		return nil
	}
	return t
}

func seedLedger(t *testing.T, pool *pgxpool.Pool, r ledgerRow) {
	t.Helper()
	if r.targetDuration == 0 {
		r.targetDuration = 6
	}
	if r.vodState == "" {
		r.vodState = "none"
	}
	var endingAt any
	if r.state != "live" {
		endingAt = r.startedAt.Add(time.Minute)
	}
	if _, err := pool.Exec(context.Background(), `
INSERT INTO stream_sessions
    (session_id, stream_id, started_at, state, end_reason, ending_at, vod_state, round_id, round_closed_at,
     stop_marked_at, target_duration, inherits_session)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)`,
		r.id, r.streamID, r.startedAt, r.state, nullText(r.endReason), endingAt, r.vodState, nullText(r.roundID),
		nullTime(r.roundClosedAt), nullTime(r.stopMarkedAt), r.targetDuration, nullText(r.inherits)); err != nil {
		t.Fatalf("세션 픽스처 실패 %s: %v", r.id, err)
	}
}

// seedSegment 는 세션에 귀속된 조각 한 행이다 — 직전 회차의 마지막 조각 끝(계승 · 합류 기산점)을 만든다.
func seedSegment(t *testing.T, pool *pgxpool.Pool, streamID, sessionID string, seq int64, start time.Time) {
	t.Helper()
	if _, err := pool.Exec(context.Background(), `
INSERT INTO stream_segments
    (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path, upload_state, bytes, session_id)
VALUES ($1, $2, 0, $3, 4000, $4, $5, 'uploaded', 1000, $6)`,
		streamID, seq, start, fmt.Sprintf("k-%s-%d", streamID, seq),
		fmt.Sprintf("/recordings/%s/%d.mp4", streamID, seq), sessionID); err != nil {
		t.Fatalf("조각 픽스처 실패 %s/%d: %v", streamID, seq, err)
	}
}

// openOne 은 결정 → 개시를 한 트랜잭션에서 돌려 새 세션 id 를 돌려준다(개시 갈래가 아니면 "").
func openOne(t *testing.T, pool *pgxpool.Pool, r *Registry, in Input) string {
	t.Helper()
	ctx := context.Background()
	var id string
	withTx(t, pool, func(tx pgx.Tx) {
		d, err := r.Decide(ctx, tx, in, in.StartWallUTC)
		if err != nil {
			t.Fatalf("결정 실패: %v", err)
		}
		if d.Outcome != OutcomeOpen && d.Outcome != OutcomeOpenFresh {
			return
		}
		if id, err = r.Open(ctx, tx, d, in.StartWallUTC); err != nil {
			t.Fatalf("개시 실패: %v", err)
		}
	})
	return id
}

type roundCols struct {
	roundID      *string
	stopMarkedAt *time.Time
	inherits     *string
	state        string
	endReason    *string
}

func roundOf(t *testing.T, pool *pgxpool.Pool, id string) roundCols {
	t.Helper()
	var c roundCols
	if err := pool.QueryRow(context.Background(),
		`SELECT round_id, stop_marked_at, inherits_session, state, end_reason FROM stream_sessions WHERE session_id = $1`, id).
		Scan(&c.roundID, &c.stopMarkedAt, &c.inherits, &c.state, &c.endReason); err != nil {
		t.Fatalf("세션 조회 실패 %s: %v", id, err)
	}
	return c
}

func strOf(p *string) string {
	if p == nil {
		return "<nil>"
	}
	return *p
}

// holdRow 는 다른 연결에서 트랜잭션을 열어 세션 행을 FOR NO KEY UPDATE 로 잡고 stmt 를 실행한 채 둔다(커밋 전).
// 돌려준 함수가 커밋한다. 경쟁 쓰기(4D 분리 · 표식 · 회차 닫힘)의 흉내다.
func holdRow(t *testing.T, pool *pgxpool.Pool, sessionID, stmt string, args ...any) (commit func()) {
	t.Helper()
	ctx := context.Background()
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("경쟁 트랜잭션 시작 실패: %v", err)
	}
	if _, err := tx.Exec(ctx, `SELECT 1 FROM stream_sessions WHERE session_id = $1 FOR NO KEY UPDATE`, sessionID); err != nil {
		t.Fatalf("경쟁 행 잠금 실패: %v", err)
	}
	if stmt != "" {
		if _, err := tx.Exec(ctx, stmt, args...); err != nil {
			t.Fatalf("경쟁 쓰기 실패: %v", err)
		}
	}
	done := false
	t.Cleanup(func() {
		if !done {
			_ = tx.Rollback(ctx)
		}
	})
	return func() {
		done = true
		if err := tx.Commit(ctx); err != nil {
			t.Fatalf("경쟁 커밋 실패: %v", err)
		}
	}
}

// waitLockWaiter 는 이 DB 에서 행 잠금을 기다리는 백엔드가 생길 때까지 기다린다(배리어 도착 확인).
func waitLockWaiter(t *testing.T, pool *pgxpool.Pool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		var n int
		if err := pool.QueryRow(context.Background(), `
SELECT pg_catalog.count(*) FROM pg_catalog.pg_stat_activity
 WHERE datname = pg_catalog.current_database() AND wait_event_type = 'Lock'`).Scan(&n); err != nil {
			t.Fatalf("잠금 대기 조회 실패: %v", err)
		}
		if n > 0 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("5초 안에 잠금 대기가 생기지 않았다 — 결정이 소속 행을 잠그지 않는다")
}

// decideOpenAsync 는 결정 → 개시를 고루틴에서 돌린다. 결과 채널은 새 세션 id 또는 오류다.
func decideOpenAsync(pool *pgxpool.Pool, r *Registry, in Input) <-chan error {
	out := make(chan error, 1)
	go func() {
		ctx := context.Background()
		tx, err := pool.Begin(ctx)
		if err != nil {
			out <- err
			return
		}
		defer func() { _ = tx.Rollback(ctx) }()
		d, err := r.Decide(ctx, tx, in, in.StartWallUTC)
		if err != nil {
			out <- err
			return
		}
		if d.Outcome == OutcomeOpen || d.Outcome == OutcomeOpenFresh {
			if _, err := r.Open(ctx, tx, d, in.StartWallUTC); err != nil {
				out <- err
				return
			}
		}
		out <- tx.Commit(ctx)
	}()
	return out
}

func waitResult(t *testing.T, ch <-chan error) {
	t.Helper()
	select {
	case err := <-ch:
		if err != nil {
			t.Fatalf("결정 · 개시 실패: %v", err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("결정 · 개시가 10초 안에 끝나지 않았다")
	}
}

func newestSession(t *testing.T, pool *pgxpool.Pool, streamID string) string {
	t.Helper()
	var id string
	if err := pool.QueryRow(context.Background(),
		`SELECT session_id FROM stream_sessions WHERE stream_id = $1 ORDER BY started_at DESC, session_id DESC LIMIT 1`,
		streamID).Scan(&id); err != nil {
		t.Fatalf("최신 세션 조회 실패: %v", err)
	}
	return id
}

// 15.2 표 4행 — TD 분할의 새 세션은 끝낸 live 의 회차 · 표식을 그 UPDATE 의 RETURNING 으로 물려받는다. 끝낸 live
// 의 회차가 NULL(M6 전)이면 3행 — 자기 회차다. 첫 개시(7행)는 자기 회차다. 잡는 결함: 회차를 쓰지 않으면
// round_id NULL 세션이 생겨 계약9 이벤트를 영영 못 낸다 / 분할이 새 회차를 열면 회차 하나가 TD 분할마다 쪼개진다.
func TestOpenWritesRoundIDByOpeningRule(t *testing.T) {
	pool := newTestPool(t)
	r, _ := newRoundRegistry()
	first := openOne(t, pool, r, Input{StreamID: "demo", Seq: 1, StartWallUTC: wall, DurationMS: 4000, Op: OpenOrCurrent})
	if got := roundOf(t, pool, first); strOf(got.roundID) != first {
		t.Fatalf("첫 개시 round_id = %s, want 자기 id %s", strOf(got.roundID), first)
	}
	split := openOne(t, pool, r, Input{StreamID: "demo", Seq: 2, StartWallUTC: wall.Add(4 * time.Second), DurationMS: 9000, Op: OpenOrCurrent})
	if got := roundOf(t, pool, split); split == "" || strOf(got.roundID) != first || got.stopMarkedAt != nil {
		t.Errorf("TD 분할 (id, round_id, 표식) = (%q, %s, %v), want (새 id, %s, NULL)", split, strOf(got.roundID), got.stopMarkedAt, first)
	}

	seedLedger(t, pool, ledgerRow{id: "S-legacy", streamID: "old", state: "live", startedAt: wall.Add(-time.Hour)})
	legacySplit := openOne(t, pool, r, Input{StreamID: "old", Seq: 5, StartWallUTC: wall, DurationMS: 9000, Op: OpenOrCurrent})
	if got := roundOf(t, pool, legacySplit); strOf(got.roundID) != legacySplit {
		t.Errorf("NULL 회차 live 의 TD 분할 round_id = %s, want 자기 id %s(15.2 표 3행)", strOf(got.roundID), legacySplit)
	}
}

// D49(M6-3a 몫) — init 불일치 재접속(15.2 표 6행)과 TD 분할(4행)을 거쳐도 회차는 하나다. 변이 = TD 분할이 자기 회차를
// 쓰면 회차가 둘로 쪼개져 FAIL. (started 1 · ended 1 outbox 단언은 M6-8 몫이다.)
func TestInitMismatchReconnectAndTDSplitStayInOneRound(t *testing.T) {
	pool := newTestPool(t)
	r, _ := newRoundRegistry()
	ctx := context.Background()
	s1 := openOne(t, pool, r, Input{StreamID: "demo", Seq: 1, StartWallUTC: wall, DurationMS: 4000, Op: OpenOrCurrent})
	seedSegment(t, pool, "demo", s1, 1, wall)
	if _, err := pool.Exec(ctx, `UPDATE stream_sessions SET state='ending', ending_at=now(), end_reason='init_mismatch' WHERE session_id=$1`, s1); err != nil {
		t.Fatalf("init 불일치 종료 흉내 실패: %v", err)
	}
	s2 := openOne(t, pool, r, Input{StreamID: "demo", Seq: 2, StartWallUTC: wall.Add(6 * time.Second), DurationMS: 4000, Op: OpenOrCurrent})
	s3 := openOne(t, pool, r, Input{StreamID: "demo", Seq: 3, StartWallUTC: wall.Add(10 * time.Second), DurationMS: 9000, Op: OpenOrCurrent})

	for _, id := range []string{s2, s3} {
		got := roundOf(t, pool, id)
		if strOf(got.roundID) != s1 {
			t.Errorf("세션 %s round_id = %s, want %s(회차 하나)", id, strOf(got.roundID), s1)
		}
	}
	if got := roundOf(t, pool, s2); got.inherits != nil {
		t.Errorf("init 불일치 재접속이 계승했다(%s) — 6행은 합류만 한다", *got.inherits)
	}
}

// D60 — 표식 뒤 재송출. (가) 표식 → 3초 뒤 init 불일치 재송출(6행 합류 · 표식 승계) → 표식 + 12초 조각은 승계한
// 표식으로 1행 분할 = 새 회차. (나) 표식 → 업로드 단계 init 불일치로 live ending → 표식 + 9초 첫 조각은 2행 = 새 회차 ·
// 비계승. 변이 = 승계 제거 → (가) FAIL · 2행 제거 → (나) 가 합류로 FAIL.
func TestStopMarkSurvivesReconnectAndSplitsAfterSlack(t *testing.T) {
	ctx := context.Background()
	mark := wall
	setup := func(t *testing.T) (*pgxpool.Pool, *Registry, string) {
		pool := newTestPool(t)
		r, _ := newRoundRegistry()
		s1 := openOne(t, pool, r, Input{StreamID: "demo", Seq: 1, StartWallUTC: mark.Add(-time.Minute), DurationMS: 4000, Op: OpenOrCurrent})
		seedSegment(t, pool, "demo", s1, 1, mark.Add(-4*time.Second))
		if _, err := pool.Exec(ctx, `UPDATE stream_sessions SET stop_marked_at=$2 WHERE session_id=$1`, s1, mark); err != nil {
			t.Fatalf("표식 흉내 실패: %v", err)
		}
		if _, err := pool.Exec(ctx, `UPDATE stream_sessions SET state='ending', ending_at=now(), end_reason='init_mismatch' WHERE session_id=$1`, s1); err != nil {
			t.Fatalf("init 불일치 종료 흉내 실패: %v", err)
		}
		return pool, r, s1
	}

	t.Run("가_승계한_표식으로_분할", func(t *testing.T) {
		pool, r, s1 := setup(t)
		s2 := openOne(t, pool, r, Input{StreamID: "demo", Seq: 2, StartWallUTC: mark.Add(3 * time.Second), DurationMS: 4000, Op: OpenOrCurrent})
		if got := roundOf(t, pool, s2); strOf(got.roundID) != s1 || got.stopMarkedAt == nil || !got.stopMarkedAt.Equal(mark) {
			t.Fatalf("재송출 세션 (round_id, 표식) = (%s, %v), want (%s, %v)", strOf(got.roundID), got.stopMarkedAt, s1, mark)
		}
		s3 := openOne(t, pool, r, Input{StreamID: "demo", Seq: 3, StartWallUTC: mark.Add(12 * time.Second), DurationMS: 4000, Op: OpenOrCurrent})
		if s3 == "" {
			t.Fatal("표식 + 12초 조각이 분할하지 않고 옛 회차에 붙었다")
		}
		if got := roundOf(t, pool, s3); strOf(got.roundID) != s3 || got.stopMarkedAt != nil {
			t.Errorf("분할 세션 (round_id, 표식) = (%s, %v), want (%s, NULL)", strOf(got.roundID), got.stopMarkedAt, s3)
		}
		if got := roundOf(t, pool, s2); got.state != "ending" || strOf(got.endReason) != endReasonIntentional {
			t.Errorf("끝난 재송출 세션 (state, 사유) = (%s, %s), want (ending, intentional)", got.state, strOf(got.endReason))
		}
	})

	t.Run("나_표식_개시는_새_회차", func(t *testing.T) {
		pool, r, s1 := setup(t)
		s2 := openOne(t, pool, r, Input{StreamID: "demo", Seq: 2, StartWallUTC: mark.Add(9 * time.Second), DurationMS: 4000, Op: OpenOrCurrent})
		got := roundOf(t, pool, s2)
		if strOf(got.roundID) != s2 || got.inherits != nil || got.stopMarkedAt != nil {
			t.Errorf("표식 개시 (round_id, 계승, 표식) = (%s, %s, %v), want (%s, NULL, NULL) — %s 에 합류하면 안 된다",
				strOf(got.roundID), strOf(got.inherits), got.stopMarkedAt, s2, s1)
		}
	})
}

// D54(기록) — 표식 뒤 8초 안에 시작한 조각은 표식 있는 live 에 붙는다. 그 조각들의 시작은 표식 + 8초 미만이라 옛 회차
// 끝에 남는 새 송출은 8초 이하다(계약4 3-2 「남을 수 있다」).
func TestSegmentsWithinStopSlackStayOnMarkedLive(t *testing.T) {
	pool := newTestPool(t)
	r, _ := newRoundRegistry()
	mark := wall
	seedLedger(t, pool, ledgerRow{id: "S-live", streamID: "demo", state: "live", startedAt: mark.Add(-time.Hour),
		roundID: "S-live", stopMarkedAt: mark})
	ctx := context.Background()
	for i, off := range []time.Duration{2 * time.Second, 6 * time.Second, 8*time.Second - time.Millisecond} {
		withTx(t, pool, func(tx pgx.Tx) {
			d, err := r.Decide(ctx, tx, Input{StreamID: "demo", Seq: int64(i + 1), StartWallUTC: mark.Add(off), DurationMS: 2000, Op: OpenOrCurrent}, mark.Add(off))
			if err != nil {
				t.Fatalf("결정 실패: %v", err)
			}
			if d.Outcome != OutcomeCurrent || d.SessionID != "S-live" {
				t.Errorf("표식 + %v 조각 = (%v, %q), want (current, S-live) — 남는 양 8초 이하", off, d.Outcome, d.SessionID)
			}
		})
	}
}

// D9 — 회차 닫힘은 DB 가 쓴 round_closed_at 으로만 본다(결정자는 시간 창으로 닫힘을 재지 않는다). 그래서 닫힌 뒤 늦게
// 온 백로그 조각은 재접속 창 안이어도 새 회차를 연다(한계 — README 한계 행은 M6-3b). 닫힌 사슬(vod_state ≠ none)은
// 계승하지 않고 신호를 남긴다. 변이 = 6행의 닫힘 조건 제거 → 닫힌 회차에 합류해 FAIL · 계승의 vod_state 조건 제거 → FAIL.
func TestClosedRoundOrChainIsNotJoinedOrInherited(t *testing.T) {
	ctx := context.Background()
	for _, c := range []struct {
		name, vodState string
		closed         bool
		wantJoin       bool
		wantSignal     string
	}{
		{"닫힌_회차", "none", true, false, declineRoundClosed},
		{"닫힌_사슬_열린_회차", "freezing", false, true, declineChainClosed},
	} {
		t.Run(c.name, func(t *testing.T) {
			pool := newTestPool(t)
			r, sig := newRoundRegistry()
			row := ledgerRow{id: "S-prev", streamID: "demo", state: "ending", endReason: "offline", vodState: c.vodState,
				roundID: "S-prev", startedAt: wall.Add(-time.Hour)}
			if c.closed {
				row.roundClosedAt = wall
			}
			seedLedger(t, pool, row)
			seedSegment(t, pool, "demo", "S-prev", 1, wall.Add(-time.Minute))
			id := openOne(t, pool, r, Input{StreamID: "demo", Seq: 2, StartWallUTC: wall.Add(time.Minute), DurationMS: 4000, Op: OpenOrCurrent})
			got := roundOf(t, pool, id)
			if joined := strOf(got.roundID) == "S-prev"; joined != c.wantJoin {
				t.Errorf("합류 = %v(round_id %s), want %v", joined, strOf(got.roundID), c.wantJoin)
			}
			if got.inherits != nil {
				t.Errorf("계승 = %s, want NULL", *got.inherits)
			}
			if reason, _ := sig.attrs("inherit_declined")["reason"].(string); !sig.fired("inherit_declined") || reason != c.wantSignal {
				t.Errorf("inherit_declined = (%v, %q), want (WARN, %q)", sig.fired("inherit_declined"), reason, c.wantSignal)
			}
			_ = ctx
		})
	}
}

// D69 (가) — TD 분할 판정 직후 경쟁 4D 가 live X 를 새 회차 B 로 옮기고 커밋 → 개시 재개 → C 는 B 회차.
func TestOpenFreshAfterConcurrentRoundMoveFollowsNewRound(t *testing.T) {
	pool := newTestPool(t)
	r, _ := newRoundRegistry()
	seedLedger(t, pool, ledgerRow{id: "S-X", streamID: "demo", state: "live", roundID: "S-R", startedAt: wall.Add(-time.Hour)})
	commit := holdRow(t, pool, "S-X", `UPDATE stream_sessions SET round_id = 'S-X' WHERE session_id = 'S-X'`)

	res := decideOpenAsync(pool, r, Input{StreamID: "demo", Seq: 2, StartWallUTC: wall, DurationMS: 9000, Op: OpenOrCurrent})
	waitLockWaiter(t, pool)
	commit()
	waitResult(t, res)

	c := newestSession(t, pool, "demo")
	if got := roundOf(t, pool, c); c == "S-X" || strOf(got.roundID) != "S-X" {
		t.Errorf("새 세션 %s round_id = %s, want S-X(옮긴 뒤 회차)", c, strOf(got.roundID))
	}
}

// D69 (가) 의 live 없는 짝 — 합류(6행) 판정 직후 경쟁 회차 닫힘이 prev 의 회차를 닫고 커밋 → 개시는 잠근 prev 로 다시
// 판정해 닫힌 회차에 붙지 않는다. 변이 = 개시 재판정 제거(무잠금 prev 로 합류) → C 가 닫힌 R 에 붙어 FAIL.
func TestOpenAfterConcurrentRoundCloseDoesNotJoinClosedRound(t *testing.T) {
	pool := newTestPool(t)
	r, _ := newRoundRegistry()
	seedLedger(t, pool, ledgerRow{id: "S-P", streamID: "demo", state: "ending", endReason: "offline", vodState: "freezing",
		roundID: "S-R", startedAt: wall.Add(-time.Hour)})
	seedSegment(t, pool, "demo", "S-P", 1, wall.Add(-time.Minute))
	commit := holdRow(t, pool, "S-P", `UPDATE stream_sessions SET round_closed_at = now() WHERE session_id = 'S-P'`)

	res := decideOpenAsync(pool, r, Input{StreamID: "demo", Seq: 2, StartWallUTC: wall, DurationMS: 4000, Op: OpenOrCurrent})
	waitLockWaiter(t, pool)
	commit()
	waitResult(t, res)

	c := newestSession(t, pool, "demo")
	if got := roundOf(t, pool, c); c == "S-P" || strOf(got.roundID) != c {
		t.Errorf("새 세션 %s round_id = %s, want 자기 회차(닫힌 S-R 에 붙으면 안 된다)", c, strOf(got.roundID))
	}
}

// D69 (다) — 경쟁 4D marked 가 live X 에 표식을 쓰는 동안 TD 분할 → C 의 표식 = 그 표식(RETURNING 승계). 변이 = 무잠금
// 판정 값을 승계 → NULL 로 FAIL.
func TestOpenFreshInheritsConcurrentStopMark(t *testing.T) {
	pool := newTestPool(t)
	r, _ := newRoundRegistry()
	mark := wall.Add(-2 * time.Second)
	seedLedger(t, pool, ledgerRow{id: "S-X", streamID: "demo", state: "live", roundID: "S-X", startedAt: wall.Add(-time.Hour)})
	commit := holdRow(t, pool, "S-X", `UPDATE stream_sessions SET stop_marked_at = $1 WHERE session_id = 'S-X'`, mark)

	res := decideOpenAsync(pool, r, Input{StreamID: "demo", Seq: 2, StartWallUTC: wall, DurationMS: 9000, Op: OpenOrCurrent})
	waitLockWaiter(t, pool)
	commit()
	waitResult(t, res)

	c := newestSession(t, pool, "demo")
	got := roundOf(t, pool, c)
	if c == "S-X" || strOf(got.roundID) != "S-X" || got.stopMarkedAt == nil || !got.stopMarkedAt.Equal(mark) {
		t.Errorf("새 세션 %s (round_id, 표식) = (%s, %v), want (S-X, %v)", c, strOf(got.roundID), got.stopMarkedAt, mark)
	}
}

// D71(개시 쪽) — 소속 잠금은 FOR NO KEY UPDATE 다. 개시가 live 를 잡은 동안 그 세션의 「계속」 조각 INSERT(FK 검사 =
// FOR KEY SHARE)는 기다리지 않는다. 변이 = 소속 잠금을 FOR UPDATE 로 → INSERT 가 막혀 시한 FAIL.
func TestMembershipLockDoesNotBlockSegmentInsert(t *testing.T) {
	pool := newTestPool(t)
	r, _ := newRoundRegistry()
	ctx := context.Background()
	seedLedger(t, pool, ledgerRow{id: "S-X", streamID: "demo", state: "live", roundID: "S-X", startedAt: wall.Add(-time.Hour)})

	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("트랜잭션 시작 실패: %v", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	d, err := r.Decide(ctx, tx, Input{StreamID: "demo", Seq: 9, StartWallUTC: wall, DurationMS: 9000, Op: OpenOrCurrent}, wall)
	if err != nil || d.Outcome != OutcomeOpenFresh {
		t.Fatalf("결정 = (%v, %v), want (open_fresh, nil)", d.Outcome, err)
	}

	ictx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	started := time.Now()
	_, err = pool.Exec(ictx, `
INSERT INTO stream_segments
    (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path, upload_state, bytes, session_id)
VALUES ('demo', 1, 0, $1, 4000, 'k-1', '/recordings/demo/1.mp4', 'uploaded', 1000, 'S-X')`, wall.Add(-time.Second))
	if err != nil {
		t.Fatalf("소속 잠금 중 조각 INSERT 실패(%v 뒤): %v — FK 잠금이 소속 잠금에 막혔다", time.Since(started), err)
	}
}

// D73(개시 쪽) — 소속 행 선택 규칙은 「live 가 있으면 live, 없으면 started_at 최대」다. A 의 늦은 조각이 연 live L 이
// 최신 ending B 보다 이른 started_at 이어도 개시는 L 을 잠근다. 변이 = started_at 최대만 → B 를 잠가 L 을 쥔 경쟁에
// 막히지 않아 FAIL.
func TestOpenLocksLiveRowEvenWhenOlderThanLatestSession(t *testing.T) {
	pool := newTestPool(t)
	r, _ := newRoundRegistry()
	ctx := context.Background()
	seedLedger(t, pool, ledgerRow{id: "S-B", streamID: "demo", state: "ending", endReason: "offline", roundID: "S-B", startedAt: wall.Add(-10 * time.Minute)})
	seedLedger(t, pool, ledgerRow{id: "S-L", streamID: "demo", state: "live", roundID: "S-L", startedAt: wall.Add(-20 * time.Minute)})
	commit := holdRow(t, pool, "S-L", "")

	decided := make(chan error, 1)
	go func() {
		tx, err := pool.Begin(ctx)
		if err != nil {
			decided <- err
			return
		}
		defer func() { _ = tx.Rollback(ctx) }()
		_, err = r.Decide(ctx, tx, Input{StreamID: "demo", Seq: 9, StartWallUTC: wall, DurationMS: 9000, Op: OpenOrCurrent}, wall)
		decided <- err
	}()
	select {
	case err := <-decided:
		t.Fatalf("결정이 L 의 잠금을 기다리지 않고 돌아왔다(err %v) — 소속 행이 live L 이 아니다", err)
	case <-time.After(300 * time.Millisecond):
	}
	commit()
	select {
	case err := <-decided:
		if err != nil {
			t.Fatalf("결정 실패: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("L 잠금이 풀린 뒤에도 결정이 끝나지 않았다")
	}
}

// 끝낼 live 가 개시 사이에 이미 live 가 아니면(0행) 그 개시는 되돌리고 ErrLiveLost 로 돌아간다(3.3 — 다음 조각이 다시
// 판정한다). 남이 적은 종료 사유는 덮지 않는다.
func TestOpenFreshFailsWhenEndingRowIsNoLongerLive(t *testing.T) {
	pool := newTestPool(t)
	r, _ := newRoundRegistry()
	ctx := context.Background()
	seedLedger(t, pool, ledgerRow{id: "S-X", streamID: "demo", state: "live", roundID: "S-X", startedAt: wall.Add(-time.Hour)})
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("트랜잭션 시작 실패: %v", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	d, err := r.Decide(ctx, tx, Input{StreamID: "demo", Seq: 9, StartWallUTC: wall, DurationMS: 9000, Op: OpenOrCurrent}, wall)
	if err != nil || d.Outcome != OutcomeOpenFresh {
		t.Fatalf("결정 = (%v, %v)", d.Outcome, err)
	}
	if _, err := tx.Exec(ctx, `UPDATE stream_sessions SET state='ending', ending_at=now(), end_reason='offline' WHERE session_id='S-X'`); err != nil {
		t.Fatalf("종료 흉내 실패: %v", err)
	}
	if _, err := r.Open(ctx, tx, d, wall); !errors.Is(err, ErrLiveLost) {
		t.Fatalf("Open 오류 = %v, want ErrLiveLost", err)
	}
	var reason string
	if err := tx.QueryRow(ctx, `SELECT end_reason FROM stream_sessions WHERE session_id='S-X'`).Scan(&reason); err != nil || reason != "offline" {
		t.Errorf("end_reason = (%q, %v), want offline — 남이 적은 종료 사유를 덮지 않는다", reason, err)
	}
}
