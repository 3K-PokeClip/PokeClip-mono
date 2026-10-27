package main

// L3 — 연속 GAP 셋 → ending(사유 gap_run)의 루프 몫(POK-196 M6-3b 커밋 ④ — 설계 M6 3.1 「gap_run(L3)」 · 6절 L3 행 · M4
// r17 4.6.3 사다리 L3 「연속 upload_stall GAP ≥ 3」). 스위치(REWIND_GAP_RUN_END)는 기본 끔이고(kty K5), 꺼져 있으면 조립이
// 종료 손잡이(rewindLoop.endGapRun)를 끼우지 않아 셈만 하고 끝내지 않는다.
//
//	셈    GAP 틱 결과가 GAP 이 섰다고 하면(settleGap) 그 회차 · seq 로 센다. 같은 회차에서 seq 가 바로 앞 GAP 의 다음이면
//	      하나 늘고, 아니면 1 로 돌아간다 — 사이에 실제 ③ 이 와서 머리가 나아가면 다음 GAP 은 seq 를 건너뛴다
//	발동  셋이 되면 다음 holdTick 의 4.1 전이 바로 뒤에서 EndLive 처럼 동기로 index.EndGapRun 을 부른다 — 그 회차가 아직
//	      live 일 때만 끝낸다(회차 술어). 끝낸 뒤 · 실패 처치는 오프라인 전이 · L2′ 와 같다(live_end.go). 재접속 창 안의 다음
//	      조각은 새 회차를 열어 계승 · 합류한다(registry inheritable — gap_run 은 막지 않는다)

import (
	"context"
	"maps"
	"slices"
	"time"
)

// gapRunLength 는 L3 의 문턱이다 — 이어서 선 GAP 수(M4 r17 4.6.3 사다리 표 「≥ 3」 · 같은 판 13절 회부 ① 권고값).
const gapRunLength = 3

// gapRun 은 한 스트림의 L3 셈이다. 영값은 「센 것이 없다」다. 종료 문장 실패 뒤의 쉼은 셈이 아니라 루프에 있다
// (rewindLoop.liveEndRetryAt) — 회차가 바뀌어 셈이 새로 시작해도 쉼은 이어진다.
type gapRun struct {
	session string
	last    int64
	n       int
}

// after 는 회차 session 의 후보 seq 에 GAP 이 선 뒤의 셈이다.
func (g gapRun) after(session string, seq int64) gapRun {
	if g.n > 0 && g.session == session && seq == g.last+1 {
		return gapRun{session: session, last: seq, n: g.n + 1}
	}
	return gapRun{session: session, last: seq, n: 1}
}

// endGapRuns 는 holdTick 의 L3 걸음이다 — 셈이 문턱에 닿은 스트림마다 그 회차를 끝낸다. 손잡이가 없으면(스위치 끔) · 루프가
// 쉬는 중이면 하지 않고, 첫 실패에서 이 틱의 남은 스트림을 멈춘다(liveEndFailed).
func (r *rewindLoop) endGapRuns(ctx context.Context, now time.Time) {
	if r.endGapRun == nil || now.Before(r.liveEndRetryAt) {
		return
	}
	for _, s := range slices.Sorted(maps.Keys(r.streams)) {
		rec := r.streams[s]
		if rec.gapRun.n < gapRunLength {
			continue
		}
		if !r.endGapRunOf(ctx, s, rec, now) {
			return
		}
	}
}

// endGapRunOf 는 스트림 s 의 셈 rec.gapRun 이 가리키는 회차를 끝낸다. 실패면 liveEndFailed 로 넘기고 거짓이다(셈은 남긴다).
// 섰거나 그 회차가 이미 live 가 아니면 셈을 비운다.
func (r *rewindLoop) endGapRunOf(ctx context.Context, s string, rec *rewindStream, now time.Time) bool {
	session, n := rec.gapRun.session, rec.gapRun.n
	ended, err := r.endGapRun(ctx, s, session)
	if err != nil {
		r.liveEndFailed(ctx, "gap_run_end_failed", s, err, now, "session_id", session, "gaps", n)
		return false
	}
	rec.gapRun = gapRun{}
	if ended == "" {
		r.log.Info("session_gap_run_end", "stream_id", s, "session_id", session, "gaps", n, "result", "not_live")
		return true
	}
	r.log.Info("session_gap_run_end", "stream_id", s, "session_id", ended, "gaps", n, "result", "ended")
	r.liveEnded(s, ended, now)
	return true
}
