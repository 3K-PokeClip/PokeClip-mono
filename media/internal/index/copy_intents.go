package index

// 회수 기록(copy intent — POK-525 ①-5a). VOD 사본 복사가 첫 CopyObject 를 보내기 전에, 그 시도의 사본 키를 파생할
// 근거(스트림 · seq 범위 · init 회차)를 vod_copy_intents 에 커밋해 둔다(Kafka KIP-405 의 「복사 시작」 선기록 꼴).
// 기한 due_at 은 발급 때 정하고(그 시도의 최악 복사 끝 + 유예) 어느 출구도 바꾸지 않는다 — 기한이 지나면 유예
// 재삭제(①-5b)가 Claim 으로 잡아 행 없는 짝의 사본을 멱등으로 지우고 Close 한다. 주기 대사는 이 표를 읽지 않는다.
//
// 외래키가 없다 — 만료 · 탈퇴가 조각 · 세션 행을 지워도 기록은 남아야 그 범위의 사본을 거둔다.

import (
	"context"
	"errors"
	"fmt"
	"slices"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// CopyIntentLease 는 Claim 이 잡은 기록의 기한을 미루는 임대다. 잡은 쪽이 닫지 못하고 죽거나 한 회 시한을 넘기면
// 이만큼 뒤에 다시 잡힌다 — 잡은 쪽의 한 회 시한은 이보다 짧아야 두 쪽이 같은 기록을 동시에 돌지 않는다.
const CopyIntentLease = 5 * time.Minute

// CopyIntentRange 는 기록 한 건이 가리키는 복사 범위다.
type CopyIntentRange struct {
	StreamID string
	// FirstSeq · LastSeq 는 보낸 조각 짝의 seq 최소 · 최대다(LastSeq ≥ FirstSeq ≥ 0).
	FirstSeq, LastSeq int64
	// InitSessions 는 init 짝을 보낸 회차(session_id)다. 없으면 빈 목록이다.
	InitSessions []string
}

// CopyIntent 는 표의 기록 한 행이다. DueAt 은 Open 이 정한 기한이거나, Claim 이 돌려준 경우 임대 끝이다.
type CopyIntent struct {
	ID int64
	CopyIntentRange
	DueAt time.Time
}

// validate 는 범위를 문장을 보내기 전에 거른다 — 표의 CHECK 와 같은 규칙에 빈 값 · 음수를 더한다.
func (r CopyIntentRange) validate() error {
	switch {
	case r.StreamID == "":
		return errors.New("index: 회수 기록의 스트림이 비었다")
	case r.FirstSeq < 0 || r.LastSeq < r.FirstSeq:
		return fmt.Errorf("index: 회수 기록 stream_id=%q 의 seq 범위 [%d, %d] 가 올바르지 않다", r.StreamID, r.FirstSeq, r.LastSeq)
	case slices.Contains(r.InitSessions, ""):
		return fmt.Errorf("index: 회수 기록 stream_id=%q 의 init 회차에 빈 이름이 있다", r.StreamID)
	}
	return nil
}

// copyIntentColumns 는 세 문장이 돌려주는 열이다(scanCopyIntent 의 순서).
const copyIntentColumns = `intent_id, stream_id, first_seq, last_seq, init_sessions, due_at`

// openCopyIntentSQL ($1 스트림 · $2 첫 seq · $3 끝 seq · $4 init 회차 · $5 기한까지의 기간) — 기한은 DB 시계로 정한다.
const openCopyIntentSQL = `
INSERT INTO vod_copy_intents (stream_id, first_seq, last_seq, init_sessions, due_at)
VALUES ($1::text, $2::bigint, $3::bigint, $4::text[], pg_catalog.now() + $5::interval)
RETURNING ` + copyIntentColumns

// claimCopyIntentsSQL ($1 쪽 크기 · $2 임대) — 기한이 지난 기록을 이른 것부터 잠가 고르고(다른 연결이 잠근 행은
// 건너뛴다) 기한을 지금 + 임대로 미룬다. 고르기와 미루기가 한 문장이라 커밋하면 잠금이 풀려도 임대가 남는다.
const claimCopyIntentsSQL = `
WITH due AS (
  SELECT i.intent_id FROM vod_copy_intents i
   WHERE i.due_at <= pg_catalog.now()
   ORDER BY i.due_at, i.intent_id
   LIMIT $1
     FOR UPDATE SKIP LOCKED
)
UPDATE vod_copy_intents i SET due_at = pg_catalog.now() + $2::interval
  FROM due WHERE i.intent_id = due.intent_id
RETURNING i.intent_id, i.stream_id, i.first_seq, i.last_seq, i.init_sessions, i.due_at`

// closeCopyIntentSQL ($1 기록 id) — 기록을 지운다. 없으면 아무것도 하지 않는다(멱등).
const closeCopyIntentSQL = `DELETE FROM vod_copy_intents WHERE intent_id = $1::bigint`

// OpenCopyIntent 는 범위 r 의 기록을 커밋하고 돌려준다. 기한은 DB 의 now() + dueAfter 다 — 부르는 쪽이 그 시도의
// 복사 예산 · 키 시한 · 유예를 더해 넘긴다. 발급 뒤에는 바꾸지 않는다.
func OpenCopyIntent(ctx context.Context, pool *pgxpool.Pool, r CopyIntentRange, dueAfter time.Duration) (CopyIntent, error) {
	qctx, cancel := context.WithTimeout(ctx, TxnDeadline)
	defer cancel()
	return openCopyIntent(qctx, pool, r, dueAfter)
}

func openCopyIntent(ctx context.Context, q rowQuerier, r CopyIntentRange, dueAfter time.Duration) (CopyIntent, error) {
	if err := r.validate(); err != nil {
		return CopyIntent{}, err
	}
	if dueAfter <= 0 {
		return CopyIntent{}, fmt.Errorf("index: 회수 기록 stream_id=%q 의 기한까지 기간이 양수가 아니다(%v)", r.StreamID, dueAfter)
	}
	sessions := r.InitSessions
	if sessions == nil {
		sessions = []string{} // nil 은 NULL 로 간다 — 열은 NOT NULL 이다
	}
	rows, err := q.Query(ctx, openCopyIntentSQL, r.StreamID, r.FirstSeq, r.LastSeq, sessions, dueAfter)
	if err != nil {
		return CopyIntent{}, fmt.Errorf("회수 기록 발급 실패 stream_id=%q: %w", r.StreamID, err)
	}
	c, err := pgx.CollectExactlyOneRow(rows, scanCopyIntent)
	if err != nil {
		return CopyIntent{}, fmt.Errorf("회수 기록 발급 실패 stream_id=%q: %w", r.StreamID, err)
	}
	return c, nil
}

// ClaimCopyIntents 는 기한이 지난 기록을 limit 개까지 잡아 기한을 지금 + CopyIntentLease 로 미루고 돌려준다. 잡은
// 쪽은 정리를 마치면 CloseCopyIntent 로 닫는다. 두 프로세스가 동시에 불러도 같은 기록을 나눠 갖지 않는다.
func ClaimCopyIntents(ctx context.Context, pool *pgxpool.Pool, limit int) ([]CopyIntent, error) {
	qctx, cancel := context.WithTimeout(ctx, TxnDeadline)
	defer cancel()
	return claimCopyIntents(qctx, pool, limit, CopyIntentLease)
}

func claimCopyIntents(ctx context.Context, q rowQuerier, limit int, lease time.Duration) ([]CopyIntent, error) {
	if limit <= 0 {
		return nil, fmt.Errorf("index: 회수 기록 쪽 크기가 양수가 아니다(%d)", limit)
	}
	rows, err := q.Query(ctx, claimCopyIntentsSQL, limit, lease)
	if err != nil {
		return nil, fmt.Errorf("회수 기록 잡기 실패: %w", err)
	}
	out, err := pgx.CollectRows(rows, scanCopyIntent)
	if err != nil {
		return nil, fmt.Errorf("회수 기록 잡기 실패: %w", err)
	}
	return out, nil
}

// CloseCopyIntent 는 기록 id 를 지운다. 이미 없으면 아무것도 하지 않는다.
func CloseCopyIntent(ctx context.Context, pool *pgxpool.Pool, id int64) error {
	qctx, cancel := context.WithTimeout(ctx, TxnDeadline)
	defer cancel()
	if _, err := pool.Exec(qctx, closeCopyIntentSQL, id); err != nil {
		return fmt.Errorf("회수 기록 닫기 실패 intent_id=%d: %w", id, err)
	}
	return nil
}

func scanCopyIntent(r pgx.CollectableRow) (CopyIntent, error) {
	var c CopyIntent
	err := r.Scan(&c.ID, &c.StreamID, &c.FirstSeq, &c.LastSeq, &c.InitSessions, &c.DueAt)
	return c, err
}
