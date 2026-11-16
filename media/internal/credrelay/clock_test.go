package credrelay

import (
	"sync"
	"time"
)

// manualClock 은 시험이 직접 옮기는 시계다(ingestauth 의 manualClock 과 같은 꼴). 시작점은 실시계
// time.Now() 다 — 원천을 ec2rolecreds 로 바꾸는 변이(P7 ②)는 실시계(sdk.NowTime)로 만료를 1시간에 자르므로,
// 가짜 시계가 실시계와 같은 자리에서 출발해야 그 자름이 가짜 시계 위에서도 그대로 드러난다(계획 E9).
// 여러 고루틴이 함께 불러도 된다.
type manualClock struct {
	mu    sync.Mutex
	start time.Time
	t     time.Time
}

func newManualClock() *manualClock {
	start := time.Now()
	return &manualClock{start: start, t: start}
}

func (c *manualClock) now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}

// advance 는 시계를 d 만큼 옮긴다.
func (c *manualClock) advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.t = c.t.Add(d)
}

// moveTo 는 시계를 시작에서 offset 만큼 떨어진 시각으로 옮긴다.
func (c *manualClock) moveTo(offset time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.t = c.start.Add(offset)
}
