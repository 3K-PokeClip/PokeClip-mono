package session

// 회차 소속 — 소속 잠금 · 개시 재판정 · 회차 개시 규칙(POK-196 M6-3a · 설계 M6 3.3 · 15.2).
//
// 개시는 세 가지를 한 트랜잭션에서 정한다: 새 세션이 어느 회차(round_id)에 드는가, 끝내는 live 의 의도적 종료
// 표식(stop_marked_at)을 물려받는가, 직전 회차의 되감기 목록을 물려받는가(inherits_session). 셋 다 소속 행을
// 잠근 **뒤** 읽은 값으로만 정한다 — 무잠금으로 본 값은 늦은 4D(분리 · 표식) · 회차 닫힘의 커밋과 겹치면 낡는다.

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
)

// endReasonIntentional 은 의도적 종료(4D)로 끝난 세션의 사유다 — 표식 분할 · 4D 승격이 쓰는 글자다(M6 3.4).
// live 회차 종료 문장(오프라인 전이 · L3 · L2′ — index 의 liveEndingSet)도 표식이 있으면 이 글자를 쓴다.
// 회차 닫힘 조건이고, 계승 · 합류를 막는다.
const endReasonIntentional = "intentional"

// inherit_declined 신호의 사유 둘(D9) — 시간 창 안의 끝난 회차를 닫힘 때문에 물려받지 않았다.
const (
	declineChainClosed = "chain_closed" // 사슬이 닫혔다(vod_state ≠ none) — 회차가 열려 있으면 합류만 한다
	declineRoundClosed = "round_closed" // 회차가 닫혔다(round_closed_at 있음) — 새 회차다
)

// ErrLiveLost 는 개시가 끝내려던 live 가 소속 잠금 아래에서 이미 live 가 아니었음을 뜻한다(M6 3.3 — endSessionSQL
// 0행). 그 개시는 되돌리고 다음 조각이 다시 판정한다. 조립(cmd)은 이것을 장부의 개시 경합(재시도)으로 옮긴다.
var ErrLiveLost = errors.New("session: 끝낼 live 가 이미 live 가 아니다 — 개시를 되돌리고 다음 조각이 다시 판정한다")

// lockLiveSQL 은 소속 행 선택 규칙의 첫 갈래다 — live 가 있으면 그 live 행을 잡는다(M6 3.3).
//
// 잠금 강도가 FOR NO KEY UPDATE 인 것이 계약이다(cc M1 · D71): 「계속」 조각 INSERT 는 FK 검사로 세션 행에
// FOR KEY SHARE 를 거는데, FOR UPDATE 는 그것과 충돌해 개시가 live 를 쥔 동안 그 방송의 조각 INSERT 가 모두 선다.
// FOR NO KEY UPDATE 는 FOR KEY SHARE 와 충돌하지 않고, 서로끼리 · 비키 UPDATE(4D · EndLive · 회차 닫힘)와는
// 충돌한다(PG 17 13.3.2). 세션 행의 키(session_id)는 바꾸지 않으므로 이 강도로 충분하다.
const lockLiveSQL = currentLiveSQL + `
   FOR NO KEY UPDATE`

// lockPreviousSQL 은 소속 행 선택 규칙의 둘째 갈래다 — live 가 없으면 started_at 최대 행을 잡는다(M6 3.3).
// 조각 하위 질의의 표는 잠그지 않는다(OF s).
const lockPreviousSQL = previousSessionSQL + `
   FOR NO KEY UPDATE OF s`

// decideLocked 는 무잠금 판정이 개시 갈래일 때 소속 행을 잠그고 처음부터 다시 판정한다(M6 3.3 개시 쪽).
//
// 소속 행 선택 규칙은 하나다 — live 가 있으면 그 live 행, 없으면 started_at 최대 행(D73). 잠금을 기다린 SELECT 는
// 커밋된 새 판으로 WHERE 를 다시 본다(READ COMMITTED · PG 17 13.2.1). 다시 판정한 결과가 계속이면 계속으로 간다
// (그 사이 다른 개시가 끝난 경우). 결과가 무잠금 판정과 달라지면 session_open_relocked 를 남긴다(D69 판별 신호).
func (r *Registry) decideLocked(ctx context.Context, tx pgx.Tx, in Input, now time.Time, unlocked Decision) (Decision, error) {
	live, err := r.loadLive(ctx, tx, in.StreamID, lockLiveSQL)
	if err != nil {
		return Decision{}, err
	}
	d := r.decide(live, in, now)
	if live == nil && d.Outcome == OutcomeOpen {
		prev, err := r.lockPrevious(ctx, tx, in.StreamID)
		if err != nil {
			return Decision{}, err
		}
		if prev != nil {
			d = r.openFromPrevious(in, *prev)
		}
	}
	if d.Outcome != unlocked.Outcome || d.SessionID != unlocked.SessionID {
		r.opt.Log.Info("session_open_relocked",
			"stream_id", in.StreamID, "seq", in.Seq,
			"unlocked", unlocked.Outcome.String(), "locked", d.Outcome.String())
	}
	return d, nil
}

// openFromPrevious 는 live 가 없을 때 잠근 직전 회차 prev 로 새 세션의 회차 · 표식 · 계승을 정한다 — M6 15.2 개시 규칙
// 표의 2 · 3 · 5 · 6 · 7행이다(위에서 처음 맞는 행이 이긴다). 기저 세션은 갈래와 무관하게 prev 다(PDT 연속).
//
//	2 표식 개시  prev 에 표식 ∧ 조각 ≥ 표식 + 여유        → 새 회차 · 계승 안 함 · 표식 없음
//	3 NULL 회차  prev.round_id 가 NULL(M6 전)             → 새 회차 · 계승은 inheritable 대로 · 표식 없음
//	5 계승 개시  inheritable                               → prev 회차 · 계승 · prev 표식 승계
//	6 합류       joinable ∧ 회차 안 닫힘                   → prev 회차 · 계승 안 함 · prev 표식 승계
//	7 그 밖                                                → 새 회차 · 표식 없음
//
// 6행이 init 불일치 재접속을 받는다 — 업로드 단계가 live 를 먼저 init_mismatch 로 끝내므로 그 뒤 조각은 live 없는
// 개시로 온다. 2행이 그 앞에 있어 표식 + 여유 뒤 첫 조각은 합류하지 않고 새 회차가 된다(D60 (나)).
func (r *Registry) openFromPrevious(in Input, prev previousSession) Decision {
	if r.pastStopMark(prev.stopMarkedAt, in.StartWallUTC) {
		return withBase(openDecision(in, nil, nil), prev.id) // 2행
	}
	if prev.state == "ending" && joinable(prev, in.StartWallUTC, r.opt.ReconnectWindow) &&
		prev.endReason != endReasonInitMismatch {
		if reason := inheritDecline(prev); reason != "" {
			r.opt.Log.Warn("inherit_declined",
				"stream_id", in.StreamID, "seq", in.Seq, "session_id", prev.id, "round_id", prev.roundID, "reason", reason)
		}
	}
	var d Decision
	if inheritable(prev, in.StartWallUTC, r.opt.ReconnectWindow) {
		d = openDecision(in, nil, &prev)
	} else {
		d = openDecision(in, nil, nil)
	}
	switch {
	case prev.roundID == "": // 3행 — 새 회차(계승은 위 판정 그대로)
	case d.plan.inheritsSession != "", joinable(prev, in.StartWallUTC, r.opt.ReconnectWindow) && !prev.roundClosed: // 5 · 6행
		d.plan.roundID = prev.roundID
		if !prev.stopMarkedAt.IsZero() {
			marked := prev.stopMarkedAt
			d.plan.stopMarkedAt = &marked
		}
	}
	return withBase(d, prev.id)
}

// withBase 는 결정의 PDT 기저 세션을 정한다(previousSessionSQL 의 「상태 필터 없음」 계약 — 갈래와 무관하게 직전 회차).
func withBase(d Decision, baseSessionID string) Decision {
	d.BaseSessionID = baseSessionID
	return d
}

// pastStopMark 는 조각이 표식 + 여유 이상에서 시작했는가다. 표식이 없거나 여유가 영값이면 거짓이다.
func (r *Registry) pastStopMark(marked, startWall time.Time) bool {
	if marked.IsZero() || r.opt.StopSlack <= 0 {
		return false
	}
	return !startWall.Before(marked.Add(r.opt.StopSlack))
}

// markSplitDecision 은 표식 분할이다(M6 3.3 결정 1 · 15.2 표 1행) — live 를 intentional 로 끝내고 그 조각으로 새
// 세션을 연다: 비계승 · 불연속 기준 0 · 새 회차 · 표식 없음. PDT 기저는 끝내는 live 다(세션 경계의 PDT 연속).
func markSplitDecision(in Input, live *liveSession) Decision {
	d := openDecision(in, nil, nil)
	d.plan.endingSessionID = live.id
	d.plan.endReason = endReasonIntentional
	d.Outcome = OutcomeOpenFresh
	d.BaseSessionID = live.id
	d.EndingSessionID = live.id
	return d
}

// endedLive 는 endSessionSQL 의 RETURNING 이다 — 끝낸 live 의 회차 · 닫힘 · 표식.
type endedLive struct {
	roundID      *string
	roundClosed  bool
	stopMarkedAt *time.Time
}

// successorRound 는 TD 분할의 새 세션이 물려받는 회차 · 표식이다(15.2 표 4행). 끝낸 live 의 회차가 NULL(M6 전 —
// 3행)이거나 이미 닫혔으면 새 회차 · 표식 없음이다.
func (e endedLive) successorRound() (string, *time.Time) {
	if e.roundID == nil || e.roundClosed {
		return "", nil
	}
	return *e.roundID, e.stopMarkedAt
}

// endLive 는 개시가 끝내는 live 를 ending 으로 보내고 RETURNING 을 돌려준다. 0행이면 ErrLiveLost 다.
func endLive(ctx context.Context, tx pgx.Tx, sessionID, reason string) (endedLive, error) {
	var e endedLive
	err := tx.QueryRow(ctx, endSessionSQL, sessionID, reason).Scan(&e.roundID, &e.roundClosed, &e.stopMarkedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return endedLive{}, fmt.Errorf("%w session_id=%q", ErrLiveLost, sessionID)
	}
	if err != nil {
		return endedLive{}, fmt.Errorf("직전 세션 종료 표시 실패 session_id=%q: %w", sessionID, err)
	}
	return e, nil
}
