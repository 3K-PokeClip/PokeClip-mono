package index

// 동결 렌더의 장부 읽기(POK-196 M6-1 커밋 ④ — M6 설계 2.2 · 3.3 사슬 간선 규칙 · 3.1 phase 3 비귀속 행 · 계획 P3).
//
// 동결은 캐시가 아니라 장부에서 렌더 입력을 한 번 읽는다(설계 4절 「동결 렌더 입력」 — 캐시의 Forget 이 300초 뒤 뷰를
// 버려 꼬리를 놓친다). 읽기는 둘이다:
//
//	LoadSessionLedger  DVR 동결(FreezeDVR)의 입력 — 그 회차의 마지막 조각까지(계승 접두 포함)
//	LoadChainLedger    VOD 동결(FreezeVOD)의 입력 — 사슬의 [첫 seq, 끝 seq] 안의 행 전부
//
// 둘 다 읽기 전용 · REPEATABLE READ 한 트랜잭션이라 한 스냅숏을 본다. 장부 쓰기 0 이고 settled 판정은 하지 않는다
// (판정은 rewind/boundary.Settled 하나 — rewind_read.go 와 같은 규약). 사슬 간선은 chainEdgeSQL 한 벌이다 — 사슬
// 닫힘(CloseChain) · 후속 판정(HasSuccessor)이 같은 상수를 쓴다(M6-3b).

import (
	"context"
	"errors"
	"fmt"
	"slices"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// ErrChainNotFound 는 사슬 꼬리가 그 스트림의 장부에 없다는 뜻이다(없는 세션 · 다른 스트림의 세션).
var ErrChainNotFound = errors.New("index: 사슬 꼬리가 장부에 없다")

// ErrChainChanged 는 부른 쪽이 준 사슬 세션 목록이 장부에서 다시 읽은 사슬과 다르다는 뜻이다 — 그 사이 계승이 끊기거나
// 이어졌다. 닫힌 사슬의 VOD 는 닫은 집합 그대로여야 하므로 다른 집합을 렌더하지 않는다.
var ErrChainChanged = errors.New("index: 사슬이 바뀌었다")

// Chain 은 사슬 하나다 — 같은 회차 안에서 inherits_session 으로 이어진 세션들이다(설계 0절 사슬 행). VOD 문서 단위이고
// 키는 머리 세션으로 정한다(K1 — vod/{stream}/{Head}/index.m3u8).
type Chain struct {
	StreamID string
	// RoundID 는 꼬리 세션의 round_id 다. "" 면 NULL(M6 전 세션)이다.
	RoundID string
	// Head 는 사슬 머리(계승 없음 · 또는 간선이 끊긴 자리) · Tail 은 꼬리(이어받은 회차 없음) session_id 다.
	Head, Tail string
	// SessionIDs 는 사슬 세션 전부다(started_at, session_id 순 — 머리가 처음 · 꼬리가 끝).
	SessionIDs []string
}

// ChainLedger 는 사슬 하나의 VOD 렌더 입력이다.
type ChainLedger struct {
	// Chain 은 장부에서 다시 읽은 사슬이다.
	Chain Chain
	// CutoffSeq 는 stream_cutoffs.cutoff_seq 다. HasCutoff 가 거짓이면 그 스트림에 컷오프가 없다.
	CutoffSeq int64
	HasCutoff bool
	// Rows 는 사슬의 [첫 seq, 끝 seq] 안의 그 스트림 행 전부다(seq 오름차순) — 사슬 세션 행이 아닌 것(세션 NULL 인
	// 비귀속 행 · 구조상 없어야 할 다른 세션 행)도 그 자리에 온다. 무엇으로 채울지는 발행 층이 정한다(D57 — GAP 줄 ·
	// foreign_session). 사슬에 행이 없으면 비어 있다.
	Rows []RewindRow
	// Sessions 는 사슬 세션의 여덟 열과 회차 최소 seq 다(개시 순).
	Sessions []RewindSession
}

// chainEdgeSQL 은 사슬 간선 규칙이다(설계 3.3) — 자식 c 가 부모 p 를 사슬로 잇는 조건. 같은 스트림 · 자식이 나중 ·
// 자식이 부모를 계승하고, 부모의 round_id 가 NULL(M6 전)이면 옛 규칙대로 잇고 아니면 같은 round_id 일 때만 잇는다.
// 그래서 배포 순간 live 의 계승이 끊기지 않고 옛 계승 사슬도 거둬지며, 늦은 회차 분리가 만든 회차를 가로지르는
// 간선은 사슬을 잇지 않는다. 별칭 c · p 를 쓰는 문장이 그대로 끼워 쓴다.
const chainEdgeSQL = `c.stream_id = p.stream_id AND c.started_at > p.started_at AND c.inherits_session = p.session_id
   AND (p.round_id IS NULL OR c.round_id = p.round_id)`

// chainMembersSQL 은 꼬리 $1(스트림 $2)의 사슬 세션이다 — 꼬리에서 간선을 거슬러 머리까지 오른다. 세션마다 계승은
// 많아야 하나라 사슬은 한 줄이고, 간선이 「자식이 나중」을 요구해 되돌아 도는 일이 없다. 꼬리의 round_id 를 함께 낸다.
const chainMembersSQL = `
WITH RECURSIVE chain AS (
    SELECT s.session_id, s.stream_id, s.started_at, s.inherits_session, s.round_id
      FROM stream_sessions s
     WHERE s.session_id = $1 AND s.stream_id = $2
    UNION ALL
    SELECT p.session_id, p.stream_id, p.started_at, p.inherits_session, p.round_id
      FROM chain c
      JOIN stream_sessions p ON ` + chainEdgeSQL + `
)
SELECT session_id, (SELECT t.round_id FROM stream_sessions t WHERE t.session_id = $1) AS tail_round
  FROM chain
 ORDER BY started_at, session_id`

// chainRangeSQL 은 사슬 세션 행의 seq 범위다 — 행이 없으면 둘 다 NULL 이다(stream_segments_session_idx).
const chainRangeSQL = `
SELECT pg_catalog.min(seq), pg_catalog.max(seq)
  FROM stream_segments
 WHERE stream_id = $1 AND session_id = ANY($2)`

// chainRowsSQL 은 사슬 범위 [$2, $3] 의 행 전부다 — 조각 축 열은 되감기 적재(rewindRowsSQL)와 같다.
const chainRowsSQL = `
SELECT s.seq, s.session_id, s.duration_ms, s.playback_pdt, s.playback_s3_key,
       (s.playback_upload_state = 'uploaded') AS pb_uploaded,
       (g.seq IS NOT NULL)                    AS is_gap
  FROM stream_segments s
  LEFT JOIN stream_published_gaps g ON g.stream_id = s.stream_id AND g.seq = s.seq
 WHERE s.stream_id = $1 AND s.seq >= $2 AND s.seq <= $3
 ORDER BY s.seq`

// sessionHeadSQL 은 회차 $2 의 마지막 조각 seq 다 — 행이 없으면 NULL 이다.
const sessionHeadSQL = `
SELECT pg_catalog.max(seq) FROM stream_segments WHERE stream_id = $1 AND session_id = $2`

// LoadChainLedger 는 사슬 c 의 VOD 렌더 입력을 읽는다. c 에서 쓰는 것은 StreamID · Tail 이고 사슬은 장부에서 다시
// 읽는다(사슬 간선 규칙). c.SessionIDs 를 주면 다시 읽은 사슬과 같아야 한다 — 다르면 ErrChainChanged 다. 꼬리가 그
// 스트림 장부에 없으면 ErrChainNotFound 다. 다섯 문장(사슬 · 컷오프 · 범위 · 행 · 회차)이 한 스냅숏이다.
func LoadChainLedger(ctx context.Context, pool *pgxpool.Pool, c Chain) (ChainLedger, error) {
	var l ChainLedger
	err := inLedgerSnapshot(ctx, pool, "사슬 장부", c.StreamID, func(ctx context.Context, tx pgx.Tx) error {
		var err error
		l, err = readChainLedger(ctx, tx, c)
		return err
	})
	if err != nil {
		return ChainLedger{}, err
	}
	return l, nil
}

// readChainLedger 는 사슬 장부 트랜잭션 tx 안의 다섯 문장이다.
func readChainLedger(ctx context.Context, tx pgx.Tx, want Chain) (ChainLedger, error) {
	chain, err := readChain(ctx, tx, want.StreamID, want.Tail)
	if err != nil {
		return ChainLedger{}, err
	}
	if len(want.SessionIDs) > 0 && !slices.Equal(want.SessionIDs, chain.SessionIDs) {
		return ChainLedger{}, fmt.Errorf("%w — 꼬리 %q 의 사슬이 %q 가 아니라 %q 다", ErrChainChanged, want.Tail, want.SessionIDs, chain.SessionIDs)
	}
	cut, err := readCutoff(ctx, tx, chain.StreamID)
	if err != nil {
		return ChainLedger{}, err
	}
	l := ChainLedger{Chain: chain, CutoffSeq: cut.CutoffSeq, HasCutoff: cut.HasCutoff}
	var first, last *int64
	if err := tx.QueryRow(ctx, chainRangeSQL, chain.StreamID, chain.SessionIDs).Scan(&first, &last); err != nil {
		return ChainLedger{}, fmt.Errorf("사슬 장부 범위 조회 실패 stream_id=%q tail=%q: %w", chain.StreamID, chain.Tail, err)
	}
	if first != nil {
		if l.Rows, err = queryRewindRows(ctx, tx, chain.StreamID, chainRowsSQL, chain.StreamID, *first, *last); err != nil {
			return ChainLedger{}, err
		}
	}
	if l.Sessions, err = loadRewindSessions(ctx, tx, chain.StreamID, chain.SessionIDs); err != nil {
		return ChainLedger{}, err
	}
	return l, nil
}

// readChain 은 꼬리 tail 의 사슬을 장부에서 읽는다(chainMembersSQL).
func readChain(ctx context.Context, tx pgx.Tx, streamID, tail string) (Chain, error) {
	rows, err := tx.Query(ctx, chainMembersSQL, tail, streamID)
	if err != nil {
		return Chain{}, fmt.Errorf("사슬 조회 실패 stream_id=%q tail=%q: %w", streamID, tail, err)
	}
	defer rows.Close()

	c := Chain{StreamID: streamID, Tail: tail}
	for rows.Next() {
		var (
			id    string
			round *string
		)
		if err := rows.Scan(&id, &round); err != nil {
			return Chain{}, fmt.Errorf("사슬 스캔 실패 stream_id=%q tail=%q: %w", streamID, tail, err)
		}
		c.SessionIDs, c.RoundID = append(c.SessionIDs, id), deref(round)
	}
	if err := rows.Err(); err != nil {
		return Chain{}, fmt.Errorf("사슬 조회 중 오류 stream_id=%q tail=%q: %w", streamID, tail, err)
	}
	if len(c.SessionIDs) == 0 {
		return Chain{}, fmt.Errorf("%w — stream_id=%q tail=%q", ErrChainNotFound, streamID, tail)
	}
	c.Head = c.SessionIDs[0]
	return c, nil
}

// LoadSessionLedger 는 회차 sessionID 의 DVR 동결 렌더 입력을 읽는다. 범위는 되감기 적재(LoadRewindLedger)와 같은 규칙
// (컷오프 · 되짚기 · 힌트 · 행 수 상한)이되 머리가 그 회차의 마지막 조각이다 — 뒤에 다른 회차 · 비귀속 행이 쌓여도
// 그 회차 꼬리까지 싣고 그 뒤는 싣지 않는다. 계승 접두를 위해 하한부터의 앞 회차 행도 온다. 컷오프가 없거나 그 회차에
// 행이 없으면 행이 없다(발행 층이 빈 동결로 닫는다).
func LoadSessionLedger(ctx context.Context, pool *pgxpool.Pool, streamID, sessionID string, b LedgerBounds) (RewindLedger, error) {
	var l RewindLedger
	err := inLedgerSnapshot(ctx, pool, "회차 장부", streamID, func(ctx context.Context, tx pgx.Tx) error {
		var head *int64
		if err := tx.QueryRow(ctx, sessionHeadSQL, streamID, sessionID).Scan(&head); err != nil {
			return fmt.Errorf("회차 장부 머리 조회 실패 stream_id=%q session_id=%q: %w", streamID, sessionID, err)
		}
		if head == nil {
			var err error
			l, err = readCutoff(ctx, tx, streamID)
			if l.HasCutoff {
				l.FloorSeq = l.CutoffSeq
			}
			return err
		}
		var err error
		l, err = readRewindLedger(ctx, tx, streamID, b, *head)
		return err
	})
	if err != nil {
		return RewindLedger{}, err
	}
	return l, nil
}

// inLedgerSnapshot 은 read 를 읽기 전용 · REPEATABLE READ 한 트랜잭션에서 돌린다 — 시한은 트랜잭션 하나에 TxnDeadline
// 하나다. 되돌림 · 확정 규약은 loadRewindLedger 와 같다(끝난 ctx 로 되돌리지 않는다 · COMMIT 오류 뒤 되돌리지 않는다).
func inLedgerSnapshot(ctx context.Context, pool *pgxpool.Pool, what, streamID string, read func(context.Context, pgx.Tx) error) error {
	tctx, cancel := context.WithTimeout(ctx, TxnDeadline)
	defer cancel()
	tx, err := pool.BeginTx(tctx, pgx.TxOptions{IsoLevel: pgx.RepeatableRead, AccessMode: pgx.ReadOnly})
	if err != nil {
		return fmt.Errorf("%s 트랜잭션 시작 실패 stream_id=%q: %w", what, streamID, err)
	}
	defer func() {
		rctx, rcancel := context.WithTimeout(context.WithoutCancel(ctx), TxnDeadline)
		defer rcancel()
		_ = tx.Rollback(rctx)
	}()
	if err := read(tctx, tx); err != nil {
		return err
	}
	if err := tx.Commit(tctx); err != nil {
		return fmt.Errorf("%s 트랜잭션 확정 실패 stream_id=%q: %w", what, streamID, err)
	}
	return nil
}
