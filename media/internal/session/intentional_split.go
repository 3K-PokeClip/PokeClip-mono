package session

// 회차 분리(round_split — 설계 M6 3.4 효과 표 · 분리 가능 조건). 4D 가 새 송출 B 의 합류 뒤에 닿았을 때다.

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
)

// splitPlan 은 ③ 잠금 뒤 회차 행으로 정한 분리다.
type splitPlan struct {
	head    roundRow // B — 새 회차의 첫 세션(started_at ≥ StoppedAt 최소)
	moved   []string // M — R 의 세션 중 started_at ≥ B.started_at
	blocked bool     // 회차를 가로지르는 계승 간선의 한 끝이 닫힌 사슬(vod_state ≠ none)
	revoke  string   // B 가 R 에 남는 세션을 계승 중이면 B
}

// planSplit 은 분리를 정한다(DB 를 보지 않는다). rows 는 (started_at, session_id) 오름차순이다.
//
// 옮길 집합 M 은 소속 잠금 뒤 계산하므로 그 사이 열린 세션(TD 분할로 생긴 C 등)도 든다(D69 (나)). M 의 세션이 R 에
// 남는 세션을 계승하면(회차를 가로지르는 간선) 양 끝이 모두 vod_state='none' 이어야 한다 — 닫힌 사슬의 간선을
// 끊으면 옛 회차 VOD 에 새 회차 조각이 남는다(D64). 합류한 B 가 없으면(멈춘 뒤 시작한 세션이 없다) 나눌 자리가
// 없어 막힌다.
func planSplit(rows []roundRow, stoppedAt time.Time) splitPlan {
	headAt := -1
	for i, r := range rows {
		if !r.startedAt.Before(stoppedAt) {
			headAt = i
			break
		}
	}
	if headAt < 0 {
		return splitPlan{blocked: true}
	}
	p := splitPlan{head: rows[headAt]}
	remain := map[string]roundRow{}
	for _, r := range rows[:headAt] {
		remain[r.id] = r
	}
	for _, s := range rows[headAt:] {
		p.moved = append(p.moved, s.id)
		parent, crosses := remain[s.inherits]
		if !crosses {
			continue
		}
		if s.vodState != "none" || parent.vodState != "none" {
			p.blocked = true
		}
		if s.id == p.head.id {
			p.revoke = s.id
		}
	}
	return p
}

// moveRoundSQL 은 M 을 새 회차 B 로 옮기고 표식을 지운다(새 회차는 표식 없이 시작한다).
const moveRoundSQL = `
UPDATE stream_sessions SET round_id = $2, stop_marked_at = NULL
 WHERE session_id = ANY($1::text[])`

// closeRemainSQL 은 R 에 남는 세션을 닫는다 — round_closed_at 은 회차 전원 행에 같은 값이다(15.2 회차 닫힘).
const closeRemainSQL = `
UPDATE stream_sessions SET round_closed_at = pg_catalog.now()
 WHERE round_id = $1 AND round_closed_at IS NULL`

// splitRound 는 round_split 효과다. 분리 불가면 아무것도 쓰지 않는다(round_split_blocked — 「확실하지 않으면 적용하지
// 않는다」 · 한계 D64). 같은 트랜잭션 끝에서 B started(EnqueueStarted 칸)와 R 완결 평가(CloseRound 칸 — R 은 이미 닫혀
// 완결만 평가된다)를 부른다.
func splitRound(ctx context.Context, tx pgx.Tx, e IntentionalEnd, o IntentOptions, res IntentionalResult, rows []roundRow) (IntentionalResult, error) {
	p := planSplit(rows, e.StoppedAt)
	if p.blocked {
		res.Skip = skipRoundSplitBlocked
		return res, nil
	}
	if _, err := tx.Exec(ctx, moveRoundSQL, p.moved, p.head.id); err != nil {
		return res, fmt.Errorf("4D 회차 분리 실패 round_id=%q: %w", res.RoundID, err)
	}
	if _, err := tx.Exec(ctx, closeRemainSQL, res.RoundID); err != nil {
		return res, fmt.Errorf("4D 분리 뒤 회차 닫힘 실패 round_id=%q: %w", res.RoundID, err)
	}
	if err := o.EnqueueStarted(ctx, tx, p.head.id, e.StreamID, p.head.startedAt); err != nil {
		return res, fmt.Errorf("4D 새 회차 started 실패 round_id=%q: %w", p.head.id, err)
	}
	if err := o.CloseRound(ctx, tx, res.RoundID); err != nil {
		return res, fmt.Errorf("4D 분리 뒤 완결 평가 실패 round_id=%q: %w", res.RoundID, err)
	}
	res.Effect, res.RevokeSession = effectRoundSplit, p.revoke
	return res, nil
}
