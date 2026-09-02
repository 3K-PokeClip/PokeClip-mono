package session

import (
	"context"
	"errors"
	"fmt"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// TestMain 은 릴리스 게이트용 스위치다(index/testdb_test.go 와 같은 이디엄).
//
// 이 파일의 케이스는 PG_DSN 이 없으면 전부 skip 되는데 skip 은 성공으로 집계된다 —
// DB 를 안 띄운 실행이 "녹색"으로 보이고 세션 SQL 은 한 줄도 검증되지 않는다.
func TestMain(m *testing.M) {
	if os.Getenv("REQUIRE_PG") == "1" && os.Getenv("PG_DSN") == "" {
		fmt.Fprintln(os.Stderr,
			"REQUIRE_PG=1 인데 PG_DSN 이 비어 있다 — 세션 SQL 통합 케이스가 전량 skip 된다. 게이트 실패.")
		os.Exit(1)
	}
	os.Exit(m.Run())
}

// newTestPool 은 이 패키지 전용 테스트 DB(…_session)에 붙은 풀이다.
// index 를 임포트하는 자리는 **테스트뿐**이다 — 비테스트 코드의 session → index 임포트는 0 이다
// (단계 3 에서 index 가 자기 인터페이스를 선언하고 main 이 조립한다).
func newTestPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	return pgtest.Pool(t, "session", index.EnsureSchema, resetTables)
}

func resetTables(ctx context.Context, pool *pgxpool.Pool) error {
	_, err := pool.Exec(ctx,
		"TRUNCATE stream_segments, stream_cutoffs, stream_published_gaps, stream_sessions")
	return err
}

// sessionRow 는 픽스처가 직접 심는 세션 행이다(레지스트리를 거치지 않는다).
type sessionRow struct {
	id                string
	streamID          string
	startedAt         time.Time
	state             string
	targetDuration    int32
	discontinuityBase int64
}

func seedSession(t *testing.T, pool *pgxpool.Pool, row sessionRow) {
	t.Helper()
	_, err := pool.Exec(context.Background(), `
		INSERT INTO stream_sessions
			(session_id, stream_id, started_at, state, target_duration, discontinuity_base)
		VALUES ($1, $2, $3, $4, $5, $6)`,
		row.id, row.streamID, row.startedAt, row.state, row.targetDuration, row.discontinuityBase)
	if err != nil {
		t.Fatalf("세션 픽스처 심기 실패 %q: %v", row.id, err)
	}
}

// withTx 는 트랜잭션 하나를 열어 fn 을 돌리고 커밋한다. 레지스트리는 tx 를 만들지 않고 받는다.
func withTx(t *testing.T, pool *pgxpool.Pool, fn func(tx pgx.Tx)) {
	t.Helper()
	ctx := context.Background()
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("트랜잭션 시작 실패: %v", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	fn(tx)
	if err := tx.Commit(ctx); err != nil {
		t.Fatalf("트랜잭션 커밋 실패: %v", err)
	}
}

func TestDecideReadsCurrentLiveSessionFromTx(t *testing.T) {
	pool := newTestPool(t)
	seedSession(t, pool, sessionRow{
		id: "S-live", streamID: "demo", startedAt: wall, state: "live", targetDuration: 6,
	})
	r, _ := newTestRegistry(time.Second, 30*time.Second)
	in := Input{
		StreamID:     "demo",
		Seq:          5,
		StartWallUTC: wall.Add(time.Minute),
		DurationMS:   4000,
		Op:           CurrentOnly,
	}

	withTx(t, pool, func(tx pgx.Tx) {
		d, err := r.Decide(context.Background(), tx, in, wall.Add(time.Minute))
		if err != nil {
			t.Fatalf("결정 실패: %v", err)
		}
		if d.Outcome != OutcomeCurrent || d.SessionID != "S-live" {
			t.Errorf("갈래 = %v · 귀속 = %q, 기대 = %v · %q",
				d.Outcome, d.SessionID, OutcomeCurrent, "S-live")
		}
	})
}

// DB 오류는 **정책 거부와 다르다**(계획 4.3 ⒉) — 값(비귀속)으로 접지 않고 error 로 올린다.
// 접어 버리면 "세션이 없어서 안 붙였다"와 "DB 가 답을 못 줬다"가 장부에서 구분되지 않는다.
func TestDecideReturnsErrorWhenSessionQueryFails(t *testing.T) {
	pool := newTestPool(t)
	r, _ := newTestRegistry(time.Second, 30*time.Second)
	ctx := context.Background()

	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("트랜잭션 시작 실패: %v", err)
	}
	if err := tx.Rollback(ctx); err != nil { // 조회가 반드시 실패하는 상태를 만든다
		t.Fatalf("롤백 실패: %v", err)
	}

	in := Input{StreamID: "demo", Seq: 1, StartWallUTC: wall, DurationMS: 4000, Op: OpenOrCurrent}
	d, err := r.Decide(ctx, tx, in, wall)

	if err == nil {
		t.Fatalf("조회가 실패했는데 갈래 %v 로 조용히 접혔다", d.Outcome)
	}
}

// explicitColumns 는 개시 트랜잭션이 **명시 대입**하는 7 컬럼이다(계획 3절 (가)).
// 나머지 컬럼은 DDL 기본값에 의존한다 — 그래서 M3 는 stream_sessions DDL 을 건드리지 않는다.
var explicitColumns = []string{
	"session_id", "stream_id", "started_at", "state",
	"first_pdt", "target_duration", "discontinuity_base",
}

// assertOnlyExplicitColumnsWritten 은 "명시 7 컬럼을 뺀 나머지가 DDL 기본값 그대로"를 잰다.
// 기본값을 손으로 32 개 나열하는 대신 **기본값만으로 만든 대조군 행**과 통째로 비교한다 —
// 컬럼 수를 세다 틀릴 여지가 없고, 뒤에 컬럼이 늘어도 대조가 자동으로 따라간다.
func assertOnlyExplicitColumnsWritten(t *testing.T, pool *pgxpool.Pool, id string) {
	t.Helper()
	ctx := context.Background()
	const refID = "S-defaults-reference"
	_, err := pool.Exec(ctx,
		`INSERT INTO stream_sessions (session_id, stream_id, started_at) VALUES ($1, 'ref-stream', now())`,
		refID)
	if err != nil {
		t.Fatalf("기본값 대조군 행 심기 실패: %v", err)
	}

	strip := ""
	for _, c := range explicitColumns {
		strip += fmt.Sprintf(" - '%s'", c)
	}
	var got, want string
	err = pool.QueryRow(ctx, fmt.Sprintf(`
		SELECT (SELECT (to_jsonb(a)%[1]s)::text FROM stream_sessions a WHERE a.session_id = $1),
		       (SELECT (to_jsonb(b)%[1]s)::text FROM stream_sessions b WHERE b.session_id = $2)`, strip),
		id, refID).Scan(&got, &want)
	if err != nil {
		t.Fatalf("기본값 대조 조회 실패: %v", err)
	}
	if got != want {
		t.Errorf("명시 7 컬럼 밖이 기본값과 다르다\n 개시 행 = %s\n 대조군  = %s", got, want)
	}
}

func TestOpenWritesSevenColumnsAndLeavesTheRestAtDDLDefaults(t *testing.T) {
	pool := newTestPool(t)
	r, _ := newTestRegistry(time.Second, 30*time.Second)
	in := Input{StreamID: "demo", Seq: 42, StartWallUTC: wall, DurationMS: 8000, Op: OpenOrCurrent}
	firstPDT := wall.Add(2 * time.Second)

	var id string
	withTx(t, pool, func(tx pgx.Tx) {
		ctx := context.Background()
		d, err := r.Decide(ctx, tx, in, wall)
		if err != nil {
			t.Fatalf("결정 실패: %v", err)
		}
		if id, err = r.Open(ctx, tx, d, firstPDT); err != nil {
			t.Fatalf("개시 실패: %v", err)
		}
	})

	if want := sessionID("demo", wall, 42); id != want {
		t.Errorf("session_id = %q, 기대 = %q", id, want)
	}
	var (
		streamID, state       string
		startedAt, gotFirstPD time.Time
		targetDuration        int32
		discBase              int64
	)
	err := pool.QueryRow(context.Background(), `
		SELECT stream_id, started_at, state, first_pdt, target_duration, discontinuity_base
		  FROM stream_sessions WHERE session_id = $1`, id).
		Scan(&streamID, &startedAt, &state, &gotFirstPD, &targetDuration, &discBase)
	if err != nil {
		t.Fatalf("개시 행 조회 실패: %v", err)
	}
	if streamID != "demo" {
		t.Errorf("stream_id = %q, 기대 = %q", streamID, "demo")
	}
	if !startedAt.Equal(wall) {
		t.Errorf("started_at = %v, 기대 = %v (개시 행의 start_wall_utc)", startedAt, wall)
	}
	if state != "live" {
		t.Errorf("state = %q, 기대 = %q", state, "live")
	}
	if !gotFirstPD.Equal(firstPDT) {
		t.Errorf("first_pdt = %v, 기대 = %v (인자 단일 대입 — 사후 UPDATE 없음)", gotFirstPD, firstPDT)
	}
	if targetDuration != 8 {
		t.Errorf("target_duration = %d, 기대 = 8 (max(6, round(8.0)))", targetDuration)
	}
	if discBase != 0 {
		t.Errorf("discontinuity_base = %d, 기대 = 0 (비분할 개시는 승계하지 않는다)", discBase)
	}
	assertOnlyExplicitColumnsWritten(t, pool, id)
}

// 두 커넥션이 같은 스트림을 동시에 열면 stream_sessions_one_live_uq 가 둘째를 23505 로 막는다.
//
// 이 단계는 그 오류를 **그대로 올린다** — 삼키지도, 재시도하지도 않는다.
// 경합의 처분(sentinel 합류 → H9 백오프)은 단계 3 에서 store 층이 정한다.
func TestOpenSurfacesConcurrentOpenConflictAsError(t *testing.T) {
	pool := newTestPool(t)
	r, _ := newTestRegistry(time.Second, 30*time.Second)
	ctx := context.Background()

	tx1, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("첫째 트랜잭션 시작 실패: %v", err)
	}
	defer func() { _ = tx1.Rollback(ctx) }()
	tx2, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("둘째 트랜잭션 시작 실패: %v", err)
	}
	defer func() { _ = tx2.Rollback(ctx) }()

	// 서로 다른 조각이라 session_id 도 다르다 — 충돌 축은 PK 가 아니라 one_live_uq 다.
	first := Input{StreamID: "demo", Seq: 1, StartWallUTC: wall, DurationMS: 4000, Op: OpenOrCurrent}
	second := Input{StreamID: "demo", Seq: 2, StartWallUTC: wall.Add(4 * time.Second), DurationMS: 4000, Op: OpenOrCurrent}

	d1, err := r.Decide(ctx, tx1, first, wall)
	if err != nil {
		t.Fatalf("첫째 결정 실패: %v", err)
	}
	d2, err := r.Decide(ctx, tx2, second, wall) // 둘 다 "live 없음"을 봤다
	if err != nil {
		t.Fatalf("둘째 결정 실패: %v", err)
	}
	if _, err := r.Open(ctx, tx1, d1, wall); err != nil {
		t.Fatalf("첫째 개시 실패: %v", err)
	}

	// 둘째 INSERT 는 첫째가 커밋할 때까지 유니크 인덱스에서 기다린다.
	conflict := make(chan error, 1)
	go func() {
		_, err := r.Open(ctx, tx2, d2, wall.Add(4*time.Second))
		conflict <- err
	}()
	if err := tx1.Commit(ctx); err != nil {
		t.Fatalf("첫째 커밋 실패: %v", err)
	}

	err = <-conflict
	if err == nil {
		t.Fatal("둘째 개시가 통과했다 — one_live_uq 가 막아야 한다")
	}
	var pgErr *pgconn.PgError
	if !errors.As(err, &pgErr) {
		t.Fatalf("올라온 오류가 PgError 가 아니다(%T: %v) — 원인을 감싸되 감추지 않아야 한다", err, err)
	}
	if pgErr.Code != "23505" || pgErr.ConstraintName != "stream_sessions_one_live_uq" {
		t.Errorf("오류 = (code %s, constraint %q), 기대 = (23505, stream_sessions_one_live_uq)",
			pgErr.Code, pgErr.ConstraintName)
	}
}

// TD 분할은 **유입·관측과 무관**하다(설계 4.9.1 — 유입별 결정 앞에 놓인 단계).
// 그래서 이 케이스는 CurrentOnly + 널 관측(EpochKnown=false)으로 잰다.
func TestOpenFreshEndsCurrentSessionThenOpensNextWithoutConflict(t *testing.T) {
	pool := newTestPool(t)
	seedSession(t, pool, sessionRow{
		id: "S-old", streamID: "demo", startedAt: wall.Add(-time.Hour), state: "live",
		targetDuration: 6, discontinuityBase: 3,
	})
	// 판별력 픽스처: **현 live 보다 늦게 시작한** 다른 세션을 깐다. OpenFresh 가 기저를
	// started_at 최신으로 조회했다면 이 행이 잡힌다(r8 이 뒤집힌 그 자리) — 손에 쥔
	// 현 live 를 쓰는지가 여기서 갈린다.
	seedSession(t, pool, sessionRow{
		id: "S-younger", streamID: "demo", startedAt: wall.Add(-time.Minute), state: "ended",
		targetDuration: 6,
	})
	r, _ := newTestRegistry(time.Second, 30*time.Second)
	in := Input{
		StreamID: "demo", Seq: 77, StartWallUTC: wall,
		DurationMS: 6500, // round(6.5) = 7 > TD 6
		Op:         CurrentOnly,
	}
	firstPDT := wall.Add(1500 * time.Millisecond)

	var id string
	withTx(t, pool, func(tx pgx.Tx) {
		ctx := context.Background()
		d, err := r.Decide(ctx, tx, in, wall)
		if err != nil {
			t.Fatalf("결정 실패: %v", err)
		}
		if d.Outcome != OutcomeOpenFresh {
			t.Fatalf("갈래 = %v, 기대 = %v", d.Outcome, OutcomeOpenFresh)
		}
		if d.BaseSessionID != "S-old" {
			t.Errorf("기저 세션 = %q, 기대 = %q (곧 ending 으로 보낼 그 세션)", d.BaseSessionID, "S-old")
		}
		if id, err = r.Open(ctx, tx, d, firstPDT); err != nil {
			t.Fatalf("분할 개시 실패: %v", err)
		}
	})

	ctx := context.Background()
	// 직전 세션 — 왜 끝났는지가 장부에 남는다.
	var (
		oldState, endReason string
		endingAt            *time.Time
	)
	if err := pool.QueryRow(ctx,
		`SELECT state, end_reason, ending_at FROM stream_sessions WHERE session_id = 'S-old'`).
		Scan(&oldState, &endReason, &endingAt); err != nil {
		t.Fatalf("직전 세션 조회 실패: %v", err)
	}
	if oldState != "ending" || endReason != "td_exceeded" || endingAt == nil {
		t.Errorf("직전 세션 = (state %q, end_reason %q, ending_at %v), 기대 = (ending, td_exceeded, 값 있음)",
			oldState, endReason, endingAt)
	}

	// 새 세션 — target_duration 은 분할을 부른 그 조각으로 다시 정하고, 불연속 기준은 승계한다.
	var (
		newState       string
		targetDuration int32
		discBase       int64
	)
	if err := pool.QueryRow(ctx,
		`SELECT state, target_duration, discontinuity_base FROM stream_sessions WHERE session_id = $1`, id).
		Scan(&newState, &targetDuration, &discBase); err != nil {
		t.Fatalf("새 세션 조회 실패: %v", err)
	}
	if newState != "live" || targetDuration != 7 || discBase != 3 {
		t.Errorf("새 세션 = (state %q, TD %d, disc_base %d), 기대 = (live, 7, 3 — 승계)",
			newState, targetDuration, discBase)
	}

	// one_live_uq 가 지켜졌다 = ending 을 먼저 쓰고 INSERT 했다는 실물 증거다.
	var liveCount int
	if err := pool.QueryRow(ctx,
		`SELECT count(*) FROM stream_sessions WHERE stream_id = 'demo' AND state = 'live'`).
		Scan(&liveCount); err != nil {
		t.Fatalf("live 세션 수 조회 실패: %v", err)
	}
	if liveCount != 1 {
		t.Errorf("live 세션 = %d 개, 기대 = 1", liveCount)
	}
}

// 개시 갈래의 기저 세션은 **상태 무관** 최신 기존 세션이다(계획 4.1 ⑶).
// state 필터를 지어내면 세션 경계에서 PDT 연속성이 끊어진다.
func TestDecideFixesBaseSessionToLatestSessionRegardlessOfState(t *testing.T) {
	pool := newTestPool(t)
	seedSession(t, pool, sessionRow{
		id: "S-old", streamID: "demo", startedAt: wall.Add(-3 * time.Hour), state: "ended", targetDuration: 6,
	})
	seedSession(t, pool, sessionRow{
		id: "S-recent", streamID: "demo", startedAt: wall.Add(-time.Hour), state: "ending", targetDuration: 6,
	})
	// 다른 스트림의 더 최신 세션은 후보가 아니다.
	seedSession(t, pool, sessionRow{
		id: "S-other", streamID: "other", startedAt: wall, state: "ended", targetDuration: 6,
	})
	r, _ := newTestRegistry(time.Second, 30*time.Second)
	in := Input{StreamID: "demo", Seq: 5, StartWallUTC: wall, DurationMS: 4000, Op: OpenOrCurrent}

	withTx(t, pool, func(tx pgx.Tx) {
		d, err := r.Decide(context.Background(), tx, in, wall)
		if err != nil {
			t.Fatalf("결정 실패: %v", err)
		}
		if d.Outcome != OutcomeOpen {
			t.Fatalf("갈래 = %v, 기대 = %v", d.Outcome, OutcomeOpen)
		}
		if d.BaseSessionID != "S-recent" {
			t.Errorf("기저 세션 = %q, 기대 = %q", d.BaseSessionID, "S-recent")
		}
	})
}

// started_at 동률에서는 session_id 내림차순이 가른다 — 값에 뜻은 없고 결정성만 얻는다(9절).
func TestDecideBreaksBaseSessionTieBySessionIDDescending(t *testing.T) {
	pool := newTestPool(t)
	for _, id := range []string{"S-a", "S-c", "S-b"} {
		seedSession(t, pool, sessionRow{
			id: id, streamID: "demo", startedAt: wall.Add(-time.Hour), state: "ended", targetDuration: 6,
		})
	}
	r, _ := newTestRegistry(time.Second, 30*time.Second)
	in := Input{StreamID: "demo", Seq: 5, StartWallUTC: wall, DurationMS: 4000, Op: OpenOrCurrent}

	withTx(t, pool, func(tx pgx.Tx) {
		d, err := r.Decide(context.Background(), tx, in, wall)
		if err != nil {
			t.Fatalf("결정 실패: %v", err)
		}
		if d.BaseSessionID != "S-c" {
			t.Errorf("기저 세션 = %q, 기대 = %q", d.BaseSessionID, "S-c")
		}
	})
}

func TestDecideLeavesBaseSessionEmptyWhenStreamHasNoSession(t *testing.T) {
	pool := newTestPool(t)
	r, _ := newTestRegistry(time.Second, 30*time.Second)
	in := Input{StreamID: "demo", Seq: 1, StartWallUTC: wall, DurationMS: 4000, Op: OpenOrCurrent}

	withTx(t, pool, func(tx pgx.Tx) {
		d, err := r.Decide(context.Background(), tx, in, wall)
		if err != nil {
			t.Fatalf("결정 실패: %v", err)
		}
		if d.Outcome != OutcomeOpen || d.BaseSessionID != "" {
			t.Errorf("갈래 = %v · 기저 = %q, 기대 = %v · \"\" (기저항 부재)",
				d.Outcome, d.BaseSessionID, OutcomeOpen)
		}
	})
}

// live 조회는 state='live' 부분 인덱스 축이다 — ending·ended 는 현 세션이 아니다(설계 6.2).
func TestDecideDoesNotSeeEndingOrEndedSessionAsLive(t *testing.T) {
	for _, state := range []string{"ending", "ended"} {
		t.Run(state, func(t *testing.T) {
			pool := newTestPool(t)
			seedSession(t, pool, sessionRow{
				id: "S-" + state, streamID: "demo", startedAt: wall, state: state, targetDuration: 6,
			})
			r, _ := newTestRegistry(time.Second, 30*time.Second)
			in := Input{
				StreamID: "demo", Seq: 5, StartWallUTC: wall.Add(time.Minute),
				DurationMS: 4000, Op: CurrentOnly,
			}

			withTx(t, pool, func(tx pgx.Tx) {
				d, err := r.Decide(context.Background(), tx, in, wall.Add(time.Minute))
				if err != nil {
					t.Fatalf("결정 실패: %v", err)
				}
				if d.Outcome != OutcomeNone {
					t.Errorf("갈래 = %v, 기대 = %v (%s 는 현 live 가 아니다)", d.Outcome, OutcomeNone, state)
				}
			})
		})
	}
}
