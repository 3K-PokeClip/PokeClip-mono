package main

// live 회차 종료 문장(4.1 오프라인 전이 · L3 gap_run · L2′ stall_seal)의 공통 뒤처리(POK-196 M6-3b 커밋 ④b — ④ 검수
// cc M1 · M2 · db M1 · M2 · L1). 문장이 1행이면 셋이 같은 걸음을 밟고(liveEnded), L3 · L2′ 의 실패는 오프라인 전이의
// offlineFailed 와 같은 꼴로 다룬다(liveEndFailed — 결과 모름이라 적재 요구 · 루프 전체 쉼 · 종료 중 침묵).

import (
	"context"
	"time"
)

// liveEndRetryAfter 는 L3 · L2′ 종료 문장이 실패한 뒤 어느 스트림도 다시 부르지 않는 시간이다 — 문장 한 번이 루프를 최대 약
// 2 × TxnDeadline 세우므로 매 틱 되풀이하지 않는다(4.1 전이의 offlineRetryAfter 와 같은 30초).
const liveEndRetryAfter = 30 * time.Second

// liveEnded 는 live 회차 종료 문장이 회차 sessionID 를 끝냈을 때의 뒤처리다 — 그 회차의 ending 을 캐시에 넘기고, 업로더의
// 그 회차 메모리를 지우며(판단 J86), 정산 몫에 넘긴다(빠른 길 · 상태 표지 — queueEnded).
func (r *rewindLoop) liveEnded(streamID, sessionID string, now time.Time) {
	r.cache.ApplySessionEnding(streamID, sessionID)
	r.forgetUploaderSession(sessionID)
	r.queueEnded(streamID, sessionID, now)
}

// demandAfterUnknown 은 결과를 모르는 종료 문장 실패(시한 · lock_timeout · COMMIT 오류 — 서버가 커밋했을 수 있다) 뒤의 캐시
// 처치다 — 그 스트림에 뷰나 진행 중인 적재가 있으면 적재를 요구한다(힌트 없음 — 커밋됐으면 적재가 캐시 회차를 ending 으로
// 맞춘다). 재시도가 「이미 live 아님」 빈 값으로 끝나도 캐시가 live 에 머물지 않는다.
func (r *rewindLoop) demandAfterUnknown(streamID string) {
	if _, hasView := r.cache.Snapshot(streamID).Cutoff(); hasView || r.cache.Loading(streamID) {
		r.cache.DemandLoad(streamID, nil)
	}
}

// liveEndFailed 는 L3 · L2′ 종료 문장 실패 err 의 처치다 — ERROR msg 한 줄(attrs 를 덧붙인다), 적재 요구, 그리고
// liveEndRetryAfter 동안 루프 전체가 쉰다. 부르는 걸음은 이 틱의 남은 스트림을 멈춘다(DB 가 멈추면 한 틱에 시한이 쌓이지
// 않게). 부른 쪽 ctx 가 끝나 난 실패는 루프가 끝나는 중이라 아무것도 남기지 않는다(offlineFailed 와 같다).
//
// 잠금 경합이면 index 가 WARN topology_lock_timeout 을 이미 남겼고 이 ERROR 가 한 줄 더 남는다 — 4.1 전이(EndLive WARN ·
// session_offline ERROR)와 같은 이중 기록이다. index 가 경합을 밖으로 가르는 값을 내지 않아 여기서 가르지 않는다.
func (r *rewindLoop) liveEndFailed(ctx context.Context, msg, streamID string, err error, now time.Time, attrs ...any) {
	if ctx.Err() != nil {
		return
	}
	r.liveEndRetryAt = now.Add(liveEndRetryAfter)
	r.log.Error(msg, append([]any{"stream_id", streamID, "err", err}, attrs...)...)
	r.demandAfterUnknown(streamID)
}
