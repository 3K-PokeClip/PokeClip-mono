package main

// 루프 보조 타입의 발행 몫 — 틱 입력과 발사 · 요구 적재 · 차선 결과의 반영 · 쉬는 뷰 버리기 · 종료 순서(체크리스트 474 A-3 3 ·
// A-5 · A-6 · 판단 J74 · J78–J81 · J84 · J87). 흐름(holdTicks · 완료 case)은 rewind_loop.go 다.

import (
	"context"
	"errors"
	"maps"
	"slices"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/cache"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
)

// dispatchTicks 는 이 holdTick 의 틱을 낸다(체크리스트 474 A-4 4 · 판단 J80 ⑴ ⑵) — 후보는 Dirty 원소가 닿은 스트림 · 직전 발행
// 틱이 실패한 스트림(재발행 표지 — 이 holdTick 이 쓴다) · 사다리 L2 가 선 스트림이다. L2 가 선 스트림은 평시 틱 대신 GAP 틱
// 하나다. 적재가 끝난 스트림의 틱과 병합된 요청의 재실행은 완료 case 가 낸다.
func (r *rewindLoop) dispatchTicks(ctx context.Context, touched map[string]bool, gaps map[string]int64, now time.Time) {
	due := maps.Clone(touched)
	for s, rec := range r.streams {
		if rec.republish {
			due[s], rec.republish = true, false
		}
	}
	for s := range gaps {
		due[s] = true
	}
	for _, s := range slices.Sorted(maps.Keys(due)) {
		var gap *int64
		if k, ok := gaps[s]; ok {
			gap = &k
		}
		r.requestTick(ctx, s, gap, now)
	}
}

// requestTick 은 스트림 s 에 발행 틱 하나를 낸다(체크리스트 474 A-4 4 · A-6) — gap 이 있으면 그 행을 후보로 한 GAP 틱이다.
//
//	소유 회차  cache.Owner — 없으면(뷰 없음 · 적재 중 · 부정 표식) 틱 0
//	게이트     publish.ShouldTick 거짓이면 틱 0 · P0 왕복 0(ending · init 미확정 · 적재 중 — 계획 4.5 A0 결정 3)
//	입력       tickInput — 서지 않으면 틱 0
//
// 차선이 돌고 있으면 Lanes 가 요청을 병합한다(이 작업은 버린다 — 결과를 받으면 새 입력으로 한 번 더 · 판단 J80 ⑷). 낸 틱은 루프의
// in-flight 표에 적고, 평시 틱이면 계산한 창의 꼬리를 다음 계산의 prevTail 로 둔다(boundary.Compute 「직전 계산이 돌려준 TailSeq」).
func (r *rewindLoop) requestTick(ctx context.Context, s string, gap *int64, now time.Time) {
	owner, ok := r.cache.Owner(s)
	if !ok {
		return
	}
	rec := r.stream(s)
	rec.observeOwner(owner.SessionID, now)
	if !publish.ShouldTick(owner.RewindSession, r.cache.Loading(s)) {
		return
	}
	in, w, ok := r.tickInput(ctx, s, owner, rec, gap, now)
	if !ok {
		return
	}
	st, tick := rec.pub, r.work.tick
	if !r.lanes.Start(ctx, s, func(ctx context.Context) publish.Result {
		return publish.Result{Kind: publish.KindPublish, Publish: tick(ctx, st, in)}
	}) {
		return
	}
	rec.running = &laneJob{kind: jobTick, session: owner.SessionID, window: w, gap: gap}
	if gap == nil {
		rec.prevTail = w.TailSeq
	}
}

// tickInput 은 스트림 s 의 소유 회차 owner 목록에 낼 틱의 입력과 계산한 창이다(체크리스트 474 A-6 · 계획 4.5 A1 결정 1 · 4 · 6).
//
//	창       평시 = boundary.Compute(cache.Snapshot, prevTail) · GAP = 후보를 settled 로 덮은 겹침(publish.WithGap — ⓑ)
//	목록     cache.Playlist 결과 그대로(StreamID · Owner 를 바꾸지 않는다 — 보안 인계). 거짓이면 요구 ②(반복 제한 — 판단 J79 ⑵) ·
//	         행 0 이면 낼 것이 없다(틱이 P0 뒤 빈 창으로 끝날 왕복을 아낀다)
//	DISC-SEQ P 가 있으면 절대식(NextDiscontinuitySequence — 캐시에서 다시 뽑은 P 의 행을 대조한다). 어긋나면 틱 0 · 요구 ④
//	         (prevMismatch). P 가 없으면 소유 회차의 개시 base(계승 취소면 0)
//
// 입력이 서지 않으면 거짓이다. 스냅숏 슬라이스는 워커로 넘기지 않는다 — 넘기는 것은 Playlist 가 만든 새 값뿐이다(A-4 8).
func (r *rewindLoop) tickInput(ctx context.Context, s string, owner cache.Session, rec *rewindStream, gap *int64, now time.Time) (publish.TickInput, boundary.Window, bool) {
	snap := r.cache.Snapshot(s)
	if gap != nil {
		snap = publish.WithGap(snap, *gap)
	}
	w, _ := boundary.Compute(snap, rec.prevTail)
	pl, ok := r.cache.Playlist(s, owner.SessionID, w)
	if !ok {
		r.redemand(s, nil, now)
		return publish.TickInput{}, w, false
	}
	if len(pl.Rows) == 0 {
		return publish.TickInput{}, w, false
	}
	disc := owner.DiscontinuityBase
	if p := rec.pub.Prev; p != nil {
		prevRows, _ := r.cache.Playlist(s, owner.SessionID, boundary.Window{TailSeq: p.MediaSequence, HeadSeq: p.PublishedSeq})
		d, err := publish.NextDiscontinuitySequence(p.Published, prevRows, pl.Rows[0].Seq)
		if err != nil {
			r.prevMismatch(ctx, s, prevMismatchOf(err, p.MediaSequence), now)
			return publish.TickInput{}, w, false
		}
		disc = d
	}
	return publish.TickInput{Playlist: pl, DiscontinuitySequence: disc, GapCandidate: gap}, w, true
}

// prevMismatchOf 는 NextDiscontinuitySequence 의 오류 err 를 P 재구성 불일치로 읽는다 — 끊김 표시를 셀 수 없는 행(회차 목록에 없는
// 회차)도 확인되지 않은 P 라 같은 처치다(계획 4.5 A1 결정 4 — 힌트는 P.MSN).
func prevMismatchOf(err error, hint int64) *publish.PrevMismatchError {
	var m *publish.PrevMismatchError
	if errors.As(err, &m) {
		return m
	}
	return &publish.PrevMismatchError{LoadHint: hint, Reason: err.Error()}
}

// prevMismatch 는 P 재구성 불일치 m 의 처치다(계획 4.5 A1 결정 4 · A3 결정 6 ④ · 판단 J79 ⑵ · J84) — 그 스트림에 적재를 요구하고
// (힌트 m.LoadHint) ERROR rewind_ledger_load_degraded(reason=prev_mismatch) 한 줄을 남긴다. 같은 까닭이 이어지면 첫 요구 뒤로는
// 감시 주기에만 다시 요구하고 그때마다 남긴다 — 래퍼는 부를 때마다 한 줄이라 되풀이 제한은 여기서 한다.
func (r *rewindLoop) prevMismatch(ctx context.Context, s string, m *publish.PrevMismatchError, now time.Time) {
	if r.redemand(s, &m.LoadHint, now) {
		r.pub.ReportPrevMismatch(ctx, s, m)
	}
}

// redemand 는 반복 제한 갈래(④ P 대조 실패 · ② Playlist 거짓)의 적재 요구다(판단 J79 ⑵) — 스트림마다 이 갈래가 마지막으로 요구한
// 뒤 감시 주기(30초 — 새 값 0)가 지났을 때만 요구 목록에 올리고 참을 돌려준다. 발사(dispatchLoads)는 묶지 않는다.
func (r *rewindLoop) redemand(s string, hint *int64, now time.Time) bool {
	rec := r.stream(s)
	if !rec.redemandAt.IsZero() && now.Sub(rec.redemandAt) < r.watchEvery {
		return false
	}
	rec.redemandAt = now
	r.cache.DemandLoad(s, hint)
	return true
}

// dispatchLoads 는 요구 적재를 낸다(체크리스트 474 A-4 5 · 판단 J79 ⑴) — holdTick 마다, 그리고 결과 모름의 요구를 받은 완료 case
// 에서(publishDone) 부른다. 실패한 적재의 재요구 시각이 지난 스트림을 먼저 요구 목록에 올리고(판단 J46), 쌓인 요구마다 예외 없이
// BeginLoad 와 적재 작업을 낸다(빠지면 캐시가 스스로 연 적재가 영구히 멈춘다 — 보안 미확인 4). 적재 범위의 되짚기 · 행 수 상한은
// 캐시 튜너블이다(Options 영값 = 기본값 — 판단 J87 ⑶).
func (r *rewindLoop) dispatchLoads(ctx context.Context, now time.Time) {
	for s, rec := range r.streams {
		if !rec.loadRetryAt.IsZero() && !now.Before(rec.loadRetryAt) {
			rec.loadRetryAt = time.Time{}
			r.cache.DemandLoad(s, nil)
		}
	}
	for _, d := range r.cache.DemandedLoads() {
		opt := r.cache.Options.WithDefaults()
		token := r.cache.BeginLoad(d.StreamID, d.Hint)
		r.startLoad(ctx, d.StreamID, publish.LoadInput{
			StreamID: d.StreamID, Token: uint64(token),
			Bounds: index.LedgerBounds{Hint: d.Hint, Lookback: opt.LedgerLookback, RowCap: opt.LedgerRowCap},
		})
	}
}

// startLoad 는 BeginLoad 를 마친 적재 in 을 스트림 차선에 낸다. 차선이 돌고 있으면 미뤄 두고 그 결과를 받은 뒤 낸다(resume) — 더
// 늦은 요구가 오면 그 입력(새 토큰)으로 바꾼다.
func (r *rewindLoop) startLoad(ctx context.Context, s string, in publish.LoadInput) {
	rec, load := r.stream(s), r.work.load
	if !r.lanes.Start(ctx, s, func(ctx context.Context) publish.Result {
		return publish.Result{Kind: publish.KindLoad, Load: load(ctx, in)}
	}) {
		rec.pendingLoad = &in
		return
	}
	rec.running, rec.pendingLoad = &laneJob{kind: jobLoad}, nil
}

// publishDone 은 스트림 s 의 발행 작업(틱 · lazy 갱신) 결과 out 을 반영한다(체크리스트 474 A-5 1).
//
//	캐시      Err 가 있어도 결과 칸을 모두 반영한다(ⓒ — reflectOutcome) · GAP 틱이면 settleGap
//	상태      소유 회차가 그대로면 out.State 로 갈아 끼운다(바뀌었으면 새 회차는 영값에서 — 버린다)
//	발행 성공  PublishedAt · Trim(마지막으로 계산한 꼬리, P.MSN — 뮤테이션 86) · 재발행 표지를 푼다
//	틱 실패    재발행 표지(판단 J80 ⑶ — Published 거짓 ∧ (Err ∨ fence 를 쥔 채 화해 예약)). 같은 본문 생략 · 빈 창 · 다른 writer
//	          의 fence(fence 를 쥐지 못하고 끝남)는 실패가 아니다 — 다시 틱을 내면 평시 상한(틱당 ≤ 3)이 깨진다
//	Err       ERROR rewind_job_failed — 같은 오류가 이어지면 한 줄(noteJobError)
//	요구      결과 모름의 요구(DemandLoad — P2′ · GAP COMMIT)면 다음 holdTick 을 기다리지 않고 요구를 발사한다(dispatchLoads).
//	          기다리면 그 사이에 닿은 ③ 결과나 병합된 재실행이, 서버가 커밋했을 수 있는 사실(GAP 행 · 계승 해제)을 모르는 캐시로
//	          틱을 낸다 — k 가 일반 줄로 올라간 뒤 적재가 그 줄을 GAP 으로 바꾼다(G-1 창 · 계획 커밋 순서 5 〔r53〕). 적재가 열리면
//	          캐시가 「모른다」(Owner 거짓)를 돌려줘 적재가 끝날 때까지 그 스트림의 틱이 없다
//
// 끝으로 미룬 적재나 병합된 틱 요청의 재실행을 낸다(resume).
func (r *rewindLoop) publishDone(ctx context.Context, s string, out publish.Outcome, rerun bool, now time.Time) {
	rec := r.stream(s)
	job := rec.running
	rec.running = nil
	r.reflectOutcome(s, job.session, out)
	if job.gap != nil {
		r.settleGap(rec, job, out)
	}
	if job.session == rec.owner {
		rec.pub = out.State
		switch {
		case out.Published:
			rec.publishedAt, rec.republish = now, false
			r.cache.Trim(s, rec.prevTail, out.State.Prev.MediaSequence)
		case job.kind == jobTick && (out.Err != nil || (out.State.ReconcileDue && out.State.FenceHeld)):
			rec.republish = true
		}
	}
	r.noteJobError(ctx, s, job.session, out.Err)
	if out.DemandLoad {
		r.dispatchLoads(ctx, now)
	}
	r.resume(ctx, s, rerun, now)
}

// reflectOutcome 은 발행 결과 out 의 캐시 칸을 스트림 s(차선 키 — 보안 인계)의 캐시에 넣는다 — Revoked 는 그 작업의 소유 회차
// session 의 계승 취소, DemandLoad 는 요구 적재(받는 자리 로그 없음 — 판단 J91), UploadedSeq 는 ③ 확정(GapSeq 보다 먼저 — J18),
// GapSeq 는 GAP 원장 등록이다.
func (r *rewindLoop) reflectOutcome(s, session string, out publish.Outcome) {
	if out.Revoked {
		r.cache.ApplyInheritanceRevoked(s, session)
	}
	if out.DemandLoad {
		r.cache.DemandLoad(s, nil)
	}
	if out.UploadedSeq != nil {
		r.cache.ApplyPlaybackUploaded(s, *out.UploadedSeq)
	}
	if out.GapSeq != nil {
		r.cache.ApplyPublishedGap(s, *out.GapSeq)
	}
}

// settleGap 은 GAP 틱 job 의 결과 out 으로 사다리 사실을 갈아 끼운다(363 ⓐ · J26 · kty 결정 2026-09-29 — 「L2 가 서지
// 못함」은 루프가 싣는 사실이다) — GAP 이 섰으면 겹침 창의 꼬리를 다음 계산의 prevTail 로 쓰고, DB 가 후보를 이미
// uploaded 로 봤으면 비우며, 둘 다 없으면(서지 않음 — 준비 단계에서 끝난 틱도 든다 · 판단 J82) 그 후보를 다음 평가의
// UnstoodGapSeq 로 싣는다. GAP 이 선 결과는 L3 셈에도 넣는다(gap_run.go).
func (r *rewindLoop) settleGap(rec *rewindStream, job *laneJob, out publish.Outcome) {
	rec.unstoodGap = nil
	switch {
	case out.GapSeq != nil:
		rec.prevTail = job.window.TailSeq
		rec.gapRun = rec.gapRun.after(job.session, *out.GapSeq)
	case out.UploadedSeq == nil:
		k := *job.gap
		rec.unstoodGap = &k
	}
}

// noteJobError 는 스트림 s 의 발행 작업 오류 err 를 남긴다(판단 J78 ⑴ · kty 결정 ⑤) — ERROR rewind_job_failed(job=publish · stream ·
// session · err)이고, 같은 스트림에서 이어지는 같은 오류는 한 줄이다(오류 없는 결과가 끼면 다시 남긴다 — noteAbort 형). 루프
// 수명 ctx 가 끝났으면 래퍼가 남기지 않는다.
func (r *rewindLoop) noteJobError(ctx context.Context, s, session string, err error) {
	rec := r.stream(s)
	if err == nil {
		rec.lastErr = ""
		return
	}
	if msg := err.Error(); msg != rec.lastErr {
		rec.lastErr = msg
		r.pub.ReportJobError(ctx, err, "job", "publish", "stream", s, "session", session)
	}
}

// resume 은 스트림 s 의 차선이 빈 뒤 할 일을 낸다 — 미룬 적재가 있으면 그것을, 아니면 병합된 틱 요청이 있었을 때(rerun) 새 입력으로
// 틱 하나를 낸다(판단 J80 ⑷).
func (r *rewindLoop) resume(ctx context.Context, s string, rerun bool, now time.Time) {
	rec := r.stream(s)
	if in := rec.pendingLoad; in != nil {
		r.startLoad(ctx, s, *in)
		return
	}
	if rerun {
		r.requestTick(ctx, s, nil, now)
	}
}

// loadDone 은 스트림 s 의 적재 작업 결과 out 을 반영한다(체크리스트 474 A-5 2 · 판단 J46).
//
//	실패  CompleteLoad 로 넘기지 않아 적재 중을 둔다 · ERROR load_failed(시도마다 — ReportLoad) · 감시 주기 뒤에 다시 요구한다
//	적용  ReportLoad(드리프트 까닭이면 cache_drift_repaired) · 적재 완료 시각(Forget 입력) · prevTail 0(계획 4.5 A3 결정 7) · 적재 뒤
//	      틱 한 번(뮤테이션 122 — 적재 중에 Ack 한 원소는 발행을 부르지 못했다)
//	버림  토큰이 다르다(다시 연 적재가 있다) — 줄 0
//
// 끝으로 미룬 적재가 있으면 낸다(그때는 다시 요구하지 않는다).
func (r *rewindLoop) loadDone(ctx context.Context, s string, out publish.LoadOutcome, now time.Time) {
	rec := r.stream(s)
	rec.running = nil
	if out.Err != nil {
		r.pub.ReportLoad(ctx, s, out, false)
		if rec.pendingLoad == nil {
			rec.loadRetryAt = now.Add(r.watchEvery)
		}
	} else if applied, drift := r.cache.CompleteLoad(s, cache.LoadToken(out.Token), out.RewindLedger); applied {
		r.pub.ReportLoad(ctx, s, out, drift)
		rec.loadedAt, rec.prevTail, rec.loadRetryAt = now, 0, time.Time{}
		r.requestTick(ctx, s, nil, now)
	}
	r.resume(ctx, s, false, now)
}

// watchDone 은 감시 작업 결과 out 을 반영한다(체크리스트 474 A-5 3 · 계획 4.5 A2 결정 11 · A3 결정 5 · 6 · 판단 J42 · J78 ⑵ ⑶).
//
//	실패  (a) · (c) · 화해 · Forget 을 건너뛴다(세지도 풀지도 않는다) · ERROR rewind_job_failed(job=watch · first — 부팅 목록을 아직
//	      내지 못했는가) — 감시마다 한 줄이고, 첫 감시면 부팅 목록은 다음 감시가 낸다
//	성공  드리프트 대조(AuditDrift) → 화해(ReconcileWatch) → 신호 · (c) 상태(ReportWatch) → 첫 성공이면 부팅 목록(live 회차 가운데
//	      컷오프 있는 스트림마다 적재 요구) → 쉬는 뷰 버리기(forgetIdle)
func (r *rewindLoop) watchDone(ctx context.Context, out publish.WatchOutcome, now time.Time) {
	if out.Err != nil {
		r.pub.ReportJobError(ctx, out.Err, "job", "watch", "first", !r.booted)
		return
	}
	r.cache.AuditDrift(out.Drift)
	repaired := r.cache.ReconcileWatch(out.Live)
	r.absent = r.pub.ReportWatch(ctx, r.absent, out.RewindWatch, repaired)
	if !r.booted {
		r.booted = true
		for _, l := range out.Live {
			if l.HasCutoff {
				r.cache.DemandLoad(l.StreamID, nil)
			}
		}
	}
	r.forgetIdle(now)
}

// forgetIdle 은 오래 쉰 스트림의 뷰나 부정 표식을 버린다(계획 4.5 A3 결정 5 · 뮤테이션 87 · 판단 J71 ⑵ · J72) — 캐시가 든 스트림마다
// (부정 표식 포함) 마지막 행 끝 = 인덱서 커서 꼬리 끝, 커서가 없으면 적재 완료 시각이고 창은 ReconnectWindow 다. 버린 스트림의
// 루프 칸도 지운다. 차선이 돌거나 미룬 적재가 있는 스트림은 건너뛴다 — 그 결과를 받을 칸이 남아야 한다. 소유 회차를 든 채
// 버린 스트림에는 잠정 Ending 을 넣는다(provisionalEnding).
func (r *rewindLoop) forgetIdle(now time.Time) {
	for _, s := range r.cache.Streams() {
		rec := r.streams[s]
		if rec != nil && (rec.running != nil || rec.pendingLoad != nil) {
			continue
		}
		lastEnd, ok := r.ix.IngestTail(s)
		if !ok && rec != nil {
			lastEnd = rec.loadedAt
		}
		owner, owned := r.cache.Owner(s) // Forget 뒤에는 읽을 수 없다 — 잠정 Ending 의 회차(설계 M5 3.3)
		if r.cache.Forget(s, lastEnd, now, r.reconnectWindow) {
			delete(r.streams, s)
			if owned {
				r.provisionalEnding(s, owner.SessionID)
			}
		}
	}
}

// stopPublishing 은 발행 몫의 종료 순서다(체크리스트 474 A-3 3 · 판단 J74 · 계획 커밋 순서 7 〔r52〕) — 루프가 돌아온 뒤 한 번
// 부른다(조립의 run 이 defer 로). 차선 ctx 를 끊고(cancelLanes) → 떠 있는 작업 고루틴이 모두 끝나기를 기다린 뒤(Lanes.Wait) →
// fence 를 반납한다(release — 운영은 publish.Publisher.Release). 순서가 계약이다: 기다리기 전에 반납하면 늦게 돈 작업의 문장이
// 반납 뒤에 나간다. 반납은 ctx 의 취소를 떼어 낸 ctx 로 한다 — 종료 신호로 끝난 ctx 로 부르면 반납 문장이 나가지 않아 후임이
// lease 끝까지 기다린다(시한은 Release 가 안에서 건다). 반납 오류를 돌려준다. 차선이 없으면 끊기만 한다. 발행 몫이 없으면
// (release nil — 꺼짐 · 강등) 반납할 fence 가 없지만 차선은 있으므로(정산 몫 — M6-3b) 작업이 끝나기를 기다린다 — 풀이 닫히기
// 전에 정산 문장이 끝나야 한다(D24).
func stopPublishing(ctx context.Context, cancelLanes context.CancelFunc, lanes *publish.Lanes, release func(context.Context) error) error {
	cancelLanes()
	if lanes == nil {
		return nil
	}
	lanes.Wait()
	if release == nil {
		return nil
	}
	return release(context.WithoutCancel(ctx))
}
