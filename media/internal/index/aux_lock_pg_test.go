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
)

// warnOps 는 topology_lock_timeout 줄의 op 를 모으는 로그 처리기다.
type warnOps struct {
	mu  sync.Mutex
	ops []string
}

func (w *warnOps) Enabled(context.Context, slog.Level) bool { return true }
func (w *warnOps) WithAttrs([]slog.Attr) slog.Handler       { return w }
func (w *warnOps) WithGroup(string) slog.Handler            { return w }
func (w *warnOps) Handle(_ context.Context, r slog.Record) error {
	if r.Message != "topology_lock_timeout" || r.Level != slog.LevelWarn {
		return nil
	}
	r.Attrs(func(a slog.Attr) bool {
		if a.Key == "op" {
			w.mu.Lock()
			w.ops = append(w.ops, a.Value.String())
			w.mu.Unlock()
		}
		return true
	})
	return nil
}

// 다른 연결이 세션 행을 쥐고 있으면 셋 모두 lock_timeout(5초)에 끊겨 ErrTopologyContended 로 접힌다 — ctx 시한(10초)을
// 다 쓰지 않는다. 변이 = 상한 설정 제거 · 접기 제거 → 각각 FAIL.
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
	w := &warnOps{}
	o := testClose
	o.Log = slog.New(w)
	calls := map[string]func(ctx context.Context) error{
		"freeze_empty_dvr": func(ctx context.Context) error { _, err := FreezeEmptyDVR(ctx, pool, "D", o); return err },
		"freeze_empty_vod": func(ctx context.Context) error {
			_, err := FreezeEmptyVOD(ctx, pool, Chain{StreamID: s, RoundID: "RV", Head: "V", Tail: "V", SessionIDs: []string{"V"}}, o)
			return err
		},
		"fail_chain_vod": func(ctx context.Context) error {
			_, err := FailChainVOD(ctx, pool, Chain{StreamID: s, RoundID: "RF", Head: "F", Tail: "F", SessionIDs: []string{"F"}}, o)
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
}
