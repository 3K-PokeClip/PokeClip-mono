package broadcast

// 발행기 보정(sgd 설계 2-1 · 4-7). 닫는 쪽이 ended 를 넣지 못한 완결 회차 — 꺼진 게이트 · 옛 판 · 권한 판정 거짓 — 를
// 발행기가 tick 머리에서 거둔다. 넣기는 닫는 쪽과 같은 문장(insertEndedSQL) 하나라 ended 의 내용 규칙(occurred_at ·
// segmentRange · started 상태)은 한 곳에만 있다.
//
// 보정은 위상 잠금을 잡지 않는다. 완결 회차는 다시 열리지 않고(닫힌 회차 합류 · 4D · 사슬 분리 거부), 잠금 밖에서
// 그 장부를 지우는 60일 만료는 보정 창(repairHorizon)으로 피한다 — 만료가 잠금에 참여하지 않으므로 잠금을 더해도 막지
// 못한다. 그래서 보정 값은 보정 시점의 원본 장부 값이다.

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// 보정 수치.
const (
	repairEvery        = 30 * time.Second // 정산 · 회차 스캔과 같은 주기 — 시도 간격의 하한
	repairTimeout      = 10 * time.Second // publishTimeout 과 같은 값 — 보정 한 번이 발행 tick 을 이만큼만 세운다
	repairBatch        = batchSize        // 주기당 쪽 크기
	repairExpiryMargin = 24 * time.Hour   // 만료 루프 · DB · 조각 벽시계 사이 시계 차와 고르기 ↔ 넣기 문장 시작 사이를 받는 여유
	// repairHorizon 은 보정 창(58일)이다 — 만료가 지울 수 있는 조각(시작 < 지금 − (보존 − 여유))과 서로 배타가 되게
	// 만료 상수에서 끌어 온다. 보존 기간을 바꾸면 같이 움직인다. 고르기 ↔ 넣기 문장 시작 사이는 repairTimeout 안이다.
	// 넣기 문장은 시작할 때의 스냅숏으로 장부를 읽고 잠금 대기 뒤에는 started 행만 다시 보므로, 서버에서 잠금을 기다린
	// 시간은 여유에 들지 않는다.
	repairHorizon = index.ExpiryRetention - index.ExpiryUntilSlack - repairExpiryMargin
)

// missingEndedSQL 은 보정 후보 한 쪽이다($1 쪽 크기 · $2 보정 창 밀리초). 완결(닫힘 ∧ 전원 ended)인데 ended 행이 없고
// started 가 살아 있으며, 회차 시작(started occurred_at = 머리 세션 started_at)이 창 안인 회차를 오래된 것부터 고른다.
// 완결 조건은 insertEndedSQL 의 done CTE 와 같은 규칙이고, WHERE 에서 미리 걸러 완결 아닌 회차가 쪽을 차지하지
// 않는다(selectDueSQL 과 같은 원리). 넣은 회차는 「ended 행 없음」 으로 다음 고르기에서 빠진다 — 커서 없이 앞으로 간다.
const missingEndedSQL = `
SELECT o.round_id
  FROM broadcast_lifecycle_outbox o
 WHERE o.sequence = 1 AND o.state <> 'abandoned'
   AND o.occurred_at >= pg_catalog.now() - $2::double precision * interval '1 millisecond'
   AND NOT EXISTS (SELECT 1 FROM broadcast_lifecycle_outbox e
                    WHERE e.round_id = o.round_id AND e.sequence = 2)
   AND EXISTS (SELECT 1 FROM stream_sessions s
                WHERE s.stream_id = o.ingest_stream_id AND s.round_id = o.round_id)
   AND NOT EXISTS (SELECT 1 FROM stream_sessions s
                    WHERE s.stream_id = o.ingest_stream_id AND s.round_id = o.round_id
                      AND (s.state <> 'ended' OR s.round_closed_at IS NULL))
 ORDER BY o.created_at, o.round_id
 LIMIT $1`

// selectMissingEnded 는 보정 후보를 limit 개까지 읽는다.
func selectMissingEnded(ctx context.Context, db *pgxpool.Pool, limit int) ([]string, error) {
	rows, err := db.Query(ctx, missingEndedSQL, limit, float64(repairHorizon.Milliseconds()))
	if err != nil {
		return nil, fmt.Errorf("broadcast: 보정 후보 읽기: %w", err)
	}
	rounds, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		return nil, fmt.Errorf("broadcast: 보정 후보 읽기: %w", err)
	}
	return rounds, nil
}

// insertMissingEnded 는 회차 하나에 insertEndedSQL 을 자동 커밋 문장 하나로 돌린다 — 완결 판정과 INSERT 가 같은
// 스냅숏이고 started 행 FOR SHARE 도 닫는 쪽과 같다. 1행이면 참, 고른 뒤 상태가 바뀌어(started 가 버려짐 · 다른
// 발행기가 먼저 넣음) 0행이면 (false, nil) 이다. 고르기와 넣기를 나눈 것은 두 발행기 경쟁을 시험이 결정적으로
// 재현하게 하려는 것이다.
func insertMissingEnded(ctx context.Context, db *pgxpool.Pool, round string) (bool, error) {
	tag, err := db.Exec(ctx, insertEndedSQL, StartedEventID(round), EndedEventID(round), EventEnded)
	if err != nil {
		return false, fmt.Errorf("broadcast: ended 보정 넣기(회차 %s): %w", round, err)
	}
	return tag.RowsAffected() == 1, nil
}

// repair 는 repairEvery 마다(기동 뒤 첫 tick 은 곧바로) repairTimeout 시한 안에서 후보 한 쪽을 거둔다. 넣은 회차마다
// WARN lifecycle_ended_repaired 를 남기고, 문장 실패는 lifecycle_store_failed(stage=repair) 뒤 그 주기를 멈춘다 —
// 남은 후보는 다음 주기다. 주기는 시도를 시작할 때 쓴다 — 실패한 시도도 다음 주기까지 다시 돌지 않아, started 행이
// 잠긴 동안 tick 마다 시한만큼 서지 않는다.
func (p *Publisher) repair(ctx context.Context) {
	now := time.Now()
	if !p.lastRepair.IsZero() && now.Sub(p.lastRepair) < p.repairEvery {
		return
	}
	p.lastRepair = now
	rctx, cancel := context.WithTimeout(ctx, p.repairTimeout)
	defer cancel()
	rounds, err := selectMissingEnded(rctx, p.pool, repairBatch)
	if err != nil {
		p.storeFailed(ctx, "repair", err) // 부모 ctx — 시한 초과는 남기고 정지 취소는 찍지 않는다
		return
	}
	for _, round := range rounds {
		ok, err := insertMissingEnded(rctx, p.pool, round)
		if err != nil {
			p.storeFailed(ctx, "repair", err)
			return
		}
		if ok {
			p.log.Warn("lifecycle_ended_repaired", "round", round)
		}
	}
}
