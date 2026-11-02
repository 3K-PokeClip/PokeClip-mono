package ingestauth

import (
	"sync"
	"time"
)

const (
	holdBurst  = 1375 * time.Millisecond // 잔액 한도 = B_obs ÷ 2. 묶음 동안에도 차오르므로 한 묶음이 쓰는 auth 시간은 B_obs 다
	holdRefill = 500 * time.Millisecond  // 1초마다 차오르는 양. 긴 구간에서 auth 몫 이용률의 상한이 1/2 이다
)

// holdBudget 은 응답기가 auth 를 부르느라 MediaMTX 경로 관리자를 붙잡는 시간의 예산이다(설계 3-8).
// 토큰 버킷이다. 잔액이 0 이상이면 auth 를 부르고, 요청이 끝나면 걸린 시간만큼 뺀다(음수가 될 수 있다).
// 잔액은 1초마다 holdRefill 만큼 차오르고 holdBurst 를 넘지 않는다. 판정 결과와 무관하다. 처음 상태는
// 가득이다.
//
// 연산 순서는 계약이다. allow 는 보충 → 한도 자르기 → 판정이고, charge 는 보충 → 차감 → 한도 자르기다.
// 그래서 한도에서 시작한 요청도 처리 중에 차오른 몫을 잃지 않는다(설계 3-8).
// 시각은 부를 때 비교한다(게으른 평가 — media/internal/upload 의 breaker 와 같은 방식). 경과는 now 가 준
// 값 그대로(단조 시계) 재고, 음수면 0 으로 본다. 처리기 안에만 있고 내보내지 않는다. 여러 고루틴이 함께
// 불러도 된다.
type holdBudget struct {
	now func() time.Time

	mu        sync.Mutex    // 아래 셋을 지킨다
	balance   time.Duration // 잔액. 음수일 수 있다(후불 차감의 넘침)
	updatedAt time.Time     // balance 가 맞는 시각 — 마지막 allow · charge 의 지금
	carry     time.Duration // 1ns 에 못 미쳐 아직 잔액에 넣지 못한 보충. 단위는 1ns 의 10억분의 1(0 이상 time.Second 미만)
}

// newHoldBudget 은 가득 찬 예산을 만든다. now 가 nil 이면 time.Now 다.
func newHoldBudget(now func() time.Time) *holdBudget {
	if now == nil {
		now = time.Now
	}
	return &holdBudget{now: now, balance: holdBurst, updatedAt: now()}
}

// allow 는 지금 auth 를 불러도 되는지다. 지난 갱신 뒤 흐른 시간만큼 보충하고 holdBurst 로 자른 뒤,
// 잔액이 0 이상이면 참이다.
func (b *holdBudget) allow() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.refill()
	b.clamp()
	return b.balance >= 0
}

// charge 는 요청 하나가 걸린 시간 d 를 잔액에서 뺀다. 지난 갱신 뒤 흐른 시간만큼 보충하고, d 를 뺀 다음
// holdBurst 로 자른다 — 차감이 자르기보다 먼저다. NewHandler 의 바깥 층이 요청마다 defer 로 한 번
// 부른다. 그래서 패닉이나 바깥 가드로 끝난 요청도 빠지지 않고, 가드 뒤에 늦게 끝난 처리기는 다시 빼지
// 않는다. d 가 음수면(시험 시계가 요청 중에 뒤로 감) 0 으로 본다 — 앞서 쓴 몫을 되돌리지 않는다.
func (b *holdBudget) charge(d time.Duration) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.refill()
	b.balance -= max(d, 0)
	b.clamp()
}

// refill 은 지난 갱신 뒤 흐른 시간만큼 잔액을 채우고(한도 자르기 전) 지금을 갱신 시각으로 삼는다. 경과가
// 음수면(시험 시계가 뒤로 감) 0 으로 본다 — 지금이 새 기준이다(x/time rate.go:389-391 과 같은 처리).
// 경과 × holdRefill 을 곧바로 곱하면 경과 약 18초에서 int64 가 넘치므로 초와 그 나머지를 따로 곱한다.
// 1ns 에 못 미친 몫은 버리지 않고 carry 로 다음 보충에 넘긴다 — 부르는 횟수가 보충량을 바꾸지 않는다.
// b.mu 를 잡은 채로 부른다.
func (b *holdBudget) refill() {
	now := b.now()
	elapsed := max(now.Sub(b.updatedAt), 0)
	b.updatedAt = now
	scaled := elapsed%time.Second*holdRefill + b.carry // 1초 미만 경과의 보충 × 10억(1초 미만 × 0.5초 — int64 안)
	b.balance += elapsed/time.Second*holdRefill + scaled/time.Second
	b.carry = scaled % time.Second
}

// clamp 는 잔액을 holdBurst 로 자른다. 한도에 닿으면 넘긴 몫도 버린다. b.mu 를 잡은 채로 부른다.
func (b *holdBudget) clamp() {
	if b.balance >= holdBurst {
		b.balance, b.carry = holdBurst, 0
	}
}
