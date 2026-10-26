package janitor

// c1 실물(계획 7절 「PG 실물」) — 실 PG + 임시 루트 + 시험 안 차단 프록시. PG_DSN 이 없으면 skip
// (REQUIRE_PG=1 이면 TestMain 이 실패로 바꾼다). 이 패키지의 PG 시험은 접미 janitor 전용 DB 를 쓴다.

import (
	"context"
	"fmt"
	"log/slog"
	"maps"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/fsop"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

func TestMain(m *testing.M) {
	if os.Getenv("REQUIRE_PG") == "1" && os.Getenv("PG_DSN") == "" {
		fmt.Fprintln(os.Stderr, "REQUIRE_PG=1 인데 PG_DSN 이 비어 있다 — janitor PG 통합 케이스가 전량 skip 된다. 게이트 실패.")
		os.Exit(1)
	}
	os.Exit(m.Run())
}

// pgPolicy 는 운영 기본값과 같은 판정 기간이다.
var pgPolicy = index.LocalCleanupPolicy{Grace: 8 * time.Minute, ReconnectWindow: 300 * time.Second, ExternalDeleteAge: 23 * time.Hour}

func newJanitorTestPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	return pgtest.Pool(t, "janitor", index.EnsureSchema, func(ctx context.Context, pool *pgxpool.Pool) error {
		_, err := pool.Exec(ctx, "TRUNCATE stream_segments, stream_cutoffs, stream_published_gaps, stream_sessions, stream_seq_floors, broadcast_lifecycle_outbox")
		return err
	})
}

// janitorPool 은 base 와 같은 DB 에 janitor 세션 설정(cmd/janitor 와 같은 꼴)으로 붙는 풀이다.
// via 가 비어 있지 않으면 그 주소(차단 프록시)로 붙는다. lockTimeout 은 회수 시험이 줄인다.
func janitorPool(t *testing.T, base *pgxpool.Pool, via string, lockTimeout time.Duration) *pgxpool.Pool {
	t.Helper()
	cfg := base.Config().Copy()
	cfg.MaxConns = 2
	cfg.ConnConfig.RuntimeParams["statement_timeout"] = "15s"
	cfg.ConnConfig.RuntimeParams["lock_timeout"] = fmt.Sprintf("%dms", lockTimeout.Milliseconds())
	cfg.ConnConfig.RuntimeParams["idle_in_transaction_session_timeout"] = "30s"
	cfg.ConnConfig.RuntimeParams["application_name"] = "janitor_test"
	if via != "" {
		if cfg.ConnConfig.TLSConfig != nil && cfg.ConnConfig.Fallbacks == nil {
			t.Skip("PG_DSN 이 TLS 를 요구한다 — 평문 전달 프록시로 붙을 수 없다")
		}
		host, port, err := splitHostPort(via)
		if err != nil {
			t.Fatal(err)
		}
		cfg.ConnConfig.Host, cfg.ConnConfig.Port = host, port
		cfg.ConnConfig.TLSConfig, cfg.ConnConfig.Fallbacks = nil, nil
	}
	pool, err := pgxpool.NewWithConfig(context.Background(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	return pool
}

func splitHostPort(addr string) (string, uint16, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return "", 0, err
	}
	n, err := strconv.ParseUint(port, 10, 16)
	return host, uint16(n), err
}

// pgRig 은 실 PG 위의 janitor 와 임시 루트다.
type pgRig struct {
	j      *Janitor
	base   *pgxpool.Pool
	dir    string
	root   *os.Root
	log    *logSink
	mu     sync.Mutex
	gone   map[string]bool // janitor remove 가 성공한 경로
	onAct  func(n int)     // n 번째 remove 성공 뒤 불린다
	nActed atomic.Int32
}

func newPGRig(t *testing.T, base, jpool *pgxpool.Pool) *pgRig {
	t.Helper()
	dir := t.TempDir()
	root, err := os.OpenRoot(dir)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { root.Close() })
	sink := newLogSink(slog.LevelDebug)
	j, err := New(Options{Root: dir, Interval: 200 * time.Millisecond, Policy: pgPolicy, Batch: 200, MaxPerCycle: 2000,
		FSOpTimeout: 5 * time.Second, RunID: "run-pg", Log: slog.New(sink)}, index.NewLocalCleanupStore(jpool), root)
	if err != nil {
		t.Fatal(err)
	}
	pr := &pgRig{j: j, base: base, dir: dir, root: root, log: sink, gone: map[string]bool{}}
	j.remove = func(rel string) error {
		err := fsop.RemoveT(root, rel, 5*time.Second)
		if err == nil {
			pr.mu.Lock()
			pr.gone[filepath.Join(dir, rel)] = true
			pr.mu.Unlock()
			if n := int(pr.nActed.Add(1)); pr.onAct != nil {
				pr.onAct(n)
			}
		}
		return err
	}
	return pr
}

// seed 는 지울 수 있는 행 n 개(② uploaded · 1시간 전 · 컷오프 없음 · 세션 없음)와 그 파일을 만든다.
func (pr *pgRig) seed(t *testing.T, stream string, n int) []rowKey {
	t.Helper()
	if err := os.MkdirAll(filepath.Join(pr.dir, stream), 0o755); err != nil {
		t.Fatal(err)
	}
	start := time.Now().UTC().Add(-time.Hour)
	keys := make([]rowKey, 0, n)
	for i := range int64(n) {
		seq := i + 1
		p := filepath.Join(pr.dir, stream, testSegName(seq))
		if err := os.WriteFile(p, []byte("x"), 0o644); err != nil {
			t.Fatal(err)
		}
		_, err := pr.base.Exec(context.Background(), `
INSERT INTO stream_segments
    (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path,
     upload_state, uploaded_at, bytes, is_discontinuity, playback_upload_state)
VALUES ($1, $2, $3, $4, 4000, $5, $6, 'uploaded', $4, 1000, false, 'pending')`,
			stream, seq, seq*4000, start, index.S3Key(stream, seq, start), p)
		if err != nil {
			t.Fatalf("행 삽입 실패 %s/%d: %v", stream, seq, err)
		}
		keys = append(keys, rowKey{stream, seq})
	}
	return keys
}

// leftover 는 「janitor remove 성공 집합 ∩ 장부 경로 잔존」 수다(J-debt 의 실물 셈).
//
// 성공 집합을 **먼저** 복사하고 장부를 나중에 읽는다. 반대 순서면 조회 스냅숏 뒤에 지우고 커밋한
// 행이 「옛 스냅숏의 경로 + 새 성공 집합」으로 잡혀 잔존으로 세어진다(리눅스 반복 1/50 실측 — 최대 4).
// 이 순서면 복사 시점에 지워졌던 행이 조회 시점에도 경로를 가질 때만 센다 — 실제 미갱신이다.
func (pr *pgRig) leftover(t *testing.T) int {
	t.Helper()
	pr.mu.Lock()
	gone := maps.Clone(pr.gone)
	pr.mu.Unlock()
	rows, err := pr.base.Query(context.Background(), `SELECT local_path FROM stream_segments WHERE local_path IS NOT NULL`)
	if err != nil {
		t.Errorf("잔존 조회 실패: %v", err)
		return 0
	}
	paths, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		t.Errorf("잔존 스캔 실패: %v", err)
		return 0
	}
	n := 0
	for _, p := range paths {
		if gone[p] {
			n++
		}
	}
	return n
}

func (pr *pgRig) nonNull(t *testing.T) int {
	t.Helper()
	var n int
	if err := pr.base.QueryRow(context.Background(), `SELECT count(*) FROM stream_segments WHERE local_path IS NOT NULL`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

// c1 — k 번째 삭제 직후 DB 로 가는 길을 막는다(COMMIT 미도달). 매 관측마다 「삭제 성공 ∩ 장부
// 잔존」 ≤ 1, 길을 풀면 회수가 0 으로 만든다.
func TestPGBlockedCommitLeavesAtMostOneUnrefreshedRow(t *testing.T) {
	base := newJanitorTestPool(t)
	cc := base.Config().ConnConfig
	proxy := pgtest.StartBlackhole(t, net.JoinHostPort(cc.Host, strconv.Itoa(int(cc.Port))))
	pr := newPGRig(t, base, janitorPool(t, base, proxy.Addr(), 2*time.Second))
	pr.j.limits.rowLock = 3 * time.Second // 시험 시간을 줄인다 — 서버 상한(8s)과 무관한 클라이언트 기한
	pr.seed(t, "s1", 50)
	const k = 7
	pr.onAct = func(n int) {
		if n == k {
			proxy.SetBlocked(true)
		}
	}

	stop := make(chan struct{})
	var worst atomic.Int32
	var watch sync.WaitGroup
	watch.Go(func() {
		for {
			select {
			case <-stop:
				return
			case <-time.After(20 * time.Millisecond):
			}
			worst.Store(max(worst.Load(), int32(pr.leftover(t))))
		}
	})
	t.Cleanup(func() { close(stop); watch.Wait() })

	pr.j.runCycle(context.Background())
	if got := int(pr.nActed.Load()); got != k {
		t.Fatalf("막힌 주기의 삭제 = %d, want %d(막힌 뒤 추가 삭제 0)", got, k)
	}
	if len(pr.j.debt) != 1 || pr.j.debt[0].kind != debtDeleted {
		t.Fatalf("부채 = %+v, want deleted 1", pr.j.debt)
	}
	if n := pr.leftover(t); n != 1 {
		t.Fatalf("막힘 중 잔존 = %d, want 1", n)
	}

	proxy.SetBlocked(false)
	deadline := time.Now().Add(30 * time.Second)
	for pr.nonNull(t) > 0 {
		if time.Now().After(deadline) {
			t.Fatalf("길을 푼 뒤 30s 안에 정리가 끝나지 않았다(남은 행 %d · 부채 %+v)", pr.nonNull(t), pr.j.debt)
		}
		pr.j.runCycle(context.Background())
		time.Sleep(50 * time.Millisecond)
	}
	if n := pr.leftover(t); n != 0 {
		t.Errorf("회수 뒤 잔존 = %d, want 0", n)
	}
	if w := worst.Load(); w > 1 {
		t.Errorf("관측 중 최대 잔존 = %d, want ≤ 1", w)
	}
}

// c1 — 다른 세션이 20행을 FOR UPDATE 로 쥔 동안 그 행은 Busy · 파일 실재, 나머지는 정리된다.
func TestPGLockedRowsAreBusyAndFilesSurvive(t *testing.T) {
	base := newJanitorTestPool(t)
	pr := newPGRig(t, base, janitorPool(t, base, "", 2*time.Second))
	keys := pr.seed(t, "s1", 30)
	ctx := context.Background()
	tx, err := base.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = tx.Rollback(ctx) })
	if _, err := tx.Exec(ctx, `SELECT 1 FROM stream_segments WHERE stream_id = 's1' AND seq <= 20 FOR UPDATE`); err != nil {
		t.Fatal(err)
	}

	pr.j.runCycle(ctx)

	if got := pr.log.last(t, "janitor_cycle_summary").attrs["lock_skipped"]; got != "20" {
		t.Errorf("lock_skipped = %q, want 20", got)
	}
	for _, k := range keys[:20] {
		if _, err := os.Stat(filepath.Join(pr.dir, "s1", testSegName(k.seq))); err != nil {
			t.Errorf("잠긴 행 %d 의 파일이 사라졌다: %v", k.seq, err)
		}
	}
	if n := pr.nonNull(t); n != 20 {
		t.Errorf("남은 행 = %d, want 20(잠긴 행만)", n)
	}
}

// c1 · D39 — 부채 행(deleted)을 다른 세션이 FOR UPDATE 로 쥐면 회수가 55P03 으로 막힌다. 10번 쌓이면
// reclaim_blocked=1 · 그동안 새 삭제 0, 풀면 회수 · 게이지 clear · 삭제 재개. 단언은 횟수로 한다.
func TestPGLockedDebtRowBlocksReclaimUntilReleased(t *testing.T) {
	base := newJanitorTestPool(t)
	pr := newPGRig(t, base, janitorPool(t, base, "", 100*time.Millisecond))
	keys := pr.seed(t, "s1", 5)
	ctx := context.Background()
	debtPath := filepath.Join(pr.dir, "s1", testSegName(1))
	if err := os.Remove(debtPath); err != nil { // 지웠는데 COMMIT 을 못 받은 상태를 만든다
		t.Fatal(err)
	}
	pr.j.addDebt(index.LocalSegment{StreamID: "s1", Seq: keys[0].seq, LocalPath: debtPath}, debtDeleted, "removed")
	tx, err := base.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = tx.Rollback(ctx) })
	if _, err := tx.Exec(ctx, `SELECT 1 FROM stream_segments WHERE stream_id = 's1' AND seq = 1 FOR UPDATE`); err != nil {
		t.Fatal(err)
	}

	for i := 1; i <= deletedBlockedFailures; i++ {
		pr.j.runCycle(ctx)
		if len(pr.j.debt) != 1 {
			t.Fatalf("회수 %d회째 부채 = %+v, want 1", i, pr.j.debt)
		}
		if pr.j.debt[0].failures != i {
			t.Fatalf("회수 %d회째 연속 실패 = %d", i, pr.j.debt[0].failures)
		}
	}
	if pr.nActed.Load() != 0 {
		t.Fatalf("회수가 막힌 동안 삭제 = %d, want 0", pr.nActed.Load())
	}
	if rec := pr.log.last(t, "janitor_reclaim_blocked"); rec.attrs["value"] != "1" {
		t.Fatalf("reclaim_blocked = %v, want value=1", rec.attrs)
	}

	if err := tx.Rollback(ctx); err != nil {
		t.Fatal(err)
	}
	pr.j.runCycle(ctx)
	if len(pr.j.debt) != 0 {
		t.Fatalf("해제 뒤 부채 = %+v, want 0", pr.j.debt)
	}
	if rec := pr.log.last(t, "janitor_reclaim_blocked"); rec.attrs["value"] != "0" {
		t.Errorf("해제 뒤 reclaim_blocked = %v, want value=0", rec.attrs)
	}
	if n := pr.nonNull(t); n != 0 {
		t.Errorf("해제 뒤 남은 행 = %d, want 0(회수 · 삭제 재개)", n)
	}
}
