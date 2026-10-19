package janitor

// D33 — 속성 시험. 장부 · FS 결과를 무작위로 섞은 1만 단계 동안 J-debt 를 모델 세계에서 직접 센다:
//   - janitor 원인 미갱신(janitor 의 remove 가 nil 또는 stall 로 끝난 이력이 있는 행 가운데 장부에 그
//     경로가 그대로 남은 행 — 파일 실재와 무관, 계획 7절 c1 정의) ≤ 1 — 매 단계 · 매 remove 직전 ·
//     잠금 아래 act 직후(= COMMIT 직전)
//   - |부채| + 진행 중(잠금 아래 새 행 remove) ≤ 1 — 새 행 remove 가 불릴 때 부채는 0 이어야 한다
// 외부 부재(젊은 ENOENT — 파일을 처음부터 안 만든 행)는 따로 세고 단언하지 않는다(D30 이 다룬다).
// 씨앗은 고정이고 실패 문구에 싣는다.

import (
	"context"
	"errors"
	"fmt"
	"math/rand/v2"
	"syscall"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

const (
	propertySeed  = 20261019
	propertySteps = 10000
)

// world 는 속성 시험의 모델 세계다 — 가짜 장부 · FS 위에 「janitor 가 건드린 행」과 「늦게 지울
// 버려진 워커」를 더한다.
type world struct {
	r       *rig
	rnd     *rand.Rand
	touched map[rowKey]bool   // janitor remove 가 nil 이었거나 stall 이었던 행(이력)
	pending map[string]rowKey // 늦은 unlink 를 기다리는 버려진 워커(rel → 행)
	nextSeq int64
	step    int
	t       *testing.T
}

// janitorUnrefreshed 는 J-debt 셈이다 — 「remove 이력 ∩ 장부 동일 경로 잔존」. stall 로 끝나 파일이 아직
// 남은 행도 센다(늦은 unlink 가 언제든 올 수 있다 — 파일 실재로 거르면 그 사이의 위반을 놓친다).
func (w *world) janitorUnrefreshed() int {
	n := 0
	for k := range w.touched {
		p, ok := w.r.ledger.pathOf(k)
		if !ok || p != testPath(k.streamID, k.seq) {
			delete(w.touched, k) // NULL · 행 부재 · 다른 경로는 끝 상태다 — 다시 셀 일이 없다
			continue
		}
		n++
	}
	return n
}

func (w *world) check(t *testing.T, where string) {
	t.Helper()
	if n := w.janitorUnrefreshed(); n > 1 {
		t.Fatalf("씨앗 %d 단계 %d(%s): janitor 원인 미갱신 = %d, want ≤ 1", propertySeed, w.step, where, n)
	}
}

// addRows 는 후보 행을 채운다 — 대부분 파일이 있고, 일부는 젊은 · 나이 지난 부재다.
func (w *world) addRows(n int) {
	for range n {
		w.nextSeq++
		due := w.rnd.IntN(10) == 0
		file := w.rnd.IntN(10) != 0
		w.r.row("s1", w.nextSeq, due, file)
	}
}

// fsResult 는 remove 한 번의 무작위 결과다. stall 이면 버려진 워커가 나중에 지울 수 있다.
func (w *world) fsResult(t *testing.T, rel string) (error, bool) {
	w.check(t, "remove 직전")
	k, inDelete := w.keyOf(rel)
	if w.r.ledger.acting && len(w.r.j.debt) != 0 {
		t.Fatalf("씨앗 %d 단계 %d: 부채 %d 인데 새 행 remove(%s) — |부채|+진행 중 > 1", propertySeed, w.step, len(w.r.j.debt), rel)
	}
	if !inDelete {
		return nil, false
	}
	switch p := w.rnd.IntN(100); {
	case p < 8:
		w.touched[k] = true
		w.pending[rel] = k
		return errStall, true
	case p < 12:
		return syscall.EIO, true
	case p < 14:
		return syscall.EACCES, true
	}
	if w.r.fs.exists(rel) {
		w.touched[k] = true
	}
	return nil, false // 가짜 FS 가 지운다(없으면 ENOENT)
}

func (w *world) keyOf(rel string) (rowKey, bool) {
	var seq int64
	if _, err := fmt.Sscanf(rel, "s1/2026-10-19_00-00-00-%06d.mp4", &seq); err != nil {
		return rowKey{}, false
	}
	return rowKey{"s1", seq}, true
}

// lockResult 는 선잠금의 무작위 결과다 — 대부분 정상(handled=false), 일부 Busy · Gone(clip DELETE) ·
// 값 바뀜 · 선잠금 오류.
func (w *world) lockResult(k rowKey) (index.RowLockResult, error, bool) {
	switch p := w.rnd.IntN(100); {
	case p < 6:
		return index.RowLockResult{Outcome: index.RowLockBusy}, nil, true
	case p < 7:
		w.r.ledger.drop(k)
		return index.RowLockResult{Outcome: index.RowLockGone}, nil, true
	case p < 8:
		other := "/recordings/s1/other.mp4" // 조각 이름이 아니다 — 다음 방문은 bad_name 거부(Q2)
		w.r.ledger.setPath(k, &other)
		return index.RowLockResult{Outcome: index.RowLockPathChanged}, nil, true
	case p < 9:
		return index.RowLockResult{Stage: index.StageLock}, pgErr("57014"), true
	}
	return index.RowLockResult{}, nil, false
}

// commitResult 는 잠금 아래 act 가 끝난 직후 · COMMIT 직전에 불린다 — 거기서도 J-debt 를 단언한다.
func (w *world) commitResult(_ context.Context, _ rowKey, _ bool) error {
	w.check(w.t, "act 직후 · COMMIT 직전")
	if w.rnd.IntN(100) < 6 {
		return errors.New("COMMIT 미확인")
	}
	return nil
}

func (w *world) clearResult(context.Context, rowKey) (bool, error, bool) {
	if w.rnd.IntN(100) < 30 {
		return false, pgErr("55P03"), true
	}
	return false, nil, false
}

// lateUnlinks 는 버려진 워커 일부가 뒤늦게 지우게 한다.
func (w *world) lateUnlinks() {
	for rel := range w.pending {
		if w.rnd.IntN(4) == 0 {
			w.r.fs.unlink(rel)
			delete(w.pending, rel)
		}
	}
}

func TestJanitorNeverLeavesMoreThanOneUnrefreshedRow(t *testing.T) {
	r := newRig(t, func(o *Options) { o.Batch, o.MaxPerCycle = 5, 20 })
	w := &world{r: r, rnd: rand.New(rand.NewPCG(propertySeed, propertySeed)), touched: map[rowKey]bool{}, pending: map[string]rowKey{}, t: t}
	r.fs.hook = func(rel string) (error, bool) { return w.fsResult(t, rel) }
	r.ledger.lockHook = w.lockResult
	r.ledger.commitHook = w.commitResult
	r.ledger.clearHook = w.clearResult
	w.addRows(40)

	var stats struct{ cycles, debtCycles, maxDebt int }
	for w.step = 1; w.step <= propertySteps; w.step++ {
		r.ledger.compact()
		if r.ledger.nonNullCount() < 20 {
			w.addRows(10)
		}
		w.lateUnlinks()
		r.clock.advance(time.Duration(w.rnd.IntN(120)) * time.Second)
		r.cycle(t)
		w.check(t, "주기 뒤")
		if n := len(r.j.debt); n > 0 {
			stats.debtCycles++
			stats.maxDebt = max(stats.maxDebt, n)
		}
		if len(r.j.debt)+boolInt(r.ledger.acting) > 1 {
			t.Fatalf("씨앗 %d 단계 %d: |부채| + 진행 중 > 1", propertySeed, w.step)
		}
		stats.cycles++
	}
	if stats.debtCycles == 0 || len(r.log.all("segment_local_deleted")) == 0 {
		t.Fatalf("시험이 부채 · 삭제 갈래를 거치지 않았다(부채 주기 %d) — 분포를 고쳐라", stats.debtCycles)
	}
	t.Logf("씨앗 %d · 주기 %d · 부채 있는 주기 %d · 최대 부채 %d · 삭제 %d줄", propertySeed, stats.cycles, stats.debtCycles, stats.maxDebt, len(r.log.all("segment_local_deleted")))
}

func boolInt(b bool) int {
	if b {
		return 1
	}
	return 0
}
