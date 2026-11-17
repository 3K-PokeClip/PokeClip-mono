package index

// 판 NULL 행의 위치 · PDT(설계 r7 2.5 판 NULL 두 줄 · r6 2.4 「PDT」 · 「위치 — I23」 승계). 위치(start_pts_ms)를
// 정하는 자리는 판과 무관하게 store 하나다 — Record.StartPTSMS 는 입력이 아니라 Insert 트랜잭션의 산출이다(계획 0절 6).
//
//	판 NULL 귀속   PDT(k) = max(j*.PDT + j*.dur + U, wall)   j* = 기저 세션의 마지막 귀속 행(basePDTSQL)
//	               U = Σ duration_ms (seq ∈ (j*.seq, k) · session_id IS NULL)
//	판 NULL 비귀속 PDT(k) = NULL
//	위치(I23)      PDT(k) 있음: max(j′.start + ⌊PDT(k)⌋ − ⌊j′.PDT⌋, q.start + q.dur)
//	               PDT(k) 없음: q.start + q.dur       스트림 첫 행: 0
//	               q = seq < k 최신 행 · j′ = seq < k 인 PDT 행 가운데 가장 가까운 행(판 무관)
//
// r6 I23 의 「+ min(ε, 0)」 항은 r7 이 ε 를 거둬 0 이다(설계 6.1). 앞 항은 회차 안에서 ⌊PDT⌋ − start 폭을 그대로 잇고,
// 뒤 항은 바로 앞 행 구간과 겹치지 않게 한다(seq 순 start 단조 — I10).

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
)

// lastRow 는 q(seq < k 최신 행 — 판 · 귀속 · 회차 무관)의 위치 재료다. PDT 가 nil 이면 NULL 이다.
type lastRow struct {
	StartPTSMS int64
	DurationMS int32
	PDT        *time.Time
}

// anchorRow 는 j′(seq < k 인 PDT 행 가운데 가장 가까운 행)의 위치 재료다.
type anchorRow struct {
	StartPTSMS int64
	PDT        time.Time
}

// baseRow 는 j*(기저 세션의 마지막 귀속 행 — basePDTSQL)와 그 뒤 비귀속 길이 합 U 다. PDT 가 nil 이면 NULL 이다.
// Version 은 그 행의 판이다 — 이어 가는 회차의 판(설계 2.4 판 정하기 1항 「v(s) = v(p)」)을 이 행에서 읽는다.
type baseRow struct {
	Seq            int64
	PDT            *time.Time
	DurationMS     int32
	Version        *int16
	UnattributedMS int64
}

// lastRowSQL 은 q 한 줄이다 — PK (stream_id, seq) 역순 첫 행.
const lastRowSQL = `
SELECT start_pts_ms, duration_ms, playback_pdt
  FROM stream_segments
 WHERE stream_id = $1 AND seq < $2
 ORDER BY seq DESC
 LIMIT 1`

// anchorSQL 은 j′ 한 줄이다 — PK (stream_id, seq) 를 역순으로 훑어 첫 PDT 행에서 멈춘다. playback_pdt 는 어느
// 인덱스에도 없어 사이의 NULL 행 수만큼 힙을 훑는다(basePDTSQL 주석과 같은 위험 · R23 시험이 10⁴ 행으로 잰다). 행 수
// 상한을 두지 않는 것이 계약이다 — 유한 상한이면 그 너머의 앵커를 놓쳐 위치가 회차 기준점을 잃는다. q 에 PDT 가
// 있으면 q 가 곧 j′ 라 이 문장을 보내지 않는다(평시 한 줄).
const anchorSQL = `
SELECT start_pts_ms, playback_pdt
  FROM stream_segments
 WHERE stream_id = $1 AND seq < $2 AND playback_pdt IS NOT NULL
 ORDER BY seq DESC
 LIMIT 1`

// readLastRow 는 q 를 읽는다. 스트림 첫 행이면 nil 이다.
func readLastRow(ctx context.Context, tx pgx.Tx, streamID string, seq int64) (*lastRow, error) {
	var q lastRow
	err := tx.QueryRow(ctx, lastRowSQL, streamID, seq).Scan(&q.StartPTSMS, &q.DurationMS, &q.PDT)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("바로 앞 행 조회 실패 stream_id=%q seq=%d: %w", streamID, seq, err)
	}
	return &q, nil
}

// readAnchor 는 PDT(k) 가 있을 때의 j′ 를 읽는다. q 에 PDT 가 있으면 q 가 j′ 다. 없으면 nil 이다.
func readAnchor(ctx context.Context, tx pgx.Tx, streamID string, seq int64, q *lastRow) (*anchorRow, error) {
	if q == nil {
		return nil, nil
	}
	if q.PDT != nil {
		return &anchorRow{StartPTSMS: q.StartPTSMS, PDT: *q.PDT}, nil
	}
	var a anchorRow
	err := tx.QueryRow(ctx, anchorSQL, streamID, seq).Scan(&a.StartPTSMS, &a.PDT)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("위치 앵커 행 조회 실패 stream_id=%q seq=%d: %w", streamID, seq, err)
	}
	return &a, nil
}

// legacyStart 는 판 NULL 위치 식(I23)이다. pdt 는 이 행의 PDT(nil = NULL)다.
func legacyStart(q *lastRow, anchor *anchorRow, pdt *time.Time) int64 {
	if q == nil {
		return 0
	}
	end := q.StartPTSMS + int64(q.DurationMS)
	if pdt == nil || anchor == nil {
		return end
	}
	// ⌊⌋ 은 항마다 한 번이다 — 차를 내리면 µs 앵커의 반올림 차가 행마다 쌓여 회차 안 폭이 흔들린다.
	return max(anchor.StartPTSMS+pdt.UnixMilli()-anchor.PDT.UnixMilli(), end)
}

// legacyPDT 는 판 NULL 귀속 PDT 식이다. 기저 행이 없거나 그 행의 PDT 가 NULL 이면 벽시계다 — 「session_id 가 있으면
// playback_pdt 도 있다」 는 쓰기 경로의 성질이지 DB 가 강제하는 제약이 아니다.
//
// 누적은 밀리초 그대로 더한다 — 정수 초 나눗셈은 999ms 까지 잘라 그 손실을 다음 항에 이월시키고, 조각마다 쌓여
// 재귀식이 조용히 퇴화한다. 누적항이 하한이라 벽시계가 역행해도 단조가 유지되고, 순단이면 벽시계가 이겨 공백만큼
// 전진한다.
func legacyPDT(base *baseRow, wall time.Time) time.Time {
	if base == nil || base.PDT == nil {
		return wall
	}
	acc := base.PDT.UTC().Add(time.Duration(int64(base.DurationMS)+base.UnattributedMS) * time.Millisecond)
	if acc.After(wall) {
		return acc
	}
	return wall
}
