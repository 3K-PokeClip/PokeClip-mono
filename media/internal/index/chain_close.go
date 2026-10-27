package index

// 사슬 닫힘 · phase 4 · 꺼짐 빈 동결 · VOD 실패 확정(POK-196 M6-3b 커밋 ① — 설계 M6 2.2 · 3.1 · 3.2 꺼짐 갈래 · 3.3 ·
// 종결 정정 D75).
//
// 사슬 닫힘은 사슬 전원의 vod_state 를 none → freezing 으로 바꾸는 한 문장이다(DDL 변경 0 — 4절 「사슬 닫힘 표식」). 그 뒤
// 사슬의 구성은 바뀌지 않는다: 개시는 vod_state='none' 인 꼬리만 계승하고(session inheritable), 4D 분리는 닫힌 사슬 안을
// 가르지 않으며(round_split_blocked), init 계승 해제는 vod_state='none' 일 때만 간선을 끊는다(markInitUploadedSQL).

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"slices"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// EndedEnqueuer 는 회차 완결 평가다 — 「닫힘 ∧ 전원 ended」 이면 계약9 ended outbox 행을 넣는다. 조립(cmd)이
// broadcast.EnqueueEndedIfComplete 를 게이트와 묶어 끼운다(index 는 broadcast 를 임포트하지 않는다). round_closed_at 을 쓰거나
// 회차 세션을 ended 로 바꾸는 트랜잭션이 끝에서 부른다(15.2 완결 평가 규칙 — 정적 단언 finalize_static_test.go).
type EndedEnqueuer func(ctx context.Context, tx pgx.Tx, round string) error

// errNoEnqueuer 는 완결 평가 칸이 비었다는 조립 결함이다 — 쓰기 전에 거부한다(nil 호출 panic 대신).
var errNoEnqueuer = errors.New("index: 완결 평가 칸(EndedEnqueuer)이 비었다")

// sessionStreamSQL 은 세션의 스트림이다 — stream_id 는 바뀌지 않는 열이라 잠금 전에 읽어도 된다(위상 잠금 열쇠).
const sessionStreamSQL = `SELECT stream_id FROM stream_sessions WHERE session_id = $1`

// chainMembersReadySQL 은 ③ 뒤 사슬 전원이 닫을 수 있는 상태인가다 — ending · vod none · dvr 종국 · init 판정 끝
// ($2 정산 창). 행이 사슬 수보다 적으면(없어진 행) 거짓이다.
const chainMembersReadySQL = `
SELECT pg_catalog.count(*) = pg_catalog.cardinality($1::text[])
   AND COALESCE(pg_catalog.bool_and(state = 'ending' AND vod_state = 'none'
                    AND dvr_state IN ('frozen', 'freeze_failed')
                    AND (init_uploaded_at IS NOT NULL OR pg_catalog.now() >= ending_at + $2::interval)), false)
  FROM stream_sessions WHERE session_id = ANY($1)`

// closeChainSQL 은 사슬 닫힘 표식이다 — 사슬 전원을 freezing 으로. 바꾼 행 수가 사슬 수와 다르면 되돌린다.
const closeChainSQL = `
UPDATE stream_sessions SET vod_state = 'freezing'
 WHERE session_id = ANY($1) AND vod_state = 'none'`

// CloseChain 은 꼬리 tail 의 사슬을 닫는다. 잠금 순서(3.3): 상한 설정 → 위상 잠금 → ② 꼬리 → (꼬리 판독 · 후속 ·
// 사슬 계산) → ③ 사슬 행 → 사슬 재계산 대조 → 꼬리 재판독 · 구성원 재확인 → vod_state 'none' → 'freezing'.
//
// 닫을 수 없으면(창 안 · 후속 생김 · init 판정 전 · dvr 종국 아님 · 재계산 집합이 다름 · 갱신 행 수가 사슬 수와 다름)
// 아무것도 쓰지 않고 ok=false 다 — 다음 스캔에 다시. 재계산이 다르면 INFO session_chain_recheck_failed(D75 (가) 의 실제
// 발생 표지)다. ③ 뒤에는 계승 해제(InitMarkRevoked)가 그 행을 기다리므로 두 번째 계산이 최종이다.
func CloseChain(ctx context.Context, pool *pgxpool.Pool, tail string, o CloseOptions) (c Chain, ok bool, err error) {
	streamID, found, err := streamOfSession(ctx, pool, tail)
	if err != nil || !found {
		return Chain{}, false, err
	}
	err = inTopologyTx(ctx, pool, streamID, "close_chain", o.Log, func(ctx context.Context, tx pgx.Tx) (bool, error) {
		c, ok, err = closeChainTx(ctx, tx, streamID, tail, o)
		return ok, err
	})
	if err != nil {
		return Chain{}, false, err
	}
	if ok {
		logger(o.Log).Info("session_chain_closed", "stream_id", streamID, "tail", tail, "round", c.RoundID,
			"sessions", len(c.SessionIDs))
	}
	return c, ok, nil
}

// closeChainTx 는 CloseChain 의 위상 잠금 아래 몸체다.
func closeChainTx(ctx context.Context, tx pgx.Tx, streamID, tail string, o CloseOptions) (Chain, bool, error) {
	if _, err := tx.Exec(ctx, lockSessionSQL, tail); err != nil {
		return Chain{}, false, fmt.Errorf("사슬 꼬리 잠금 실패 session_id=%q: %w", tail, err)
	}
	if ready, err := tailClosable(ctx, tx, tail, o); err != nil || !ready {
		return Chain{}, false, err
	}
	first, err := readChain(ctx, tx, streamID, tail)
	if err != nil {
		return Chain{}, false, err
	}
	if _, err := lockSessions(ctx, tx, first.SessionIDs); err != nil {
		return Chain{}, false, err
	}
	again, err := readChain(ctx, tx, streamID, tail)
	if err != nil {
		return Chain{}, false, err
	}
	if !slices.Equal(first.SessionIDs, again.SessionIDs) {
		logger(o.Log).Info("session_chain_recheck_failed", "stream_id", streamID, "tail", tail,
			"before", first.SessionIDs, "after", again.SessionIDs)
		return Chain{}, false, nil
	}
	if ready, err := tailClosable(ctx, tx, tail, o); err != nil || !ready {
		return Chain{}, false, err
	}
	var membersReady bool
	if err := tx.QueryRow(ctx, chainMembersReadySQL, again.SessionIDs, SettleWindow).Scan(&membersReady); err != nil {
		return Chain{}, false, fmt.Errorf("사슬 구성원 확인 실패 tail=%q: %w", tail, err)
	}
	if !membersReady {
		return Chain{}, false, nil
	}
	tag, err := tx.Exec(ctx, closeChainSQL, again.SessionIDs)
	if err != nil {
		return Chain{}, false, fmt.Errorf("사슬 닫힘 실패 tail=%q: %w", tail, err)
	}
	if tag.RowsAffected() != int64(len(again.SessionIDs)) {
		return Chain{}, false, nil
	}
	return again, true, nil
}

// tailClosable 은 꼬리를 판독식으로 다시 읽어 지금 닫을 수 있는가다 — ending · dvr 종국 · vod none · 후속 없음 · init
// 판정 끝 · 닫을 때(ChainCloseDue). 같은 트랜잭션의 새 문장이라 앞 잠금 뒤의 커밋된 판을 본다.
func tailClosable(ctx context.Context, tx pgx.Tx, tail string, o CloseOptions) (bool, error) {
	s, found, err := readEndingRow(ctx, tx, tail)
	if err != nil || !found {
		return false, err
	}
	dvrDone := s.DVRState == "frozen" || s.DVRState == "freeze_failed"
	return dvrDone && s.VODState == "none" && !s.HasSuccessor && s.InitSettled && s.ChainCloseDue(o.closeAfter()), nil
}

// streamOfSession 은 세션의 스트림이다. 없으면 found=false 다.
func streamOfSession(ctx context.Context, pool *pgxpool.Pool, sessionID string) (string, bool, error) {
	qctx, cancel := context.WithTimeout(ctx, TxnDeadline)
	defer cancel()
	var streamID string
	err := pool.QueryRow(qctx, sessionStreamSQL, sessionID).Scan(&streamID)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", false, nil
	}
	if err != nil {
		return "", false, fmt.Errorf("세션 스트림 조회 실패 session_id=%q: %w", sessionID, err)
	}
	return streamID, true, nil
}

// LoadChain 은 꼬리 tail(스트림 streamID)의 사슬 값만 읽는다(사슬 간선 규칙 — 렌더 행은 읽지 않는다). 꼬리가 그 스트림
// 장부에 없으면 ErrChainNotFound 다. 동결 걸음(FreezeVOD · phase 4)이 정산 스캔 행에서 사슬을 얻는다.
func LoadChain(ctx context.Context, pool *pgxpool.Pool, streamID, tail string) (Chain, error) {
	qctx, cancel := context.WithTimeout(ctx, TxnDeadline)
	defer cancel()
	var c Chain
	err := pgx.BeginTxFunc(qctx, pool, pgx.TxOptions{AccessMode: pgx.ReadOnly}, func(tx pgx.Tx) error {
		var err error
		c, err = readChain(qctx, tx, streamID, tail)
		return err
	})
	return c, err
}

// chainStatesSQL 은 ③ 뒤 사슬 행의 상태와 회차다(phase 4 판정 · 완결 평가 회차).
const chainStatesSQL = `
SELECT session_id, state, dvr_state, vod_state, COALESCE(round_id, '')
  FROM stream_sessions WHERE stream_id = $1 AND session_id = ANY($2)`

// markEndedSQL 은 phase 4 다 — 사슬 전원을 ended 로. 사슬 종료이지 계약9 ended 가 아니다(그것은 완결 평가).
const markEndedSQL = `
UPDATE stream_sessions SET state = 'ended', ended_at = pg_catalog.now()
 WHERE stream_id = $1 AND session_id = ANY($2) AND state = 'ending'`

// MarkChainEnded 는 사슬 phase 4 다(위상 잠금 선행). 사슬 전원이 dvr · vod 종국일 때만 state='ended', ended_at=now() 를
// 쓰고 바꾼 행 수를 돌려준다(아니면 0 · 쓰지 않음). 완결 평가할 회차는 Chain 이나 스캔 행의 값이 아니라 잠금 뒤 사슬
// 꼬리 행에서 다시 읽은 round_id 다 — 늦은 4D 분리가 동결 중인 사슬을 통째로 옮겼을 수 있다(D55 다섯째 갈래). 같은
// 트랜잭션 끝에서 그 회차로 TryCloseRound(닫힘 조건 평가 + 완결 평가)를 부른다. 잠금 순서는 상한 설정 → 위상 잠금 →
// ② 소속 행 → ③ 사슬 행이다.
func MarkChainEnded(ctx context.Context, pool *pgxpool.Pool, c Chain, o CloseOptions, enqueue EndedEnqueuer) (int64, error) {
	if enqueue == nil {
		return 0, errNoEnqueuer
	}
	if len(c.SessionIDs) == 0 {
		return 0, fmt.Errorf("index: phase 4 사슬 세션 목록이 비었다 tail=%q", c.Tail)
	}
	var (
		ended int64
		round string
	)
	err := inTopologyTx(ctx, pool, c.StreamID, "mark_ended", o.Log, func(ctx context.Context, tx pgx.Tx) (bool, error) {
		if _, err := lockMembership(ctx, tx, c.StreamID); err != nil {
			return false, err
		}
		if _, err := lockSessions(ctx, tx, c.SessionIDs); err != nil {
			return false, err
		}
		var done bool
		var err error
		if done, round, err = chainTerminal(ctx, tx, c); err != nil || !done {
			return false, err
		}
		tag, err := tx.Exec(ctx, markEndedSQL, c.StreamID, c.SessionIDs)
		if err != nil {
			return false, fmt.Errorf("phase 4 실패 tail=%q: %w", c.Tail, err)
		}
		if ended = tag.RowsAffected(); ended == 0 {
			return false, nil
		}
		if round != "" {
			if _, err := TryCloseRound(ctx, tx, round, o, enqueue); err != nil {
				return false, err
			}
		}
		return true, nil
	})
	if err != nil {
		return 0, err
	}
	if ended > 0 {
		logger(o.Log).Info("session_ended", "stream_id", c.StreamID, "tail", c.Tail, "round", round, "sessions", ended)
	}
	return ended, nil
}

// chainTerminal 은 ③ 뒤 사슬 전원이 dvr · vod 종국인가와 꼬리의 회차(다시 읽은 값)다. 행이 사슬 수보다 적으면 거짓이다.
func chainTerminal(ctx context.Context, tx pgx.Tx, c Chain) (bool, string, error) {
	rows, err := tx.Query(ctx, chainStatesSQL, c.StreamID, c.SessionIDs)
	if err != nil {
		return false, "", fmt.Errorf("phase 4 사슬 상태 조회 실패 tail=%q: %w", c.Tail, err)
	}
	defer rows.Close()
	n, done, round := 0, true, ""
	for rows.Next() {
		var id, state, dvr, vod, r string
		if err := rows.Scan(&id, &state, &dvr, &vod, &r); err != nil {
			return false, "", fmt.Errorf("phase 4 사슬 상태 읽기 실패 tail=%q: %w", c.Tail, err)
		}
		n++
		done = done && (dvr == "frozen" || dvr == "freeze_failed") &&
			(vod == "frozen" || vod == "frozen_with_gaps" || vod == "freeze_failed")
		if id == c.Tail {
			round = r
		}
	}
	if err := rows.Err(); err != nil {
		return false, "", fmt.Errorf("phase 4 사슬 상태 조회 중 오류 tail=%q: %w", c.Tail, err)
	}
	return done && n == len(c.SessionIDs), round, nil
}

// freezeEmptyDVRSQL 은 꺼짐(S3_BUCKET 빈 값) 빈 동결이다 — 발행 흔적(manifest_etag · ③ uploaded)이 없을 때만 PUT 없이
// DVR 을 닫는다(3.2 · D72). 흔적 조건은 finalize 의 보류와 겹 방어다 — 버킷 있던 판의 /dvr 판 · ③ 객체가 S3 에 남아 있는
// 세션을 빈 동결로 닫으면 다시보기가 영구히 없다. fence 를 쓰지 않는다(꺼짐에는 발행자가 없다).
const freezeEmptyDVRSQL = `
UPDATE stream_sessions s
   SET dvr_state = 'frozen', dvr_freezing_at = COALESCE(s.dvr_freezing_at, pg_catalog.now()), dvr_frozen_at = pg_catalog.now()
 WHERE s.session_id = $1 AND s.state = 'ending' AND s.dvr_state IN ('open', 'freezing') AND s.manifest_etag IS NULL
   AND NOT EXISTS (SELECT 1 FROM stream_segments g
                    WHERE g.stream_id = s.stream_id AND g.session_id = s.session_id
                      AND g.playback_upload_state = 'uploaded')`

// FreezeEmptyDVR 는 꺼짐 갈래의 DVR 빈 동결이다. 닫았으면 참이다 — 흔적이 있거나 이미 닫혔으면 쓰지 않고 거짓이다. 잠금
// 상한 · 경합 접기는 limitedTx 다(op freeze_empty_dvr).
func FreezeEmptyDVR(ctx context.Context, pool *pgxpool.Pool, sessionID string, o CloseOptions) (bool, error) {
	var n int64
	err := limitedTx(ctx, pool, "freeze_empty_dvr", sessionID, o.Log, func(ctx context.Context, tx pgx.Tx) error {
		tag, err := tx.Exec(ctx, freezeEmptyDVRSQL, sessionID)
		n = tag.RowsAffected()
		return err
	})
	if err != nil {
		return false, fmt.Errorf("꺼짐 DVR 빈 동결 실패 session_id=%q: %w", sessionID, err)
	}
	return n == 1, nil
}

// limitedTx 는 정산 보조 문장 body 를 한 트랜잭션에서 트랜잭션 지역 상한(setTxnLimitsSQL — lock_timeout 5초 ·
// statement_timeout 10초) 아래 돌린다(c3 db M2). 상한이 없으면 행 잠금 대기가 ctx 시한(TxnDeadline)까지 연결을 쥔다.
// 잠금 경합 · 시한으로 끊기면 WARN topology_lock_timeout op 를 남기고 ErrTopologyContended 로 감싼다 — 부르는 쪽(정산
// 걸음)이 inTopologyTx 의 경합과 같이 다룬다. 위상 잠금은 잡지 않는다 — 셋 다 회차 · 사슬의 상태 칸만 쓰고 소속을
// 바꾸지 않는다.
func limitedTx(ctx context.Context, pool *pgxpool.Pool, op, id string, log *slog.Logger, body func(ctx context.Context, tx pgx.Tx) error) error {
	qctx, cancel := context.WithTimeout(ctx, TxnDeadline)
	defer cancel()
	err := pgx.BeginFunc(qctx, pool, func(tx pgx.Tx) error {
		if _, err := tx.Exec(qctx, setTxnLimitsSQL); err != nil {
			return err
		}
		return body(qctx, tx)
	})
	if err != nil && isContention(err) {
		logger(log).Warn("topology_lock_timeout", "op", op, "id", id, "err", err)
		if !errors.Is(err, ErrTopologyContended) {
			err = fmt.Errorf("%w: %w", ErrTopologyContended, err)
		}
	}
	return err
}

// freezeEmptyVODSQL 은 꺼짐 VOD 빈 동결이다 — 사슬 어느 구성원에도 ③ uploaded 가 없을 때만 사슬 전원을 frozen(문서 없음)
// 으로 닫는다. 업로더도 꺼져 ③ 이 오지 않으므로 정산 창을 기다리지 않는다(3.2 꺼짐 행).
const freezeEmptyVODSQL = `
WITH upd AS (
    UPDATE stream_sessions SET vod_state = 'frozen'
     WHERE stream_id = $1 AND session_id = ANY($2) AND vod_state = 'freezing'
       AND NOT EXISTS (SELECT 1 FROM stream_segments g
                        WHERE g.stream_id = $1 AND g.session_id = ANY($2) AND g.playback_upload_state = 'uploaded')
    RETURNING 1
)
SELECT pg_catalog.count(*) FROM upd`

// FreezeEmptyVOD 는 꺼짐 갈래의 VOD 빈 동결이다. 사슬 전원을 닫았으면 참이다 — ③ 흔적이 있거나 이미 종국이면 쓰지 않고
// 거짓이다(사슬 일부만 freezing 이면 되돌린다).
func FreezeEmptyVOD(ctx context.Context, pool *pgxpool.Pool, c Chain, o CloseOptions) (bool, error) {
	return settleChainVOD(ctx, pool, freezeEmptyVODSQL, "freeze_empty_vod", "꺼짐 VOD 빈 동결", c, o)
}

// failChainVODSQL 은 VOD 실패 확정이다 — 4초 × 3 실패(또는 같은 입력으로 다시 해도 서지 않는 실패) 뒤 freezing 사슬
// 전원을 freeze_failed 로 쓴다(설계 3.1 phase 3 · ERROR 는 판정부가 남긴다).
const failChainVODSQL = `
WITH upd AS (
    UPDATE stream_sessions SET vod_state = 'freeze_failed'
     WHERE stream_id = $1 AND session_id = ANY($2) AND vod_state = 'freezing'
    RETURNING 1
)
SELECT pg_catalog.count(*) FROM upd`

// FailChainVOD 는 사슬 VOD 를 freeze_failed 로 확정한다. 사슬 전원을 썼으면 참이다.
func FailChainVOD(ctx context.Context, pool *pgxpool.Pool, c Chain, o CloseOptions) (bool, error) {
	return settleChainVOD(ctx, pool, failChainVODSQL, "fail_chain_vod", "VOD 실패 확정", c, o)
}

// errPartialChainVOD 는 사슬 일부만 freezing 이라 VOD 확정을 되돌렸다는 뜻이다.
var errPartialChainVOD = errors.New("index: 사슬 일부만 VOD freezing 이다")

// settleChainVOD 는 사슬 VOD 종국 문장 하나를 한 트랜잭션에서 보낸다 — 바꾼 행 수가 0 도 사슬 수도 아니면 되돌린다. 잠금
// 상한 · 경합 접기는 limitedTx 다(op).
func settleChainVOD(ctx context.Context, pool *pgxpool.Pool, sql, op, what string, c Chain, o CloseOptions) (bool, error) {
	if len(c.SessionIDs) == 0 {
		return false, fmt.Errorf("index: %s 사슬 세션 목록이 비었다 tail=%q", what, c.Tail)
	}
	var n int64
	err := limitedTx(ctx, pool, op, c.Tail, o.Log, func(ctx context.Context, tx pgx.Tx) error {
		if err := tx.QueryRow(ctx, sql, c.StreamID, c.SessionIDs).Scan(&n); err != nil {
			return err
		}
		if n != 0 && n != int64(len(c.SessionIDs)) {
			return errPartialChainVOD
		}
		return nil
	})
	if errors.Is(err, errPartialChainVOD) {
		return false, nil
	}
	if err != nil {
		return false, fmt.Errorf("%s 실패 tail=%q: %w", what, c.Tail, err)
	}
	return n == int64(len(c.SessionIDs)), nil
}
