package upload

import (
	"cmp"
	"slices"
	"sync"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// sessionMemory 는 업로더가 회차(세션)마다 메모리에 드는 셋이다(계획 2.1 · 4.2-R R3):
//
//	보정값 표   스위퍼 ③ 작업의 도장 위치를 다시 만드는 재료 — 인덱서의 실시간 ③ 요청만 쓴다
//	sessionInit 그 세션의 init CAS 가 확정한 해시 — 실시간 ③ 의 기대값이다
//	보류 목록   init 확정을 기다리는 ③ 작업 — 도장 위치를 확정한 작업만 든다
//
// 셋은 같은 수명 값(Options.SessionTTL — 녹화 파일 보존 1일과 같은 창)을 쓰고 같은 자리(스위퍼
// tick)에서 치운다. 한 뮤텍스가 전부를 지키고, 뮤텍스 안에서는 I/O 도 접수(enqueue)도 하지
// 않는다 — 인덱서 루프가 RequestUpload 로 여기 기록하므로 막히면 루프가 선다
// (indexer.UploadRequester 논블로킹 계약).
type sessionMemory struct {
	mu      sync.Mutex
	byID    map[string]*sessionEntry
	heldMax int
	ttl     time.Duration
}

// sessionEntry 는 한 회차의 몫이다. 셋의 수명 기준 시각은 서로 다르다.
type sessionEntry struct {
	// offsets 는 보정값 표의 offset 줄이다(seq_from → offset). 비어 있으면 표가 없다 — 그 회차의
	// 실시간 ③ 요청을 이 프로세스가 아직 못 봤다(재기동 포함).
	offsets map[int64]time.Duration
	// pinned 는 고정 줄이다(seq → pos) — mtxi 를 못 읽어 도장을 기대값으로 고정한 조각이다.
	pinned map[int64]time.Duration
	// tableSeen 은 그 회차의 마지막 실시간 ③ 요청 시각이다 — 표의 수명 기준이다.
	tableSeen time.Time

	// initSHA 는 그 세션의 init CAS 가 확정한 해시다(Success·AlreadySame). nil 이면 미확정이다.
	initSHA  []byte
	initSeen time.Time

	// held 는 init 확정을 기다리는 ③ 작업이다(seq → 작업). **도장 위치를 확정한 작업만** 든다 —
	// 재요청은 그 위치 그대로 재포장된다(계획 4.2-R R3 규칙 ⑤).
	held     map[int64]index.UploadTarget
	heldSeen time.Time
	// uploaded 는 ③ 이 이미 확정된 조각(seq)의 표시다 — 재요청이 꺼내 간 사이 확정된 조각을 giveBack 이
	// 되돌리지 않게 한다(Phase 3 r5 cx #2). 보류 목록에 딸려 수명·청소를 같이한다. 몫이 비었는가(empty)는
	// 가르지 않는다 — 재요청은 sessionInit 이 있는 몫에서만 꺼내 가므로 표시만 남은 몫은 지워도 된다.
	uploaded map[int64]struct{}
	// overflowWarned 는 이 회차의 보류 목록 넘침을 이미 WARN 으로 알렸는가다(세션당 첫 번만 WARN).
	overflowWarned bool
}

func newSessionMemory(opt Options) *sessionMemory {
	return &sessionMemory{byID: map[string]*sessionEntry{}, heldMax: opt.HeldPerSession, ttl: opt.SessionTTL}
}

// entry 는 세션의 몫을 돌려준다(없으면 만든다). 락을 잡은 채로 부른다.
func (m *sessionMemory) entry(sessionID string) *sessionEntry {
	e, ok := m.byID[sessionID]
	if !ok {
		e = &sessionEntry{}
		m.byID[sessionID] = e
	}
	return e
}

// recordLive 는 실시간 ③ 요청 하나를 그 회차의 보정값 표에 적고 표의 수명을 다시 센다.
//
//	표 생성   그 회차의 첫 요청이 첫 줄 {seq → StitchOffset} 을 만든다(별도 개시 표식 없음)
//	offset 줄 그 seq 에 적용되는 줄과 보정값이 다를 때만 더한다 — 요청은 seq 순이라 곧 "마지막 줄과 다를 때"다
//	고정 줄   PosPinned 면 {seq → PlaybackPos}
func (m *sessionMemory) recordLive(t index.UploadTarget, now time.Time) {
	m.mu.Lock()
	defer m.mu.Unlock()
	e := m.entry(t.SessionID)
	if off, ok := e.offsetAt(t.Seq); !ok || off != t.StitchOffset {
		if e.offsets == nil {
			e.offsets = map[int64]time.Duration{}
		}
		e.offsets[t.Seq] = t.StitchOffset
	}
	if t.PosPinned {
		if e.pinned == nil {
			e.pinned = map[int64]time.Duration{}
		}
		e.pinned[t.Seq] = t.PlaybackPos
	}
	e.tableSeen = now
}

// pinnedPos 는 그 조각의 고정 줄이다.
func (m *sessionMemory) pinnedPos(sessionID string, seq int64) (time.Duration, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	e, ok := m.byID[sessionID]
	if !ok {
		return 0, false
	}
	pos, ok := e.pinned[seq]
	return pos, ok
}

// offsetAt 은 그 조각에 적용되는 보정값이다 — seq_from ≤ seq 인 줄 중 **seq_from 이 가장 큰
// 줄**이다(삽입 순서가 아니다). 그런 줄이 없으면(표가 없거나 표가 그 뒤에서 시작) 거짓이다.
func (m *sessionMemory) offsetAt(sessionID string, seq int64) (time.Duration, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	e, ok := m.byID[sessionID]
	if !ok {
		return 0, false
	}
	return e.offsetAt(seq)
}

// offsetAt 은 sessionMemory.offsetAt 의 본체다. 락을 잡은 채로 부른다.
func (e *sessionEntry) offsetAt(seq int64) (time.Duration, bool) {
	var (
		from   int64
		offset time.Duration
		found  bool
	)
	for f, off := range e.offsets {
		if f <= seq && (!found || f > from) {
			from, offset, found = f, off, true
		}
	}
	return offset, found
}

// initOf 는 그 세션의 확정 init 해시다.
func (m *sessionMemory) initOf(sessionID string) ([]byte, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	e, ok := m.byID[sessionID]
	if !ok || e.initSHA == nil {
		return nil, false
	}
	return e.initSHA, true
}

// hold 는 init 확정을 기다릴 ③ 작업을 든다. 돌려주는 값은 "목록에 있는가"다 — 같은 키가 이미
// 있으면 그 항목을 그대로 둔 채 참이고(교체 금지 — 나중 것이 다른 도장일 수 있다), 상한에 닿았으면
// 들지 않고 거짓이다(그 조각은 장부에 pending 으로 남아 스위퍼가 다시 집는다).
func (m *sessionMemory) hold(t index.UploadTarget, now time.Time) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	e := m.entry(t.SessionID)
	if _, ok := e.held[t.Seq]; ok {
		return true
	}
	if len(e.held) >= m.heldMax {
		return false
	}
	if e.held == nil {
		e.held = map[int64]index.UploadTarget{}
	}
	e.held[t.Seq] = t
	e.heldSeen = now
	return true
}

// firstOverflow 는 이 회차의 보류 목록 넘침이 처음인가다 — 처음이면 표시하고 참이다.
func (m *sessionMemory) firstOverflow(sessionID string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	e := m.entry(sessionID)
	if e.overflowWarned {
		return false
	}
	e.overflowWarned = true
	return true
}

// confirmInit 은 그 세션의 init 해시를 확정으로 적고, 기다리던 작업을 목록에서 꺼내 돌려준다.
// 꺼낸 작업은 호출자가 다시 넣는다 — 들지 못한 몫은 giveBack 으로 되돌린다.
func (m *sessionMemory) confirmInit(sessionID string, sha []byte, now time.Time) []index.UploadTarget {
	m.mu.Lock()
	defer m.mu.Unlock()
	e := m.entry(sessionID)
	e.initSHA = append([]byte(nil), sha...)
	e.initSeen = now
	return e.takeHeld()
}

// claimReady 는 init 이 확정된 세션들에 남아 있던 작업을 꺼낸다 — 재요청이 한 번에 다 들지
// 못했을 때(큐 포화·게이트 거부) 다음 차례에 다시 넣기 위해서다.
func (m *sessionMemory) claimReady() []index.UploadTarget {
	m.mu.Lock()
	defer m.mu.Unlock()
	var out []index.UploadTarget
	for _, e := range m.byID {
		if e.initSHA != nil {
			out = append(out, e.takeHeld()...)
		}
	}
	return out
}

// giveBack 은 재요청이 들지 못한 작업을 목록에 되돌린다. 그사이 같은 키가 다시 보류됐으면
// 그 항목을 둔다(교체 금지). 그사이 확정된 조각(dropHeld 의 표시)은 되돌리지 않는다.
func (m *sessionMemory) giveBack(targets []index.UploadTarget) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, t := range targets {
		e := m.entry(t.SessionID)
		if _, done := e.uploaded[t.Seq]; done {
			continue
		}
		if e.held == nil {
			e.held = map[int64]index.UploadTarget{}
		}
		if _, ok := e.held[t.Seq]; !ok {
			e.held[t.Seq] = t
		}
	}
}

// dropHeld 는 그 조각의 보류 작업을 목록에서 빼고 확정 표시를 남긴다 — 같은 조각이 다른 경로(스위퍼
// 작업)로 이미 올라가 확정됐다. 남겨 두면 다음 drain 이 같은 키를 또 PUT 한다. 표시는 재요청이 그 조각을
// 이미 꺼내 간 때(claimReady 와 giveBack 사이 — 목록에 뺄 것이 없다)를 막는다.
func (m *sessionMemory) dropHeld(sessionID string, seq int64) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if e, ok := m.byID[sessionID]; ok {
		delete(e.held, seq)
		if e.uploaded == nil {
			e.uploaded = map[int64]struct{}{}
		}
		e.uploaded[seq] = struct{}{}
	}
}

// takeHeld 는 보류 목록을 비우고 그 작업들을 seq 순서로 돌려준다. 락을 잡은 채로 부른다.
//
// 순서가 계약이다 — 되감기 목록은 ③ uploaded 의 연속 접두까지만 실리므로(인덱스 불변식 1)
// 앞 조각부터 넣어야 목록이 늦지 않게 전진한다.
func (e *sessionEntry) takeHeld() []index.UploadTarget {
	out := make([]index.UploadTarget, 0, len(e.held))
	for _, t := range e.held {
		out = append(out, t)
	}
	slices.SortFunc(out, func(a, b index.UploadTarget) int { return cmp.Compare(a.Seq, b.Seq) })
	e.held = nil
	return out
}

// forget 은 끝난 회차의 sessionInit·보류 목록을 지운다. **보정값 표는 남긴다** — 정산 창
// (ending)의 스위퍼 재시도가 같은 보정값을 써야 한다(계획 4.2-R R3). 표는 수명이 치운다.
func (m *sessionMemory) forget(sessionID string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	e, ok := m.byID[sessionID]
	if !ok {
		return
	}
	e.initSHA, e.held, e.uploaded = nil, nil, nil
	if e.empty() {
		delete(m.byID, sessionID)
	}
}

// evict 는 수명이 지난 것을 치운다. 기준 시각이 셋마다 다르다:
//
//	보정값 표   마지막 실시간 ③ 요청 — 스위퍼·재요청은 수명을 늘리지 않는다(계획 뮤테이션 64)
//	sessionInit 확정과 마지막 실시간 ③ 요청 중 늦은 쪽 — 회차가 살아 있는 동안은 남는다
//	            (24시간 넘는 방송에서 확정값이 사라지면 그 뒤 실시간 ③ 이 전부 보류로 샌다)
//	보류 목록   마지막 보류
func (m *sessionMemory) evict(now time.Time) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for id, e := range m.byID {
		if m.expired(e.tableSeen, now) {
			e.offsets, e.pinned = nil, nil
		}
		if m.expired(later(e.initSeen, e.tableSeen), now) {
			e.initSHA = nil
		}
		if m.expired(e.heldSeen, now) {
			e.held, e.uploaded = nil, nil
		}
		if e.empty() {
			delete(m.byID, id)
		}
	}
}

// expired 는 seen 으로부터 수명이 지났는가다.
func (m *sessionMemory) expired(seen, now time.Time) bool {
	return !now.Before(seen.Add(m.ttl))
}

func later(a, b time.Time) time.Time {
	if a.After(b) {
		return a
	}
	return b
}

// empty 는 이 몫에 든 것이 없는가다 — 없으면 맵에서 지운다.
func (e *sessionEntry) empty() bool {
	return len(e.offsets) == 0 && len(e.pinned) == 0 && e.initSHA == nil && len(e.held) == 0
}
