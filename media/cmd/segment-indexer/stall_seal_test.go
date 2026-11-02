package main

// L2′(정체 마감 → 봉인 → ending · 사유 stall_seal)의 루프 몫(POK-196 M6-3b 커밋 ④b — M4 r17 4.6.3 · kty 2026-10-27 결정
// (나) · K5 스위치 기본 끔). 사다리가 Seal 을 내면 루프가 그 스트림 차선에 봉인(Endlist)을 내고, 봉인이 서면 다음 holdTick
// 의 L3 걸음 뒤에서 EndLive 처럼 동기로 그 회차를 끝낸다. 끝낸 뒤는 오프라인 전이 · L3 와 같다.

import (
	"context"
	"errors"
	"log/slog"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
)

// sealCalls 는 봉인 작업의 가짜다 — 차선 고루틴에서 돈다(락). 부른 (스트림, 회차)를 적고 outs 를 앞에서부터 하나씩
// 돌려준다(다 쓰면 frozen).
type sealCalls struct {
	mu    sync.Mutex
	calls []string
	outs  []publish.FreezeOutcome
}

func (c *sealCalls) seal(_ context.Context, stream, session string) publish.FreezeOutcome {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.calls = append(c.calls, stream+"/"+session)
	if len(c.outs) > 0 {
		out := c.outs[0]
		c.outs = c.outs[1:]
		return out
	}
	return publish.FreezeOutcome{Axis: publish.AxisDVR, Result: publish.ResultFrozen}
}

func (c *sealCalls) got() []string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return slices.Clone(c.calls)
}

// newSealLoop 은 L2′ 를 켠 발행 루프 픽스처다 — 발행자의 봉인 문턱을 D_act 로 두고(조립의 publishOptions 와 같다) 봉인 ·
// 종료 손잡이를 가짜로 끼운다.
func newSealLoop(t *testing.T) (*publishLoop, *sealCalls, *gapRunCalls) {
	t.Helper()
	f := newPublishLoop(t)
	pool, err := pgxpool.New(t.Context(), "postgres://user@127.0.0.1:1/none")
	if err != nil {
		t.Fatalf("풀 생성 실패(접속은 하지 않는다): %v", err)
	}
	t.Cleanup(pool.Close)
	opt := publish.DefaultOptions("w-loop", "https://media.example.com")
	opt.Log = slog.New(f.logs)
	opt.StallSealAfter = opt.DAct()
	if f.pub, err = publish.New(pool, nopPublishStore{}, opt); err != nil {
		t.Fatalf("발행자 생성 실패: %v", err)
	}
	sc, ends := &sealCalls{}, &gapRunCalls{}
	f.rewind.pub = f.pub
	f.rewind.work.seal = sc.seal
	f.rewind.endStallSeal = ends.end
	return f, sc, ends
}

// stallAfterPublish 는 스트림 s1 의 회차 S 를 loopBase 에 한 번 발행하고 유입을 멈춘다 — 머리 다음 행이 없어 L2 가 설 수
// 없다(no_row). stallAge 의 기점은 loopBase 다.
func stallAfterPublish(f *publishLoop) {
	f.put("s1", liveLedger("s1", "S", 3, 3))
	f.publishOnce("s1", "S", 3, loopBase)
}

// at 은 loopBase 뒤 d 에 holdTick 하나를 돌리고 차선을 비운다.
func (f *publishLoop) at(d time.Duration) {
	f.t.Helper()
	f.hold(loopBase.Add(d))
	f.settle(loopBase.Add(d))
}

// L2′ — stallAge 가 D_act(5.5초)에 닿으면 그 스트림 차선에 봉인을 한 번 내고, 봉인이 서면 다음 holdTick 에 그 회차를 끝낸다.
// 끝낸 뒤는 다시 봉인하지도 끝내지도 않는다(소유 회차가 ending — 사다리 꺼짐). 변이 = 봉인이 선 결과를 종료 대기로 넣지 않음 ·
// holdTick 의 L2′ 걸음 제거 · 사다리 Seal 을 봉인으로 내지 않음 → 각각 FAIL.
func TestStallSealSealsThenEndsSessionL2Prime(t *testing.T) {
	f, sc, ends := newSealLoop(t)
	stallAfterPublish(f)

	f.at(5499 * time.Millisecond)
	if got := sc.got(); len(got) != 0 {
		t.Fatalf("D_act 전 봉인 = %v, want 없음", got)
	}
	f.at(5500 * time.Millisecond)
	if got := sc.got(); !slices.Equal(got, []string{"s1/S"}) {
		t.Fatalf("봉인 = %v, want [s1/S]", got)
	}
	for i := range 5 {
		f.at(6500*time.Millisecond + time.Duration(i)*time.Second)
	}

	if !slices.Equal(ends.calls, []string{"s1/S"}) {
		t.Fatalf("L2′ 종료 = %v, want [s1/S] 한 번 — 봉인했는데 ending 을 보내지 않았다", ends.calls)
	}
	if got := sc.got(); len(got) != 1 {
		t.Errorf("봉인 = %v, want 한 번", got)
	}
	if rec := f.logs.last("session_stall_seal_end"); rec == nil || rec.level != slog.LevelInfo ||
		rec.attrs["session_id"] != "S" || rec.attrs["result"] != "ended" {
		t.Errorf("session_stall_seal_end = %+v, want INFO session_id=S result=ended", rec)
	}
	if owner, ok := f.cache.Owner("s1"); !ok || owner.State != "ending" {
		t.Errorf("캐시 소유 회차 = %+v (%v), want ending", owner.RewindSession, ok)
	}
	if got := f.ups.forgets(); !slices.Contains(got, "S") {
		t.Errorf("업로더 회차 메모리 치움 = %v, want S 포함", got)
	}
}

// 봉인이 서지 않으면 끝내지 않는다. retry(fence · Head · PUT 결과 모름 등)면 다음 holdTick 의 사다리가 곧바로 다시 낸다 —
// 갱신 의무 안에 닫는 것이 목적이다. failed(같은 입력으로 다시 해도 서지 않음)면 30초 동안 다시 내지 않는다. 변이 = 서지
// 않은 봉인도 종료 대기로 넣음 · failed 쉼 제거 → 각각 FAIL.
func TestStallSealUnstoodSealDoesNotEndL2Prime(t *testing.T) {
	t.Run("retry_는_다음_틱", func(t *testing.T) {
		f, sc, ends := newSealLoop(t)
		sc.outs = []publish.FreezeOutcome{{Axis: publish.AxisDVR, Result: publish.ResultRetry, Retry: true, Reason: "fence"}}
		stallAfterPublish(f)
		f.at(5500 * time.Millisecond)
		f.at(6500 * time.Millisecond)
		if len(ends.calls) != 0 {
			t.Fatalf("서지 않은 봉인 뒤 종료 = %v, want 없음", ends.calls)
		}
		if got := sc.got(); len(got) != 2 {
			t.Fatalf("봉인 = %v, want 두 번(retry 뒤 다음 틱)", got)
		}
		if rec := f.logs.last("stall_seal"); rec == nil || rec.level != slog.LevelInfo || rec.attrs["result"] != "frozen" {
			t.Errorf("stall_seal 마지막 줄 = %+v, want INFO result=frozen", rec)
		}
		f.at(7500 * time.Millisecond)
		if !slices.Equal(ends.calls, []string{"s1/S"}) {
			t.Fatalf("다시 선 봉인 뒤 종료 = %v, want [s1/S]", ends.calls)
		}
	})
	t.Run("failed_는_30초_쉼", func(t *testing.T) {
		f, sc, ends := newSealLoop(t)
		sc.outs = []publish.FreezeOutcome{{Axis: publish.AxisDVR, Result: publish.ResultFailed, Reason: "seal"}}
		stallAfterPublish(f)
		f.at(5500 * time.Millisecond)
		if rec := f.logs.last("stall_seal"); rec == nil || rec.level != slog.LevelWarn || rec.attrs["result"] != "failed" {
			t.Fatalf("stall_seal = %+v, want WARN result=failed", rec)
		}
		for d := 6500 * time.Millisecond; d < 35500*time.Millisecond; d += 5 * time.Second {
			f.at(d)
		}
		if got := sc.got(); len(got) != 1 || len(ends.calls) != 0 {
			t.Fatalf("30초 안 봉인 %v · 종료 %v, want 한 번 · 없음", got, ends.calls)
		}
		f.at(35500 * time.Millisecond)
		if got := sc.got(); len(got) != 2 {
			t.Fatalf("30초 뒤 봉인 = %v, want 두 번째", got)
		}
	})
}

// 종료 문장이 실패하면(결과 모름) ERROR 한 줄 뒤 30초 동안 다시 부르지 않는다(L3 와 같다). 그동안 봉인도 다시 내지 않는다 —
// 이미 닫힌 목록이다. 변이 = 재시도 간격 제거 → FAIL.
func TestStallSealEndFailureRetriesAfterBackoffL2Prime(t *testing.T) {
	f, sc, ends := newSealLoop(t)
	ends.errs = []error{errors.New("lock_timeout")}
	stallAfterPublish(f)
	f.at(5500 * time.Millisecond)
	for d := 6500 * time.Millisecond; d < 36500*time.Millisecond; d += 5 * time.Second {
		f.at(d)
	}
	if len(ends.calls) != 1 {
		t.Fatalf("30초 안 종료 시도 = %v, want 한 번", ends.calls)
	}
	if rec := f.logs.last("stall_seal_end_failed"); rec == nil || rec.level != slog.LevelError {
		t.Fatalf("stall_seal_end_failed = %+v, want ERROR", rec)
	}
	if got := sc.got(); len(got) != 1 {
		t.Fatalf("종료 대기 중 봉인 = %v, want 한 번(다시 내지 않음)", got)
	}
	f.at(36500 * time.Millisecond)
	if len(ends.calls) != 2 {
		t.Fatalf("30초 뒤 종료 시도 = %v, want 두 번째", ends.calls)
	}
}

// 스위치가 꺼져 있으면(REWIND_STALL_SEAL 기본 false) 봉인도 종료도 없다 — 사다리 문턱이 0 이거나, 문턱이 있어도 종료
// 손잡이가 없으면 봉인하지 않는다(봉인만 되고 끝내지 못하는 live 회차를 만들지 않는다). 변이 = 손잡이 없음 막이 제거 →
// FAIL.
func TestStallSealOffDoesNothingL2Prime(t *testing.T) {
	t.Run("문턱_0", func(t *testing.T) {
		f := newPublishLoop(t)
		sc, ends := &sealCalls{}, &gapRunCalls{}
		f.rewind.work.seal, f.rewind.endStallSeal = sc.seal, ends.end
		stallAfterPublish(f)
		for d := 5500 * time.Millisecond; d < time.Minute; d += 5 * time.Second {
			f.at(d)
		}
		if got := sc.got(); len(got) != 0 || len(ends.calls) != 0 {
			t.Fatalf("꺼짐 봉인 %v · 종료 %v, want 없음", got, ends.calls)
		}
	})
	t.Run("손잡이_없음", func(t *testing.T) {
		f, sc, _ := newSealLoop(t)
		f.rewind.endStallSeal = nil
		stallAfterPublish(f)
		for d := 5500 * time.Millisecond; d < time.Minute; d += 5 * time.Second {
			f.at(d)
		}
		if got := sc.got(); len(got) != 0 {
			t.Fatalf("종료 손잡이 없이 봉인 = %v, want 없음", got)
		}
	})
}

// 조립 — 스위치가 꺼지면 발행자 봉인 문턱 0 · 종료 손잡이 nil 이고, 켜지면 문턱 D_act(5.5초) · 손잡이가 있다. 변이 = 스위치를
// 보지 않고 늘 켬 → FAIL.
func TestStallSealWiredOnlyWhenSwitchOn(t *testing.T) {
	fa := finalizeAssembly{enqueue: noEndedEnqueue}
	if fa.stallSealEnd(false, nil) != nil {
		t.Error("스위치가 꺼졌는데 L2′ 종료 손잡이를 끼웠다")
	}
	if fa.stallSealEnd(true, nil) == nil {
		t.Error("스위치가 켜졌는데 L2′ 종료 손잡이가 없다")
	}
	log := slog.New(slog.DiscardHandler)
	if got := publishOptions(config.Config{}, log).StallSealAfter; got != 0 {
		t.Errorf("꺼짐 봉인 문턱 = %v, want 0", got)
	}
	if got := publishOptions(config.Config{RewindStallSeal: true}, log).StallSealAfter; got != 5500*time.Millisecond {
		t.Errorf("켜짐 봉인 문턱 = %v, want 5.5s(D_act)", got)
	}
}
