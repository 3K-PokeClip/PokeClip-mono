package index

// L3 · L2′ 종료 문장(POK-196 M6-3b 커밋 ④ · ④b — 설계 M6 3.1 「gap_run(L3)」 · 6절 L3 · L2′ 행). 소유 회차에서 L2 GAP 이 seq 로 이어서 셋 선
// 뒤 루프가 그 회차를 ending 으로 보낸다. EndLive 와 같은 꼴이다(상한 → 위상 잠금 → live 행) — 다른 점은 사유 gap_run 과,
// 셋을 낸 그 회차가 아직 그 스트림의 live 일 때만 끝낸다는 술어다.

import (
	"context"
	"slices"
	"testing"
	"time"
)

// L3 — 셋을 낸 회차가 live 면 사유 gap_run 으로 ending 이다(회차는 열린 채 — 재접속 창 안의 새 조각이 합류 · 계승한다).
// 그 사이 live 가 다른 회차로 바뀌었으면(TD 분할 등) 아무것도 쓰지 않는다. 표식이 있는 live 면 EndLive 와 같은 CASE 로 사유
// intentional 이고 같은 트랜잭션에서 회차를 닫는다. 변이 = 회차 술어 제거 → 바뀐 live 를 끝내 FAIL · 사유 글자 offline → FAIL.
func TestEndGapRunEndsOnlyTheGappedLiveSession(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	now := time.Now()
	putEnd(t, pool, endRow{id: "G", stream: "gr-a", state: "live", round: "RG", startedAt: now.Add(-time.Hour)})
	putEnd(t, pool, endRow{id: "N", stream: "gr-b", state: "live", round: "RN", startedAt: now.Add(-time.Hour)})
	putEnd(t, pool, endRow{id: "M", stream: "gr-c", state: "live", round: "RM", stopMarkedAt: now.Add(-time.Second), startedAt: now.Add(-time.Hour)})

	var rec recordEnqueue
	if id, err := EndGapRun(ctx, pool, "gr-a", "G", testClose, rec.fn); err != nil || id != "G" {
		t.Fatalf("EndGapRun(G) = (%q, %v), want (G, nil)", id, err)
	}
	if id, err := EndGapRun(ctx, pool, "gr-b", "OLD", testClose, rec.fn); err != nil || id != "" {
		t.Fatalf("EndGapRun(바뀐 live) = (%q, %v), want (\"\", nil) — 셋을 낸 회차가 아니면 쓰지 않는다", id, err)
	}
	if id, err := EndGapRun(ctx, pool, "gr-c", "M", testClose, rec.fn); err != nil || id != "M" {
		t.Fatalf("EndGapRun(표식) = (%q, %v), want (M, nil)", id, err)
	}

	for _, c := range []struct {
		id, state, reason string
		closed            bool
	}{
		{"G", "ending", "gap_run", false},
		{"N", "live", "", false},
		{"M", "ending", "intentional", true},
	} {
		var state, reason string
		var closed bool
		if err := pool.QueryRow(ctx, `SELECT state, COALESCE(end_reason, ''), round_closed_at IS NOT NULL FROM stream_sessions WHERE session_id = $1`,
			c.id).Scan(&state, &reason, &closed); err != nil {
			t.Fatal(err)
		}
		if state != c.state || reason != c.reason || closed != c.closed {
			t.Errorf("%s = (%s, %q, 회차 닫힘 %v), want (%s, %q, %v)", c.id, state, reason, closed, c.state, c.reason, c.closed)
		}
	}
	if got := rec.got(); !slices.Equal(got, []string{"RM"}) {
		t.Errorf("완결 평가 = %q, want [RM](표식 갈래만)", got)
	}
}

// L2′ 종료(POK-196 M6-3b 커밋 ④b — kty 2026-10-27 결정 (나)) — 봉인이 선 회차가 아직 live 면 사유 stall_seal 로 ending 이다.
// 꼴은 L3 와 같다(회차 술어 · 표식이면 intentional · 같은 트랜잭션 회차 닫힘). 변이 = 사유 글자 gap_run · 회차 술어 제거 → FAIL.
func TestEndStallSealEndsOnlyTheSealedLiveSession(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	now := time.Now()
	putEnd(t, pool, endRow{id: "Z", stream: "ss-a", state: "live", round: "RZ", dvr: "frozen", startedAt: now.Add(-time.Hour)})
	putEnd(t, pool, endRow{id: "Y", stream: "ss-b", state: "live", round: "RY", startedAt: now.Add(-time.Hour)})
	putEnd(t, pool, endRow{id: "X", stream: "ss-c", state: "live", round: "RX", stopMarkedAt: now.Add(-time.Second), startedAt: now.Add(-time.Hour)})

	var rec recordEnqueue
	if id, err := EndStallSeal(ctx, pool, "ss-a", "Z", testClose, rec.fn); err != nil || id != "Z" {
		t.Fatalf("EndStallSeal(Z) = (%q, %v), want (Z, nil)", id, err)
	}
	if id, err := EndStallSeal(ctx, pool, "ss-b", "OLD", testClose, rec.fn); err != nil || id != "" {
		t.Fatalf("EndStallSeal(바뀐 live) = (%q, %v), want (\"\", nil)", id, err)
	}
	if id, err := EndStallSeal(ctx, pool, "ss-c", "X", testClose, rec.fn); err != nil || id != "X" {
		t.Fatalf("EndStallSeal(표식) = (%q, %v), want (X, nil)", id, err)
	}
	for _, c := range []struct{ id, state, reason string }{{"Z", "ending", "stall_seal"}, {"Y", "live", ""}, {"X", "ending", "intentional"}} {
		var state, reason string
		if err := pool.QueryRow(ctx, `SELECT state, COALESCE(end_reason, '') FROM stream_sessions WHERE session_id = $1`, c.id).Scan(&state, &reason); err != nil {
			t.Fatal(err)
		}
		if state != c.state || reason != c.reason {
			t.Errorf("%s = (%s, %q), want (%s, %q)", c.id, state, reason, c.state, c.reason)
		}
	}
	if got := rec.got(); !slices.Equal(got, []string{"RX"}) {
		t.Errorf("완결 평가 = %q, want [RX](표식 갈래만)", got)
	}
}
