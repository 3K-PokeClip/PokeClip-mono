package publish

// 반납의 잠금 순서(POK-525 종결 r3 M1) — 반납(fenceReleaseSQL)은 이 writer 가 쥔 세션 행을 여러 스트림에 걸쳐 한
// 문장에 잠그고, 하한 원장 올림(index raiseSQL)은 열린 회차의 세션 행을 FOR SHARE 로 (stream, round) 순 — 부르는
// 쪽(edgebounds Writer)이 바이트 순으로 정렬한 입력 순서 — 으로 잠근다. 두 문장이 같은 행 둘을 다른 순서로 쥐면
// 교착(40P01)이다. PG 통합이다(PG_DSN 이 없으면 skip).

import (
	"context"
	"errors"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/edgebounds"
)

// 반납은 올림과 같은 순서로 잠근다 — 교착 없이 한쪽이 줄을 선다. 자연 창은 수 μs 라, 창을 결정적으로 연다.
//
//  1. 힙 순서를 올림 순서와 거꾸로 둔다(뒤 스트림 행이 앞 — 순서 지정 없는 반납은 힙 순서로 잠근다).
//  2. 다른 연결이 앞 스트림 행 first 를 FOR SHARE 로 쥔다. 반납을 보내면, 순서 없는 반납은 second 를 잠근 채
//     first 에서 기다리고(교착 전제), 정렬한 반납은 아무것도 쥐지 않고 first 에서 기다린다.
//  3. 반납이 기다리는 것을 pg_stat_activity 로 확인한 뒤 Raise([first, second]) 를 보낸다. FOR SHARE 는 쥔 쪽이
//     공유 잠금뿐이면 줄(튜플 잠금)을 서지 않고 바로 얻는다(heap_lock_tuple) — 올림이 first 를 얻는다. 순서 없는
//     반납이면 올림은 반납이 쥔 second 에서 기다리고, 정렬한 반납이면 second 도 얻어 끝난다.
//  4. 쥔 것을 풀면 순서 없는 반납은 first 에서 올림을 기다려 순환이 닫힌다 — deadlock_timeout 뒤 한쪽이 40P01 이다.
//
// 대소문자 섞인 스트림은 정렬 기준을 가른다 — 올림의 입력 순서는 Go 의 바이트 비교(edgebounds compareFloor)라 DB
// 기본 정렬(en_US 등)의 ORDER BY 와 다를 수 있다("Zb" < "aa" 는 바이트 순에서만 참).
func TestReleaseLocksInRaiseOrder(t *testing.T) {
	cases := []struct {
		name          string
		first, second string // 올림이 잠그는 순서(바이트 순)의 스트림 — 회차 id 는 스트림 id + "-r"
		collation     bool   // DB 기본 정렬이 바이트 순과 갈라야 뜻이 있는 사례
	}{
		{name: "소문자", first: "sa", second: "sb"},
		{name: "대소문자_섞임", first: "Zb", second: "aa", collation: true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			pool := newPool(t)
			exec(t, pool, `TRUNCATE stream_edge_bounds`)
			if tc.collation && !collationDiffersFromBytes(t, pool, tc.first, tc.second) {
				t.Skip("DB 기본 정렬이 바이트 순과 같다 — 이 사례가 가르는 것이 없다")
			}
			first, second := tc.first+"-r", tc.second+"-r"
			me := newPublisher(t, pool, newFakeStore(t), "w-me", &logRecorder{})
			for _, s := range [][2]string{{tc.second, second}, {tc.first, first}} { // 힙에 second 가 앞이다
				insertRoundHead(t, pool, s[0], s[1])
				if ok, err := me.acquire(t.Context(), s[1]); !ok || err != nil {
					t.Fatalf("acquire(%s) = (%v, %v)", s[1], ok, err)
				}
			}
			if got := heapOrder(t, pool, "w-me"); !slices.Equal(got, []string{second, first}) {
				t.Fatalf("픽스처 힙 순서 = %v, want [%s %s]", got, second, first)
			}

			unshare := shareRow(t, pool, first)
			ctx, cancel := context.WithTimeout(t.Context(), 30*time.Second)
			defer cancel()
			released := make(chan error, 1)
			go func() { released <- me.Release(ctx) }()
			waitStmtLockWait(t, pool, fenceReleaseSQL, nil)

			rows := []edgebounds.Floor{{StreamID: tc.first, RoundID: first, Value: 5}, {StreamID: tc.second, RoundID: second, Value: 5}}
			raised := make(chan error, 1)
			raiseEnd := make(chan struct{})
			go func() {
				_, err := index.NewEdgeBoundsLedger(pool).Raise(ctx, rows)
				raised <- err
				close(raiseEnd)
			}()
			waitStmtLockWait(t, pool, "INSERT INTO stream_edge_bounds", raiseEnd)
			unshare()

			for _, r := range []struct {
				name string
				done chan error
			}{{"Release", released}, {"Raise", raised}} {
				err := <-r.done
				var pgErr *pgconn.PgError
				switch {
				case err == nil:
				case errors.As(err, &pgErr) && pgErr.Code == "40P01":
					t.Errorf("%s 이 교착(40P01)으로 끝났다 — 반납과 올림의 잠금 순서가 엇갈린다: %v", r.name, err)
				default:
					t.Errorf("%s 오류: %v", r.name, err)
				}
			}
			for _, id := range []string{first, second} {
				if got := readSession(t, pool, id).fence; got != nil {
					t.Errorf("반납 뒤 %s 의 writer_fence = %s, want NULL", id, *got)
				}
			}
		})
	}
}

// insertRoundHead 는 스트림 streamID 의 회차 머리 세션 id 를 live 로 넣는다(round_id = id · 열린 회차).
func insertRoundHead(t *testing.T, pool *pgxpool.Pool, streamID, id string) {
	t.Helper()
	exec(t, pool, `
		INSERT INTO stream_sessions (session_id, stream_id, round_id, started_at, state, init_uploaded_at, target_duration)
		VALUES ($1, $2, $1, now(), 'live', now(), 6)`, id, streamID)
}

// collationDiffersFromBytes 는 DB 기본 정렬이 a < b(바이트 순)를 뒤집는지다.
func collationDiffersFromBytes(t *testing.T, pool *pgxpool.Pool, a, b string) bool {
	t.Helper()
	var flipped bool
	if err := pool.QueryRow(context.Background(), `SELECT $1::text COLLATE "C" < $2::text AND $1::text > $2::text`, a, b).Scan(&flipped); err != nil {
		t.Fatalf("정렬 확인 실패: %v", err)
	}
	return flipped
}

// heapOrder 는 writer 가 쥔 세션 행을 힙(물리) 순서로 돌려준다 — 순서 지정 없는 순차 스캔이 잠그는 순서다.
func heapOrder(t *testing.T, pool *pgxpool.Pool, writer string) []string {
	t.Helper()
	rows, err := pool.Query(context.Background(), `SELECT session_id FROM stream_sessions WHERE writer_fence = $1 ORDER BY ctid`, writer)
	if err != nil {
		t.Fatalf("힙 순서 읽기 실패: %v", err)
	}
	ids, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		t.Fatalf("힙 순서 읽기 실패: %v", err)
	}
	return ids
}

// shareRow 는 다른 연결에서 회차 id 의 행을 FOR SHARE 로 쥔 트랜잭션을 연다(lockRow 의 공유 잠금 판) — 그 행을 고치는
// 문장은 기다리고, 공유 잠금은 줄을 서지 않고 함께 얻는다. 돌려준 함수가 트랜잭션을 되돌려 푼다(여러 번 불러도 된다).
func shareRow(t *testing.T, pool *pgxpool.Pool, id string) (release func()) {
	t.Helper()
	tx, err := pool.Begin(context.Background())
	if err != nil {
		t.Fatalf("잠금 트랜잭션 시작 실패: %v", err)
	}
	var once sync.Once
	release = func() {
		once.Do(func() {
			if err := tx.Rollback(context.Background()); err != nil {
				t.Errorf("잠금 트랜잭션 되돌리기 실패: %v", err)
			}
		})
	}
	t.Cleanup(release)
	if _, err := tx.Exec(context.Background(), `SELECT 1 FROM stream_sessions WHERE session_id = $1 FOR SHARE`, id); err != nil {
		t.Fatalf("회차 %s 행 공유 잠금 실패: %v", id, err)
	}
	return release
}

// waitStmtLockWait 는 문장 텍스트에 marker 가 든 이 DB 의 연결이 행 잠금을 기다릴 때까지 기다린다. done 이 닫히면(그 문장이
// 기다리지 않고 끝났다) 바로 돌아온다. 10초 안에 둘 다 아니면 실패다.
func waitStmtLockWait(t *testing.T, pool *pgxpool.Pool, marker string, done <-chan struct{}) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		select {
		case <-done:
			return
		default:
		}
		var waiting bool
		err := pool.QueryRow(context.Background(), `
			SELECT EXISTS (SELECT 1 FROM pg_stat_activity
			                WHERE datname = current_database() AND wait_event_type = 'Lock'
			                  AND position($1 in query) > 0)`, marker).Scan(&waiting)
		if err != nil {
			t.Fatalf("pg_stat_activity 읽기 실패: %v", err)
		}
		if waiting {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("10초 안에 %q 문장이 잠금 대기에 들지도 끝나지도 않았다", marker)
}
