package index

// 주조 트랜잭션 되돌림의 PG 통합 검증 — 실패한 트랜잭션을 끝나지 않은 ctx 로 되돌려 연결을 지키는가.
// PG_DSN 미설정이면 전량 skip 된다(REQUIRE_PG=1 인 CI 가 실주행 게이트다).

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
	"github.com/jackc/pgx/v5"
)

// rollbackProbe 는 문장마다 부르는 추적기다(pgx.QueryTracer). 주입 문장(injectSQL)이 서버로 나가기 전에
// inject 로 그 문장의 ctx 를 끝내고, 되돌림 문장("rollback" — pgx 가 Rollback 에서 보내는 문장)이 나간 ctx 와
// 결과를 적는다. 적기만 하고 판정은 시험 본문이 한다. pgx 는 문장을 보내는 고루틴에서 추적기를 부르므로 이
// 시험에서는 전부 시험 고루틴이다.
type rollbackProbe struct {
	// injectSQL 은 ctx 를 끝낼 문장이고, bound 는 그 문장 ctx 에 지금부터 있어야 할 시한의 상한이다.
	injectSQL string
	bound     time.Duration
	inject    func(ctx context.Context) context.Context

	injects  int       // 주입 횟수
	failedAt time.Time // 주입이 돌아온 시각 — 트랜잭션이 실패한 시각
	// unbounded 는 주입 문장 ctx 에 지금부터 bound 이내의 시한이 없어 주입하지 않았다는 표시다 —
	// 끝을 기다리는 주입이 그런 ctx 를 붙잡으면 시험이 -timeout 까지 매달린다.
	unbounded bool

	rollbackStarts   int       // 되돌림 문장 시작 횟수
	rollbackAt       time.Time // 되돌림 문장 시작 시각
	rollbackDeadline time.Time // 되돌림 문장 ctx 의 시한(hasDeadline 이 거짓이면 영값)
	hasDeadline      bool
	rollbackCaller   any // 되돌림 문장 ctx 의 callerValueKey 값 — 부른 쪽 ctx 의 값을 물려받았는가
	// rollbackErrs 는 되돌림 문장 끝마다의 결과 오류다. 시작과 따로 센다 — 무효 문장 해제가 먼저 실패하면
	// pgx 는 끝을 부르지 않고 돌아온다.
	rollbackErrs []error
}

// callerValueKey 는 시험이 부른 쪽 ctx 에 심는 값의 키다 — 되돌림 ctx 가 그 값을 물려받는지 잰다.
type callerValueKey struct{}

// probeSQLKey 는 문장 시작이 ctx 에 적어 끝에서 읽는 그 문장이다 — pgx 는 시작이 돌려준 ctx 로 끝을 부르고,
// 끝에는 SQL 을 주지 않는다.
type probeSQLKey struct{}

func (p *rollbackProbe) TraceQueryStart(ctx context.Context, _ *pgx.Conn, d pgx.TraceQueryStartData) context.Context {
	switch d.SQL {
	case p.injectSQL:
		if deadline, ok := ctx.Deadline(); !ok || time.Until(deadline) > p.bound {
			p.unbounded = true // 주입하지 않는다 — 판정은 시험 본문이 한다
			break
		}
		ctx = p.inject(ctx)
		p.injects++
		p.failedAt = time.Now()
	case "rollback":
		p.rollbackStarts++
		p.rollbackAt = time.Now()
		p.rollbackDeadline, p.hasDeadline = ctx.Deadline()
		p.rollbackCaller = ctx.Value(callerValueKey{})
	}
	return context.WithValue(ctx, probeSQLKey{}, d.SQL)
}

func (p *rollbackProbe) TraceQueryEnd(ctx context.Context, _ *pgx.Conn, d pgx.TraceQueryEndData) {
	if sql, _ := ctx.Value(probeSQLKey{}).(string); sql == "rollback" {
		p.rollbackErrs = append(p.rollbackErrs, d.Err)
	}
}

// 되돌림 ctx — 실패한 주조 트랜잭션은 되돌리는 순간에 만든 끝나지 않은 ctx 로 되돌린다. 끝난 ctx 로 되돌리면
// pgx 는 ROLLBACK 을 보내지 못하고 그 연결을 버린다. 두 갈래 모두 주조 문장이 서버로 나가기 전에 ctx 가
// 끝나므로(문장과 문장 사이), 서버의 트랜잭션은 되돌림을 받을 수 있는 채로 남는다. 연결이 이미 선(데운) 풀에서
// 재므로 새 연결이 섰다면 되돌림이 연결을 버린 것이다. 되돌림 ctx 의 시한은 되돌리는 순간 + TxnDeadline 이다 —
// 실패 시각과 되돌림 문장 시작 시각 사이에 만들었어야 그 구간에 든다(단조 시계끼리의 비교라 허용 오차가 없다).
//
// txn_deadline_spent 는 진짜 TxnDeadline(10초)이 지나기를 기다린다 — 상수라 줄일 수 없다.
func TestInsertRollsBackWithLiveContext(t *testing.T) {
	cases := []struct {
		name string
		// inject 는 주조 문장의 ctx 를 끝낸다 — 부른 쪽 ctx 의 cancel 을 받는다. 추적기는 그 ctx 에
		// TxnDeadline 이내의 시한이 있을 때만 부르므로 끝을 기다려도 매달리지 않는다.
		inject  func(ctx context.Context, cancel context.CancelFunc) context.Context
		wantErr error
	}{
		{
			name: "txn_deadline_spent",
			inject: func(ctx context.Context, _ context.CancelFunc) context.Context {
				<-ctx.Done() // 트랜잭션 시한(TxnDeadline)이 지날 때까지 붙잡는다 — 부른 쪽 ctx 는 살아 있다
				return ctx
			},
			wantErr: context.DeadlineExceeded,
		},
		{
			name: "caller_canceled",
			inject: func(ctx context.Context, cancel context.CancelFunc) context.Context {
				cancel() // 부른 쪽이 취소했다 — 트랜잭션 ctx 도 함께 끝난다
				return ctx
			},
			wantErr: context.Canceled,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.WithValue(t.Context(), callerValueKey{}, tc.name))
			defer cancel()
			probe := &rollbackProbe{injectSQL: insertSeedSQL, bound: TxnDeadline, inject: func(sctx context.Context) context.Context {
				return tc.inject(sctx, cancel)
			}}
			pool := pgtest.TracedPool(t.Context(), t, newTestPool(t), probe)
			if _, err := pool.Exec(t.Context(), "select 1"); err != nil { // 연결 하나를 세워 둔다
				t.Fatalf("풀 데우기 실패: %v", err)
			}
			conns := pool.Stat().NewConnsCount()
			stream := sessionStream("rollback-" + tc.name)

			// 세션 개시 갈래다 — 주입 전에 상한 설정 · 세션 INSERT · 되읽기가 이미 서버에 나가 있다.
			_, _, err := newSessionStore(pool).Insert(ctx, segAt(stream, 0, sessionBase, 4000), Seed{}, liveIngress())

			if probe.unbounded {
				t.Fatalf("주조 문장 ctx 시한(문장 시작부터 %v 이내) = 없음, want 있음 — 트랜잭션 상한이 주조 문장에 걸리지 않아 주입하지 않았다",
					probe.bound)
			}
			if probe.injects != 1 {
				t.Fatalf("주조 문장 주입 = %d번, want 1번 — 주입이 없으면 아래 단언은 아무것도 재지 않는다", probe.injects)
			}
			if !errors.Is(err, tc.wantErr) {
				t.Errorf("Insert() 오류 = %v, want %v", err, tc.wantErr)
			}
			if probe.rollbackStarts != 1 || len(probe.rollbackErrs) != 1 || probe.rollbackErrs[0] != nil {
				t.Errorf("되돌림 문장 = 시작 %d번 · 끝 %v, want 시작 1번 · 끝 [<nil>](끝나지 않은 ctx)",
					probe.rollbackStarts, probe.rollbackErrs)
			}
			if got := sessionsOf(t, pool, stream); len(got) != 0 { // 같은 풀의 다음 문장
				t.Errorf("sessionsOf(%q) = %d개, want 0개(되돌렸다)", stream, len(got))
			}
			// 새 연결은 위 문장이 선 뒤에 센다 — 최소 연결 수 0 인 풀은 버린 연결을 스스로 채우지 않는다.
			if n := pool.Stat().NewConnsCount() - conns; n != 0 {
				t.Errorf("새 연결 = %d개, want 0개(되돌림이 연결을 버리지 않았다)", n)
			}
			d, lo, hi := probe.rollbackDeadline, probe.failedAt.Add(TxnDeadline), probe.rollbackAt.Add(TxnDeadline)
			switch {
			case probe.rollbackStarts != 1:
				// ② 가 이미 알렸다 — 잴 되돌림 문장이 없거나 하나로 정해지지 않는다
			case !probe.hasDeadline:
				t.Errorf("되돌림 ctx 시한 = 없음, want 실패 뒤 [%v, %v](되돌리는 순간 + TxnDeadline)",
					TxnDeadline, hi.Sub(probe.failedAt))
			case d.Before(lo) || d.After(hi):
				t.Errorf("되돌림 ctx 시한 = 실패 뒤 %v, want 실패 뒤 [%v, %v](되돌리는 순간 + TxnDeadline)",
					d.Sub(probe.failedAt), TxnDeadline, hi.Sub(probe.failedAt))
			}
			if probe.rollbackStarts == 1 && probe.rollbackCaller != tc.name {
				t.Errorf("되돌림 ctx 의 부른 쪽 값 = %v, want %q(부른 쪽 ctx 의 값을 물려받는다)", probe.rollbackCaller, tc.name)
			}
		})
	}
}
