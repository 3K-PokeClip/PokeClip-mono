package janitor

// ③ 포기 갈래의 신호 둘(POK-196 M6-4 c3 · 설계 M6 7절 X5 · D52) — 포기 갈래로 지운 행의 WARN
// local_abandoned_reclaimed 와, ② 가 안 올라 지울 수 없는 포기 행이 있을 때의 게이지 abandoned_local_retained.

import (
	"errors"
	"testing"
)

// 포기 갈래로 고른 행(Abandoned)을 지우면 WARN 한 줄이 스트림 · seq 와 함께 나온다. 보통 행은 나오지 않는다.
func TestAbandonedRowDeleteWarns(t *testing.T) {
	r, warn := warnRig(t, nil)
	k := r.row("s1", 1, false, true)
	r.ledger.markAbandoned(k)
	r.row("s1", 2, false, true)
	r.cycle(t)

	recs := warn.all(abandonedReclaimedName)
	if len(recs) != 1 {
		t.Fatalf("%s 줄 %d (1 기대)", abandonedReclaimedName, len(recs))
	}
	if recs[0].attrs["stream_id"] != "s1" || recs[0].attrs["seq"] != "1" {
		t.Fatalf("속성 %v (stream_id=s1 seq=1 기대)", recs[0].attrs)
	}
	if _, ok := r.ledger.pathOf(k); ok {
		t.Fatal("포기 행의 local_path 가 남았다")
	}
}

// ② 가 안 올라 지울 수 없는 포기 행이 있으면 게이지를 켜고, 0 이 되면 끈다 — 둘 다 WARN 수집에서 보인다.
func TestAbandonedLocalRetainedGaugeToggles(t *testing.T) {
	r, warn := warnRig(t, nil)
	r.ledger.retained = 2
	r.cycle(t)
	r.ledger.retained = 0
	r.cycle(t)
	assertToggled(t, warn, abandonedRetainedName)
	if rec := warn.all(abandonedRetainedName); len(rec) < 2 || rec[1].attrs["rows"] != "2" {
		t.Fatalf("게이지 줄 %v (set 줄에 rows=2 기대)", rec)
	}
}

// 잔존 집계가 실패하면 그 주기는 실패로 끝난다(삭제로 넘어가지 않는다) — 다른 조회 실패와 같은 분기다.
func TestAbandonedLocalRetainedQueryFailureAbortsCycle(t *testing.T) {
	r, warn := warnRig(t, nil)
	r.row("s1", 1, false, true)
	r.ledger.retainedErr = errors.New("x")
	r.cycle(t)
	if _, ok := r.ledger.pathOf(rowKey{"s1", 1}); !ok {
		t.Fatal("집계 실패 주기에 삭제가 돌았다")
	}
	if len(warn.all("janitor_cycle_failing")) < 2 { // 시작 덤프 + set
		t.Fatal("cycle_failing 이 켜지지 않았다")
	}
}
