package index

// 위상 잠금 · 소속 잠금(POK-196 M6-3b 커밋 ① — 설계 M6 3.3). 사슬 · 회차의 소속을 바꾸거나 여러 세션 행을 함께 쓰는
// 종료 문장(CloseChain · MarkChainEnded · CloseRound · EndLive)이 이 순서로 잠근다.
//
//	⓪ 상한 설정(setTxnLimitsSQL — lock_timeout 5초 · statement_timeout 10초)
//	① 위상 잠금(스트림별 advisory — 상한 설정 바로 다음 문장)
//	② 소속 잠금(소속 행 또는 사슬 닫힘의 꼬리 행 · FOR NO KEY UPDATE)
//	③ 함께 쓸 나머지 행(ORDER BY started_at, session_id FOR NO KEY UPDATE)
//
// session 의 4D(ApplyIntentionalEnd)가 같은 글자 · 같은 순서를 쓴다. index 는 session 을 임포트하지 않아 글자를 따로
// 들고, 두 원문은 finalize_static_test.go 가 대조한다.

import (
	"context"
	"errors"
	"fmt"
	"log/slog"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

// ErrTopologyContended 는 종료 문장이 잠금 상한 안에 서지 않았다는 뜻이다 — 위상 잠금 · ② · ③ 가운데 하나가
// lock_timeout(55P03)이나 문장 시한(57014)에 걸렸거나, 교착 검출(40P01) · 트랜잭션 시한에 끊겼거나, 소속 행이 재확인
// 상한 안에 멈추지 않았다. 아무것도 쓰지 않았다. 스캔 걸음은 다음 주기에 다시 하고 EndLive 는 기존 실패 갈래로 간다(D74).
var ErrTopologyContended = errors.New("index: 종료 문장의 잠금이 상한 안에 서지 않았다")

// topologyLockSQL 은 스트림별 위상 잠금이다 — session.topologyLockSQL 과 같은 글자다(이름 공간 pc_topology_ 는 컷오프
// 주조의 pc_cutoff_ 와 겹치지 않는다).
const topologyLockSQL = `SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext('pc_topology_' || $1::text)::bigint)`

// membershipSQL 은 소속 행 선택 규칙이다(3.3) — live 가 있으면 그 live, 없으면 started_at 최대(같으면 session_id 큰 쪽).
// COALESCE 는 구문이라 pg_catalog 로 한정하지 않는다.
const membershipSQL = `
SELECT COALESCE(
  (SELECT session_id FROM stream_sessions WHERE stream_id = $1 AND state = 'live'),
  (SELECT session_id FROM stream_sessions WHERE stream_id = $1 ORDER BY started_at DESC, session_id DESC LIMIT 1))`

// lockSessionSQL 은 세션 행 하나의 잠금이다 — 강도 FOR NO KEY UPDATE 가 계약이다(조각 INSERT 의 FK FOR KEY SHARE 와
// 충돌하지 않는다 · D71).
const lockSessionSQL = `SELECT 1 FROM stream_sessions WHERE session_id = $1 FOR NO KEY UPDATE`

// lockSessionsSQL 은 ③ 이다 — 세션 $1 들을 교착 방지 순서로 잠근다.
const lockSessionsSQL = `
SELECT session_id FROM stream_sessions WHERE session_id = ANY($1)
 ORDER BY started_at, session_id
   FOR NO KEY UPDATE`

// membershipTries 는 소속 잠금의 재확인 상한이다(3.3 — 넘기면 그 걸음은 실패다).
const membershipTries = 5

// lockMembership 은 스트림 소속 행을 잡고 새 문장으로 다시 고른다(3.3 분리 · 닫힘 쪽). 바뀌었으면 새 소속 행을 잡고
// 다시 본다. 세션이 없으면 "" 다.
func lockMembership(ctx context.Context, tx pgx.Tx, streamID string) (string, error) {
	locked := ""
	for range membershipTries {
		var cur *string
		if err := tx.QueryRow(ctx, membershipSQL, streamID).Scan(&cur); err != nil {
			return "", fmt.Errorf("소속 행 조회 실패 stream_id=%q: %w", streamID, err)
		}
		if cur == nil {
			return "", nil
		}
		if *cur == locked {
			return locked, nil
		}
		if _, err := tx.Exec(ctx, lockSessionSQL, *cur); err != nil {
			return "", fmt.Errorf("소속 잠금 실패 session_id=%q: %w", *cur, err)
		}
		locked = *cur
	}
	return "", fmt.Errorf("%w — 소속 행이 %d 번 안에 멈추지 않았다 stream_id=%q", ErrTopologyContended, membershipTries, streamID)
}

// lockSessions 는 ③ 이다 — 잠근 행 수를 돌려준다(없어진 행은 세지 않는다).
func lockSessions(ctx context.Context, tx pgx.Tx, ids []string) (int, error) {
	rows, err := tx.Query(ctx, lockSessionsSQL, ids)
	if err != nil {
		return 0, fmt.Errorf("세션 행 잠금 실패 %q: %w", ids, err)
	}
	n := 0
	for rows.Next() {
		n++
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, fmt.Errorf("세션 행 잠금 중 오류 %q: %w", ids, err)
	}
	return n, nil
}

// isContention 은 err 가 잠금 상한 · 교착 · 시한으로 끊긴 실패인가다.
func isContention(err error) bool {
	if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, ErrTopologyContended) {
		return true
	}
	var pgErr *pgconn.PgError
	if !errors.As(err, &pgErr) {
		return false
	}
	switch pgErr.Code {
	case pgLockNotAvailable, "57014", "40P01":
		return true
	}
	return false
}

// inTopologyTx 는 body 를 ⓪ 상한 설정 → ① 위상 잠금 아래에서 돌린다. body 가 commit 을 참으로 돌려줄 때만 확정하고,
// 거짓이면 되돌린다(쓴 것이 없거나 버려야 한다). 잠금 경합으로 끊기면 WARN topology_lock_timeout op stream 을 남기고
// ErrTopologyContended 를 감싼 오류다. ctx 시한은 트랜잭션 하나에 TxnDeadline 하나이고 되돌림은 새 시한을 받는다
// (endLive 와 같은 규약).
func inTopologyTx(ctx context.Context, pool *pgxpool.Pool, streamID, op string, log *slog.Logger,
	body func(ctx context.Context, tx pgx.Tx) (commit bool, err error)) error {
	err := topologyTx(ctx, pool, streamID, body)
	if err != nil && isContention(err) {
		logger(log).Warn("topology_lock_timeout", "op", op, "stream_id", streamID, "err", err)
		if !errors.Is(err, ErrTopologyContended) {
			err = fmt.Errorf("%w: %w", ErrTopologyContended, err)
		}
	}
	return err
}

func topologyTx(ctx context.Context, pool *pgxpool.Pool, streamID string,
	body func(ctx context.Context, tx pgx.Tx) (bool, error)) error {
	tctx, cancel := context.WithTimeout(ctx, TxnDeadline)
	defer cancel()
	tx, err := pool.Begin(tctx)
	if err != nil {
		return fmt.Errorf("종료 문장 트랜잭션 시작 실패 stream_id=%q: %w", streamID, err)
	}
	defer func() {
		rctx, rcancel := context.WithTimeout(context.WithoutCancel(ctx), TxnDeadline)
		defer rcancel()
		_ = tx.Rollback(rctx)
	}()
	if _, err := tx.Exec(tctx, setTxnLimitsSQL); err != nil {
		return fmt.Errorf("종료 문장 상한 설정 실패 stream_id=%q: %w", streamID, err)
	}
	if _, err := tx.Exec(tctx, topologyLockSQL, streamID); err != nil {
		return fmt.Errorf("위상 잠금 실패 stream_id=%q: %w", streamID, err)
	}
	commit, err := body(tctx, tx)
	if err != nil || !commit {
		return err
	}
	if err := tx.Commit(tctx); err != nil {
		return fmt.Errorf("종료 문장 확정 실패 stream_id=%q: %w", streamID, err)
	}
	return nil
}

// logger 는 nil 이면 slog.Default() 다.
func logger(log *slog.Logger) *slog.Logger {
	if log == nil {
		return slog.Default()
	}
	return log
}
