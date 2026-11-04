package edgebounds

// 끼어들기 순서 전수 검증(POK-525 설계 3-3 Writer 계약 3 · 8-2 E1 · E4 · 계획 Ⓒ-4). 행위자(Writer 의 한 패스 · 회차
// 닫기)마다 원장 · KVS 호출 앞에서 멈추게 하고, 멈춘 행위자 가운데 하나씩 골라 풀어 주는 모든 차례를 깊이 우선으로
// 돌린다. 판은 가짜 KVS 가 실측대로 흉내 낸다(무변경 지우기는 판 불변 — H3-1 · H3-4).

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"
)

// gate 는 행위자 하나의 멈춤 자리다. wait 는 runInterleaved 가 행위자를 띄울 때 채운다.
type gate struct {
	name  string
	wait  func()
	trace *[]string
	mu    *sync.Mutex
}

// pass 는 호출 이름을 적고 차례를 기다린다.
func (g *gate) pass(call string) {
	g.wait()
	g.mu.Lock()
	defer g.mu.Unlock()
	*g.trace = append(*g.trace, g.name+":"+call)
}

// gatedLedger · gatedStore 는 차례가 갈리는 호출(판 읽기 · 원장 읽기 · 쓰기)마다 gate 를 지난다. DeleteReleased ·
// Fetch 는 판 결속과 무관해 바로 넘긴다(가짓수를 줄인다).
type gatedLedger struct {
	Ledger
	g *gate
}

func (l gatedLedger) Raise(ctx context.Context, rows []Floor) ([]Floor, error) {
	l.g.pass("Raise")
	return l.Ledger.Raise(ctx, rows)
}

func (l gatedLedger) ClosedUnreleased(ctx context.Context, limit int) ([]Floor, error) {
	l.g.pass("ClosedUnreleased")
	return l.Ledger.ClosedUnreleased(ctx, limit)
}

func (l gatedLedger) MarkReleased(ctx context.Context, rows []Floor) error {
	l.g.pass("MarkReleased")
	return l.Ledger.MarkReleased(ctx, rows)
}

type gatedStore struct {
	Store
	g *gate
}

func (s gatedStore) Describe(ctx context.Context) (Stat, error) {
	s.g.pass("Describe")
	return s.Store.Describe(ctx)
}

func (s gatedStore) Update(ctx context.Context, ifMatch string, puts []Entry, deletes []string) (string, error) {
	s.g.pass("Update")
	return s.Store.Update(ctx, ifMatch, puts, deletes)
}

// actor 는 행위자 하나다 — g 를 거쳐 멈추며 할 일을 한다.
type actor struct {
	g   *gate
	run func()
}

// runInterleaved 는 행위자들을 고루틴으로 띄우고, 모두가 멈춤 자리에 서거나 끝날 때마다 choose 가 고른 하나만 다음
// 멈춤 자리까지 풀어 준다 — 한 번에 하나만 돌므로 호출 차례가 고름 열로 정해진다.
func runInterleaved(actors []actor, choose func(n int) int) {
	n := len(actors)
	const done = -1
	events := make(chan [2]int, n) // {행위자, done 또는 0}
	resume := make([]chan struct{}, n)
	for i, a := range actors {
		resume[i] = make(chan struct{})
		a.g.wait = func() {
			events <- [2]int{i, 0}
			<-resume[i]
		}
		go func() {
			defer func() { events <- [2]int{i, done} }()
			a.run()
		}()
	}
	parked := make([]bool, n)
	live, pending := n, n
	for {
		for ; pending > 0; pending-- {
			e := <-events
			if e[1] == done {
				live--
				continue
			}
			parked[e[0]] = true
		}
		if live == 0 {
			return
		}
		var ready []int
		for i, p := range parked {
			if p {
				ready = append(ready, i)
			}
		}
		pick := ready[choose(len(ready))]
		parked[pick] = false
		pending = 1
		resume[pick] <- struct{}{}
	}
}

// eachInterleaving 은 run 을 가능한 모든 고름 열로 한 번씩 부르고 그 수를 돌려준다(깊이 우선 — 고름 열의 앞부분이 같으면
// 상태가 같다는 것에 기댄다. 시계는 멈춰 있다).
func eachInterleaving(run func(choose func(n int) int)) int {
	var script []int
	for count := 1; ; count++ {
		var chosen, widths []int
		run(func(n int) int {
			c := 0
			if i := len(chosen); i < len(script) {
				c = script[i]
			}
			chosen = append(chosen, c)
			widths = append(widths, n)
			return c
		})
		next := -1
		for j := len(chosen) - 1; j >= 0; j-- {
			if chosen[j]+1 < widths[j] {
				next = j
				break
			}
		}
		if next < 0 {
			return count
		}
		script = append(chosen[:next:next], chosen[next]+1)
	}
}

// world 는 한 차례의 원장 · KVS · 호출 기록이다.
type world struct {
	clock *testClock
	l     *fakeLedger
	st    *fakeStore
	mu    sync.Mutex
	trace []string
}

func newWorld() *world {
	clock := newTestClock()
	return &world{clock: clock, l: newFakeLedger(clock.now), st: newFakeStore()}
}

// actorWriter 는 이 세상의 원장 · KVS 를 g 로 감싼 Writer 다.
func (w *world) actorWriter(t *testing.T, name string) (*Writer, *gate) {
	t.Helper()
	g := &gate{name: name, trace: &w.trace, mu: &w.mu}
	opt := testOptions(w.clock.now, nil)
	opt.WriterID = name
	return newTestWriter(t, gatedLedger{w.l, g}, gatedStore{w.st, g}, opt), g
}

// 한 번 지운 키는 되살아나지 않는다(Writer 계약 3 · E1). 올림(상자 A) · 해제(상자 B) · 회차 닫기의 모든 차례에서, 끝에
// 해제 한 번을 더 돌리면 KVS 에 키가 없고 원장 행은 해제돼 있다. 「첫 올림 전」 은 KVS 에 키가 아직 없는 자리다 — 거기서
// 해제가 지우기만 보내면 판이 그대로라 옛 판으로 보낸 put 이 통과해 키가 되살아난다(H3-4). 해제 표지(_release)가 그
// 판을 바꾼다. 잡는 결함: 표지 put 을 빼거나, 판보다 원장을 먼저 읽거나, 실패 뒤 원장을 다시 읽지 않고 옛 값을 보내는 것.
func TestReleasedKeyNeverReappearsUnderAnyInterleaving(t *testing.T) {
	for _, seeded := range []bool{false, true} {
		name := map[bool]string{false: "첫 올림 전", true: "올림 뒤"}[seeded]
		t.Run(name, func(t *testing.T) {
			n := eachInterleaving(func(choose func(int) int) {
				w := newWorld()
				w.l.openRound("s", "r")
				if seeded {
					w.l.seed("s", "r", 5)
					w.st.seed("s/r", "5")
				}
				raiser, ga := w.actorWriter(t, "A")
				releaser, gb := w.actorWriter(t, "B")
				raiser.Observe("s", "r", 9, w.clock.now().Add(-2*time.Minute))
				gc := &gate{name: "C", trace: &w.trace, mu: &w.mu}
				runInterleaved([]actor{
					{ga, func() { _ = raiser.raise(context.Background()) }},
					{gb, func() { _ = releaser.release(context.Background()) }},
					{gc, func() { gc.pass("close"); w.l.closeRound("s", "r") }},
				}, choose)

				final := newTestWriter(t, w.l, w.st, testOptions(w.clock.now, nil))
				if err := final.release(context.Background()); err != nil {
					t.Fatalf("마무리 해제 returned error: %v (차례 %s)", err, strings.Join(w.trace, " "))
				}
				if v, found := w.st.value("s/r"); found {
					t.Fatalf("해제 뒤 KVS s/r = %q 가 되살아났다 — 차례 %s", v, strings.Join(w.trace, " "))
				}
				if row, ok := w.l.row("s", "r"); ok && row.ReleasedAt == nil {
					t.Fatalf("원장 행이 해제되지 않았다 — 차례 %s", strings.Join(w.trace, " "))
				}
			})
			t.Logf("끼어들기 %d가지", n)
		})
	}
}

// 두 Writer 가 하한을 내리지 않는다(E4). 서로 다른 관측(100 · 200)으로 함께 올리는 모든 차례에서, KVS 의 그 키에 반영된
// put 은 오르기만 하고 끝값은 원장 값 200 이다 — 원장 GREATEST 와 판 결속(E 를 얻은 뒤 원장 읽기)이 함께 지킨다. 잡는
// 결함: 관측값을 그대로 보내거나(원장 값 대신), 경합 뒤 원장을 다시 읽지 않고 옛 값을 보내는 것.
func TestTwoWritersNeverLowerFloorUnderAnyInterleaving(t *testing.T) {
	for _, seeded := range []bool{false, true} {
		name := map[bool]string{false: "행 없음", true: "행 50"}[seeded]
		t.Run(name, func(t *testing.T) {
			n := eachInterleaving(func(choose func(int) int) {
				w := newWorld()
				w.l.openRound("s", "r")
				if seeded {
					w.l.seed("s", "r", 50)
					w.st.seed("s/r", "50")
				}
				a, ga := w.actorWriter(t, "A")
				b, gb := w.actorWriter(t, "B")
				past := w.clock.now().Add(-2 * time.Minute)
				a.Observe("s", "r", 100, past)
				b.Observe("s", "r", 200, past)
				runInterleaved([]actor{
					{ga, func() { _ = a.raise(context.Background()) }},
					{gb, func() { _ = b.raise(context.Background()) }},
				}, choose)

				trace := strings.Join(w.trace, " ")
				var prev int64
				for _, s := range w.st.putHistory("s/r") {
					v, err := DecodeValue(s)
					if err != nil || v < prev {
						t.Fatalf("KVS s/r put 차례 %v 가 내려갔다 — 차례 %s", w.st.putHistory("s/r"), trace)
					}
					prev = v
				}
				v, _ := w.st.value("s/r")
				if row, _ := w.l.row("s", "r"); v != "200" || row.Value != 200 {
					t.Fatalf("끝 KVS %q · 원장 %d, want 200 · 200 — 차례 %s", v, row.Value, trace)
				}
			})
			t.Logf("끼어들기 %d가지", n)
		})
	}
}

// 차례 조정기 자신의 검증 — 행위자 둘이 두 걸음씩이면 차례는 C(4,2) = 6 가지다.
func TestEachInterleavingEnumeratesAllOrders(t *testing.T) {
	seen := map[string]bool{}
	n := eachInterleaving(func(choose func(int) int) {
		var mu sync.Mutex
		var trace []string
		ga := &gate{name: "a", trace: &trace, mu: &mu}
		gb := &gate{name: "b", trace: &trace, mu: &mu}
		runInterleaved([]actor{
			{ga, func() { ga.pass("1"); ga.pass("2") }},
			{gb, func() { gb.pass("1"); gb.pass("2") }},
		}, choose)
		seen[fmt.Sprint(trace)] = true
	})
	if n != 6 || len(seen) != 6 {
		t.Errorf("차례 %d가지 · 서로 다른 것 %d, want 6 · 6", n, len(seen))
	}
}
