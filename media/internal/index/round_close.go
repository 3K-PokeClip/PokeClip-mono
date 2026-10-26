package index

// 회차 닫힘(POK-196 M6-3b 커밋 ① — 설계 M6 15.2 회차 닫힘 · 3.3 소속 잠금).
//
// 회차는 최신 세션(회차 안 선택 규칙 — live 가 있으면 그 live, 없으면 started_at 최대)이 끝났고 (intentional ∨ 멈춘 시각
// 표식 ∨ 회차 마지막 조각 끝에서 300초 + 60초)일 때 닫힌다. round_closed_at 은 회차 전원 행에 같은 값이다 — 개시 판정이
// 읽는 직전 회차(소속 행)에서 바로 보이게 한다. 구동은 둘이다: 같은 트랜잭션 안 평가(TryCloseRound — phase 4 · 4D
// upgraded · 표식 뒤 EndLive)와 30초 회차 스캔(ListClosableRounds → CloseRound — 시간 조건).

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// ClosableRound 는 회차 스캔 한 행이다 — 닫힘 조건이 지금 참인 열린 회차만 온다. Now 는 그 문장의 pg_catalog.now() 다.
type ClosableRound struct {
	RoundID, StreamID, LatestSessionID, LatestState, LatestEndReason string
	// LastEnd 는 회차 마지막 조각의 끝이다(조각이 없으면 회차 세션의 마지막 ending_at). LatestStopMarkedAt 은 NULL 이면 영값이다.
	LastEnd, LatestStopMarkedAt, Now time.Time
}

// closableRoundsSQL 은 열린 회차의 최신 세션(부분 인덱스 stream_sessions_round_open_idx)을 회차마다 하나 고르고 닫힘 조건을
// WHERE 에서 평가한다 — $1 쪽 크기 · $2 닫힘 시한(300초 + 60초) · $3 회차 하나로 좁힘(NULL 이면 전부).
//
// 회차 마지막 조각 끝은 구성원마다 마지막 seq 한 행(LIMIT 1 LATERAL — stream_segments_session_idx)의 끝 가운데 최대다.
// 조각이 없는 회차(구조상 드묾)는 구성원의 마지막 ending_at 이다. 최신이 live 인 회차는 닫히지 않는다 — 회차에 live 가
// 하나라도 있으면 선택 규칙이 그 live 를 고른다. 최신이 ended(사슬이 먼저 끝남 — D59)여도 닫힌다.
const closableRoundsSQL = `
WITH latest AS (
  SELECT DISTINCT ON (s.round_id) s.round_id, s.stream_id, s.session_id, s.state, s.end_reason, s.stop_marked_at
    FROM stream_sessions s
   WHERE s.round_closed_at IS NULL AND s.round_id IS NOT NULL AND ($3::text IS NULL OR s.round_id = $3)
   ORDER BY s.round_id, (s.state = 'live') DESC, s.started_at DESC, s.session_id DESC
)
SELECT l.round_id, l.stream_id, l.session_id, l.state, COALESCE(l.end_reason, ''), le.last_end, l.stop_marked_at,
       pg_catalog.now()
  FROM latest l
  CROSS JOIN LATERAL (
       SELECT COALESCE(pg_catalog.max(x.seg_end), pg_catalog.max(m.ending_at)) AS last_end
         FROM stream_sessions m
         LEFT JOIN LATERAL (
              SELECT g.start_wall_utc + g.duration_ms::double precision * interval '1 millisecond' AS seg_end
                FROM stream_segments g
               WHERE g.stream_id = m.stream_id AND g.session_id = m.session_id
               ORDER BY g.seq DESC LIMIT 1) x ON true
        WHERE m.round_id = l.round_id AND m.round_closed_at IS NULL) le
 WHERE l.state <> 'live'
   AND (l.end_reason = 'intentional' OR l.stop_marked_at IS NOT NULL OR pg_catalog.now() - le.last_end > $2::interval)
 ORDER BY l.round_id
 LIMIT $1`

// ListClosableRounds 는 round_closed_at IS NULL 인 회차 가운데 닫힘 조건이 지금 참인 회차를 limit 개 고른다. 커서가 없다 —
// 고른 회차는 그 걸음에서 닫혀 다음 스캔에서 빠지므로 쪽이 가득 차도 매 스캔 진행한다.
func ListClosableRounds(ctx context.Context, pool *pgxpool.Pool, limit int, o CloseOptions) ([]ClosableRound, error) {
	if limit <= 0 {
		return nil, fmt.Errorf("index: 회차 스캔 쪽 크기가 양수가 아니다(%d)", limit)
	}
	qctx, cancel := context.WithTimeout(ctx, TxnDeadline)
	defer cancel()
	rows, err := pool.Query(qctx, closableRoundsSQL, limit, o.closeAfter(), nil)
	if err != nil {
		return nil, fmt.Errorf("회차 스캔 실패: %w", err)
	}
	out, err := pgx.CollectRows(rows, scanClosableRound)
	if err != nil {
		return nil, fmt.Errorf("회차 스캔 읽기 실패: %w", err)
	}
	return out, nil
}

func scanClosableRound(r pgx.CollectableRow) (ClosableRound, error) {
	var (
		c      ClosableRound
		marked *time.Time
	)
	err := r.Scan(&c.RoundID, &c.StreamID, &c.LatestSessionID, &c.LatestState, &c.LatestEndReason, &c.LastEnd, &marked, &c.Now)
	if marked != nil {
		c.LatestStopMarkedAt = *marked
	}
	return c, err
}

// CloseRound 는 회차 스캔 한 행 r 을 닫는다 — 상한 설정 → 위상 잠금 아래에서 TryCloseRound 를 부른다(소속 잠금 뒤 조건을
// 새 문장으로 다시 보고 닫고, 같은 트랜잭션에서 완결을 평가한다). 닫았으면 참이다. 부른 쪽은 커밋 뒤
// PlaybackChanged(r.StreamID) 를 부른다.
func CloseRound(ctx context.Context, pool *pgxpool.Pool, r ClosableRound, o CloseOptions, enqueue EndedEnqueuer) (bool, error) {
	if enqueue == nil {
		return false, errNoEnqueuer
	}
	var closed bool
	err := inTopologyTx(ctx, pool, r.StreamID, "close_round", o.Log, func(ctx context.Context, tx pgx.Tx) (bool, error) {
		var err error
		closed, err = TryCloseRound(ctx, tx, r.RoundID, o, enqueue)
		return true, err
	})
	if err != nil {
		return false, err
	}
	return closed, nil
}

// roundStreamSQL 은 회차의 스트림이다(소속 잠금 열쇠).
const roundStreamSQL = `SELECT stream_id FROM stream_sessions WHERE round_id = $1 LIMIT 1`

// lockRoundRowsSQL 은 ③ — 열린 회차 $1 의 전원 행을 교착 방지 순서로 잠근다.
const lockRoundRowsSQL = `
SELECT session_id FROM stream_sessions
 WHERE round_id = $1 AND round_closed_at IS NULL
 ORDER BY started_at, session_id
   FOR NO KEY UPDATE`

// closeRoundSQL 은 회차 닫힘이다 — 회차 전원 행에 같은 round_closed_at 을 쓴다(session closeRemainSQL 과 같은 글자).
const closeRoundSQL = `
UPDATE stream_sessions SET round_closed_at = pg_catalog.now()
 WHERE round_id = $1 AND round_closed_at IS NULL`

// TryCloseRound 는 트랜잭션 tx 안에서 회차 round 의 닫힘 조건을 평가해 참이면 닫고, 닫았든 아니든 끝에서 완결 평가
// (enqueue)를 부른다 — 이미 닫힌 회차(4D round_split 이 R 을 직접 닫은 뒤)는 완결 평가만 된다. 닫았으면 참이다.
//
// 부른 쪽이 위상 잠금을 쥐고 있어야 한다(phase 4 · CloseRound · 4D · 표식 뒤 EndLive). 잠금은 ② 소속 행(새 문장으로
// 재확인) → 조건 재평가(같은 트랜잭션의 새 문장 — 소속 잠금 전에 본 값을 쓰지 않는다) → ③ 회차 전원 행 순이다. 개시가 같은
// 소속 행을 잡으므로 「닫힌 회차에 합류」와 「합류가 생긴 회차를 닫음」이 함께 일어나지 않는다(D43 · D69).
func TryCloseRound(ctx context.Context, tx pgx.Tx, round string, o CloseOptions, enqueue EndedEnqueuer) (bool, error) {
	if enqueue == nil {
		return false, errNoEnqueuer
	}
	due, err := lockRoundIfDue(ctx, tx, round, o)
	if err != nil {
		return false, err
	}
	closed := false
	if due {
		tag, err := tx.Exec(ctx, closeRoundSQL, round)
		if err != nil {
			return false, fmt.Errorf("회차 닫힘 실패 round_id=%q: %w", round, err)
		}
		closed = tag.RowsAffected() > 0
	}
	if err := enqueue(ctx, tx, round); err != nil {
		return false, fmt.Errorf("회차 완결 평가 실패 round_id=%q: %w", round, err)
	}
	return closed, nil
}

// lockRoundIfDue 는 TryCloseRound 의 판정 몫이다 — ② 소속 잠금 뒤 닫힘 조건을 새 문장으로 보고, 참이면 ③ 회차 전원 행을
// 잠그고 참을 돌려준다. 회차가 없거나 이미 닫혔거나 조건이 거짓이면 거짓이다.
func lockRoundIfDue(ctx context.Context, tx pgx.Tx, round string, o CloseOptions) (bool, error) {
	var streamID string
	err := tx.QueryRow(ctx, roundStreamSQL, round).Scan(&streamID)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, fmt.Errorf("회차 스트림 조회 실패 round_id=%q: %w", round, err)
	}
	if _, err := lockMembership(ctx, tx, streamID); err != nil {
		return false, err
	}
	rows, err := tx.Query(ctx, closableRoundsSQL, 1, o.closeAfter(), round)
	if err != nil {
		return false, fmt.Errorf("회차 닫힘 조건 평가 실패 round_id=%q: %w", round, err)
	}
	due, err := pgx.CollectRows(rows, scanClosableRound)
	if err != nil {
		return false, fmt.Errorf("회차 닫힘 조건 읽기 실패 round_id=%q: %w", round, err)
	}
	if len(due) == 0 {
		return false, nil
	}
	if _, err := tx.Exec(ctx, lockRoundRowsSQL, round); err != nil {
		return false, fmt.Errorf("회차 행 잠금 실패 round_id=%q: %w", round, err)
	}
	return true, nil
}
