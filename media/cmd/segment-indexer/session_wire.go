package main

// 세션 결정자 어댑터 — 조립 지점(계획 4.6).
//
// **이 파일이 존재하는 이유**: 장부(index)는 세션 결정자를 자기가 선언한 인터페이스로
// 받고, 판정(session)은 자기 타입으로 답한다. 둘이 서로를 임포트하지 않는 것이 경계이며
// (계획 3절), 그 경계를 잇는 자리는 프로그램을 조립하는 여기 하나다.
// 어댑터가 없으면 둘 중 하나가 상대를 임포트해야 하고, 그 순간 "장부가 세션 정책을 안다"
// 또는 "판정이 장부 스키마를 안다"가 되어 각각 따로 갈아 끼울 수 없게 된다.

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
)

// sessionDecider 는 session.Registry 를 index.SessionDecider 로 입힌다. 상태는 없다.
type sessionDecider struct{ reg *session.Registry }

// newSessionDecider 는 정책값을 config 에서 받아 결정자를 만든다.
// 값의 집은 config 하나다 — 여기에 기본값을 또 적지 않는다.
func newSessionDecider(floorSlack, obsFresh time.Duration, log *slog.Logger) index.SessionDecider {
	return sessionDecider{reg: session.New(session.Options{
		FloorSlack: floorSlack,
		ObsFresh:   obsFresh,
		Log:        log,
	})}
}

func (d sessionDecider) Decide(ctx context.Context, tx pgx.Tx, in index.SessionInput, now time.Time) (index.SessionDecision, error) {
	op, err := sessionOpOf(in.Op)
	if err != nil {
		return index.SessionDecision{}, err
	}
	dec, err := d.reg.Decide(ctx, tx, session.Input{
		StreamID:     in.StreamID,
		Seq:          in.Seq,
		StartWallUTC: in.StartWallUTC,
		DurationMS:   in.DurationMS,
		Op:           op,
		Obs: session.Observation{
			Publishing:     in.Obs.Publishing,
			ObservedAt:     in.Obs.ObservedAt,
			EpochStartedAt: in.Obs.EpochStartedAt,
			EpochKnown:     in.Obs.EpochKnown,
			EpochSlack:     in.Obs.EpochSlack,
		},
	}, now)
	if err != nil {
		return index.SessionDecision{}, err
	}
	// 결정 자체(개시 계획 포함)를 불투명 값으로 되돌려 준다 — Open 이 그대로 되받으므로
	// 결정과 쓰기가 같은 입력을 본다는 것이 구조로 보장된다.
	return index.SessionDecision{
		Opens:         dec.Outcome == session.OutcomeOpen || dec.Outcome == session.OutcomeOpenFresh,
		SessionID:     dec.SessionID,
		BaseSessionID: dec.BaseSessionID,
		Plan:          dec,
	}, nil
}

func (d sessionDecider) Open(ctx context.Context, tx pgx.Tx, dec index.SessionDecision, firstPDT time.Time) (string, error) {
	plan, ok := dec.Plan.(session.Decision)
	if !ok {
		return "", fmt.Errorf("세션 개시 계획이 이 결정자의 것이 아니다(%T) — Decide 가 준 값을 그대로 넘겨야 한다", dec.Plan)
	}
	return d.reg.Open(ctx, tx, plan, firstPDT)
}

// sessionOpOf 는 장부 쪽 연산을 판정 쪽 연산으로 옮긴다.
//
// **숫자 변환이 아니라 값 대조인 것이 요점이다**: 두 열거는 서로 다른 패키지의 것이라
// 한쪽 순서가 바뀌어도 컴파일러가 잡아 주지 않는다. 모르는 값은 에러로 올려
// 배선 누락이 조용히 어느 갈래로 접히지 않게 한다.
func sessionOpOf(op index.SessionOp) (session.Op, error) {
	switch op {
	case index.SessionOpenOrCurrent:
		return session.OpenOrCurrent, nil
	case index.SessionCurrentOrOpenIfCorroborated:
		return session.CurrentOrOpenIfCorroborated, nil
	case index.SessionCurrentOnly:
		return session.CurrentOnly, nil
	}
	return 0, fmt.Errorf("세션 결정 연산이 지정되지 않았다(%d)", op)
}
