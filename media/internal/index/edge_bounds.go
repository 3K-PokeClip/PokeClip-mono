package index

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/edgebounds"
)

// /dvr 하한 원장(stream_edge_bounds — POK-525 설계 3-3 · 계획 부록 A)의 운영 구현이다. 원장은 열린 회차의 하한 정본이고
// KVS 는 사본이다 — Writer(rewind/edgebounds)가 이 문장 넷만 부른다. 열린 회차 판정은 stream_sessions_round_open_idx
// 부분 인덱스를 탄다. 회차는 다시 열리지 않는다(설계 2-2 — 회차 불변 트리거).
//
// 내장 함수는 pg_catalog 로 한정한다(search_path 가로채기, media/README.md). 비교 연산자는 text · bigint ·
// timestamptz 라 pg_catalog 에 정확한 판이 있다. 배열 여럿을 나란히 펴는 것은 ROWS FROM 에 한 인자 unnest 를 늘어놓는다
// (rewind_read.go 와 같은 꼴) — 여러 인자 unnest 는 FROM 의 한정하지 않은 이름에서만 풀리는 문법이라 pg_catalog 로
// 한정하면 42883 이고, 한정하지 않으면 public 에 심은 unnest(text[]) 가 다형 판보다 먼저 골린다.

// raiseSQL ($1 stream_id[] · $2 round_id[] · $3 dvr_floor[]) — 열린 회차만 · 해제된 행은 건드리지 않음 · 잠금 순서 고정.
const raiseSQL = `
INSERT INTO stream_edge_bounds AS b (stream_id, round_id, dvr_floor)
SELECT u.stream_id, u.round_id, u.dvr_floor
  FROM ROWS FROM (pg_catalog.unnest($1::text[]), pg_catalog.unnest($2::text[]), pg_catalog.unnest($3::bigint[]))
       AS u(stream_id, round_id, dvr_floor)
 WHERE EXISTS (SELECT 1 FROM stream_sessions s
                WHERE s.stream_id = u.stream_id AND s.round_id = u.round_id
                  AND s.round_closed_at IS NULL)
 ORDER BY u.stream_id, u.round_id
ON CONFLICT (stream_id, round_id) DO UPDATE
   SET dvr_floor  = GREATEST(b.dvr_floor, EXCLUDED.dvr_floor),
       updated_at = pg_catalog.now()
 WHERE b.released_at IS NULL
RETURNING b.stream_id, b.round_id, b.dvr_floor`

// closedUnreleasedSQL ($1 limit) — 그 회차에 열린 세션이 없으면(세션 행 없음 포함) 닫힌 것으로 본다.
const closedUnreleasedSQL = `
SELECT b.stream_id, b.round_id, b.dvr_floor
  FROM stream_edge_bounds b
 WHERE b.released_at IS NULL
   AND NOT EXISTS (SELECT 1 FROM stream_sessions s
                    WHERE s.stream_id = b.stream_id AND s.round_id = b.round_id
                      AND s.round_closed_at IS NULL)
 ORDER BY b.stream_id, b.round_id
 LIMIT $1`

// markReleasedSQL ($1 stream_id[] · $2 round_id[])
const markReleasedSQL = `
UPDATE stream_edge_bounds b
   SET released_at = pg_catalog.now(), updated_at = pg_catalog.now()
  FROM ROWS FROM (pg_catalog.unnest($1::text[]), pg_catalog.unnest($2::text[])) AS u(stream_id, round_id)
 WHERE b.stream_id = u.stream_id AND b.round_id = u.round_id AND b.released_at IS NULL`

// deleteReleasedSQL ($1 초)
const deleteReleasedSQL = `
DELETE FROM stream_edge_bounds
 WHERE released_at IS NOT NULL
   AND released_at < pg_catalog.now() - pg_catalog.make_interval(secs => $1::double precision)`

var _ edgebounds.Ledger = (*EdgeBoundsLedger)(nil)

// EdgeBoundsLedger 는 edgebounds.Ledger 의 PG 구현이다. 사이드카 계정(media_indexer)의 원장 표 권한 넷으로 돈다.
type EdgeBoundsLedger struct {
	pool *pgxpool.Pool
}

// NewEdgeBoundsLedger 는 pool 위의 원장이다.
func NewEdgeBoundsLedger(pool *pgxpool.Pool) *EdgeBoundsLedger {
	return &EdgeBoundsLedger{pool: pool}
}

// Raise 는 rows 가운데 열린 회차 · 미해제 행만 GREATEST 로 올리고(없으면 만든다) 올린 뒤의 행을 돌려준다. 1 보다 작은
// 값은 문장에 싣기 전에 거른다(CHECK dvr_floor >= 1 — 한 행이 묶음 전체를 23514 로 만들지 않게). 다 걸러지면 문장을
// 보내지 않는다. 한 묶음 안 같은 (stream, round) 는 부른 쪽이 하나로 모은다 — 둘이면 문장 오류(21000)다.
func (l *EdgeBoundsLedger) Raise(ctx context.Context, rows []edgebounds.Floor) ([]edgebounds.Floor, error) {
	streams := make([]string, 0, len(rows))
	rounds := make([]string, 0, len(rows))
	values := make([]int64, 0, len(rows))
	for _, r := range rows {
		if r.Value < 1 {
			continue
		}
		streams, rounds, values = append(streams, r.StreamID), append(rounds, r.RoundID), append(values, r.Value)
	}
	if len(streams) == 0 {
		return nil, nil
	}
	out, err := l.collectFloors(ctx, raiseSQL, streams, rounds, values)
	if err != nil {
		return nil, fmt.Errorf("하한 원장 올림 실패: %w", err)
	}
	return out, nil
}

// ClosedUnreleased 는 해제 안 된 행 가운데 회차가 닫혔거나 세션 행이 없는 것을 (stream, round) 순으로 limit 개
// 돌려준다. limit 이 1 보다 작으면 거절한다.
func (l *EdgeBoundsLedger) ClosedUnreleased(ctx context.Context, limit int) ([]edgebounds.Floor, error) {
	if limit < 1 {
		return nil, fmt.Errorf("하한 원장 해제 대상 조회의 limit = %d, want 1 이상", limit)
	}
	out, err := l.collectFloors(ctx, closedUnreleasedSQL, limit)
	if err != nil {
		return nil, fmt.Errorf("하한 원장 해제 대상 조회 실패: %w", err)
	}
	return out, nil
}

// MarkReleased 는 rows 의 행에 released_at 을 적는다. 이미 적힌 행 · 없는 행은 건드리지 않는다.
func (l *EdgeBoundsLedger) MarkReleased(ctx context.Context, rows []edgebounds.Floor) error {
	if len(rows) == 0 {
		return nil
	}
	streams := make([]string, 0, len(rows))
	rounds := make([]string, 0, len(rows))
	for _, r := range rows {
		streams, rounds = append(streams, r.StreamID), append(rounds, r.RoundID)
	}
	if _, err := l.pool.Exec(ctx, markReleasedSQL, streams, rounds); err != nil {
		return fmt.Errorf("하한 원장 해제 기록 실패: %w", err)
	}
	return nil
}

// DeleteReleased 는 olderThan 보다 먼저 해제된 행을 지우고 지운 수를 돌려준다. olderThan 이 음수면 거절한다 — 아직
// 해제 유예 안의 행까지 지운다.
func (l *EdgeBoundsLedger) DeleteReleased(ctx context.Context, olderThan time.Duration) (int64, error) {
	if olderThan < 0 {
		return 0, fmt.Errorf("하한 원장 해제 행 삭제의 olderThan = %s, want 0 이상", olderThan)
	}
	tag, err := l.pool.Exec(ctx, deleteReleasedSQL, olderThan.Seconds())
	if err != nil {
		return 0, fmt.Errorf("하한 원장 해제 행 삭제 실패: %w", err)
	}
	return tag.RowsAffected(), nil
}

// collectFloors 는 (stream_id, round_id, dvr_floor) 행을 돌려주는 문장 sql 의 결과다.
func (l *EdgeBoundsLedger) collectFloors(ctx context.Context, sql string, args ...any) ([]edgebounds.Floor, error) {
	rows, err := l.pool.Query(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (edgebounds.Floor, error) {
		var f edgebounds.Floor
		err := r.Scan(&f.StreamID, &f.RoundID, &f.Value)
		return f, err
	})
}
