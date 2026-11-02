package publish

// VOD 사본 복사 엔진의 시험이다(POK-525 설계 r7 4-2 · 4-3 — copyPairs · copyBudget · copyAll · 기동 검사).

import (
	"context"
	"errors"
	"fmt"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// copyStub 은 Copy 만 흉내 내는 Store 다 — 나머지 메서드는 부르면 패닉이다(복사 엔진은 Copy 만 부른다). fn 이 nil 이면 늘
// 성공이다. 호출 수 · 동시 진행 수 · 끝난 ctx 로 들어온 호출 수를 센다.
type copyStub struct {
	Store

	fn func(ctx context.Context, src, dst string) error

	calls    atomic.Int64
	deadCtx  atomic.Int64 // 이미 끝난 ctx 로 들어온 호출 — 취소 뒤 보낸 Copy 다
	inFlight atomic.Int64
	maxIn    atomic.Int64

	mu     sync.Mutex
	perSrc map[string]int
	copied []copyPair // 성공한 호출
}

func (s *copyStub) Copy(ctx context.Context, src, dst string) error {
	s.calls.Add(1)
	if ctx.Err() != nil {
		s.deadCtx.Add(1)
	}
	n := s.inFlight.Add(1)
	defer s.inFlight.Add(-1)
	for {
		m := s.maxIn.Load()
		if n <= m || s.maxIn.CompareAndSwap(m, n) {
			break
		}
	}
	s.mu.Lock()
	if s.perSrc == nil {
		s.perSrc = map[string]int{}
	}
	s.perSrc[src]++
	s.mu.Unlock()
	var err error
	if s.fn != nil {
		err = s.fn(ctx, src, dst)
	}
	if err == nil {
		s.mu.Lock()
		s.copied = append(s.copied, copyPair{src: src, dst: dst})
		s.mu.Unlock()
	}
	return err
}

func (s *copyStub) callsFor(src string) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.perSrc[src]
}

func (s *copyStub) copiedPairs() []copyPair {
	s.mu.Lock()
	defer s.mu.Unlock()
	return slices.Clone(s.copied)
}

// errAccessDenied 는 늘 403 인 저장소의 오류다(IAM · 버킷 정책 고장 — 일시 갈래).
var errAccessDenied = errors.New("api error AccessDenied: 403")

// copyJob 은 stub 으로 도는 복사 문맥이다 — 재시도 간격은 1ms 로 줄인다.
func copyJob(stub *copyStub, edit func(*Options)) *vodJob {
	opt := DefaultOptions("w-copy", fxBaseURL)
	opt.copyRetryDelay = time.Millisecond
	if edit != nil {
		edit(&opt)
	}
	return &vodJob{p: &Publisher{store: stub, opt: opt, now: time.Now},
		chain: index.Chain{StreamID: fxStream, Head: "S", Tail: "U", SessionIDs: []string{"S", "T", "U"}}}
}

// segPairs 는 조각 seq 0..n-1 의 짝이다.
func segPairs(n int) []copyPair {
	pairs := make([]copyPair, n)
	for i := range pairs {
		pairs[i] = copyPair{src: fmt.Sprintf("dvr/%s/seg/%06d.m4s", fxStream, i), dst: fmt.Sprintf("vod/%s/seg/%06d.m4s", fxStream, i)}
	}
	return pairs
}

// 짝 규칙(E61 · M64): 비GAP 행의 ③ 키만, 그리고 plan 에 런이 있는 회차마다 init 키(GAP 만 있는 런 포함)다. 사본 키는
// 앞머리만 vod/ 다. 변이 = GAP 행도 복사 → seq 3 · 6 짝이 끼어 FAIL.
func TestVODCopyPairsTakeNonGapRowsAndEveryRunInit(t *testing.T) {
	v := &vodJob{chain: index.Chain{StreamID: fxStream, Head: "S", Tail: "U", SessionIDs: []string{"S", "T", "U"}}}
	gap := func(r boundary.Row) boundary.Row { r.IsGap = true; return r }
	plan := vodPlan{rows: []boundary.Row{
		fxRow("S", 0), fxRow("S", 1), gap(fxRow("S", 2)), fxRow("T", 3), fxRow("T", 4), gap(fxRow("U", 5)),
	}}

	got, err := v.copyPairs(plan)
	if err != nil {
		t.Fatalf("copyPairs = %v, want nil", err)
	}
	want := []copyPair{
		{"dvr/str/seg/000000.m4s", "vod/str/seg/000000.m4s"},
		{"dvr/str/seg/000001.m4s", "vod/str/seg/000001.m4s"},
		{"dvr/str/seg/000003.m4s", "vod/str/seg/000003.m4s"},
		{"dvr/str/seg/000004.m4s", "vod/str/seg/000004.m4s"},
		{"dvr/str/init/S.mp4", "vod/str/init/S.mp4"},
		{"dvr/str/init/T.mp4", "vod/str/init/T.mp4"},
		{"dvr/str/init/U.mp4", "vod/str/init/U.mp4"},
	}
	if !slices.Equal(got, want) {
		t.Errorf("copyPairs =\n%v\nwant\n%v", got, want)
	}
}

// 빈 VOD 는 짝이 없다 — Copy 0 이다.
func TestVODCopyEmptyPlanCopiesNothing(t *testing.T) {
	stub := &copyStub{}
	v := copyJob(stub, nil)

	pairs, err := v.copyPairs(vodPlan{})
	if err != nil || len(pairs) != 0 {
		t.Fatalf("copyPairs(빈 plan) = (%v, %v), want 빈 짝", pairs, err)
	}
	if out, ok := v.copyAll(context.Background(), pairs, time.Minute); !ok {
		t.Errorf("copyAll(빈 짝) = %+v, want ok", out)
	}
	if n := stub.calls.Load(); n != 0 {
		t.Errorf("Copy 호출 %d 번, want 0", n)
	}
}

// 비GAP 행의 ③ 키가 비었거나 ③ 키 문법이 아니면 짝을 만들지 않는다(설계 4-3 「VODPath 오류 → render」 — 부르는 쪽이
// 영구 실패로 옮긴다).
func TestVODCopyPairsRejectBadKeys(t *testing.T) {
	v := &vodJob{chain: index.Chain{StreamID: fxStream, Head: "S", Tail: "S", SessionIDs: []string{"S"}}}
	for _, key := range []string{"", "vod/str/seg/000000.m4s", "dvr/str/seg/x.m4s"} {
		r := fxRow("S", 0)
		r.PlaybackS3Key = key
		if pairs, err := v.copyPairs(vodPlan{rows: []boundary.Row{r}}); err == nil {
			t.Errorf("copyPairs(③ 키 %q) = (%v, nil), want 오류", key, pairs)
		}
	}
}

// 예산 표(E73 · M77 · E78 · M79): max(VODCopyBudgetMin, n × VODCopyPerPair) 이고 until 이 있으면 그 시각까지로 줄인다.
// 변이 = 예산 고정 10분 → 36,000짝 행 FAIL · 클램프 제거 → until 행 FAIL.
func TestVODCopyBudget(t *testing.T) {
	opt := DefaultOptions("w", fxBaseURL)
	now := fxDay
	cases := []struct {
		name  string
		n     int
		until time.Time
		want  time.Duration
	}{
		{"짝_0", 0, time.Time{}, 10 * time.Minute},
		{"3시간_2700짝", 2700, time.Time{}, 10 * time.Minute},
		{"8시간_7200짝", 7200, time.Time{}, 10 * time.Minute},
		{"본문_상한_36000짝", 36000, time.Time{}, 15 * time.Minute},
		{"until_이_예산보다_멂", 36000, now.Add(time.Hour), 15 * time.Minute},
		{"until_1초_뒤", 36000, now.Add(time.Second), time.Second},
		{"until_지남", 2700, now.Add(-time.Second), 0},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := opt.copyBudget(tc.n, now, tc.until); got != tc.want {
				t.Errorf("copyBudget(%d, until %v) = %v, want %v", tc.n, tc.until, got, tc.want)
			}
		})
	}
}

// 전부 성공하면 ok 이고 짝마다 한 번 Copy(src → dst)다. 진척은 Copied = Total = 짝 수다.
func TestVODCopyAllCopiesEveryPair(t *testing.T) {
	stub := &copyStub{}
	v := copyJob(stub, nil)
	pairs := segPairs(100)

	out, ok := v.copyAll(context.Background(), pairs, time.Minute)
	if !ok || out.Copied != 100 || out.Total != 100 {
		t.Fatalf("copyAll = (%+v, %t), want ok · 100/100", out, ok)
	}
	got := stub.copiedPairs()
	slices.SortFunc(got, func(a, b copyPair) int { return strings.Compare(a.src, b.src) })
	if !slices.Equal(got, pairs) {
		t.Errorf("복사한 짝 %d 개가 짝 목록과 다르다", len(got))
	}
}

// 원본 없음(E53 · M69)은 곧바로 영구 실패다 — 그 키를 다시 보내지 않는다. 변이 = NoSuchKey 를 copy_pending 으로 →
// Result · Reason · 재시도 수 FAIL.
func TestVODCopyAllSourceMissingFailsAtOnce(t *testing.T) {
	missing := fmt.Sprintf("dvr/%s/seg/%06d.m4s", fxStream, 7)
	stub := &copyStub{fn: func(_ context.Context, src, _ string) error {
		if src == missing {
			return fmt.Errorf("CopyObject: %w", ErrNotFound)
		}
		return nil
	}}
	v := copyJob(stub, nil)

	out, ok := v.copyAll(context.Background(), segPairs(50), time.Minute)
	if ok || out.Result != ResultFailed || out.Retry || out.Reason != vodCopySourceMissing || !errors.Is(out.Err, ErrNotFound) {
		t.Errorf("copyAll(원본 없음) = (%+v, %t), want vod_copy_source_missing 실패", out, ok)
	}
	if n := stub.callsFor(missing); n != 1 {
		t.Errorf("없는 원본 Copy %d 번, want 1(재시도 없음)", n)
	}
	if out.Total != 50 {
		t.Errorf("Total = %d, want 50", out.Total)
	}
}

// 늘 403(E74 · M78): 첫 재시도 소진에 시도를 끊어 Copy 가 많아야 일꾼 × 3 = 24번이다. 변이 = 첫 오류 취소 제거 →
// 짝 1,000개 × 3 번을 보내 FAIL.
func TestVODCopyAllAlwaysForbiddenStopsWithinWorkerBound(t *testing.T) {
	stub := &copyStub{fn: func(context.Context, string, string) error { return errAccessDenied }}
	v := copyJob(stub, nil)

	out, ok := v.copyAll(context.Background(), segPairs(1000), time.Minute)
	if ok || !out.Retry || out.Reason != ReasonCopyPending || !errors.Is(out.Err, errAccessDenied) {
		t.Errorf("copyAll(늘 403) = (%+v, %t), want copy_pending 재시도", out, ok)
	}
	if n, limit := stub.calls.Load(), int64(v.p.opt.VODCopyWorkers*3); n > limit {
		t.Errorf("Copy %d 번, want ≤ %d(일꾼 × 3)", n, limit)
	}
}

// 첫 재시도 소진이 나머지 일꾼을 멈춘다(M78): 한 키만 늘 403 이고 나머지는 50ms 걸려 성공하는 저장소에서, 그 키가 재시도를
// 다 쓰면 다른 일꾼의 진행 중 Copy 가 취소되고 새 짝을 가져가지 않는다. 변이 = 취소 제거 → 나머지 일꾼이 짝 1,000개를 끝까지
// 보내 FAIL(늘 403 시험은 일꾼마다 제 키에서 멈춰 이 변이를 못 가른다).
func TestVODCopyAllFirstExhaustionStopsOtherWorkers(t *testing.T) {
	bad := fmt.Sprintf("dvr/%s/seg/%06d.m4s", fxStream, 0)
	stub := &copyStub{fn: func(ctx context.Context, src, _ string) error {
		if src == bad {
			return errAccessDenied
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(50 * time.Millisecond):
			return nil
		}
	}}
	v := copyJob(stub, nil)

	out, ok := runCopyAllWithin(t, v, context.Background(), segPairs(1000), time.Minute, 5*time.Second)
	if ok || out.Reason != ReasonCopyPending || !errors.Is(out.Err, errAccessDenied) {
		t.Errorf("copyAll = (%+v, %t), want copy_pending(403)", out, ok)
	}
	if n, limit := stub.calls.Load(), int64(v.p.opt.VODCopyWorkers*3); n > limit {
		t.Errorf("Copy %d 번, want ≤ %d(일꾼 × 3)", n, limit)
	}
}

// 일시 오류는 키마다 두 번 더 보낸다 — 두 번 실패한 키는 세 번째에 회복해 시도가 ok 다.
func TestVODCopyAllRetriesTransientTwice(t *testing.T) {
	flaky := fmt.Sprintf("dvr/%s/seg/%06d.m4s", fxStream, 3)
	var fails atomic.Int64
	stub := &copyStub{fn: func(_ context.Context, src, _ string) error {
		if src == flaky && fails.Add(1) <= 2 {
			return errors.New("api error InternalError: 500")
		}
		return nil
	}}
	v := copyJob(stub, nil)

	if out, ok := v.copyAll(context.Background(), segPairs(10), time.Minute); !ok || out.Copied != 10 {
		t.Errorf("copyAll(두 번 실패 뒤 회복) = (%+v, %t), want ok · 10", out, ok)
	}
	if n := stub.callsFor(flaky); n != 3 {
		t.Errorf("흔들린 키 Copy %d 번, want 3", n)
	}
}

// E51 엔진 몫: 한 키가 세 번 일시 실패하면 그 시도는 copy_pending 이고, 저장소가 회복한 뒤 다음 시도가 짝 전체를 처음부터
// 다시 Copy 해 전부 복사한다(멱등 — 설계 11절 「두 번째 시도가 짝 전체를 다시 Copy」). 정산 연결(frozen · ended)은 ①-5 몫이다.
func TestVODCopyAllRecoversOnNextAttempt(t *testing.T) {
	bad := fmt.Sprintf("dvr/%s/seg/%06d.m4s", fxStream, 0)
	var down atomic.Bool
	down.Store(true)
	stub := &copyStub{fn: func(_ context.Context, src, _ string) error {
		if down.Load() && src == bad {
			return errors.New("api error SlowDown: 503")
		}
		return nil
	}}
	v := copyJob(stub, nil)
	pairs := segPairs(20)

	out, ok := v.copyAll(context.Background(), pairs, time.Minute)
	if ok || out.Reason != ReasonCopyPending {
		t.Fatalf("첫 시도 = (%+v, %t), want copy_pending", out, ok)
	}
	if n := stub.callsFor(bad); n != 3 {
		t.Errorf("첫 시도의 실패 키 Copy %d 번, want 3", n)
	}
	down.Store(false)
	before := len(stub.copiedPairs())
	out, ok = v.copyAll(context.Background(), pairs, time.Minute)
	if !ok || out.Copied != 20 || out.Total != 20 {
		t.Fatalf("회복 뒤 시도 = (%+v, %t), want ok · 20/20", out, ok)
	}
	if got := len(stub.copiedPairs()) - before; got != 20 {
		t.Errorf("회복 뒤 시도가 Copy 한 짝 %d 개, want 20(짝 전체 다시)", got)
	}
}

// 동시 Copy 는 일꾼 수를 넘지 않는다.
func TestVODCopyAllHonoursWorkerLimit(t *testing.T) {
	for _, workers := range []int{1, 3, 8} {
		t.Run(fmt.Sprint(workers), func(t *testing.T) {
			stub := &copyStub{fn: func(context.Context, string, string) error {
				time.Sleep(2 * time.Millisecond)
				return nil
			}}
			v := copyJob(stub, func(o *Options) { o.VODCopyWorkers = workers })

			if out, ok := v.copyAll(context.Background(), segPairs(64), time.Minute); !ok {
				t.Fatalf("copyAll = %+v, want ok", out)
			}
			if m := stub.maxIn.Load(); m > int64(workers) {
				t.Errorf("동시 Copy 최대 %d, want ≤ %d", m, workers)
			}
		})
	}
}

// 취소 뒤 남은 Copy 0: 한 키가 재시도를 다 쓰면 나머지 일꾼이 그 자리에서 멈추고, 끝난 ctx 로 새 Copy 를 보내지 않으며,
// copyAll 이 돌아온 뒤에는 Copy 가 더 오지 않는다.
func TestVODCopyAllSendsNothingAfterCancel(t *testing.T) {
	bad := fmt.Sprintf("dvr/%s/seg/%06d.m4s", fxStream, 0)
	stub := &copyStub{fn: func(ctx context.Context, src, _ string) error {
		if src == bad {
			return errAccessDenied
		}
		<-ctx.Done() // 나머지 키는 취소될 때까지 걸려 있다
		return ctx.Err()
	}}
	v := copyJob(stub, nil)

	out, ok := runCopyAllWithin(t, v, context.Background(), segPairs(500), time.Minute, 2*time.Second)
	if ok || out.Reason != ReasonCopyPending || !errors.Is(out.Err, errAccessDenied) {
		t.Fatalf("copyAll = (%+v, %t), want copy_pending(403)", out, ok)
	}
	after := stub.calls.Load()
	time.Sleep(20 * time.Millisecond)
	if n := stub.calls.Load(); n != after {
		t.Errorf("copyAll 이 돌아온 뒤 Copy %d 번 더, want 0", n-after)
	}
	if n := stub.deadCtx.Load(); n != 0 {
		t.Errorf("끝난 ctx 로 보낸 Copy %d 번, want 0", n)
	}
	if after > int64(v.p.opt.VODCopyWorkers*3) {
		t.Errorf("Copy %d 번, want ≤ 일꾼 × 3", after)
	}
}

// 부르는 쪽 ctx 취소(종료)는 copy_pending 이고 곧바로 돌아온다.
func TestVODCopyAllParentCancelIsPending(t *testing.T) {
	stub := &copyStub{fn: func(ctx context.Context, _, _ string) error { <-ctx.Done(); return ctx.Err() }}
	v := copyJob(stub, nil)
	ctx, cancel := context.WithCancel(context.Background())
	time.AfterFunc(20*time.Millisecond, cancel)

	out, ok := runCopyAllWithin(t, v, ctx, segPairs(30), time.Minute, 2*time.Second)
	if ok || !out.Retry || out.Reason != ReasonCopyPending || !errors.Is(out.Err, context.Canceled) {
		t.Errorf("copyAll(부르는 쪽 취소) = (%+v, %t), want copy_pending", out, ok)
	}
}

// E78 · M79 · E52 엔진 몫: 기한 직전(1초 · 시험은 150ms 전)에 시작한 시도는 예산이 until 에서 끊겨 늘 걸려 있는 Copy 에도
// 그 시각에 돌아온다. 변이 = until 클램프 제거 → 예산 10분이라 시한 안에 돌아오지 않아 FAIL. ended 시각(4-3 식)의 연결
// 시험은 ①-5 몫이다.
func TestVODCopyAllStopsAtUntil(t *testing.T) {
	stub := &copyStub{fn: func(ctx context.Context, _, _ string) error { <-ctx.Done(); return ctx.Err() }}
	v := copyJob(stub, nil)
	pairs := segPairs(10)
	start := time.Now()
	budget := v.p.opt.copyBudget(len(pairs), start, start.Add(150*time.Millisecond))

	out, ok := runCopyAllWithin(t, v, context.Background(), pairs, budget, 2*time.Second)
	if ok || !out.Retry || out.Reason != ReasonCopyPending || !errors.Is(out.Err, context.DeadlineExceeded) {
		t.Errorf("copyAll(until 150ms) = (%+v, %t), want copy_pending(예산)", out, ok)
	}
	if took := time.Since(start); took > time.Second {
		t.Errorf("copyAll 이 %v 걸렸다, want until(150ms) 근처", took)
	}
}

// 예산이 0 이하(until 이 이미 지남)면 Copy 없이 copy_pending 이다.
func TestVODCopyAllZeroBudgetSendsNothing(t *testing.T) {
	stub := &copyStub{}
	v := copyJob(stub, nil)

	out, ok := v.copyAll(context.Background(), segPairs(5), 0)
	if ok || out.Reason != ReasonCopyPending || out.Total != 5 {
		t.Errorf("copyAll(예산 0) = (%+v, %t), want copy_pending", out, ok)
	}
	if n := stub.calls.Load(); n != 0 {
		t.Errorf("Copy %d 번, want 0", n)
	}
}

// 키 시한: 걸린 Copy 한 번은 VODCopyCallTimeout 에서 끊기고 일시 오류로 다시 보낸다 — 세 번 끊기면 시도가 copy_pending
// 으로 끝난다(예산 10초보다 훨씬 이르게).
func TestVODCopyAllCallTimeoutIsTransient(t *testing.T) {
	stub := &copyStub{fn: func(ctx context.Context, _, _ string) error { <-ctx.Done(); return ctx.Err() }}
	v := copyJob(stub, func(o *Options) { o.VODCopyCallTimeout = 20 * time.Millisecond })

	out, ok := runCopyAllWithin(t, v, context.Background(), segPairs(1), 10*time.Second, 2*time.Second)
	if ok || out.Reason != ReasonCopyPending || !errors.Is(out.Err, context.DeadlineExceeded) {
		t.Errorf("copyAll(늘 걸림 · 키 시한 20ms) = (%+v, %t), want copy_pending", out, ok)
	}
	if n := stub.calls.Load(); n != 3 {
		t.Errorf("Copy %d 번, want 3(한 번 + 두 번 더)", n)
	}
}

// 두 VOD 걸음이 같은 저장소로 동시에 복사해도 된다(-race).
func TestVODCopyAllConcurrentJobs(t *testing.T) {
	stub := &copyStub{}
	var wg sync.WaitGroup
	for range 2 {
		wg.Go(func() {
			if out, ok := copyJob(stub, nil).copyAll(context.Background(), segPairs(200), time.Minute); !ok {
				t.Errorf("copyAll = %+v, want ok", out)
			}
		})
	}
	wg.Wait()
	if n := stub.calls.Load(); n != 400 {
		t.Errorf("Copy %d 번, want 400", n)
	}
}

// runCopyAllWithin 은 copyAll 을 돌려 limit 안에 돌아오지 않으면 시험을 끝낸다(걸린 Copy 를 기다리지 않는다).
func runCopyAllWithin(t *testing.T, v *vodJob, ctx context.Context, pairs []copyPair, budget, limit time.Duration) (FreezeOutcome, bool) {
	t.Helper()
	type result struct {
		out FreezeOutcome
		ok  bool
	}
	done := make(chan result, 1)
	go func() {
		out, ok := v.copyAll(ctx, pairs, budget)
		done <- result{out, ok}
	}()
	select {
	case r := <-done:
		return r.out, r.ok
	case <-time.After(limit):
		t.Fatalf("copyAll 이 %v 안에 돌아오지 않았다", limit)
		return FreezeOutcome{}, false
	}
}

// 기동 검사(E104 · M113 · 계획 이월 P4): copyBudget(2 × vodMaxRows) + VODCopyCallTimeout < VODCopyGrace 가 아니면 New 가
// 거절한다. 기대값은 식으로 계산한 값이다 — 최대 짝 121,574 × 25ms = 50분 39.35초 + 10초 = 50분 49.35초 < 1시간(기본값
// 통과) · × 100ms = 3시간 22분 37.4초 + 10초 = 3시간 22분 47.4초(유예 1시간 · 2시간 모두 거절 · 그보다 1ns 길면 통과).
// 변이 = 기동 검사 제거 → PerPair 100ms 판이 New 를 지나 FAIL.
func TestNewChecksVODCopyGrace(t *testing.T) {
	if vodMaxRows != 60787 {
		t.Fatalf("vodMaxRows = %d, want 60787(이 표의 기대값 전제)", vodMaxRows)
	}
	pool := unreachablePool(t)
	store := &copyStub{}
	slowest := 3*time.Hour + 22*time.Minute + 47*time.Second + 400*time.Millisecond
	cases := []struct {
		name string
		edit func(*Options)
		ok   bool
	}{
		{"기본값", func(*Options) {}, true},
		{"기본값_유예_경계", func(o *Options) { o.VODCopyGrace = 50*time.Minute + 49350*time.Millisecond }, false},
		{"기본값_유예_경계_1ns_위", func(o *Options) { o.VODCopyGrace = 50*time.Minute + 49350*time.Millisecond + 1 }, true},
		{"PerPair_100ms_유예_1시간", func(o *Options) { o.VODCopyPerPair = 100 * time.Millisecond }, false},
		{"PerPair_100ms_유예_2시간", func(o *Options) {
			o.VODCopyPerPair, o.VODCopyGrace = 100*time.Millisecond, 2*time.Hour
		}, false},
		{"PerPair_100ms_유예_경계", func(o *Options) { o.VODCopyPerPair, o.VODCopyGrace = 100*time.Millisecond, slowest }, false},
		{"PerPair_100ms_유예_경계_1ns_위", func(o *Options) {
			o.VODCopyPerPair, o.VODCopyGrace = 100*time.Millisecond, slowest+1
		}, true},
		{"일꾼_0", func(o *Options) { o.VODCopyWorkers = 0 }, false},
		{"일꾼_음수", func(o *Options) { o.VODCopyWorkers = -1 }, false},
		{"키_시한_0", func(o *Options) { o.VODCopyCallTimeout = 0 }, false},
		{"예산_하한_0", func(o *Options) { o.VODCopyBudgetMin = 0 }, false},
		{"짝당_예산_0", func(o *Options) { o.VODCopyPerPair = 0 }, false},
		{"유예_0", func(o *Options) { o.VODCopyGrace = 0 }, false},
		{"유예_음수", func(o *Options) { o.VODCopyGrace = -time.Hour }, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			opt := DefaultOptions("w-1", fxBaseURL)
			tc.edit(&opt)
			if _, err := New(pool, store, opt); (err == nil) != tc.ok {
				t.Errorf("New(일꾼 %d · 키 시한 %v · 하한 %v · 짝당 %v · 유예 %v) = %v, want 통과 %t", opt.VODCopyWorkers,
					opt.VODCopyCallTimeout, opt.VODCopyBudgetMin, opt.VODCopyPerPair, opt.VODCopyGrace, err, tc.ok)
			}
		})
	}
}

// 기본값은 설계값이다(설계 3-2 Options 칸 · 계획 5-1 ①-4).
func TestDefaultOptionsVODCopyValues(t *testing.T) {
	o := DefaultOptions("w", fxBaseURL)
	if o.VODCopyWorkers != 8 || o.VODCopyCallTimeout != 10*time.Second || o.VODCopyBudgetMin != 10*time.Minute ||
		o.VODCopyPerPair != 25*time.Millisecond || o.VODCopyGrace != time.Hour {
		t.Errorf("기본 복사 설계값 = (%d · %v · %v · %v · %v), want (8 · 10s · 10m · 25ms · 1h)", o.VODCopyWorkers,
			o.VODCopyCallTimeout, o.VODCopyBudgetMin, o.VODCopyPerPair, o.VODCopyGrace)
	}
}
