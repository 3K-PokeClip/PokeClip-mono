package control

// 회차 경로 GET /api/streams/{streamId}/rounds/{roundId}/rewind 의 시험이다(설계 M5 2.1 RoundRoute · 3.3 · 3.4 ·
// 경계 확정 4 · 보완 2 ㉡ · 보완 3 · R54). 차례는 id 검사 → FindRound → hit 면 공유 칸 → 그 답, Miss 면 1초 캐시
// (적중이면 공유 칸) → singleflight → 전역 예산 → lookup(시한) → 캐시다. miss 는 공유 칸을 깎지 않는다(보완 3).

import (
	"context"
	"errors"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/status"
)

func roundURL(base, s, r string) string {
	return base + "/api/streams/" + s + "/rounds/" + r + "/rewind"
}

// fakeLookup 은 부른 횟수 · 동시 수의 최댓값을 센다. answer 가 nil 이면 늘 vod Found 다.
type fakeLookup struct {
	calls, inFlight, maxInFlight atomic.Int64
	delay                        time.Duration
	answer                       func(s, r string) (status.Status, status.Answer, error)
}

func (l *fakeLookup) fn(ctx context.Context, s, r string) (status.Status, status.Answer, error) {
	l.calls.Add(1)
	n := l.inFlight.Add(1)
	defer l.inFlight.Add(-1)
	for {
		m := l.maxInFlight.Load()
		if n <= m || l.maxInFlight.CompareAndSwap(m, n) {
			break
		}
	}
	if l.delay > 0 {
		select {
		case <-ctx.Done():
			return status.Status{}, 0, ctx.Err()
		case <-time.After(l.delay):
		}
	}
	if l.answer != nil {
		return l.answer(s, r)
	}
	return vodSt, status.Found, nil
}

func TestRoundRouteHitDoesNotLookup(t *testing.T) {
	f := newFixture(10)
	f.store(true, map[string]status.Entry{"s": vodEntry("R1")})
	l := &fakeLookup{}
	base := f.start(t, RoundRoute(f.holder, f.opt, l.fn))
	if got := get(t, roundURL(base, "s", "R1")); got.code != 200 || !strings.Contains(got.body, `"mode":"vod"`) {
		t.Errorf("hit = (%d, %s), want 200 vod", got.code, got.body)
	}
	if l.calls.Load() != 0 {
		t.Errorf("hit 의 lookup = %d, want 0", l.calls.Load())
	}
}

// miss 는 lookup 한 번이고 결과는 1초 캐시가 받는다 — 1초 안의 같은 요청은 lookup 을 다시 부르지 않는다.
func TestRoundRouteMissLooksUpOnceThenCaches(t *testing.T) {
	f := newFixture(10)
	f.store(true, nil)
	l := &fakeLookup{}
	base := f.start(t, RoundRoute(f.holder, f.opt, l.fn))
	for range 2 {
		if got := get(t, roundURL(base, "x", "R9")); got.code != 200 {
			t.Errorf("miss = %d, want 200", got.code)
		}
	}
	if l.calls.Load() != 1 {
		t.Errorf("1초 안 같은 요청 둘의 lookup = %d, want 1", l.calls.Load())
	}
	f.clock.Advance(time.Second)
	f.store(true, nil)
	get(t, roundURL(base, "x", "R9"))
	if l.calls.Load() != 2 {
		t.Errorf("1초 뒤 lookup = %d, want 2", l.calls.Load())
	}
}

// cx c2 M1 · cc c2 L1: 1초 캐시는 lookup 이 끝난 시각부터 센다 — 1.2초 걸린 lookup 의 답도 바로 다음 요청에 적중한다.
// 시작 시각으로 세면 저장 직후 이미 만료돼 다음 요청이 DB 를 다시 부른다.
func TestRoundRouteCacheCountsFromLookupEnd(t *testing.T) {
	f := newFixture(10)
	f.store(true, nil)
	l := &fakeLookup{answer: func(s, r string) (status.Status, status.Answer, error) {
		f.clock.Advance(1200 * time.Millisecond)
		return vodSt, status.Found, nil
	}}
	base := f.start(t, RoundRoute(f.holder, f.opt, l.fn))
	for range 2 {
		if got := get(t, roundURL(base, "x", "R9")); got.code != 200 {
			t.Errorf("miss = %d, want 200", got.code)
		}
	}
	if c := l.calls.Load(); c != 1 {
		t.Errorf("느린 lookup 바로 뒤 같은 요청의 lookup = %d, want 1(캐시 적중)", c)
	}
	f.clock.Advance(time.Second)
	f.store(true, nil)
	get(t, roundURL(base, "x", "R9"))
	if c := l.calls.Load(); c != 2 {
		t.Errorf("끝난 시각 1초 뒤 lookup = %d, want 2", c)
	}
}

// lookup 의 답 갈래: NotFound → 404(캐시) · Unavailable · 오류 → 503(캐시 안 함) · Live → 200 live url null(M5 보충).
func TestRoundRouteLookupAnswers(t *testing.T) {
	f := newFixture(10)
	f.store(true, nil)
	l := &fakeLookup{answer: func(s, r string) (status.Status, status.Answer, error) {
		switch r {
		case "gone":
			return status.Status{}, status.NotFound, nil
		case "busy":
			return status.Status{}, status.Unavailable, errors.New("풀 고갈")
		case "live":
			return status.Status{Mode: status.ModeLive}, status.Found, nil
		}
		return status.Status{}, status.Found, errors.New("변환 오류")
	}}
	base := f.start(t, RoundRoute(f.holder, f.opt, l.fn))
	for _, c := range []struct {
		round, body string
		code        int
	}{
		{"gone", `{"error":"not_found"}`, 404},
		{"busy", `{"error":"unavailable"}`, 503},
		{"bad", `{"error":"unavailable"}`, 503},
		{"live", `{"ready":false,"mode":"live","url":null,"firstPdt":null,"headPdt":null}`, 200},
	} {
		for range 2 {
			if got := get(t, roundURL(base, "s", c.round)); got.code != c.code || strings.TrimSpace(got.body) != c.body {
				t.Errorf("%s: (%d, %s), want (%d, %s)", c.round, got.code, got.body, c.code, c.body)
			}
		}
	}
	// gone · live 는 캐시돼 한 번씩, busy · bad 는 캐시되지 않아 두 번씩.
	if l.calls.Load() != 6 {
		t.Errorf("lookup = %d, want 6", l.calls.Load())
	}
}

// singleflight: 캐시가 빈 순간 같은 (스트림, 회차) 동시 50 은 lookup 하나를 기다린다.
func TestRoundRouteSingleflight(t *testing.T) {
	f := newFixture(1000)
	f.opt.RoundLimits.MaxInFlight, f.opt.RoundLimits.PerSecond = 100, 1000
	f.store(true, nil)
	l := &fakeLookup{delay: 300 * time.Millisecond}
	base := f.start(t, RoundRoute(f.holder, f.opt, l.fn))
	var wg sync.WaitGroup
	start := make(chan struct{})
	codes := make([]int, 50)
	for i := range codes {
		wg.Go(func() {
			<-start
			codes[i] = get(t, roundURL(base, "x", "R9")).code
		})
	}
	close(start)
	wg.Wait()
	if l.calls.Load() != 1 {
		t.Errorf("동시 50 의 lookup = %d, want 1", l.calls.Load())
	}
	for i, c := range codes {
		if c != 200 {
			t.Errorf("%d번째 = %d, want 200", i, c)
		}
	}
}

// lookup 시한: lookup 이 시한보다 오래 걸리면 처리기가 시한에 503 으로 끝낸다(캐시 안 함).
func TestRoundRouteLookupTimeout(t *testing.T) {
	f := newFixture(10)
	f.opt.RoundLimits.Timeout = 200 * time.Millisecond
	f.store(true, nil)
	l := &fakeLookup{delay: 1500 * time.Millisecond}
	base := f.start(t, RoundRoute(f.holder, f.opt, l.fn))
	began := time.Now()
	got := get(t, roundURL(base, "x", "R9"))
	if took := time.Since(began); got.code != 503 || took > time.Second {
		t.Errorf("느린 lookup = (%d, %v), want (503, 시한 근처)", got.code, took)
	}
	if fails := f.logs.events("control_round_lookup_failed"); len(fails) != 1 || fails[0]["reason"] != "timeout" {
		t.Errorf("control_round_lookup_failed = %v, want reason=timeout 한 줄", fails)
	}
}

func TestDefaultRoundLimits(t *testing.T) {
	want := RoundLimits{MaxInFlight: 2, PerSecond: 20, Timeout: 2 * time.Second, CacheCells: 4096}
	if got := DefaultRoundLimits(); got != want {
		t.Errorf("DefaultRoundLimits = %+v, want %+v", got, want)
	}
}

// 무작위 스트림 · 회차 1,000건(1초 안) → lookup ≤ 20 · 동시 ≤ 2 · 칸 ≤ 상한 · 그동안 다른 스트림의 스트림 경로는 정상.
func TestRoundRouteRandomFloodIsBounded(t *testing.T) {
	f := newFixture(10)
	f.store(true, map[string]status.Entry{"other": vodEntry("R1")})
	l := &fakeLookup{delay: 5 * time.Millisecond}
	base := f.start(t, RewindRoute(f.holder, f.opt), RoundRoute(f.holder, f.opt, l.fn))
	var wg sync.WaitGroup
	for w := range 8 {
		wg.Go(func() {
			for i := w; i < 1000; i += 8 {
				get(t, roundURL(base, "s"+strconv.Itoa(i), "r"+strconv.Itoa(i)))
			}
		})
	}
	for range 5 {
		if got := get(t, streamURL(base, "other")); got.code != 200 {
			t.Errorf("폭주 중 다른 스트림의 스트림 경로 = %d, want 200", got.code)
		}
	}
	wg.Wait()
	if c, m := l.calls.Load(), l.maxInFlight.Load(); c > 20 || m > 2 {
		t.Errorf("lookup = %d · 동시 최대 = %d, want ≤ 20 · ≤ 2", c, m)
	}
	if n := cellCount(f.opt.Limiter); n > 4096 {
		t.Errorf("Limiter 칸 = %d, want ≤ 4096", n)
	}
	if shed := f.logs.countValue("control_round_shed", 1); shed != 1 {
		t.Errorf("control_round_shed 세움 = %d, want 1", shed)
	}
}

// 보완 3 ⓐ: 같은 스트림 s 에 무작위 roundId 1,000건이 와도 s 의 스트림 경로는 1초 1회 호출에 200 이다 — miss 는 공유
// 칸을 깎지 않는다. lookup 은 전역 예산에 묶인다.
func TestRoundRouteMissDoesNotDrainSharedCell(t *testing.T) {
	f := newFixture(10)
	f.store(true, map[string]status.Entry{"s": vodEntry("R1")})
	l := &fakeLookup{}
	base := f.start(t, RewindRoute(f.holder, f.opt), RoundRoute(f.holder, f.opt, l.fn))
	for i := range 1000 {
		get(t, roundURL(base, "s", "r"+strconv.Itoa(i)))
		if i%100 == 99 {
			if got := get(t, streamURL(base, "s")); got.code != 200 {
				t.Fatalf("%d건 뒤 s 의 스트림 경로 = %d, want 200 — miss 가 공유 칸을 깎았다", i+1, got.code)
			}
		}
	}
	if c := l.calls.Load(); c > 20 {
		t.Errorf("lookup = %d, want ≤ 20", c)
	}
}

// 확정 4 「한도 공유」: 답한 요청(스트림 경로 Found · 회차 hit · 캐시 적중)은 같은 칸을 센다 — 스트림 경로 10회 뒤
// 회차 hit 첫 요청은 503 이다.
func TestRoundRouteSharesCellWithStreamPath(t *testing.T) {
	f := newFixture(10)
	f.store(true, map[string]status.Entry{"s": vodEntry("R1")})
	base := f.start(t, RewindRoute(f.holder, f.opt), RoundRoute(f.holder, f.opt, (&fakeLookup{}).fn))
	for range 10 {
		get(t, streamURL(base, "s"))
	}
	if got := get(t, roundURL(base, "s", "R1")); got.code != 503 {
		t.Errorf("스트림 경로 10회 뒤 회차 hit = %d, want 503", got.code)
	}
}

// 1초 캐시 적중도 답한 요청이라 공유 칸을 센다.
func TestRoundRouteCacheHitCountsSharedCell(t *testing.T) {
	f := newFixture(3)
	f.store(true, nil)
	base := f.start(t, RoundRoute(f.holder, f.opt, (&fakeLookup{}).fn))
	codes := ""
	for range 5 {
		codes += strconv.Itoa(get(t, roundURL(base, "x", "R9")).code) + " "
	}
	// 첫 요청은 miss(칸 무관) · 다음 셋은 캐시 적중(칸 1–3) · 다섯째는 칸 초과.
	if codes != "200 200 200 200 503 " {
		t.Errorf("응답 = %q, want 200 ×4 · 503", codes)
	}
}

// 보안 L2 · cc c2 L3: 1초 캐시의 404 적중은 공유 칸을 열지 않는다 — 무작위 (s, r) 의 404 가 Board 밖 스트림 칸을 만들지
// 않게. 404 는 답이 없는 요청이라 「답한 요청만 센다」에도 들지 않는다.
func TestRoundRouteCachedNotFoundOpensNoCell(t *testing.T) {
	f := newFixture(10)
	f.store(true, nil)
	l := &fakeLookup{answer: func(s, r string) (status.Status, status.Answer, error) {
		return status.Status{}, status.NotFound, nil
	}}
	base := f.start(t, RoundRoute(f.holder, f.opt, l.fn))
	for i := range 15 {
		for range 2 { // 둘째는 캐시 적중
			if got := get(t, roundURL(base, "g"+strconv.Itoa(i), "R1")); got.code != 404 {
				t.Fatalf("g%d = %d, want 404", i, got.code)
			}
		}
	}
	if c, n := l.calls.Load(), cellCount(f.opt.Limiter); c != 15 || n != 0 {
		t.Errorf("lookup %d · 칸 %d, want 15 · 0", c, n)
	}
}

// R54 ⓑ: 칸 상한 64 를 주입하면 hit 200 스트림 · miss 200 쌍 뒤에도 Limiter 칸 · 캐시 칸이 64 이하다. 세움 상태로
// 축출된 칸마다 내림 줄이 하나다.
func TestRoundRouteCellCaps(t *testing.T) {
	f := newFixture(1)
	f.opt.Limiter = NewLimiter(1, 64, f.clock.Now, f.opt.Log)
	f.opt.RoundLimits = RoundLimits{MaxInFlight: 1000, PerSecond: 100000, Timeout: time.Second, CacheCells: 64}
	entries := map[string]status.Entry{}
	for i := range 200 {
		entries["h"+strconv.Itoa(i)] = vodEntry("R1")
	}
	f.store(true, entries)
	rr := RoundRoute(f.holder, f.opt, (&fakeLookup{}).fn)
	base := f.start(t, rr)
	for i := range 200 {
		get(t, roundURL(base, "h"+strconv.Itoa(i), "R1"))
		get(t, roundURL(base, "h"+strconv.Itoa(i), "R1")) // 칸 세움
		get(t, roundURL(base, "m"+strconv.Itoa(i), "R1"))
	}
	if n := cellCount(f.opt.Limiter); n > 64 {
		t.Errorf("Limiter 칸 = %d, want ≤ 64", n)
	}
	if n := cacheCount(rr); n > 64 {
		t.Errorf("캐시 칸 = %d, want ≤ 64", n)
	}
	if set, clear := f.logs.countValue("control_rate_limited", 1), f.logs.countWarnValue("control_rate_limited", 0); set != 200 || clear != 200-64 {
		t.Errorf("control_rate_limited 세움 %d · WARN 내림 %d, want 200 · %d", set, clear, 200-64)
	}
}

// 청소: 1초 지난 캐시 칸은 지우고, 60초 동안 거부가 없으면 control_round_shed 를 내린다.
func TestRoundRouteSweep(t *testing.T) {
	f := newFixture(10)
	f.opt.RoundLimits.PerSecond = 1
	f.store(true, nil)
	rr := RoundRoute(f.holder, f.opt, (&fakeLookup{}).fn)
	base := f.start(t, rr)
	get(t, roundURL(base, "x", "a"))
	if got := get(t, roundURL(base, "x", "b")); got.code != 503 {
		t.Fatalf("초당 예산 초과 = %d, want 503", got.code)
	}
	f.clock.Advance(59 * time.Second)
	rr.Handler.(*roundHandler).sweep(f.clock.Now())
	if cacheCount(rr) != 0 || f.logs.countValue("control_round_shed", 0) != 0 {
		t.Errorf("59초 청소: 캐시 %d · 내림 %d, want 0 · 0", cacheCount(rr), f.logs.countValue("control_round_shed", 0))
	}
	f.clock.Advance(time.Second)
	rr.Handler.(*roundHandler).sweep(f.clock.Now())
	if f.logs.countWarnValue("control_round_shed", 0) != 1 {
		t.Errorf("60초 청소 뒤 control_round_shed WARN 내림 = %d, want 1", f.logs.countWarnValue("control_round_shed", 0))
	}
}

// 3.1절 규칙은 회차 id 에도 건다 — 어기면 lookup 없이 404 다.
func TestRoundRouteRejectsBadIDs(t *testing.T) {
	f := newFixture(10)
	f.store(true, nil)
	l := &fakeLookup{}
	base := f.start(t, RoundRoute(f.holder, f.opt, l.fn))
	for _, c := range [][2]string{{"..", "R1"}, {"s", ".."}, {"s", strings.Repeat("r", 129)}, {"s", "a%20b"}} {
		if got := get(t, roundURL(base, c[0], c[1])); got.code != 404 {
			t.Errorf("%v = %d, want 404", c, got.code)
		}
	}
	if l.calls.Load() != 0 {
		t.Errorf("lookup = %d, want 0", l.calls.Load())
	}
}

// 보안 c3 M1: 두 id 가운데 하나라도 퍼센트 인코딩이면 lookup · 칸 없이 404 다.
func TestRoundRoutePercentEncodedPathIsNotFound(t *testing.T) {
	f := newFixture(10)
	f.store(true, map[string]status.Entry{"abc": vodEntry("R1")})
	l := &fakeLookup{}
	base := f.start(t, RoundRoute(f.holder, f.opt, l.fn))
	for _, c := range [][2]string{{"%61bc", "R1"}, {"abc", "%52%31"}, {"x", "%61"}} {
		if got := get(t, roundURL(base, c[0], c[1])); got.code != 404 {
			t.Errorf("%v = %d, want 404", c, got.code)
		}
	}
	if l.calls.Load() != 0 || cellCount(f.opt.Limiter) != 0 {
		t.Errorf("lookup %d · 칸 %d, want 0 · 0", l.calls.Load(), cellCount(f.opt.Limiter))
	}
}

// 보안 c2 M2: lookup 이 패닉해도 그 요청은 503 이고, 같은 키를 기다리던 요청도 503 으로 풀리며, 전역 예산 · flight 칸이
// 돌아온다 — 패닉 둘(동시 상한)에 회차 경로 전체가 재기동까지 503 으로 굳지 않는다. 다음 키는 200 이다.
func TestRoundRouteLookupPanicReleasesBudget(t *testing.T) {
	f := newFixture(10)
	f.store(true, nil)
	entered, release := make(chan struct{}), make(chan struct{})
	var once sync.Once
	l := &fakeLookup{answer: func(s, r string) (status.Status, status.Answer, error) {
		if r == "ok" {
			return vodSt, status.Found, nil
		}
		if r == "wait" {
			once.Do(func() { close(entered) })
			<-release
		}
		panic("lookup 결함")
	}}
	rr := RoundRoute(f.holder, f.opt, l.fn)
	base := f.start(t, rr)
	waiter := make(chan reply, 2)
	go func() { waiter <- get(t, roundURL(base, "x", "wait")) }()
	waitFor(t, entered, "패닉 키 lookup 진입")
	go func() { waiter <- get(t, roundURL(base, "x", "wait")) }()
	time.Sleep(50 * time.Millisecond) // 둘째 요청이 flight 를 기다리게 — 늦게 닿아도 같은 키 lookup 이 다시 패닉해 503 이다
	close(release)
	for range 2 {
		var got reply
		select {
		case got = <-waiter:
		case <-time.After(testWait):
			t.Fatal("패닉 키 요청이 풀리지 않았다")
		}
		if got.code != 503 {
			t.Errorf("패닉 키(첫 요청 · 기다린 요청) = %d, want 503", got.code)
		}
	}
	for _, r := range []string{"p1", "p2", "p3"} {
		if got := get(t, roundURL(base, "x", r)); got.code != 503 {
			t.Errorf("패닉 %s = %d, want 503", r, got.code)
		}
	}
	if got := get(t, roundURL(base, "x", "ok")); got.code != 200 {
		t.Errorf("패닉 넷 뒤 다음 키 = %d, want 200", got.code)
	}
	h := rr.Handler.(*roundHandler)
	h.mu.Lock()
	inFlight, flights := h.inFlight, len(h.flights)
	h.mu.Unlock()
	if inFlight != 0 || flights != 0 {
		t.Errorf("패닉 뒤 inFlight %d · flight 칸 %d, want 0 · 0", inFlight, flights)
	}
	if fails := f.logs.events("control_round_lookup_failed"); len(fails) == 0 || fails[0]["reason"] != "panic" {
		t.Errorf("control_round_lookup_failed = %v, want reason=panic", fails)
	}
}

// lookup 실패 줄은 10초에 한 줄로 묶는다(count) — 무작위 폭주에 줄 수가 같이 늘지 않게.
func TestRoundRouteLookupFailuresAreBatched(t *testing.T) {
	f := newFixture(10)
	f.opt.RoundLimits.PerSecond = 1000
	f.store(true, nil)
	l := &fakeLookup{answer: func(s, r string) (status.Status, status.Answer, error) {
		return status.Status{}, status.Unavailable, errors.New("db")
	}}
	base := f.start(t, RoundRoute(f.holder, f.opt, l.fn))
	for i := range 5 {
		get(t, roundURL(base, "x", "r"+strconv.Itoa(i)))
	}
	f.clock.Advance(10 * time.Second)
	get(t, roundURL(base, "x", "last"))
	lines := f.logs.events("control_round_lookup_failed")
	if len(lines) != 2 || lines[0]["count"] != 1.0 || lines[1]["count"] != 5.0 || lines[0]["reason"] != "error" {
		t.Errorf("control_round_lookup_failed = %v, want count 1(첫 줄) · 5(10초 뒤 묶음)", lines)
	}
}

// 묶음 꼬리는 다음 실패가 없어도 청소가 내보낸다 — 10초가 지나면 센 실패를 count 로 한 줄 쓰고 비운다(설계 8절
// 「10초에 한 줄로 묶음」). 그래서 실패 줄의 count 합은 실패 수와 같다.
func TestRoundRouteLookupFailureTailFlushedBySweep(t *testing.T) {
	f := newFixture(10)
	f.opt.RoundLimits.PerSecond = 1000
	f.store(true, nil)
	l := &fakeLookup{answer: func(s, r string) (status.Status, status.Answer, error) {
		return status.Status{}, status.Unavailable, errors.New("db")
	}}
	rr := RoundRoute(f.holder, f.opt, l.fn)
	base := f.start(t, rr)
	for i := range 40 {
		get(t, roundURL(base, "x", "r"+strconv.Itoa(i)))
	}
	sweep := rr.Handler.(*roundHandler).sweep
	f.clock.Advance(9 * time.Second)
	sweep(f.clock.Now())
	if lines := f.logs.events("control_round_lookup_failed"); len(lines) != 1 {
		t.Fatalf("9초 청소 뒤 실패 줄 = %v, want 첫 줄 하나(묶음 시간 안)", lines)
	}
	f.clock.Advance(time.Second)
	sweep(f.clock.Now())
	sweep(f.clock.Now())
	if lines, total := failureLines(f.logs); len(lines) != 2 || total != 40 {
		t.Errorf("10초 청소 뒤 실패 줄 = %v, want 두 줄 · count 합 40", lines)
	}
	f.clock.Advance(10 * time.Second)
	sweep(f.clock.Now())
	if lines, _ := failureLines(f.logs); len(lines) != 2 {
		t.Errorf("센 실패 없는 청소 뒤 실패 줄 = %v, want 그대로 두 줄", lines)
	}
	get(t, roundURL(base, "x", "last"))
	if lines, total := failureLines(f.logs); len(lines) != 3 || total != 41 {
		t.Errorf("다음 실패 뒤 실패 줄 = %v, want 세 줄 · count 합 41(비운 꼬리를 다시 세지 않음)", lines)
	}
}

// failureLines 는 control_round_lookup_failed 줄과 그 count 합이다.
func failureLines(logs *logSink) ([]map[string]any, float64) {
	lines := logs.events("control_round_lookup_failed")
	total := 0.0
	for _, e := range lines {
		c, _ := e["count"].(float64)
		total += c
	}
	return lines, total
}

// 초당 예산: 한 초 안에서 차례로 온 서로 다른 miss 100건(동시 1)은 lookup 이 정확히 PerSecond 번이다. 다음 초에는
// 새로 받는다.
func TestRoundRoutePerSecondBudget(t *testing.T) {
	f := newFixture(10)
	f.store(true, nil)
	l := &fakeLookup{}
	base := f.start(t, RoundRoute(f.holder, f.opt, l.fn))
	shed := 0
	for i := range 100 {
		if get(t, roundURL(base, "x", "r"+strconv.Itoa(i))).code == 503 {
			shed++
		}
	}
	if c := l.calls.Load(); c != 20 || shed != 80 {
		t.Errorf("한 초 100건: lookup %d · 503 %d, want 20 · 80", c, shed)
	}
	f.clock.Advance(time.Second)
	f.store(true, nil)
	get(t, roundURL(base, "x", "next"))
	if c := l.calls.Load(); c != 21 {
		t.Errorf("다음 초 lookup = %d, want 21", c)
	}
}
