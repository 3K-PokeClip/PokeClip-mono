package main

// 루프의 제어면 상태 몫 — holdTick 마다 M6 상태를 종료 항목에 반영하고(Drain), 끝에서 Board 를 새로 만든다(publishStatus ·
// 설계 M5 2.1 · 3.3 · 4.1–4.4). 모두 루프 고루틴에서 돈다. HTTP 고루틴과 나누는 것은 Holder 의 불변 Board 하나다.

import (
	"errors"
	"maps"
	"slices"
	"strconv"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/status"
)

// readyAlarmAfter 는 송출 중인데 준비되지 않은 채로 rewind_ready_delayed 를 세우기까지의 시간이다(4.4절).
const readyAlarmAfter = 60 * time.Second

// rewind_ready_delayed · rewind_final_* 의 reason 값.
const (
	reasonLiveNotFromM6 = "live_not_from_m6"
	reasonNoCutoff      = "no_cutoff"
	reasonLoading       = "loading"
	reasonInitPending   = "init_pending"
	reasonNotPublished  = "not_published"
	reasonRangeMissing  = "range_missing"
)

// readyAlarm 은 rewind_ready_delayed 의 스트림 하나 상태다 — since 는 「송출 중 · 준비 안 됨」을 처음 본 시각이다.
type readyAlarm struct {
	since     time.Time
	raised    bool
	sessionID string // 세울 때의 소유 회차(없으면 빈 값)
}

// drainFinals 는 받은편지함을 비워 종료 항목에 반영한다(2.1절 Drain 변환 · 경계 확정 1 — 마지막 값이 이긴다).
//
//	Live(M6 가 보내지 않는 값)     버림 · WARN rewind_final_invalid reason=live_not_from_m6
//	FromPlaybackState 오류         버림 · WARN rewind_final_invalid reason=<오류 사유>
//	NotFound(Unknown)              final[s] 삭제
//	Found                          final[s] 를 조건 없이 덮음(Forget 잠정 항목도 덮인다)
//
// 같은 스트림 · 같은 값의 WARN 은 한 번만이다.
func (r *rewindLoop) drainFinals() {
	if r.finals == nil {
		return
	}
	in := r.finals.Drain()
	for _, s := range slices.Sorted(maps.Keys(in)) {
		r.applyFinal(s, in[s])
	}
}

func (r *rewindLoop) applyFinal(s string, st index.PlaybackState) {
	if st.Phase == index.PhaseLive {
		r.rejectFinal(s, st, reasonLiveNotFromM6)
		return
	}
	resp, answer, err := status.FromPlaybackState(st, r.baseURL)
	if err != nil {
		reason := status.ReasonOutOfRange
		if inv := (*status.InvalidError)(nil); errors.As(err, &inv) {
			reason = inv.Reason
		}
		r.rejectFinal(s, st, reason)
		return
	}
	delete(r.invalid, s)
	if answer == status.NotFound {
		if old, ok := r.final[s]; ok {
			delete(r.final, s)
			r.logFinal(s, old, "m6", "unknown")
		}
		return
	}
	f := status.Final{RoundID: st.RoundID, LatestSessionID: st.LatestSessionID, Status: resp}
	r.final[s] = f
	r.logFinal(s, f, "m6", "")
}

// rejectFinal 은 옮길 수 없는 M6 값 st 를 버린다 — 같은 스트림에 같은 값이 거듭 오면 WARN 은 처음 한 번이다.
func (r *rewindLoop) rejectFinal(s string, st index.PlaybackState, reason string) {
	if prev, ok := r.invalid[s]; ok && prev == st {
		return
	}
	if r.invalid == nil {
		r.invalid = map[string]index.PlaybackState{}
	}
	r.invalid[s] = st
	r.log.Warn("rewind_final_invalid", "stream_id", s, "phase", phaseName(st.Phase), "reason", reason)
}

// phaseName 은 로그의 phase 글자다(index.PlaybackPhase 에 String 이 없다 — 착수 체크리스트 ⓕ). 범위 밖은 숫자 그대로다.
func phaseName(p index.PlaybackPhase) string {
	switch p {
	case index.PhaseUnknown:
		return "unknown"
	case index.PhaseLive:
		return "live"
	case index.PhaseEnding:
		return "ending"
	case index.PhaseVOD:
		return "vod"
	case index.PhaseEnded:
		return "ended"
	}
	return strconv.Itoa(int(p))
}

// logFinal 은 종료 항목을 넣거나 덮거나(removedReason 빈 값) 지운 것(removedReason = session_opened · unknown)을 남긴다.
func (r *rewindLoop) logFinal(s string, f status.Final, source, removedReason string) {
	attrs := []any{"stream_id", s, "round_id", f.RoundID, "latest_session_id", f.LatestSessionID, "mode", string(f.Status.Mode),
		"source", source, "removed", removedReason != ""}
	if removedReason != "" {
		attrs = append(attrs, "reason", removedReason)
	}
	r.log.Info("rewind_final_applied", attrs...)
}

// provisionalEnding 은 Forget 이 소유 회차 owner 를 든 스트림 s 의 뷰를 버렸을 때 잠정 Ending 을 넣는다(3.3절) — 종료 항목이
// 없거나 다른(더 옛) 회차의 것일 때만이다. 소유 회차는 그 스트림에서 가장 늦게 연 회차라 다른 LatestSessionID 는 옛 회차다.
// RoundID 는 비워 회차 경로가 hit 하지 않는다. 반영은 다음 holdTick 스냅숏부터다.
func (r *rewindLoop) provisionalEnding(s, owner string) {
	if r.final == nil {
		return
	}
	if f, ok := r.final[s]; ok && f.LatestSessionID == owner {
		return
	}
	f := status.Final{LatestSessionID: owner, Status: status.Status{Mode: status.ModeEnding}}
	r.final[s] = f
	r.logFinal(s, f, "forget_provisional", "")
}

// publishStatus 는 holdTick 끝에서 Board 를 새로 만든다(꺼짐이면 하지 않는다 — DisabledBoard 그대로). 캐시가 든 스트림마다
// 개시 삭제를 평가하고 Derive 로 답을 정한 뒤, 캐시 밖 종료 항목을 더한다. warm 은 부팅 감시가 끝났거나 발행 몫이 없을
// 때(강등 — 기다릴 감시가 없다) 참이다.
func (r *rewindLoop) publishStatus(now time.Time) {
	if r.board == nil || r.statusOff {
		return
	}
	entries := map[string]status.Entry{}
	streams := r.cache.Streams()
	for _, s := range streams {
		in, pubReason := r.statusInput(s)
		st, _ := status.Derive(in) // 캐시가 아는 스트림은 늘 참(행 7)
		entries[s] = r.entry(s, st)
		r.observeReady(s, st.Ready, notReadyReason(in, pubReason), in.Owner, now)
	}
	for s, f := range r.final {
		if _, ok := entries[s]; ok {
			continue
		}
		if st, ok := status.Derive(status.Input{Final: &f}); ok {
			entries[s] = r.entry(s, st)
		}
	}
	r.dropAlarmsOutside(streams, now)
	r.board.Store(status.NewBoard(now, r.booted || !r.publishing(), entries))
}

// statusInput 은 캐시가 든 스트림 s 의 도출 입력이다. 소유 회차가 live 면 그 틱에 개시 삭제를 먼저 한다(경계 확정 1 — 매
// 틱 평가 · R51). 둘째 값은 발행 판이 없을 때의 까닭이다(경보 reason).
func (r *rewindLoop) statusInput(s string) (status.Input, string) {
	in := status.Input{CacheKnown: true, Loading: r.cache.Loading(s)}
	_, in.HasView = r.cache.Snapshot(s).Cutoff()
	pubReason := ""
	if o, ok := r.cache.Owner(s); ok {
		in.Owner = &status.OwnerSession{SessionID: o.SessionID, State: o.State, InitUploaded: o.InitUploaded}
		if o.State == "live" {
			r.dropFinalOnOpen(s, o.SessionID)
		}
		in.Published, pubReason = r.published(s, o.SessionID)
	}
	if f, ok := r.final[s]; ok {
		in.Final = &f
	}
	return in, pubReason
}

// dropFinalOnOpen 은 새 회차 owner 가 live 로 열린 스트림의 지난 종료 항목을 지운다.
func (r *rewindLoop) dropFinalOnOpen(s, owner string) {
	f, ok := r.final[s]
	if !ok || f.LatestSessionID == owner {
		return
	}
	delete(r.final, s)
	r.logFinal(s, f, "m6", "session_opened")
}

// published 는 소유 회차 sid 목록의 발행 판이다(4.2 · 4.3절) — 루프가 그 회차의 발행본(Prev)을 들 때만이고, 범위는 발행 판
// 첫 · 끝 행의 PDT 다. 행이 빠졌거나 PDT 가 없으면 nil 이고 WARN rewind_status_range_missing 을 회차당 한 번 남긴다. 목록
// URL 을 만들 수 없는 성분이면 발행자도 그 키로 쓰지 못하므로 발행 판 없음과 같다.
func (r *rewindLoop) published(s, sid string) (*status.Range, string) {
	rec := r.streams[s]
	if rec == nil || rec.owner != sid || rec.pub.Prev == nil {
		return nil, reasonNotPublished
	}
	url, err := publish.ManifestURL(r.baseURL, s, sid)
	if err != nil {
		return nil, reasonNotPublished
	}
	p := rec.pub.Prev
	first, head, ok := r.cache.PDTRange(s, p.MediaSequence, p.PublishedSeq)
	if !ok {
		r.warnRangeMissing(s, sid, p.MediaSequence, p.PublishedSeq)
		return nil, reasonRangeMissing
	}
	return &status.Range{URL: url, First: first, Head: head}, ""
}

func (r *rewindLoop) warnRangeMissing(s, sid string, from, to int64) {
	if r.rangeWarned[s] == sid {
		return
	}
	if r.rangeWarned == nil {
		r.rangeWarned = map[string]string{}
	}
	r.rangeWarned[s] = sid
	r.log.Warn("rewind_status_range_missing", "stream_id", s, "session_id", sid, "from", from, "to", to)
}

// entry 는 Board 항목이다 — 회차 사본은 종료 항목에 RoundID 가 있을 때만이다(잠정 항목은 회차 경로가 hit 하지 않는다).
func (r *rewindLoop) entry(s string, st status.Status) status.Entry {
	e := status.Entry{Stream: st}
	if f, ok := r.final[s]; ok && f.RoundID != "" {
		e.Round = &status.RoundEntry{RoundID: f.RoundID, Status: f.Status}
	}
	return e
}

// notReadyReason 은 준비 안 된 까닭 하나다(4.4절 reason).
func notReadyReason(in status.Input, pubReason string) string {
	switch {
	case in.Loading:
		return reasonLoading
	case !in.HasView:
		return reasonNoCutoff
	case in.Owner == nil || in.Owner.State != "live":
		return reasonNotPublished
	case !in.Owner.InitUploaded:
		return reasonInitPending
	case pubReason != "":
		return pubReason
	}
	return reasonNotPublished
}

// observeReady 는 rewind_ready_delayed 를 옮긴다(4.4절). 경보는 소유 회차가 아니라 스트림의 송출 사실에 건다 — 「송출 중」은
// 4.1 종료 판정과 같은 술어 IngestStopped 의 부정이다. 송출 중 · 준비 안 됨이 60초를 넘으면 WARN value=1 을 한 번 세우고,
// 준비되면 reason=ready, 송출이 멈추면 reason=ingest_stopped 로 내린다.
func (r *rewindLoop) observeReady(s string, ready bool, reason string, owner *status.OwnerSession, now time.Time) {
	stopped, known := r.ix.IngestStopped(s, now)
	switch {
	case !known || stopped:
		r.clearAlarm(s, "ingest_stopped", now)
		return
	case ready:
		r.clearAlarm(s, "ready", now)
		return
	}
	a := r.alarms[s]
	if a == nil {
		if r.alarms == nil {
			r.alarms = map[string]*readyAlarm{}
		}
		a = &readyAlarm{since: now}
		r.alarms[s] = a
	}
	if a.raised || now.Sub(a.since) <= readyAlarmAfter {
		return
	}
	a.raised = true
	if owner != nil {
		a.sessionID = owner.SessionID
	}
	r.log.Warn("rewind_ready_delayed", "value", 1, "stream_id", s, "session_id", a.sessionID,
		"waited_ms", now.Sub(a.since).Milliseconds(), "reason", reason)
}

// clearAlarm 은 s 의 경보 상태를 지우고, 세워져 있었으면 내림 줄을 쓴다.
func (r *rewindLoop) clearAlarm(s, reason string, now time.Time) {
	a := r.alarms[s]
	if a == nil {
		return
	}
	delete(r.alarms, s)
	if a.raised {
		r.log.Info("rewind_ready_delayed", "value", 0, "stream_id", s, "session_id", a.sessionID,
			"waited_ms", now.Sub(a.since).Milliseconds(), "reason", reason)
	}
}

// dropAlarmsOutside 는 캐시가 더는 들지 않는 스트림의 경보를 내린다(Forget — 오래 쉰 스트림이다).
func (r *rewindLoop) dropAlarmsOutside(streams []string, now time.Time) {
	for s := range r.alarms {
		if !slices.Contains(streams, s) {
			r.clearAlarm(s, "ingest_stopped", now)
		}
	}
}
