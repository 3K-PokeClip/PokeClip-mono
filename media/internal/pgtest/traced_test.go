package pgtest

import (
	"context"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// nopTracer 는 값으로 구별되는 아무 일도 하지 않는 추적기다.
type nopTracer struct{ name string }

func (*nopTracer) TraceQueryStart(ctx context.Context, _ *pgx.Conn, _ pgx.TraceQueryStartData) context.Context {
	return ctx
}

func (*nopTracer) TraceQueryEnd(context.Context, *pgx.Conn, pgx.TraceQueryEndData) {}

// TracedPool 은 PG_DSN 의 풀 매개변수를 덮고 고정 설정과 받은 추적기를 건다. 원래 풀의 여섯 값과 추적기를
// 모두 고정값과 다르게 둔다 — 한 줄이라도 빠지면 드러나게. DB 는 없어도 된다: 원래 풀은 닫힌 포트로 최소
// 연결을 세우려다 실패할 뿐이고, 고정 설정 풀은 최소 연결 수가 0 이라 연결을 세우지 않는다.
func TestTracedPoolFixesPoolSettings(t *testing.T) {
	const dsn = "postgres://u:p@127.0.0.1:1/x?pool_min_conns=2&pool_min_idle_conns=1" +
		"&pool_max_conn_lifetime=1s&pool_max_conn_lifetime_jitter=1s" +
		"&pool_max_conn_idle_time=1s&pool_health_check_period=200ms"
	for _, tc := range []struct {
		name   string
		tracer pgx.QueryTracer
	}{
		{"with_tracer", &nopTracer{name: "traced"}},
		{"nil_tracer", nil},
	} {
		t.Run(tc.name, func(t *testing.T) {
			cfg, err := pgxpool.ParseConfig(dsn)
			if err != nil {
				t.Fatalf("DSN 파싱 실패: %v", err)
			}
			cfg.ConnConfig.Tracer = &nopTracer{name: "original"}
			pool, err := pgxpool.NewWithConfig(context.Background(), cfg)
			if err != nil {
				t.Fatalf("원래 풀 생성 실패: %v", err)
			}
			t.Cleanup(pool.Close)

			got := TracedPool(context.Background(), t, pool, tc.tracer).Config()

			if got.MinConns != 0 || got.MinIdleConns != 0 {
				t.Errorf("최소 연결 · 최소 유휴 = %d · %d, want 0 · 0", got.MinConns, got.MinIdleConns)
			}
			if got.MaxConnLifetime != time.Hour || got.MaxConnLifetimeJitter != 0 {
				t.Errorf("수명 · 지터 = %v · %v, want 1h · 0", got.MaxConnLifetime, got.MaxConnLifetimeJitter)
			}
			if got.MaxConnIdleTime != time.Hour || got.HealthCheckPeriod != time.Hour {
				t.Errorf("유휴 한도 · 점검 주기 = %v · %v, want 1h · 1h", got.MaxConnIdleTime, got.HealthCheckPeriod)
			}
			if got.ConnConfig.Tracer != tc.tracer {
				t.Errorf("추적기 = %v, want %v(받은 것)", got.ConnConfig.Tracer, tc.tracer)
			}
		})
	}
}
