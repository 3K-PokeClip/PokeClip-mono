package ingestauth

import (
	"fmt"
	"log/slog"
	"net/netip"
	"sync"
	"time"
)

// IPLimit 의 범위다. 곱 Burst × Interval 의 최댓값(1000 × 10분 ≈ 6×10¹⁴ns)은 int64 시각 계산을 넘치지 않는다.
const (
	IPLimitBurstMin    = 1
	IPLimitBurstMax    = 1000
	IPLimitIntervalMin = 100 * time.Millisecond
	IPLimitIntervalMax = 10 * time.Minute
)

const (
	ipLimitMaxEntries   = 16384       // 기록하는 키의 상한. 차면 새 키는 기록하지 않고 예산에만 맡긴다(fail-open)
	ipLimitSweepGap     = time.Second // 상한에서 넣을 때 만료 항목을 회수하는 간격의 하한 — 잠금 안 O(n) 정리를 묶는다
	ipLimitReleaseLines = 32          // 정리 한 번에 키마다 내는 released 사건의 상한. 넘는 몫은 요약 사건 하나로
	ipLimitV6Bits       = 64          // IPv6 키의 접두 길이 — 서브넷 하나의 표준 크기
)

// IPLimit 은 publish 송출자 IP별 resolve 시도 한도다(트랙 B 설계 2-1 · 2-3). Burst 번은 몰아 쓸 수 있고, 그 뒤는
// Interval 에 하나씩 다시 찬다. 영값이면 꺼짐이다 — 켜는 것은 부르는 쪽(cmd 의 기본값)이 맡는다.
type IPLimit struct {
	Burst    int
	Interval time.Duration
}

// enabled 는 한도가 켜졌는지다.
func (c IPLimit) enabled() bool {
	return c != IPLimit{}
}

// validate 는 c 가 꺼짐(영값)이거나 두 값이 모두 범위 안인지 본다.
func (c IPLimit) validate() error {
	if !c.enabled() {
		return nil
	}
	if c.Burst < IPLimitBurstMin || c.Burst > IPLimitBurstMax {
		return fmt.Errorf("ingestauth: IPLimit.Burst 는 %d 이상 %d 이하여야 한다 — %d", IPLimitBurstMin, IPLimitBurstMax, c.Burst)
	}
	if c.Interval < IPLimitIntervalMin || c.Interval > IPLimitIntervalMax {
		return fmt.Errorf("ingestauth: IPLimit.Interval 은 %v 이상 %v 이하여야 한다 — %v", IPLimitIntervalMin, IPLimitIntervalMax, c.Interval)
	}
	return nil
}

// ipEventKind 는 한도기가 돌려주는 사건의 갈래다. 처리기가 잠금 밖에서 로그 한 줄로 낸다(logEvents).
type ipEventKind int

const (
	ipLimited         ipEventKind = iota // 에피소드 시작 — 그 키가 처음 거절됐다
	ipReleased                           // 에피소드 끝 — 통이 다시 다 찼다(peek 또는 정리가 봤다)
	ipReleasedSummary                    // 정리 한 번에서 ipLimitReleaseLines 를 넘은 released 의 요약
	ipTableFull                          // 상한이 차서 새 키를 기록하지 못했다(처음 한 번)
	ipTableRecovered                     // 상한 밑으로 내려와 새 키를 다시 기록했다
	ipUnkeyed                            // 키를 만들 수 없는 주소(영값 · 미지정)가 왔다(처음 한 번)
)

// ipEvent 는 한도기의 사건 하나다. 토큰 · passphrase · 연결 id 는 담지 않는다 — 키와 수뿐이다.
type ipEvent struct {
	kind       ipEventKind
	key        netip.Prefix
	ipKind     string        // ipLimited 일 때 — ipKindOf
	denied     int           // ipReleased 일 때 그 에피소드의 거절 수, ipReleasedSummary 일 때 합
	limitedFor time.Duration // ipReleased 일 때 — 에피소드 시작부터 끝을 본 시각까지
	count      int           // ipReleasedSummary 일 때 — 요약한 키 수
}

// ipEntry 는 키 하나의 GCRA 상태다.
type ipEntry struct {
	tat       time.Time // 다음 허용의 이론 시각. 이것이 지금 이하면 통이 다 찼다
	limitedAt time.Time // 에피소드 시작 시각. 영값이면 에피소드가 아니다
	denied    int       // 에피소드 동안의 거절 수
}

// ipLimiter 는 송출자 IP별 GCRA 한도기다(설계 2-3). 키(IPv4 /32 · IPv6 /64)마다 다음 허용의 이론 시각 하나를 쥔다.
// 허용 = 지금 ≥ tat − (Burst−1)·Interval, 차감 = tat ← max(tat, 지금) + Interval. 검사(peek)와 차감(take)이
// 나뉘어 있어 그 사이에 겹친 요청은 모두 차감되고, 넘친 몫은 tat 를 더 미래로 밀어 빚으로 갚는다 — 그 빚은 어떤
// 자르기로도 지우지 않는다.
//
// 메서드는 모두 잠금 하나 안에서 끝나고 로그를 내지 않는다 — 사건 값을 돌려주고 부르는 쪽이 잠금 밖에서 낸다. 시각은
// 주입 시계로만 재고, 시험 시계가 뒤로 가면 남은 대기를 보존한 채 당긴다(readClock). 처리기 안에만 있고 내보내지
// 않는다. 다시 뜨면 비어서 시작한다. 여러 고루틴이 함께 불러도 된다.
type ipLimiter struct {
	burst    int
	interval time.Duration
	now      func() time.Time
	limit    int // 기록하는 키의 상한 — ipLimitMaxEntries. 시험이 작게 준다

	mu        sync.Mutex // 아래를 지킨다
	entries   map[netip.Prefix]ipEntry
	lastNow   time.Time // 마지막으로 읽은 지금
	lastSweep time.Time // 마지막 정리 시각. 영값이면 아직 없다
	full      bool      // 상한이 차서 새 키를 기록하지 못하는 중이다
	unkeyed   bool      // ipUnkeyed 사건을 이미 냈다
	sweeps    int       // 정리 횟수 — 시험이 1초 게이트를 본다
}

// newIPLimiter 는 cfg 의 빈 한도기다. cfg 가 꺼짐이면 nil 이다 — 부르는 쪽이 nil 을 보고 건너뛴다.
func newIPLimiter(cfg IPLimit, now func() time.Time) *ipLimiter {
	if !cfg.enabled() {
		return nil
	}
	return &ipLimiter{
		burst:    cfg.Burst,
		interval: cfg.Interval,
		now:      now,
		limit:    ipLimitMaxEntries,
		entries:  make(map[netip.Prefix]ipEntry),
	}
}

// peek 은 addr 이 지금 resolve 를 불러도 되는지 본다. 항목을 만들지 않는다 — 있는 항목의 에피소드 칸만 고친다.
// 통이 다 찬 항목은 지우고, 에피소드 중이었으면 ipReleased 를 낸다. 거절이면 거절 수를 세고, 에피소드 시작이면
// ipLimited 를 낸다. 키를 만들 수 없는 주소는 늘 참이다.
func (l *ipLimiter) peek(addr netip.Addr) (ok bool, evs []ipEvent) {
	key, keyed := ipKey(addr)
	l.mu.Lock()
	defer l.mu.Unlock()
	now, evs := l.readClock()
	if !keyed {
		if !l.unkeyed {
			l.unkeyed = true
			evs = append(evs, ipEvent{kind: ipUnkeyed})
		}
		return true, evs
	}
	e, found := l.entries[key]
	switch {
	case !found:
		return true, evs
	case !e.tat.After(now):
		delete(l.entries, key)
		if !e.limitedAt.IsZero() {
			evs = append(evs, e.released(key, now))
		}
		return true, evs
	case !now.Before(e.tat.Add(-time.Duration(l.burst-1) * l.interval)):
		return true, evs
	}
	e.denied++
	if e.limitedAt.IsZero() {
		e.limitedAt = now
		evs = append(evs, ipEvent{kind: ipLimited, key: key, ipKind: ipKindOf(addr)})
	}
	l.entries[key] = e
	return false, evs
}

// take 는 addr 의 시도 하나를 센다 — 결정 없이 늘 기록한다(tat 를 민다). 새 키인데 상한이면 만료 항목을
// 회수하고(ipLimitSweepGap 에 한 번까지), 그래도 차면 기록하지 않는다 — 처음이면 ipTableFull 이다.
func (l *ipLimiter) take(addr netip.Addr) []ipEvent {
	key, keyed := ipKey(addr)
	if !keyed {
		return nil
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	now, evs := l.readClock()
	e, found := l.entries[key]
	if !found {
		if len(l.entries) >= l.limit {
			evs = append(evs, l.sweepGated(now)...)
		}
		if len(l.entries) >= l.limit {
			if !l.full {
				l.full = true
				evs = append(evs, ipEvent{kind: ipTableFull})
			}
			return evs // 이 키는 기록 없이 예산만 탄다
		}
		if l.full {
			l.full = false
			evs = append(evs, ipEvent{kind: ipTableRecovered})
		}
	}
	if e.tat.Before(now) {
		e.tat = now
	}
	e.tat = e.tat.Add(l.interval)
	l.entries[key] = e
	return evs
}

// sweepGated 는 마지막 정리에서 ipLimitSweepGap 이 지났을 때만 만료 항목을 회수한다. l.mu 를 잡은 채로 부른다.
func (l *ipLimiter) sweepGated(now time.Time) []ipEvent {
	if !l.lastSweep.IsZero() && now.Sub(l.lastSweep) < ipLimitSweepGap {
		return nil
	}
	l.lastSweep = now
	l.sweeps++
	return l.sweepExpired(now)
}

// sweepExpired 는 at 에 통이 다 찬(tat ≤ at) 항목을 에피소드 유무와 무관하게 모두 지운다 — 잠긴 뒤 돌아오지 않는
// IP 가 상한을 영구히 쥐지 않게. 에피소드였던 항목은 ipReleased 를 ipLimitReleaseLines 개까지 내고, 넘는 몫은
// ipReleasedSummary 하나로 묶는다. l.mu 를 잡은 채로 부른다.
func (l *ipLimiter) sweepExpired(at time.Time) []ipEvent {
	var evs []ipEvent
	summary := ipEvent{kind: ipReleasedSummary}
	for key, e := range l.entries {
		if e.tat.After(at) {
			continue
		}
		delete(l.entries, key)
		switch {
		case e.limitedAt.IsZero():
		case len(evs) < ipLimitReleaseLines:
			evs = append(evs, e.released(key, at))
		default:
			summary.count++
			summary.denied += e.denied
		}
	}
	if summary.count > 0 {
		evs = append(evs, summary)
	}
	return evs
}

// readClock 은 지금을 읽는다. 시험 시계가 마지막으로 읽은 지금보다 뒤로 갔을 때만 보정한다 — 그때 이미 만료된
// 항목을 먼저 회수하고(되살아나지 않게), 남은 항목의 tat 는 되감긴 만큼 당겨 남은 대기(겹침 빚 포함)를 그대로
// 두며, 에피소드 시작 · 마지막 정리 시각은 지금보다 늦지 않게 당긴다. 정리 시각을 당기지 않으면 되감긴 시간만큼 정리
// 게이트가 닫혀 상한에서 새 키가 계속 기록 밖에 선다. 후퇴가 없으면 아무것도 고치지 않는다. 돌려주는 사건은 회수한
// 에피소드의 released 다. l.mu 를 잡은 채로 부른다.
func (l *ipLimiter) readClock() (time.Time, []ipEvent) {
	now := l.now()
	var evs []ipEvent
	if now.Before(l.lastNow) {
		evs = l.sweepExpired(l.lastNow)
		back := l.lastNow.Sub(now)
		for key, e := range l.entries {
			e.tat = e.tat.Add(-back)
			if !e.limitedAt.IsZero() {
				e.limitedAt = earlier(e.limitedAt, now)
			}
			l.entries[key] = e
		}
		l.lastSweep = earlier(l.lastSweep, now)
	}
	l.lastNow = now
	return now, evs
}

// released 는 key 의 에피소드가 at 에 끝났다는 사건이다.
func (e ipEntry) released(key netip.Prefix, at time.Time) ipEvent {
	return ipEvent{kind: ipReleased, key: key, denied: e.denied, limitedFor: at.Sub(e.limitedAt)}
}

// logEvents 는 evs 를 한 줄씩 낸다(WARN). 한도기의 잠금 밖에서 부른다 — 로그 쓰기 대기가 다른 요청의 판정을 막지
// 않게. 키 문자열(IPv4 는 주소, IPv6 는 /64 접두)과 수만 싣는다.
func (l *ipLimiter) logEvents(lg *slog.Logger, evs []ipEvent) {
	for _, e := range evs {
		switch e.kind {
		case ipLimited:
			lg.Warn("ingestauth_ip_limited", "ip", keyString(e.key), "ip_kind", e.ipKind,
				"burst", l.burst, "interval_ms", l.interval.Milliseconds())
		case ipReleased:
			lg.Warn("ingestauth_ip_limit_released", "ip", keyString(e.key), "denied", e.denied,
				"limited_for_ms", e.limitedFor.Milliseconds())
		case ipReleasedSummary:
			lg.Warn("ingestauth_ip_limit_released_summary", "count", e.count, "denied_total", e.denied)
		case ipTableFull:
			lg.Warn("ingestauth_ip_limit_full", "max_entries", l.limit)
		case ipTableRecovered:
			lg.Warn("ingestauth_ip_limit_recovered", "max_entries", l.limit)
		case ipUnkeyed:
			lg.Warn("ingestauth_ip_limit_unkeyed", "ip_kind", ipKindOther)
		}
	}
}

// ipKey 는 a 의 한도 키다 — IPv4 는 /32, IPv6 는 /64. zone 은 떼고 IPv4-mapped 는 IPv4 로 푼다. 영값 · 미지정
// 주소면 ok 가 거짓이다(한도를 걸지 않는다 — readRequest 가 IP 로 해석된 값만 넘기므로 오는 경로는 추론상 없다).
func ipKey(a netip.Addr) (key netip.Prefix, ok bool) {
	a = a.WithZone("").Unmap()
	if !a.IsValid() || a.IsUnspecified() {
		return netip.Prefix{}, false
	}
	bits := a.BitLen()
	if a.Is6() {
		bits = ipLimitV6Bits
	}
	key, err := a.Prefix(bits)
	if err != nil {
		// 유효한 주소에 그 주소 길이 이하의 bits 라 오지 않는다.
		return netip.Prefix{}, false
	}
	return key, true
}

// keyString 은 키의 로그 문자열이다 — IPv4 는 주소만, IPv6 는 접두(예: 2001:db8:1:2::/64).
func keyString(key netip.Prefix) string {
	if key.Addr().Is4() {
		return key.Addr().String()
	}
	return key.String()
}

// ipKindOf 의 값이다 — 공인 송출자가 private 로 찍히면 docker · NLB 가 출발지를 바꾼 것이다(설계 1-2 P1).
const (
	ipKindPublic   = "public"
	ipKindPrivate  = "private"
	ipKindLoopback = "loopback"
	ipKindOther    = "other"
)

// ipKindOf 는 a 의 부류다. 사설을 전역 유니캐스트보다 먼저 본다 — IsGlobalUnicast 는 사설 주소에도 참이다.
func ipKindOf(a netip.Addr) string {
	a = a.WithZone("").Unmap()
	switch {
	case a.IsLoopback():
		return ipKindLoopback
	case a.IsPrivate():
		return ipKindPrivate
	case a.IsGlobalUnicast():
		return ipKindPublic
	}
	return ipKindOther
}
