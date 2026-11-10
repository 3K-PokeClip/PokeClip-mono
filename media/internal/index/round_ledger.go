package index

// 회차 VOD 의 장부 읽기(POK-549 C1 · 설계 2.1). 사슬이 둘 이상인 회차는 마지막 사슬(회차 최신 세션이 든 사슬)의 VOD
// 동결이 회차 전체를 한 문서로 렌더한다. 그 판단과 렌더에 드는 읽기가 셋이다:
//
//	LoadRoundGate      대기 판정 재료 — 한 문장 · 행 적재 없음(대기마다 행을 싣지 않는다 — R13)
//	LoadRoundLedger    회차 문서 렌더 입력 — 회차 세션 행의 [첫 seq, 끝 seq] 안의 행 전부(읽기 전용 RR)
//	ReadRoundLedger    같은 읽기를 부른 쪽 트랜잭션(꼬리 잠금) 안에서
//	RoundLatestEnding  깨우기 대상 — 회차 최신 세션이 ending 이면 그 id
//
// 회차 장부는 사슬 장부(ChainLedger)와 따로 둔 형이다 — ChainLedger.Rows 의 계약이 「사슬 범위」라 넓히면 그 이름이
// 거짓이 된다. 최신 세션 선택은 SQL 안에서 한다(판정식 playbackVerdictSQL · 회차 스캔 closableRoundsSQL 과 같은
// ORDER BY) — Go 문자열 비교는 PG 정렬 규칙(collation)과 갈린다(R8). 회차 세션 조회 조건은 stream_id · round_id 다
// (닫힌 회차는 부분 인덱스 stream_sessions_round_open_idx 를 못 타고 stream_sessions_stream_idx 로 스트림 안을
// 훑는다 — 새 인덱스 없음). 내장 함수는 pg_catalog 로 한정한다.

import (
	"context"
	"errors"
	"fmt"
	"slices"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// ErrRoundTooLarge 는 회차 범위의 컷오프 이상 행이 부른 쪽이 준 상한(maxRows)을 넘는다는 뜻이다 — 행을 다 싣기 전에
// 끊는다. ErrChainTooLarge 와 다른 값이라 발행 층이 회차 경로의 실패(사슬 범위로 물러섬)로 가를 수 있다.
var ErrRoundTooLarge = errors.New("index: 회차 행이 적재 상한을 넘는다")

// RoundMember 는 회차 세션 하나의 VOD 대기 판정 재료다.
type RoundMember struct {
	SessionID string
	// VODState 는 vod_state 다 — none · freezing · frozen · frozen_with_gaps · freeze_failed.
	VODState string
	// HasDocument 는 vod_s3_key 가 있는가다.
	HasDocument bool
	// HasRows 는 그 세션 행 가운데 렌더할 행(컷오프가 없거나 seq ≥ 컷오프)이 있는가다 — VOD plan 의 컷오프 규칙과 같다.
	HasRows bool
}

// RoundGate 는 마지막 사슬 VOD 동결의 대기 판정 재료다 — 한 문장 · 한 스냅숏 · 행 적재 없음. 회원이 없으면(모르는
// 회차 · M6 전 세션) RoundID 밖의 칸이 영값이다.
type RoundGate struct {
	RoundID string
	// Closed 는 회원 전원의 round_closed_at 이 있는가다.
	Closed bool
	// Latest 는 판정식과 같은 선택의 최신 세션이다 — (state='live') DESC, started_at DESC, session_id DESC.
	Latest string
	// Members 는 회차 세션 전부다(started_at, session_id 순).
	Members []RoundMember
}

// RoundLedger 는 회차 문서의 렌더 입력이다.
type RoundLedger struct {
	// Gate 는 같은 스냅숏에서 다시 읽은 판정 재료다 — 꼬리 잠금 아래 재판정이 쓴다.
	Gate RoundGate
	// Chain 은 장부에서 다시 읽은 마지막 사슬이다.
	Chain Chain
	// CutoffSeq 는 stream_cutoffs.cutoff_seq 다. HasCutoff 가 거짓이면 그 스트림에 컷오프가 없다.
	CutoffSeq int64
	HasCutoff bool
	// Rows 는 회차 세션 행의 [첫 seq, 끝 seq] 안에서 컷오프 이상인 그 스트림 행 전부다(seq 오름차순) — 비귀속 행 · 다른
	// 회차 행도 그 자리에 온다(무엇으로 채울지는 발행 층이 정한다 — ChainLedger.Rows 와 같은 규약). 회차에 행이 없으면
	// 비어 있다.
	Rows []RewindRow
	// Sessions 는 회차 세션 전부의 여덟 열과 회차 최소 seq 다(개시 순).
	Sessions []RewindSession
}

// roundGateSQL 은 회차 $2(스트림 $1)의 판정 재료다 — 회원 한 줄마다 상태 · 문서 유무 · 렌더할 행 유무를 내고, 회차
// 전체 값(전원 닫힘 · 최신 세션)은 창 함수로 줄마다 같은 값을 싣는다. 행 유무는 stream_segments_session_idx 의 범위
// 하나다.
const roundGateSQL = `
SELECT s.session_id, s.vod_state, s.vod_s3_key IS NOT NULL,
       EXISTS (SELECT 1 FROM stream_segments g
                WHERE g.stream_id = s.stream_id AND g.session_id = s.session_id
                  AND (c.cutoff_seq IS NULL OR g.seq >= c.cutoff_seq)),
       pg_catalog.bool_and(s.round_closed_at IS NOT NULL) OVER (),
       pg_catalog.first_value(s.session_id) OVER (ORDER BY (s.state = 'live') DESC, s.started_at DESC, s.session_id DESC)
  FROM stream_sessions s
  LEFT JOIN stream_cutoffs c ON c.stream_id = s.stream_id
 WHERE s.stream_id = $1 AND s.round_id = $2
 ORDER BY s.started_at, s.session_id`

// roundLatestSQL 은 회차 $2(스트림 $1)의 최신 세션과 그 상태다 — 판정식과 같은 선택이다.
const roundLatestSQL = `
SELECT session_id, state
  FROM stream_sessions
 WHERE stream_id = $1 AND round_id = $2
 ORDER BY (state = 'live') DESC, started_at DESC, session_id DESC
 LIMIT 1`

// LoadRoundGate 는 회차 roundID 의 대기 판정 재료를 읽는다. 시한은 풀 획득부터 읽기까지 TxnDeadline 하나다.
func LoadRoundGate(ctx context.Context, pool *pgxpool.Pool, streamID, roundID string) (RoundGate, error) {
	qctx, cancel := context.WithTimeout(ctx, TxnDeadline)
	defer cancel()
	return readRoundGate(qctx, pool, streamID, roundID)
}

// readRoundGate 는 roundGateSQL 한 문장이다 — 풀과 꼬리 잠금 트랜잭션이 함께 쓴다.
func readRoundGate(ctx context.Context, q rowQuerier, streamID, roundID string) (RoundGate, error) {
	rows, err := q.Query(ctx, roundGateSQL, streamID, roundID)
	if err != nil {
		return RoundGate{}, fmt.Errorf("회차 판정 재료 조회 실패 stream_id=%q round_id=%q: %w", streamID, roundID, err)
	}
	defer rows.Close()

	g := RoundGate{RoundID: roundID}
	for rows.Next() {
		var m RoundMember
		if err := rows.Scan(&m.SessionID, &m.VODState, &m.HasDocument, &m.HasRows, &g.Closed, &g.Latest); err != nil {
			return RoundGate{}, fmt.Errorf("회차 판정 재료 스캔 실패 stream_id=%q round_id=%q: %w", streamID, roundID, err)
		}
		g.Members = append(g.Members, m)
	}
	if err := rows.Err(); err != nil {
		return RoundGate{}, fmt.Errorf("회차 판정 재료 조회 중 오류 stream_id=%q round_id=%q: %w", streamID, roundID, err)
	}
	return g, nil
}

// LoadRoundLedger 는 마지막 사슬 last 가 렌더할 회차 문서의 입력을 읽는다(읽기 전용 · REPEATABLE READ 한 스냅숏). last 에서
// 쓰는 것은 StreamID · Tail 이고 사슬은 장부에서 다시 읽는다 — last.SessionIDs 를 주면 다시 읽은 사슬과 같아야 하고
// 다르면 ErrChainChanged 다. 꼬리가 그 스트림 장부에 없으면 ErrChainNotFound · 꼬리에 회차가 없으면(M6 전) 오류다.
// 회차는 다시 읽은 꼬리의 round_id 다. 행은 컷오프 이상만 maxRows 까지 싣고, 넘으면 ErrRoundTooLarge 다.
func LoadRoundLedger(ctx context.Context, pool *pgxpool.Pool, last Chain, maxRows int) (RoundLedger, error) {
	var l RoundLedger
	err := inLedgerSnapshot(ctx, pool, "회차 문서 장부", last.StreamID, func(ctx context.Context, tx pgx.Tx) error {
		var err error
		l, err = readRoundLedger(ctx, tx, last, maxRows)
		return err
	})
	if err != nil {
		return RoundLedger{}, err
	}
	return l, nil
}

// ReadRoundLedger 는 LoadRoundLedger 의 읽기를 부른 쪽 트랜잭션 tx 안에서 한다 — 꼬리 잠금을 쥔 트랜잭션이 같은 연결로
// 다시 읽는다(ReadChainLedger 와 같은 까닭). 여섯 문장이 한 스냅숏인지는 tx 의 격리 수준을 따른다. READ COMMITTED 면
// 문장마다 새 스냅숏이라 판정 재료(Gate) · 범위 · 행 · 세션이 서로 다른 시점일 수 있다 — 그 사이에 든 늦은 조각이나 ③
// 착지가 Rows 와 Sessions(MinSeq) · Gate(HasRows)를 어긋나게 할 수 있다. 부른 쪽(발행 층 꼬리 잠금 재독)이 그 근거를 진다.
// 오류는 LoadRoundLedger 와 같다.
func ReadRoundLedger(ctx context.Context, tx pgx.Tx, last Chain, maxRows int) (RoundLedger, error) {
	return readRoundLedger(ctx, tx, last, maxRows)
}

// readRoundLedger 는 회차 장부 트랜잭션 tx 안의 여섯 문장(사슬 · 판정 재료 · 컷오프 · 범위 · 행 · 세션)이다.
func readRoundLedger(ctx context.Context, tx pgx.Tx, want Chain, maxRows int) (RoundLedger, error) {
	chain, err := readChain(ctx, tx, want.StreamID, want.Tail)
	if err != nil {
		return RoundLedger{}, err
	}
	if len(want.SessionIDs) > 0 && !slices.Equal(want.SessionIDs, chain.SessionIDs) {
		return RoundLedger{}, fmt.Errorf("%w — 꼬리 %q 의 사슬이 %q 가 아니라 %q 다", ErrChainChanged, want.Tail, want.SessionIDs, chain.SessionIDs)
	}
	if chain.RoundID == "" {
		return RoundLedger{}, fmt.Errorf("index: 꼬리 %q(stream_id=%q)에 회차가 없다 — 회차 장부를 읽을 수 없다", chain.Tail, chain.StreamID)
	}
	gate, err := readRoundGate(ctx, tx, chain.StreamID, chain.RoundID)
	if err != nil {
		return RoundLedger{}, err
	}
	cut, err := readCutoff(ctx, tx, chain.StreamID)
	if err != nil {
		return RoundLedger{}, err
	}
	l := RoundLedger{Gate: gate, Chain: chain, CutoffSeq: cut.CutoffSeq, HasCutoff: cut.HasCutoff}
	ids := make([]string, 0, len(gate.Members))
	for _, m := range gate.Members {
		ids = append(ids, m.SessionID)
	}
	if l.Rows, err = readRoundRows(ctx, tx, chain, ids, l.CutoffSeq, l.HasCutoff, maxRows); err != nil {
		return RoundLedger{}, err
	}
	if l.Sessions, err = loadRewindSessions(ctx, tx, chain.StreamID, ids); err != nil {
		return RoundLedger{}, err
	}
	return l, nil
}

// readRoundRows 는 회차 세션 ids 행의 범위(chainRangeSQL)를 구해 그 안의 컷오프 이상 행을 maxRows 까지 싣는다
// (chainRowsSQL · 상한보다 하나 더 받아 넘침을 가른다). 회차에 행이 없으면 nil 이다.
func readRoundRows(ctx context.Context, tx pgx.Tx, chain Chain, ids []string, cutoff int64, hasCutoff bool, maxRows int) ([]RewindRow, error) {
	var first, last *int64
	if err := tx.QueryRow(ctx, chainRangeSQL, chain.StreamID, ids).Scan(&first, &last); err != nil {
		return nil, fmt.Errorf("회차 장부 범위 조회 실패 stream_id=%q round_id=%q: %w", chain.StreamID, chain.RoundID, err)
	}
	if first == nil {
		return nil, nil
	}
	// 컷오프 아래 행은 렌더하지 않으므로 싣지도 세지도 않는다.
	start := *first
	if hasCutoff && cutoff > start {
		start = cutoff
	}
	rows, err := queryRewindRows(ctx, tx, chain.StreamID, chainRowsSQL, chain.StreamID, start, *last, maxRows+1)
	if err != nil {
		return nil, err
	}
	if len(rows) > maxRows {
		return nil, fmt.Errorf("%w — stream_id=%q round_id=%q 상한 %d", ErrRoundTooLarge, chain.StreamID, chain.RoundID, maxRows)
	}
	return rows, nil
}

// RoundLatestEnding 은 회차 roundID 의 최신 세션(판정식과 같은 선택)이 state='ending' 이고 except 에 없으면 그 id 를
// 돌려준다 — 앞 사슬 종국 · 회차 닫힘이 마지막 사슬을 깨울 대상이다(X1). 최신이 live · ended 이거나 except 안이거나
// 모르는 회차면 ok=false 다. 시한은 TxnDeadline 하나다.
func RoundLatestEnding(ctx context.Context, pool *pgxpool.Pool, streamID, roundID string, except []string) (sessionID string, ok bool, err error) {
	qctx, cancel := context.WithTimeout(ctx, TxnDeadline)
	defer cancel()
	var state string
	err = pool.QueryRow(qctx, roundLatestSQL, streamID, roundID).Scan(&sessionID, &state)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", false, nil
	}
	if err != nil {
		return "", false, fmt.Errorf("회차 최신 세션 조회 실패 stream_id=%q round_id=%q: %w", streamID, roundID, err)
	}
	if state != "ending" || slices.Contains(except, sessionID) {
		return "", false, nil
	}
	return sessionID, true, nil
}
