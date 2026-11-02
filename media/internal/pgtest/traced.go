package pgtest

import (
	"context"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// TracedPool 은 pool 과 같은 테스트 DB 에 붙되 문장마다 tracer 를 거치는 새 풀이다(pool.Config 는 사본이라
// pool 은 그대로다). tracer 가 nil 이면 추적 없이 아래 고정 설정만 건다. 풀은 t.Cleanup 이 닫는다.
//
// 새 연결 수(Stat().NewConnsCount())로 판정하는 시험은 이 풀을 쓴다 — 시험이 재는 동작 말고는 연결을 세우거나
// 버리지 않게 풀 설정을 못 박는다. PG_DSN 에 pool_min_conns · pool_max_conn_lifetime · pool_health_check_period
// 같은 매개변수가 있어도 새 연결 수를 흔들지 않는다. 최소 연결 수 0 은 배경에서 서는 연결을 막는다. 수명(지터 0) ·
// 유휴 한도 · 점검 주기는 시험 한 번보다 훨씬 길게 둔다. 수명이 다한 연결은 반납 · 획득 · 점검 때 버려지고, 점검은
// 한도보다 오래 논 연결을 버린다. 점검이 도는 동안은 유휴 연결을 모두 빌려 가 있어서 그 사이의 획득이 새 연결을
// 세운다. 그래서 이 풀은 버린 연결을 다음 문장이 나갈 때에야 다시 세운다.
func TracedPool(ctx context.Context, t *testing.T, pool *pgxpool.Pool, tracer pgx.QueryTracer) *pgxpool.Pool {
	t.Helper()
	cfg := pool.Config()
	cfg.ConnConfig.Tracer = tracer
	cfg.MinConns, cfg.MinIdleConns = 0, 0
	cfg.MaxConnLifetime, cfg.MaxConnLifetimeJitter = time.Hour, 0
	cfg.MaxConnIdleTime, cfg.HealthCheckPeriod = time.Hour, time.Hour
	traced, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatalf("추적 풀 생성 실패: %v", err)
	}
	t.Cleanup(traced.Close)
	return traced
}
