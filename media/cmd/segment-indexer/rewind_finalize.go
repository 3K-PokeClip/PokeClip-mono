package main

// 루프의 정산 몫 — 받은편지함 비우기 · 빠른 길 · 30초 정산 스캔 · 걸음 배차와 결과 반영 · 상태 전달 차선(POK-196 M6-3b
// 커밋 ③ — 설계 M6 3.2 「누가 언제」 · holdTick 순서 ㉤ · 차선 · 주기 배선). 모두 루프 고루틴에서 돌고 작업
// 몸체(finalize_work.go)만 차선 고루틴에서 돈다. 판정은 순수 함수(rewind/finalize.Next)다.
//
//	차선 키   "scan:finalize"(스캔 — 감시의 프로세스 차선과 따로) · "final:"+회차(행 읽기 · 걸음) · "playback:"+스트림
//	         (상태 읽기)
//	발행 몫   꺼짐 · 강등에서도 돈다 — lanes · 스캔 주기는 run 이 조립 밖에서 채운다. 판정이 꺼짐 · 강등을 Mode 로 가른다

import (
	"context"
	"maps"
	"slices"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/finalize"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
)

// 차선 키 셋은 스트림 차선 키(stream_id 그대로)와 겹치지 않는다 — 셋 다 「:」 를 담고, stream_id 는 녹화 경로 화이트리스트
// (recording.ValidStreamID — [A-Za-z0-9_-]{1,64})를 지난 값이라 「:」 가 없다(M6-3b 사후 보안 검수 LOW-1 의 전제).
const (
	// scanLaneKey 는 정산 · 회차 스캔의 예약 차선 키다 — 감시(프로세스 차선)와 다른 키라 같은 틱의 둘이 병합되지 않는다(D66).
	scanLaneKey = "scan:finalize"
	// finalLanePrefix 는 회차 정산 차선의 앞머리다.
	finalLanePrefix = "final:"
	// playbackLanePrefix 는 상태 전달 차선의 앞머리다.
	playbackLanePrefix = "playback:"
	// freezeRetryAfter 는 S3 걸음 재시도 간격이다(설계 3.1 — 4초 × 3).
	freezeRetryAfter = 4 * time.Second
	// fastSettleWait 는 빠른 길의 꼬리 정착 대기 상한이다(설계 3.2 — 장부 정착 + 10초).
	fastSettleWait = 10 * time.Second
)

// finalizerOptions 는 정산 몫의 조립 값이다.
type finalizerOptions struct {
	Mode       finalize.Mode
	Every      time.Duration // 스캔 주기(30초)
	CloseAfter time.Duration // 사슬 닫힘 시한(재접속 창 + 60초)
	Work       finalizeWork
	Inbox      *session.PlaybackInbox
	// Sink 는 M5 상태 받은편지함(finalsInbox.Post)이다. nil 이면 보내지 않는다.
	Sink index.PlaybackSink
	// PlaybackReadMax 는 상태 읽기의 전체 동시 상한이다(REWIND_PLAYBACK_READ_MAX — 1 이상 · config 가 검사한다).
	PlaybackReadMax int
}

// finalizer 는 루프 고루틴의 정산 몫 상태다.
type finalizer struct {
	opt    finalize.Options
	limits finalize.Limits
	every  time.Duration
	work   finalizeWork
	inbox  *session.PlaybackInbox
	sink   index.PlaybackSink

	lastScan  time.Time
	scanning  bool
	cursor    index.ScanCursor
	fullPages int
	saturated bool

	attempts map[string]finalize.Attempts
	retryAt  map[string]time.Time
	fast     map[string]fastEntry
	running  map[string]finalize.Step // 도는 걸음
	loading  map[string]bool          // 도는 행 읽기
	vod      int                      // 도는 VOD 동결
	streamOf map[string]string        // 도는 걸음 · 행 읽기의 회차 → 스트림(같은 스트림 상한이 센다)
	held     map[string]string        // 보류 회차 → 사유
	gauge    map[string]int           // 사유 → 마지막으로 낸 게이지 값
	reading  map[string]bool          // 도는 상태 읽기(스트림)
	readMax  int                      // 도는 상태 읽기의 전체 상한
	// seen 은 이번 스캔 한 바퀴에서 본 회차다 — 바퀴가 끝나면 보지 않은 회차의 메모리를 지운다.
	seen map[string]bool
	// vodChainOnly 는 회차 경로에서 물러선 적 있는 회차 → 그 까닭이다 — 다음 걸음부터 stepChain 을 낸다(POK-549 설계 r4 2.4).
	vodChainOnly map[string]string
	// waitWarned 는 회차 대기 오래 기다림 경보(session_vod_round_wait_long)를 이미 낸 회차다(세션당 한 번).
	waitWarned map[string]bool
}

// fastEntry 는 빠른 길 대기 하나다 — due 부터 행을 읽고, deadline 전에는 꼬리가 정착했을 때만 읽는다.
type fastEntry struct {
	stream        string
	due, deadline time.Time
}

func newFinalizer(o finalizerOptions) *finalizer {
	return &finalizer{
		opt:    finalize.Options{Mode: o.Mode, CloseAfter: o.CloseAfter, MaxAttempts: 3},
		limits: finalize.DefaultLimits, every: o.Every, work: o.Work, inbox: o.Inbox, sink: o.Sink,
		attempts: map[string]finalize.Attempts{}, retryAt: map[string]time.Time{}, fast: map[string]fastEntry{},
		running: map[string]finalize.Step{}, loading: map[string]bool{}, streamOf: map[string]string{},
		held: map[string]string{}, gauge: map[string]int{},
		reading: map[string]bool{}, readMax: o.PlaybackReadMax, seen: map[string]bool{},
		vodChainOnly: map[string]string{}, waitWarned: map[string]bool{},
	}
}

// busy 는 정산 몫의 차선 작업이 도는가다(시험 · 종료 확인).
func (f *finalizer) busy() bool {
	return f.scanning || len(f.running) > 0 || len(f.loading) > 0 || len(f.reading) > 0
}

// queueFast 는 회차 sessionID 를 빠른 길에 올린다 — due 부터 그 행을 읽어 다음 걸음을 낸다.
func (f *finalizer) queueFast(streamID, sessionID string, due time.Time) {
	f.fast[sessionID] = fastEntry{stream: streamID, due: due, deadline: due.Add(fastSettleWait)}
}

// wakeFast 는 깨우기를 빠른 길 예약과 병합한다(Y1). 재시도 예약(retryAt)보다 이르게는 읽지 않고, 그보다 늦지 않은
// 예약이 이미 있으면 그대로 둔다 — 깨우기가 예약을 덮어써 retryAt 앞에서 읽고 버리는 일(dispatchStep 의 retryAt 검사)을 막는다.
// 그대로 둬도 되는 근거는 불변식 I-fast(항목과 retryAt 이 둘 다 있으면 fast.due ≥ retryAt)다 — 모든 진입점이 지킨다.
func (f *finalizer) wakeFast(streamID, sessionID string, now time.Time) {
	due := now
	if t, ok := f.retryAt[sessionID]; ok && t.After(due) {
		due = t
	}
	if e, ok := f.fast[sessionID]; ok && !e.due.After(due) {
		return
	}
	f.queueFast(streamID, sessionID, due)
}

// forget 은 끝난(또는 ending 이 아닌) 회차의 메모리를 지운다.
func (f *finalizer) forget(id string) {
	delete(f.attempts, id)
	delete(f.retryAt, id)
	delete(f.fast, id)
	delete(f.held, id)
	delete(f.vodChainOnly, id)
	delete(f.waitWarned, id)
}

// inFlight 는 도는 정산 작업 수다(행 읽기 포함 — 동시 상한이 센다).
func (f *finalizer) inFlight() int { return len(f.running) + len(f.loading) }

// streamInFlight 는 스트림 stream 에서 도는 정산 작업 수다(행 읽기 포함 — 같은 스트림 상한이 센다).
func (f *finalizer) streamInFlight(stream string) int {
	n := 0
	for _, s := range f.streamOf {
		if s == stream {
			n++
		}
	}
	return n
}

// drainInboxes 는 holdTick 의 받은편지함 비우기 걸음이다(3.2 순서 — endOfflineSessions 다음). 상태 표지는 스트림마다
// "playback:" 차선에 낸다.
func (r *rewindLoop) drainInboxes(ctx context.Context) {
	if r.fin == nil {
		return
	}
	for _, s := range r.fin.inbox.Drain() {
		r.startPlayback(ctx, s)
	}
}

// finalizeTick 은 holdTick 의 정산 걸음이다(받은편지함 비우기 다음 · 발행 몫 조기 반환 앞) — 빠른 길을 배차하고, 스캔 주기가
// 되면 정산 스캔을 낸다.
func (r *rewindLoop) finalizeTick(ctx context.Context, now time.Time) {
	if r.fin == nil {
		return
	}
	r.dispatchFast(ctx, now)
	if r.fin.lastScan.IsZero() || now.Sub(r.fin.lastScan) >= r.fin.every {
		r.fin.lastScan = now
		r.startScan(ctx)
	}
}

// dispatchFast 는 빠른 길 대기 가운데 때가 된 회차의 행 읽기를 낸다 — 동시 상한 안에서만(그 스트림이 차 있으면 다음 회차를 본다).
func (r *rewindLoop) dispatchFast(ctx context.Context, now time.Time) {
	f := r.fin
	for _, id := range slices.Sorted(maps.Keys(f.fast)) {
		e := f.fast[id]
		if now.Before(e.due) || f.loading[id] {
			continue
		}
		if _, run := f.running[id]; run {
			continue
		}
		if now.Before(e.deadline) && !r.tailSettled(e.stream, id) {
			continue
		}
		if !f.limits.Admit(f.inFlight(), f.vod, f.streamInFlight(e.stream), finalize.StepWait) {
			continue
		}
		load := f.work.load
		if !r.lanes.Start(ctx, finalLanePrefix+id, func(ctx context.Context) publish.Result {
			return publish.Result{Kind: publish.KindFinalizeLoad, Value: load(ctx, id)}
		}) {
			continue
		}
		delete(f.fast, id)
		f.loading[id], f.streamOf[id] = true, e.stream
	}
}

// tailSettled 는 캐시가 아는 회차 id 의 행이 모두 settled 인가다 — 캐시가 없거나(꺼짐 · 강등) 그 스트림 뷰가 없으면 참이다
// (기다릴 근거가 없다). 빠른 길은 정착을 상한(10초)까지만 기다린다.
func (r *rewindLoop) tailSettled(stream, id string) bool {
	if r.cache == nil {
		return true
	}
	snap := r.cache.Snapshot(stream)
	cut, ok := snap.Cutoff()
	if !ok {
		return true
	}
	for _, row := range snap.RowsFrom(cut) {
		if row.SessionID == id && !boundary.Settled(row, cut) {
			return false
		}
	}
	return true
}

// startScan 은 정산 스캔을 예약 키 차선에 낸다. 돌고 있으면 Lanes 가 병합하고 결과 뒤 다시 낸다(scanDone).
func (r *rewindLoop) startScan(ctx context.Context) {
	scan, after := r.fin.work.scan, r.fin.cursor
	if r.lanes.Start(ctx, scanLaneKey, func(ctx context.Context) publish.Result {
		return publish.Result{Kind: publish.KindFinalizeScan, Value: scan(ctx, after)}
	}) {
		r.fin.scanning = true
	}
}

// scanDone 은 스캔 결과를 반영한다 — 닫은 회차의 상태 표지 · 깨우기 · 커서 · 포화 게이지 · 행마다 판정과 배차 · 보류 게이지.
func (r *rewindLoop) scanDone(ctx context.Context, res publish.Result, rerun bool, now time.Time) {
	f := r.fin
	f.scanning = false
	out, _ := res.Value.(finalScan)
	for _, s := range out.closed {
		f.inbox.Changed(s)
	}
	for _, w := range out.wake {
		f.wakeFast(w.stream, w.session, now)
	}
	if out.err != nil && !contended(out.err) {
		r.log.Warn("finalize_scan_failed", "err", out.err)
	}
	r.noteSaturation(out.full)
	for _, row := range out.rows {
		f.seen[row.SessionID] = true
		r.dispatchStep(ctx, row, now)
	}
	f.cursor = out.next
	if out.err == nil && out.next == (index.ScanCursor{}) {
		r.pruneUnseen()
	}
	r.publishHeld()
	if rerun {
		r.startScan(ctx)
	}
}

// noteSaturation 은 포화 게이지다(3.2 「스캔 포화」) — 쪽이 가득 찬 일이 3회 연속이면 WARN value=1, 가득 차지 않은 쪽에서 value=0.
func (r *rewindLoop) noteSaturation(full bool) {
	f := r.fin
	switch {
	case full:
		f.fullPages++
		if f.fullPages >= 3 && !f.saturated {
			f.saturated = true
			r.log.Warn("finalize_scan_saturated", "value", 1)
		}
	default:
		f.fullPages = 0
		if f.saturated {
			f.saturated = false
			r.log.Warn("finalize_scan_saturated", "value", 0)
		}
	}
}

// pruneUnseen 은 스캔 한 바퀴에서 보지 않은 회차의 메모리를 지운다 — ended 가 됐거나 다른 writer 가 거둔 회차다. 도는 작업 ·
// 빠른 길 대기가 있는 회차는 남긴다.
func (r *rewindLoop) pruneUnseen() {
	f := r.fin
	for id := range f.attempts {
		if !f.seen[id] && !f.loading[id] {
			if _, run := f.running[id]; !run {
				if _, q := f.fast[id]; !q {
					f.forget(id)
				}
			}
		}
	}
	for id := range f.held {
		if !f.seen[id] {
			delete(f.held, id)
		}
	}
	f.seen = map[string]bool{}
}

// dispatchStep 은 행 row 의 다음 걸음을 판정해 낸다 — 도는 작업이 있거나 재시도 시각 전이면 건너뛴다. 보류는 게이지에만
// 적고, 동시 상한에 걸리면 빠른 길에 올려 다음 틱에 다시 읽는다. 회차 경로에서 물러선 적 있는 회차는 stepChain 으로 낸다.
func (r *rewindLoop) dispatchStep(ctx context.Context, row index.EndingSession, now time.Time) {
	f, id := r.fin, row.SessionID
	if _, run := f.running[id]; run || f.loading[id] {
		return
	}
	if t, ok := f.retryAt[id]; ok && now.Before(t) {
		return
	}
	d := finalize.Next(row, f.attempts[id], f.opt)
	switch d.Step {
	case finalize.StepWait:
		delete(f.held, id)
		return
	case finalize.StepHold:
		f.held[id] = d.Hold
		return
	}
	delete(f.held, id)
	if !f.limits.Admit(f.inFlight(), f.vod, f.streamInFlight(row.StreamID), d.Step) {
		f.queueFast(row.StreamID, id, now)
		return
	}
	step := f.work.step
	if _, ok := f.vodChainOnly[id]; ok {
		step = f.work.stepChain
	}
	if !r.lanes.Start(ctx, finalLanePrefix+id, func(ctx context.Context) publish.Result {
		return publish.Result{Kind: publish.KindFinalize, Value: step(ctx, row, d)}
	}) {
		return
	}
	f.running[id], f.streamOf[id] = d.Step, row.StreamID
	if d.Step == finalize.StepFreezeVOD {
		f.vod++
	}
}

// finalLoadDone 은 빠른 길 행 읽기 결과다 — 아직 ending 이면 판정해 걸음을 낸다.
func (r *rewindLoop) finalLoadDone(ctx context.Context, res publish.Result, now time.Time) {
	out, _ := res.Value.(finalLoad)
	delete(r.fin.loading, out.sessionID)
	delete(r.fin.streamOf, out.sessionID)
	switch {
	case out.err != nil:
		r.log.Warn("finalize_load_failed", "session_id", out.sessionID, "err", out.err)
	case !out.found:
		r.fin.forget(out.sessionID)
	default:
		r.dispatchStep(ctx, out.row, now)
		r.publishHeld()
	}
}

// finalStepDone 은 걸음 결과를 반영한다 — 실패 횟수 · 신호 · 물러섬 기억 · 상태 표지 · 다음 걸음(섰으면 곧바로 다시 읽고,
// 재시도면 4초 뒤) · 회차 깨우기. 물러섬 기억은 noteStep 뒤에 적는다 — noteStep 이 기억 부재로 그 세션의 첫 물러섬을 안다.
func (r *rewindLoop) finalStepDone(ctx context.Context, res publish.Result, now time.Time) {
	f := r.fin
	out, _ := res.Value.(finalStep)
	id := out.row.SessionID
	delete(f.running, id)
	delete(f.streamOf, id)
	if out.d.Step == finalize.StepFreezeVOD {
		f.vod--
	}
	a := f.attempts[id].After(out.d.Step, out.out, f.opt.MaxAttempts)
	f.attempts[id] = a
	r.noteStep(out, a)
	if out.freeze.Fallback != "" {
		f.vodChainOnly[id] = out.freeze.Fallback
	}
	if out.hold != "" {
		f.held[id] = out.hold
	}
	if out.notify {
		f.inbox.Changed(out.row.StreamID)
	}
	switch {
	case out.d.Step == finalize.StepMarkEnded && out.progressed:
		f.forget(id)
	case out.out == finalize.OutcomeRetry:
		f.retryAt[id] = now.Add(freezeRetryAfter)
		f.queueFast(out.row.StreamID, id, now.Add(freezeRetryAfter))
	case out.progressed:
		delete(f.retryAt, id)
		f.queueFast(out.row.StreamID, id, now)
	}
	if out.wakeErr != nil {
		r.log.Warn("finalize_round_wake_failed", "session_id", id, "round", out.wake.round, "err", out.wakeErr)
	}
	if out.wake.session != "" {
		f.wakeFast(out.wake.stream, out.wake.session, now)
	}
	r.publishHeld()
}

// stepAxis 는 걸음의 동결 축이다(신호 속성 axis) — 장부만 쓰는 걸음은 빈 값이다.
func stepAxis(s finalize.Step) string {
	switch s {
	case finalize.StepFreezeDVR, finalize.StepSealDVR, finalize.StepFailDVR, finalize.StepRecoverOrphan, finalize.StepEmptyFreezeDVR:
		return publish.AxisDVR
	case finalize.StepFreezeVOD, finalize.StepEmptyFreezeVOD, finalize.StepFailVOD:
		return publish.AxisVOD
	}
	return ""
}

// noteStep 은 걸음 신호다(설계 8절) — session_freeze(섰다 · INFO) · session_freeze_retry(재시도 · WARN · 정산 대기는 남기지
// 않음) · session_freeze_failed(freeze_failed 종국 · ERROR) · session_settle_abandoned(정산 창 만료로 GAP 포기 · WARN) ·
// finalize_step_failed(장부 문장 오류 · 경합 제외 · WARN) · 회차 신호 둘(noteRound — 결과 갈래보다 먼저: 회차 대기는 언제나
// Unsettled 로 온다).
func (r *rewindLoop) noteStep(out finalStep, a finalize.Attempts) {
	axis, id, step := stepAxis(out.d.Step), out.row.SessionID, out.d.Step.String()
	if out.err != nil {
		if !contended(out.err) {
			r.log.Warn("finalize_step_failed", "step", step, "session_id", id, "err", out.err)
		}
		return
	}
	r.noteRound(out)
	switch out.out {
	case finalize.OutcomeRetry:
		r.log.Warn("session_freeze_retry", "axis", axis, "step", step, "session_id", id,
			"attempt", attemptOf(out.d.Step, a), "reason", out.freeze.Reason, "err", out.freeze.Err)
		return
	case finalize.OutcomeUnsettled:
		return
	}
	switch {
	case out.d.Step == finalize.StepFailDVR || out.d.Step == finalize.StepFailVOD ||
		(out.d.Step == finalize.StepRecoverOrphan && out.freeze.Result == publish.ResultFailed):
		r.log.Error("session_freeze_failed", "axis", axis, "step", step, "session_id", id, "reason", out.freeze.Reason)
	case out.freeze.Result == publish.ResultFailed:
		// 다음 단(StepFailVOD 등)의 session_freeze_failed 는 장부 문장이라 이 사유를 모른다 — 사유는 이 줄에만 남는다.
		r.log.Info("session_freeze", "axis", axis, "step", step, "session_id", id, "result", out.freeze.Result,
			"reason", out.freeze.Reason, "scope", out.freeze.Scope)
	case out.freeze.Result != "":
		r.log.Info("session_freeze", "axis", axis, "step", step, "session_id", id, "result", out.freeze.Result,
			"scope", out.freeze.Scope)
	}
	// 회차 경로는 마지막 사슬 범위에 GAP 줄이 있을 때만이다(R19 — 앞 사슬 GAP 은 앞 사슬 동결 때 이미 냈다). 그 GAP 의 출처는
	// 가리지 않는다 — 사슬 경로의 지금 조건과 같다(K26).
	if out.d.Abandon && out.freeze.Result == publish.ResultFrozenWithGaps &&
		(out.freeze.Scope != publish.ScopeRound || out.freeze.LastChainHasGaps) {
		r.log.Warn("session_settle_abandoned", "session_id", id, "stream_id", out.row.StreamID)
	}
}

// noteRound 는 회차 신호다(설계 8절) — session_vod_round_fallback(사슬 범위로 물러섬 · 그 세션의 첫 물러섬만 — 물러섬 기억이
// 아직 없을 때) · session_vod_round_wait_long(회차 대기가 ending 뒤 2 × 정산 창을 넘김 · 세션당 한 번). 시각은 행의 DB 시계
// 칸이다.
func (r *rewindLoop) noteRound(out finalStep) {
	f, id := r.fin, out.row.SessionID
	if _, known := f.vodChainOnly[id]; out.freeze.Fallback != "" && !known {
		r.log.Warn("session_vod_round_fallback", "session_id", id, "round", out.row.RoundID, "reason", out.freeze.Fallback,
			"cause", out.freeze.FallbackCause, "err", out.freeze.FallbackErr)
	}
	if publish.IsRoundWait(out.freeze.Reason) && out.row.Now.Sub(out.row.EndingAt) > 2*index.SettleWindow && !f.waitWarned[id] {
		f.waitWarned[id] = true
		r.log.Warn("session_vod_round_wait_long", "session_id", id, "round", out.row.RoundID, "wait", out.freeze.Reason)
	}
}

// attemptOf 는 걸음의 단에서 지금까지 센 실패 횟수다.
func attemptOf(s finalize.Step, a finalize.Attempts) int {
	switch s {
	case finalize.StepFreezeDVR:
		return a.DVR
	case finalize.StepSealDVR:
		return a.Seal
	case finalize.StepFreezeVOD:
		return a.VOD
	}
	return 0
}

// publishHeld 는 보류 게이지다(finalize_held reason value=n) — 사유마다 값이 바뀔 때만 WARN 한 줄(0 이면 내린다).
func (r *rewindLoop) publishHeld() {
	f := r.fin
	counts := map[string]int{}
	for _, reason := range f.held {
		counts[reason]++
	}
	for _, reason := range []string{finalize.HoldPublishOffWithTrace, finalize.HoldPublishDegraded} {
		if n := counts[reason]; n != f.gauge[reason] {
			f.gauge[reason] = n
			r.log.Warn("finalize_held", "reason", reason, "value", n)
		}
	}
}

// startPlayback 은 스트림 s 의 상태 읽기를 낸다. 돌고 있으면 Lanes 가 병합하고 결과를 받은 뒤 다시 읽는다(D65 — 커밋 X 뒤의
// 표지는 X 를 본 읽기를 적어도 한 번 낳는다). 새 스트림의 읽기는 전체 동시 상한(readMax) 안에서만 내고, 넘치면 표지를
// 되돌려 다음 holdTick 에 다시 본다.
func (r *rewindLoop) startPlayback(ctx context.Context, s string) {
	if !r.fin.reading[s] && len(r.fin.reading) >= r.fin.readMax {
		r.fin.inbox.Changed(s)
		return
	}
	read := r.fin.work.playback
	if r.lanes.Start(ctx, playbackLanePrefix+s, func(ctx context.Context) publish.Result {
		return publish.Result{Kind: publish.KindPlayback, Value: read(ctx, s)}
	}) {
		r.fin.reading[s] = true
	}
}

// playbackDone 은 상태 읽기 결과다 — 값이 있으면(최신 회차가 live 가 아니면) sink 로 보낸다. 실패면 WARN 뒤 표지를 되돌려
// 다음 holdTick 에 다시 읽는다. 병합된 표지가 있었으면 곧바로 다시 읽는다.
func (r *rewindLoop) playbackDone(ctx context.Context, res publish.Result, rerun bool) {
	out, _ := res.Value.(playbackRead)
	delete(r.fin.reading, out.streamID)
	switch {
	case out.err != nil:
		r.log.Warn("playback_read_failed", "stream_id", out.streamID, "err", out.err)
		r.fin.inbox.Changed(out.streamID)
	case out.ok && r.fin.sink != nil:
		r.fin.sink(out.streamID, out.st)
		r.log.Info("playback_state_sent", "stream_id", out.streamID, "phase", int(out.st.Phase), "round", out.st.RoundID)
	}
	if rerun {
		r.startPlayback(ctx, out.streamID)
	}
}
