package ingestauth

import (
	"math/rand/v2"
	"net/netip"
	"sync"
	"testing"
	"time"
)

// IP별 한도기의 단위 시험이다(트랙 B 설계 2-3 · 계획 4-2 의 U1–U12). 수치는 운영 기본값(B = 30 · r = 3초)이고, 시각은
// manualClock 으로 옮긴다 — 실제로 기다리지 않는다. 주소는 문서용 대역(RFC 5737 · RFC 3849)이다.
const (
	testIPBurst    = 30
	testIPInterval = 3 * time.Second
)

var (
	testIPA = netip.MustParseAddr("203.0.113.5")
	testIPB = netip.MustParseAddr("203.0.113.6")
)

// newTestIPLimiter 는 운영 기본값의 한도기다. limit 이 0 보다 크면 상한을 그 값으로 줄인다.
func newTestIPLimiter(clock *manualClock, limit int) *ipLimiter {
	l := newIPLimiter(IPLimit{Burst: testIPBurst, Interval: testIPInterval}, clock.now)
	if limit > 0 {
		l.limit = limit
	}
	return l
}

// testIPAddr 는 i 번째 합성 IPv4 주소다(198.18.0.0/15 — RFC 2544 벤치 대역, 키가 서로 다르다).
func testIPAddr(i int) netip.Addr {
	return netip.AddrFrom4([4]byte{198, byte(18 + i>>16&1), byte(i >> 8), byte(i)})
}

// attempt 는 판정 순서 그대로 peek 하고 허용이면 take 한다. 허용 여부와 두 단계의 사건을 돌려준다.
func attempt(l *ipLimiter, addr netip.Addr) (bool, []ipEvent) {
	ok, evs := l.peek(addr)
	if ok {
		evs = append(evs, l.take(addr)...)
	}
	return ok, evs
}

// takeN 은 addr 로 n 번 take 한다(peek 없이 — 겹친 요청이 이미 검사를 지난 꼴).
func takeN(l *ipLimiter, addr netip.Addr, n int) {
	for range n {
		l.take(addr)
	}
}

// eventKinds 는 evs 의 갈래별 수다.
func eventKinds(evs []ipEvent) map[ipEventKind]int {
	n := make(map[ipEventKind]int)
	for _, e := range evs {
		n[e.kind]++
	}
	return n
}

// entriesLen 은 한도기 맵의 항목 수다.
func entriesLen(l *ipLimiter) int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.entries)
}

// hasEntry 는 addr 의 키가 맵에 있는지다.
func hasEntry(l *ipLimiter, addr netip.Addr) bool {
	key, _ := ipKey(addr)
	l.mu.Lock()
	defer l.mu.Unlock()
	_, ok := l.entries[key]
	return ok
}

// sweepCount 는 지금까지 돈 정리 횟수다.
func sweepCount(l *ipLimiter) int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.sweeps
}

// U1 — 같은 시각에 처음 B 번은 허용, B+1 번째는 거절이다(B5 경계).
func TestIPLimiterAllowsBurstThenDenies(t *testing.T) {
	clock := newManualClock()
	l := newTestIPLimiter(clock, 0)
	for i := 1; i <= testIPBurst; i++ {
		if ok, _ := attempt(l, testIPA); !ok {
			t.Fatalf("attempt %d at t=0 = denied, want allowed (burst %d)", i, testIPBurst)
		}
	}
	if ok, _ := attempt(l, testIPA); ok {
		t.Errorf("attempt %d at t=0 = allowed, want denied", testIPBurst+1)
	}
}

// U2 — 통이 빈 뒤 다음 허용은 정확히 r 뒤다. r − 1ns 는 거절이다(B5 경계).
func TestIPLimiterRefillsAtInterval(t *testing.T) {
	clock := newManualClock()
	l := newTestIPLimiter(clock, 0)
	takeN(l, testIPA, testIPBurst)

	clock.moveTo(testIPInterval - time.Nanosecond)
	if ok, _ := l.peek(testIPA); ok {
		t.Errorf("peek at r−1ns after %d takes = allowed, want denied", testIPBurst)
	}
	clock.moveTo(testIPInterval)
	if ok, _ := l.peek(testIPA); !ok {
		t.Errorf("peek at r after %d takes = denied, want allowed", testIPBurst)
	}
}

// U3 — 키는 서로 독립이다.
func TestIPLimiterKeysAreIndependent(t *testing.T) {
	clock := newManualClock()
	l := newTestIPLimiter(clock, 0)
	takeN(l, testIPA, testIPBurst)
	if ok, _ := l.peek(testIPA); ok {
		t.Fatalf("Setup: peek(A) after %d takes = allowed, want denied", testIPBurst)
	}
	if ok, _ := l.peek(testIPB); !ok {
		t.Errorf("peek(B) while A is limited = denied, want allowed")
	}
}

// U4 — IPv4-mapped 는 IPv4 와, zone 붙은 IPv6 는 zone 없는 같은 /64 와, 같은 /64 의 다른 주소는 서로 같은 키다. 다른
// /64 는 다른 키다(B15 · 설계 2-1 의 7).
func TestIPKeyNormalizesAddresses(t *testing.T) {
	for _, tc := range []struct {
		name string
		a, b string
		same bool
	}{
		{name: "mapped_IPv4", a: "::ffff:203.0.113.5", b: "203.0.113.5", same: true},
		{name: "zone_IPv6", a: "2001:db8:1:2::5%eth0", b: "2001:db8:1:2::5", same: true},
		{name: "zone_mapped", a: "::ffff:203.0.113.5%eth0", b: "203.0.113.5", same: true},
		{name: "same_64", a: "2001:db8:1:2::5", b: "2001:db8:1:2:ffff:ffff:ffff:ffff", same: true},
		{name: "other_64", a: "2001:db8:1:2::5", b: "2001:db8:1:3::5", same: false},
		{name: "other_IPv4", a: "203.0.113.5", b: "203.0.113.6", same: false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			ka, oka := ipKey(netip.MustParseAddr(tc.a))
			kb, okb := ipKey(netip.MustParseAddr(tc.b))
			if !oka || !okb {
				t.Fatalf("ipKey(%s) ok = %v, ipKey(%s) ok = %v, want both true", tc.a, oka, tc.b, okb)
			}
			if (ka == kb) != tc.same {
				t.Errorf("ipKey(%s) = %v, ipKey(%s) = %v, same = %v, want %v", tc.a, ka, tc.b, kb, ka == kb, tc.same)
			}
		})
	}
	if k, _ := ipKey(netip.MustParseAddr("203.0.113.5")); k.Bits() != 32 {
		t.Errorf("ipKey(IPv4).Bits() = %d, want 32", k.Bits())
	}
	if k, _ := ipKey(netip.MustParseAddr("2001:db8:1:2::5")); k.Bits() != 64 {
		t.Errorf("ipKey(IPv6).Bits() = %d, want 64", k.Bits())
	}
}

// U4 의 처리 쪽 — 같은 /64 의 주소 1만 개는 항목 하나이고 함께 잠긴다(B7 — /64 마스크).
func TestIPLimiterGroupsIPv6By64(t *testing.T) {
	clock := newManualClock()
	l := newTestIPLimiter(clock, 0)
	base := netip.MustParseAddr("2001:db8:1:2::").As16()
	for i := range 10000 {
		a := base
		a[14], a[15] = byte(i>>8), byte(i)
		l.take(netip.AddrFrom16(a))
	}
	if got := entriesLen(l); got != 1 {
		t.Errorf("entries after 10000 takes in one /64 = %d, want 1", got)
	}
	if ok, _ := l.peek(netip.MustParseAddr("2001:db8:1:2::abcd")); ok {
		t.Errorf("peek(other address in the same /64) = allowed, want denied")
	}
}

// U5 — 영값 · 미지정 주소는 한도를 걸지 않고 항목을 만들지 않는다. 처음 한 번만 unkeyed 사건이다(설계 2-3 키 함수).
func TestIPLimiterIgnoresUnkeyedAddresses(t *testing.T) {
	clock := newManualClock()
	l := newTestIPLimiter(clock, 0)
	var unkeyed int
	for _, a := range []netip.Addr{{}, netip.IPv4Unspecified(), netip.IPv6Unspecified()} {
		for range 2 * testIPBurst {
			ok, evs := attempt(l, a)
			if !ok {
				t.Fatalf("attempt(%v) = denied, want allowed (unkeyed)", a)
			}
			unkeyed += eventKinds(evs)[ipUnkeyed]
		}
	}
	if got := entriesLen(l); got != 0 {
		t.Errorf("entries after unkeyed attempts = %d, want 0", got)
	}
	if unkeyed != 1 {
		t.Errorf("unkeyed events = %d, want 1 (first time only)", unkeyed)
	}
}

// U6 — 시험 시계를 1시간 되감아도 영구 차단이 생기지 않는다. 남은 대기는 되감기 전과 같고(겹침 빚 포함), 되감기
// 전에 만료된 항목은 회수돼 다시 잠기지 않는다(B4 · 설계 2-3 시계 불변식).
func TestIPLimiterClockRewindKeepsRemainingWait(t *testing.T) {
	clock := newManualClock()
	l := newTestIPLimiter(clock, 0)
	takeN(l, testIPA, 40) // TAT = 120초 → 다음 허용 = 120 − 29×3 = 33초
	takeN(l, testIPB, 1)  // TAT = 3초
	clock.moveTo(10 * time.Second)
	if ok, _ := l.peek(testIPA); ok {
		t.Fatalf("Setup: peek(A) at 10s = allowed, want denied (next allowed at 33s)")
	}

	clock.moveTo(10*time.Second - time.Hour)
	rewound := clock.now()
	if ok, _ := l.peek(testIPB); !ok {
		t.Errorf("peek(B) after rewinding 1h = denied, want allowed (B had expired before the rewind)")
	}
	clock.moveTo(10*time.Second - time.Hour + 23*time.Second - time.Nanosecond)
	if ok, _ := l.peek(testIPA); ok {
		t.Errorf("peek(A) at rewound+23s−1ns = allowed, want denied (remaining wait 23s kept)")
	}
	clock.moveTo(10*time.Second - time.Hour + 23*time.Second)
	if ok, _ := l.peek(testIPA); !ok {
		t.Errorf("peek(A) at rewound+23s = denied, want allowed (rewound at %v)", rewound.Format(time.TimeOnly))
	}
}

// U6 의 상한 행 — 상한이 찬 채로 되감아도 정리 게이트가 되감긴 시간만큼 닫히지 않는다. 항목이 만료될 만큼 민 뒤 새
// 키 take 가 기록된다(설계 2-3 readClock ③ · 변이 m4c).
func TestIPLimiterClockRewindReopensSweepGate(t *testing.T) {
	clock := newManualClock()
	l := newTestIPLimiter(clock, 4)
	for i := range 4 {
		l.take(testIPAddr(i)) // TAT = 3초
	}
	l.take(testIPAddr(100)) // 상한 — 정리(지운 것 없음) · 기록 안 함
	if hasEntry(l, testIPAddr(100)) {
		t.Fatalf("Setup: a new key was recorded on a full table")
	}
	clock.moveTo(500 * time.Millisecond)
	clock.moveTo(500*time.Millisecond - time.Hour)
	l.peek(testIPA) // 되감기를 읽힌다
	clock.moveTo(500*time.Millisecond - time.Hour + 5*time.Second)
	l.take(testIPAddr(101))
	if !hasEntry(l, testIPAddr(101)) {
		t.Errorf("new key after rewinding a full table and waiting 5s was not recorded, want recorded (sweep gate reopened)")
	}
}

// U7 — 상한이 차면 새 키는 기록하지 않고 full 사건은 한 번이다. 만료 뒤 넣을 때 정리가 만료 항목만 회수하고
// recovered 사건이 나온다(B7 · 설계 2-1 의 9).
func TestIPLimiterTableLimit(t *testing.T) {
	clock := newManualClock()
	l := newTestIPLimiter(clock, 4)
	for i := range 3 {
		l.take(testIPAddr(i)) // TAT = 3초
	}
	takeN(l, testIPAddr(3), 5) // TAT = 15초 — 3초 뒤에도 남는다

	full := eventKinds(l.take(testIPAddr(10)))[ipTableFull]
	full += eventKinds(l.take(testIPAddr(11)))[ipTableFull]
	if full != 1 {
		t.Errorf("full events for two new keys on a full table = %d, want 1", full)
	}
	if got := entriesLen(l); got != 4 {
		t.Errorf("entries on a full table = %d, want 4 (the limit)", got)
	}

	clock.moveTo(3 * time.Second)
	evs := l.take(testIPAddr(12))
	if !hasEntry(l, testIPAddr(12)) {
		t.Errorf("new key after the others expired was not recorded")
	}
	if !hasEntry(l, testIPAddr(3)) {
		t.Errorf("an unexpired entry was swept, want kept")
	}
	if got := eventKinds(evs)[ipTableRecovered]; got != 1 {
		t.Errorf("recovered events = %d, want 1", got)
	}
}

// U7 의 대량 행 — 10만 키를 넣어도 맵은 상한을 넘지 않는다(B7).
func TestIPLimiterTableNeverExceedsLimit(t *testing.T) {
	clock := newManualClock()
	l := newTestIPLimiter(clock, 0)
	for i := range 100000 {
		l.take(testIPAddr(i))
	}
	if got := entriesLen(l); got > ipLimitMaxEntries {
		t.Errorf("entries after 100000 keys = %d, want ≤ %d", got, ipLimitMaxEntries)
	}
}

// U7b — 잠긴 뒤 돌아오지 않는 IP 도 만료되면 회수된다. 상한까지 에피소드 항목으로 채우고 TAT 너머로 시계를 밀면, 그
// 키들의 peek 없이 새 키 take 가 기록되고 released 32줄 + 요약 한 줄 + recovered 가 나온다(B26 · 변이 m15).
func TestIPLimiterSweepsExpiredEpisodesWithoutRevisit(t *testing.T) {
	const keys = 40
	clock := newManualClock()
	l := newTestIPLimiter(clock, keys)
	for i := range keys {
		takeN(l, testIPAddr(i), testIPBurst+1) // TAT = 93초
		if ok, _ := l.peek(testIPAddr(i)); ok {
			t.Fatalf("Setup: peek(key %d) after %d takes = allowed, want denied", i, testIPBurst+1)
		}
	}
	l.take(testIPAddr(1000))
	if hasEntry(l, testIPAddr(1000)) {
		t.Fatalf("Setup: a new key was recorded on a table full of episodes")
	}

	clock.moveTo(93 * time.Second)
	evs := l.take(testIPAddr(1001))
	if !hasEntry(l, testIPAddr(1001)) {
		t.Errorf("new key after every episode expired was not recorded, want recorded")
	}
	kinds := eventKinds(evs)
	if kinds[ipReleased] != 32 || kinds[ipReleasedSummary] != 1 || kinds[ipTableRecovered] != 1 {
		t.Errorf("events = %v, want 32 released, 1 summary, 1 recovered", kinds)
	}
	for _, e := range evs {
		if e.kind == ipReleasedSummary && (e.count != keys-32 || e.denied != keys-32) {
			t.Errorf("summary count = %d, denied = %d, want %d and %d", e.count, e.denied, keys-32, keys-32)
		}
	}
}

// U8 — 상한에서 넣을 때의 정리는 1초에 한 번까지다(B8).
func TestIPLimiterSweepGate(t *testing.T) {
	clock := newManualClock()
	l := newTestIPLimiter(clock, 4)
	for i := range 4 {
		takeN(l, testIPAddr(i), 100) // 만료되지 않는다
	}
	for i := range 10000 {
		l.take(testIPAddr(100 + i))
	}
	if got := sweepCount(l); got != 1 {
		t.Errorf("sweeps for 10000 inserts at one instant = %d, want 1", got)
	}
	clock.moveTo(time.Second - time.Nanosecond)
	l.take(testIPAddr(20000))
	if got := sweepCount(l); got != 1 {
		t.Errorf("sweeps at 1s−1ns = %d, want 1", got)
	}
	clock.moveTo(time.Second)
	l.take(testIPAddr(20001))
	if got := sweepCount(l); got != 2 {
		t.Errorf("sweeps at 1s = %d, want 2", got)
	}
}

// U9 — peek 는 항목을 만들지 않는다(설계 2-1 의 2 · 9).
func TestIPLimiterPeekDoesNotCreateEntries(t *testing.T) {
	clock := newManualClock()
	l := newTestIPLimiter(clock, 0)
	for i := range 10000 {
		l.peek(testIPAddr(i))
	}
	if got := entriesLen(l); got != 0 {
		t.Errorf("entries after 10000 peeks = %d, want 0", got)
	}
}

// U10 — 겹친 take 의 빚은 지워지지 않는다. 29회 차감한 통에서 16개가 함께 peek 를 지나 모두 take 하면 TAT 는 45×3초로
// 밀리고, 다음 허용은 45×3 − 29×3 = 48초다(B2 · 변이 m2 · m4a).
func TestIPLimiterOverlappingTakesKeepDebt(t *testing.T) {
	const overlap = 16
	clock := newManualClock()
	l := newTestIPLimiter(clock, 0)
	takeN(l, testIPA, testIPBurst-1)

	var peeked, taken sync.WaitGroup
	release := make(chan struct{})
	allowed := make(chan bool, overlap)
	for range overlap {
		peeked.Add(1)
		taken.Go(func() {
			ok, _ := l.peek(testIPA)
			allowed <- ok
			peeked.Done()
			<-release
			if ok {
				l.take(testIPA)
			}
		})
	}
	peeked.Wait()
	close(release)
	taken.Wait()
	close(allowed)
	for ok := range allowed {
		if !ok {
			t.Fatalf("Setup: an overlapping peek on a bucket with one slot left = denied, want allowed")
		}
	}

	clock.moveTo(48*time.Second - time.Millisecond)
	if ok, _ := l.peek(testIPA); ok {
		t.Errorf("peek at 47.999s after 29 + %d overlapping takes = allowed, want denied (debt kept)", overlap)
	}
	clock.moveTo(48 * time.Second)
	if ok, _ := l.peek(testIPA); !ok {
		t.Errorf("peek at 48s after 29 + %d overlapping takes = denied, want allowed", overlap)
	}
}

// U11 — 여러 고루틴이 같은 · 다른 키로 함께 peek · take 해도 된다(B1 — -race 로 본다).
func TestIPLimiterConcurrentUse(t *testing.T) {
	clock := newManualClock()
	l := newTestIPLimiter(clock, 64)
	var wg sync.WaitGroup
	for g := range 64 {
		wg.Go(func() {
			r := rand.New(rand.NewPCG(uint64(g), 1))
			for range 500 {
				addr := testIPAddr(r.IntN(128))
				if r.IntN(2) == 0 {
					attempt(l, addr)
				} else {
					l.peek(addr)
				}
				if r.IntN(50) == 0 {
					clock.advance(time.Duration(r.IntN(2000)) * time.Millisecond)
				}
			}
		})
	}
	wg.Wait()
	if got := entriesLen(l); got > 64 {
		t.Errorf("entries after concurrent use = %d, want ≤ 64", got)
	}
}

// U12 — 에피소드 사건. 처음 거절에 limited 한 번 · 통이 다 차기 전의 허용은 released 를 내지 않고 에피소드가 이어진다 ·
// 통이 다 찬 뒤 첫 peek 에 released(denied · limited_for)다(B3 · 설계 용어 「에피소드」).
func TestIPLimiterEpisodeEvents(t *testing.T) {
	clock := newManualClock()
	l := newTestIPLimiter(clock, 0)
	takeN(l, testIPA, testIPBurst) // TAT = 90초

	var all []ipEvent
	step := func(at time.Duration, wantOK bool) {
		t.Helper()
		clock.moveTo(at)
		ok, evs := attempt(l, testIPA)
		if ok != wantOK {
			t.Fatalf("attempt at %v = %v, want %v", at, ok, wantOK)
		}
		all = append(all, evs...)
	}
	step(0, false)             // limited
	step(time.Second, false)   // 같은 에피소드
	step(3*time.Second, true)  // 허용 — 통이 다 차지 않았다(TAT 93초)
	step(4*time.Second, false) // 같은 에피소드
	if got := eventKinds(all); got[ipLimited] != 1 || got[ipReleased] != 0 {
		t.Fatalf("events before the bucket refilled = %v, want 1 limited and 0 released", got)
	}
	if all[0].key != netip.PrefixFrom(testIPA, 32) || all[0].ipKind != "public" {
		t.Errorf("limited event key = %v, ip_kind = %q, want %v and public", all[0].key, all[0].ipKind, netip.PrefixFrom(testIPA, 32))
	}

	clock.moveTo(93 * time.Second)
	ok, evs := l.peek(testIPA)
	if !ok || len(evs) != 1 || evs[0].kind != ipReleased {
		t.Fatalf("peek after the bucket refilled = %v with %d events, want allowed with one released", ok, len(evs))
	}
	if evs[0].denied != 3 || evs[0].limitedFor != 93*time.Second {
		t.Errorf("released denied = %d, limited_for = %v, want 3 and 93s", evs[0].denied, evs[0].limitedFor)
	}
}

// ipKind 는 키 주소의 부류다 — 공인 송출자가 private 로 찍히면 출발지가 바뀐 것이다(P1 판별 신호).
func TestIPKindOf(t *testing.T) {
	for addr, want := range map[string]string{
		"203.0.113.5":  "public",
		"10.0.0.1":     "private",
		"fd00::1":      "private",
		"127.0.0.1":    "loopback",
		"::1":          "loopback",
		"169.254.0.1":  "other",
		"2001:db8::1":  "public",
		"192.168.1.10": "private",
	} {
		if got := ipKindOf(netip.MustParseAddr(addr)); got != want {
			t.Errorf("ipKindOf(%s) = %q, want %q", addr, got, want)
		}
	}
}

// validate 는 영값(꺼짐)과 범위 안 값만 받는다(B6 · 설계 2-3).
func TestIPLimitValidate(t *testing.T) {
	for _, tc := range []struct {
		cfg IPLimit
		ok  bool
	}{
		{IPLimit{}, true},
		{IPLimit{Burst: 30, Interval: 3 * time.Second}, true},
		{IPLimit{Burst: IPLimitBurstMin, Interval: IPLimitIntervalMin}, true},
		{IPLimit{Burst: IPLimitBurstMax, Interval: IPLimitIntervalMax}, true},
		{IPLimit{Burst: 0, Interval: 3 * time.Second}, false},
		{IPLimit{Burst: 30}, false},
		{IPLimit{Burst: IPLimitBurstMax + 1, Interval: 3 * time.Second}, false},
		{IPLimit{Burst: 30, Interval: IPLimitIntervalMin - time.Nanosecond}, false},
		{IPLimit{Burst: 30, Interval: IPLimitIntervalMax + time.Nanosecond}, false},
		{IPLimit{Burst: -1, Interval: 3 * time.Second}, false},
	} {
		if err := tc.cfg.validate(); (err == nil) != tc.ok {
			t.Errorf("IPLimit%+v.validate() = %v, want ok = %v", tc.cfg, err, tc.ok)
		}
	}
}
