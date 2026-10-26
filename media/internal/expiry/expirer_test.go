package expiry

// 만료 루프 시험(POK-196 M6-4 c2 · 설계 M6 7절 · 9절 D16 · D19–D24 · D28 · e2 · e3).

import (
	"context"
	"errors"
	"fmt"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// 한 방송의 걸음 순서 — 표시(0) → 상태 표지 → e1 검사 → 대상 행 → 문서 → 조각 객체 → 행. 문서가 조각보다 먼저다(D16).
func TestSweepDeletesBroadcastInOrder(t *testing.T) {
	r := newRig(t, nil)
	r.ledger.put("live1", 3)
	r.ledger.docs["live1"] = []index.ExpiryDocument{{Kind: index.ExpiryDocDVR, SessionID: "live1-s", Key: "dvr/live1"}}
	r.clip.pages[""] = clipPage{items: []clipItem{item("live1")}, next: ptr("c1")}
	r.sweep(t)

	if got := r.ledger.callLog(); !slices.Equal(got, []string{"live1:mark", "live1:cutoff", "live1:rows", "live1:docs", "live1:delete"}) {
		t.Fatalf("걸음 %v", got)
	}
	if !slices.Equal(r.changed, []string{"live1"}) {
		t.Fatalf("상태 표지 %v (live1 한 번 기대)", r.changed)
	}
	keys := r.objects.deletedKeys()
	if len(keys) != 7 || keys[0] != "dvr/live1" {
		t.Fatalf("객체 삭제 %v (문서 먼저 · 7개 기대)", keys)
	}
	if got := r.ledger.deletedSeqs("live1"); !slices.Equal(got, []int64{0, 1, 2}) {
		t.Fatalf("행 삭제 %v", got)
	}
	if len(r.log.all("expiry_broadcast_deleted")) != 1 {
		t.Fatal("expiry_broadcast_deleted 줄이 없다")
	}
}

// 요청 — 전용 헤더에 토큰 · before 는 주입 시계의 UTC · 처음은 cursor 없음 · 다음 회차는 받은 표시로 이어 부른다.
func TestSweepRequestShapeAndCursor(t *testing.T) {
	r := newRig(t, nil)
	r.clip.pages[""] = clipPage{items: []clipItem{item("a")}, next: ptr("c1")}
	r.clip.pages["c1"] = clipPage{next: ptr("c1")}
	r.sweep(t)
	r.now = r.now.Add(10 * time.Minute)
	r.sweep(t)

	reqs := r.clip.reqs()
	if len(reqs) != 2 {
		t.Fatalf("요청 %d (2 기대)", len(reqs))
	}
	first, second := reqs[0], reqs[1]
	if first.Header.Get(TokenHeader) != "tok-canary-7f3a" || first.URL.Path != "/internal/broadcasts/expired" {
		t.Fatalf("요청 머리 · 경로 %v %s", first.Header, first.URL.Path)
	}
	if first.URL.Query().Get("before") != "2026-10-01T00:00:00Z" || first.URL.Query().Has("cursor") {
		t.Fatalf("첫 요청 질의 %q", first.URL.RawQuery)
	}
	if second.URL.Query().Get("cursor") != "c1" || second.URL.Query().Get("before") != "2026-10-01T00:10:00Z" {
		t.Fatalf("둘째 요청 질의 %q (cursor=c1 기대)", second.URL.RawQuery)
	}
}

// hasMore 면 그 회차 안에서 이어 부르고, 한 회 상한(방송 수)에 닿으면 멈춘다 — 표시는 다 처리한 장까지만 넘긴다.
func TestSweepFollowsHasMoreUpToRunCap(t *testing.T) {
	r := newRig(t, func(o *Options) { o.RunCap = 3 })
	r.clip.pages[""] = clipPage{items: []clipItem{item("a"), item("b")}, next: ptr("c1"), more: true}
	r.clip.pages["c1"] = clipPage{items: []clipItem{item("c"), item("d")}, next: ptr("c2"), more: true}
	r.clip.pages["c2"] = clipPage{items: []clipItem{item("e")}, next: ptr("c3")}
	r.sweep(t)
	if n := len(r.clip.reqs()); n != 2 {
		t.Fatalf("요청 %d (상한 3 에 닿아 둘째 장에서 멈춤 — 2 기대)", n)
	}
	r.sweep(t)
	if q := r.clip.reqs()[2].URL.Query().Get("cursor"); q != "c2" {
		t.Fatalf("다음 회차 표시 %q (c2 기대)", q)
	}
}

// D20 — 시한 · 본문 상한 · 리다이렉트 금지. 셋 다 그 회차 실패이고 표시를 넘기지 않는다.
func TestSweepRejectsSlowHugeOrRedirectedResponse(t *testing.T) {
	cases := []struct {
		name, status, errHas string
		set                  func(c *fakeClip)
	}{
		{"지연", "timeout", "", func(c *fakeClip) { c.delay = 2 * time.Second }},
		{"본문 상한 초과", "body", "상한", func(c *fakeClip) {
			c.body = []byte(`{"broadcasts":[],"pad":"` + strings.Repeat("x", 2<<10) + `"}`)
		}},
		{"302", "302", "", func(c *fakeClip) { c.redirect = true }},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			r := newRig(t, func(o *Options) { o.RequestTimeout, o.BodyLimit = 300*time.Millisecond, 1<<10 })
			r.clip.set(c.set)
			r.sweep(t)
			polls := r.log.all("expiry_poll")
			if len(polls) != 1 || polls[0].attrs["status"] != c.status || !strings.Contains(polls[0].attrs["err"], c.errHas) {
				t.Fatalf("expiry_poll %v (status=%s · err 에 %q 기대)", polls, c.status, c.errHas)
			}
			if r.e.cursor != nil {
				t.Fatal("실패 회차에 표시가 생겼다")
			}
		})
	}
}

// D20 기본값 — 시한 10초 · 본문 1MiB · 주기 10분 · 하루 한 번 · 회당 2000 방송(설계 7절).
func TestDefaultOptionsMatchDesign(t *testing.T) {
	o := DefaultOptions()
	if o.RequestTimeout != 10*time.Second || o.BodyLimit != 1<<20 || o.Interval != 10*time.Minute ||
		o.FullScanEvery != 24*time.Hour || o.RunCap != 2000 {
		t.Fatalf("기본값 %+v", o)
	}
}

// D19 — 401 · 5xx 가 연속 3회면 expiry_stalled 를 켜고, 성공하면 끈다.
func TestSweepStalledGaugeAfterThreeFailures(t *testing.T) {
	r := newRig(t, nil)
	r.clip.set(func(c *fakeClip) { c.status = 401 })
	r.sweep(t)
	r.sweep(t)
	if len(r.log.all("expiry_stalled")) != 0 {
		t.Fatal("두 번 실패에 켜졌다")
	}
	r.clip.set(func(c *fakeClip) { c.status = 503 })
	r.sweep(t)
	stalled := r.log.all("expiry_stalled")
	if len(stalled) != 1 || stalled[0].attrs["value"] != "1" {
		t.Fatalf("셋째 실패 뒤 게이지 %v", stalled)
	}
	r.clip.set(func(c *fakeClip) { c.status = 0 })
	r.sweep(t)
	stalled = r.log.all("expiry_stalled")
	if len(stalled) != 2 || stalled[1].attrs["value"] != "0" {
		t.Fatalf("성공 뒤 게이지 %v (clear 기대)", stalled)
	}
	if polls := r.log.all("expiry_poll"); len(polls) != 3 || polls[0].attrs["status"] != "401" {
		t.Fatalf("expiry_poll %v", polls)
	}
}

// D21 — 부팅 회차와 하루 한 번은 표시 없이 처음부터 훑는다.
func TestSweepFullScanAtBootAndDaily(t *testing.T) {
	r := newRig(t, nil)
	r.clip.pages[""] = clipPage{next: ptr("c9")}
	r.clip.pages["c9"] = clipPage{next: ptr("c9")}
	r.sweep(t)                          // 부팅 — 처음부터
	r.now = r.now.Add(10 * time.Minute) // 표시 이어받기
	r.sweep(t)
	r.now = r.now.Add(24 * time.Hour) // 하루 지남 — 처음부터
	r.sweep(t)
	var cursors []string
	for _, q := range r.clip.reqs() {
		cursors = append(cursors, q.URL.Query().Get("cursor"))
	}
	if !slices.Equal(cursors, []string{"", "c9", ""}) {
		t.Fatalf("표시 %q (처음 · 이어받기 · 처음 기대)", cursors)
	}
}

// D22 — 가운데 방송 하나가 영구 실패(S3 403)여도 첫 · 셋째는 지우고, 실패한 방송은 재시도 집합에서 다음 회차에 다시 본다.
func TestSweepOneBroadcastFailureDoesNotBlockOthers(t *testing.T) {
	r := newRig(t, nil)
	for _, s := range []string{"a", "b", "c"} {
		r.ledger.put(s, 1)
	}
	r.objects.fail = func(k string) bool { return strings.HasPrefix(k, "b/") }
	r.clip.pages[""] = clipPage{items: []clipItem{item("a"), item("b"), item("c")}, next: ptr("c1")}
	r.clip.pages["c1"] = clipPage{next: ptr("c1")}
	r.sweep(t)

	if len(r.ledger.deletedSeqs("a")) != 1 || len(r.ledger.deletedSeqs("c")) != 1 || len(r.ledger.deletedSeqs("b")) != 0 {
		t.Fatalf("삭제 a=%v b=%v c=%v", r.ledger.deletedSeqs("a"), r.ledger.deletedSeqs("b"), r.ledger.deletedSeqs("c"))
	}
	if fails := r.log.all("expiry_delete_failed"); len(fails) != 1 || fails[0].attrs["stage"] != "segments" {
		t.Fatalf("expiry_delete_failed %v", fails)
	}
	if r.e.cursor == nil || *r.e.cursor != "c1" {
		t.Fatal("장을 다 처리했는데 표시가 안 넘어갔다")
	}
	r.objects.fail = nil
	r.now = r.now.Add(10 * time.Minute)
	r.sweep(t)
	if len(r.ledger.deletedSeqs("b")) != 1 {
		t.Fatal("재시도 집합의 방송을 다음 회차에 다시 보지 않았다")
	}
}

// 조각 키 일부만 지워지면 그 키의 행만 남기고 나머지 행은 지운다(키 성공분만 다음 단계로 — 설계 7절 단계 4).
func TestSweepDeletesOnlyRowsWhoseKeysWereDeleted(t *testing.T) {
	r := newRig(t, nil)
	r.ledger.put("k", 3)
	r.objects.fail = func(k string) bool { return k == "k/p/1" }
	r.clip.pages[""] = clipPage{items: []clipItem{item("k")}}
	r.sweep(t)
	if got := r.ledger.deletedSeqs("k"); !slices.Equal(got, []int64{0, 2}) {
		t.Fatalf("행 삭제 %v ([0 2] 기대 — seq 1 의 ③ 키 실패)", got)
	}
}

// D16 — 문서 삭제가 실패하면 조각 · 행 단계로 가지 않는다. 조회 제외(0단계)와 상태 표지는 이미 났다.
func TestSweepDocumentFailureStopsBeforeSegments(t *testing.T) {
	r := newRig(t, nil)
	r.ledger.put("d", 2)
	r.ledger.docs["d"] = []index.ExpiryDocument{{Kind: index.ExpiryDocVOD, SessionID: "d-s", Key: "vod/d"}}
	r.objects.fail = func(k string) bool { return k == "vod/d" }
	r.clip.pages[""] = clipPage{items: []clipItem{item("d")}}
	r.sweep(t)
	if len(r.objects.deletedKeys()) != 0 || len(r.ledger.deletedSeqs("d")) != 0 {
		t.Fatalf("문서 실패 뒤 진행했다: 객체 %v 행 %v", r.objects.deletedKeys(), r.ledger.deletedSeqs("d"))
	}
	if !slices.Equal(r.ledger.marked, []string{"d"}) || !slices.Equal(r.changed, []string{"d"}) {
		t.Fatalf("0단계 표시 %v · 표지 %v 가 먼저 나야 한다", r.ledger.marked, r.changed)
	}
	if fails := r.log.all("expiry_delete_failed"); len(fails) != 1 || fails[0].attrs["stage"] != "documents" {
		t.Fatalf("expiry_delete_failed %v", fails)
	}
}

// e1 · e2 · e3 · D23 — 무효 컷오프 스트림은 객체를 지우지 않고 ERROR 를 스트림당 한 번 낸다. 다른 스트림은 계속한다.
// 유효해지면 clear 한다.
func TestSweepE2InvalidCutoffAlarmOnceOthersContinue(t *testing.T) {
	r := newRig(t, nil)
	for _, s := range []string{"ok1", "bad", "ok2"} {
		r.ledger.put(s, 1)
	}
	r.ledger.invalid["bad"] = true
	r.clip.pages[""] = clipPage{items: []clipItem{item("ok1"), item("bad"), item("ok2")}, next: ptr("c1")}
	r.clip.pages["c1"] = clipPage{next: ptr("c1")}
	r.sweep(t)
	r.now = r.now.Add(10 * time.Minute)
	r.sweep(t) // 재시도 — 여전히 무효

	if len(r.ledger.deletedSeqs("ok1")) != 1 || len(r.ledger.deletedSeqs("ok2")) != 1 {
		t.Fatal("e3 — 다른 스트림이 지워지지 않았다")
	}
	for _, k := range r.objects.deletedKeys() {
		if strings.HasPrefix(k, "bad/") {
			t.Fatalf("e1 — 무효 컷오프 스트림의 객체를 지웠다: %s", k)
		}
	}
	alarms := r.log.all("cutoff_invalid")
	if len(alarms) != 1 || alarms[0].attrs["axis"] != "expiry" || alarms[0].attrs["stream_id"] != "bad" || alarms[0].attrs["value"] != "1" {
		t.Fatalf("e2 — cutoff_invalid %v (스트림당 정확히 1건 기대)", alarms)
	}
	delete(r.ledger.invalid, "bad")
	r.now = r.now.Add(10 * time.Minute)
	r.sweep(t)
	if alarms = r.log.all("cutoff_invalid"); len(alarms) != 2 || alarms[1].attrs["value"] != "0" {
		t.Fatalf("유효해진 뒤 clear 가 없다: %v", alarms)
	}
	if len(r.ledger.deletedSeqs("bad")) != 1 {
		t.Fatal("유효해진 뒤 재시도로 지우지 않았다")
	}
}

// D28 — ingestStreamId 가 키 성분 규칙에 안 맞으면 장부에 묻지도 않고 건너뛴다(재시도 집합 아님).
func TestSweepRejectsUnsafeIngestStreamID(t *testing.T) {
	for _, bad := range []string{"../x", strings.Repeat("a", 65), "S-20260801-000000-x-1", "", ".", "..", "a/b"} {
		t.Run(fmt.Sprintf("%q", bad), func(t *testing.T) {
			r := newRig(t, nil)
			it := item("x")
			it.IngestStreamID = bad
			r.clip.pages[""] = clipPage{items: []clipItem{it}}
			r.sweep(t)
			if calls := r.ledger.callLog(); len(calls) != 0 {
				t.Fatalf("장부에 물었다: %v", calls)
			}
			if skips := r.log.all("expiry_skipped"); len(skips) != 1 || skips[0].attrs["reason"] != "no_ingest_key" {
				t.Fatalf("expiry_skipped %v", skips)
			}
			if len(r.e.retry) != 0 {
				t.Fatal("재시도 집합에 들어갔다")
			}
		})
	}
}

// 대상이 아닌 행(열린 세션 등)은 사유별로 WARN 한 줄에 세고 객체 · 행을 건드리지 않는다(D17).
func TestSweepSkipsNonTargetRows(t *testing.T) {
	r := newRig(t, nil)
	r.ledger.put("s", 2)
	r.ledger.rows["s"][1].Skip = index.ExpirySkipSessionOpen
	r.clip.pages[""] = clipPage{items: []clipItem{item("s")}}
	r.sweep(t)
	if got := r.ledger.deletedSeqs("s"); !slices.Equal(got, []int64{0}) {
		t.Fatalf("행 삭제 %v", got)
	}
	for _, k := range r.objects.deletedKeys() {
		if strings.HasSuffix(k, "/1") {
			t.Fatalf("건너뛸 행의 객체를 지웠다: %s", k)
		}
	}
	if skips := r.log.all("expiry_skipped"); len(skips) != 1 || skips[0].attrs["reason"] != index.ExpirySkipSessionOpen || skips[0].attrs["rows"] != "1" {
		t.Fatalf("expiry_skipped %v", skips)
	}
}

// 객체 삭제는 1000 키 단위 · 행 삭제는 500 행 단위로 나눈다.
func TestSweepBatchesObjectsAndRows(t *testing.T) {
	r := newRig(t, nil)
	r.ledger.put("big", 1200) // 키 2400
	r.clip.pages[""] = clipPage{items: []clipItem{item("big")}}
	r.sweep(t)
	if !slices.Equal(r.objects.batches, []int{1000, 1000, 400}) {
		t.Fatalf("객체 묶음 %v", r.objects.batches)
	}
	n := 0
	for _, c := range r.ledger.callLog() {
		if c == "big:delete" {
			n++
		}
	}
	if n != 3 || len(r.ledger.deletedSeqs("big")) != 1200 {
		t.Fatalf("행 삭제 호출 %d · 행 %d (3 · 1200 기대)", n, len(r.ledger.deletedSeqs("big")))
	}
}

// D24 — 객체 삭제 도중 종료 신호(취소)가 오면 행을 지우지 않는다. 다음 기동이 처음부터 훑어 다시 한다.
func TestSweepCanceledDoesNotDeleteRows(t *testing.T) {
	r := newRig(t, nil)
	r.ledger.put("c", 2)
	r.clip.pages[""] = clipPage{items: []clipItem{item("c")}}
	ctx, cancel := context.WithCancel(context.Background())
	r.objects.hook = func(context.Context) error {
		cancel() // 키는 지워졌는데 그 사이 종료 신호가 왔다
		return nil
	}
	r.e.sweep(ctx)
	if got := r.ledger.deletedSeqs("c"); len(got) != 0 {
		t.Fatalf("취소 뒤 행을 지웠다: %v", got)
	}
	for _, c := range r.ledger.callLog() {
		if c == "c:delete" {
			t.Fatal("취소 뒤 행 삭제 문장을 보냈다")
		}
	}
}

// 행 단계의 무효 컷오프(객체 단계 뒤 경합)도 경보 꼴로 다루고 재시도한다.
func TestSweepRowStageCutoffInvalid(t *testing.T) {
	r := newRig(t, nil)
	r.ledger.put("r", 1)
	r.ledger.deleteErr = fmt.Errorf("x: %w", index.ErrCutoffInvalid)
	r.clip.pages[""] = clipPage{items: []clipItem{item("r")}}
	r.sweep(t)
	if alarms := r.log.all("cutoff_invalid"); len(alarms) != 1 {
		t.Fatalf("cutoff_invalid %v", alarms)
	}
	if _, ok := r.e.retry["S-r"]; !ok {
		t.Fatal("재시도 집합에 없다")
	}
}

// 표시(0단계) 실패는 그 방송의 실패다 — 이후 걸음을 하지 않고 표지도 내지 않는다.
func TestSweepMarkFailureStops(t *testing.T) {
	r := newRig(t, nil)
	r.ledger.put("m", 1)
	r.ledger.markErr = errors.New("db down")
	r.clip.pages[""] = clipPage{items: []clipItem{item("m")}}
	r.sweep(t)
	if len(r.changed) != 0 || slices.Contains(r.ledger.callLog(), "m:rows") {
		t.Fatalf("표시 실패 뒤 진행: 표지 %v 걸음 %v", r.changed, r.ledger.callLog())
	}
	if fails := r.log.all("expiry_delete_failed"); len(fails) != 1 || fails[0].attrs["stage"] != "mark" {
		t.Fatalf("expiry_delete_failed %v", fails)
	}
}

// New 는 빠진 협력자를 거부한다 — 상태 표지(Changed) 없이 돌면 조회 제외가 캐시에 안 닿는다(보내는 길은 하나).
func TestNewRejectsMissingCollaborators(t *testing.T) {
	o := DefaultOptions()
	o.ClipURL = "http://127.0.0.1:1"
	o.Log = newRig(t, nil).e.log
	if _, err := New(newFakeLedger(), &fakeObjects{}, o); err == nil {
		t.Fatal("Changed 없이 만들어졌다")
	}
	o.Changed = func(string) {}
	if _, err := New(nil, &fakeObjects{}, o); err == nil {
		t.Fatal("Ledger 없이 만들어졌다")
	}
	o.ClipURL = ""
	if _, err := New(newFakeLedger(), &fakeObjects{}, o); err == nil {
		t.Fatal("빈 주소로 만들어졌다")
	}
}

// Run 은 ctx 가 끝나면 돌아온다(첫 회차는 곧바로 돈다).
func TestRunReturnsOnCancel(t *testing.T) {
	r := newRig(t, func(o *Options) { o.Interval = time.Hour })
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.e.Run(ctx) }()
	deadline := time.Now().Add(5 * time.Second)
	for len(r.clip.reqs()) == 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("Run = %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Run 이 취소 뒤 돌아오지 않았다")
	}
	if len(r.clip.reqs()) == 0 {
		t.Fatal("첫 회차가 돌지 않았다")
	}
}

// D22 — 같은 물리 키의 두 방송이 같이 실패해도 재시도 집합에 둘 다 남는다(방송 식별자가 키다). 다음 회차에 둘 다 다시
// 본다 — 하루 한 번 처음부터 훑기까지 기다리지 않는다.
func TestSweepRetryKeepsEveryBroadcastOfOneIngestKey(t *testing.T) {
	r := newRig(t, nil)
	r.ledger.put("k", 1)
	first, second := item("k"), item("k")
	first.StreamID, second.StreamID = "S-k-1", "S-k-2"
	second.SegmentsFrom = ptr(first.SegmentsFrom.Add(-2 * time.Hour))
	r.objects.fail = func(string) bool { return true }
	r.clip.pages[""] = clipPage{items: []clipItem{first, second}, next: ptr("c1")}
	r.clip.pages["c1"] = clipPage{next: ptr("c1")}
	r.sweep(t)
	if len(r.e.retry) != 2 {
		t.Fatalf("재시도 집합 %d 건 (2 기대 — 뒤 방송이 앞 방송을 덮었다)", len(r.e.retry))
	}
	r.objects.fail = nil
	r.now = r.now.Add(10 * time.Minute)
	r.sweep(t)
	marks := 0
	for _, c := range r.ledger.callLog() {
		if c == "k:mark" {
			marks++
		}
	}
	if marks != 4 || len(r.e.retry) != 0 {
		t.Fatalf("표시 %d 회 · 남은 재시도 %d (4 · 0 기대 — 두 방송 모두 다시 본다)", marks, len(r.e.retry))
	}
}

// 설계 7절 단계 3 — 문서는 종류마다 따로 지운다(dvr → vod → init). 앞 종류가 하나라도 실패하면 뒤 종류를 지우지
// 않는다 — 남은 재생 목록이 지워진 init 을 가리키지 않는다(MAP 404).
func TestSweepDocumentKindsDeleteInOrderAndStopOnFailure(t *testing.T) {
	docs := []index.ExpiryDocument{
		{Kind: index.ExpiryDocDVR, SessionID: "x-s", Key: "dvr/x"},
		{Kind: index.ExpiryDocVOD, SessionID: "x-s", Key: "vod/x"},
		{Kind: index.ExpiryDocInit, SessionID: "x-s", Key: "init/x"},
	}
	for _, tt := range []struct {
		name, fail string
		want       []string
	}{
		{"모두 성공", "", []string{"dvr/x", "vod/x", "init/x"}},
		{"dvr 실패", "dvr/x", nil},
		{"vod 실패", "vod/x", []string{"dvr/x"}},
	} {
		t.Run(tt.name, func(t *testing.T) {
			r := newRig(t, nil)
			r.ledger.docs["x"] = slices.Clone(docs)
			r.objects.fail = func(k string) bool { return k == tt.fail }
			r.clip.pages[""] = clipPage{items: []clipItem{item("x")}}
			r.sweep(t)
			if got := r.objects.deletedKeys(); !slices.Equal(got, tt.want) {
				t.Fatalf("지운 키 %v, want %v", got, tt.want)
			}
			if tt.fail == "" && !slices.Equal(r.objects.batches, []int{1, 1, 1}) {
				t.Fatalf("요청 %v (종류마다 한 요청 — [1 1 1] 기대)", r.objects.batches)
			}
		})
	}
}

// cc r1 — 무효 컷오프는 게이지(cutoff_invalid set · clear)만 ERROR 로 낸다. 재시도 회차마다 expiry_delete_failed
// stage=cutoff 를 다시 내지 않는다(10분마다 같은 ERROR).
func TestSweepInvalidCutoffDoesNotRepeatDeleteFailed(t *testing.T) {
	r := newRig(t, nil)
	r.ledger.put("bad", 1)
	r.ledger.invalid["bad"] = true
	r.clip.pages[""] = clipPage{items: []clipItem{item("bad")}, next: ptr("c1")}
	r.clip.pages["c1"] = clipPage{next: ptr("c1")}
	for range 3 {
		r.sweep(t)
		r.now = r.now.Add(10 * time.Minute)
	}
	if fails := r.log.all("expiry_delete_failed"); len(fails) != 0 {
		t.Fatalf("expiry_delete_failed %v (0 기대 — 경보는 cutoff_invalid 한 줄)", fails)
	}
	if alarms := r.log.all("cutoff_invalid"); len(alarms) != 1 {
		t.Fatalf("cutoff_invalid %v (1 기대)", alarms)
	}
	if _, ok := r.e.retry["S-bad"]; !ok {
		t.Fatal("재시도 집합에 없다 — 유효해지면 다시 봐야 한다")
	}
}

// cc r1 — hasMore 인데 빈 장이거나 표시가 그대로면 창구 응답 이상이다. 그 회차를 status=body 실패로 끝낸다 — 같은
// 장을 끝없이 다시 부르지 않는다.
func TestSweepRejectsPageThatCannotAdvance(t *testing.T) {
	for _, tt := range []struct {
		name string
		page clipPage
	}{
		{"빈 장", clipPage{next: ptr("c1"), more: true}},
		{"표시 없음", clipPage{items: []clipItem{item("b")}, more: true}},
		{"표시 정지", clipPage{items: []clipItem{item("b")}, next: ptr("c1"), more: true}},
	} {
		t.Run(tt.name, func(t *testing.T) {
			r := newRig(t, nil)
			r.clip.pages[""] = clipPage{items: []clipItem{item("a")}, next: ptr("c1"), more: true}
			r.clip.pages["c1"] = tt.page
			done := make(chan struct{})
			go func() {
				defer close(done)
				r.sweep(t)
			}()
			select {
			case <-done:
			case <-time.After(5 * time.Second):
				t.Fatal("회차가 끝나지 않는다 — 같은 장을 다시 부른다")
			}
			if n := len(r.clip.reqs()); n != 2 {
				t.Fatalf("요청 %d (2 기대)", n)
			}
			polls := r.log.all("expiry_poll")
			if len(polls) != 1 || polls[0].attrs["status"] != "body" {
				t.Fatalf("expiry_poll %v (status=body 한 줄 기대)", polls)
			}
			if r.e.cursor == nil || *r.e.cursor != "c1" {
				t.Fatal("다 처리한 첫 장의 표시가 남아야 한다")
			}
		})
	}
}
