package poolstat

// 실 PostgreSQL 에 붙어 관측을 잰다. PG_DSN 이 없으면 건너뛴다(pgtest.Pool) — REQUIRE_PG=1 이면 TestMain 이
// 실패로 바꾼다.

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"os"
	"strconv"
	"sync"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
	"github.com/jackc/pgx/v5/pgxpool"
)

func TestMain(m *testing.M) {
	if os.Getenv("REQUIRE_PG") == "1" && os.Getenv("PG_DSN") == "" {
		fmt.Fprintln(os.Stderr, "REQUIRE_PG=1 인데 PG_DSN 이 비어 있다 — poolstat PG 통합 케이스가 전량 skip 된다. 게이트 실패.")
		os.Exit(1)
	}
	os.Exit(m.Run())
}

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

// waitPool 은 pool.Stat 이 ok 를 만족할 때까지 기다린다. snapshotOf 를 거치지 않는다 — 그 배선을 재는 시험의
// 대기 조건이 배선 변이를 따라 바뀌지 않게.
func waitPool(t *testing.T, pool *pgxpool.Pool, what string, ok func(*pgxpool.Stat) bool) {
	t.Helper()
	deadline := time.Now().Add(statWait)
	for !ok(pool.Stat()) {
		if time.Now().After(deadline) {
			t.Fatalf("%v 안에 %s 에 닿지 않았다", statWait, what)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// warm 은 연결 n 개를 동시에 빌렸다 돌려준다 — 풀이 n 개까지 자란다. 도중에 실패해도 빌린 연결은 돌려준다
// (t.Fatalf 도 defer 를 돈다) — 안 돌려주면 Cleanup 의 pool.Close 가 반납을 기다리며 멈춘다.
func warm(t *testing.T, pool *pgxpool.Pool, n int) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), statWait)
	defer cancel()
	var conns []*pgxpool.Conn
	defer func() {
		for _, c := range conns {
			c.Release()
		}
	}()
	for range n {
		c, err := pool.Acquire(ctx)
		if err != nil {
			t.Fatalf("연결 획득 실패: %v", err)
		}
		conns = append(conns, c)
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

// 통계 줄의 칸은 같은 순간 pool.Stat 의 값이다 — snapshotOf 의 배선을 잰다. 칸끼리 값이 달라야 맞바꿈이
// 드러나므로 연결 셋 가운데 하나를 쥐고(빌림 1 · 유휴 2) 수명 폐기만 하나 만든다(수명 1 · 유휴 0).
func TestPGStatsLineMatchesPoolStat(t *testing.T) {
	const lifetime = 500 * time.Millisecond
	cfg := pgtest.Pool(t, "poolstat", noTables, noTables).Config()
	cfg.MinConns, cfg.MinIdleConns = 0, 0
	cfg.MaxConnLifetime, cfg.MaxConnLifetimeJitter = lifetime, 0
	cfg.MaxConnIdleTime, cfg.HealthCheckPeriod = time.Hour, time.Hour
	pool, err := pgxpool.NewWithConfig(context.Background(), cfg)
	if err != nil {
		t.Fatalf("풀 생성 실패: %v", err)
	}
	t.Cleanup(pool.Close)
	ctx, cancel := context.WithTimeout(context.Background(), statWait)
	defer cancel()

	// 수명을 넘긴 유휴 연결은 다음 획득이 버린다. 반납 때 버리면 점검이 깨어나 유휴 연결을 건드린다.
	warm(t, pool, 1)
	time.Sleep(lifetime + 100*time.Millisecond)
	held, err := pool.Acquire(ctx)
	if err != nil {
		t.Fatalf("연결 획득 실패: %v", err)
	}
	defer held.Release()
	warm(t, pool, 2)
	waitPool(t, pool, "빌림 1 · 유휴 2 · 수명 폐기 1", func(s *pgxpool.Stat) bool {
		return s.TotalConns() == 3 && s.IdleConns() == 2 && s.AcquiredConns() == 1 && s.ConstructingConns() == 0 &&
			s.MaxLifetimeDestroyCount() == 1 && s.MaxIdleDestroyCount() == 0
	})
	log, out := newCapture(slog.LevelDebug)

	pgWatcher(pool, log).tick(time.Minute)
	s := pool.Stat()

	lines := out.named(t, statsName)
	if len(lines) != 1 {
		t.Fatalf("%s 줄 %d개 = %v, want 하나", statsName, len(lines), lines)
	}
	for _, f := range []struct {
		key  string
		want int64
	}{
		{"total", int64(s.TotalConns())},
		{"idle", int64(s.IdleConns())},
		{"acquired", int64(s.AcquiredConns())},
		{"constructing", int64(s.ConstructingConns())},
		{"max", int64(s.MaxConns())},
		{"lifetime_destroys", s.MaxLifetimeDestroyCount()},
		{"idle_destroys", s.MaxIdleDestroyCount()},
	} {
		if got := lines[0][f.key]; got != float64(f.want) {
			t.Errorf("%s 줄의 %s = %v, want %d(같은 순간 pool.Stat)", statsName, f.key, got, f.want)
		}
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

// 망이 막혀 연결을 맺지 못한 시도도 「버렸거나 맺지 못한 연결」로 센다(P5). 시도가 끝나기 전, 연결을 세우는
// 중인 주기는 세지 않고 건너뛴다.
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
	log, out := newCapture(slog.LevelDebug)
	w := pgWatcher(pool, log)
	w.begin(context.Background(), time.Millisecond)

	errc := make(chan error, 1)
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_, err := pool.Exec(ctx, "SELECT 1")
		errc <- err
	}()
	// 연결 시도는 ConnectTimeout(1s) 동안 생성 중으로 남는다 — 그 사이의 tick 은 건너뛰어야 한다.
	waitPool(t, pool, "생성 중 연결 1", func(s *pgxpool.Stat) bool { return s.ConstructingConns() == 1 })
	w.tick(time.Minute)
	if err := <-errc; err == nil {
		t.Fatal("막힌 망으로 질의가 성공했다 — 프록시가 막지 않았다")
	}
	waitStat(t, pool, "연결 시도 끝", func(s snapshot) bool { return s.newConns >= 1 })
	w.tick(time.Minute)

	if stats := out.named(t, statsName); len(stats) != 2 || stats[0]["skipped"] != float64(1) {
		t.Errorf("%s 줄 = %v, want 둘 · 생성 중 tick 은 skipped=1", statsName, stats)
	}

	got := out.named(t, gaugeName)
	if len(got) != 2 {
		t.Fatalf("%s 줄 %d개 = %v, want startup · 켜짐 둘", gaugeName, len(got), got)
	}
	if d, _ := got[1]["discarded"].(float64); got[1]["value"] != float64(1) || d < 1 {
		t.Errorf("%s 줄 = %v, want startup · value=1 discarded≥1", gaugeName, got)
	}
}
