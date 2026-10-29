package index

// 정산 보조 문장 셋(FreezeEmptyDVR · FreezeEmptyVOD · FailChainVOD)의 잠금 상한(POK-196 M6-3b 커밋 ④c — c3 db M2). 셋은
// 트랜잭션 지역 상한(setTxnLimitsSQL — lock_timeout 5초) 아래 돌고, 잠금 경합으로 끊기면 ErrTopologyContended 로 접혀
// 루프가 finalize_step_failed 를 되풀이하지 않는다. 경합은 index 가 WARN topology_lock_timeout op 한 줄로 남긴다.

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// warnOps 는 topology_lock_timeout 줄의 op 와 그 줄의 session_id 를 모으는 로그 처리기다.
type warnOps struct {
	mu       sync.Mutex
	ops      []string
	sessions map[string]string // op → session_id
}

func (w *warnOps) Enabled(context.Context, slog.Level) bool { return true }
func (w *warnOps) WithAttrs([]slog.Attr) slog.Handler       { return w }
func (w *warnOps) WithGroup(string) slog.Handler            { return w }
func (w *warnOps) Handle(_ context.Context, r slog.Record) error {
	if r.Message != "topology_lock_timeout" || r.Level != slog.LevelWarn {
		return nil
	}
	var op, session string
	r.Attrs(func(a slog.Attr) bool {
		switch a.Key {
		case "op":
			op = a.Value.String()
		case "session_id":
			session = a.Value.String()
		}
		return true
	})
	w.mu.Lock()
	defer w.mu.Unlock()
	w.ops = append(w.ops, op)
	if w.sessions == nil {
		w.sessions = map[string]string{}
	}
	w.sessions[op] = session
	return nil
}

// 다른 연결이 세션 행을 쥐고 있으면 셋 모두 lock_timeout(5초)에 끊겨 ErrTopologyContended 로 접힌다 — ctx 시한(10초)을
// 다 쓰지 않는다. WARN 줄은 session_id 칸에 그 세션(VOD 는 사슬 꼬리)을 싣는다 — 같은 메시지의 다른 줄과 같은 키라
// session_id 로 거르는 질의가 놓치지 않는다(db ④c L5). 변이 = 상한 설정 제거 · 접기 제거 · 키 이름 → 각각 FAIL.
//
// 세 호출은 호출 수만큼 연결을 가진 풀에서 함께 돈다 — 시험 풀은 DSN · 러너 CPU 수(pgx 기본 max(4, CPU))에 따라 작을 수
// 있고, 그러면 풀 획득 대기가 함수의 10초 시한을 먹어 잠금 상한이 아니라 시한으로 끝난다(PR 461 r1 cc H1 — CI 풀 4).
func TestAuxEndingStatementsFoldLockTimeout(t *testing.T) {
	pool := newTestPool(t)
	old := time.Now().Add(-time.Hour)
	const s = "aux-lock"
	putEnd(t, pool, endRow{id: "D", stream: s, startedAt: old})
	putEnd(t, pool, endRow{id: "V", stream: s, dvr: "frozen", vod: "freezing", round: "RV", startedAt: old.Add(time.Minute)})
	putEnd(t, pool, endRow{id: "F", stream: s, dvr: "frozen", vod: "freezing", round: "RF", startedAt: old.Add(2 * time.Minute)})
	for _, id := range []string{"D", "V", "F"} {
		holdSession(t, pool, id)
	}
	cfg := pool.Config()
	cfg.MaxConns = 3 // 아래 호출 셋
	callPool, err := pgxpool.NewWithConfig(context.Background(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(callPool.Close)
	w := &warnOps{}
	o := testClose
	o.Log = slog.New(w)
	calls := map[string]func(ctx context.Context) error{
		"freeze_empty_dvr": func(ctx context.Context) error { _, err := FreezeEmptyDVR(ctx, callPool, "D", o); return err },
		"freeze_empty_vod": func(ctx context.Context) error {
			_, err := FreezeEmptyVOD(ctx, callPool, Chain{StreamID: s, RoundID: "RV", Head: "V", Tail: "V", SessionIDs: []string{"V"}}, o)
			return err
		},
		"fail_chain_vod": func(ctx context.Context) error {
			_, err := FailChainVOD(ctx, callPool, Chain{StreamID: s, RoundID: "RF", Head: "F", Tail: "F", SessionIDs: []string{"F"}}, o)
			return err
		},
	}
	var wg sync.WaitGroup
	for op, call := range calls {
		wg.Go(func() {
			start := time.Now()
			err := call(context.Background())
			if took := time.Since(start); !errors.Is(err, ErrTopologyContended) || took > 8*time.Second {
				t.Errorf("%s = %v (%v), want ErrTopologyContended 를 lock_timeout 5초 근처에", op, err, took)
			}
		})
	}
	wg.Wait()
	w.mu.Lock()
	defer w.mu.Unlock()
	if len(w.ops) != len(calls) {
		t.Errorf("topology_lock_timeout op = %v, want 셋 각 한 줄", w.ops)
	}
	for op, want := range map[string]string{"freeze_empty_dvr": "D", "freeze_empty_vod": "V", "fail_chain_vod": "F"} {
		if got := w.sessions[op]; got != want {
			t.Errorf("topology_lock_timeout op=%s 의 session_id = %q, want %q", op, got, want)
		}
	}
}
