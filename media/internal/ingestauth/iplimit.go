package ingestauth

import (
	"log/slog"
	"net/netip"
	"sync"
	"time"
)

// IPLimit 은 publish 송출자 IP별 시도 한도다(트랙 B 설계 2-1). 영값이면 꺼짐이다.
type IPLimit struct {
	Burst    int
	Interval time.Duration
}

// ipEventKind 는 한도기가 돌려주는 사건의 갈래다.
type ipEventKind int

const (
	ipLimited ipEventKind = iota
	ipReleased
	ipReleasedSummary
	ipTableFull
	ipTableRecovered
	ipUnkeyed
)

// ipEvent 는 한도기의 사건 하나다.
type ipEvent struct {
	kind       ipEventKind
	key        netip.Prefix
	ipKind     string
	denied     int
	limitedFor time.Duration
	count      int
}

// ipEntry 는 키 하나의 상태다.
type ipEntry struct {
	tat       time.Time
	limitedAt time.Time
	denied    int
}

// ipLimiter 는 IP별 한도기다.
type ipLimiter struct {
	burst    int
	interval time.Duration
	now      func() time.Time
	limit    int

	mu        sync.Mutex
	entries   map[netip.Prefix]ipEntry
	lastNow   time.Time
	lastSweep time.Time
	full      bool
	unkeyed   bool
	sweeps    int
}

const ipLimitMaxEntries = 16384

func newIPLimiter(cfg IPLimit, now func() time.Time) *ipLimiter {
	return &ipLimiter{burst: cfg.Burst, interval: cfg.Interval, now: now, limit: ipLimitMaxEntries, entries: make(map[netip.Prefix]ipEntry)}
}

func (l *ipLimiter) peek(addr netip.Addr) (ok bool, evs []ipEvent) { return true, nil }

func (l *ipLimiter) take(addr netip.Addr) []ipEvent { return nil }

func (l *ipLimiter) logEvents(lg *slog.Logger, evs []ipEvent) {}

func ipKey(a netip.Addr) (netip.Prefix, bool) { return netip.Prefix{}, false }

func ipKindOf(a netip.Addr) string { return "" }

const (
	IPLimitBurstMin    = 1
	IPLimitBurstMax    = 1000
	IPLimitIntervalMin = 100 * time.Millisecond
	IPLimitIntervalMax = 10 * time.Minute
)

func (c IPLimit) validate() error { return nil }
