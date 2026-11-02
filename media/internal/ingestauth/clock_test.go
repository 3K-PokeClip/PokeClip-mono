package ingestauth

import (
	"sync"
	"time"
)

// manualClock 은 시험이 직접 옮기는 시계다 — 앞으로도 뒤로도 간다. 예산과 실패 토큰 차례는 모두 시각
// 비교라, 실제로 기다리지 않고 시각을 옮겨 시험한다(upload 패키지의 fakeClock 과 같은 방식). 여러
// 고루틴이 함께 불러도 된다.
type manualClock struct {
	mu    sync.Mutex
	start time.Time
	t     time.Time
}

func newManualClock() *manualClock {
	start := time.Date(2026, 10, 12, 9, 0, 0, 0, time.UTC)
	return &manualClock{start: start, t: start}
}

func (c *manualClock) now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}

// advance 는 시계를 d 만큼 옮긴다. d 가 음수면 되감기다.
func (c *manualClock) advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.t = c.t.Add(d)
}

// moveTo 는 시계를 시험 시작에서 offset 만큼 떨어진 시각으로 옮긴다. 지금보다 이르면 되감기다.
func (c *manualClock) moveTo(offset time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.t = c.start.Add(offset)
}
