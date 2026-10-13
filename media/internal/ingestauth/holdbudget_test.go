package ingestauth

import (
	"math"
	"sync"
	"testing"
	"time"
)

// balanceOf 는 b 의 잔액이다 — 마지막 allow · charge 가 남긴 값이고, 그 뒤 흐른 시간의 보충은 넣지 않는다.
func balanceOf(b *holdBudget) time.Duration {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.balance
}

// 연산 순서가 계약이다(설계 2-4 · 3-8 G3-5). allow 는 보충 → 한도 자르기 → 판정이고, charge 는 보충 → 차감
// → 한도 자르기다. 그래서 한도(1.375초)에서 시작한 1.175초 요청(바깥 가드)은 처리 중 차오른 0.5875초를
// 잃지 않아 잔액 0.7875초를 남긴다. 보충을 먼저 한도로 자르는 순서면 0.2초다.
func TestHoldBudgetChargeKeepsRefillDuringRequest(t *testing.T) {
	clock := newManualClock()
	b := newHoldBudget(clock.now)
	clock.advance(10 * time.Second) // 가득인 채로 쉰다 — allow 가 보충을 한도로 잘라 둬야 요청이 한도에서 시작한다
	if !b.allow() {
		t.Fatalf("allow() on a full budget = false, want true")
	}

	clock.advance(1175 * time.Millisecond)
	b.charge(1175 * time.Millisecond)

	if got, want := balanceOf(b), 787500*time.Microsecond; got != want {
		t.Errorf("balance after allow, 1.175s and charge(1.175s) = %v, want %v (보충 → 차감 → 한도 자르기)", got, want)
	}
}

// 잔액 0.1초에서 1.1초 요청이 들어가면 잔액은 −0.45초다(처리 중 차오름 0.55초 포함). 그다음 allow 는 0.9초가
// 지나야 참이다(설계 2-4 시험 「넘침 한계」). 0.9초 − 1ns 에 한 번 물어도 0.9초의 판정은 그대로다 — 부르는
// 횟수가 보충량을 바꾸지 않는다.
func TestHoldBudgetOverdraftRecoversAfterRefill(t *testing.T) {
	clock := newManualClock()
	b := newHoldBudget(clock.now)
	b.charge(1275 * time.Millisecond)
	if got, want := balanceOf(b), 100*time.Millisecond; got != want {
		t.Fatalf("Setup: balance after charge(1.275s) on a full budget = %v, want %v", got, want)
	}
	if !b.allow() {
		t.Fatalf("allow() with balance 0.1s = false, want true")
	}

	clock.advance(1100 * time.Millisecond)
	b.charge(1100 * time.Millisecond)
	if got, want := balanceOf(b), -450*time.Millisecond; got != want {
		t.Fatalf("balance after a 1.1s request from 0.1s = %v, want %v", got, want)
	}

	clock.advance(900*time.Millisecond - time.Nanosecond)
	if b.allow() {
		t.Errorf("allow() 0.9s−1ns after the overdraft = true, want false")
	}
	clock.advance(time.Nanosecond)
	if !b.allow() {
		t.Errorf("allow() 0.9s after the overdraft = false, want true (잔액 0 이상이면 참)")
	}
}

// 긴 유휴 뒤의 보충은 넘치지 않고 1.375초로 잘린다. 경과 × 0.5 를 time.Duration 곱으로
// 셈하면 경과 약 18.4초에서 int64 가 넘친다.
func TestHoldBudgetLongIdleRefillsToBurst(t *testing.T) {
	for _, idle := range []time.Duration{20 * time.Second, time.Hour, 1000 * time.Hour, math.MaxInt64} {
		t.Run(idle.String(), func(t *testing.T) {
			clock := newManualClock()
			b := newHoldBudget(clock.now)
			b.charge(1825 * time.Millisecond) // 1.375 → −0.45초

			clock.advance(idle)

			if !b.allow() {
				t.Errorf("allow() after %v idle from −0.45s = false, want true", idle)
			}
			if got, want := balanceOf(b), 1375*time.Millisecond; got != want {
				t.Errorf("balance after %v idle from −0.45s = %v, want %v", idle, got, want)
			}
		})
	}
}

// 시험 시계가 뒤로 가면 경과를 0 으로 보고 지금을 새 기준으로 삼는다(설계 2-4 — x/time rate.go:389-391 과
// 같은 처리). 잔액은 줄지 않고, 그 뒤의 정방향 경과는 되감은 시각부터 센다.
func TestHoldBudgetClockRewind(t *testing.T) {
	clock := newManualClock()
	b := newHoldBudget(clock.now)
	b.charge(1375 * time.Millisecond) // 잔액 0

	clock.advance(-10 * time.Second)
	if !b.allow() {
		t.Errorf("allow() with balance 0 after the clock went back 10s = false, want true (잔액이 줄지 않는다)")
	}
	if got, want := balanceOf(b), time.Duration(0); got != want {
		t.Errorf("balance after the clock went back 10s = %v, want %v", got, want)
	}

	clock.advance(time.Second)
	b.allow()
	if got, want := balanceOf(b), 500*time.Millisecond; got != want {
		t.Errorf("balance 1s after the rewound time = %v, want %v (되감은 시각부터 센다)", got, want)
	}
}

// 걸린 시간이 음수면(시험 시계가 요청 중에 뒤로 감) 0 으로 본다 — 앞서 쓴 몫을 되돌리지 않는다(설계 3-8
// (ii) 「되돌리는 동작이 셈에 없다」).
func TestHoldBudgetChargeIgnoresNegativeDuration(t *testing.T) {
	clock := newManualClock()
	b := newHoldBudget(clock.now)
	b.charge(1375 * time.Millisecond) // 잔액 0

	b.charge(-time.Second)

	if got, want := balanceOf(b), time.Duration(0); got != want {
		t.Errorf("balance after charge(−1s) at 0 = %v, want %v (되돌림 없음)", got, want)
	}
}

// allow · charge 는 여러 요청 고루틴이 함께 불러도 된다 — 차감이 빠지지 않는다. 판마다 시험 고루틴이 출발 장벽을
// 열고 일꾼 모두가 함께 한 번씩 부른다 — 한 일꾼이 제 몫을 다 끝낸 뒤 다음 일꾼이 도는 스케줄은 생기지 않는다.
// 시계를 멈춰 두면 보충이 없으므로 잔액은 처음 1.375초에서 차감의 합을 뺀 값이다.
func TestHoldBudgetConcurrentUse(t *testing.T) {
	clock := newManualClock()
	b := newHoldBudget(clock.now)
	const workers, rounds = 50, 20

	for range rounds {
		start := make(chan struct{})
		var wg sync.WaitGroup
		for range workers {
			wg.Go(func() {
				<-start
				b.allow()
				b.charge(time.Millisecond)
			})
		}
		close(start)
		wg.Wait()
	}

	if got, want := balanceOf(b), 375*time.Millisecond; got != want {
		t.Errorf("balance after %d concurrent charges of 1ms = %v, want %v", workers*rounds, got, want)
	}
}

// now 가 nil 이면 time.Now 를 쓴다(OpenConnLog 와 같은 규칙). 처음 상태는 가득이다.
func TestNewHoldBudgetNilNowUsesTimeNow(t *testing.T) {
	b := newHoldBudget(nil)
	if !b.allow() {
		t.Errorf("allow() on a new budget with now == nil = false, want true")
	}
}
