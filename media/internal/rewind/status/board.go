package status

import (
	"maps"
	"sync/atomic"
	"time"
)

// Entry 는 Board 의 한 스트림 항목이다.
type Entry struct {
	Stream Status      // 스트림 경로의 답(Derive 결과)
	Round  *RoundEntry // 종료 항목의 회차 사본. nil = 종료 항목 없음 또는 RoundID 빈 잠정 항목
}

// RoundEntry 는 종료 항목의 회차 사본이다 — 회차 경로가 루프의 종료 항목 대신 읽는다.
type RoundEntry struct {
	RoundID string
	Status  Status // final[s].Status
}

// Board 는 한 holdTick 의 응답 스냅숏이다. 만든 뒤에는 바뀌지 않아 여러 HTTP 고루틴이 잠금 없이 읽는다.
// nil Board(첫 스냅숏 전)는 모든 조회에 Unavailable 이다.
type Board struct {
	builtAt time.Time
	// warm 은 부팅 감시가 끝났는가다 — 거짓이면 모르는 스트림은 404 가 아니라 503 이다(아직 적재하지 못했을 수
	// 있다). 강등은 기다릴 부팅 감시가 없어 처음부터 참이다.
	warm bool
	// disabled 는 발행 꺼짐 Board 다 — 갱신되지 않으므로 낡음 판정보다 먼저 404 다.
	disabled bool
	entries  map[string]Entry
}

// NewBoard 는 builtAt 에 만든 스냅숏이다. entries 를 복사한다 — 루프가 다음 틱에 같은 map 을 고쳐도 이 스냅숏은
// 그대로다(R2).
func NewBoard(builtAt time.Time, warm bool, entries map[string]Entry) *Board {
	return &Board{builtAt: builtAt, warm: warm, entries: maps.Clone(entries)}
}

// DisabledBoard 는 발행 꺼짐(S3_BUCKET 빈 값)의 Board 다 — 모든 조회가 404 다. 조립이 한 번 넣고 갱신하지 않는다.
func DisabledBoard() *Board { return &Board{disabled: true} }

// Find 는 스트림 경로의 답이다(3.2절 표).
//
//	첫 스냅숏 전                   → Unavailable
//	발행 꺼짐                      → NotFound
//	스냅숏이 staleAfter 넘게 낡음    → Unavailable
//	항목 있음(부팅 감시 전 포함)      → Found
//	부팅 감시 전 ∧ 항목 없음          → Unavailable
//	그 밖                         → NotFound
func (b *Board) Find(streamID string, now time.Time, staleAfter time.Duration) (Status, Answer) {
	if a, ok := b.closed(now, staleAfter); ok {
		return Status{}, a
	}
	if e, ok := b.entries[streamID]; ok {
		return e.Stream, Found
	}
	return Status{}, b.unknown()
}

// FindRound 는 회차별 조회의 첫 단계다(경계 확정 4 조회 순서 · 보완 2 ㉡ · cc L8). hit 는 Board 가 그 회차를 들어
// 답했는가다.
//
//	첫 스냅숏 전 · 낡음              → Unavailable
//	발행 꺼짐                      → NotFound
//	항목의 회차 = roundID(부팅 전 포함) → (회차 사본, Found, hit)
//	부팅 감시 전 ∧ hit 아님          → Unavailable — Find 와 같은 꼴(모르는 것만)
//	그 밖(Board 밖 스트림 포함)        → Miss — 처리기가 lookup 으로 간다
//
// roundID 빈 값은 늘 hit 가 아니다(RoundID 빈 잠정 항목과 맞지 않게).
func (b *Board) FindRound(streamID, roundID string, now time.Time, staleAfter time.Duration) (Status, Answer, bool) {
	if a, ok := b.closed(now, staleAfter); ok {
		return Status{}, a, false
	}
	if r := b.entries[streamID].Round; roundID != "" && r != nil && r.RoundID == roundID {
		return r.Status, Found, true
	}
	if !b.warm {
		return Status{}, Unavailable, false
	}
	return Status{}, Miss, false
}

// closed 는 항목을 보기 전에 끝나는 답이다 — 첫 스냅숏 전 · 꺼짐 · 낡음.
func (b *Board) closed(now time.Time, staleAfter time.Duration) (Answer, bool) {
	switch {
	case b == nil:
		return Unavailable, true
	case b.disabled:
		return NotFound, true
	case now.Sub(b.builtAt) > staleAfter:
		return Unavailable, true
	}
	return 0, false
}

// unknown 은 Board 가 모르는 스트림의 답이다 — 부팅 감시 전이면 아직 적재하지 못했을 수 있어 503 이다.
func (b *Board) unknown() Answer {
	if !b.warm {
		return Unavailable
	}
	return NotFound
}

// Holder 는 최신 Board 하나를 든다. 루프가 Store 하고 HTTP 고루틴이 Load 한다.
type Holder struct{ p atomic.Pointer[Board] }

// Store 는 b 를 최신 스냅숏으로 둔다.
func (h *Holder) Store(b *Board) { h.p.Store(b) }

// Load 는 최신 스냅숏이다. 첫 Store 전이면 nil 이다 — nil Board 는 모든 조회에 Unavailable 이다.
func (h *Holder) Load() *Board { return h.p.Load() }
