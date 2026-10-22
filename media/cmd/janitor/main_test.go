package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"slices"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/janitor"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgconf"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// fakeDeps 는 기동 순서의 바깥 호출을 센다.
type fakeDeps struct {
	mu                                     sync.Mutex
	pools, confirms, versions, roots, runs int
	version                                int
	versionErr, confirmErr                 error
	calls                                  []string      // 바깥 호출 차례
	confirmBudget                          time.Duration // 연결 확인이 받은 ctx 의 남은 기한(없으면 0)
}

// note 는 바깥 호출 하나를 차례에 적는다.
func (f *fakeDeps) note(call string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls = append(f.calls, call)
}

func (f *fakeDeps) deps(t *testing.T) deps {
	return deps{
		newPool: func(ctx context.Context, cfg *pgxpool.Config) (*pgxpool.Pool, error) {
			f.mu.Lock()
			f.pools++
			f.mu.Unlock()
			f.note("newPool")
			return pgxpool.NewWithConfig(ctx, cfg) // 연결은 첫 질의 때 — 여기서는 열지 않는다
		},
		confirm: func(ctx context.Context, _ *pgxpool.Pool, _ pgconf.Settings, _ *slog.Logger) error {
			f.note("confirm")
			f.mu.Lock()
			defer f.mu.Unlock()
			f.confirms++
			if dl, ok := ctx.Deadline(); ok {
				f.confirmBudget = time.Until(dl)
			}
			return f.confirmErr
		},
		serverVersion: func(context.Context, *pgxpool.Pool) (int, error) {
			f.note("serverVersion")
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
	if f.pools+f.confirms+f.versions+f.roots+f.runs != 0 {
		t.Errorf("꺼짐에서 풀 %d · 연결 확인 %d · 버전 %d · 루트 %d · 실행 %d, want 전부 0(변이 — 연결 확인을 꺼짐 분기 앞으로)",
			f.pools, f.confirms, f.versions, f.roots, f.runs)
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

// POK-311 — 풀을 만든 뒤 버전 조회보다 먼저 연결을 확인한다(Ping · db_connected 줄). 확인은 기동 상한
// (versionQueryTimeout) 안에서 돈다.
func TestRunConfirmsConnectionBeforeVersionQuery(t *testing.T) {
	f := &fakeDeps{version: 170000}
	var out, errOut bytes.Buffer
	if code := run(context.Background(), envOf(nil), &out, &errOut, f.deps(t)); code != exitOK {
		t.Fatalf("코드 %d, want 0 — stderr %q", code, errOut.String())
	}
	if want := []string{"newPool", "confirm", "serverVersion"}; !slices.Equal(f.calls, want) {
		t.Errorf("바깥 호출 차례 = %v, want %v", f.calls, want)
	}
	if f.confirmBudget <= 0 || f.confirmBudget > versionQueryTimeout {
		t.Errorf("연결 확인 ctx 의 남은 기한 = %v, want 0 초과 %v 이하", f.confirmBudget, versionQueryTimeout)
	}
}

// 연결 확인이 실패하면 버전 조회 · 루트 · 정리기 없이 janitor_failed 한 줄로 종료 코드 1 이다.
func TestRunFailsWhenConnectionCheckFails(t *testing.T) {
	f := &fakeDeps{version: 170000, confirmErr: errors.New("DB Ping 실패(pool=janitor)")}
	var out, errOut bytes.Buffer
	code := run(context.Background(), envOf(nil), &out, &errOut, f.deps(t))
	if code != exitFailure || f.versions+f.roots+f.runs != 0 {
		t.Errorf("코드 %d · 버전 %d · 루트 %d · 실행 %d, want %d · 0 · 0 · 0", code, f.versions, f.roots, f.runs, exitFailure)
	}
	if n := len(find(lines(t, &out), "janitor_failed")); n != 1 {
		t.Errorf("janitor_failed %d줄, want 1", n)
	}
}

// 운영 조립(deployedDeps)의 연결 확인이 닿지 않는 DB 에서 db_connected 없이 실패한다 — 줄은 Ping 성공 뒤에만 남는다.
func TestRunDeployedLogsNoConnectedLineWhenDBUnreachable(t *testing.T) {
	d := deployedDeps()
	d.openRoot = func(string) (*os.Root, error) { return os.OpenRoot(t.TempDir()) }
	var out, errOut bytes.Buffer
	code := run(context.Background(), envOf(map[string]string{"POSTGRES_HOST": "127.0.0.1", "POSTGRES_PORT": "1"}), &out, &errOut, d)
	recs := lines(t, &out)
	if code != exitFailure || len(find(recs, pgconf.ConnectedEvent)) != 0 || len(find(recs, "janitor_failed")) != 1 {
		t.Errorf("코드 %d · %s %d줄 · janitor_failed %d줄, want %d · 0 · 1",
			code, pgconf.ConnectedEvent, len(find(recs, pgconf.ConnectedEvent)), len(find(recs, "janitor_failed")), exitFailure)
	}
	if strings.Contains(out.String(), "p@ss") || strings.Contains(out.String(), "p%40ss") {
		t.Error("로그에 비밀번호가 실렸다")
	}
}

// POK-311 실물 — 운영 조립(deployedDeps)으로 실 PG 에 붙으면 db_connected 한 줄(pool=janitor · 접속 값)이 janitor_started
// 앞에 남는다. 정리기 실행만 바꾼다(곧장 끝남).
func TestRunDeployedLogsDBConnectedOnRealServer(t *testing.T) {
	env := pgtest.PostgresEnv(t, "")
	env["JANITOR_ENABLED"] = "true"
	d := deployedDeps()
	d.openRoot = func(string) (*os.Root, error) { return os.OpenRoot(t.TempDir()) }
	d.runJanitor = func(context.Context, *janitor.Janitor) error { return context.Canceled }
	var out, errOut bytes.Buffer
	if code := run(context.Background(), func(k string) string { return env[k] }, &out, &errOut, d); code != exitOK {
		t.Fatalf("코드 %d, want 0 — stderr %q · 로그 %s", code, errOut.String(), out.String())
	}
	recs := lines(t, &out)
	got := find(recs, pgconf.ConnectedEvent)
	if len(got) != 1 {
		t.Fatalf("%s %d줄, want 1", pgconf.ConnectedEvent, len(got))
	}
	port, _ := strconv.Atoi(env["POSTGRES_PORT"])
	want := map[string]any{
		"level": "INFO", "pool": "janitor", "auth": "password", "sslmode": env["POSTGRES_SSLMODE"],
		"user": env["POSTGRES_USER"], "host": env["POSTGRES_HOST"], "port": float64(port), "db": env["POSTGRES_DB"],
	}
	if want["sslmode"] == "" {
		want["sslmode"] = "prefer" // PG_DSN 에 sslmode 가 없으면 기동 기본값
	}
	if want["sslmode"] == "disable" {
		want["tls"] = false // CI · 실험 PG 는 TLS 를 끈다 — 줄의 tls 가 실제 연결을 따르는지 본다
	}
	for k, v := range want {
		if got[0][k] != v {
			t.Errorf("%s[%s] = %v, want %v", pgconf.ConnectedEvent, k, got[0][k], v)
		}
	}
	at := map[string]int{}
	for i, r := range recs {
		at[r["msg"].(string)] = i
	}
	if at[pgconf.ConnectedEvent] > at["janitor_started"] {
		t.Errorf("%s 이 janitor_started 뒤에 있다", pgconf.ConnectedEvent)
	}
	if pw := env["POSTGRES_PASSWORD"]; pw != "" && strings.Contains(out.String(), pw) {
		t.Error("로그에 비밀번호가 실렸다")
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
	pc, err := poolConfig(context.Background(), cfg)
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
