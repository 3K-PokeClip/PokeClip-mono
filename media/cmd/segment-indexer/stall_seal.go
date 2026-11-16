package main

// L2′ — 정체 마감 → 봉인 → ending(사유 stall_seal)의 루프 몫(POK-196 M6-3b 커밋 ④b — M4 r17 4.6.3 사다리 L2′ · 4.6.4 E0–E5 ·
// kty 2026-10-27 결정 (나) 「봉인과 함께 세션을 ending 으로」). 스위치(REWIND_STALL_SEAL)는 기본 끔이고(kty K5), 꺼져 있으면
// 조립이 발행자의 봉인 문턱을 0 으로 두고 종료 손잡이(rewindLoop.endStallSeal)를 끼우지 않는다.
//
//	봉인  사다리가 Seal 을 내면(publish.LadderStep) 그 스트림 차선이 비었을 때 봉인(Publisher.EndlistIfCaughtUp)을 낸다 —
//	      저장소를 부르므로 차선이다(설계 3.3). 봉인 작업은 fence 를 잡은 뒤 같은 트랜잭션에서 장부 마지막 seq 를 읽어(시한
//	      0.5초), 캐시 뷰 다음 seq 이상이면 되돌리고 봉인 없이 retry ledger_ahead 로 돌아온다 — 루프가 그 값을 캐시에 알려
//	      (뒤처짐 표지 · 적재 요구) 다음 틱부터 사다리 까닭이 view_behind 가 된다. 못 읽으면 retry ledger_failed 다(POK-591).
//	      서지 않았으면 retry 는 다음 holdTick 의 사다리가 다시 내고, failed 는 30초 쉰다
//	끝냄  봉인이 서면 다음 holdTick 의 L3 걸음 뒤에서 EndLive 처럼 동기로 index.EndStallSeal 을 부른다 — 그 회차가 아직 live
//	      일 때만 끝낸다(회차 술어). 끝낸 뒤 · 실패 처치는 오프라인 전이 · L3 와 같다(live_end.go). 동결은 봉인이 이미 했다(dvr frozen — 정산이
//	      phase 1 을 건너뛴다 · M4 r17 4.5.3). 재접속 창 안의 다음 조각은 새 회차를 열어 계승 · 합류한다(registry
//	      inheritable — stall_seal 은 막지 않는다)

import (
	"context"
	"maps"
	"slices"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/finalize"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
)

// stallSealRetryAfter 는 봉인이 failed 인 뒤 그 스트림이 다시 봉인하기까지다(종료 문장 실패의 쉼과 같은 30초 — 그쪽은 루프
// 전체의 쉼이다 · live_end.go). 봉인 retry 는 쉬지 않는다 — 갱신 의무 안에 닫는 것이 L2′ 의 목적이다.
const stallSealRetryAfter = liveEndRetryAfter

// stallSeal 은 한 스트림의 L2′ 상태다. 영값은 「봉인 · 종료 대기가 없다」다.
type stallSeal struct {
	// sealed 는 봉인이 선 회차다 — 다음 holdTick 이 ending 으로 보낸다(빈 값 = 없음).
	sealed string
	// retryAt 은 failed 봉인 뒤 다시 봉인할 시각이다(영값 = 바로).
	retryAt time.Time
	// noted 는 장부 확인 결과(ledger_ahead · ledger_failed)의 WARN 을 마지막으로 남긴 (회차, 사유, 장부 끝)이다 — 같으면 다시
	// 남기지 않는다. 다른 봉인 결과가 오면 비운다.
	noted ledgerNote
}

// ledgerNote 는 장부 확인 WARN 한 줄의 열쇠다.
type ledgerNote struct {
	session, reason string
	last            int64
}

// sealResult 는 봉인 작업 한 번의 결과다 — viewNext 는 그 작업에 넘긴 캐시 뷰 다음 seq 다(ledger_ahead 신호의 속성).
type sealResult struct {
	sessionID string
	viewNext  int64
	out       publish.FreezeOutcome
}

// startSeal 은 스트림 s 의 소유 회차 session 에 봉인을 낸다. 종료 손잡이가 없거나(스위치 끔 — 봉인만 되고 끝내지 못하는
// live 회차를 만들지 않는다) 차선이 돌거나 그 회차가 이미 종료 대기거나(봉인이 섰다 — 종료 문장이 실패해 루프가 쉬는 동안
// 다시 봉인하지 않는다) failed 뒤 쉬는 중이면 내지 않는다.
func (r *rewindLoop) startSeal(ctx context.Context, s, session string, now time.Time) {
	rec := r.stream(s)
	if r.endStallSeal == nil || rec.running != nil || rec.stallSeal.sealed == session || now.Before(rec.stallSeal.retryAt) {
		return
	}
	next, viewed := r.cache.ViewNext(s)
	if !viewed {
		return // 사다리가 돌았다면 뷰가 있다 — 견줄 뷰 끝이 없으면 봉인하지 않는다
	}
	seal := r.work.seal
	if !r.lanes.Start(ctx, s, func(ctx context.Context) publish.Result {
		return publish.Result{Kind: publish.KindSeal, Value: sealResult{sessionID: session, viewNext: next, out: seal(ctx, s, session, next)}}
	}) {
		return
	}
	rec.running = &laneJob{kind: jobSeal, session: session}
}

// sealDone 은 봉인 결과다 — 섰으면(frozen · empty) 그 회차를 종료 대기에 넣고, failed 면 쉰다. 그 사이 병합된 틱 요청은 다시
// 내지 않는다 — 봉인한 목록에는 낼 틱이 없고, 서지 않았으면 다음 holdTick 이 다시 본다. 미룬 적재는 낸다(resume).
func (r *rewindLoop) sealDone(ctx context.Context, res publish.Result, now time.Time) {
	s := res.StreamID
	rec := r.stream(s)
	rec.running = nil
	out, _ := res.Value.(sealResult)
	if reason := out.out.Reason; reason == publish.ReasonLedgerAhead || reason == publish.ReasonLedgerFailed {
		r.ledgerChecked(s, rec, out)
		r.resume(ctx, s, false, now)
		return
	}
	rec.stallSeal.noted = ledgerNote{}
	switch outcomeOf(out.out) {
	case finalize.OutcomeDone:
		rec.stallSeal = stallSeal{sealed: out.sessionID}
		r.log.Info("stall_seal", "stream_id", s, "session_id", out.sessionID, "result", out.out.Result)
	case finalize.OutcomeFailed:
		rec.stallSeal.retryAt = now.Add(stallSealRetryAfter)
		r.log.Warn("stall_seal", "stream_id", s, "session_id", out.sessionID, "result", out.out.Result,
			"reason", out.out.Reason, "err", out.out.Err)
	default:
		r.log.Info("stall_seal", "stream_id", s, "session_id", out.sessionID, "result", out.out.Result,
			"reason", out.out.Reason, "err", out.out.Err)
	}
	r.resume(ctx, s, false, now)
}

// ledgerChecked 는 봉인 작업의 장부 확인이 봉인을 막은 결과다(retry — 다음 holdTick 의 사다리가 다시 낸다).
//
//	ledger_ahead   캐시가 장부보다 뒤처졌다 — cache.ObserveLedgerLast 로 알리면 다음 틱부터 사다리 까닭이 view_behind 라
//	               봉인에서 빠진다
//	ledger_failed  장부를 못 읽었다(DB 오류 · 확인 시한) — 확인 없이 봉인하지 않는다. 봉인 retry 는 쉬지 않는다
//
// WARN 은 같은 (회차, 사유, 장부 끝)에 한 줄이다 — DB 장애 동안 틱마다 같은 줄이 쌓이지 않는다.
func (r *rewindLoop) ledgerChecked(s string, rec *rewindStream, out sealResult) {
	if out.out.Reason == publish.ReasonLedgerAhead {
		r.cache.ObserveLedgerLast(s, out.out.LedgerLast)
	}
	note := ledgerNote{session: out.sessionID, reason: out.out.Reason, last: out.out.LedgerLast}
	if rec.stallSeal.noted == note {
		return
	}
	rec.stallSeal.noted = note
	r.log.Warn("stall_seal", "stream_id", s, "session_id", out.sessionID, "result", out.out.Result,
		"reason", out.out.Reason, "ledger_last", out.out.LedgerLast, "view_next", out.viewNext, "err", out.out.Err)
}

// endStallSeals 는 holdTick 의 L2′ 걸음이다 — 봉인이 선 스트림마다 그 회차를 끝낸다. 손잡이가 없으면(스위치 끔) · 루프가 쉬는
// 중이면(L3 의 실패도 든다) 하지 않고, 첫 실패에서 이 틱의 남은 스트림을 멈춘다(liveEndFailed).
func (r *rewindLoop) endStallSeals(ctx context.Context, now time.Time) {
	if r.endStallSeal == nil || now.Before(r.liveEndRetryAt) {
		return
	}
	for _, s := range slices.Sorted(maps.Keys(r.streams)) {
		rec := r.streams[s]
		if rec.stallSeal.sealed == "" {
			continue
		}
		if !r.endStallSealOf(ctx, s, rec, now) {
			return
		}
	}
}

// endStallSealOf 는 스트림 s 의 봉인이 선 회차를 끝낸다. 실패면 liveEndFailed 로 넘기고 거짓이다(대기는 남긴다). 섰거나 그
// 회차가 이미 live 가 아니면 대기를 비운다.
func (r *rewindLoop) endStallSealOf(ctx context.Context, s string, rec *rewindStream, now time.Time) bool {
	session := rec.stallSeal.sealed
	ended, err := r.endStallSeal(ctx, s, session)
	if err != nil {
		r.liveEndFailed(ctx, "stall_seal_end_failed", s, err, now, "session_id", session)
		return false
	}
	rec.stallSeal = stallSeal{}
	if ended == "" {
		r.log.Info("session_stall_seal_end", "stream_id", s, "session_id", session, "result", "not_live")
		return true
	}
	r.log.Info("session_stall_seal_end", "stream_id", s, "session_id", ended, "result", "ended")
	r.liveEnded(s, ended, now)
	return true
}
