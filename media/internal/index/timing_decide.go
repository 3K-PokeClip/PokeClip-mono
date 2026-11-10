package index

// 판 정하기 · 이어 계산 회차 R(k)(설계 r7 2.4 · 9.3 · 계약 6-6-4 판 정하기 1–3항). Insert 트랜잭션 안에서 세션 결정
// 다음, PDT 산출 앞에 판을 고른다 — 판을 쓰는 식이 판마다 달라 PDT 보다 먼저여야 한다(설계 4절 ⑵⑶).
//
//	귀속 · 새 회차를 여는 개시   그때 게이트 행의 스위치(advisory 공유 잠금 아래)        회차 = 결정의 RoundID(새 session_id)
//	귀속 · 회차를 이어 가는 행   기저 행(앞 세션의 마지막 귀속 행)의 판 — 「v(s) = v(p)」  회차 = 결정의 RoundID
//	비귀속                       마지막 귀속 행의 회차가 열려 있으면 그 행의 판 · 그 회차,
//	                             닫혔거나 귀속 행이 없으면 게이트 판 · 회차 없음
//	비귀속 · 라이브 중 하한 거부   판은 위 줄 그대로 · 회차 없음
//
// 비귀속 행의 판과 회차는 같은 조회 한 줄(lastAttributedSQL)에서 함께 정해진다 — 그래서 timing_round_id(k) = R 이면
// timing_version(k) 은 회차 R 의 판이다(판 고정).

import (
	"context"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5"
)

// timingChoice 는 판 정하기가 고른 판과 이어 계산 회차, 그리고 판을 고르려고 읽은 게이트다.
type timingChoice struct {
	// version 은 이 행의 timing_version 이다. nil 이면 판 NULL 이다.
	version *int16
	// roundID 는 이 행의 timing_round_id 다. "" 면 회차 없음이다. 새 회차를 여는 행은 결정이 미리 정한 새 session_id 이고,
	// Open 이 돌려준 id 와 같은지는 settleAttributed 가 단언한다.
	roundID string
	// gate 는 판을 고르려고 읽은 게이트 행이다. 게이트를 읽지 않은 행(회차를 이어 가는 행)은 nil 이다.
	gate *TimingGate
}

// lastAttributedSQL 은 판 정하기 3항 · 회차 규칙 3 의 조회다 — 그 스트림의 seq < k 최신 귀속 행의 판과 그 세션의 회차 ·
// 회차 열림을 한 줄로 읽는다. PK (stream_id, seq) 를 역순으로 훑어 첫 귀속 행에서 멈추므로 사이의 비귀속 행 수만큼
// 힙을 훑는다(anchorSQL 과 같은 위험 · R23 시험이 10⁴ 행으로 잰다). 세션 행이 없으면(FK NOT VALID) 회차를 모르므로
// 닫힌 것과 같게 다룬다.
const lastAttributedSQL = `
SELECT g.timing_version, s.round_id, s.round_closed_at IS NULL
  FROM (SELECT session_id, timing_version
          FROM stream_segments
         WHERE stream_id = $1 AND seq < $2 AND session_id IS NOT NULL
         ORDER BY seq DESC
         LIMIT 1) g
  LEFT JOIN stream_sessions s ON s.session_id = g.session_id`

// opensNewRound 는 이 결정이 새 회차를 여는 개시인가다(SessionDecision.NewRound). 개시의 RoundID 는 새 회차여도 비지 않아
// (POK-525 — 새 session_id) RoundID 로는 가릴 수 없고, Open 앞이라 Open 이 돌려줄 id 와 견줄 수도 없다.
func opensNewRound(dec SessionDecision) bool {
	return dec.Opens && dec.NewRound
}

// gateTiming 은 게이트 공유 잠금 아래 스위치의 판을 고른다(새 회차 · 회차 없는 비귀속 행 — 설계 9.3 「읽는 때 · 잠금」).
func gateTiming(ctx context.Context, tx pgx.Tx, roundID string) (timingChoice, error) {
	g, err := readTimingGateLocked(ctx, tx)
	if err != nil {
		return timingChoice{}, err
	}
	return timingChoice{version: g.NewRoundVersion, roundID: roundID, gate: &g}, nil
}

// attributedTiming 은 귀속 행의 판 정하기다(1 · 2항). base 는 기저 행(없으면 nil)이다.
//
// 이어 가는 회차인데 기저 행이 없으면(행이 지워진 세션 등 — 정상 경로에서는 없다) 앞 세션 판을 알 수 없어 새 회차처럼
// 게이트 판을 쓴다(r6 2.4 판 정하기 3 「그 행이 지워져 없으면 이미지의 새 회차 판」 승계 — 이미지 상수가 게이트로 옮겼다).
func attributedTiming(ctx context.Context, tx pgx.Tx, dec SessionDecision, base *baseRow) (timingChoice, error) {
	if opensNewRound(dec) || base == nil {
		return gateTiming(ctx, tx, dec.RoundID)
	}
	return timingChoice{version: base.Version, roundID: dec.RoundID}, nil
}

// unattributedTiming 은 비귀속 행의 판 정하기다(3항 · 회차 규칙 2 · 3). floorRejected 면 판은 그대로 두고 회차를 비운다.
func unattributedTiming(ctx context.Context, tx pgx.Tx, r Record, floorRejected bool) (timingChoice, error) {
	var (
		version *int16
		round   *string
		open    *bool
	)
	err := tx.QueryRow(ctx, lastAttributedSQL, r.StreamID, r.Seq).Scan(&version, &round, &open)
	if errors.Is(err, pgx.ErrNoRows) {
		return gateTiming(ctx, tx, "")
	}
	if err != nil {
		return timingChoice{}, fmt.Errorf("마지막 귀속 행 조회 실패 stream_id=%q seq=%d: %w", r.StreamID, r.Seq, err)
	}
	if round == nil || open == nil || !*open {
		return gateTiming(ctx, tx, "")
	}
	c := timingChoice{version: version, roundID: *round}
	if floorRejected {
		c.roundID = ""
	}
	return c, nil
}
