package poolstat

import (
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// snap 은 판정에 쓰는 다섯 값만 채운 스냅숏이다.
func snap(total, constructing int32, newConns, lifetimeDestroys, idleDestroys int64) snapshot {
	return snapshot{total: total, constructing: constructing, newConns: newConns,
		lifetimeDestroys: lifetimeDestroys, idleDestroys: idleDestroys}
}

// capture 는 로거가 쓴 JSON 줄을 모은다. 관측 고루틴과 시험이 함께 만지므로 잠금을 건다.
type capture struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (c *capture) Write(p []byte) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.buf.Write(p)
}

func newCapture(level slog.Level) (*slog.Logger, *capture) {
	c := &capture{}
	return slog.New(slog.NewJSONHandler(c, &slog.HandlerOptions{Level: level})), c
}

// named 는 지금까지 쓴 줄 가운데 메시지가 msg 인 것을 차례대로 돌려준다.
func (c *capture) named(t *testing.T, msg string) []map[string]any {
	t.Helper()
	c.mu.Lock()
	text := c.buf.String()
	c.mu.Unlock()
	var out []map[string]any
	for _, line := range strings.Split(strings.TrimSpace(text), "\n") {
		if line == "" {
			continue
		}
		var rec map[string]any
		if err := json.Unmarshal([]byte(line), &rec); err != nil {
			t.Fatalf("로그 줄 %q 가 JSON 이 아니다: %v", line, err)
		}
		if rec["msg"] == msg {
			out = append(out, rec)
		}
	}
	return out
}

// script 는 부를 때마다 다음 스냅숏을 돌려주고, 다 쓰면 마지막 것을 되풀이한다.
type script struct {
	mu    sync.Mutex
	snaps []snapshot
	calls int
}

func (s *script) read() snapshot {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := min(s.calls, len(s.snaps)-1)
	s.calls++
	return s.snaps[i]
}

func (s *script) callCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.calls
}

// twice 는 tick 한 번(연달아 두 번 읽기)이 같은 스냅숏을 보게 한다 — 정지 쌍이다.
func twice(s snapshot) []snapshot { return []snapshot{s, s} }

// ticks 는 정지 쌍 여러 개를 이어 붙인다.
func ticks(snaps ...snapshot) []snapshot {
	var out []snapshot
	for _, s := range snaps {
		out = append(out, twice(s)...)
	}
	return out
}

func TestObserve(t *testing.T) {
	// 기준: 연결 둘이 열려 있고(새 연결 누적 2) 아직 버린 것이 없다.
	base := state{mark: 0, newConns: 2, baselined: true}
	firing := state{mark: 0, newConns: 2, baselined: true, firing: true}
	tests := []struct {
		name          string
		st            state
		a, b          snapshot
		wantSkipped   bool
		wantDiscarded int64
		wantNewConns  int64
		wantSignal    signal
		wantState     state
	}{
		{name: "성장만은 폐기가 아니다", st: base, a: snap(4, 0, 4, 0, 0), b: snap(4, 0, 4, 0, 0),
			wantNewConns: 2, wantState: state{mark: 0, newConns: 4, baselined: true}},
		{name: "수명 교체는 폐기가 아니다", st: base, a: snap(2, 0, 3, 1, 0), b: snap(2, 0, 3, 1, 0),
			wantNewConns: 1, wantState: state{mark: 0, newConns: 3, baselined: true}},
		{name: "유휴 한도 폐기는 폐기가 아니다", st: base, a: snap(1, 0, 2, 0, 1), b: snap(1, 0, 2, 0, 1),
			wantState: state{mark: 0, newConns: 2, baselined: true}},
		{name: "반납 폐기 하나", st: base, a: snap(1, 0, 2, 0, 0), b: snap(1, 0, 2, 0, 0),
			wantDiscarded: 1, wantSignal: signalSet, wantState: state{mark: 1, newConns: 2, baselined: true, firing: true}},
		{name: "연결 시도 실패도 센다", st: base, a: snap(2, 0, 3, 0, 0), b: snap(2, 0, 3, 0, 0),
			wantDiscarded: 1, wantNewConns: 1, wantSignal: signalSet,
			wantState: state{mark: 1, newConns: 3, baselined: true, firing: true}},
		{name: "비동기 폐기 중 일시 하강은 표시를 내리지 않는다", st: base, a: snap(2, 0, 2, 1, 0), b: snap(2, 0, 2, 1, 0),
			wantState: state{mark: 0, newConns: 2, baselined: true}},
		{name: "ⓐ 두 읽기 사이에 생성이 시작되면 건너뛴다", st: base, a: snap(2, 0, 2, 0, 0), b: snap(2, 0, 3, 0, 0),
			wantSkipped: true, wantState: base},
		{name: "ⓑ 생성 중인 연결이 있으면 건너뛴다", st: base, a: snap(2, 1, 3, 0, 0), b: snap(2, 1, 3, 0, 0),
			wantSkipped: true, wantState: base},
		{name: "기준점 없는 첫 정지 쌍이 기준점이 된다", st: state{}, a: snap(1, 0, 3, 0, 0), b: snap(1, 0, 3, 0, 0),
			wantState: state{mark: 2, newConns: 3, baselined: true}},
		{name: "기준점 없는 비정지 쌍은 기준점이 되지 않는다", st: state{}, a: snap(1, 0, 3, 0, 0), b: snap(1, 0, 4, 0, 0),
			wantSkipped: true, wantState: state{}},
		{name: "켜진 뒤 첫 0 은 끈다", st: firing, a: snap(2, 0, 2, 0, 0), b: snap(2, 0, 2, 0, 0),
			wantSignal: signalClear, wantState: base},
		{name: "켜진 채 또 버리면 다시 켠다", st: firing, a: snap(1, 0, 2, 0, 0), b: snap(1, 0, 2, 0, 0),
			wantDiscarded: 1, wantSignal: signalSet, wantState: state{mark: 1, newConns: 2, baselined: true, firing: true}},
		{name: "켜진 채 건너뛰면 게이지는 그대로다", st: firing, a: snap(2, 0, 2, 0, 0), b: snap(2, 0, 3, 0, 0),
			wantSkipped: true, wantState: firing},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, r := observe(tt.st, tt.a, tt.b)

			if r.skipped != tt.wantSkipped || r.discarded != tt.wantDiscarded || r.newConns != tt.wantNewConns ||
				r.signal != tt.wantSignal {
				t.Errorf("observe(%+v, %+v, %+v) 판정 = skipped %v · discarded %d · new_conns %d · signal %d, "+
					"want %v · %d · %d · %d", tt.st, tt.a, tt.b, r.skipped, r.discarded, r.newConns, r.signal,
					tt.wantSkipped, tt.wantDiscarded, tt.wantNewConns, tt.wantSignal)
			}
			if got != tt.wantState {
				t.Errorf("observe(%+v, %+v, %+v) 다음 상태 = %+v, want %+v", tt.st, tt.a, tt.b, got, tt.wantState)
			}
			if r.snap != tt.b || r.total != got.mark {
				t.Errorf("observe 보고 스냅숏 · 누적 = %+v · %d, want 뒤 스냅숏 %+v · 표시 %d", r.snap, r.total, tt.b, got.mark)
			}
		})
	}
}

// 수명 폐기는 계수기를 먼저 올리고 연결은 비동기로 치운다(puddle Destroy). 그 사이의 일시 하강 뒤 회복을
// 폐기로 세면 교체만 있는 구간에 거짓 경보가 난다 — 높은 물 표시가 막는다(P3).
func TestObserveAbsorbsAsyncDestroyDip(t *testing.T) {
	st := state{mark: 0, newConns: 2, baselined: true}
	var sum int64
	for _, s := range []snapshot{
		snap(2, 0, 2, 1, 0), // 계수기만 올랐다(잔차 −1)
		snap(1, 0, 2, 1, 0), // 연결이 치워졌다(잔차 0)
		snap(2, 0, 3, 1, 0), // 새 연결로 채웠다(잔차 0)
	} {
		var r reading
		st, r = observe(st, s, s)
		sum += r.discarded
		if r.signal == signalSet {
			t.Errorf("교체 구간 스냅숏 %+v 에서 켜졌다 — 일시 하강을 폐기로 셌다", s)
		}
	}
	if sum != 0 {
		t.Errorf("교체만 있는 구간의 폐기 합 = %d, want 0", sum)
	}
}

// 게이지 줄은 기동 · 켜짐 · 꺼짐 모두 WARN 이라 LOG_LEVEL=warn 에서도 회복이 보인다(P6). 폐기가 이어지는
// tick 마다 value=1 이 나가고, 꺼짐은 켜진 뒤 첫 0 tick 에 한 번뿐이다(P7).
func TestWatcherGaugeLinesAtWarn(t *testing.T) {
	log, out := newCapture(slog.LevelWarn)
	src := &script{snaps: ticks(
		snap(2, 0, 2, 0, 0), // 기준점
		snap(1, 0, 2, 0, 0), // 폐기 1
		snap(0, 0, 2, 0, 0), // 폐기 1 더
		snap(0, 0, 2, 0, 0), // 0 — 끈다
		snap(0, 0, 2, 0, 0), // 0 — 꺼진 채
	)}
	w := &watcher{name: "indexer", log: log, read: src.read}

	w.begin(context.Background(), time.Millisecond)
	for range 4 {
		w.tick(time.Minute)
	}

	got := out.named(t, gaugeName)
	want := []struct {
		value, discarded, total float64
		reason                  string
	}{{0, 0, 0, "startup"}, {1, 1, 1, ""}, {1, 1, 2, ""}, {0, 0, 2, ""}}
	if len(got) != len(want) {
		t.Fatalf("%s 줄 %d개 = %v, want %d개(startup · 켜짐 · 켜짐 · 꺼짐)", gaugeName, len(got), got, len(want))
	}
	for i, w := range want {
		r := got[i]
		if r["level"] != "WARN" || r["pool"] != "indexer" || r["value"] != w.value {
			t.Errorf("줄 %d = %v, want WARN pool=indexer value=%v", i, r, w.value)
		}
		if w.reason != "" {
			if r["reason"] != w.reason {
				t.Errorf("줄 %d reason = %v, want %q", i, r["reason"], w.reason)
			}
			continue
		}
		if r["discarded"] != w.discarded || r["discarded_total"] != w.total || r["new_conns"] != float64(0) ||
			r["every"] == nil {
			t.Errorf("줄 %d = %v, want discarded=%v discarded_total=%v new_conns=0 every 있음", i, r, w.discarded, w.total)
		}
	}
}

// 건너뛴 tick 은 게이지를 바꾸지 않고 DEBUG 통계 줄에 skipped=1 로만 남는다(P10).
func TestWatcherSkippedTickKeepsGaugeAndLogsStats(t *testing.T) {
	log, out := newCapture(slog.LevelDebug)
	src := &script{snaps: append(append(ticks(
		snap(2, 0, 2, 0, 0), // 기준점
		snap(1, 0, 2, 0, 0), // 폐기 1 — 켠다
	), snap(1, 0, 2, 0, 0), snap(1, 0, 3, 0, 0)), // 두 읽기 사이 생성 시작 — 건너뜀
		twice(snap(2, 0, 3, 0, 0))...)} // 정지 · 새 폐기 없음 — 끈다
	w := &watcher{name: "janitor", log: log, read: src.read}

	w.begin(context.Background(), time.Millisecond)
	for range 3 {
		w.tick(time.Minute)
	}

	stats := out.named(t, statsName)
	if len(stats) != 3 {
		t.Fatalf("%s 줄 %d개, want tick 마다 1개(3)", statsName, len(stats))
	}
	for i, wantSkipped := range []float64{0, 1, 0} {
		if stats[i]["level"] != "DEBUG" || stats[i]["skipped"] != wantSkipped {
			t.Errorf("%s 줄 %d = %v, want DEBUG skipped=%v", statsName, i, stats[i], wantSkipped)
		}
	}
	for _, key := range []string{"pool", "total", "idle", "acquired", "constructing", "max", "new_conns_total",
		"lifetime_destroys", "idle_destroys", "discarded_total"} {
		if _, ok := stats[2][key]; !ok {
			t.Errorf("%s 줄에 %q 가 없다: %v", statsName, key, stats[2])
		}
	}
	gauge := out.named(t, gaugeName)
	if len(gauge) != 3 || gauge[1]["value"] != float64(1) || gauge[2]["value"] != float64(0) {
		t.Errorf("%s 줄 = %v, want startup · 켜짐 · 꺼짐 셋(건너뛴 tick 은 줄 없음)", gaugeName, gauge)
	}
}

// 기동 때 비정지면 100ms 간격으로 최대 세 번 더 읽고, 처음 정지한 쌍이 기준점이다.
func TestBeginRetriesUntilSettled(t *testing.T) {
	log, out := newCapture(slog.LevelWarn)
	src := &script{snaps: append([]snapshot{
		snap(1, 0, 1, 0, 0), snap(1, 0, 2, 0, 0), // 비정지
		snap(1, 0, 2, 0, 0), snap(1, 0, 3, 0, 0), // 비정지
	}, ticks(snap(1, 0, 3, 0, 0), snap(1, 0, 3, 0, 0))...)}
	w := &watcher{name: "indexer", log: log, read: src.read}

	w.begin(context.Background(), time.Millisecond)

	if !w.st.baselined || w.st.mark != 2 || src.callCount() != 6 {
		t.Fatalf("세 번째 시도 뒤 상태 = %+v · 읽기 %d번, want 기준점 표시 2 · 6번", w.st, src.callCount())
	}
	w.tick(time.Minute)
	if got := out.named(t, gaugeName); len(got) != 1 {
		t.Errorf("%s 줄 = %v, want startup 하나 — 기준점 전 잔차를 폐기로 셌다", gaugeName, got)
	}
}

// 네 번 다 비정지면 기준점 없이 시작한다. 다음 정지 tick 이 기준점이고 그 tick 은 세지 않는다.
func TestBeginGivesUpAfterThreeRetries(t *testing.T) {
	log, out := newCapture(slog.LevelWarn)
	unsettled := []snapshot{snap(0, 0, 1, 0, 0), snap(0, 0, 2, 0, 0)}
	var snaps []snapshot
	for range 4 {
		snaps = append(snaps, unsettled...)
	}
	src := &script{snaps: append(snaps, ticks(snap(0, 0, 5, 0, 0), snap(0, 0, 6, 0, 0))...)}
	w := &watcher{name: "indexer", log: log, read: src.read}

	w.begin(context.Background(), time.Millisecond)

	if w.st.baselined || src.callCount() != 8 {
		t.Fatalf("기동 뒤 상태 = %+v · 읽기 %d번, want 기준점 없음 · 8번(첫 시도 + 재시도 3)", w.st, src.callCount())
	}
	w.tick(time.Minute) // 기준점 — 잔차 5 를 세지 않는다
	w.tick(time.Minute) // 연결 시도 실패 1
	got := out.named(t, gaugeName)
	if len(got) != 2 || got[1]["value"] != float64(1) || got[1]["discarded"] != float64(1) {
		t.Errorf("%s 줄 = %v, want startup · value=1 discarded=1", gaugeName, got)
	}
}

// 종료 신호가 오면 재시도를 기다리지 않는다 — 기동이 종료를 붙잡지 않는다.
func TestBeginStopsRetryingWhenCanceled(t *testing.T) {
	log, _ := newCapture(slog.LevelWarn)
	src := &script{snaps: []snapshot{snap(0, 0, 1, 0, 0), snap(0, 0, 2, 0, 0), snap(0, 0, 3, 0, 0)}}
	w := &watcher{name: "indexer", log: log, read: src.read}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	done := make(chan struct{})
	go func() { w.begin(ctx, time.Hour); close(done) }()

	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("취소된 ctx 에서 begin 이 재시도 간격(1h)을 기다린다")
	}
}

// start 는 startup 줄을 반환 전에 동기로 낸다 — 기동 줄 차례가 관측 고루틴과 경합하지 않는다.
func TestStartLogsStartupBeforeReturning(t *testing.T) {
	log, out := newCapture(slog.LevelWarn)
	src := &script{snaps: twice(snap(0, 0, 0, 0, 0))}

	stop := start(context.Background(), &watcher{name: "janitor", log: log, read: src.read}, time.Hour, time.Millisecond)
	got := out.named(t, gaugeName)
	stop()

	if len(got) != 1 || got[0]["reason"] != "startup" || got[0]["pool"] != "janitor" {
		t.Errorf("start 반환 시점의 %s 줄 = %v, want startup 하나(pool=janitor)", gaugeName, got)
	}
}

// stop 은 도는 tick 이 끝날 때까지 기다린다(P2 · P8). 기다리지 않으면 풀을 닫은 뒤에도 관측이 풀을 읽는다.
func TestStopWaitsForTickInFlight(t *testing.T) {
	log, _ := newCapture(slog.LevelWarn)
	src := &script{snaps: twice(snap(0, 0, 0, 0, 0))}
	entered := make(chan struct{})
	release := make(chan struct{})
	var once sync.Once
	var armed atomic.Bool // 기동(begin) 동안은 막지 않는다 — 켠 뒤의 읽기부터 막는다
	read := func() snapshot {
		if armed.Load() {
			once.Do(func() { close(entered) })
			<-release
		}
		return src.read()
	}

	stop := start(context.Background(), &watcher{name: "indexer", log: log, read: read}, time.Millisecond, time.Millisecond)
	armed.Store(true)
	select {
	case <-entered:
	case <-time.After(2 * time.Second):
		t.Fatal("2s 안에 tick 이 돌지 않았다")
	}
	stopped := make(chan struct{})
	go func() { stop(); close(stopped) }()

	select {
	case <-stopped:
		t.Fatal("tick 이 도는 중인데 stop 이 반환했다 — 관측 고루틴 종료를 기다리지 않는다")
	case <-time.After(50 * time.Millisecond):
	}
	close(release)
	select {
	case <-stopped:
	case <-time.After(2 * time.Second):
		t.Fatal("tick 이 끝났는데 stop 이 2s 안에 반환하지 않았다")
	}
	calls := src.callCount()
	time.Sleep(20 * time.Millisecond)
	if src.callCount() != calls {
		t.Errorf("stop 뒤에도 읽기가 늘었다(%d → %d) — 관측 고루틴이 살아 있다", calls, src.callCount())
	}
}

// 부모 ctx 가 끝나면 관측 고루틴도 끝나고, 그 뒤 stop 은 곧바로 반환한다.
func TestLoopTicksUntilContextCanceled(t *testing.T) {
	log, out := newCapture(slog.LevelDebug)
	src := &script{snaps: twice(snap(0, 0, 0, 0, 0))}
	ctx, cancel := context.WithCancel(context.Background())

	stop := start(ctx, &watcher{name: "indexer", log: log, read: src.read}, time.Millisecond, time.Millisecond)
	deadline := time.Now().Add(2 * time.Second)
	for len(out.named(t, statsName)) < 2 {
		if time.Now().After(deadline) {
			t.Fatalf("2s 안에 %s 줄이 2개 나오지 않았다 — 주기가 돌지 않는다", statsName)
		}
		time.Sleep(time.Millisecond)
	}
	cancel()
	stopped := make(chan struct{})
	go func() { stop(); close(stopped) }()

	select {
	case <-stopped:
	case <-time.After(2 * time.Second):
		t.Fatal("ctx 취소 뒤 stop 이 2s 안에 반환하지 않았다")
	}
}

// Start 는 풀 하나에 이름을 붙여 기동 줄을 낸다. 연결을 맺지 않는 풀(최소 연결 0)이라 DB 가 없어도 된다.
func TestStartUsesPoolNameWithoutConnecting(t *testing.T) {
	pool, err := pgxpool.New(context.Background(), "postgres://poolstat@127.0.0.1:1/poolstat?pool_min_conns=0")
	if err != nil {
		t.Fatalf("풀 생성 실패: %v", err)
	}
	defer pool.Close()
	log, out := newCapture(slog.LevelWarn)

	stop := Start(context.Background(), pool, "publisher", log)
	stop()

	got := out.named(t, gaugeName)
	if len(got) != 1 || got[0]["pool"] != "publisher" || got[0]["reason"] != "startup" {
		t.Errorf("%s 줄 = %v, want pool=publisher startup 하나", gaugeName, got)
	}
	if n := pool.Stat().NewConnsCount(); n != 0 {
		t.Errorf("관측이 연결을 %d개 세웠다, want 0 — Stat 만 읽어야 한다", n)
	}
}
