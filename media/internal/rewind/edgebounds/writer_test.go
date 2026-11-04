package edgebounds

// Writer 의 단위 검증(POK-525 설계 3-3 Writer 계약 · 8-1 신호 · 8-2 E1 · E4 · E7 · E18 · E20 · 계획 Ⓒ-4). 끼어들기
// 순서 전수는 interleave_test.go, 프로세스가 죽은 뒤의 마무리는 writer_kill_test.go 가 본다.

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"math/rand/v2"
	"slices"
	"strings"
	"testing"
	"testing/synctest"
	"time"
)

// Options 검사(계획 Ⓒ-4) — 동작이 성립하지 않는 값은 New 가 거절한다. 잡는 결함: 틱 수(raiseEveryTicks)가 정수가
// 아니면 올림 주기가 FlushEvery 와 어긋나고, WriterID 가 비면 해제 표지 값이 상자끼리 겹칠 수 있다.
func TestNewRejectsInvalidOptions(t *testing.T) {
	cases := []struct {
		name string
		edit func(*Options)
	}{
		{"FlushEvery 0", func(o *Options) { o.FlushEvery = 0 }},
		{"ReleaseEvery 음수", func(o *Options) { o.ReleaseEvery = -time.Second }},
		{"ReleaseSettle 0", func(o *Options) { o.ReleaseSettle = 0 }},
		{"CallTimeout 0", func(o *Options) { o.CallTimeout = 0 }},
		{"Margin 음수", func(o *Options) { o.Margin = -time.Nanosecond }},
		{"FlushEvery 가 ReleaseEvery 의 정수배 아님", func(o *Options) { o.FlushEvery = 310 * time.Second }},
		{"FlushEvery 가 ReleaseEvery 보다 짧음", func(o *Options) { o.FlushEvery = 10 * time.Second }},
		{"WriterID 빈 값", func(o *Options) { o.WriterID = "" }},
		{"SizeWarnBytes 0", func(o *Options) { o.SizeWarnBytes = 0 }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			opt := testOptions(time.Now, nil)
			tc.edit(&opt)
			if _, err := New(newFakeLedger(time.Now), newFakeStore(), opt); err == nil {
				t.Errorf("New(%s) returned nil error, want error", tc.name)
			}
		})
	}
	t.Run("원장 · 저장소 nil", func(t *testing.T) {
		if _, err := New(nil, newFakeStore(), testOptions(time.Now, nil)); err == nil {
			t.Error("New(nil ledger) returned nil error")
		}
		if _, err := New(newFakeLedger(time.Now), nil, testOptions(time.Now, nil)); err == nil {
			t.Error("New(nil store) returned nil error")
		}
	})
}

func TestNewAcceptsZeroMarginAndDefaultsLogAndClock(t *testing.T) {
	opt := testOptions(nil, nil)
	opt.Margin = 0
	opt.Log = nil
	if _, err := New(newFakeLedger(time.Now), newFakeStore(), opt); err != nil {
		t.Fatalf("New(Margin 0 · Log · Now nil) returned error: %v", err)
	}
}

// 틱 계산(cc 부수 5): ReleaseEvery 마다 틱 하나 — 틱마다 해제, FlushEvery/ReleaseEvery(10) 번째 틱마다 그 뒤 올림이고
// 모두 한 고루틴에서 차례로다. 잡는 결함: 올림이 첫 틱 · 매 틱에 돌거나, 해제와 올림이 다른 고루틴이라 호출이 겹치거나,
// 같은 틱에서 올림이 해제보다 앞서는 것.
func TestRunReleasesEveryTickAndRaisesEveryTenthTick(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		start := time.Now()
		calls := &callLog{now: time.Now}
		l := newFakeLedger(time.Now)
		l.openRound("s", "r")
		w := newTestWriter(t, recLedger{l, calls}, recStore{newFakeStore(), calls}, testOptions(time.Now, nil))
		w.Observe("s", "r", 5, start)
		stop := runWriter(w)
		time.Sleep(20*30*time.Second + time.Second)
		stop()

		var deletes, raises []time.Duration
		for i, c := range calls.calls {
			switch c {
			case "DeleteReleased":
				deletes = append(deletes, calls.at[i].Sub(start))
			case "Raise":
				raises = append(raises, calls.at[i].Sub(start))
			}
		}
		if len(deletes) != 20 {
			t.Errorf("해제 틱 %d번(%v), want 20", len(deletes), deletes)
		}
		if want := []time.Duration{300 * time.Second, 600 * time.Second}; !slices.Equal(raises, want) {
			t.Errorf("올림 시각 %v, want %v", raises, want)
		}
		if calls.overlap {
			t.Error("원장 · KVS 호출이 겹쳤다 — 쓰기 고루틴 하나여야 한다")
		}
		tick10 := callsAt(calls, start.Add(300*time.Second))
		want := []string{"DeleteReleased", "Describe", "ClosedUnreleased", "Describe", "Raise", "Update"}
		if !slices.Equal(tick10, want) {
			t.Errorf("10번째 틱 호출 %v, want %v(해제 뒤 올림)", tick10, want)
		}
	})
}

func callsAt(c *callLog, at time.Time) []string {
	var out []string
	for i, name := range c.calls {
		if c.at[i].Equal(at) {
			out = append(out, name)
		}
	}
	return out
}

// runWriter 는 w.Run 을 고루틴으로 띄우고, 끝내고 돌아올 때까지 기다리는 함수를 돌려준다.
func runWriter(w *Writer) (stop func()) {
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		defer close(done)
		w.Run(ctx)
	}()
	return func() {
		cancel()
		<-done
	}
}

func TestRunReturnsWhenContextEnds(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		w := newTestWriter(t, newFakeLedger(time.Now), newFakeStore(), testOptions(time.Now, nil))
		stop := runWriter(w)
		time.Sleep(45 * time.Second)
		stop() // 돌아오지 않으면 synctest 가 교착으로 끝낸다
	})
}

// 올림의 읽기 순서(Writer 계약 1): 판 E 를 얻은 뒤 원장을 올리고, 원장이 돌려준 값만 If-Match E 로 보낸다. 잡는 결함:
// 원장보다 판을 늦게 읽으면 해제와 엇갈린 늦은 put 이 통과할 수 있다(E1).
func TestRaiseReadsETagBeforeLedgerAndSendsLedgerValue(t *testing.T) {
	clock := newTestClock()
	calls := &callLog{}
	l := newFakeLedger(clock.now)
	l.openRound("s", "r")
	l.seed("s", "r", 3)
	st := newFakeStore()
	w := newTestWriter(t, recLedger{l, calls}, recStore{st, calls}, testOptions(clock.now, nil))
	w.Observe("s", "r", 9, clock.now())
	clock.advance(61 * time.Second)
	etag := st.currentETag()

	if err := w.raise(context.Background()); err != nil {
		t.Fatalf("raise returned error: %v", err)
	}
	if got, want := calls.snapshot(), []string{"Describe", "Raise", "Update"}; !slices.Equal(got, want) {
		t.Errorf("호출 차례 %v, want %v", got, want)
	}
	reqs := st.updates()
	if len(reqs) != 1 || reqs[0].ifMatch != etag {
		t.Fatalf("Update 요청 %+v, want If-Match %s 하나", reqs, etag)
	}
	if v, _ := st.value("s/r"); v != "9" {
		t.Errorf("KVS s/r = %q, want %q", v, "9")
	}
}

// floor 정의(설계 1절 · D2): floor 는 「Margin 전까지 발행에 성공한 목록 MSN 의 최댓값」을 넘지 않는다. 웹 계약
// 불변식(최신 사슬 목록의 첫 조각 — kty (가))은 floor_bound_test.go 가 본다. 무작위 관측(MSN 되돌림 · 늦게 도착한 시각 · 0 이하 포함)과
// 무작위 올림 시각마다 원장 하한을 그 시각의 상한과 견준다. 투영 이력이 넘치지 않는 한 같아야 한다(덜 막는 것도 결함 —
// 설계 D2 하한 정의). 잡는 결함: Margin 을 안 기다리고 올리거나(첫 조각 404), 발행 시각 대신 Observe 를 부른 시각을 쓰거나,
// 더 늦게 관측된 작은 MSN 이 큰 MSN 의 성숙을 가리는 것.
func TestFloorNeverExceedsMSNPublishedMarginAgo(t *testing.T) {
	for _, margin := range []time.Duration{60 * time.Second, 0} {
		for seed := uint64(1); seed <= 100; seed++ {
			checkFloorBound(t, margin, seed)
		}
	}
}

func checkFloorBound(t *testing.T, margin time.Duration, seed uint64) {
	t.Helper()
	rng := rand.New(rand.NewPCG(seed, 0))
	clock := newTestClock()
	l := newFakeLedger(clock.now)
	l.openRound("s", "r")
	opt := testOptions(clock.now, nil)
	opt.Margin = margin
	w := newTestWriter(t, l, newFakeStore(), opt)
	type obs struct {
		msn int64
		at  time.Time
	}
	var seen []obs
	bound := func(now time.Time) int64 {
		var b int64
		for _, o := range seen {
			if !o.at.After(now.Add(-margin)) {
				b = max(b, o.msn)
			}
		}
		return b
	}
	var want int64
	for step := range 400 {
		clock.advance(time.Duration(rng.IntN(5000)) * time.Millisecond)
		now := clock.now()
		if rng.IntN(12) != 0 {
			o := obs{msn: int64(step/2 + rng.IntN(7) - 3), at: now.Add(-time.Duration(rng.IntN(4000)) * time.Millisecond)}
			seen = append(seen, o)
			w.Observe("s", "r", o.msn, o.at)
			continue
		}
		if err := w.raise(context.Background()); err != nil {
			t.Fatalf("seed %d: raise returned error: %v", seed, err)
		}
		b := bound(now)
		want = max(want, b)
		row, ok := l.row("s", "r")
		switch {
		case ok && row.Value > b:
			t.Fatalf("margin %v seed %d step %d: 원장 하한 %d > %v 전까지 발행된 MSN 최댓값 %d — 목록 첫 조각이 막힌다",
				margin, seed, step, row.Value, margin, b)
		case want >= 1 && (!ok || row.Value != want):
			t.Fatalf("margin %v seed %d step %d: 원장 하한 %+v(있음 %v), want %d", margin, seed, step, row, ok, want)
		}
	}
}

// 투영 이력이 상한(pendingCap)을 넘어도 하한은 상한을 넘지 않는다 — 넘칠 때 버리는 쪽은 덜 막는 쪽이다.
func TestFloorStaysUnderBoundWhenPendingOverflows(t *testing.T) {
	clock := newTestClock()
	l := newFakeLedger(clock.now)
	l.openRound("s", "r")
	w := newTestWriter(t, l, newFakeStore(), testOptions(clock.now, nil))
	start := clock.now()
	for i := range 500 {
		w.Observe("s", "r", int64(i+1), start.Add(time.Duration(i)*100*time.Millisecond))
	}
	for _, after := range []time.Duration{61 * time.Second, 75 * time.Second, 111 * time.Second} {
		clock.t = start.Add(after)
		if err := w.raise(context.Background()); err != nil {
			t.Fatalf("raise returned error: %v", err)
		}
		limit := int64((after-60*time.Second)/(100*time.Millisecond)) + 1
		if row, _ := l.row("s", "r"); row.Value > limit {
			t.Errorf("%v 뒤 하한 %d > 상한 %d", after, row.Value, limit)
		}
	}
}

// 닫힌 회차는 올리지 않고 잊는다(Writer 계약 3 (i)) — Raise 가 그 행을 돌려주지 않으므로 put 도 없다.
func TestRaiseSkipsClosedRoundAndForgetsIt(t *testing.T) {
	clock := newTestClock()
	l := newFakeLedger(clock.now)
	st := newFakeStore()
	w := newTestWriter(t, l, st, testOptions(clock.now, nil))
	w.Observe("s", "r", 9, clock.now())
	clock.advance(2 * time.Minute)

	if err := w.raise(context.Background()); err != nil {
		t.Fatalf("raise returned error: %v", err)
	}
	if reqs := st.updates(); len(reqs) != 0 {
		t.Errorf("닫힌 회차에 Update %d번, want 0", len(reqs))
	}
	if n := w.tracked(); n != 0 {
		t.Errorf("닫힌 회차 이력이 %d개 남았다, want 0", n)
	}
}

// 실패 분류(Writer 계약 4 · E20): 선행 조건 실패는 실측 그대로 ValidationException · HTTP 400 이다. Writer 는 오류 종류가
// 아니라 Describe 로 판을 다시 봐 가르고, 판이 바뀌었으면 원장을 다시 읽어 새 판으로 한 번 더 보낸다. 잡는 결함: 409 ·
// 412 를 기다리는 분기라 400 경합을 실패로 보고 하한을 5분 늦게 올리는 것.
func TestPreconditionFailureIsRetriedOnceOnNewETag(t *testing.T) {
	for _, pass := range []string{"raise", "release"} {
		t.Run(pass, func(t *testing.T) {
			clock := newTestClock()
			rec := &logRecorder{}
			l, st, w := passFixture(t, clock, rec, pass)
			once := true
			st.beforeUpdate = func() {
				if once {
					once = false
					st.foreignPut("other/round", "1")
				}
			}

			if err := runPass(w, pass); err != nil {
				t.Fatalf("%s returned error: %v", pass, err)
			}
			reqs := st.updates()
			if len(reqs) != 2 {
				t.Fatalf("Update %d번, want 2(경합 뒤 한 번 더)", len(reqs))
			}
			var api *fakeAPIError
			if !errors.As(reqs[0].err, &api) || api.status != 400 || api.code != "ValidationException" || api.message != preconditionMessage {
				t.Fatalf("첫 요청 오류 %v, want ValidationException 400 %q", reqs[0].err, preconditionMessage)
			}
			if reqs[1].ifMatch == reqs[0].ifMatch || reqs[1].err != nil {
				t.Errorf("두 번째 요청 If-Match %s(첫 %s) · 오류 %v, want 새 판 · 성공", reqs[1].ifMatch, reqs[0].ifMatch, reqs[1].err)
			}
			checkPassEffect(t, l, st, pass)
			if n := len(rec.named("edge_bounds_write_failing")); n != 0 {
				t.Errorf("edge_bounds_write_failing %d줄, want 0", n)
			}
		})
	}
}

// 판이 그대로인 실패는 경합이 아니다 — 다시 보내지 않고 다음 틱을 기다리며 reason=failed 로 알린다.
func TestFailureWithUnchangedETagIsNotRetried(t *testing.T) {
	for _, pass := range []string{"raise", "release"} {
		t.Run(pass, func(t *testing.T) {
			rec := &logRecorder{}
			l, st, w := passFixture(t, newTestClock(), rec, pass)
			st.setDeny(true)

			err := runPass(w, pass)
			if err == nil {
				t.Fatalf("%s returned nil error, want error", pass)
			}
			w.report(pass, err)
			if n := len(st.updates()); n != 1 {
				t.Errorf("Update %d번, want 1", n)
			}
			checkFailing(t, rec, pass, reasonFailed)
			if pass == "release" {
				if row, _ := l.row("s", "r"); row.ReleasedAt != nil {
					t.Error("쓰기가 거부됐는데 원장 행이 해제됐다")
				}
			}
		})
	}
}

// 경합 뒤 다시 보낸 것도 실패하면 그 시도는 reason=conflict 로 끝난다(한 번만 다시).
func TestSecondFailureAfterConflictReportsConflict(t *testing.T) {
	for _, pass := range []string{"raise", "release"} {
		t.Run(pass, func(t *testing.T) {
			rec := &logRecorder{}
			_, st, w := passFixture(t, newTestClock(), rec, pass)
			st.beforeUpdate = func() { st.foreignPut("other/round", "1") }

			err := runPass(w, pass)
			if err == nil {
				t.Fatalf("%s returned nil error, want error", pass)
			}
			w.report(pass, err)
			if n := len(st.updates()); n != 2 {
				t.Errorf("Update %d번, want 2", n)
			}
			checkFailing(t, rec, pass, reasonConflict)
		})
	}
}

// 응답을 잃은 성공(Writer 계약 4): 반영됐는데 오류를 받으면 판이 바뀌어 경합 갈래로 가고, 다시 보내도 같은 결과다 —
// 해제는 없는 키 지우기가 성공하고(H3-1) 원장에 해제를 적는다.
func TestLostResponseSuccessIsResentAsConflict(t *testing.T) {
	for _, pass := range []string{"raise", "release"} {
		t.Run(pass, func(t *testing.T) {
			rec := &logRecorder{}
			l, st, w := passFixture(t, newTestClock(), rec, pass)
			st.loseResponses = 1

			if err := runPass(w, pass); err != nil {
				t.Fatalf("%s returned error: %v", pass, err)
			}
			reqs := st.updates()
			if len(reqs) != 2 || !errors.Is(reqs[0].err, errResponseLost) || reqs[1].err != nil {
				t.Fatalf("Update 요청 %+v, want 응답 잃음 뒤 성공 하나", reqs)
			}
			checkPassEffect(t, l, st, pass)
		})
	}
}

// passFixture 는 pass 하나(raise · release)가 쓸 원장 · KVS · Writer 다 — 올림은 열린 회차 s/r 의 성숙한 관측 9,
// 해제는 KVS 에 키가 있는 닫힌 회차 s/r(하한 9)이다.
func passFixture(t *testing.T, clock *testClock, rec *logRecorder, pass string) (*fakeLedger, *fakeStore, *Writer) {
	t.Helper()
	l := newFakeLedger(clock.now)
	st := newFakeStore()
	w := newTestWriter(t, l, st, testOptions(clock.now, rec))
	switch pass {
	case "raise":
		l.openRound("s", "r")
		w.Observe("s", "r", 9, clock.now())
		clock.advance(2 * time.Minute)
	case "release":
		l.seed("s", "r", 9)
		st.seed("s/r", "9")
	default:
		t.Fatalf("Setup: pass %q", pass)
	}
	return l, st, w
}

func runPass(w *Writer, pass string) error {
	if pass == "raise" {
		return w.raise(context.Background())
	}
	return w.release(context.Background())
}

// checkPassEffect 는 pass 가 끝난 뒤의 상태다 — 올림이면 KVS 에 원장 값, 해제면 키 없음 · 원장 해제.
func checkPassEffect(t *testing.T, l *fakeLedger, st *fakeStore, pass string) {
	t.Helper()
	v, found := st.value("s/r")
	row, _ := l.row("s", "r")
	switch pass {
	case "raise":
		if v != "9" || row.Value != 9 {
			t.Errorf("KVS s/r = %q · 원장 %d, want 9 · 9", v, row.Value)
		}
	case "release":
		if found || row.ReleasedAt == nil {
			t.Errorf("KVS s/r 있음 %v · 원장 해제 %v, want 없음 · 해제", found, row.ReleasedAt)
		}
	}
}

func checkFailing(t *testing.T, rec *logRecorder, pass, reason string) {
	t.Helper()
	got := rec.named("edge_bounds_write_failing")
	if len(got) != 1 {
		t.Fatalf("edge_bounds_write_failing %d줄, want 1", len(got))
	}
	if got[0].attrs["pass"] != pass || got[0].attrs["reason"] != reason {
		t.Errorf("edge_bounds_write_failing %v, want pass=%s reason=%s", got[0].attrs, pass, reason)
	}
}

// 묶음 꼴(Writer 계약 5): 올림은 요청마다 put 50개 이하, 해제는 delete 49 + 표지 put 하나 — 한 요청에 키 중복 0. 가짜
// KVS 는 상한 초과 · 키 중복을 400 으로 거절한다(H3-5).
func TestRaiseSendsAtMostFiftyPutsPerRequest(t *testing.T) {
	clock := newTestClock()
	l := newFakeLedger(clock.now)
	st := newFakeStore()
	w := newTestWriter(t, l, st, testOptions(clock.now, nil))
	for i := range 120 {
		r := fmt.Sprintf("r%03d", i)
		l.openRound("s", r)
		w.Observe("s", r, int64(i+1), clock.now())
	}
	clock.advance(2 * time.Minute)

	if err := w.raise(context.Background()); err != nil {
		t.Fatalf("raise returned error: %v", err)
	}
	var sizes []int
	for _, req := range st.updates() {
		sizes = append(sizes, len(req.puts)+len(req.deletes))
	}
	if want := []int{50, 50, 20}; !slices.Equal(sizes, want) {
		t.Errorf("요청 크기 %v, want %v", sizes, want)
	}
	if v, _ := st.value("s/r119"); v != "120" {
		t.Errorf("KVS s/r119 = %q, want 120", v)
	}
}

func TestReleaseSendsFortyNineDeletesAndMarkerPerTick(t *testing.T) {
	clock := newTestClock()
	l := newFakeLedger(clock.now)
	st := newFakeStore()
	w := newTestWriter(t, l, st, testOptions(clock.now, nil))
	for i := range 100 {
		r := fmt.Sprintf("r%03d", i)
		l.seed("s", r, 7)
		st.seed("s/"+r, "7")
	}

	for range 3 {
		if err := w.release(context.Background()); err != nil {
			t.Fatalf("release returned error: %v", err)
		}
	}
	reqs := st.updates()
	if len(reqs) != 3 {
		t.Fatalf("Update %d번, want 3", len(reqs))
	}
	for i, req := range reqs {
		if len(req.puts) != 1 || req.puts[0].Key != ReleaseMarkerKey {
			t.Errorf("요청 %d puts %v, want 표지 하나", i, req.puts)
		}
	}
	if got := []int{len(reqs[0].deletes), len(reqs[1].deletes), len(reqs[2].deletes)}; !slices.Equal(got, []int{49, 49, 2}) {
		t.Errorf("지우기 수 %v, want [49 49 2]", got)
	}
	for i := range 100 {
		r := fmt.Sprintf("r%03d", i)
		if _, found := st.value("s/" + r); found {
			t.Errorf("KVS s/%s 가 남았다", r)
		}
		if row, _ := l.row("s", r); row.ReleasedAt == nil {
			t.Errorf("원장 s/%s 가 해제되지 않았다", r)
		}
	}
}

// dupLedger 는 돌려주는 행을 두 번씩 싣는다 — 묶음을 집합으로 만드는지 본다.
type dupLedger struct{ Ledger }

func (d dupLedger) Raise(ctx context.Context, rows []Floor) ([]Floor, error) {
	out, err := d.Ledger.Raise(ctx, rows)
	return append(out, out...), err
}

func (d dupLedger) ClosedUnreleased(ctx context.Context, limit int) ([]Floor, error) {
	out, err := d.Ledger.ClosedUnreleased(ctx, limit)
	return append(out, out...), err
}

func TestBatchesHaveNoDuplicateKeysEvenIfLedgerRepeatsRows(t *testing.T) {
	clock := newTestClock()
	l := newFakeLedger(clock.now)
	st := newFakeStore()
	w := newTestWriter(t, dupLedger{l}, st, testOptions(clock.now, nil))
	l.openRound("s", "open")
	w.Observe("s", "open", 4, clock.now())
	l.seed("s", "closed", 3)
	clock.advance(2 * time.Minute)

	if err := w.raise(context.Background()); err != nil {
		t.Fatalf("raise returned error: %v", err)
	}
	if err := w.release(context.Background()); err != nil {
		t.Fatalf("release returned error: %v", err)
	}
	for i, req := range st.updates() {
		if req.err != nil {
			t.Errorf("요청 %d 가 거절됐다: %v", i, req.err)
		}
	}
}

// 해제 표지 값은 요청마다 다르고 WriterID 로 시작한다 — 시계가 멈춰 있어도(계약 3: 무변경 지우기도 판을 바꾸게).
func TestReleaseMarkerValueIsNewForEveryRequest(t *testing.T) {
	clock := newTestClock()
	l := newFakeLedger(clock.now)
	st := newFakeStore()
	w := newTestWriter(t, l, st, testOptions(clock.now, nil))
	l.seed("s", "a", 1)
	if err := w.release(context.Background()); err != nil {
		t.Fatalf("release returned error: %v", err)
	}
	l.seed("s", "b", 1)
	if err := w.release(context.Background()); err != nil {
		t.Fatalf("release returned error: %v", err)
	}
	reqs := st.updates()
	if len(reqs) != 2 {
		t.Fatalf("Update %d번, want 2", len(reqs))
	}
	a, b := reqs[0].puts[0].Value, reqs[1].puts[0].Value
	if a == b || !strings.HasPrefix(a, "box-a") || !strings.HasPrefix(b, "box-a") {
		t.Errorf("표지 값 %q · %q, want 서로 다르고 WriterID 로 시작", a, b)
	}
}

// 해제 차례(Writer 계약 2): 유예가 지난 해제 행 삭제 → 판 → 닫힌 회차 → 지우기 + 표지 → 원장 해제.
func TestReleaseCallOrder(t *testing.T) {
	clock := newTestClock()
	calls := &callLog{}
	l := newFakeLedger(clock.now)
	l.seed("s", "r", 2)
	w := newTestWriter(t, recLedger{l, calls}, recStore{newFakeStore(), calls}, testOptions(clock.now, nil))

	if err := w.release(context.Background()); err != nil {
		t.Fatalf("release returned error: %v", err)
	}
	want := []string{"DeleteReleased", "Describe", "ClosedUnreleased", "Update", "MarkReleased"}
	if got := calls.snapshot(); !slices.Equal(got, want) {
		t.Errorf("호출 차례 %v, want %v", got, want)
	}
}

// 해제 행은 ReleaseSettle 이 지난 뒤에만 지운다 — 유예 동안 회차별 조회는 ending 이다(설계 2-3).
func TestReleasedRowIsDeletedAfterSettle(t *testing.T) {
	clock := newTestClock()
	rec := &logRecorder{}
	l := newFakeLedger(clock.now)
	l.seed("s", "r", 2)
	w := newTestWriter(t, l, newFakeStore(), testOptions(clock.now, rec))

	if err := w.release(context.Background()); err != nil {
		t.Fatalf("release returned error: %v", err)
	}
	clock.advance(119 * time.Second)
	_ = w.release(context.Background())
	if _, ok := l.row("s", "r"); !ok {
		t.Fatal("유예 안에 해제 행이 지워졌다")
	}
	clock.advance(2 * time.Second)
	_ = w.release(context.Background())
	if _, ok := l.row("s", "r"); ok {
		t.Error("유예가 지났는데 해제 행이 남았다")
	}
	got := rec.named("dvr_floor_released")
	if len(got) != 1 || got[0].attrs["stream"] != "s" || got[0].attrs["round"] != "r" || got[0].attrs["settle_ms"] != int64(120000) {
		t.Errorf("dvr_floor_released %+v, want stream=s round=r settle_ms=120000 한 줄", got)
	}
}

// E7(가짜 몫 · P7): KVS 쓰기가 거부되는 동안 회차가 닫히면 원장 행이 남고(회차별 조회는 ending), 10분이 지나면
// release_overdue 가 선다. 거부가 풀리면 다음 틱에 해제하고 신호들이 짝으로 풀린다.
func TestWriteDeniedKeepsRowThenOverdueThenReleasesOnRecovery(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		rec := &logRecorder{}
		l := newFakeLedger(time.Now)
		l.openRound("s", "r")
		st := newFakeStore()
		w := newTestWriter(t, l, st, testOptions(time.Now, rec))
		w.Observe("s", "r", 7, time.Now())
		stop := runWriter(w)
		defer stop()

		time.Sleep(301 * time.Second) // 10번째 틱(300초)에 올림
		synctest.Wait()
		if v, _ := st.value("s/r"); v != "7" {
			t.Fatalf("Setup: 올림 뒤 KVS s/r = %q, want 7", v)
		}
		st.setDeny(true)
		l.closeRound("s", "r") // 다음 해제 틱(330초)에 처음 닫힘을 본다

		time.Sleep(9 * time.Minute) // 841초
		synctest.Wait()
		if row, _ := l.row("s", "r"); row.ReleasedAt != nil {
			t.Fatal("쓰기 거부 중에 원장 행이 해제됐다")
		}
		if n := len(rec.named("dvr_floor_release_overdue")); n != 0 {
			t.Fatalf("10분 전에 release_overdue %d줄", n)
		}
		checkFailing(t, rec, "release", reasonFailed)

		time.Sleep(90 * time.Second) // 931초 — 930초 틱이 처음 본 330초에서 10분
		synctest.Wait()
		overdue := rec.named("dvr_floor_release_overdue")
		if len(overdue) != 1 || overdue[0].attrs["value"] != int64(1) || overdue[0].attrs["stream"] != "s" || overdue[0].attrs["round"] != "r" {
			t.Fatalf("release_overdue %+v, want value=1 stream=s round=r 한 줄", overdue)
		}

		st.setDeny(false)
		time.Sleep(30 * time.Second) // 960초 틱에 해제
		synctest.Wait()
		if _, found := st.value("s/r"); found {
			t.Error("거부가 풀린 뒤에도 KVS 키가 남았다")
		}
		if row, _ := l.row("s", "r"); row.ReleasedAt == nil {
			t.Error("거부가 풀린 뒤에도 원장 행이 해제되지 않았다")
		}
		overdue = rec.named("dvr_floor_release_overdue")
		if len(overdue) != 2 || overdue[1].attrs["value"] != int64(0) {
			t.Errorf("release_overdue %+v, want set 뒤 clear(value=0)", overdue)
		}
		if got := rec.named("edge_bounds_write_failing"); len(got) != 1 {
			t.Errorf("edge_bounds_write_failing %d줄, want 1(set 한 번)", len(got))
		}
		recovered := rec.named("edge_bounds_write_recovered")
		if len(recovered) != 1 || recovered[0].attrs["pass"] != "release" {
			t.Errorf("edge_bounds_write_recovered %+v, want pass=release 한 줄", recovered)
		}
		released := rec.named("dvr_floor_released")
		if len(released) != 1 || released[0].attrs["waited_ms"] != int64(630000) {
			t.Errorf("dvr_floor_released %+v, want waited_ms=630000 한 줄", released)
		}
	})
}

// 다른 상자가 이미 해제한 회차는(닫힌 미해제 목록이 다 들어왔는데 없음) release_overdue 를 푼다.
func TestOverdueClearsWhenAnotherWriterReleases(t *testing.T) {
	clock := newTestClock()
	rec := &logRecorder{}
	l := newFakeLedger(clock.now)
	l.seed("s", "r", 2)
	st := newFakeStore()
	st.setDeny(true)
	w := newTestWriter(t, l, st, testOptions(clock.now, rec))
	_ = w.release(context.Background())
	clock.advance(10 * time.Minute)
	_ = w.release(context.Background())
	if n := len(rec.named("dvr_floor_release_overdue")); n != 1 {
		t.Fatalf("Setup: release_overdue %d줄, want 1", n)
	}

	if err := l.MarkReleased(context.Background(), []Floor{{StreamID: "s", RoundID: "r"}}); err != nil {
		t.Fatalf("Setup: %v", err)
	}
	_ = w.release(context.Background())
	got := rec.named("dvr_floor_release_overdue")
	if len(got) != 2 || got[1].attrs["value"] != int64(0) {
		t.Errorf("release_overdue %+v, want clear", got)
	}
}

// 해제 패스의 Describe 가 계속 실패하면 원장을 읽지 않는다(판을 얻은 뒤에 읽는다 — Writer 계약 2). 그래서 닫힌 회차를
// 처음 본 시각이 잡히지 않아 10분이 지나도 release_overdue 는 서지 않고, 실패는 write_failing(pass=release) 한 줄로만
// 알린다 — 지금 동작을 못 박는다. 올림 패스는 올릴 후보가 없어 KVS 를 부르지 않으므로 pass=raise 는 없다. 잡는 결함:
// 판보다 원장을 먼저 읽는 순서로 바뀌는 것(이 시험에서는 release_overdue 가 선다).
func TestDescribeFailingRaisesWriteFailingOnlyWithoutOverdue(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		rec := &logRecorder{}
		l := newFakeLedger(time.Now)
		l.seed("s", "r", 7) // 닫힌 미해제 회차
		st := newFakeStore()
		st.describeErr = errors.New("ServiceUnavailableException")
		w := newTestWriter(t, l, st, testOptions(time.Now, rec))
		stop := runWriter(w)
		defer stop()

		time.Sleep(21 * time.Minute) // 해제 틱 42번 · 올림 틱 4번 — 처음 틱에서 10분을 두 번 넘긴다
		synctest.Wait()
		if n := len(rec.named("dvr_floor_release_overdue")); n != 0 {
			t.Errorf("Describe 실패 중에 release_overdue %d줄, want 0(처음 본 시각이 없다)", n)
		}
		checkFailing(t, rec, "release", reasonFailed)
		if row, _ := l.row("s", "r"); row.ReleasedAt != nil {
			t.Error("Describe 실패 중에 원장 행이 해제됐다")
		}
	})
}

// 차단 상태(Writer 계약 6): 원장 하한이 그 회차의 최근 발행 목록 MSN 보다 크면 dvr_floor_blocks_list 가 서고(ERROR ·
// value=1 · 회차 단위) 아니게 되면 같은 이름 value=0 으로 풀린다. 그 동안에도 KVS 에는 원장 값을 보낸다(값을 내리지
// 않는다 — E4). E15 경우다 — 웹 불변식은 최신 사슬 목록만 보장한다(kty (가)).
func TestBlocksListSetWhenLedgerFloorExceedsLatestListAndClears(t *testing.T) {
	clock := newTestClock()
	rec := &logRecorder{}
	l := newFakeLedger(clock.now)
	l.openRound("s", "r")
	l.seed("s", "r", 500) // 다른 상자가 올린 하한
	st := newFakeStore()
	w := newTestWriter(t, l, st, testOptions(clock.now, rec))
	w.Observe("s", "r", 100, clock.now())
	clock.advance(2 * time.Minute)

	for range 2 {
		if err := w.raise(context.Background()); err != nil {
			t.Fatalf("raise returned error: %v", err)
		}
	}
	got := rec.named("dvr_floor_blocks_list")
	if len(got) != 1 || got[0].attrs["value"] != int64(1) || got[0].attrs["stream"] != "s" || got[0].attrs["round"] != "r" {
		t.Fatalf("dvr_floor_blocks_list %+v, want value=1 stream=s round=r 한 줄", got)
	}
	if v, _ := st.value("s/r"); v != "500" {
		t.Errorf("KVS s/r = %q, want 원장 값 500", v)
	}

	w.Observe("s", "r", 600, clock.now())
	clock.advance(2 * time.Minute)
	if err := w.raise(context.Background()); err != nil {
		t.Fatalf("raise returned error: %v", err)
	}
	got = rec.named("dvr_floor_blocks_list")
	if len(got) != 2 || got[1].attrs["value"] != int64(0) {
		t.Errorf("dvr_floor_blocks_list %+v, want set 뒤 clear", got)
	}
}

// 차단된 회차가 닫히면 그 신호도 함께 푼다 — 잊은 회차의 신호가 선 채 남지 않게.
func TestBlocksListClearsWhenRoundCloses(t *testing.T) {
	clock := newTestClock()
	rec := &logRecorder{}
	l := newFakeLedger(clock.now)
	l.openRound("s", "r")
	l.seed("s", "r", 500)
	w := newTestWriter(t, l, newFakeStore(), testOptions(clock.now, rec))
	w.Observe("s", "r", 100, clock.now())
	clock.advance(2 * time.Minute)
	_ = w.raise(context.Background())
	l.closeRound("s", "r")
	_ = w.raise(context.Background())

	got := rec.named("dvr_floor_blocks_list")
	if len(got) != 2 || got[1].attrs["value"] != int64(0) {
		t.Errorf("dvr_floor_blocks_list %+v, want set 뒤 clear", got)
	}
}

// 크기 경고(설계 8-1): KVS 크기가 SizeWarnBytes 를 넘으면 edge_bounds_store_large(WARN)를 넘을 때마다 한 번 낸다.
func TestStoreLargeWarnsOncePerCrossing(t *testing.T) {
	clock := newTestClock()
	rec := &logRecorder{}
	st := newFakeStore()
	opt := testOptions(clock.now, rec)
	opt.SizeWarnBytes = 10
	w := newTestWriter(t, newFakeLedger(clock.now), st, opt)

	st.seed("big", strings.Repeat("x", 20))
	for range 3 {
		_ = w.release(context.Background())
	}
	st.drop("big")
	_ = w.release(context.Background())
	st.seed("big", strings.Repeat("x", 20))
	_ = w.release(context.Background())

	if n := len(rec.named("edge_bounds_store_large")); n != 2 {
		t.Errorf("edge_bounds_store_large %d줄, want 2(넘을 때마다 한 번)", n)
	}
}

// 쓰기 실패 신호는 짝이다 — 실패가 이어져도 set 한 번, 다음 성공에 clear 한 번(pass 마다 따로).
func TestWriteFailingSetAndRecoveredArePaired(t *testing.T) {
	clock := newTestClock()
	rec := &logRecorder{}
	l := newFakeLedger(clock.now)
	st := newFakeStore()
	st.setDeny(true)
	w := newTestWriter(t, l, st, testOptions(clock.now, rec))
	for i := range 3 {
		l.seed("s", fmt.Sprintf("r%d", i), 1)
		w.report("release", w.release(context.Background()))
	}
	st.setDeny(false)
	for range 2 {
		w.report("release", w.release(context.Background()))
	}
	want := []string{"edge_bounds_write_failing", "edge_bounds_write_recovered"}
	var got []string
	for _, m := range rec.messages() {
		if strings.HasPrefix(m, "edge_bounds_write_") {
			got = append(got, m)
		}
	}
	if !slices.Equal(got, want) {
		t.Errorf("쓰기 신호 차례 %v, want %v", got, want)
	}
}

// 쓰기 실패 신호의 set(write_failing)과 clear(write_recovered)는 같은 등급 WARN 이다 — clear 만 낮으면 LOG_LEVEL=warn
// 수집에서 회복이 걸러진다(kty K4 · release_overdue 와 같은 관례). 잡는 결함: write_recovered 를 INFO 로 내는 것.
func TestWriteFailingAndRecoveredAreBothWarn(t *testing.T) {
	clock := newTestClock()
	rec := &logRecorder{}
	l := newFakeLedger(clock.now)
	l.seed("s", "r", 1)
	st := newFakeStore()
	st.setDeny(true)
	w := newTestWriter(t, l, st, testOptions(clock.now, rec))
	w.report("release", w.release(context.Background()))
	st.setDeny(false)
	w.report("release", w.release(context.Background()))

	for _, name := range []string{"edge_bounds_write_failing", "edge_bounds_write_recovered"} {
		got := rec.named(name)
		if len(got) != 1 || got[0].level != slog.LevelWarn {
			t.Errorf("%s %+v, want WARN 한 줄", name, got)
		}
	}
}

// Observe 는 Run 이 도는 동안 다른 고루틴에서 불려도 된다(-race 로 본다).
func TestObserveIsSafeWhileRunning(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		l := newFakeLedger(time.Now)
		l.openRound("s", "r")
		w := newTestWriter(t, l, newFakeStore(), testOptions(time.Now, nil))
		stop := runWriter(w)
		for i := range 700 {
			w.Observe("s", "r", int64(i+1), time.Now())
			time.Sleep(time.Second)
		}
		stop()
		if row, _ := l.row("s", "r"); row.Value < 1 {
			t.Errorf("원장 하한 %d, want 1 이상", row.Value)
		}
	})
}
