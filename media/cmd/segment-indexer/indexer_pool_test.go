package main

// 인덱서 풀 기동(openIndexerPool)의 시험(POK-311) — run 이 DB 에 붙는 첫 단계다. 성공하면 db_connected 한 줄(배포 확인의 판정
// 줄)을 남기고, 발행자 풀이 Copy 해 쓸 풀 설정을 돌려준다. 실패하면 줄 없이 오류다(사이드카 기동 실패 → 재기동이 회복).

import (
	"log/slog"
	"strconv"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgconf"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// settingsOf 는 env 를 기동 경로(pgconf.FromEnv)로 지난 DB 설정이다.
func settingsOf(t *testing.T, env map[string]string) pgconf.Settings {
	t.Helper()
	s, err := pgconf.FromEnv(func(k string) string { return env[k] })
	if err != nil {
		t.Fatalf("POSTGRES_* 설정 실패: %v", err)
	}
	return s
}

// 닿지 않는 DB 면 db_connected 없이 오류다. 오류에 비밀번호가 실리지 않는다.
func TestOpenIndexerPoolFailsWithoutConnectedLine(t *testing.T) {
	const secret = "c2-canary-pw"
	s := settingsOf(t, map[string]string{
		"POSTGRES_USER": "u", "POSTGRES_PASSWORD": secret, "POSTGRES_DB": "d",
		"POSTGRES_HOST": "127.0.0.1", "POSTGRES_PORT": "1", "POSTGRES_SSLMODE": "disable",
	})
	logs := &logCapture{}

	pool, base, err := openIndexerPool(t.Context(), s, slog.New(logs))

	if err == nil || pool != nil || base != nil {
		t.Fatalf("openIndexerPool = 풀 %v · 설정 %v · 오류 %v, want 오류만", pool, base, err)
	}
	if n := logs.count(pgconf.ConnectedEvent); n != 0 {
		t.Errorf("%s %d줄, want 0 — 줄은 Ping 성공 뒤에만 남는다", pgconf.ConnectedEvent, n)
	}
	if strings.Contains(err.Error(), secret) {
		t.Error("오류에 비밀번호가 실렸다")
	}
}

// 실 PG — 인덱서 풀이 붙으면 db_connected 한 줄(pool=indexer · 접속 값)을 남기고, 돌려준 풀 설정의 Copy 로 만든 두 번째 풀
// (발행자 풀 자리)도 같은 DB 에 붙는다.
func TestOpenIndexerPoolLogsDBConnected(t *testing.T) {
	db := newWireTestPool(t)
	env := pgtest.PostgresEnv(t, db.Config().ConnConfig.Database)
	s := settingsOf(t, env)
	logs := &logCapture{}

	pool, base, err := openIndexerPool(t.Context(), s, slog.New(logs))
	if err != nil {
		t.Fatalf("openIndexerPool: %v", err)
	}
	t.Cleanup(pool.Close)

	if n := logs.count(pgconf.ConnectedEvent); n != 1 {
		t.Fatalf("%s %d줄, want 1", pgconf.ConnectedEvent, n)
	}
	got := logs.attrs(pgconf.ConnectedEvent)
	port, _ := strconv.Atoi(env["POSTGRES_PORT"])
	want := map[string]any{
		"pool": "indexer", "auth": "password", "sslmode": s.SSLMode,
		"user": env["POSTGRES_USER"], "host": env["POSTGRES_HOST"], "port": int64(port), "db": env["POSTGRES_DB"],
	}
	if s.SSLMode == "disable" {
		want["tls"] = false
	}
	for k, v := range want {
		if got[k] != v {
			t.Errorf("%s[%s] = %v(%T), want %v(%T)", pgconf.ConnectedEvent, k, got[k], got[k], v, v)
		}
	}
	if base == nil || base.ConnConfig.Database != env["POSTGRES_DB"] {
		t.Fatalf("돌려준 풀 설정 = %v, want 전용 테스트 DB 설정", base)
	}
	second, err := pgxpool.NewWithConfig(t.Context(), base.Copy())
	if err != nil {
		t.Fatalf("돌려준 풀 설정의 Copy 로 풀 생성 실패: %v", err)
	}
	t.Cleanup(second.Close)
	if err := second.Ping(t.Context()); err != nil {
		t.Errorf("돌려준 풀 설정의 Copy 로 만든 풀(발행자 풀 자리)이 붙지 못했다: %v", err)
	}
}
