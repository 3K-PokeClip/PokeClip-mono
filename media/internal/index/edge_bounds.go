package index

import (
	"cmp"
	"context"
	"fmt"
	"slices"
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
// 한정하면 42883 이다. 한정하지 않으면 PG 가 그것을 한정하지 않은 한 인자 호출 여럿으로 풀고, 가로채기는 그 한 인자
// 호출에서 생긴다 — public 에 심은 unnest(text[]) 가 다형 판 unnest(anyarray) 보다 먼저 골린다. 나란히 펴는 배열은
// 길이가 같아야 한다 — ROWS FROM 은 짧은 쪽을 NULL 로 채운다. 하한 값이 NULL 이면 NOT NULL 위반(23502)으로 묶음 전체가
// 실패하고(충돌 행도 — 제안 행을 먼저 검사한다), 열쇠가 NULL 이면 열린 회차 · 원장 행 조인에 걸리지 않아 조용히 빠진다.
// Raise · MarkReleased 는 배열을 한 루프에서 함께 채운다.

// raiseSQL ($1 stream_id[] · $2 round_id[] · $3 dvr_floor[]) — 열린 회차만 · 해제된 행은 건드리지 않음 · 잠금 순서
// 고정.
//
// 열린 회차 확인은 그 회차의 열린 세션 행 하나를 FOR SHARE 로 잠근다 — 회차 닫힘(closeRoundSQL · lockRoundRowsSQL)은 그
// 회차의 열린 행 전부를 고치므로 이 잠금과 줄을 선다. 닫힘이 먼저 커밋했으면 잠금이 최신 행을 다시 봐(READ COMMITTED
// 재평가 · PG 17 13.2.1) 그 회차를 빼고, 이 문장이 먼저 잠갔으면 닫힘이 이 커밋을 기다려 판정식은 원장 행을 보고
// ending 을 낸다. 잠금이 없으면 문장 스냅숏의 「열림」 으로 닫힘 커밋 뒤에 행이 생겨, 이미 vod 를 낸 회차에 하한 키가
// 늦게 붙는다(E3 · 종결 r2 codex 설계 H1). FOR KEY SHARE 는 비키 UPDATE 와 충돌하지 않아 이 몫을 못 한다. 세션 잠금은
// EXISTS 하위 계획이 정렬 전 입력 순서로 모두 잡고 원장 행 잠금은 그 뒤 (stream, round) 순이다. 부르는 쪽(Raise)은 한
// 문장에 스트림마다 회차 하나만 실어, 이 문장이 쥐는 세션 행은 스트림마다 하나다.
//
// 여러 스트림의 세션 행을 한 문장에 잠그는 것은 이 문장과 fence 반납(rewind/publish fenceReleaseSQL — 한 writer 가 쥔
// fence 전부)이다. 둘은 같은 순서 — (stream, round) 바이트 순 — 로 잠가 서로 기다려도 교착(40P01)이 없다. 이 문장의
// 입력 순서는 Writer 의 후보 순서(edgebounds candidates — Go 문자열 비교)다. 앞 행을 쥔 채 뒤 행을 기다리는 동안 앞
// 행의 갱신도 줄을 서므로, 기다림은 raiseTxnLimitsSQL 의 lock_timeout(500ms)에서 끊는다.
const raiseSQL = `
INSERT INTO stream_edge_bounds AS b (stream_id, round_id, dvr_floor)
SELECT u.stream_id, u.round_id, u.dvr_floor
  FROM ROWS FROM (pg_catalog.unnest($1::text[]), pg_catalog.unnest($2::text[]), pg_catalog.unnest($3::bigint[]))
       AS u(stream_id, round_id, dvr_floor)
 WHERE EXISTS (SELECT 1 FROM stream_sessions s
                WHERE s.stream_id = u.stream_id AND s.round_id = u.round_id
                  AND s.round_closed_at IS NULL
                  FOR SHARE OF s)
 ORDER BY u.stream_id, u.round_id
ON CONFLICT (stream_id, round_id) DO UPDATE
   SET dvr_floor  = GREATEST(b.dvr_floor, EXCLUDED.dvr_floor),
       updated_at = pg_catalog.now()
 WHERE b.released_at IS NULL
RETURNING b.stream_id, b.round_id, b.dvr_floor`

// raiseTxnLimitsSQL 은 올림 트랜잭션의 지역 상한이다 — lock_timeout 500ms 가 raiseSQL 의 잠금 대기 하나의
// 상한이다(종결 r3 M2 · kty 2026-11-11 결정). 올림은 위상 잠금 밖에서 여러 스트림의 세션 행을 FOR SHARE 로 쥔 채 다음
// 행을 기다리므로, 상한이 없으면 한 스트림의 대기(다른 연결의 위상 · fence 갱신)가 이미 쥔 다른 스트림의 P0 · P4 ·
// fence · 위상 갱신까지 붙잡는다 — 그 대기의 끝은 Writer 의 CallTimeout(5초)뿐이고, 위상 트랜잭션의 lock_timeout(5초
// — setTxnLimitsSQL)에 닿아 ErrTopologyContended 를 낼 수 있다. 500ms 는 그보다 열 배 작고, 정상 경합(다른 상자의
// 올림 · 해제 · 회차 닫힘 — 짧은 문장 하나)은 넘지 않는다. 넘으면 55P03 으로 문장째 되돌리고 Writer 가
// write_failing(reason=failed · pass=raise)을 알린 뒤 다음 올림 틱(FlushEvery)에 GREATEST 로 다시 맞춘다 — 하한이 한
// 틱 늦게 오를 뿐(엣지가 덜 막는 쪽) 내려가지 않는다. 상한은 잠금 대기 하나마다다 — 한 대기라도 500ms 에 닿으면 그
// 자리에서 문장이 끝나고, 번진 대기는 문장이 실제로 기다린 대기(저마다 500ms 미만)의 합이다. statement_timeout 은 역할
// 기본값(60초)과 CallTimeout 에 맡긴다. 지역 설정이라 명시 트랜잭션 안에서만 듣는다(raisePass).
const raiseTxnLimitsSQL = `SELECT pg_catalog.set_config('lock_timeout','500ms',true)`

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

// markReleasedSQL ($1 stream_id[] · $2 round_id[]) — 잠금 순서 고정. 바깥 UPDATE 의 조인은 행을 잡는 순서를 정하지
// 않으므로(입력 순서 · 표의 물리 순서 · 해시 조인), 안쪽 질의가 (stream, round) 순으로 먼저 잠근다 — ORDER BY 뒤에
// FOR UPDATE 가 돈다. 두 상자의 Writer 가 겹치는 행을 다른 순서로 넘겨도 한쪽이 줄을 설 뿐 교착(40P01)은 없다.
const markReleasedSQL = `
UPDATE stream_edge_bounds b
   SET released_at = pg_catalog.now(), updated_at = pg_catalog.now()
  FROM (SELECT t.stream_id, t.round_id
          FROM stream_edge_bounds t
          JOIN ROWS FROM (pg_catalog.unnest($1::text[]), pg_catalog.unnest($2::text[])) AS u(stream_id, round_id)
            ON t.stream_id = u.stream_id AND t.round_id = u.round_id
         WHERE t.released_at IS NULL
         ORDER BY t.stream_id, t.round_id
           FOR UPDATE OF t) k
 WHERE b.stream_id = k.stream_id AND b.round_id = k.round_id AND b.released_at IS NULL`

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

// Raise 는 rows 가운데 열린 회차 · 미해제 행만 GREATEST 로 올리고(없으면 만든다) 올린 뒤의 행을 (stream, round) 순으로
// 돌려준다. 1 보다 작은 값은 문장에 싣기 전에 거른다(CHECK dvr_floor >= 1 — 한 행이 묶음 전체를 23514 로 만들지 않게).
// 다 걸러지면 문장을 보내지 않는다. 한 묶음 안 같은 (stream, round) 는 부른 쪽이 하나로 모은다 — 둘이면 문장
// 오류(21000)다.
//
// 한 스트림의 회차가 여럿이면 문장을 나눠 한 문장에 스트림마다 회차 하나만 싣는다(raisePasses). 위상 트랜잭션(회차 닫힘
// · 사슬 닫힘 · EndLive · 4D)은 ① 스트림별 위상 잠금으로 줄을 서서 세션 행 잠금 순서가 제각각이다(닫힘은 새 회차의 소속
// 행 → 옛 회차 행). 그 잠금 밖의 이 문장이 한 스트림의 두 회차 행을 함께 쥐면 그 순서와 엇갈려 교착(40P01)이 된다. 앞
// 문장이 커밋한 뒤 뒤 문장이 실패하면 오류만 돌려준다 — 원장은 GREATEST 라 다음 올림이 같은 값으로 다시 맞춘다.
func (l *EdgeBoundsLedger) Raise(ctx context.Context, rows []edgebounds.Floor) ([]edgebounds.Floor, error) {
	var out []edgebounds.Floor
	for _, p := range raisePasses(rows) {
		got, err := l.raisePass(ctx, p)
		if err != nil {
			return nil, fmt.Errorf("하한 원장 올림 실패: %w", err)
		}
		out = append(out, got...)
	}
	slices.SortFunc(out, func(a, b edgebounds.Floor) int {
		return cmp.Or(cmp.Compare(a.StreamID, b.StreamID), cmp.Compare(a.RoundID, b.RoundID))
	})
	return out, nil
}

// raisePass 는 raiseSQL 한 번을 제 트랜잭션에서 잠금 대기 상한(raiseTxnLimitsSQL) 아래 돌린다. 상한에 걸리면 55P03 이고
// 문장째 되돌린다(쥔 세션 행도 놓는다).
func (l *EdgeBoundsLedger) raisePass(ctx context.Context, p raiseArgs) ([]edgebounds.Floor, error) {
	var out []edgebounds.Floor
	err := pgx.BeginFunc(ctx, l.pool, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, raiseTxnLimitsSQL); err != nil {
			return err
		}
		var err error
		out, err = collectFloors(ctx, tx, raiseSQL, p.streams, p.rounds, p.values)
		return err
	})
	return out, err
}

// raiseArgs 는 raiseSQL 한 번의 인자 배열 셋이다(같은 길이).
type raiseArgs struct {
	streams, rounds []string
	values          []int64
}

// raisePasses 는 rows 의 1 이상 값을 문장 묶음으로 나눈다 — 스트림의 k 번째 회차는 k 번째 묶음에 들고, 같은
// (stream, round) 는 같은 묶음에 든다. 스트림마다 회차가 하나면(보통) 묶음도 하나다. 순서는 입력 순서를 지킨다.
func raisePasses(rows []edgebounds.Floor) []raiseArgs {
	var passes []raiseArgs
	slot := make(map[[2]string]int)
	next := make(map[string]int)
	for _, r := range rows {
		if r.Value < 1 {
			continue
		}
		key := [2]string{r.StreamID, r.RoundID}
		i, ok := slot[key]
		if !ok {
			i = next[r.StreamID]
			slot[key], next[r.StreamID] = i, i+1
		}
		if i == len(passes) {
			passes = append(passes, raiseArgs{})
		}
		p := &passes[i]
		p.streams, p.rounds = append(p.streams, r.StreamID), append(p.rounds, r.RoundID)
		p.values = append(p.values, r.Value)
	}
	return passes
}

// ClosedUnreleased 는 해제 안 된 행 가운데 회차가 닫혔거나 세션 행이 없는 것을 (stream, round) 순으로 limit 개
// 돌려준다. limit 이 1 보다 작으면 거절한다.
func (l *EdgeBoundsLedger) ClosedUnreleased(ctx context.Context, limit int) ([]edgebounds.Floor, error) {
	if limit < 1 {
		return nil, fmt.Errorf("하한 원장 해제 대상 조회의 limit = %d, want 1 이상", limit)
	}
	out, err := collectFloors(ctx, l.pool, closedUnreleasedSQL, limit)
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
func collectFloors(ctx context.Context, q rowQuerier, sql string, args ...any) ([]edgebounds.Floor, error) {
	rows, err := q.Query(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (edgebounds.Floor, error) {
		var f edgebounds.Floor
		err := r.Scan(&f.StreamID, &f.RoundID, &f.Value)
		return f, err
	})
}
