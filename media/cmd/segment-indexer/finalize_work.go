package main

// 정산 · 상태 전달 · 계승 해제 차선 작업의 몸체(POK-196 M6-3b 커밋 ③ — 설계 M6 3.2 「차선 계약」). 작업은 입력 값만 받아
// 차선 고루틴에서 돌고 결과를 값으로 돌려준다 — 캐시 · 루프 메모리를 만지지 않는다(D2 · 기존 Lanes 계약). 운영 몸체는
// index 종료 문장과 발행자 동결 메서드를 묶고, 시험은 함수 칸을 가짜로 바꾼다(laneWork 와 같은 꼴 — 새 인터페이스 없음).

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/finalize"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
)

// revokeNoPublisher 는 발행 몫이 없어(꺼짐 · 강등) 계승 해제를 하지 않은 사유다 — Head 를 볼 수 없다.
const revokeNoPublisher = "no_publisher"

// finalizeScanLimit 은 정산 · 회차 스캔의 쪽 크기다(설계 3.2 — 50).
const finalizeScanLimit = 50

// finalizeWork 는 정산 몫 차선 작업의 몸체다.
type finalizeWork struct {
	scan     func(ctx context.Context, after index.ScanCursor) finalScan
	load     func(ctx context.Context, sessionID string) finalLoad
	step     func(ctx context.Context, s index.EndingSession, d finalize.Decision) finalStep
	playback func(ctx context.Context, streamID string) playbackRead
	revoke   func(ctx context.Context, streamID, sessionID string) publish.RevokeOutcome
}

// finalScan 은 스캔 한 번의 결과다 — 정산 스캔 행 · 다음 커서 · 쪽이 가득 찼는가 · 이번에 닫은 회차의 스트림.
type finalScan struct {
	rows   []index.EndingSession
	next   index.ScanCursor
	full   bool
	closed []string
	err    error
}

// finalLoad 는 빠른 길 행 읽기의 결과다.
type finalLoad struct {
	sessionID string
	row       index.EndingSession
	found     bool
	err       error
}

// finalStep 은 정산 걸음 하나의 결과다. progressed 는 장부가 다음 상태로 갔다(곧바로 그 행을 다시 읽어 다음 걸음을 낸다) ·
// notify 는 상태 전달 값이 바뀌었다(회차 완결 — PlaybackChanged) · hold 는 걸음이 장부에서 찾은 보류 사유다.
type finalStep struct {
	row        index.EndingSession
	d          finalize.Decision
	out        finalize.Outcome
	freeze     publish.FreezeOutcome
	progressed bool
	notify     bool
	hold       string
	err        error
}

// playbackRead 는 상태 전달 읽기 한 번의 결과다.
type playbackRead struct {
	streamID string
	st       index.PlaybackState
	ok       bool
	err      error
}

// revokeResult 는 계승 해제 작업 한 번의 결과다.
type revokeResult struct {
	sessionID string
	out       publish.RevokeOutcome
}

// newFinalizeWork 는 운영 몸체다 — 종료 문장은 pool(인덱서 풀)로, S3 걸음은 발행자 pub 로 간다. pub 이 nil 이면(꺼짐 ·
// 강등) 판정이 S3 걸음을 내지 않는다(finalize.Next) — 그래도 오면 강등 보류로 돌려준다.
func newFinalizeWork(pool *pgxpool.Pool, pub *publish.Publisher, o index.CloseOptions, enqueue index.EndedEnqueuer) finalizeWork {
	return finalizeWork{
		scan: func(ctx context.Context, after index.ScanCursor) finalScan {
			return scanFinalize(ctx, pool, after, o, enqueue)
		},
		load: func(ctx context.Context, id string) finalLoad {
			row, found, err := index.LoadEndingSession(ctx, pool, id)
			return finalLoad{sessionID: id, row: row, found: found, err: err}
		},
		step: func(ctx context.Context, s index.EndingSession, d finalize.Decision) finalStep {
			return runFinalStep(ctx, finalDeps{pool: pool, pub: pub, close: o, enqueue: enqueue}, s, d)
		},
		playback: func(ctx context.Context, stream string) playbackRead {
			st, ok, err := index.LoadStreamPlayback(ctx, pool, stream)
			return playbackRead{streamID: stream, st: st, ok: ok, err: err}
		},
		revoke: func(ctx context.Context, stream, id string) publish.RevokeOutcome {
			if pub == nil {
				return publish.RevokeOutcome{Reason: revokeNoPublisher}
			}
			return pub.RevokeInheritance(ctx, stream, id)
		},
	}
}

// scanFinalize 는 스캔 한 번이다 — 정산 스캔 한 쪽과, 닫힘 조건이 참인 회차를 모두 닫는다(회차 스캔은 커서가 없다 — 고른
// 회차는 이 걸음에서 닫혀 다음 스캔에서 빠진다). 회차 하나가 실패해도 나머지는 닫는다(첫 오류를 돌려준다 — 다음 스캔에 다시).
func scanFinalize(ctx context.Context, pool *pgxpool.Pool, after index.ScanCursor, o index.CloseOptions, enqueue index.EndedEnqueuer) finalScan {
	rows, next, err := index.ListEndingSessions(ctx, pool, after, finalizeScanLimit, o)
	if err != nil {
		return finalScan{next: after, err: err}
	}
	out := finalScan{rows: rows, next: next, full: len(rows) == finalizeScanLimit}
	rounds, err := index.ListClosableRounds(ctx, pool, finalizeScanLimit, o)
	if err != nil {
		out.err = err
		return out
	}
	for _, r := range rounds {
		closed, err := index.CloseRound(ctx, pool, r, o, enqueue)
		if err != nil && out.err == nil {
			out.err = err
		}
		if closed {
			out.closed = append(out.closed, r.StreamID)
		}
	}
	return out
}

// finalDeps 는 걸음 몸체가 쓰는 것이다.
type finalDeps struct {
	pool    *pgxpool.Pool
	pub     *publish.Publisher
	close   index.CloseOptions
	enqueue index.EndedEnqueuer
}

// runFinalStep 은 판정 d 의 걸음을 한 번 한다. 장부 문장의 오류는 재시도(다음 걸음 · 다음 스캔)이고 오류 칸에 싣는다.
func runFinalStep(ctx context.Context, deps finalDeps, s index.EndingSession, d finalize.Decision) finalStep {
	res := finalStep{row: s, d: d, out: finalize.OutcomeDone}
	switch d.Step {
	case finalize.StepFreezeDVR, finalize.StepSealDVR, finalize.StepFailDVR, finalize.StepRecoverOrphan:
		return runDVRStep(ctx, deps, res)
	case finalize.StepEmptyFreezeDVR:
		ok, err := index.FreezeEmptyDVR(ctx, deps.pool, s.SessionID)
		return res.ledger(ok, err)
	case finalize.StepCloseChain:
		_, ok, err := index.CloseChain(ctx, deps.pool, s.SessionID, deps.close)
		return res.ledger(ok, err)
	}
	c, err := index.LoadChain(ctx, deps.pool, s.StreamID, s.SessionID)
	if err != nil {
		return res.ledger(false, err)
	}
	switch d.Step {
	case finalize.StepFreezeVOD:
		if deps.pub == nil {
			res.hold = finalize.HoldPublishDegraded
			return res
		}
		res.freeze = deps.pub.FreezeVOD(ctx, c, d.Abandon)
		res.out = outcomeOf(res.freeze)
		res.progressed = res.out == finalize.OutcomeDone || res.out == finalize.OutcomeFailed
		return res
	case finalize.StepEmptyFreezeVOD:
		ok, err := index.FreezeEmptyVOD(ctx, deps.pool, c)
		if err == nil && !ok {
			res.hold = finalize.HoldPublishOffWithTrace // 사슬 구성원에 ③ 흔적이 있다
		}
		return res.ledger(ok, err)
	case finalize.StepFailVOD:
		ok, err := index.FailChainVOD(ctx, deps.pool, c)
		return res.ledger(ok, err)
	case finalize.StepMarkEnded:
		n, err := index.MarkChainEnded(ctx, deps.pool, c, deps.close, deps.enqueue)
		res = res.ledger(n > 0, err)
		res.notify = n > 0
		return res
	}
	return res
}

// runDVRStep 은 phase 1 의 S3 걸음이다. 3단(FailDVR)과 좌초 회수의 「실패」는 장부를 freeze_failed 로 쓴 종국이라 섰다로 센다.
func runDVRStep(ctx context.Context, deps finalDeps, res finalStep) finalStep {
	if deps.pub == nil {
		res.hold = finalize.HoldPublishDegraded
		return res
	}
	s := res.row
	switch res.d.Step {
	case finalize.StepFreezeDVR:
		res.freeze = deps.pub.FreezeDVR(ctx, s.StreamID, s.SessionID)
	case finalize.StepSealDVR:
		res.freeze = deps.pub.Endlist(ctx, s.StreamID, s.SessionID)
	case finalize.StepFailDVR:
		res.freeze = deps.pub.FailDVR(ctx, s.StreamID, s.SessionID)
	case finalize.StepRecoverOrphan:
		res.freeze = deps.pub.RecoverOrphan(ctx, s.StreamID, s.SessionID)
	}
	res.out = outcomeOf(res.freeze)
	if res.out == finalize.OutcomeFailed && (res.d.Step == finalize.StepFailDVR || res.d.Step == finalize.StepRecoverOrphan) {
		res.out = finalize.OutcomeDone
	}
	res.progressed = res.out == finalize.OutcomeDone || res.out == finalize.OutcomeFailed
	return res
}

// ledger 는 장부 문장 걸음의 결과다 — 섰으면 다음 걸음으로 간다. 오류는 재시도다(잠금 경합 포함 — index 가 이미 남겼다).
func (r finalStep) ledger(ok bool, err error) finalStep {
	if err != nil {
		r.out, r.err = finalize.OutcomeRetry, err
		return r
	}
	r.progressed = ok
	return r
}

// outcomeOf 는 발행 층 결과를 판정의 결과로 줄인다 — 정산 대기(unsettled)는 장애 재시도와 가른다.
func outcomeOf(o publish.FreezeOutcome) finalize.Outcome {
	switch {
	case o.Result == publish.ResultFrozen, o.Result == publish.ResultFrozenWithGaps, o.Result == publish.ResultEmpty:
		return finalize.OutcomeDone
	case o.Retry && o.Reason == publish.ReasonUnsettled:
		return finalize.OutcomeUnsettled
	case o.Retry:
		return finalize.OutcomeRetry
	}
	return finalize.OutcomeFailed
}

// contended 는 err 가 종료 문장의 잠금 경합(다음 주기에 다시)인가다 — index 가 WARN 을 이미 남겼다.
func contended(err error) bool { return errors.Is(err, index.ErrTopologyContended) }
