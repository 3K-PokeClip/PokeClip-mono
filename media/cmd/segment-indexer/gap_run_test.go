package main

// L3(연속 GAP 셋 → ending · 사유 gap_run)의 루프 몫(POK-196 M6-3b 커밋 ④ — 설계 M6 3.1 · 6절 L3 행 · kty K5 스위치 기본
// 끔). 셈은 GAP 틱 결과를 받는 settleGap 이 하고, 발동은 다음 holdTick 의 4.1 전이 바로 뒤 걸음(endGapRuns)이 EndLive 처럼
// 동기로 한다. 끝낸 뒤는 오프라인 전이와 같다 — 캐시 ending 반영 · 업로더 회차 메모리 치움 · 빠른 길 · 상태 표지.

import (
	"context"
	"errors"
	"log/slog"
	"slices"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/finalize"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
)

// gapRunCalls 는 L3 종료 문장의 가짜다 — 부른 (스트림, 회차)를 적고 errs 를 앞에서부터 하나씩 돌려준다.
type gapRunCalls struct {
	calls []string
	errs  []error
}

func (g *gapRunCalls) end(_ context.Context, stream, session string) (string, error) {
	g.calls = append(g.calls, stream+"/"+session)
	if len(g.errs) > 0 {
		err := g.errs[0]
		g.errs = g.errs[1:]
		if err != nil {
			return "", err
		}
	}
	return session, nil
}

// stoodGap 은 스트림 s 의 회차 session 에서 후보 k 의 GAP 이 섰다는 결과를 루프에 넣는다(publishDone 의 settleGap 자리).
func stoodGap(r *rewindLoop, s, session string, k int64) {
	seq := k
	r.settleGap(r.stream(s), &laneJob{kind: jobTick, session: session, gap: &seq}, publish.Outcome{GapSeq: &seq})
}

func newGapRunLoop(t *testing.T) (*finalLoop, *gapRunCalls) {
	t.Helper()
	f := newFinalLoop(t, finalize.ModePublishing)
	g := &gapRunCalls{}
	f.rewind.endGapRun = g.end
	return f, g
}

// L3 — 소유 회차에서 GAP 이 seq 로 이어서 셋 서면 다음 holdTick 에 그 회차를 끝낸다(한 번). 둘째까지는 끝내지 않는다. 끝낸
// 회차는 빠른 길로 정산에 들어간다(그 행을 읽는다). 변이 = 문턱 3 을 4 로 · 발동 걸음 제거 → FAIL.
func TestGapRunEndsSessionAfterThreeConsecutiveGapsL3(t *testing.T) {
	f, g := newGapRunLoop(t)
	f.work.rows["S1"] = endingRow("S1", nil)
	f.view("demo", "S1", "live")
	var forgot []string
	f.rewind.forgetSession = func(id string) { forgot = append(forgot, id) }
	stoodGap(f.rewind, "demo", "S1", 10)
	stoodGap(f.rewind, "demo", "S1", 11)
	f.hold(loopBase)
	f.settle(loopBase)
	if len(g.calls) != 0 {
		t.Fatalf("GAP 둘에 끝냈다: %v", g.calls)
	}
	stoodGap(f.rewind, "demo", "S1", 12)
	f.hold(loopBase.Add(time.Second))
	f.settle(loopBase.Add(time.Second))
	f.hold(loopBase.Add(2 * time.Second))
	f.settle(loopBase.Add(2 * time.Second))
	if !slices.Equal(g.calls, []string{"demo/S1"}) {
		t.Fatalf("L3 종료 = %v, want [demo/S1] 한 번", g.calls)
	}
	if !slices.Contains(f.work.loads, "S1") {
		t.Fatalf("끝낸 회차를 빠른 길로 읽지 않았다: %v", f.work.loads)
	}
	if rec := f.logs.last("session_gap_run_end"); rec == nil || rec.level != slog.LevelInfo || rec.attrs["session_id"] != "S1" {
		t.Fatalf("session_gap_run_end = %+v, want INFO session_id=S1", rec)
	}
	// 끝낸 뒤처리(liveEnded — 오프라인 전이 · L2′ 와 같은 함수 · cc M1): 캐시 ending · 업로더 회차 메모리 치움.
	if got := f.sessionState("demo", "S1"); got != "ending" {
		t.Errorf("캐시 S1 = %q, want ending", got)
	}
	if !slices.Equal(forgot, []string{"S1"}) {
		t.Errorf("업로더 회차 메모리 치움 = %v, want [S1]", forgot)
	}
}

// 「연속」은 seq 가 이어진 GAP 셋이다. 사이에 실제 ③ 이 와서 머리가 나아가면 다음 GAP 의 seq 가 하나 건너뛰어 셈이 1 로
// 돌아간다. 회차가 바뀌어도 1 이다. 변이 = seq 연속 조건 제거 · 회차 조건 제거 → 각각 FAIL.
func TestGapRunCountsOnlyContiguousGapsOfOneSessionL3(t *testing.T) {
	tests := []struct {
		name string
		gaps []struct {
			session string
			seq     int64
		}
	}{
		{"사이에_③", []struct {
			session string
			seq     int64
		}{{"S1", 10}, {"S1", 11}, {"S1", 13}}},
		{"회차_바뀜", []struct {
			session string
			seq     int64
		}{{"S1", 10}, {"S1", 11}, {"S2", 12}}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f, g := newGapRunLoop(t)
			for _, gp := range tt.gaps {
				stoodGap(f.rewind, "demo", gp.session, gp.seq)
			}
			f.hold(loopBase)
			f.settle(loopBase)
			if len(g.calls) != 0 {
				t.Fatalf("이어지지 않은 GAP 셋에 끝냈다: %v", g.calls)
			}
		})
	}
}

// 종료 문장이 실패하면(결과 모름 — 커밋했을 수 있다) ERROR 한 줄 뒤 30초 동안 다시 부르지 않는다 — 문장은 회차 술어라
// 다시 불러도 안전하지만, 한 번에 루프를 최대 약 20초 세우므로 매 틱 되풀이하지 않는다. 쉼은 루프 하나에 하나다(스트림 ·
// 회차를 가리지 않는다 — live_end_test.go). 변이 = 재시도 간격 제거 → FAIL.
func TestGapRunEndFailureRetriesAfterBackoffL3(t *testing.T) {
	f, g := newGapRunLoop(t)
	g.errs = []error{errors.New("lock_timeout")}
	for k := int64(10); k < 13; k++ {
		stoodGap(f.rewind, "demo", "S1", k)
	}
	for _, d := range []time.Duration{0, time.Second, 29 * time.Second} {
		f.hold(loopBase.Add(d))
		f.settle(loopBase.Add(d))
	}
	if len(g.calls) != 1 {
		t.Fatalf("30초 안 시도 = %v, want 한 번", g.calls)
	}
	if rec := f.logs.last("gap_run_end_failed"); rec == nil || rec.level != slog.LevelError {
		t.Fatalf("gap_run_end_failed = %+v, want ERROR", rec)
	}
	f.hold(loopBase.Add(30 * time.Second))
	f.settle(loopBase.Add(30 * time.Second))
	if len(g.calls) != 2 {
		t.Fatalf("30초 뒤 시도 = %v, want 두 번째", g.calls)
	}
}

// 스위치(REWIND_GAP_RUN_END)가 꺼져 있으면 조립이 종료 손잡이를 끼우지 않는다 — GAP 이 몇이 서도 끝내지 않는다(기본 끔 —
// kty K5). 변이 = 스위치를 보지 않고 늘 끼움 → FAIL.
func TestGapRunEndWiredOnlyWhenSwitchOn(t *testing.T) {
	fa := finalizeAssembly{enqueue: noEndedEnqueue}
	if fa.gapRunEnd(false, nil) != nil {
		t.Fatal("스위치가 꺼졌는데 L3 종료 손잡이를 끼웠다")
	}
	if fa.gapRunEnd(true, nil) == nil {
		t.Fatal("스위치가 켜졌는데 L3 종료 손잡이가 없다")
	}
	f := newFinalLoop(t, finalize.ModePublishing)
	for k := int64(10); k < 14; k++ {
		stoodGap(f.rewind, "demo", "S1", k)
	}
	f.hold(loopBase) // 손잡이 nil — 아무것도 하지 않는다(패닉 없음)
	f.settle(loopBase)
}
