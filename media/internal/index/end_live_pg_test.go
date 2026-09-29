package index

// index.EndLive 의 대기 상한과 트랜잭션 종료(POK-195 M4 PR ⓒ 커밋 6 — 계획 4.1 전이 · 체크리스트 459 B-2 6 ·
// 판단 J54). 전이의 1행 · 0행 효과와 술어는 session/end_session_pg_test.go 가 세 종료 문장을 대조하며 잰다. 여기는
// 전이가 루프를 얼마나 붙잡을 수 있는가(lock_timeout · 시한)와, 실패한 트랜잭션을 끝낸 뒤 풀 연결이 살아 있는가를
// 잰다. PG_DSN 미설정이면 전량 skip 된다(REQUIRE_PG=1 인 CI 가 실주행 게이트다).

import (
	"context"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
)

// offline_transition_bounded_by_lock_timeout(계획 4.1 대기 상한 · 뮤테이션 130) — 다른 트랜잭션이 live 회차 행을
// 잠그고 있으면 EndLive 는 lock_timeout(5초) 안에 55P03 오류로 돌아온다 — 기다림이 루프를 붙잡는 시간이 전이 한
// 번에 상한 안이다. 실패한 트랜잭션은 되돌려져 같은 풀의 다음 문장이 새 연결 없이 서고, 회차는 live 그대로다(서버가
// 커밋하지 않았다). 루프 쪽 처분(ERROR · 쉼)은 cmd/segment-indexer 의 루프 층 시험이 잰다(판단 J61).
func TestEndLiveBoundedByLockTimeout(t *testing.T) {
	pool := newTestPool(t)
	ctx := t.Context()
	stream := sessionStream("endlive-lock")
	putSession(t, pool, fixtureSession{id: stream + "-S", stream: stream, startedAt: sessionBase})

	holder, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("잠금 트랜잭션 시작 실패: %v", err)
	}
	defer func() { _ = holder.Rollback(context.WithoutCancel(ctx)) }()
	if _, err := holder.Exec(ctx, `SELECT 1 FROM stream_sessions WHERE session_id = $1 FOR UPDATE`, stream+"-S"); err != nil {
		t.Fatalf("회차 행 잠금 실패: %v", err)
	}
	if _, err := pool.Exec(ctx, "select 1"); err != nil { // 연결 하나를 세워 둔다
		t.Fatalf("풀 데우기 실패: %v", err)
	}
	conns := pool.Stat().NewConnsCount()

	type result struct {
		sessionID string
		err       error
	}
	done := make(chan result, 1)
	go func() {
		id, err := EndLive(ctx, pool, stream)
		done <- result{id, err}
	}()
	var got result
	select {
	case got = <-done:
	case <-time.After(8 * time.Second):
		t.Error("EndLive 가 잠긴 회차 앞에서 8초 안에 돌아오지 않았다 — 대기 상한(lock_timeout)이 없다")
		_ = holder.Rollback(ctx) // 잠금을 풀어 붙잡힌 전이를 끝낸다
		got = <-done
	}
	if got.sessionID != "" || !isLockTimeout(got.err) {
		t.Errorf("EndLive = (%q, %v), want (\"\", 55P03 lock_timeout 오류)", got.sessionID, got.err)
	}

	_ = holder.Rollback(ctx)
	if _, err := pool.Exec(ctx, "select 1"); err != nil { // 같은 풀의 다음 문장
		t.Errorf("실패한 전이 뒤 다음 문장 실패: %v", err)
	}
	if n := pool.Stat().NewConnsCount() - conns; n != 0 {
		t.Errorf("새 연결 %d개, want 0(되돌림이 연결을 버리지 않았다)", n)
	}
	if s := statesOf(sessionsOf(t, pool, stream)); len(s) != 1 || s[0] != "live" {
		t.Errorf("실패한 전이 뒤 회차 state = %v, want [live] — 서버가 커밋하지 않았다", s)
	}
}

// end_live_rolls_back_with_live_context(판단 J54 — TestLedgerLoadRollsBackWithLiveContext 와 같은 형) — 전이
// 트랜잭션은 EndLive 가 BEGIN 부터 끝까지 소유하고, 실패하면 되돌리는 순간에 만든 끝나지 않은 ctx(부른 쪽 ctx 에서
// 취소를 떼고 같은 시한)로 되돌린다. 끝난 ctx(시한이 지난 트랜잭션 ctx · 끝난 부른 쪽 ctx)로 보내면 pgx 가 그 연결을
// 버린다. 연결이 이미 선 풀에서 재므로 되돌림이 오류 없이 끝나고 새 연결 없이 같은 풀의 다음 문장이 선다. 시한은
// 문장 시한과 되돌림 시한이 같은 손잡이(비공개 endLive 의 시한 인자)로 200ms 로 줄인다 — 「문장_시한_지남」 행은
// 시한이 없는 구현이면 전이가 성공해 드러난다(뮤테이션 130 의 시한 쪽).
func TestEndLiveRollsBackWithLiveContext(t *testing.T) {
	cases := []struct {
		name string
		// inject 는 전이 문장을 실패시킨다 — 부른 쪽 ctx 의 cancel 을 받는다.
		inject func(ctx context.Context, cancel context.CancelFunc) context.Context
	}{
		{"문장_시한_지남", func(ctx context.Context, _ context.CancelFunc) context.Context {
			select { // 트랜잭션 시한이 지날 때까지 붙잡고(시한이 없는 구현이면 2초 뒤 놓아 시험이 멈추지 않는다)
			case <-ctx.Done():
			case <-time.After(2 * time.Second):
			}
			time.Sleep(100 * time.Millisecond) // BEGIN 때 만든 ctx 의 시한도 지나도록 늦게 돌아온다
			return ctx
		}},
		{"부른_쪽_ctx_끝남", func(ctx context.Context, cancel context.CancelFunc) context.Context {
			cancel()
			return ctx
		}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			base := newTestPool(t)
			stream := sessionStream("endlive-rollback")
			putSession(t, base, fixtureSession{id: stream + "-S", stream: stream, startedAt: sessionBase})
			ctx, cancel := context.WithCancel(t.Context())
			defer cancel()
			var rollbacks []error
			pool := hookedPool(t, base, traceSQLEnd{
				start: func(sctx context.Context, _ *pgx.Conn, sql string) context.Context {
					if sql == endLiveSQL {
						return tc.inject(sctx, cancel)
					}
					return sctx
				},
				end: func(sql string, err error) {
					if sql == "rollback" {
						rollbacks = append(rollbacks, err)
					}
				},
			})
			if _, err := pool.Exec(t.Context(), "select 1"); err != nil { // 연결 하나를 세워 둔다
				t.Fatalf("풀 데우기 실패: %v", err)
			}
			conns := pool.Stat().NewConnsCount()

			id, err := endLive(ctx, pool, stream, 200*time.Millisecond)

			if err == nil || id != "" {
				t.Errorf("endLive = (%q, %v), want (\"\", 오류) — 전이 문장이 실패했다", id, err)
			}
			if len(rollbacks) != 1 || rollbacks[0] != nil {
				t.Errorf("되돌림 결과 %v, want 한 번 · 오류 nil(끝나지 않은 ctx)", rollbacks)
			}
			if _, err := pool.Exec(t.Context(), "select 1"); err != nil { // 같은 풀의 다음 문장
				t.Errorf("되돌림 뒤 다음 문장 실패: %v", err)
			}
			if n := pool.Stat().NewConnsCount() - conns; n != 0 {
				t.Errorf("새 연결 %d개, want 0(되돌림이 연결을 버리지 않았다)", n)
			}
			if s := statesOf(sessionsOf(t, base, stream)); len(s) != 1 || s[0] != "live" {
				t.Errorf("실패한 전이 뒤 회차 state = %v, want [live] — 서버가 커밋하지 않았다", s)
			}
		})
	}
}

// end_live_reports_failure_at_every_statement(판단 J54 · 체크리스트 A-2 1 — 실패 = 결과 모름) — BEGIN · 상한 문장 ·
// COMMIT 어느 자리에서 실패해도 EndLive 는 오류를 돌려준다. 삼키면 루프가 0행으로 읽어 한 번 가드를 쓰고 다시
// 시도하지 않는다 — 회차가 live 로 남는다. 전이 문장 자리는 TestEndLiveRollsBackWithLiveContext 가 잰다. 실패는 그
// 문장 직전에 부른 쪽 ctx 를 끝내 만든다. 어느 자리든 서버는 커밋하지 않아 회차는 live 다. 되돌림 문장은 트랜잭션이
// 열린 뒤의 실패에만 나가고, COMMIT 오류 뒤에는 나가지 않는다(pgx 의 Commit 이 트랜잭션을 닫는다 — 미뤄 둔 되돌림은
// 문장 없이 끝난다).
func TestEndLiveReportsFailureAtEveryStatement(t *testing.T) {
	for _, tc := range []struct {
		name string
		// at 은 부른 쪽 ctx 를 끝낼 문장이다.
		at            string
		wantRollbacks int
	}{
		{"BEGIN", "begin", 0},
		{"상한_문장", setTxnLimitsSQL, 1},
		{"COMMIT", "commit", 0},
	} {
		t.Run(tc.name, func(t *testing.T) {
			base := newTestPool(t)
			stream := sessionStream("endlive-fail")
			putSession(t, base, fixtureSession{id: stream + "-S", stream: stream, startedAt: sessionBase})
			ctx, cancel := context.WithCancel(t.Context())
			defer cancel()
			rollbacks := 0
			pool := hookedPool(t, base, traceSQLEnd{
				start: func(sctx context.Context, _ *pgx.Conn, sql string) context.Context {
					if sql == tc.at {
						cancel()
					}
					return sctx
				},
				end: func(sql string, _ error) {
					if sql == "rollback" {
						rollbacks++
					}
				},
			})

			id, err := EndLive(ctx, pool, stream)

			if err == nil || id != "" {
				t.Errorf("EndLive = (%q, %v), want (\"\", 오류) — %s 에서 실패했다", id, err, tc.name)
			}
			if rollbacks != tc.wantRollbacks {
				t.Errorf("되돌림 문장 %d번, want %d", rollbacks, tc.wantRollbacks)
			}
			if s := statesOf(sessionsOf(t, base, stream)); len(s) != 1 || s[0] != "live" {
				t.Errorf("실패한 전이 뒤 회차 state = %v, want [live] — 서버가 커밋하지 않았다", s)
			}
		})
	}
}

// statesOf 는 회차들의 state 다(개시 순).
func statesOf(rows []sessionRow) []string {
	var out []string
	for _, r := range rows {
		out = append(out, r.State)
	}
	return out
}
