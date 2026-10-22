package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/janitor"
)

// fakeDeps 는 기동 순서의 바깥 호출을 센다.
type fakeDeps struct {
	mu                           sync.Mutex
	pools, versions, roots, runs int
	version                      int
	versionErr                   error
}

func (f *fakeDeps) deps(t *testing.T) deps {
	return deps{
		newPool: func(ctx context.Context, cfg *pgxpool.Config) (*pgxpool.Pool, error) {
			f.mu.Lock()
			f.pools++
			f.mu.Unlock()
			return pgxpool.NewWithConfig(ctx, cfg) // 연결은 첫 질의 때 — 여기서는 열지 않는다
		},
		serverVersion: func(context.Context, *pgxpool.Pool) (int, error) {
			f.mu.Lock()
			defer f.mu.Unlock()
			f.versions++
			return f.version, f.versionErr
		},
		openRoot: func(string) (*os.Root, error) {
			f.mu.Lock()
			f.roots++
			f.mu.Unlock()
			return os.OpenRoot(t.TempDir())
		},
		runJanitor: func(context.Context, *janitor.Janitor) error {
			f.mu.Lock()
			f.runs++
			f.mu.Unlock()
			return context.Canceled
		},
	}
}

// lines 는 JSON 로그 줄을 msg 순서대로 편 것이다.
func lines(t *testing.T, out *bytes.Buffer) []map[string]any {
	t.Helper()
	var recs []map[string]any
	for _, l := range strings.Split(strings.TrimSpace(out.String()), "\n") {
		if l == "" {
			continue
		}
		var m map[string]any
		if err := json.Unmarshal([]byte(l), &m); err != nil {
			t.Fatalf("JSON 아닌 로그 %q: %v", l, err)
		}
		recs = append(recs, m)
	}
	return recs
}

func find(recs []map[string]any, msg string) []map[string]any {
	var out []map[string]any
	for _, r := range recs {
		if r["msg"] == msg {
			out = append(out, r)
		}
	}
	return out
}

// D31 — 꺼져 있으면 DB 풀 · 버전 조회 · 루트를 하나도 만들지 않고 신호를 기다리다 0 으로 끝난다.
func TestRunDisabledTouchesNothingAndWaits(t *testing.T) {
	f := &fakeDeps{version: 170000}
	var out, errOut bytes.Buffer
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan int, 1)
	go func() { done <- run(ctx, envOf(map[string]string{"JANITOR_ENABLED": ""}), &out, &errOut, f.deps(t)) }()

	select {
	case code := <-done:
		t.Fatalf("꺼진 janitor 가 신호 전에 끝났다(코드 %d) — 대기여야 한다(재시작 반복 방지)", code)
	case <-time.After(100 * time.Millisecond):
	}
	cancel()
	if code := <-done; code != exitOK {
		t.Errorf("종료 코드 = %d, want 0", code)
	}
	if f.pools+f.versions+f.roots+f.runs != 0 {
		t.Errorf("꺼짐에서 풀 %d · 버전 %d · 루트 %d · 실행 %d, want 전부 0", f.pools, f.versions, f.roots, f.runs)
	}
	got := find(lines(t, &out), "janitor_disabled")
	if len(got) != 1 || got[0]["level"] != "WARN" {
		t.Errorf("janitor_disabled = %v, want WARN 1줄", got)
	}
}

// D43 — PG 17 미만이면 transaction_timeout 이 없어 잠금 보유 상한이 사라진다 → 기동 거부 · ERROR 1줄 ·
// 루트 · 실행 0.
func TestRunRefusesPostgresBelow17(t *testing.T) {
	f := &fakeDeps{version: 169999}
	var out, errOut bytes.Buffer
	code := run(context.Background(), envOf(nil), &out, &errOut, f.deps(t))

	if code != exitFailure {
		t.Errorf("종료 코드 = %d, want %d", code, exitFailure)
	}
	if f.roots+f.runs != 0 {
		t.Errorf("버전 거부 뒤 루트 %d · 실행 %d, want 0 · 0(변이 — 버전 검사 제거)", f.roots, f.runs)
	}
	got := find(lines(t, &out), "janitor_pg_version_unsupported")
	if len(got) != 1 {
		t.Fatalf("janitor_pg_version_unsupported %d줄, want 1", len(got))
	}
	rec := got[0]
	if rec["level"] != "ERROR" || rec["server_version_num"] != float64(169999) || rec["required"] != float64(170000) {
		t.Errorf("janitor_pg_version_unsupported = %v, want ERROR server_version_num=169999 required=170000", rec)
	}
}

func TestRunStartsJanitorOnPostgres17(t *testing.T) {
	f := &fakeDeps{version: 170000}
	var out, errOut bytes.Buffer
	code := run(context.Background(), envOf(nil), &out, &errOut, f.deps(t))

	if code != exitOK || f.roots != 1 || f.runs != 1 {
		t.Fatalf("코드 %d · 루트 %d · 실행 %d, want 0 · 1 · 1", code, f.roots, f.runs)
	}
	recs := lines(t, &out)
	order := []string{"janitor_starting", "janitor_started", "janitor_stopped"}
	at := map[string]int{}
	for i, r := range recs {
		if _, ok := at[r["msg"].(string)]; !ok {
			at[r["msg"].(string)] = i
		}
	}
	for i := 1; i < len(order); i++ {
		a, okA := at[order[i-1]]
		b, okB := at[order[i]]
		if !okA || !okB || a > b {
			t.Errorf("기동 줄 차례 %s@%v → %s@%v, want 앞이 먼저", order[i-1], a, order[i], b)
		}
	}
	starting := find(recs, "janitor_starting")[0]
	started := find(recs, "janitor_started")[0]
	if starting["run_id"] == nil || starting["run_id"] != started["run_id"] {
		t.Errorf("run_id 가 기동 줄끼리 다르다: %v · %v", starting["run_id"], started["run_id"])
	}
	if strings.Contains(out.String(), "p@ss") || strings.Contains(out.String(), "p%40ss") {
		t.Error("로그에 비밀번호가 실렸다")
	}
}

func TestRunFailsWhenVersionQueryFails(t *testing.T) {
	f := &fakeDeps{versionErr: errors.New("접속 실패")}
	var out, errOut bytes.Buffer
	if code := run(context.Background(), envOf(nil), &out, &errOut, f.deps(t)); code != exitFailure || f.roots != 0 {
		t.Errorf("코드 %d · 루트 %d, want %d · 0", code, f.roots, exitFailure)
	}
}

func TestRunRejectsBadConfigBeforeAnything(t *testing.T) {
	f := &fakeDeps{version: 170000}
	var out, errOut bytes.Buffer
	code := run(context.Background(), envOf(map[string]string{"FS_OP_TIMEOUT": "7s"}), &out, &errOut, f.deps(t))
	if code != exitFailure || f.pools != 0 {
		t.Errorf("코드 %d · 풀 %d, want %d · 0", code, f.pools, exitFailure)
	}
	if !strings.Contains(errOut.String(), "FS_OP_TIMEOUT") {
		t.Errorf("stderr = %q, want FS_OP_TIMEOUT 언급", errOut.String())
	}
}

// R15 · 설계 2-4 — 세션 설정은 풀이 연결마다 건다(역할 기본값에 기대지 않는다). 값 하나라도 빠지면
// 서버 쪽 대기 상한이 사라진다.
func TestPoolConfigCarriesSessionLimits(t *testing.T) {
	cfg, err := loadConfig(envOf(nil))
	if err != nil {
		t.Fatal(err)
	}
	pc, err := poolConfig(cfg)
	if err != nil {
		t.Fatal(err)
	}
	want := map[string]string{"statement_timeout": "15s", "lock_timeout": "2s", "idle_in_transaction_session_timeout": "30s", "application_name": "janitor"}
	for k, v := range want {
		if got := pc.ConnConfig.RuntimeParams[k]; got != v {
			t.Errorf("RuntimeParams[%s] = %q, want %q", k, got, v)
		}
	}
	if pc.MaxConns != 2 {
		t.Errorf("MaxConns = %d, want 2", pc.MaxConns)
	}
}

// R15 실물 — 위 설정이 실제 세션에 걸리는지 실험 PG 에서 SHOW 로 본다. 버전 조회도 실물로 잰다.
func TestPoolConfigAppliesOnRealServer(t *testing.T) {
	dsn := os.Getenv("PG_DSN")
	if dsn == "" {
		t.Skip("PG_DSN 미설정")
	}
	pc, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		t.Fatal(err)
	}
	applySessionLimits(pc)
	ctx := context.Background()
	pool, err := pgxpool.NewWithConfig(ctx, pc)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	for _, q := range [][2]string{{"statement_timeout", "15s"}, {"lock_timeout", "2s"}, {"idle_in_transaction_session_timeout", "30s"}, {"application_name", "janitor"}} {
		var got string
		if err := pool.QueryRow(ctx, "SELECT current_setting($1)", q[0]).Scan(&got); err != nil {
			t.Fatal(err)
		}
		if got != q[1] {
			t.Errorf("세션 %s = %q, want %q", q[0], got, q[1])
		}
	}
	v, err := queryServerVersion(ctx, pool)
	if err != nil || v < minServerVersion {
		t.Errorf("server_version_num = %d, %v; want ≥ %d", v, err, minServerVersion)
	}
	fmt.Fprintf(os.Stderr, "실험 PG server_version_num=%d\n", v)
}

func TestMain(m *testing.M) {
	if os.Getenv("REQUIRE_PG") == "1" && os.Getenv("PG_DSN") == "" {
		fmt.Fprintln(os.Stderr, "REQUIRE_PG=1 인데 PG_DSN 이 비어 있다 — cmd/janitor PG 케이스가 skip 된다. 게이트 실패.")
		os.Exit(1)
	}
	os.Exit(m.Run())
}
