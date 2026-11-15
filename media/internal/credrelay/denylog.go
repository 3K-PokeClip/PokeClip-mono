package credrelay

import (
	"sync"
	"time"
)

// denyLimiter 는 거부 줄(cred_denied)을 denyLogInterval 에 한 줄로 묶는다. 줄을 내지 않은 거부는 세어 두었다가
// 다음 줄의 count 에 싣는다. 여러 고루틴이 함께 불러도 된다.
type denyLimiter struct {
	mu      sync.Mutex
	logged  bool      // 한 줄이라도 냈다
	last    time.Time // 마지막 줄의 시각
	pending int       // 마지막 줄 뒤의 거부 수(이번 것 포함)
}

// note 는 시각 t 의 거부 하나를 세고, 줄을 낼 차례면 그 줄의 count(지난 줄 뒤 억제된 수 + 이번 1)와 참을
// 돌려준다.
func (d *denyLimiter) note(t time.Time) (count int, emit bool) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.pending++
	if d.logged && t.Sub(d.last) < denyLogInterval {
		return 0, false
	}
	count, d.pending, d.last, d.logged = d.pending, 0, t, true
	return count, true
}
