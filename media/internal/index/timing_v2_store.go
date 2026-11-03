package index

// 판 2 행의 PDT · 위치 · 길이를 Insert 트랜잭션 안에서 정한다(설계 r7 2.4 · 2.5 · 2.6 · 4절 ⑷). 장부에서 p(같은 회차 최신
// 행 — 회차 색인 한 줄)와 q(바로 앞 행 — settleCarrier 가 이미 읽은 PK 한 줄)를 모아 LedgerView 를 만들고 MixV2 를 부른다.
// 식은 pdt_mix.go 에만 있다 — 여기는 장부를 읽고 결과를 Record 에 옮기는 자리다. 위치를 정하는 자리는 판과 무관하게 store
// 하나라는 것(계획 0절 6)은 판 NULL 쪽(placement.go)과 같다.

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
)

// errV2WallMissing 은 판 2 로 쓸 행에 짐작 w 가 없다는 뜻이다 — 인덱서는 판독 실패에도 파일명 시각으로 w 를 늘 채우므로
// (indexer timingInputOf) 부르는 쪽의 배선 결함이다. 그대로 쓰면 PDT 가 0001년이 되고 CHECK 넷째(23514)가 상자의 모든 색인을
// 멈추게 하므로, 그 전에 이 행만 오류로 돌린다.
var errV2WallMissing = errors.New("index: 판 2 행에 짐작 w(Timing.Facts.Wall)가 없다")

// prevRoundRowSQL 은 p 한 줄이다 — 부분 색인 stream_segments_timing_round_idx (stream_id, timing_round_id, seq) 역순 첫
// 행(설계 2.4). 판 · 귀속과 무관하게 같은 회차의 최신 행이다 — 판이 2 가 아니면 MixV2 가 판 고정 위반으로 접는다.
const prevRoundRowSQL = `
SELECT seq, start_pts_ms, duration_ms, playback_pdt, timing_version,
       timing_wall_guess, timing_recorder_id, timing_segment_number, timing_video_first_ns, timing_video_end_ns
  FROM stream_segments
 WHERE stream_id = $1 AND timing_round_id = $2 AND seq < $3
 ORDER BY seq DESC
 LIMIT 1`

// readPrevRoundRow 는 회차 round 의 seq < k 최신 행 p 를 읽는다. 없으면 nil 이다.
func readPrevRoundRow(ctx context.Context, tx pgx.Tx, streamID, round string, seq int64) (*PrevRow, error) {
	var (
		p    PrevRow
		pdt  *time.Time
		cols factColumns
	)
	err := tx.QueryRow(ctx, prevRoundRowSQL, streamID, round, seq).Scan(&p.Seq, &p.StartPTSMS, &p.DurationMS, &pdt,
		&p.Version, &cols.wall, &cols.recorder, &cols.number, &cols.videoFirst, &cols.videoEnd)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("같은 회차 앞 행 조회 실패 stream_id=%q seq=%d round=%q: %w", streamID, seq, round, err)
	}
	if pdt != nil {
		ms := pdt.UnixMilli()
		p.PDTMS = &ms
	}
	p.Facts = cols.toFacts()
	return &p, nil
}

// isV2 는 판 v 가 판 2 인가다.
func isV2(v *int16) bool { return v != nil && *v == versionV2 }

// v2Mix 는 판 2 행을 쓴 섞기 결과와 그 재료 가운데 신호가 쓰는 것이다(설계 8절 · 계획 14절).
type v2Mix struct {
	TimingV2
	// prevFacts 는 이은 p 의 원래 사실 칸이다. 회차 첫 행이면 nil 이다.
	prevFacts *TimingFacts
	// prevVersion 은 p 의 판이다. p 가 없거나 판 NULL 이면 nil 이다.
	prevVersion *int16
	// resetBackMS 는 회차 첫 행의 「q 의 PDT 끝 − w」 다. 섞은 행이거나 q · q 의 PDT 가 없으면 nil 이다.
	resetBackMS *int64
}

// settleV2 는 판 2 행 r 의 PDT · 위치 · 길이를 정해 r 에 쓴다. round 는 판 정하기가 고른 회차("" = 회차 없음)이고, newRound
// 면 r 이 새 회차를 연다 — 새 회차의 id 는 개시(Open) 뒤에 정해져 round 가 빈 채로 오지만 r 은 회차 안에 있다.
// floorRejected 면 라이브 중 귀속 하한 거부 행이다(회차 없음 · 위치 q.start).
func settleV2(ctx context.Context, tx pgx.Tx, r *Record, round string, q *lastRow, newRound, floorRejected bool) (*v2Mix, error) {
	if r.Timing.Facts.Wall.IsZero() {
		return nil, fmt.Errorf("stream_id=%q seq=%d: %w", r.StreamID, r.Seq, errV2WallMissing)
	}
	view := LedgerView{NewRound: newRound, FloorRejected: floorRejected}
	if q != nil {
		view.Last = &struct {
			StartPTSMS int64
			DurationMS int32
		}{q.StartPTSMS, q.DurationMS}
	}
	if round != "" || newRound {
		view.Round = &round
	}
	if round != "" && !newRound {
		p, err := readPrevRoundRow(ctx, tx, r.StreamID, round, r.Seq)
		if err != nil {
			return nil, err
		}
		view.Prev = p
	}

	m := &v2Mix{TimingV2: MixV2(view, r.Timing)}
	pdt := time.UnixMilli(m.PDTMS).UTC()
	r.PlaybackPDT, r.StartPTSMS, r.DurationMS = &pdt, m.StartPTSMS, m.DurationMS
	if view.Prev != nil {
		m.prevVersion = view.Prev.Version
	}
	switch {
	case m.Branch != BranchRoundFirst:
		m.prevFacts = &view.Prev.Facts
	case q != nil && q.PDT != nil:
		back := q.PDT.UnixMilli() + int64(q.DurationMS) - m.PDTMS
		m.resetBackMS = &back
	}
	return m, nil
}

// attributedPDT 는 귀속 행 ⑷ 의 PDT 다. 판 NULL 이면 r6 재귀식(legacyPDT)이고, 판 2 면 섞기가 PDT · 위치 · 길이를 함께 정해
// r 에 쓴다 — 개시 갈래는 그 PDT 가 새 세션의 first_pdt 다(설계 4절 ⑸).
func attributedPDT(ctx context.Context, tx pgx.Tx, r *Record, dec SessionDecision, base *baseRow, q *lastRow, tc timingChoice) (time.Time, *v2Mix, error) {
	if !isV2(tc.version) {
		return legacyPDT(base, r.StartWallUTC.UTC()), nil, nil
	}
	mix, err := settleV2(ctx, tx, r, tc.roundID, q, opensNewRound(dec), false)
	if err != nil {
		return time.Time{}, nil, err
	}
	return *r.PlaybackPDT, mix, nil
}

// withMix 는 판 2 섞기 결과 m 을 커밋 결과에 얹는다. 판 NULL 행(m == nil)이면 그대로다.
func withMix(res InsertCommit, m *v2Mix) InsertCommit {
	if m == nil {
		return res
	}
	res.TimingBranch, res.DeltaMS, res.TimingLimitedBy = m.Branch, m.DeltaMS, m.LimitedBy
	res.TimingReset, res.TimingBroken = m.ResetReason, m.Broken
	res.TimingSameInstance, res.TimingCompared = m.SameInstance, m.Compared
	res.TimingPrevFacts, res.TimingPrevVersion, res.TimingResetBackMS = m.prevFacts, m.prevVersion, m.resetBackMS
	return res
}
