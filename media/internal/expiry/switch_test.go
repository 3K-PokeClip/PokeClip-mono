package expiry

// 만료 끄기 스위치 시험(POK-551 c2 · 계획 T5 · 결함 예측 P4 · P5). 스위치는 DB 한 행이고(index.ReadExpirySwitch), 만료기는
// 회차 시작과 방송마다 읽는다. 읽을 수 없으면 끔이다.

import (
	"errors"
	"log/slog"
	"slices"
	"strings"
	"testing"
	"time"
)

// expectSwitchLines 는 expiry_switch 줄의 value 들이 want 와 같은지 본다(WARN).
func expectSwitchLines(t *testing.T, r *rig, want ...string) {
	t.Helper()
	var got []string
	for _, l := range r.log.all("expiry_switch") {
		if l.level != slog.LevelWarn {
			t.Fatalf("expiry_switch 등급 %v (WARN 기대)", l.level)
		}
		got = append(got, l.attrs["value"])
	}
	if !slices.Equal(got, want) {
		t.Fatalf("expiry_switch value %v, want %v", got, want)
	}
}

// T5a — 끔이면 창구를 부르지 않고 장부도 만지지 않는다. 회차마다 다시 읽지만 줄은 처음 한 번이다.
func TestSweepSwitchOffSkipsPollAndLedger(t *testing.T) {
	r := newRig(t, nil)
	r.ledger.put("a", 1)
	r.ledger.setSwitch(true, nil)
	r.clip.pages[""] = clipPage{items: []clipItem{item("a")}}
	for range 2 {
		r.sweep(t)
		r.now = r.now.Add(10 * time.Minute)
	}

	if n := len(r.clip.reqs()); n != 0 {
		t.Fatalf("창구 요청 %d (0 기대 — 끔이면 폴링하지 않는다)", n)
	}
	if calls := r.ledger.callLog(); len(calls) != 0 {
		t.Fatalf("장부 걸음 %v (없음 기대)", calls)
	}
	if len(r.objects.deletedKeys()) != 0 || len(r.changed) != 0 {
		t.Fatalf("끔인데 객체 삭제 %v · 상태 표지 %v", r.objects.deletedKeys(), r.changed)
	}
	if r.ledger.switchReads != 2 {
		t.Fatalf("스위치 읽기 %d (회차마다 한 번 — 2 기대)", r.ledger.switchReads)
	}
	expectSwitchLines(t, r, "0")
}

// T5b — 켬이면 진행한다. 줄은 바뀔 때만이다(켬 → 그대로 → 끔 → 켬).
func TestSweepSwitchTransitionsLogOnlyOnChange(t *testing.T) {
	r := newRig(t, nil)
	r.ledger.put("a", 1)
	r.clip.pages[""] = clipPage{items: []clipItem{item("a")}, next: ptr("c1")}
	r.clip.pages["c1"] = clipPage{next: ptr("c1")}
	r.sweep(t)
	if got := r.ledger.deletedSeqs("a"); !slices.Equal(got, []int64{0}) {
		t.Fatalf("켬인데 행 삭제 %v", got)
	}
	for _, off := range []bool{false, true, false} {
		r.ledger.setSwitch(off, nil)
		r.now = r.now.Add(10 * time.Minute)
		r.sweep(t)
	}
	expectSwitchLines(t, r, "1", "0", "1")
	if n := len(r.clip.reqs()); n != 3 {
		t.Fatalf("창구 요청 %d (켬 회차 셋 — 3 기대)", n)
	}
}

// T5c · P4 — 두 방송 사이에 끄면 둘째는 처리하지 않고 표시(cursor)도 넘기지 않는다. 다시 켜면 그 장을 다시 받아
// 둘째를 처리한다(첫째는 멱등).
func TestSweepSwitchOffBetweenBroadcastsStopsAndKeepsCursor(t *testing.T) {
	r := newRig(t, nil)
	r.ledger.put("a", 1)
	r.ledger.put("b", 1)
	r.ledger.onStep = func(l *fakeLedger, call string) {
		if call == "a:delete" {
			l.switchOff = true
		}
	}
	r.clip.pages[""] = clipPage{items: []clipItem{item("a"), item("b")}, next: ptr("c1")}
	r.clip.pages["c1"] = clipPage{next: ptr("c1")}
	r.sweep(t)

	for _, c := range r.ledger.callLog() {
		if strings.HasPrefix(c, "b:") {
			t.Fatalf("끈 뒤 둘째 방송 걸음 %s", c)
		}
	}
	if done := r.log.all("expiry_broadcast_deleted"); len(done) != 1 || done[0].attrs["stream_id"] != "a" {
		t.Fatalf("expiry_broadcast_deleted %v (a 하나 기대)", done)
	}
	if r.e.cursor != nil {
		t.Fatalf("끈 회차가 표시를 %q 로 넘겼다", *r.e.cursor)
	}
	expectSwitchLines(t, r, "1", "0")

	r.ledger.onStep = nil
	r.ledger.setSwitch(false, nil)
	r.now = r.now.Add(10 * time.Minute)
	r.sweep(t)
	if got := r.ledger.deletedSeqs("b"); !slices.Equal(got, []int64{0}) {
		t.Fatalf("다시 켠 뒤 둘째 방송 행 삭제 %v", got)
	}
	if q := r.clip.reqs()[1].URL.Query(); q.Has("cursor") {
		t.Fatalf("다시 켠 회차 요청 cursor=%q (표시 없이 그 장을 다시 받기 기대)", q.Get("cursor"))
	}
}

// T5d · P5 — 스위치를 읽지 못하면(표 없음 · 권한 없음 · 시간 초과 — 장부가 오류로 돌려준다) 끔이다. ERROR 줄은 실패가
// 시작될 때 한 번 · 다시 읽힐 때 한 번이다.
func TestSweepSwitchReadFailureIsOff(t *testing.T) {
	r := newRig(t, nil)
	r.ledger.put("a", 1)
	r.ledger.setSwitch(false, errors.New(`ERROR: relation "stream_expiry_switch" does not exist (SQLSTATE 42P01)`))
	r.clip.pages[""] = clipPage{items: []clipItem{item("a")}}
	for range 2 {
		r.sweep(t)
		r.now = r.now.Add(10 * time.Minute)
	}

	if n := len(r.clip.reqs()); n != 0 || len(r.ledger.callLog()) != 0 {
		t.Fatalf("읽기 실패인데 창구 요청 %d · 장부 걸음 %v", n, r.ledger.callLog())
	}
	fails := r.log.all("expiry_switch_read_failed")
	if len(fails) != 1 || fails[0].level != slog.LevelError || fails[0].attrs["value"] != "1" ||
		!strings.Contains(fails[0].attrs["err"], "42P01") {
		t.Fatalf("expiry_switch_read_failed %v (ERROR value=1 한 줄 · err 기대)", fails)
	}
	expectSwitchLines(t, r)

	r.ledger.setSwitch(false, nil)
	r.sweep(t)
	if fails = r.log.all("expiry_switch_read_failed"); len(fails) != 2 || fails[1].attrs["value"] != "0" {
		t.Fatalf("다시 읽힌 뒤 expiry_switch_read_failed %v (value=0 한 줄 기대)", fails)
	}
	expectSwitchLines(t, r, "1")
	if got := r.ledger.deletedSeqs("a"); !slices.Equal(got, []int64{0}) {
		t.Fatalf("다시 읽힌 뒤 행 삭제 %v", got)
	}
}

// T5e · P4 — retryFailed 도중에 끄면 꺼냈지만 처리하지 않은 방송을 재시도 집합에 되돌린다. 그 회차는 창구도 부르지
// 않는다.
func TestSweepSwitchOffDuringRetryRequeuesBatch(t *testing.T) {
	r := newRig(t, nil)
	for _, s := range []string{"a", "b", "c"} {
		r.ledger.put(s, 1)
	}
	r.objects.fail = func(string) bool { return true }
	r.clip.pages[""] = clipPage{items: []clipItem{item("a"), item("b"), item("c")}, next: ptr("c1")}
	r.clip.pages["c1"] = clipPage{next: ptr("c1")}
	r.sweep(t)
	if len(r.e.retry) != 3 {
		t.Fatalf("첫 회차 뒤 재시도 집합 %d (3 기대)", len(r.e.retry))
	}

	r.objects.fail = nil
	r.ledger.onStep = func(l *fakeLedger, call string) {
		if strings.HasSuffix(call, ":delete") {
			l.switchOff = true // 재시도 첫 방송을 지운 뒤 끈다
		}
	}
	r.now = r.now.Add(10 * time.Minute)
	r.sweep(t)

	if len(r.e.retry) != 2 {
		t.Fatalf("끈 뒤 재시도 집합 %d (2 기대 — 처리한 하나만 빠지고 나머지는 되돌아온다)", len(r.e.retry))
	}
	deleted := 0
	for _, s := range []string{"a", "b", "c"} {
		_, waiting := r.e.retry["S-"+s]
		if done := len(r.ledger.deletedSeqs(s)) == 1; done == waiting {
			t.Fatalf("방송 %s 처리 %t · 재시도 대기 %t (둘 중 하나만 기대)", s, done, waiting)
		}
		if !waiting {
			deleted++
		}
	}
	if deleted != 1 {
		t.Fatalf("처리한 재시도 %d (1 기대)", deleted)
	}
	if n := len(r.clip.reqs()); n != 1 {
		t.Fatalf("창구 요청 %d (첫 회차 하나 — 끈 회차는 부르지 않는다)", n)
	}
}

// c2 cc L — 장의 마지막 방송 뒤에 끄면 다음 장을 받지 않는다(끔인 동안 창구를 부르지 않는다). 표시는 처리한 장까지
// 넘긴다 — 그 장의 방송은 다 처리했다.
func TestSweepSwitchOffAtPageEndSkipsNextPage(t *testing.T) {
	r := newRig(t, nil)
	r.ledger.put("a", 1)
	r.ledger.put("b", 1)
	r.ledger.onStep = func(l *fakeLedger, call string) {
		if call == "a:delete" {
			l.switchOff = true
		}
	}
	r.clip.pages[""] = clipPage{items: []clipItem{item("a")}, next: ptr("c1"), more: true}
	r.clip.pages["c1"] = clipPage{items: []clipItem{item("b")}, next: ptr("c2")}
	r.sweep(t)

	if n := len(r.clip.reqs()); n != 1 {
		t.Fatalf("창구 요청 %d (1 기대 — 끈 뒤 다음 장을 받지 않는다)", n)
	}
	if r.e.cursor == nil || *r.e.cursor != "c1" {
		t.Fatalf("표시 %v (c1 기대 — 첫 장은 다 처리했다)", r.e.cursor)
	}
	if got := r.ledger.deletedSeqs("b"); len(got) != 0 {
		t.Fatalf("끈 뒤 둘째 장 방송 행 삭제 %v", got)
	}
}
