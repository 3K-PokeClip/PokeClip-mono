package upload

import (
	"cmp"
	"slices"
	"sync"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
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
//
// ③ 사슬 복원(POK-196 PDT 계획 4절 c10b · 설계 r6 2.10)의 몫도 여기 든다 — 표의 끊김 줄 · 복원 링크 · 불일치 구멍 · 깨움 ·
// 세션 단위 백오프. 복원 작업 자체(DB · S3 · 파일)는 이 뮤텍스 밖에서 돈다(restore.go).
type sessionMemory struct {
	mu      sync.Mutex
	byID    map[string]*sessionEntry
	heldMax int
	ttl     time.Duration

	// wakes 는 복원을 기다리는 회차다(세션 → 스트림). 깨움은 회차마다 하나로 합쳐진다.
	wakes map[string]string
	// wakeSig 는 복원 고루틴을 깨우는 신호다(용량 1 — 보내기가 막히지 않는다).
	wakeSig chan struct{}
	// backoffBase · backoffMax 는 복원 실패의 세션 단위 백오프다(SweepEvery · 2^n · 상한 BackoffMax — 게이트와 같은 꼴).
	backoffBase, backoffMax time.Duration
	// tableGens 는 표 세대를 매기는 셈이다 — 회차 몫마다 따로 세지 않는다(몫이 지워졌다 다시 생겨도 세대가 겹치지 않게).
	tableGens uint64
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
	// tableGen 은 표의 세대다 — 몫이 생길 때와 수명이 표를 치울 때 새 값을 받는다. 복원이 베낀 표가 덩어리를 쓰는 사이 치워졌는가를
	// 가른다(recordRestored).
	tableGen uint64

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

	// breaks 는 끊김 줄이다(seq 집합) — 인덱서가 위치 모름(PosUnknown)으로 요청한 조각이다. 그 seq 부터 다음 줄 전까지 표가 없다.
	// 보정값 표와 수명 · 청소를 같이한다.
	breaks map[int64]struct{}
	// restored 는 복원이 마지막으로 정한 링크다 — 인덱서가 RestoredLink 로 읽어 사슬을 다시 안다.
	restored *restoredLink
	// mismatches 는 불일치로 판정한 구멍과 그때의 입력 지문이다 — 같은 입력이면 다시 판정하지 않는다.
	mismatches map[holeSpan]string
	// restoreFails · restoreNext 는 복원 실패 횟수와 다음 시도 시각이다(세션 단위 백오프).
	restoreFails int
	restoreNext  time.Time
}

// restoredLink 는 복원이 정한 링크 하나와 그 행을 읽을 때의 장부 크기다 — 크기가 바뀌었으면(꼬리 교정) 그 행부터 다시 읽는다.
// exact 는 링크의 도장이 실제 값인가다(닻을 ③ 객체 범위 가운데로 골랐으면 거짓 — 범위 폭만큼 다를 수 있다).
type restoredLink struct {
	link  playback.Link
	bytes int64
	exact bool
	// slackLo · slackHi 는 exact 가 거짓일 때 실제 값이 도장에서 [−slackLo, +slackHi] 안이라는 범위다.
	slackLo, slackHi time.Duration
}

// holeSpan 은 구멍 한 무리다 — 구멍 첫 · 끝 seq, 닻 seq(없으면 −1), 구멍 뒤 대조한 uploaded 행 seq.
type holeSpan struct{ from, to, anchor, check int64 }

// restoreWake 는 깨운 복원 한 건이다.
type restoreWake struct{ streamID, sessionID string }

func newSessionMemory(opt Options) *sessionMemory {
	return &sessionMemory{byID: map[string]*sessionEntry{}, heldMax: opt.HeldPerSession, ttl: opt.SessionTTL,
		wakes: map[string]string{}, wakeSig: make(chan struct{}, 1), backoffBase: opt.SweepEvery, backoffMax: opt.BackoffMax}
}

// entry 는 세션의 몫을 돌려준다(없으면 만든다). 락을 잡은 채로 부른다.
func (m *sessionMemory) entry(sessionID string) *sessionEntry {
	e, ok := m.byID[sessionID]
	if !ok {
		e = &sessionEntry{tableGen: m.nextTableGen()}
		m.byID[sessionID] = e
	}
	return e
}

// nextTableGen 은 새 표 세대다. 락을 잡은 채로 부른다.
func (m *sessionMemory) nextTableGen() uint64 {
	m.tableGens++
	return m.tableGens
}

// recordLive 는 실시간 ③ 요청 하나를 그 회차의 보정값 표에 적고 표의 수명을 다시 센다.
//
//	표 생성   그 회차의 첫 요청이 첫 줄 {seq → StitchOffset} 을 만든다(별도 개시 표식 없음)
//	offset 줄 그 seq 에 적용되는 줄과 보정값이 다를 때만 더한다 — 요청은 seq 순이라 곧 "마지막 줄과 다를 때"다
//	고정 줄   PosPinned 면 {seq → PlaybackPos}
//	끊김 줄   PosUnknown 이면 {seq} — 그 seq 부터 표가 없다. 그 회차의 복원을 깨운다
func (m *sessionMemory) recordLive(t index.UploadTarget, now time.Time) {
	m.mu.Lock()
	defer m.mu.Unlock()
	e := m.entry(t.SessionID)
	if t.PosUnknown {
		if e.breaks == nil {
			e.breaks = map[int64]struct{}{}
		}
		e.breaks[t.Seq] = struct{}{}
		e.tableSeen = now
		m.wakeLocked(t.StreamID, t.SessionID)
		return
	}
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

// offsetAt 은 sessionMemory.offsetAt 의 본체다. 락을 잡은 채로 부른다. 그 줄보다 뒤에(같은 seq 는 아니고) 끊김 줄이 있으면
// 표가 없다 — 끊김 줄 뒤 조각은 앞 줄의 보정값이 맞는지 모른다.
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
	for b := range e.breaks {
		if b <= seq && (!found || b > from) {
			return 0, false
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
			e.offsets, e.pinned, e.breaks, e.restored, e.mismatches = nil, nil, nil, nil, nil
			e.restoreFails, e.restoreNext = 0, time.Time{}
			e.tableGen = m.nextTableGen()
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
	return len(e.offsets) == 0 && len(e.pinned) == 0 && e.initSHA == nil && len(e.held) == 0 &&
		len(e.breaks) == 0 && e.restored == nil && len(e.mismatches) == 0 && e.restoreFails == 0
}

// recordRestored 는 복원이 정한 링크들로 표를 채운다(설계 r6 2.10 「구멍 하나의 처리」). 표가 이미 아는 seq(끊김 줄 뒤가 아닌
// 줄이 있다)는 덮지 않는다 — 실시간이 적은 줄이 정본이다. 모르는 seq 에는 그 링크의 offset 줄을, 고정 링크면 고정 줄도 적고
// 같은 seq 의 끊김 줄을 걷는다. 표를 이 복원이 처음 만들면 수명을 지금부터 센다 — 실시간 요청이 없는 회차(재기동 뒤 스위퍼가
// 깨운 복원)의 표가 다음 tick 에 바로 치워지지 않게 한다.
//
// 공용 뮤텍스는 인덱서 루프의 RestoredLink · RequestUpload 도 잡으므로 짧게 쥔다(c10b 확인 패스 2회차 cx H — 21,600 링크를 잠금 안에서
// 링크마다 표 전체로 견주면 O(n²) 로 다른 회차가 2.5초 멈췄다). 표의 열쇠만 잠금 안에서 베끼고, 아는가는 잠금 밖에서 정렬 · 이분
// 탐색으로 가르고, 쓰기는 restoredChunk 링크씩 잠근다.
//
// 덩어리는 seq 가 큰 쪽부터 쓴다(PR 종결 r1 cc H). 앞 덩어리부터 쓰면 그 덩어리가 끊김 줄을 걷은 뒤 ~ 뒤 덩어리를 쓰기 전에
// 뒤 seq 가 앞 덩어리 끝 줄의 보정값으로 「아는」 것처럼 보이고, 스위퍼 ③ 작업이 그 값으로 만들어 CAS 로 굳힌다. 뒤에서부터
// 쓰면 아직 안 쓴 seq 의 「seq 이하 마지막 줄 · 그 뒤 끊김 줄」 은 쓰기 전 그대로라(새 줄 · 걷은 끊김 줄은 모두 그보다 뒤다) 읽기는
// 언제나 「모름」 이거나 다 쓴 뒤와 같은 값이다. 돌려주는 값은 실제로 적은 줄 수다(실시간 줄이 이긴 seq 는 빠진다).
//
// 덩어리 사이에 수명이 표를 치우면(evict — 표 세대가 바뀐다) 남은 덩어리를 쓰지 않고 0 을 돌려준다(PR 종결 r2 cx H). 치움은 이미 쓴
// 큰 seq 줄 · 끊김 줄을 함께 지우므로, 작은 seq 덩어리를 마저 쓰면 큰 seq 가 그 덩어리 끝 줄의 보정값으로 「아는」 것처럼 보인다.
// 멈추면 이 복원이 쓴 줄은 하나도 남지 않아 모든 seq 가 「모름」 이다 — 스위퍼의 표 없는 ③ 작업이 복원을 다시 깨운다. 세대 대조는
// 덩어리 잠금 안의 비교 하나라 잠금을 더 오래 쥐지 않는다.
func (m *sessionMemory) recordRestored(sessionID string, links []playback.Link, now time.Time) int {
	lines, breaks, gen := m.tableKeys(sessionID)
	unknown := unknownLinks(links, lines, breaks)
	slices.SortStableFunc(unknown, func(a, b playback.Link) int { return cmp.Compare(a.Seq, b.Seq) })
	written := 0
	for to := len(unknown); to > 0; to -= restoredChunk {
		n, ok := m.writeRestored(sessionID, gen, unknown[max(to-restoredChunk, 0):to], now)
		if !ok {
			return 0
		}
		written += n
	}
	if len(unknown) == 0 {
		m.writeRestored(sessionID, gen, nil, now)
	}
	return written
}

// restoredChunk 는 복원 줄을 한 번 잠가 쓰는 링크 수다 — 맵 쓰기 이만큼은 수백 µs 안이다.
const restoredChunk = 1024

// tableKeys 는 그 회차 표의 offset 줄 · 끊김 줄 seq 를 정렬해 돌려주고, 베낀 표의 세대도 돌려준다(잠금 안에서는 베끼기만 한다).
// 몫이 없으면 만든다 — 세대를 매길 몫이 있어야 첫 덩어리를 쓰기 전의 치움도 가른다.
func (m *sessionMemory) tableKeys(sessionID string) (lines, breaks []int64, gen uint64) {
	m.mu.Lock()
	e := m.entry(sessionID)
	lines = make([]int64, 0, len(e.offsets))
	for f := range e.offsets {
		lines = append(lines, f)
	}
	breaks = make([]int64, 0, len(e.breaks))
	for b := range e.breaks {
		breaks = append(breaks, b)
	}
	gen = e.tableGen
	m.mu.Unlock()
	slices.Sort(lines)
	slices.Sort(breaks)
	return lines, breaks, gen
}

// unknownLinks 는 표가 모르는 seq 의 링크다 — offsetAt 과 같은 판정(seq 이하 마지막 줄이 있고 그 뒤 끊김 줄이 없으면 안다)을 정렬한
// 열쇠의 이분 탐색으로 한다. 아는가는 채우기 전의 표로 가른다 — 앞 링크의 줄을 적고 나면 뒤 seq 가 그 줄로 「아는」 것처럼 보인다.
func unknownLinks(links []playback.Link, lines, breaks []int64) []playback.Link {
	lastAtOrBefore := func(keys []int64, seq int64) (int64, bool) {
		i, found := slices.BinarySearch(keys, seq)
		if found {
			return seq, true
		}
		if i == 0 {
			return 0, false
		}
		return keys[i-1], true
	}
	unknown := make([]playback.Link, 0, len(links))
	for _, l := range links {
		f, hasLine := lastAtOrBefore(lines, l.Seq)
		b, hasBreak := lastAtOrBefore(breaks, l.Seq)
		if !hasLine || (hasBreak && b > f) {
			unknown = append(unknown, l)
		}
	}
	return unknown
}

// writeRestored 는 복원 줄 한 묶음을 쓰고 적은 줄 수를 돌려준다. 베낀 뒤 실시간 요청이 그 seq 에 줄을 적었으면 그 줄을 둔다(실시간
// 값이 정본이다). 표가 베낀 세대(gen)가 아니면(그사이 치워졌다) 쓰지 않고 거짓이다.
func (m *sessionMemory) writeRestored(sessionID string, gen uint64, links []playback.Link, now time.Time) (int, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	e, ok := m.byID[sessionID]
	if !ok || e.tableGen != gen {
		return 0, false
	}
	written := 0
	for _, l := range links {
		if _, live := e.offsets[l.Seq]; live {
			continue
		}
		written++
		if e.offsets == nil {
			e.offsets = map[int64]time.Duration{}
		}
		e.offsets[l.Seq] = l.Offset
		delete(e.breaks, l.Seq)
		if l.Pinned {
			if e.pinned == nil {
				e.pinned = map[int64]time.Duration{}
			}
			e.pinned[l.Seq] = l.Pos
		}
	}
	if e.tableSeen.IsZero() {
		e.tableSeen = now
	}
	return written, true
}

// setRestored 는 복원이 정한 그 회차의 마지막 링크를 적는다.
func (m *sessionMemory) setRestored(sessionID string, rl restoredLink) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.entry(sessionID).restored = &rl
}

// restoredLink 는 그 회차의 복원 링크다 — 맵 읽기 하나라 인덱서 루프에서 불러도 막히지 않는다.
func (m *sessionMemory) restoredLink(sessionID string) (restoredLink, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	e, ok := m.byID[sessionID]
	if !ok || e.restored == nil {
		return restoredLink{}, false
	}
	return *e.restored, true
}

// recordMismatch 는 불일치로 판정한 구멍을 그 입력 지문(판정에 쓴 행들의 장부 크기)과 함께 적는다.
func (m *sessionMemory) recordMismatch(sessionID string, span holeSpan, fingerprint string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	e := m.entry(sessionID)
	if e.mismatches == nil {
		e.mismatches = map[holeSpan]string{}
	}
	e.mismatches[span] = fingerprint
}

// knownMismatch 는 같은 입력으로 이미 불일치 판정한 구멍인가다.
func (m *sessionMemory) knownMismatch(sessionID string, span holeSpan, fingerprint string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	e, ok := m.byID[sessionID]
	if !ok {
		return false
	}
	fp, ok := e.mismatches[span]
	return ok && fp == fingerprint
}

// wake 는 그 회차의 복원을 깨운다 — 스위퍼의 표 없는 ③ 작업이 부른다. 막히지 않는다.
func (m *sessionMemory) wake(streamID, sessionID string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.wakeLocked(streamID, sessionID)
}

// wakeLocked 는 wake 의 본체다. 락을 잡은 채로 부른다 — 신호 보내기는 용량 1 채널의 비차단 보내기라 락 안에서도 막히지 않는다.
func (m *sessionMemory) wakeLocked(streamID, sessionID string) {
	m.wakes[sessionID] = streamID
	select {
	case m.wakeSig <- struct{}{}:
	default:
	}
}

// wakeCh 는 복원 고루틴이 기다리는 신호다.
func (m *sessionMemory) wakeCh() <-chan struct{} { return m.wakeSig }

// takeWakes 는 기다리던 깨움을 비우고, 백오프가 끝난 회차만 seq 와 무관하게 세션 순서로 돌려준다. 백오프 안의 깨움은 버린다 —
// 위치 모름 요청(조각마다)과 스위퍼(30초마다)가 다시 깨운다.
func (m *sessionMemory) takeWakes(now time.Time) []restoreWake {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]restoreWake, 0, len(m.wakes))
	for session, stream := range m.wakes {
		if e, ok := m.byID[session]; ok && now.Before(e.restoreNext) {
			continue
		}
		out = append(out, restoreWake{streamID: stream, sessionID: session})
	}
	clear(m.wakes)
	slices.SortFunc(out, func(a, b restoreWake) int { return cmp.Compare(a.sessionID, b.sessionID) })
	return out
}

// restoreFailed 는 그 회차의 복원 실패를 센다 — 다음 시도는 SweepEvery · 2^(n−1) 뒤다(상한 BackoffMax).
func (m *sessionMemory) restoreFailed(sessionID string, now time.Time) time.Time {
	m.mu.Lock()
	defer m.mu.Unlock()
	e := m.entry(sessionID)
	wait := m.backoffBase << min(e.restoreFails, 30)
	if wait <= 0 || wait > m.backoffMax {
		wait = m.backoffMax
	}
	e.restoreFails++
	e.restoreNext = now.Add(wait)
	return e.restoreNext
}

// restoreIdle 은 진전 없이 끝난 복원 뒤 다음 시도를 SweepEvery 뒤로 미룬다 — 실패 횟수는 세지 않는다(c10b 보안 M).
func (m *sessionMemory) restoreIdle(sessionID string, now time.Time) {
	m.mu.Lock()
	defer m.mu.Unlock()
	e := m.entry(sessionID)
	e.restoreFails, e.restoreNext = 0, now.Add(m.backoffBase)
}

// restoreSucceeded 는 그 회차의 백오프를 푼다.
func (m *sessionMemory) restoreSucceeded(sessionID string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if e, ok := m.byID[sessionID]; ok {
		e.restoreFails, e.restoreNext = 0, time.Time{}
	}
}
