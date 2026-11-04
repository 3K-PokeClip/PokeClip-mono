package edgebounds

import (
	"errors"
	"time"
)

// 신호 이름(설계 8-1). release_overdue · blocks_list 는 같은 이름 · 같은 등급(ERROR)으로 value=1(set) · value=0(clear)
// 를 낸다 — clear 만 등급이 낮으면 LOG_LEVEL=warn 수집에서 회복이 걸러진다(janitor · ingestauth 게이지 관례).
// write_failing(WARN) · write_recovered(INFO)는 설계 표의 두 이름 · 두 등급 그대로다.
const (
	signalWriteFailing   = "edge_bounds_write_failing"
	signalWriteRecovered = "edge_bounds_write_recovered"
	signalReleased       = "dvr_floor_released"
	signalReleaseOverdue = "dvr_floor_release_overdue"
	signalBlocksList     = "dvr_floor_blocks_list"
	signalStoreLarge     = "edge_bounds_store_large"
)

// releaseOverdueAfter 는 닫힌 미해제 회차를 처음 본 뒤 이만큼 지나도록 못 풀면 release_overdue 를 세우는 시간이다
// (설계 8-1). 처음 본 시각은 이 프로세스의 기억이라 재기동하면 다시 잰다.
const releaseOverdueAfter = 10 * time.Minute

// 패스 이름 — write_failing · write_recovered 의 pass 다.
const (
	passRaise   = "raise"
	passRelease = "release"
)

// 실패 사유 — write_failing 의 reason 이다(Writer 계약 4). conflict 는 판이 바뀌어 다시 보낸 것도 실패한 것, failed 는
// 그 밖(판 그대로인 실패 · 원장 · Describe 실패)이다.
const (
	reasonConflict = "conflict"
	reasonFailed   = "failed"
)

// passError 는 사유를 붙인 패스 실패다.
type passError struct {
	reason string
	err    error
}

func (e *passError) Error() string { return e.reason + ": " + e.err.Error() }
func (e *passError) Unwrap() error { return e.err }

func failed(err error) error { return &passError{reason: reasonFailed, err: err} }

func reasonOf(err error) string {
	if pe, ok := errors.AsType[*passError](err); ok {
		return pe.reason
	}
	return reasonFailed
}

// closedRound 는 해제를 기다리는 닫힌 회차다.
type closedRound struct {
	floor   Floor
	since   time.Time // 이 프로세스가 처음 본 시각
	overdue bool      // release_overdue 가 섰다
}

// report 는 패스 하나의 결과로 쓰기 실패 신호를 맞춘다 — 실패가 이어져도 set 한 번, 다음 성공에 clear 한 번(패스마다).
func (w *Writer) report(pass string, err error) {
	switch {
	case err != nil && !w.failing[pass]:
		w.failing[pass] = true
		w.opt.Log.Warn(signalWriteFailing, "reason", reasonOf(err), "pass", pass, "err", err.Error())
	case err == nil && w.failing[pass]:
		delete(w.failing, pass)
		w.opt.Log.Info(signalWriteRecovered, "pass", pass)
	}
}

// trackClosed 는 원장이 돌려준 닫힌 미해제 회차로 release_overdue 를 맞춘다. complete 면 rows 가 그런 회차 전부라,
// 기억에는 있는데 rows 에 없는 회차는 다른 상자가 푼 것으로 보고 잊는다.
func (w *Writer) trackClosed(rows []Floor, complete bool) {
	now := w.opt.Now()
	seen := make(map[string]bool, len(rows))
	for _, r := range rows {
		key := FloorKey(r.StreamID, r.RoundID)
		seen[key] = true
		c, ok := w.closed[key]
		if !ok {
			c = &closedRound{floor: r, since: now}
			w.closed[key] = c
		}
		if !c.overdue && now.Sub(c.since) >= releaseOverdueAfter {
			c.overdue = true
			w.opt.Log.Error(signalReleaseOverdue, "value", 1, "stream", r.StreamID, "round", r.RoundID, "since", c.since)
		}
	}
	if !complete {
		return
	}
	for key, c := range w.closed {
		if !seen[key] {
			w.forgetClosed(key, c)
		}
	}
}

// released 는 원장에 해제를 적은 회차마다 dvr_floor_released 를 내고 기억을 지운다.
func (w *Writer) released(rows []Floor) {
	now := w.opt.Now()
	for _, r := range rows {
		key := FloorKey(r.StreamID, r.RoundID)
		var waited time.Duration
		c, ok := w.closed[key]
		if ok {
			waited = now.Sub(c.since)
		}
		w.opt.Log.Info(signalReleased, "stream", r.StreamID, "round", r.RoundID,
			"waited_ms", waited.Milliseconds(), "settle_ms", w.opt.ReleaseSettle.Milliseconds())
		if ok {
			w.forgetClosed(key, c)
		}
	}
}

func (w *Writer) forgetClosed(key string, c *closedRound) {
	delete(w.closed, key)
	if c.overdue {
		w.opt.Log.Error(signalReleaseOverdue, "value", 0, "stream", c.floor.StreamID, "round", c.floor.RoundID)
	}
}

// setBlocked 는 회차 f 의 차단 상태를 맞춘다(Writer 계약 6) — floor 는 원장 하한, msn 은 최근 발행 목록 MSN 이다.
func (w *Writer) setBlocked(f Floor, on bool, floor, msn int64) {
	key := FloorKey(f.StreamID, f.RoundID)
	if w.blocked[key] == on {
		return
	}
	if on {
		w.blocked[key] = true
		w.opt.Log.Error(signalBlocksList, "value", 1, "stream", f.StreamID, "round", f.RoundID, "floor", floor, "msn", msn)
		return
	}
	delete(w.blocked, key)
	w.opt.Log.Error(signalBlocksList, "value", 0, "stream", f.StreamID, "round", f.RoundID)
}

// checkSize 는 KVS 크기가 SizeWarnBytes 를 넘을 때마다 edge_bounds_store_large 를 한 번 낸다.
func (w *Writer) checkSize(st Stat) {
	large := st.Bytes > w.opt.SizeWarnBytes
	if large && !w.storeLarge {
		w.opt.Log.Warn(signalStoreLarge, "bytes", st.Bytes, "items", st.Items, "warn_bytes", w.opt.SizeWarnBytes)
	}
	w.storeLarge = large
}
