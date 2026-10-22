package poolstat

// 실 PostgreSQL 에 붙어 관측을 잰다. PG_DSN 이 없으면 건너뛴다(pgtest.Pool).

import (
	"context"
	"log/slog"
	"net"
	"strconv"
	"sync"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
	"github.com/jackc/pgx/v5/pgxpool"
)

// statWait 는 아래 대기의 상한이다.
const statWait = 5 * time.Second

func noTables(context.Context, *pgxpool.Pool) error { return nil }

// newPGPool 은 전용 테스트 DB 에 붙은 고정 설정 풀이다 — 새 연결 수로 판정하므로 pgtest.TracedPool 을 쓴다.
func newPGPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	return pgtest.TracedPool(context.Background(), t, pgtest.Pool(t, "poolstat", noTables, noTables), nil)
}

func pgWatcher(pool *pgxpool.Pool, log *slog.Logger) *watcher {
	return &watcher{name: "indexer", log: log, read: func() snapshot { return snapshotOf(pool.Stat()) }}
}

// waitStat 은 연달아 읽은 두 스냅숏 사이에 생성이 없고 생성 중인 연결도 없으며 ok 를 만족할 때까지 기다린다.
// 정지 조건을 제품의 settled 와 따로 적는다 — 그 판정을 깨는 변이가 시험의 대기 조건까지 바꾸지 않게.
func waitStat(t *testing.T, pool *pgxpool.Pool, what string, ok func(snapshot) bool) {
	t.Helper()
	deadline := time.Now().Add(statWait)
	for {
		a, b := snapshotOf(pool.Stat()), snapshotOf(pool.Stat())
		if a.newConns == b.newConns && b.constructing == 0 && ok(b) {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("%v 안에 %s 에 닿지 않았다 — 마지막 스냅숏 %+v", statWait, what, b)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// warm 은 연결 n 개를 동시에 빌렸다 돌려준다 — 풀이 n 개까지 자란다.
func warm(t *testing.T, pool *pgxpool.Pool, n int) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), statWait)
	defer cancel()
	var conns []*pgxpool.Conn
	for range n {
		c, err := pool.Acquire(ctx)
		if err != nil {
			t.Fatalf("연결 획득 실패: %v", err)
		}
		conns = append(conns, c)
	}
	for _, c := range conns {
		c.Release()
	}
}

// 풀이 자라기만 한 구간은 폐기가 아니다(P4).
func TestPGWarmupRaisesNoWarn(t *testing.T) {
	pool := newPGPool(t)
	log, out := newCapture(slog.LevelDebug)
	w := pgWatcher(pool, log)
	w.begin(context.Background(), time.Millisecond)

	warm(t, pool, 4)
	waitStat(t, pool, "연결 4개", func(s snapshot) bool { return s.total == 4 })
	w.tick(time.Minute)

	if got := out.named(t, gaugeName); len(got) != 1 {
		t.Errorf("데우기 뒤 %s 줄 = %v, want startup 하나", gaugeName, got)
	}
	stats := out.named(t, statsName)
	if len(stats) != 1 || stats[0]["new_conns_total"] != float64(4) || stats[0]["discarded_total"] != float64(0) {
		t.Errorf("%s 줄 = %v, want new_conns_total=4 discarded_total=0 하나", statsName, stats)
	}
}

// 질의 도중 ctx 가 끝나면 기본 감시기가 연결을 닫고 풀이 반납 때 버린다 — 그 하나를 센다. 다음 주기에 끈다.
func TestPGCanceledQueryCountsOneDiscard(t *testing.T) {
	pool := newPGPool(t)
	log, out := newCapture(slog.LevelWarn)
	w := pgWatcher(pool, log)
	warm(t, pool, 1)
	waitStat(t, pool, "연결 1개", func(s snapshot) bool { return s.total == 1 })
	w.begin(context.Background(), time.Millisecond)

	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	_, err := pool.Exec(ctx, "SELECT pg_sleep(2)")
	cancel()
	if err == nil {
		t.Fatal("100ms 시한의 pg_sleep(2) 가 성공했다 — 시한이 질의를 끊지 않았다")
	}
	waitStat(t, pool, "버린 연결 1", func(s snapshot) bool { return s.discarded() == w.st.mark+1 })
	w.tick(time.Minute)
	w.tick(time.Minute)

	got := out.named(t, gaugeName)
	if len(got) != 3 {
		t.Fatalf("%s 줄 %d개 = %v, want startup · 켜짐 · 꺼짐 셋", gaugeName, len(got), got)
	}
	if got[1]["value"] != float64(1) || got[1]["discarded"] != float64(1) {
		t.Errorf("폐기 주기 줄 = %v, want value=1 discarded=1", got[1])
	}
	if got[2]["value"] != float64(0) || got[2]["discarded"] != float64(0) {
		t.Errorf("다음 주기 줄 = %v, want value=0 discarded=0", got[2])
	}
}

// 동시 질의가 풀을 키우고 빌리는 동안 tick 을 돌려도 폐기가 없으면 켜지지 않는다(P1 · P10 — -race 로 돌린다).
func TestPGTickDuringConcurrentQueriesRaisesNoWarn(t *testing.T) {
	pool := newPGPool(t)
	log, out := newCapture(slog.LevelWarn)
	w := pgWatcher(pool, log)
	w.begin(context.Background(), time.Millisecond)

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	var wg sync.WaitGroup
	errs := make(chan error, 8)
	for range 8 {
		wg.Go(func() {
			for range 50 {
				if _, err := pool.Exec(ctx, "SELECT 1"); err != nil {
					errs <- err
					return
				}
			}
		})
	}
	done := make(chan struct{})
	go func() { wg.Wait(); close(done) }()
	ticked := 0
	for running := true; running; ticked++ {
		select {
		case <-done:
			running = false
		default:
		}
		w.tick(time.Minute)
	}
	close(errs)
	for err := range errs {
		t.Fatalf("동시 질의 실패: %v", err)
	}

	if got := out.named(t, gaugeName); len(got) != 1 {
		t.Errorf("폐기 없는 동시 질의 중 tick %d번의 %s 줄 = %v, want startup 하나", ticked, gaugeName, got)
	}
}

// 관측은 Stat 만 읽는다 — tick 을 100번 돌려도 새 연결이 생기지 않는다(P9 — 연결마다 IAM 토큰을 받는 풀).
func TestPGObservationOpensNoConnections(t *testing.T) {
	pool := newPGPool(t)
	log, _ := newCapture(slog.LevelWarn)
	warm(t, pool, 2)
	waitStat(t, pool, "연결 2개", func(s snapshot) bool { return s.total == 2 })
	before := pool.Stat().NewConnsCount()
	w := pgWatcher(pool, log)

	w.begin(context.Background(), time.Millisecond)
	for range 100 {
		w.tick(time.Minute)
	}

	if after := pool.Stat().NewConnsCount(); after != before {
		t.Errorf("tick 100번 뒤 새 연결 누적 = %d, want %d(관측 전과 같음)", after, before)
	}
}

// 망이 막혀 연결을 맺지 못한 시도도 「버렸거나 맺지 못한 연결」로 센다(P5).
func TestPGConnectFailureCountsAsDiscard(t *testing.T) {
	base := pgtest.Pool(t, "poolstat", noTables, noTables)
	cfg := base.Config()
	if cfg.ConnConfig.TLSConfig != nil && cfg.ConnConfig.Fallbacks == nil {
		t.Skip("PG_DSN 이 TLS 를 요구한다 — 평문 전달 프록시로 붙을 수 없다")
	}
	bh := pgtest.StartBlackhole(t, net.JoinHostPort(cfg.ConnConfig.Host, strconv.Itoa(int(cfg.ConnConfig.Port))))
	bh.SetBlocked(true)
	host, portText, err := net.SplitHostPort(bh.Addr())
	if err != nil {
		t.Fatal(err)
	}
	port, err := strconv.ParseUint(portText, 10, 16)
	if err != nil {
		t.Fatal(err)
	}
	cfg.ConnConfig.Host, cfg.ConnConfig.Port = host, uint16(port)
	cfg.ConnConfig.TLSConfig, cfg.ConnConfig.Fallbacks = nil, nil
	cfg.ConnConfig.ConnectTimeout = time.Second
	cfg.MinConns, cfg.MinIdleConns, cfg.HealthCheckPeriod = 0, 0, time.Hour
	pool, err := pgxpool.NewWithConfig(context.Background(), cfg)
	if err != nil {
		t.Fatalf("차단 프록시 풀 생성 실패: %v", err)
	}
	t.Cleanup(pool.Close)
	log, out := newCapture(slog.LevelWarn)
	w := pgWatcher(pool, log)
	w.begin(context.Background(), time.Millisecond)

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	_, err = pool.Exec(ctx, "SELECT 1")
	cancel()
	if err == nil {
		t.Fatal("막힌 망으로 질의가 성공했다 — 프록시가 막지 않았다")
	}
	waitStat(t, pool, "연결 시도 끝", func(s snapshot) bool { return s.newConns >= 1 })
	w.tick(time.Minute)

	got := out.named(t, gaugeName)
	if len(got) != 2 {
		t.Fatalf("%s 줄 %d개 = %v, want startup · 켜짐 둘", gaugeName, len(got), got)
	}
	if d, _ := got[1]["discarded"].(float64); got[1]["value"] != float64(1) || d < 1 {
		t.Errorf("%s 줄 = %v, want startup · value=1 discarded≥1", gaugeName, got)
	}
}
