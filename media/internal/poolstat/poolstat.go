// Package poolstat 는 pgxpool 풀이 연결을 버렸거나 맺지 못한 횟수를 로그로 낸다(POK-388).
//
// pgxpool 은 반납 때 깨진 연결(질의 도중 ctx 가 끝나 감시기가 닫은 연결 등)과 점검에 실패한 연결을
// 버리지만 그 수를 세는 계수기가 없다. 그래서 공개 계수기로 잔차를 낸다:
//
//	버린 연결 누적 = 새 연결 누적 − 지금 풀의 연결 − 수명 폐기 누적 − 유휴 폐기 누적
//
// 새 연결 누적은 연결 시도를 시작할 때 오르므로 맺지 못한 연결(연결 시도 실패)도 여기에 들어간다.
// 관측은 Stat 만 읽는다 — DB 에 아무것도 보내지 않고 연결도 세우지 않는다.
package poolstat

import (
	"context"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

const (
	// every 는 관측 주기다. pgxpool 기본 점검 주기(HealthCheckPeriod)와 같은 값이다.
	every = time.Minute
	// baselineRetries 는 기동 때 정지 스냅숏을 못 얻으면 더 읽어 보는 횟수다.
	baselineRetries = 3
	// baselineRetryDelay 는 그 재시도 간격이다.
	baselineRetryDelay = 100 * time.Millisecond

	gaugeName = "pg_pool_conn_discarded"
	statsName = "pg_pool_stats"
)

// snapshot 은 pgxpool.Stat 에서 필요한 값만 옮긴 것이다. pgxpool.Stat 은 필드가 비공개라 시험이 만들 수 없다.
type snapshot struct {
	total, idle, acquired, constructing, max int32
	newConns, lifetimeDestroys, idleDestroys int64
}

func snapshotOf(s *pgxpool.Stat) snapshot {
	return snapshot{
		total: s.TotalConns(), idle: s.IdleConns(), acquired: s.AcquiredConns(),
		constructing: s.ConstructingConns(), max: s.MaxConns(),
		newConns: s.NewConnsCount(), lifetimeDestroys: s.MaxLifetimeDestroyCount(), idleDestroys: s.MaxIdleDestroyCount(),
	}
}

// discarded 는 풀이 생긴 뒤 버렸거나 맺지 못한 연결의 누적 수(잔차)다.
func (s snapshot) discarded() int64 {
	return s.newConns - int64(s.total) - s.lifetimeDestroys - s.idleDestroys
}

// settled 는 연달아 읽은 두 스냅숏 사이에 연결 생성이 시작되지 않았고 b 에 생성 중인 연결이 없는지다.
//
// Stat 은 풀의 연결 목록을 먼저 읽고 새 연결 계수기를 뒤에 읽는다(pgxpool pool.go Stat). 그 틈에 생성이
// 시작되면 계수기만 오르고 연결은 안 보여 잔차가 거짓으로 +1 된다. 그런 읽기는 b 의 새 연결 수가 a 보다
// 커서 여기서 걸러진다 — 거짓 +N 을 막는 것은 이 조건이다. 생성 중 0 조건은 결과(성공 · 실패)가 정해질
// 때까지 계수를 미루는 보수 장치다.
func settled(a, b snapshot) bool {
	return a.newConns == b.newConns && b.constructing == 0
}

// signal 은 tick 하나가 게이지에 낸 변화다.
type signal int

const (
	signalNone  signal = iota
	signalSet          // 버린 연결이 늘었다(value=1)
	signalClear        // 켜진 뒤 처음으로 늘지 않았다(value=0)
)

// state 는 tick 사이에 이어지는 관측 상태다.
type state struct {
	mark      int64 // 잔차의 높은 물 표시
	newConns  int64 // 마지막으로 채택한 스냅숏의 새 연결 누적
	baselined bool  // 기준점을 잡았는지
	firing    bool  // 게이지가 켜져 있는지
}

// reading 은 tick 하나의 판정이다.
type reading struct {
	snap      snapshot
	skipped   bool  // 비정지라 이 tick 을 세지 않았다
	discarded int64 // 이 구간에 버린 연결
	newConns  int64 // 이 구간에 세운 연결
	total     int64 // 버린 연결 누적(높은 물 표시)
	signal    signal
}

// observe 는 연달아 읽은 두 스냅숏으로 tick 하나를 판정한다. 정지 쌍만 채택하고 아니면 상태를 그대로 둔다.
//
// 높은 물 표시를 쓰는 이유: 수명 폐기는 계수기를 먼저 올리고 연결은 비동기로 치운다(puddle Destroy).
// 그 사이 잔차가 잠깐 내려갔다 돌아오는데, 직전 값과의 차로 세면 그 회복을 폐기로 센다.
func observe(st state, a, b snapshot) (state, reading) {
	if !settled(a, b) {
		return st, reading{snap: b, skipped: true, total: st.mark}
	}
	if !st.baselined {
		next := state{mark: b.discarded(), newConns: b.newConns, baselined: true}
		return next, reading{snap: b, total: next.mark}
	}
	next := state{mark: max(st.mark, b.discarded()), newConns: b.newConns, baselined: true, firing: st.firing}
	r := reading{snap: b, discarded: next.mark - st.mark, newConns: b.newConns - st.newConns, total: next.mark}
	switch {
	case r.discarded > 0:
		next.firing, r.signal = true, signalSet
	case st.firing:
		next.firing, r.signal = false, signalClear
	}
	return next, r
}

// watcher 는 풀 하나의 관측자다. read 는 풀의 스냅숏을 읽는다(시험이 바꾼다).
type watcher struct {
	name string
	log  *slog.Logger
	read func() snapshot
	st   state
}

// begin 은 기동 줄을 내고 기준점을 잡는다. 비정지면 retryDelay 간격으로 baselineRetries 번 더 읽고,
// 그래도 비정지면 기준점 없이 끝낸다 — 다음 정지 tick 이 기준점이 된다(그 사이 폐기는 세지 못한다).
//
// 기동 줄은 게이지 관례대로 value=0 reason=startup 이다 — 이 프로세스 이전 값을 0 으로 본다는 뜻이다.
func (w *watcher) begin(ctx context.Context, retryDelay time.Duration) {
	w.log.Warn(gaugeName, "pool", w.name, "value", 0, "reason", "startup")
	for try := 0; ; try++ {
		w.st, _ = observe(w.st, w.read(), w.read())
		if w.st.baselined || try == baselineRetries {
			return
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(retryDelay):
		}
	}
}

// tick 은 스냅숏 두 개를 연달아 읽어 판정하고 줄을 낸다. every 는 줄에 싣는 주기다.
func (w *watcher) tick(every time.Duration) {
	var r reading
	w.st, r = observe(w.st, w.read(), w.read())
	s := r.snap
	w.log.Debug(statsName, "pool", w.name, "total", s.total, "idle", s.idle, "acquired", s.acquired,
		"constructing", s.constructing, "max", s.max, "new_conns_total", s.newConns,
		"lifetime_destroys", s.lifetimeDestroys, "idle_destroys", s.idleDestroys,
		"discarded_total", r.total, "skipped", skippedFlag(r.skipped))
	if r.signal == signalNone {
		return
	}
	value := 0
	if r.signal == signalSet {
		value = 1
	}
	// 켜짐과 꺼짐을 같은 등급으로 낸다 — 꺼짐만 낮으면 LOG_LEVEL=warn 수집에서 회복이 걸러진다.
	w.log.Warn(gaugeName, "pool", w.name, "value", value, "discarded", r.discarded,
		"discarded_total", r.total, "new_conns", r.newConns, "every", every)
}

func skippedFlag(skipped bool) int {
	if skipped {
		return 1
	}
	return 0
}

// start 는 기동 줄과 기준점을 동기로 끝낸 뒤 관측 고루틴을 띄운다.
func start(ctx context.Context, w *watcher, every, retryDelay time.Duration) (stop func()) {
	ctx, cancel := context.WithCancel(ctx)
	w.begin(ctx, retryDelay)
	done := make(chan struct{})
	go func() {
		defer close(done)
		t := time.NewTicker(every)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				w.tick(every)
			}
		}
	}()
	return func() {
		cancel()
		<-done
	}
}

// Start 는 pool 의 연결 폐기 관측을 시작한다. name 은 줄의 pool 속성이다(indexer · publisher · janitor).
//
// 1분마다 DEBUG `pg_pool_stats` 한 줄을 내고, 버린 연결이 늘어난 주기마다 WARN `pg_pool_conn_discarded`
// value=1 을, 켜진 뒤 처음 늘지 않은 주기에 value=0 을 한 번 낸다. 기동 줄(value=0 reason=startup)은 반환
// 전에 낸다.
//
// stop 은 관측을 멈추고 고루틴이 끝날 때까지 기다린다. pool.Close 보다 먼저 불러야 한다 — defer 라면
// pool.Close 의 defer 보다 뒤에 등록한다.
func Start(ctx context.Context, pool *pgxpool.Pool, name string, log *slog.Logger) (stop func()) {
	w := &watcher{name: name, log: log, read: func() snapshot { return snapshotOf(pool.Stat()) }}
	return start(ctx, w, every, baselineRetryDelay)
}
