package edgebounds

import (
	"slices"
	"time"
)

// pendingCap 는 회차 하나가 기억하는 Margin 안 관측 수의 상한이다. 발행은 조각(4초)마다 한 번이라 Margin 60초면
// 열다섯 개쯤이다. 넘치면 가장 새 관측 바로 앞 것을 버린다 — 하한이 늦게 오를 뿐이라 덜 막는 쪽이다.
const pendingCap = 64

// observation 은 되감기 발행 성공 하나다 — 그 목록의 MSN 과 발행 시각.
type observation struct {
	msn int64
	at  time.Time
}

// history 는 회차 하나의 관측 이력이다. ready 는 하한 후보 — Margin 을 지난 관측의 MSN 최댓값(설계 1절 floor 정의)이고,
// pending 은 아직 Margin 안인 관측, latest 는 최근 발행 목록의 MSN 이다(차단 상태 판정 — Writer 계약 6).
//
// pending 은 시각과 MSN 이 함께 오르는 관측만 둔다. 더 이르게 발행된 더 큰 MSN 이 있으면 그 관측은 성숙해도 ready 를
// 바꾸지 못하기 때문이다. 그래서 성숙한 앞부분의 마지막 MSN 이 그 부분의 최댓값이다.
type history struct {
	streamID, roundID string
	ready             int64
	pending           []observation
	latest            int64
}

// add 는 관측 o 를 더한다. cutoff(지금 − Margin) 이전에 발행된 관측은 곧바로 후보가 된다.
func (h *history) add(o observation, cutoff time.Time) {
	h.latest = o.msn
	h.mature(cutoff)
	if !o.at.After(cutoff) {
		h.ready = max(h.ready, o.msn)
		h.pending = slices.DeleteFunc(h.pending, func(p observation) bool { return p.msn <= h.ready })
		return
	}
	if o.msn <= h.ready {
		return
	}
	// i 는 o 보다 늦게 발행된 첫 관측 자리다. 그 앞의 마지막 관측이 o 이상이면 o 는 하한을 바꾸지 못한다.
	i := slices.IndexFunc(h.pending, func(p observation) bool { return p.at.After(o.at) })
	if i < 0 {
		i = len(h.pending)
	}
	if i > 0 && h.pending[i-1].msn >= o.msn {
		return
	}
	// o 보다 늦게 발행된 o 이하 관측은 o 가 먼저 성숙하므로 필요 없다.
	j := i
	for j < len(h.pending) && h.pending[j].msn <= o.msn {
		j++
	}
	h.pending = slices.Replace(h.pending, i, j, o)
	if n := len(h.pending); n > pendingCap {
		h.pending = slices.Delete(h.pending, n-2, n-1)
	}
}

// mature 는 cutoff 이전에 발행된 관측을 후보로 올린다.
func (h *history) mature(cutoff time.Time) {
	i := slices.IndexFunc(h.pending, func(p observation) bool { return p.at.After(cutoff) })
	if i < 0 {
		i = len(h.pending)
	}
	if i == 0 {
		return
	}
	h.ready = max(h.ready, h.pending[i-1].msn)
	h.pending = slices.Delete(h.pending, 0, i)
}
