package index

// 판 2 회차 개시와 게이트 변경이 두 연결에서 엇갈리는 PG 시험(POK-196 PDT 생산자 c4b · 설계 r7 9.3 · 10.3 P19 · 계획 4절
// c4b 시험 3 · 이연 목록 「생산자 Insert 트랜잭션 격리 고정」). 엇갈림은 잠금 대기를 pg_stat_activity 로 확인해 순서를
// 못 박는다(시간 잠으로 맞추지 않는다 — waitLockWaiters 는 chain_close_pg_test.go). PG_DSN 미설정이면 skip 이다.

import (
	"context"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// poolWithIsolation 은 pool 과 같은 DB 에 붙되 세션 기본 격리 수준이 level 인 풀이다 — DB · 역할 설정이 기본값을 바꾼
// 운영 환경(ALTER ROLE … SET default_transaction_isolation)을 흉내 낸다(broadcast 패키지 abandon_race_pg_test.go 와 같은 꼴).
func poolWithIsolation(t *testing.T, pool *pgxpool.Pool, level string) *pgxpool.Pool {
	t.Helper()
	cfg := pool.Config()
	if cfg.ConnConfig.RuntimeParams == nil {
		cfg.ConnConfig.RuntimeParams = map[string]string{}
	}
	cfg.ConnConfig.RuntimeParams["default_transaction_isolation"] = level
	p, err := pgxpool.NewWithConfig(context.Background(), cfg)
	if err != nil {
		t.Fatalf("격리 수준 %s 풀 실패: %v", level, err)
	}
	t.Cleanup(p.Close)
	return p
}

// gateLatch 는 게이트 래치(highest_version)다. NULL 이면 nil 이다.
func gateLatch(t *testing.T, pool *pgxpool.Pool) *int16 {
	t.Helper()
	g, err := ReadTimingGate(context.Background(), pool)
	if err != nil {
		t.Fatalf("게이트 읽기 실패: %v", err)
	}
	return g.Highest
}

func countV2Rows(t *testing.T, pool *pgxpool.Pool) int {
	t.Helper()
	var n int
	if err := pool.QueryRow(context.Background(), `SELECT pg_catalog.count(*) FROM stream_segments WHERE timing_version = 2`).Scan(&n); err != nil {
		t.Fatalf("판 2 행 세기 실패: %v", err)
	}
	return n
}

// lowerLatchSQL 은 소유자 손 SQL 의 흉내다 — 스위치 · 래치를 함께 NULL 로 내린다(게이트 트리거가 판 2 행이 있으면 거부).
const lowerLatchSQL = `UPDATE stream_timing_gate SET new_round_version = NULL, highest_version = NULL, changed_at = now(), changed_by = 'test'`

// P19(설계 10.3 · 9.3) — 회차 개시 트랜잭션이 게이트 공유 잠금을 쥐고 판 2 를 읽은 채 행을 쓰는 동안, 다른 연결의 래치
// 낮추기는 배타 잠금에서 기다렸다가 개시가 커밋한 판 2 행을 보고 23514 로 거부된다. 개시는 media_indexer 역할로 돌고
// 거부된 문장이 없다(42501 0). 개시를 세우는 방법: 같은 (stream, seq) 를 다른 트랜잭션이 미커밋으로 쥐어 개시의 행 INSERT 가
// PK 확인에서 기다리게 한다 — 그 자리는 게이트를 읽은 뒤다. 잡는 결함: m23(회차 개시의 공유 잠금 제거 — 낮추기가 기다리지
// 않고 성공해 래치 NULL 인데 판 2 행이 커밋된다).
func TestV2GateLatchLoweringWaitsForOpeningRound(t *testing.T) {
	admin := newTestPool(t)
	role := pgtest.NewMediaIndexerRole(t, admin)
	t.Cleanup(func() { role.RequireNoneDenied(t) })
	store := newSessionStore(role.Pool)
	ctx := context.Background()
	stream := sessionStream("v2-p19")
	setGateSwitch(t, admin, &versionTwo)

	blocker, err := admin.Begin(ctx)
	if err != nil {
		t.Fatalf("막는 트랜잭션 시작 실패: %v", err)
	}
	defer func() { _ = blocker.Rollback(ctx) }()
	if _, err := blocker.Exec(ctx, `
		INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path,
		                             upload_state, bytes, timing_wall_guess)
		VALUES ($1, 0, 0, $2, 4000, 'blocker', '/recordings/blocker/0.mp4', 'pending', 1, $2)`, stream, sessionBase); err != nil {
		t.Fatalf("막는 행 INSERT 실패: %v", err)
	}

	type result struct {
		res InsertCommit
		err error
	}
	opened := make(chan result, 1)
	go func() {
		_, res, err := store.Insert(ctx, v2Seg(stream, 0, v2At(0), v2Facts(recorderX, 10, v2At(0)), true), Seed{}, liveIngress())
		opened <- result{res, err}
	}()
	waitLockWaiters(t, admin, 1) // 개시가 게이트를 읽고 행 INSERT 에서 기다린다

	lowered := make(chan error, 1)
	go func() {
		_, err := admin.Exec(ctx, lowerLatchSQL)
		lowered <- err
	}()
	waitLockWaiters(t, admin, 2) // 낮추기가 게이트 배타 잠금에서 기다린다

	if err := blocker.Rollback(ctx); err != nil {
		t.Fatalf("막는 트랜잭션 되돌리기 실패: %v", err)
	}
	got := <-opened
	if got.err != nil || got.res.TimingVersion == nil || *got.res.TimingVersion != versionV2 {
		t.Fatalf("개시 Insert = (%+v, %v), want 판 2 행", got.res, got.err)
	}
	if err := <-lowered; !isSQLState(err, "23514") || !strings.Contains(err.Error(), "timing_latch_lower_refused") {
		t.Errorf("래치 낮추기 오류 = %v, want 23514 timing_latch_lower_refused", err)
	}
	if latch := gateLatch(t, admin); latch == nil || *latch != versionV2 {
		t.Errorf("래치 = %v, want 2(판 2 행이 있다)", latch)
	}
	role.RequireSent(t, map[string]string{
		"lockTimingGateSharedSQL": lockTimingGateSharedSQL, "timingGateSQL": timingGateSQL, "lastRowSQL": lastRowSQL,
		"insertSeedSQL": insertSeedSQL,
	})
}

// 이연 목록 「생산자 Insert 트랜잭션 격리 고정」(c4a cc · cx · 보안 M1) — 연결의 기본 격리 수준이 REPEATABLE READ 여도 회차
// 개시는 게이트 공유 잠금을 기다린 뒤의 게이트를 읽는다. 소유자가 스위치 · 래치를 내리는 트랜잭션을 미커밋으로 쥔 채
// 개시가 시작되고(첫 문장에서 스냅숏이 잡힌다), 개시가 공유 잠금에서 기다리는 동안 소유자가 커밋한다. READ COMMITTED 면
// 기다린 뒤의 문장이 새 스냅숏으로 스위치 NULL 을 읽어 판 NULL 행을 쓴다. REPEATABLE READ 를 물려받으면 첫 문장의 스냅숏으로
// 스위치 2 를 읽어 래치 NULL 인 장부에 판 2 행을 쓴다 — 판 2 행이 있는데 래치가 없으면 쓰기자 거부가 꺼진 채라 출시 이미지가
// 판 2 회차에 옛 식으로 쓸 수 있다. 잡는 결함: Insert 가 격리 수준을 정하지 않음.
func TestV2InsertReadsGateAfterLockWhateverDefaultIsolation(t *testing.T) {
	for _, level := range []string{"read committed", "repeatable read"} {
		t.Run(level, func(t *testing.T) {
			admin := newTestPool(t)
			store := newSessionStore(poolWithIsolation(t, admin, level))
			ctx := context.Background()
			stream := sessionStream("v2-iso")
			setGateSwitch(t, admin, &versionTwo)

			owner, err := admin.Begin(ctx)
			if err != nil {
				t.Fatalf("소유자 트랜잭션 시작 실패: %v", err)
			}
			defer func() { _ = owner.Rollback(ctx) }()
			if _, err := owner.Exec(ctx, lowerLatchSQL); err != nil {
				t.Fatalf("래치 낮추기 실패(판 2 행이 없어야 한다): %v", err)
			}

			type result struct {
				res InsertCommit
				err error
			}
			opened := make(chan result, 1)
			go func() {
				_, res, err := store.Insert(ctx, v2Seg(stream, 0, v2At(0), v2Facts(recorderX, 10, v2At(0)), true), Seed{}, liveIngress())
				opened <- result{res, err}
			}()
			waitLockWaiters(t, admin, 1) // 개시가 게이트 공유 잠금에서 기다린다
			if err := owner.Commit(ctx); err != nil {
				t.Fatalf("소유자 커밋 실패: %v", err)
			}
			got := <-opened
			if got.err != nil {
				t.Fatalf("개시 Insert 오류 = %v", got.err)
			}
			if got.res.TimingVersion != nil || got.res.TimingGateRead == nil || got.res.TimingGateRead.NewRoundVersion != nil {
				t.Errorf("개시 (판, 읽은 스위치) = (%v, %+v), want (NULL, 스위치 NULL — 잠금 뒤의 게이트)", got.res.TimingVersion, got.res.TimingGateRead)
			}
			if n, latch := countV2Rows(t, admin), gateLatch(t, admin); n != 0 || latch != nil {
				t.Errorf("(판 2 행, 래치) = (%d, %v), want (0, NULL) — 래치 없는 판 2 행", n, latch)
			}
		})
	}
}
