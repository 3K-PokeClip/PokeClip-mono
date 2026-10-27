package broadcast

// 발행기 쪽 outbox 문장(설계 M6 15.5 · X4 · X5). 발행기(Publisher)만 부르고, 버림(abandon)의 두 문장 말고는 각 문장이
// 풀에서 단독으로 돈다.

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// outboxRow 는 발행 대상 행 하나다.
type outboxRow struct {
	eventID    string
	round      string
	eventType  string
	sequence   int64
	occurredAt time.Time
	ingest     string
	streamerID string // started 는 그 행의 값, ended 는 같은 회차 started 행의 값(D62). 없으면 ""
	payload    []byte
	attempts   int
}

// selectDueSQL 은 때가 된 pending 행을 next_at 순으로 고른다($1 쪽 크기). 같은 회차의 낮은 sequence 가 published 가
// 아니면 그 행은 고르지 않는다 — 자격을 LIMIT 앞(WHERE)에서 거르므로 막힌 ended 가 쪽을 차지하지 않는다(D36 · D63).
// 부분 인덱스 broadcast_lifecycle_outbox_due_idx 로 간다.
const selectDueSQL = `
SELECT o.event_id, o.round_id, o.event_type, o.sequence, o.occurred_at, o.ingest_stream_id,
       CASE WHEN o.sequence = 1 THEN o.streamer_id
            ELSE (SELECT p.streamer_id FROM broadcast_lifecycle_outbox p
                   WHERE p.round_id = o.round_id AND p.sequence = 1) END,
       o.payload::text, o.attempts
  FROM broadcast_lifecycle_outbox o
 WHERE o.state = 'pending' AND o.next_at <= pg_catalog.now()
   AND NOT EXISTS (SELECT 1 FROM broadcast_lifecycle_outbox p
                    WHERE p.round_id = o.round_id AND p.sequence < o.sequence
                      AND p.state <> 'published')
 ORDER BY o.next_at
 LIMIT $1`

// selectDue 는 발행 대상을 limit 개까지 읽는다.
func selectDue(ctx context.Context, db *pgxpool.Pool, limit int) ([]outboxRow, error) {
	rows, err := db.Query(ctx, selectDueSQL, limit)
	if err != nil {
		return nil, fmt.Errorf("broadcast: 발행 대상 읽기: %w", err)
	}
	defer rows.Close()
	var out []outboxRow
	for rows.Next() {
		var (
			r        outboxRow
			streamer *string
			payload  string
		)
		if err := rows.Scan(&r.eventID, &r.round, &r.eventType, &r.sequence, &r.occurredAt, &r.ingest, &streamer,
			&payload, &r.attempts); err != nil {
			return nil, fmt.Errorf("broadcast: 발행 대상 읽기: %w", err)
		}
		if streamer != nil {
			r.streamerID = *streamer
		}
		r.payload = []byte(payload)
		out = append(out, r)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("broadcast: 발행 대상 읽기: %w", err)
	}
	return out, nil
}

// execPending 은 pending 행 하나를 바꾸는 문장을 돌린다 — 이미 pending 이 아니면 0행이고 오류가 아니다.
func execPending(ctx context.Context, db *pgxpool.Pool, what, sql string, args ...any) error {
	if _, err := db.Exec(ctx, sql, args...); err != nil {
		return fmt.Errorf("broadcast: %s: %w", what, err)
	}
	return nil
}

// markPublished 는 발행 성공이다.
func markPublished(ctx context.Context, db *pgxpool.Pool, eventID string) error {
	return execPending(ctx, db, "발행 기록", `
UPDATE broadcast_lifecycle_outbox
   SET state = 'published', published_at = pg_catalog.now(), attempts = attempts + 1, last_error = NULL
 WHERE event_id = $1 AND state = 'pending'`, eventID)
}

// markRetry 는 실패 · 불명이다 — 같은 행(같은 eventId)을 delay 뒤에 다시 고른다. lastErr 는 분류된 짧은 문구만 받는다
// (응답 본문 · 토큰을 싣지 않는다).
func markRetry(ctx context.Context, db *pgxpool.Pool, eventID string, delay time.Duration, lastErr string) error {
	return execPending(ctx, db, "재시도 기록", `
UPDATE broadcast_lifecycle_outbox
   SET attempts = attempts + 1, last_error = $2,
       next_at = pg_catalog.now() + $3::double precision * interval '1 millisecond'
 WHERE event_id = $1 AND state = 'pending'`, eventID, lastErr, float64(delay.Milliseconds()))
}

// recordStreamer 는 started 행에 resolve 로 얻은 userId 를 남긴다 — 재시도 · 재기동 뒤 다시 묻지 않고, ended 가 같은
// 값을 싣는다.
func recordStreamer(ctx context.Context, db *pgxpool.Pool, eventID, streamerID string) error {
	return execPending(ctx, db, "streamerId 기록", `
UPDATE broadcast_lifecycle_outbox SET streamer_id = $2
 WHERE event_id = $1 AND state = 'pending'`, eventID, streamerID)
}

// abandonRowSQL · abandonLaterSQL 은 행 하나를 버리고, 그 행보다 sequence 가 높은 같은 회차 pending 행도
// started_abandoned 로 버린다(X4 규칙 2 — started 를 버리면 ended 도 내지 않는다). 한 트랜잭션의 두 문장이다 — 앞
// 문장이 started 행 잠금을 얻을 때까지 기다리면(완결 평가가 FOR SHARE 로 쥠 · insertEndedSQL) 뒤 문장이 새 스냅숏으로
// 그 사이 커밋된 ended 를 본다. 문장 하나면 그 스냅숏에 없는 ended 가 pending 에 고착한다(B4).
const (
	abandonRowSQL = `
UPDATE broadcast_lifecycle_outbox SET state = 'abandoned', last_error = $2
 WHERE event_id = $1 AND state = 'pending'`
	abandonLaterSQL = `
UPDATE broadcast_lifecycle_outbox SET state = 'abandoned', last_error = 'started_abandoned'
 WHERE state = 'pending' AND round_id = $1 AND sequence > $2`
)

// abandon 은 행을 버리고 바뀐 행 수(뒤 행 포함)를 돌려준다. 격리 수준을 READ COMMITTED 로 못 박는다 — 뒤 문장이
// 새 스냅숏을 받는 것은 이 수준에서만이다. DB · 역할의 기본값(default_transaction_isolation)이 repeatable read 면
// 두 문장이 같은 스냅숏을 보아 문장 하나일 때처럼 ended 가 pending 에 고착한다.
func abandon(ctx context.Context, db *pgxpool.Pool, r outboxRow, reason string) (int64, error) {
	var n int64
	err := pgx.BeginTxFunc(ctx, db, pgx.TxOptions{IsoLevel: pgx.ReadCommitted}, func(tx pgx.Tx) error {
		row, err := tx.Exec(ctx, abandonRowSQL, r.eventID, reason)
		if err != nil {
			return err
		}
		later, err := tx.Exec(ctx, abandonLaterSQL, r.round, r.sequence)
		if err != nil {
			return err
		}
		n = row.RowsAffected() + later.RowsAffected()
		return nil
	})
	if err != nil {
		return 0, fmt.Errorf("broadcast: 버림 기록: %w", err)
	}
	return n, nil
}

// deleteSettledSQL 은 ended 가 발행된 지 $1 밀리초가 지난 회차의 행을 지운다(15.5 규칙 5).
const deleteSettledSQL = `
DELETE FROM broadcast_lifecycle_outbox
 WHERE round_id IN (SELECT round_id FROM broadcast_lifecycle_outbox
                     WHERE sequence = 2 AND state = 'published'
                       AND published_at < pg_catalog.now() - $1::double precision * interval '1 millisecond')`

// deleteSettled 는 정리 문장을 돌려 지운 행 수를 돌려준다.
func deleteSettled(ctx context.Context, db *pgxpool.Pool, age time.Duration) (int64, error) {
	tag, err := db.Exec(ctx, deleteSettledSQL, float64(age.Milliseconds()))
	if err != nil {
		return 0, fmt.Errorf("broadcast: 정리: %w", err)
	}
	return tag.RowsAffected(), nil
}
