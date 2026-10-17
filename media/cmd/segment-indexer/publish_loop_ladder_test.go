package main

// 발행 루프의 사다리 · lazy 갱신 배선(체크리스트 474 B-3 22–28 · 계획 4.5 A0 결정 6 · 363 「c4-fix3 개정」 ⓐ–ⓘ · 커밋 순서 7
// 〔r51〕 〔r51a〕). 픽스처는 publish_loop_fixture_test.go 다.

import (
	"log/slog"
	"slices"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
)

// publishOnce 는 스트림 s 의 회차 sid 에 원소 하나로 틱을 내고 결과(발행 성공)를 받는다 — 발행 이력 · fence 를 세운다.
func (f *publishLoop) publishOnce(s, sid string, seq int64, at time.Time) {
	f.t.Helper()
	f.uploaded(s, sid, seq)
	f.hold(at)
	f.settle(at)
}

// 사다리는 holdTick 마다 스트림 하나에 한 번이고 돌려준 상태로 갈아 끼운다(체크리스트 474 B-3 22 · 363 J34) — 머리 다음 행이
// L1 에 닿은 두 스트림은 holdTick 마다 한 번씩 Expedite 를 받고(L1 되풀이 — ⓙ), 정체 경보(L4)는 정체 한 번에 한 줄이다(상태의
// 되풀이 가드가 이어진다).
func TestLadderEvaluatedOncePerStreamPerHoldTick(t *testing.T) {
	f := newPublishLoop(t)
	for _, s := range []string{"a", "b"} {
		f.put(s, liveLedger(s, "S", 4, 3)) // 행 4 가 머리 다음 행 — PDT +16초 · dueAt +22초
		f.publishOnce(s, "S", 3, loopBase)
	}
	l1 := loopBase.Add(23 * time.Second) // holdAge 1초 — L1 · L2 전

	for i := range 3 {
		f.hold(l1.Add(time.Duration(i) * 300 * time.Millisecond))
	}

	if got := f.ups.expedites(); !slices.Equal(got, []string{"a/4", "b/4", "a/4", "b/4", "a/4", "b/4"}) {
		t.Errorf("Expedite = %v, want holdTick 마다 스트림마다 행 4 한 번", got)
	}
	if n := f.logs.countLevel(slog.LevelError, "rewind_publish_stall_seconds"); n != 2 {
		t.Errorf("L4 ERROR = %d줄, want 2(스트림마다 정체 한 번에 한 줄 — holding)", n)
	}
}

// L1 · L2 의 실행(체크리스트 474 B-3 23 · 363 ⓑ · 판단 J80 ⑵) — L1 이면 그 행의 ③ 백오프를 당기고(Expedite), L2 면 그 행을 후보로
// GAP 틱을 낸다(입력 창은 그 행을 settled 로 덮은 겹침 스냅숏 — 후보 행까지 실린다). 소유 회차의 init 이 확정되지 않아 게이트가
// 닫혀 있으면 L1 은 서도 GAP 틱은 0 이다.
func TestLadderL1ExpeditesAndL2SendsGapTick(t *testing.T) {
	tests := []struct {
		name    string
		hold    time.Duration // 행 4 의 holdAge
		initUp  bool
		gapTick bool
	}{
		{"L1", time.Second, true, false},
		{"L2", 2 * time.Second, true, true},
		{"L2_게이트_닫힘", 2 * time.Second, false, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f := newPublishLoop(t)
			l := liveLedger("s1", "S", 5, 3)
			l.Rows[5].PlaybackUploaded = true
			l.Sessions[0].InitUploaded = tt.initUp
			f.put("s1", l)

			at := loopBase.Add(22*time.Second + tt.hold)
			f.hold(at)
			f.settle(at)

			if got := f.ups.expedites(); !slices.Equal(got, []string{"s1/4"}) {
				t.Errorf("Expedite = %v, want [s1/4]", got)
			}
			calls := f.work.tickCalls()
			if !tt.gapTick {
				if len(calls) != 0 {
					t.Errorf("틱 = %+v, want 0", calls)
				}
				return
			}
			if len(calls) != 1 || calls[0].in.GapCandidate == nil || *calls[0].in.GapCandidate != 4 ||
				!slices.Equal(rowSeqs(calls[0]), seqRange(0, 5)) {
				t.Errorf("틱 = %+v, want GAP 틱 한 번(후보 4 · 겹침 창 0..5)", calls)
			}
		})
	}
}

// OwnedSince(체크리스트 474 B-3 24 · 363 ⓓ · 판단 J29) — 이 프로세스의 발행 성공 없이 화해가 되살린 P 만 있는 회차도 발행
// 이력이 있다: stallAge 는 처음 소유 회차로 본 시각부터라 7.5초가 지나면 L4 가 선다(OwnedSince 가 영값이면 무음이다).
func TestLadderAlarmsForRestoredPrevOnlySession(t *testing.T) {
	f := newPublishLoop(t)
	f.put("s1", liveLedger("s1", "S", 3, 3))
	f.work.tickFn = func(st publish.State, _ publish.TickInput) publish.Outcome {
		st.FenceHeld = true
		st.Prev = &publish.Manifest{Published: rewind.Published{Gen: 3, MediaSequence: 0, PublishedSeq: 3, SegmentCount: 4}, ETag: "e3"}
		return publish.Outcome{State: st} // 화해가 P 를 되살렸고 이 틱은 발행하지 않았다
	}
	f.publishOnce("s1", "S", 3, loopBase) // 처음 소유 회차로 본 시각 = loopBase

	f.hold(loopBase.Add(7499 * time.Millisecond))
	if n := f.logs.count("rewind_publish_stall_seconds"); n != 0 {
		t.Fatalf("7.499초 L4 = %d줄, want 0", n)
	}
	f.hold(loopBase.Add(7500 * time.Millisecond))
	if a := f.logs.attrs("rewind_publish_stall_seconds"); a == nil || a["seconds"] != 7.5 {
		t.Errorf("L4 = %v, want 7.5초 한 줄 — 되살린 P 만 있어도 처음 소유 회차로 본 시각부터 잰다", a)
	}
}

// live 가 아닌 소유 회차(체크리스트 474 B-3 25 · 363 ⓘ · 리스크 C3) — 사다리는 그 회차로도 평가된다: 발행을 멈춘 사실을 회차마다
// 한 번 WARN 하고, 머리 다음 행이 늦어도 L1 · L2 는 없다.
func TestLadderWarnsNonLiveOwnerOnceWithoutL1OrL2(t *testing.T) {
	f := newPublishLoop(t)
	f.put("s1", liveLedger("s1", "S", 4, 3))
	f.publishOnce("s1", "S", 3, loopBase)
	f.cache.ApplySessionEnding("s1", "S")

	for i := range 5 {
		f.hold(loopBase.Add(time.Hour + time.Duration(i)*time.Second))
		f.settle(loopBase.Add(time.Hour + time.Duration(i)*time.Second))
	}

	if n := f.logs.countLevel(slog.LevelWarn, "rewind_publish_stall_seconds"); n != 1 {
		t.Errorf("C3 WARN = %d줄, want 1(회차마다 한 번)", n)
	}
	if got, calls := f.ups.expedites(), f.work.tickCalls(); len(got) != 0 || len(calls) != 1 {
		t.Errorf("Expedite %v · 틱 %d번, want [] · 1(첫 발행뿐)", got, len(calls))
	}
}

// L4 등급(체크리스트 474 B-3 26 · kty 결정 ④ A — 판단 J73 대체 · 363 ⓔ) — 머리 다음 행이 캐시 뷰에 없어서 선 정체(유입이 멈춘
// 방송 종료 구간의 모양 — no_row)는 WARN, 머리 다음 행이 있는데 L2 가 서지 못해 선 정체(incomplete 등)는 ERROR 다. 루프는
// 등급을 정하지 않는다 — 사다리가 까닭 값으로 가른다.
func TestLadderStallLevelFollowsReasonInLoop(t *testing.T) {
	f := newPublishLoop(t)
	f.put("ended", liveLedger("ended", "S", 3, 3)) // 머리 3 · 다음 행 없음
	incomplete := liveLedger("stuck", "S", 4, 3)
	incomplete.Rows[4].PlaybackPDT = time.Time{} // 다음 행은 있는데 PDT 가 없다
	f.put("stuck", incomplete)
	for _, s := range []string{"ended", "stuck"} {
		f.publishOnce(s, "S", 3, loopBase)
	}

	f.hold(loopBase.Add(7500 * time.Millisecond))

	got := map[string]slog.Level{}
	f.logs.mu.Lock()
	for _, r := range f.logs.records {
		if r.Message != "rewind_publish_stall_seconds" {
			continue
		}
		r.Attrs(func(a slog.Attr) bool {
			if a.Key == "stream" {
				got[a.Value.String()] = r.Level
			}
			return true
		})
	}
	f.logs.mu.Unlock()
	if want := map[string]slog.Level{"ended": slog.LevelWarn, "stuck": slog.LevelError}; len(got) != 2 ||
		got["ended"] != want["ended"] || got["stuck"] != want["stuck"] {
		t.Errorf("L4 등급 = %v, want %v", got, want)
	}
}

// 캐시 적격 · DB 부적격 정체(체크리스트 474 B-3 27 · 363 ⓖ · kty #77) — 캐시에서는 GAP 적격이라 L2 가 GAP 틱을 내지만 DB 가 부적격으로
// 봐 서지 않으면(결과에 GapSeq · UploadedSeq 없음) 그 사실이 다음 평가의 입력이 되고, stallAge 가 7.5초를 넘은 첫 평가에 ERROR
// gap=unstood 가 선다 — 발행자 로그가 없는 갈래라 이것이 유일한 신호다.
func TestLadderCacheEligibleDBIneligibleAlarmsUnstood(t *testing.T) {
	f := newPublishLoop(t)
	l := liveLedger("s1", "S", 5, 3)
	l.Rows[5].PlaybackUploaded = true
	f.put("s1", l)
	published := loopBase.Add(25 * time.Second) // 행 4 의 holdAge 3초 — L2
	f.work.tickFn = func(st publish.State, in publish.TickInput) publish.Outcome {
		if in.GapCandidate != nil {
			return publish.Outcome{State: st} // DB 부적격 — 서지 않았다
		}
		return publishedOutcome(st, in, published)
	}
	f.cache.ApplyPublishedGap("s1", 4) // 첫 발행이 행 0..5 를 싣게 잠시 GAP 으로 둔다
	f.publishOnce("s1", "S", 5, published)
	f.put("s1", l) // 캐시는 다시 행 4 를 모른다(미확정)

	for d := time.Second; d < 7500*time.Millisecond; d += time.Second {
		f.hold(published.Add(d))
		f.settle(published.Add(d))
	}
	if n := f.logs.count("rewind_publish_stall_seconds"); n != 0 {
		t.Fatalf("7.5초 전 L4 = %d줄, want 0", n)
	}
	f.hold(published.Add(7500 * time.Millisecond))
	if a := f.logs.attrs("rewind_publish_stall_seconds"); f.logs.countLevel(slog.LevelError, "rewind_publish_stall_seconds") != 1 ||
		a["gap"] != "unstood" {
		t.Errorf("L4 = %v, want ERROR 한 줄(gap=unstood)", a)
	}
}

// lazy 갱신(체크리스트 474 B-3 28 · 계획 커밋 순서 7 〔r51〕 · 판단 J80 ⑷) — fence 를 쥔 live 소유 회차에 마지막 갱신에서 3.5초가
// 지나야 갱신 작업을 하나 내고(그 전에는 0), 갱신이 돌아오면 그 시각부터 다시 잰다. 틱이 도는 동안에는 차례가 와도 내지 않고
// 도는 틱에 병합하지도 않는다 — 병합되면 끝난 뒤 틱이 한 번 더 돈다(P0 왕복이 늘어 틱당 상한이 깨진다).
func TestLazyRenewWaitsForIntervalAndIdleLane(t *testing.T) {
	t.Run("간격", func(t *testing.T) {
		f := newPublishLoop(t)
		f.put("s1", liveLedger("s1", "S", 3, 3))
		f.publishOnce("s1", "S", 3, loopBase) // lease 갱신 시각 loopBase

		var renewedAt []time.Duration
		for d := time.Second; d <= 8*time.Second; d += time.Second {
			before := len(f.work.renewCalls())
			f.hold(loopBase.Add(d))
			f.settle(loopBase.Add(d))
			if len(f.work.renewCalls()) > before {
				renewedAt = append(renewedAt, d)
			}
		}
		if want := []time.Duration{4 * time.Second, 8 * time.Second}; !slices.Equal(renewedAt, want) {
			t.Errorf("lazy 갱신 시각 = %v, want %v — 3.5초가 지난 첫 holdTick · 그 갱신에서 다시 3.5초", renewedAt, want)
		}
		if n := len(f.work.tickCalls()); n != 1 {
			t.Errorf("틱 = %d번, want 1(갱신은 틱을 부르지 않는다)", n)
		}
	})
	t.Run("도는_틱_옆", func(t *testing.T) {
		f := newPublishLoop(t)
		f.put("s1", liveLedger("s1", "S", 4, 3))
		f.publishOnce("s1", "S", 3, loopBase)
		f.work.mu.Lock()
		f.work.gate = make(chan struct{})
		gate := f.work.gate
		f.work.mu.Unlock()
		f.cache.ApplyPlaybackUploaded("s1", 4)
		f.publishOnceStart("s1", "S", 4, loopBase.Add(time.Second)) // 둘째 틱이 문에서 멈춘다

		f.hold(loopBase.Add(5 * time.Second)) // lazy 차례 — 차선이 돈다
		f.work.mu.Lock()
		f.work.gate = nil
		f.work.mu.Unlock()
		close(gate)
		f.settle(loopBase.Add(5 * time.Second))

		if r, n := len(f.work.renewCalls()), len(f.work.tickCalls()); r != 0 || n != 2 {
			t.Errorf("갱신 %d번 · 틱 %d번, want 0 · 2 — 도는 틱에 lazy 를 병합하지 않는다", r, n)
		}
	})
}

// publishOnceStart 는 원소 하나로 틱을 내되 결과를 받지 않는다(도는 틱을 만든다).
func (f *publishLoop) publishOnceStart(s, sid string, seq int64, at time.Time) {
	f.t.Helper()
	f.uploaded(s, sid, seq)
	f.hold(at)
	if f.running(s) == nil {
		f.t.Fatal("틱이 나가지 않았다 — 준비가 어긋났다")
	}
}

// sessionLedger 는 회차 하나의 장부다(스트림 s · 회차 sid · 조각 first..last 가 ③ 올라갔다).
func sessionLedger(s, sid string, first, last int64) index.RewindLedger {
	l := index.RewindLedger{HasCutoff: true, FloorSeq: first, Sessions: []index.RewindSession{{
		SessionID: sid, State: "live", InitUploaded: true, TargetDuration: 6, MinSeq: first,
	}}}
	for seq := first; seq <= last; seq++ {
		l.Rows = append(l.Rows, segRow(s, sid, seq, true))
	}
	return l
}
