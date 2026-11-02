package publish

// VOD 사본 복사 엔진 — POK-525 설계 r7 4-2 · 4-3.
//
// VOD 목록은 조각 · init 을 /dvr 이 아니라 사본 키(vod/…)로 가리킨다. 그래서 목록을 공개하기 전에 렌더 행이 가리킬 ③
// 객체를 사본 키로 서버 측 복사한다(Store.Copy). 이 파일은 복사만 한다 — 짝을 고르고(copyPairs), 시도 하나의 예산을 정하고
// (copyBudget), 일꾼으로 보낸다(copyAll). 복사가 끝난 뒤의 장부 확인 · 자기 정리 · FreezeVOD 배선은 부르는 쪽 몫이다.
//
// 다시 해도 같다 — 사본 키는 ③ 키에서 앞머리만 바꾼 것이고(playback.VODPath) 같은 키에 같은 바이트를 덮어쓴다. 「이미 있으면
// 건너뜀」 은 두지 않는다(S3 에 물어야 한다 — 프로필 4절 S3 LIST 금지). 다음 시도는 짝 전체를 처음부터 다시 보낸다.

import (
	"cmp"
	"context"
	"errors"
	"fmt"
	"slices"
	"sync"
	"sync/atomic"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// vodCopySourceMissing 은 복사 원본이 없다는 영구 실패 사유다(CopyObject 404 NoSuchKey) — 장부는 uploaded 인데 객체가 없어
// 다시 해도 같다(설계 r7 4-3 E53).
const vodCopySourceMissing = "vod_copy_source_missing"

// 키마다 일시 오류를 다시 보내는 횟수와 첫 간격이다 — 200ms · 400ms 로 두 번 더(설계 r7 3-2 copyAll).
const (
	vodCopyRetries    = 2
	vodCopyRetryDelay = 200 * time.Millisecond
)

// vodCopyMaxPairs 는 VOD 하나의 짝 수 상한이다 — 조각 짝은 적재 상한 vodMaxRows 를 넘지 않고, init 짝은 런마다 하나라
// 조각 수를 넘지 않으므로 보수로 두 배다(계획 r6 5-1 ①-4). 기동 검사가 이 짝 수의 예산을 잰다.
const vodCopyMaxPairs = 2 * vodMaxRows

// copyPair 는 복사 하나다 — src 는 ③ 조각 · init 키(dvr/…), dst 는 그 사본 키(playback.VODPath(src))다.
type copyPair struct{ src, dst string }

// copyPairs 는 plan 의 비GAP 행 ③ 키와, plan 에 런이 있는 회차마다 그 init 키를 사본 키와 짝짓는다(순서 = seq · 회차가 처음
// 나온 순). GAP 행은 목록에 #EXT-X-GAP 으로 실려 재생기가 받지 않으므로 복사하지 않는다(E61). GAP 만 있는 런의 회차도 init 은
// 복사한다 — 그 init 도 목록의 MAP 이 가리키고, Validate 가 런마다 업로드를 요구했다(validate.go 의 mapProblem).
//
// ③ 키가 비었거나 ③ 키 문법이 아니면 오류다 — 같은 입력이면 다시 해도 같다(설계 r7 4-3 render).
func (v *vodJob) copyPairs(plan vodPlan) ([]copyPair, error) {
	var pairs []copyPair
	var runs []string
	for _, r := range plan.rows {
		if !slices.Contains(runs, r.SessionID) {
			runs = append(runs, r.SessionID)
		}
		if r.IsGap {
			continue
		}
		p, err := newCopyPair(r.PlaybackS3Key)
		if err != nil {
			return nil, fmt.Errorf("publish: seq %d 의 ③ 키로 사본 짝을 만들 수 없다: %w", r.Seq, err)
		}
		pairs = append(pairs, p)
	}
	for _, session := range runs {
		key, err := playback.InitKey(v.chain.StreamID, session)
		if err != nil {
			return nil, fmt.Errorf("publish: 회차 %q 의 init 키: %w", session, err)
		}
		p, err := newCopyPair(key)
		if err != nil {
			return nil, err
		}
		pairs = append(pairs, p)
	}
	return pairs, nil
}

// newCopyPair 는 ③ 키 src 와 그 사본 키의 짝이다.
func newCopyPair(src string) (copyPair, error) {
	dst, err := playback.VODPath(src)
	if err != nil {
		return copyPair{}, err
	}
	return copyPair{src: src, dst: dst}, nil
}

// copyBudget 은 짝 n 개의 복사 예산이다 — max(VODCopyBudgetMin, n × VODCopyPerPair)(E73). until 이 영값이 아니면 now 에서
// until 까지로 줄이고(기한 직전에 시작한 시도가 기한을 넘겨 돌지 않게 — E78), 이미 지났으면 0 이다.
func (o Options) copyBudget(n int, now, until time.Time) time.Duration {
	budget := max(o.VODCopyBudgetMin, time.Duration(n)*o.VODCopyPerPair)
	if until.IsZero() {
		return budget
	}
	return max(0, min(budget, until.Sub(now)))
}

// checkVODCopy 는 VOD 사본 복사 설계값을 본다 — 다섯 모두 양수여야 하고, 가장 큰 VOD 의 시도 하나가 걸릴 수 있는 시간
// (copyBudget(vodCopyMaxPairs) + VODCopyCallTimeout — 예산이 끊은 뒤 걸려 있던 Copy 하나가 키 시한까지 갈 수 있다)이
// VODCopyGrace 보다 짧아야 한다(기동 검사 · E104). ISB 실측으로 짝당 예산을 올려 이 부등식이 깨지면 New 가 거절해 배포 전에
// 드러난다 — 그때는 유예를 함께 올린다.
func (o Options) checkVODCopy() error {
	if o.VODCopyWorkers <= 0 || o.VODCopyCallTimeout <= 0 || o.VODCopyBudgetMin <= 0 || o.VODCopyPerPair <= 0 || o.VODCopyGrace <= 0 {
		return fmt.Errorf("publish: VOD 사본 복사 설계값이 0 이하다(일꾼 %d · 키 시한 %v · 예산 하한 %v · 짝당 %v · 유예 %v)",
			o.VODCopyWorkers, o.VODCopyCallTimeout, o.VODCopyBudgetMin, o.VODCopyPerPair, o.VODCopyGrace)
	}
	if longest := o.copyBudget(vodCopyMaxPairs, time.Time{}, time.Time{}) + o.VODCopyCallTimeout; longest >= o.VODCopyGrace {
		return fmt.Errorf("publish: VOD 사본 복사의 최장 시간 %v(짝 %d 개의 예산 + 키 시한)가 유예 %v 보다 짧아야 한다",
			longest, vodCopyMaxPairs, o.VODCopyGrace)
	}
	return nil
}

// copyAll 은 짝을 일꾼 VODCopyWorkers 로 복사한다 — 시도 전체는 budget 안이고 Copy 한 번은 VODCopyCallTimeout 안이다. 키마다
// 일시 오류는 두 번까지 다시 한다(200ms · 400ms). 한 키라도 재시도를 다 쓰면 그 자리에서 시도를 끊는다(나머지 일꾼 ctx 취소
// — 남은 키를 보내지 않는다 · E74). ErrNotFound 는 곧바로 실패(vod_copy_source_missing), 그 밖의 남은 오류 · 예산 소진 ·
// ctx 취소는 copy_pending 재시도다. 전부 성공하면 ok 다. 결과의 Copied · Total 은 복사를 마친 짝 수 · 전체 짝 수다.
//
// 돌아올 때 이 시도가 보낸 Copy 는 모두 끝나 있다 — 일꾼을 다 기다린다.
func (v *vodJob) copyAll(ctx context.Context, pairs []copyPair, budget time.Duration) (FreezeOutcome, bool) {
	total := len(pairs)
	if total == 0 {
		return FreezeOutcome{Axis: AxisVOD}, true
	}
	if budget <= 0 {
		out := vodRetry(vodCopyPending, fmt.Errorf("publish: 사본 복사 예산이 없다(%v)", budget))
		out.Total = total
		return out, false
	}
	actx, cancel := context.WithTimeout(ctx, budget)
	defer cancel()
	run := &copyRun{v: v, pairs: pairs, cancel: cancel}
	var wg sync.WaitGroup
	for range min(v.p.opt.VODCopyWorkers, total) {
		wg.Go(func() { run.work(actx) })
	}
	wg.Wait()
	return run.outcome(actx)
}

// copyRun 은 copyAll 시도 하나의 공유 상태다 — 일꾼은 next 로 짝을 하나씩 가져가고, 처음 끊은 까닭을 stop 에 적는다.
type copyRun struct {
	v      *vodJob
	pairs  []copyPair
	cancel context.CancelFunc
	next   atomic.Int64
	copied atomic.Int64

	mu sync.Mutex
	// stop 은 시도를 끊은 까닭이다 — Result 가 빈 값이면 아직 없다. 영구 실패(원본 없음)는 일시 끊김을 덮는다(다음 시도가
	// 다시 해도 같은 실패라 먼저 알린다).
	stop FreezeOutcome
}

// work 는 일꾼 하나다 — ctx 가 살아 있는 동안 짝을 가져가 복사하고, 실패하면 시도를 끊고 멈춘다.
func (r *copyRun) work(ctx context.Context) {
	for ctx.Err() == nil {
		i := int(r.next.Add(1) - 1)
		if i >= len(r.pairs) {
			return
		}
		if err := r.v.copyOne(ctx, r.pairs[i]); err != nil {
			r.halt(ctx, err)
			return
		}
		r.copied.Add(1)
	}
}

// halt 는 짝 하나의 실패 err 로 시도를 끊는다. ctx 가 이미 끝나 생긴 실패(예산 · 부르는 쪽 취소 · 다른 일꾼의 끊김)는 새
// 까닭이 아니다 — 결과는 outcome 이 가른다.
func (r *copyRun) halt(ctx context.Context, err error) {
	var out FreezeOutcome
	switch {
	case errors.Is(err, ErrNotFound):
		out = vodFailed(vodCopySourceMissing, err)
	case ctx.Err() != nil:
		return
	default:
		out = vodRetry(vodCopyPending, err)
	}
	r.mu.Lock()
	if r.stop.Result == "" || (r.stop.Retry && !out.Retry) {
		r.stop = out
	}
	r.mu.Unlock()
	r.cancel()
}

// outcome 은 일꾼이 모두 돌아온 뒤 시도의 결과다 — 끊은 까닭이 있으면 그것, 짝을 다 복사했으면 ok, 아니면 ctx(예산 · 부르는
// 쪽 취소)가 끊은 copy_pending 이다.
func (r *copyRun) outcome(ctx context.Context) (FreezeOutcome, bool) {
	copied, total := int(r.copied.Load()), len(r.pairs)
	r.mu.Lock()
	out := r.stop
	r.mu.Unlock()
	switch {
	case out.Result != "":
	case copied == total:
		return FreezeOutcome{Axis: AxisVOD, Copied: copied, Total: total}, true
	default:
		out = vodRetry(vodCopyPending, fmt.Errorf("publish: 사본 복사가 짝 %d/%d 에서 끊겼다: %w", copied, total, ctx.Err()))
	}
	out.Copied, out.Total = copied, total
	return out, false
}

// copyOne 은 짝 하나를 복사한다 — Copy 한 번은 VODCopyCallTimeout 안이고, 일시 오류는 vodCopyRetries 번 더 보낸다(간격은
// 두 배씩). ErrNotFound 는 다시 보내지 않는다. ctx 가 끝나면 더 보내지 않고 마지막 오류(아직 없으면 ctx 오류)를 돌려준다.
func (v *vodJob) copyOne(ctx context.Context, p copyPair) error {
	delay := cmp.Or(v.p.opt.copyRetryDelay, vodCopyRetryDelay)
	for attempt := 0; ; attempt++ {
		if err := ctx.Err(); err != nil {
			return err
		}
		cctx, cancel := context.WithTimeout(ctx, v.p.opt.VODCopyCallTimeout)
		err := v.p.store.Copy(cctx, p.src, p.dst)
		cancel()
		if err == nil || errors.Is(err, ErrNotFound) || attempt == vodCopyRetries {
			return err
		}
		if !sleepCtx(ctx, delay<<attempt) {
			return err
		}
	}
}

// sleepCtx 는 d 만큼 기다린다 — 그 사이 ctx 가 끝나면 곧바로 거짓이다.
func sleepCtx(ctx context.Context, d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-t.C:
		return true
	}
}
