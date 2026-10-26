package control

import (
	"container/list"
	"log/slog"
	"sync"
	"time"
)

// Limiter 는 스트림별 1초 고정 창 계수기다. startControl 안에서 하나 만들어 두 처리기가 나눠 쓴다(보완 2 ㉠).
// 세는 것은 「답한 요청」뿐이다 — 스트림 경로의 Found 답 · 회차 경로의 Board hit · 1초 캐시 적중(보완 3).
// 칸 상한 maxCells — 넘으면 가장 오래 쓰지 않은 칸을 축출한다(그 칸이 세움 상태였으면 control_rate_limited 내림 줄을
// 쓴다). 60초마다 쓰지 않은 칸을 청소하는 고루틴은 Start 가 띄우고 Server.Shutdown 이 멈춘다. 여러 고루틴이 함께 부른다.
type Limiter struct {
	rps, maxCells int
	now           func() time.Time
	log           *slog.Logger

	mu    sync.Mutex // 아래 둘을 지키고, 게이지 줄을 전이 차례대로 낸다
	cells map[string]*list.Element
	lru   *list.List // 값 *rateCell · 앞이 가장 최근에 쓴 칸
}

// rateCell 은 한 스트림의 계수기 칸이다.
type rateCell struct {
	stream      string
	windowStart time.Time
	count       int
	usedAt      time.Time
	// limited 는 control_rate_limited 게이지가 선 칸이다. limitedAt 은 마지막 초과 시각이다.
	limited   bool
	limitedAt time.Time
}

// NewLimiter 는 스트림당 초당 rps 요청을 받는 계수기다. 칸은 maxCells 까지다. now 가 nil 이면 time.Now 다. log 는
// control_rate_limited 세움 · 내림 줄을 받는다.
func NewLimiter(rps, maxCells int, now func() time.Time, log *slog.Logger) *Limiter {
	if now == nil {
		now = time.Now
	}
	if log == nil {
		log = slog.New(slog.DiscardHandler)
	}
	return &Limiter{rps: rps, maxCells: maxCells, now: now, log: log, cells: map[string]*list.Element{}, lru: list.New()}
}

// Allow 는 streamID 의 요청 하나를 센다. 이 1초 창에서 rps 를 넘으면 거짓이다 — 칸의 첫 초과에 세움 줄을 쓴다.
func (l *Limiter) Allow(streamID string) bool {
	now := l.now()
	l.mu.Lock()
	defer l.mu.Unlock()
	c := l.cell(streamID, now)
	c.usedAt = now
	if now.Sub(c.windowStart) >= time.Second {
		c.windowStart, c.count = now, 0
	}
	c.count++
	if c.count <= l.rps {
		return true
	}
	c.limitedAt = now
	if !c.limited {
		c.limited = true
		l.log.Warn("control_rate_limited", "value", 1, "stream_id", streamID, "limit", l.rps)
	}
	return false
}

// cell 은 streamID 의 칸이다 — 없으면 만들고, 상한이면 가장 오래 쓰지 않은 칸을 먼저 축출한다.
func (l *Limiter) cell(streamID string, now time.Time) *rateCell {
	if e, ok := l.cells[streamID]; ok {
		l.lru.MoveToFront(e)
		return e.Value.(*rateCell)
	}
	for len(l.cells) >= l.maxCells && l.lru.Len() > 0 {
		l.remove(l.lru.Back())
	}
	c := &rateCell{stream: streamID, windowStart: now}
	l.cells[streamID] = l.lru.PushFront(c)
	return c
}

// remove 는 칸 e 를 지운다. 세움 상태였으면 내림 줄을 먼저 쓴다 — 게이지가 1 로 굳지 않게.
func (l *Limiter) remove(e *list.Element) {
	c := e.Value.(*rateCell)
	if c.limited {
		l.log.Info("control_rate_limited", "value", 0, "stream_id", c.stream, "limit", l.rps)
	}
	l.lru.Remove(e)
	delete(l.cells, c.stream)
}

// sweep 은 now 기준으로 60초 무초과 칸의 게이지를 내리고 60초 쓰지 않은 칸을 지운다(서버의 청소 고루틴이 부른다).
func (l *Limiter) sweep(now time.Time) {
	if l == nil {
		return
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	for e := l.lru.Back(); e != nil; {
		prev, c := e.Prev(), e.Value.(*rateCell)
		if c.limited && now.Sub(c.limitedAt) >= sweepEvery {
			c.limited = false
			l.log.Info("control_rate_limited", "value", 0, "stream_id", c.stream, "limit", l.rps)
		}
		if now.Sub(c.usedAt) >= sweepEvery {
			l.remove(e)
		}
		e = prev
	}
}
