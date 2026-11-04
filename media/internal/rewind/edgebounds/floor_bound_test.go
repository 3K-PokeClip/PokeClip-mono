package edgebounds

// 웹 계약 불변식 검증(86 공통 지시 · 장부 끝 절 — 2026-11-04 kty (가)로 범위를 좁힘): 「CDN 이 주는 그 회차의 최신
// 사슬 목록(가장 늦은 세션의 목록)의 첫 조각은 엣지가 막지 않는다」. 앞 사슬 목록의 이른 404 는 E15(K2 (가))로 남는다.

import (
	"context"
	"math/rand/v2"
	"testing"
	"time"
)

// chainLists 는 한 사슬(세션)의 목록 발행 차례다 — 사슬 안에서 MSN 은 줄지 않는다.
type chainLists struct {
	msn []int64
	at  []time.Time
}

func (c *chainLists) publish(msn int64, at time.Time) {
	c.msn = append(c.msn, msn)
	c.at = append(c.at, at)
}

func (c *chainLists) latest() int64 { return c.msn[len(c.msn)-1] }

// oldestServed 는 지금 CDN 이 줄 수 있는 이 사슬 목록 가운데 MSN 이 가장 작은 것이다. 보수적 시험 모델이다 — CDN 이
// Margin 전에 최신이던 목록부터 지금 최신 목록까지를 줄 수 있다고 본다(목록 max-age=1 보다 넓게 · 설계 D2). KVS 전파
// 늦음의 상한을 보장하는 문장이 아니다. MSN 이 줄지 않으므로 그 가운데 가장 작은 것은 Margin 전에 최신이던 목록이고,
// 그런 목록이 없으면(첫 목록이 Margin 안) 첫 목록이다.
func (c *chainLists) oldestServed(now time.Time, margin time.Duration) int64 {
	cut := now.Add(-margin)
	oldest := c.msn[0]
	for i, at := range c.at {
		if !at.After(cut) {
			oldest = c.msn[i]
		}
	}
	return oldest
}

// 최신 사슬의 목록 MSN 이 줄지 않게 발행될 때, 매 걸음 뒤 floor 는 CDN 이 지금 줄 수 있는 최신 사슬 목록의 첫 조각
// seq(= MSN) 이하다 — 지금 최신 목록뿐 아니라 Margin 전에 최신이던 목록까지(무작위 발행 간격 · 올림 시각 · Margin 60초 ·
// 0초). 잡는 결함: Margin 을 기다리지 않고 가장 새 관측을 하한으로 올리는 것 — CDN 이 아직 주는 바로 앞 목록의 첫
// 조각이 404 가 된다.
func TestFloorNeverBlocksFirstSegmentOfLatestChainList(t *testing.T) {
	for _, margin := range []time.Duration{60 * time.Second, 0} {
		for seed := uint64(1); seed <= 100; seed++ {
			checkLatestChainUnblocked(t, margin, seed)
		}
	}
}

func checkLatestChainUnblocked(t *testing.T, margin time.Duration, seed uint64) {
	t.Helper()
	rng := rand.New(rand.NewPCG(seed, 1))
	clock := newTestClock()
	l := newFakeLedger(clock.now)
	l.openRound("s", "r")
	opt := testOptions(clock.now, nil)
	opt.Margin = margin
	w := newTestWriter(t, l, newFakeStore(), opt)
	var chain chainLists
	msn := int64(1 + rng.IntN(100))
	for step := range 400 {
		clock.advance(time.Duration(1+rng.IntN(4000)) * time.Millisecond)
		now := clock.now()
		if rng.IntN(5) == 0 {
			if err := w.raise(context.Background()); err != nil {
				t.Fatalf("margin %v seed %d: raise returned error: %v", margin, seed, err)
			}
		} else {
			msn += int64(rng.IntN(3))
			chain.publish(msn, now)
			w.Observe("s", "r", msn, now)
		}
		row, ok := l.row("s", "r")
		if !ok {
			continue
		}
		if first := chain.oldestServed(now, margin); row.Value > first || row.Value > chain.latest() {
			t.Fatalf("margin %v seed %d step %d: floor %d > CDN 이 줄 수 있는 최신 사슬 목록의 첫 조각 %d(지금 최신 %d) — 막힌다",
				margin, seed, step, row.Value, first, chain.latest())
		}
	}
}

// E15(K2 (가) 수용 · kty (가)): 같은 회차에 앞 사슬 S1(MSN 이 낮게 멈춤) 뒤 새 사슬 S2(MSN 이 계속 오름)가 발행되면
// floor 가 S1 목록의 MSN 을 넘을 수 있다 — S1 목록 앞 조각의 이른 404 는 받아들인 한계다. 그때도 최신 사슬 S2 목록의 첫
// 조각은 막히지 않는다(S2 가 아직 없을 때는 S1 이 최신 사슬이다).
func TestFloorMayPassEarlierChainListButNeverLatestChainList(t *testing.T) {
	const margin = 60 * time.Second
	clock := newTestClock()
	l := newFakeLedger(clock.now)
	l.openRound("s", "r")
	w := newTestWriter(t, l, newFakeStore(), testOptions(clock.now, nil))
	var s1, s2 chainLists
	passedS1 := false
	for sec := 0; sec <= 400; sec += 2 {
		if sec > 0 {
			clock.advance(2 * time.Second)
		}
		now := clock.now()
		switch {
		case sec%4 == 0 && sec <= 40: // S1: MSN 10–20 뒤 멈춤
			s1.publish(10+int64(sec/4), now)
			w.Observe("s", "r", s1.latest(), now)
		case sec%4 == 0 && sec >= 44: // S2: MSN 100 부터 계속 오름
			s2.publish(100+int64((sec-44)/4), now)
			w.Observe("s", "r", s2.latest(), now)
		}
		if sec%30 == 0 {
			if err := w.raise(context.Background()); err != nil {
				t.Fatalf("%d초: raise returned error: %v", sec, err)
			}
		}
		row, ok := l.row("s", "r")
		if !ok {
			continue
		}
		latestChain := &s1
		if len(s2.msn) > 0 {
			latestChain = &s2
		}
		if first := latestChain.oldestServed(now, margin); row.Value > first {
			t.Fatalf("%d초: floor %d > 최신 사슬 목록의 첫 조각 %d — 막힌다", sec, row.Value, first)
		}
		if row.Value > s1.latest() {
			passedS1 = true
		}
	}
	if !passedS1 {
		t.Fatal("floor 가 S1 목록의 MSN 을 넘는 때가 없었다 — E15 경우를 만들지 못했다")
	}
}
