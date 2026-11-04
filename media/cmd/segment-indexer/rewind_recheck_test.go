package main

// ending 재조회(POK-525 Ⓒ-7 — 설계 4-3 · 5-3 · E19)의 루프 층 시험이다. 종료 항목이 ending(잠정 포함)인 스트림은 재조회
// 주기마다 상태 표지로 되넣어지고, 기존 상태 읽기 차선이 DB 를 다시 읽는다. 차선 몸체는 가짜(fakeFinalWork)다.

import (
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/finalize"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/status"
)

// readLog 는 상태 읽기가 부른 스트림을 차례로 적는다(차선 고루틴에서 불린다). 읽기 값은 늘 Ending 이다.
type readLog struct {
	mu      sync.Mutex
	streams []string
}

func (l *readLog) read(stream string) (index.PlaybackState, bool, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.streams = append(l.streams, stream)
	return index.PlaybackState{Phase: index.PhaseEnding, RoundID: "R-" + stream, LatestSessionID: "S-" + stream}, true, nil
}

func (l *readLog) all() []string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return slices.Clone(l.streams)
}

// sorted 는 읽은 스트림을 정렬해 돌려준다 — 상한 안의 읽기는 동시에 돌아 차례가 정해지지 않는다.
func (l *readLog) sorted() []string { return slices.Sorted(slices.Values(l.all())) }

// recheckLoop 은 종료 항목 finals 를 든 정산 루프 픽스처다 — 상태 읽기를 reads 에 적는다. sink 는 가짜라 읽기 결과가 종료
// 항목을 바꾸지 않는다(시험이 정한 종료 항목이 그대로 남는다).
func recheckLoop(t *testing.T, finals map[string]status.Final) (*finalLoop, *readLog) {
	t.Helper()
	f := newFinalLoop(t, finalize.ModeOff)
	reads := &readLog{}
	f.work.playback = reads.read
	f.rewind.final = finals
	return f, reads
}

// endingFinal 은 회차 round 의 ending 종료 항목이다(round 가 빈 값이면 Forget 의 잠정 항목).
func endingFinal(round string) status.Final {
	return status.Final{RoundID: round, LatestSessionID: "S" + round, Status: status.Status{Mode: status.ModeEnding}}
}

// 5-3 (가): 재조회는 종료 항목이 ending 인 스트림(잠정 포함)만 되넣고, 주기(30초)마다 한 번이다 — vod · ended 는 읽지
// 않고, 주기 안의 holdTick 은 되넣지 않는다. 변이 = 재조회 제거 → 읽기 0 FAIL · 국면 조건 제거 → vod · ended 읽기 FAIL ·
// 주기 무시 → 29초 틱 읽기 FAIL.
func TestRecheckEndingRereadsOnlyEndingFinalsEachPeriod(t *testing.T) {
	f, reads := recheckLoop(t, map[string]status.Final{
		"e": endingFinal("R"),
		"p": endingFinal(""),
		"v": {RoundID: "V", LatestSessionID: "SV", Status: status.Status{Ready: true, Mode: status.ModeVOD}},
		"d": {RoundID: "D", LatestSessionID: "SD", Status: status.Status{Mode: status.ModeEnded}},
	})

	f.hold(loopBase)
	f.settle(loopBase)
	if got, want := reads.sorted(), []string{"e", "p"}; !slices.Equal(got, want) {
		t.Fatalf("첫 재조회 읽기 = %v, want %v(ending · 잠정 ending 만)", got, want)
	}
	for _, d := range []time.Duration{time.Second, endingRecheckEvery - time.Second} {
		f.hold(loopBase.Add(d))
		f.settle(loopBase.Add(d))
	}
	if got := reads.all(); len(got) != 2 {
		t.Fatalf("주기 안 holdTick 뒤 읽기 = %v, want 그대로 둘(주기 %v 안에는 되넣지 않는다)", got, endingRecheckEvery)
	}
	next := loopBase.Add(endingRecheckEvery)
	f.hold(next)
	f.settle(next)
	if got, want := reads.sorted(), []string{"e", "e", "p", "p"}; !slices.Equal(got, want) {
		t.Fatalf("다음 주기 읽기 = %v, want %v", got, want)
	}
}

// 5-3 「30초는 주기이지 상한이 아니다」: 읽기 상한이 1 이고 ending 스트림이 셋이면, 첫 틱에 하나만 읽고 나머지는 표지가
// 되돌려져 다음 holdTick 에 하나씩 읽힌다 — 다음 재조회 주기(30초)를 기다리지 않는다. 변이 = 상한 초과 표지 되돌림 제거 →
// 둘째 · 셋째 스트림이 다음 주기까지 밀려 FAIL.
func TestRecheckEndingOverReadMaxSlipsToNextHoldTick(t *testing.T) {
	f, reads := recheckLoop(t, map[string]status.Final{"a": endingFinal("A"), "b": endingFinal("B"), "c": endingFinal("C")})
	f.rewind.fin.readMax = 1

	for i, want := range [][]string{{"a"}, {"a", "b"}, {"a", "b", "c"}} {
		now := loopBase.Add(time.Duration(i) * time.Second)
		f.hold(now)
		f.settle(now)
		if got := reads.all(); !slices.Equal(got, want) {
			t.Fatalf("%d번째 holdTick 뒤 읽기 = %v, want %v(상한 1 — 넘친 표지는 다음 틱으로)", i+1, got, want)
		}
	}
}

// 상태 몫이 없거나(정산 몫 nil) 꺼짐이면 재조회는 아무것도 하지 않는다 — 패닉 없이 지나간다.
func TestRecheckEndingWithoutFinalizerIsNoop(t *testing.T) {
	f := newOfflineLoop(t, nil)
	f.rewind.final = map[string]status.Final{"e": endingFinal("R")}

	f.rewind.recheckEnding(loopBase)
}
