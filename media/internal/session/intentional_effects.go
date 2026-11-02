package session

// 의도적 종료의 후보 판정과 효과 문장(설계 M6 3.4 후보 규칙 · 효과 표). 모두 applyIntent 의 소속 잠금 뒤에 돈다.

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
)

// intentCandidate 는 후보 T 다.
type intentCandidate struct {
	id, state, roundID string // roundID 는 NULL(M6 전)이면 ""
	roundClosed        bool
}

// intentCandidateSQL 은 후보 규칙이다(3.4) — 그 스트림의 세션 중 조각이 [ConnStartedAt − 여유, StoppedAt + 여유] 와
// 겹치고 started_at ≤ StoppedAt 인 것 가운데 started_at 최대. 연결 중간의 TD 분할 · init 불일치 분리로 열린 세션도
// 후보다(D10 — 「started_at ≤ ConnStartedAt + 여유」 로 좁히면 그 세션이 빠진다).
// 조각 겹침은 시작 ≤ 창 끝 ∧ 끝 ≥ 창 시작이다. duration_ms 는 registry previousSessionSQL 과 같은 까닭으로
// ::double precision 으로 맞춘다.
const intentCandidateSQL = `
SELECT s.session_id, s.state, s.round_id, s.round_closed_at IS NOT NULL
  FROM stream_sessions s
 WHERE s.stream_id = $1 AND s.started_at <= $2
   AND EXISTS (SELECT 1 FROM stream_segments g
                WHERE g.stream_id = s.stream_id AND g.session_id = s.session_id
                  AND g.start_wall_utc <= $4
                  AND g.start_wall_utc + g.duration_ms::double precision * interval '1 millisecond' >= $3)
 ORDER BY s.started_at DESC, s.session_id DESC
 LIMIT 1`

func findIntentCandidate(ctx context.Context, tx pgx.Tx, e IntentionalEnd, slack time.Duration) (intentCandidate, bool, error) {
	var (
		c       intentCandidate
		roundID *string
	)
	err := tx.QueryRow(ctx, intentCandidateSQL, e.StreamID, e.StoppedAt.UTC(),
		e.ConnStartedAt.Add(-slack).UTC(), e.StoppedAt.Add(slack).UTC()).Scan(&c.id, &c.state, &roundID, &c.roundClosed)
	if errors.Is(err, pgx.ErrNoRows) {
		return intentCandidate{}, false, nil
	}
	if err != nil {
		return intentCandidate{}, false, fmt.Errorf("4D 후보 조회 실패 stream_id=%q: %w", e.StreamID, err)
	}
	if roundID != nil {
		c.roundID = *roundID
	}
	return c, true, nil
}

// ambiguousSQL 은 ambiguous 판정이다(3.4) — T 와 다른 회차의 세션이 (StoppedAt − 여유, StoppedAt + 여유] 에 시작했다.
const ambiguousSQL = `
SELECT EXISTS (SELECT 1 FROM stream_sessions o
                WHERE o.stream_id = $1 AND o.round_id IS DISTINCT FROM $2::text
                  AND o.started_at > $3 AND o.started_at <= $4)`

func otherRoundStartedNear(ctx context.Context, tx pgx.Tx, e IntentionalEnd, roundID string, slack time.Duration) (bool, error) {
	var amb bool
	if err := tx.QueryRow(ctx, ambiguousSQL, e.StreamID, nullableText(roundID),
		e.StoppedAt.Add(-slack).UTC(), e.StoppedAt.Add(slack).UTC()).Scan(&amb); err != nil {
		return false, fmt.Errorf("4D 모호 판정 실패 stream_id=%q: %w", e.StreamID, err)
	}
	return amb, nil
}

// nullableText 는 "" 를 SQL NULL 로 보낸다.
func nullableText(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

// markSQL 은 marked 효과다 — 첫 표식만 남긴다(COALESCE · 늦게 온 두 번째 4D 가 덮지 않는다).
const markSQL = `
UPDATE stream_sessions SET stop_marked_at = COALESCE(stop_marked_at, $2)
 WHERE session_id = $1 AND state = 'live'`

func markLive(ctx context.Context, tx pgx.Tx, e IntentionalEnd, res IntentionalResult) (IntentionalResult, error) {
	tag, err := tx.Exec(ctx, markSQL, res.SessionID, e.StoppedAt.UTC())
	if err != nil {
		return res, fmt.Errorf("4D 표식 실패 session_id=%q: %w", res.SessionID, err)
	}
	if tag.RowsAffected() != 1 {
		// T 는 소속 행(live)이라 잠금 아래에서 상태가 바뀔 수 없다 — 바뀌었다면 잠금 계약이 깨진 것이다.
		return res, fmt.Errorf("4D 표식이 %d 행을 썼다 session_id=%q — 소속 잠금 아래 live 가 사라졌다", tag.RowsAffected(), res.SessionID)
	}
	res.Effect = effectMarked
	return res, nil
}

// roundRow 는 회차 R 의 한 행이다(③ 잠금 뒤 값).
type roundRow struct {
	id, state, vodState, inherits string
	startedAt                     time.Time
}

// lockRoundRowsSQL 은 ③ — 회차 R 의 전원 행을 started_at, session_id 순으로 잠근다(교착 방지 순서 · 설계 3.3).
// round_closed_at IS NULL 은 stream_sessions_round_open_idx(부분 인덱스)로 가려는 술어다 — R 은 안 닫혔음을 이미 봤다.
const lockRoundRowsSQL = `
SELECT session_id, state, vod_state, inherits_session, started_at
  FROM stream_sessions
 WHERE round_id = $1 AND round_closed_at IS NULL
 ORDER BY started_at, session_id
   FOR NO KEY UPDATE`

func lockRoundRows(ctx context.Context, tx pgx.Tx, roundID string) ([]roundRow, error) {
	rows, err := tx.Query(ctx, lockRoundRowsSQL, roundID)
	if err != nil {
		return nil, fmt.Errorf("회차 행 잠금 실패 round_id=%q: %w", roundID, err)
	}
	out, err := pgx.CollectRows(rows, func(r pgx.CollectableRow) (roundRow, error) {
		var (
			row      roundRow
			inherits *string
		)
		err := r.Scan(&row.id, &row.state, &row.vodState, &inherits, &row.startedAt)
		if inherits != nil {
			row.inherits = *inherits
		}
		return row, err
	})
	if err != nil {
		return nil, fmt.Errorf("회차 행 읽기 실패 round_id=%q: %w", roundID, err)
	}
	return out, nil
}

// latestInRound 는 회차 안 선택 규칙이다(3.4 · 3.3) — R 에 live 가 있으면 그 live, 없으면 started_at 최대(같으면 session_id 큰 쪽).
func latestInRound(rows []roundRow) string {
	latest := -1
	for i, r := range rows {
		if r.state == "live" {
			return r.id
		}
		if latest < 0 || !r.startedAt.Before(rows[latest].startedAt) {
			latest = i // rows 는 (started_at, session_id) 오름차순이라 뒤가 이긴다
		}
	}
	if latest < 0 {
		return ""
	}
	return rows[latest].id
}

// applyToEndedRound 는 끝난 T(ending · ended)의 효과다 — T 가 R 의 최신이면 upgraded, 아니면 round_split 이나 미적용.
func applyToEndedRound(ctx context.Context, tx pgx.Tx, e IntentionalEnd, o IntentOptions, res IntentionalResult, rows []roundRow) (IntentionalResult, error) {
	if latestInRound(rows) == res.SessionID {
		return upgradeLatest(ctx, tx, e, o, res)
	}
	return splitRound(ctx, tx, e, o, res, rows)
}

// upgradeSQL 은 upgraded 효과다 — 사유만 intentional 로 바꾸고 표식을 쓴다(ended 의 동결 · VOD 는 그대로).
const upgradeSQL = `
UPDATE stream_sessions SET end_reason = 'intentional', stop_marked_at = COALESCE(stop_marked_at, $2)
 WHERE session_id = $1 AND state IN ('ending', 'ended')`

func upgradeLatest(ctx context.Context, tx pgx.Tx, e IntentionalEnd, o IntentOptions, res IntentionalResult) (IntentionalResult, error) {
	if _, err := tx.Exec(ctx, upgradeSQL, res.SessionID, e.StoppedAt.UTC()); err != nil {
		return res, fmt.Errorf("4D 승격 실패 session_id=%q: %w", res.SessionID, err)
	}
	if err := o.CloseRound(ctx, tx, res.RoundID); err != nil {
		return res, fmt.Errorf("4D 승격 뒤 회차 닫힘 실패 round_id=%q: %w", res.RoundID, err)
	}
	res.Effect = effectUpgraded
	return res, nil
}
