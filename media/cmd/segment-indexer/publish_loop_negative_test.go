package main

// 발행 루프의 음성 대조(체크리스트 474 B-5 — 커밋 7 몫 · 계획 6.4)와 넘어온 주의(445 「커밋 7b · 7c 개발 지시문에 넣을 것」). 정상
// 경로에서 루프가 헛 신호 · 헛 작업을 내지 않는지 본다. 픽스처는 publish_loop_fixture_test.go 다.

import (
	"errors"
	"slices"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/upload"
)

// 정상 경로의 사다리(체크리스트 474 B-5 L1–L4 · 계획 6.4 「holdAge 가 음수인 정상 경로에서 사다리 미발동」) — 4초 조각이 끝나는
// 대로 장부에 들고 ③ 가 1초 뒤 올라가면 머리 다음 행의 holdAge 는 늘 음수라, 2분 동안 holdTick 마다 평가해도 Expedite · GAP 틱 ·
// 정체 경보가 하나도 없다. 발행은 조각마다 한 번이다.
func TestNormalPathRaisesNoLadderAction(t *testing.T) {
	f := newPublishLoop(t)
	f.put("s1", liveLedger("s1", "S", 0, 0))
	f.publishOnce("s1", "S", 0, loopBase.Add(5*time.Second))

	for sec := int64(6); sec <= 120; sec++ {
		now := loopBase.Add(time.Duration(sec) * time.Second)
		switch k := sec / 4; sec % 4 {
		case 0: // 조각 k−1 이 끝나 장부에 든다(INSERT push)
			r := segRow("s1", "S", k-1, false)
			f.cache.ApplyInsert("s1", k-1, index.SeedResult{SessionID: "S", DurationMS: r.DurationMS,
				PlaybackPDT: r.PlaybackPDT, PlaybackS3Key: r.PlaybackS3Key})
		case 1: // 1초 뒤 ③ 확정
			f.uploaded("s1", "S", k-1)
		}
		f.hold(now)
		f.settle(now)
	}

	if got := f.ups.expedites(); len(got) != 0 {
		t.Errorf("Expedite = %v, want []", got)
	}
	for _, c := range f.work.tickCalls() {
		if c.in.GapCandidate != nil {
			t.Fatalf("GAP 틱 = %+v, want 0", c)
		}
	}
	if n := f.logs.count("rewind_publish_stall_seconds"); n != 0 {
		t.Errorf("정체 경보 = %d줄, want 0", n)
	}
	if n := len(f.work.tickCalls()); n != 29 {
		t.Errorf("틱 = %d번, want 29(조각 0..28 마다 한 번)", n)
	}
}

// 감시 화해 · (c) 의 음성 대조(체크리스트 474 B-5 · 계획 4.5 A2 결정 11 · 6.4) — DB 와 캐시가 같으면(init 확정 · 계승 없음) 화해 WARN 이
// 없고, 컷오프 있는 스트림만 있는 감시에는 rewind_cutoff_absent 가 없다.
func TestWatchQuietWhenCacheMatchesDB(t *testing.T) {
	f := newPublishLoop(t)
	f.watchEveryHold()
	f.put("s1", liveLedger("s1", "S", 3, 3))
	f.work.watchFn = func([]index.WatchProbe) publish.WatchOutcome {
		return publish.WatchOutcome{RewindWatch: index.RewindWatch{Live: []index.WatchLive{
			{StreamID: "s1", SessionID: "S", InitUploaded: true, InheritAbsent: true, HasCutoff: true},
		}}}
	}

	f.hold(loopBase)
	f.settleWatch(loopBase, 1)

	if n, m := f.logs.count("cache_drift_repaired"), f.logs.count("rewind_cutoff_absent"); n != 0 || m != 0 {
		t.Errorf("cache_drift_repaired %d줄 · rewind_cutoff_absent %d줄, want 0 · 0", n, m)
	}
}

// 정상 계승 취소(체크리스트 474 B-5 「P2′ 정상 취소(1행)는 요구 적재 0」) — 틱 결과가 계승을 취소했다고만 하면(Revoked · DemandLoad
// 거짓) 캐시의 계승을 풀 뿐 적재를 요구하지 않는다.
func TestNormalRevokeDemandsNoLoad(t *testing.T) {
	f := newPublishLoop(t)
	l := liveLedger("s1", "S", 3, 3)
	l.Sessions[0].InheritsSession, l.Sessions[0].DiscontinuityBase = "P", 5
	f.put("s1", l)
	f.work.tickFn = func(st publish.State, in publish.TickInput) publish.Outcome {
		out := publishedOutcome(st, in, loopBase)
		out.Revoked = true
		return out
	}
	f.publishOnce("s1", "S", 3, loopBase)
	f.hold(loopBase.Add(time.Second))
	f.settle(loopBase.Add(time.Second))

	if s, _ := f.cache.Session("s1", "S"); s.InheritsSession != "" {
		t.Errorf("캐시 계승 = %q, want 풀림", s.InheritsSession)
	}
	if n := len(f.work.loadCalls()); n != 0 {
		t.Errorf("적재 = %d번, want 0", n)
	}
}

// 재발행의 DISC-SEQ(체크리스트 474 B-5 「A1(r40): 축출 없는 틱 · 발행 실패만 겪은 재시도에서 DISC-SEQ 불변」 · 계획 4.5 A1 「멱등」) —
// 발행이 실패한 틱 뒤의 재발행은 같은 P 로 세므로, 축출이 없으면 실패한 틱과 같은 DISC-SEQ 를 싣는다.
func TestRepublishAfterFailureKeepsDiscontinuitySequence(t *testing.T) {
	f := newPublishLoop(t)
	l := liveLedger("s1", "S", 5, 3)
	l.Sessions[0].DiscontinuityBase = 7
	f.put("s1", l)
	f.publishOnce("s1", "S", 3, loopBase)
	f.work.tickFn = func(st publish.State, in publish.TickInput) publish.Outcome {
		if len(f.work.tickCalls()) == 2 {
			st.FenceHeld = true
			return publish.Outcome{State: st, Err: errors.New("publish: 화해 DB 쓰기 실패")}
		}
		return publishedOutcome(st, in, loopBase)
	}
	f.cache.ApplyPlaybackUploaded("s1", 4)
	f.publishOnce("s1", "S", 4, loopBase.Add(time.Second)) // 실패
	f.hold(loopBase.Add(2 * time.Second))                  // 재발행
	f.settle(loopBase.Add(2 * time.Second))

	calls := f.work.tickCalls()
	if len(calls) != 3 {
		t.Fatalf("틱 = %d번, want 3", len(calls))
	}
	if d := []int64{calls[1].in.DiscontinuitySequence, calls[2].in.DiscontinuitySequence}; !slices.Equal(d, []int64{7, 7}) {
		t.Errorf("실패한 틱 · 재발행의 DISC-SEQ = %v, want [7 7]", d)
	}
}

// 적재 중의 소유 회차(445 「커밋 7b · 7c 개발 지시문에 넣을 것」 · 7a 검수 C7a-13 cc) — 적재 중에는 Owner 가 거짓이지만 그것은
// 소유 회차가 바뀐 것이 아니다: 적재가 끝난 뒤의 틱은 적재 전 발행 상태(P · fence)를 그대로 싣는다(P 로 DISC-SEQ 를 센다).
// 적재 중 holdTick 에는 그 스트림의 Dirty 원소가 있어 틱 요청이 실제로 Owner 거짓에 닿는다(종결 라운드 1 CL1-10 —
// 뮤턴트 m80_no_owner_check 가 이 갈래에서만 갈린다).
func TestLoadingDoesNotResetOwnerState(t *testing.T) {
	f := newPublishLoop(t)
	f.put("s1", liveLedger("s1", "S", 4, 3))
	f.work.ledgers["s1"] = liveLedger("s1", "S", 4, 4)
	f.publishOnce("s1", "S", 3, loopBase)
	f.work.mu.Lock()
	f.work.loadGate = make(chan struct{})
	f.work.mu.Unlock()
	f.cache.DemandLoad("s1", nil)
	f.hold(loopBase.Add(time.Second)) // 적재가 문에서 멈춘다
	f.uploaded("s1", "S", 4)
	f.hold(loopBase.Add(2 * time.Second)) // 적재 중 holdTick — Dirty 원소가 틱을 요청하지만 Owner 거짓
	f.work.open(&f.work.loadGate)
	f.settle(loopBase.Add(2 * time.Second))

	calls := f.work.tickCalls()
	if len(calls) != 2 {
		t.Fatalf("틱 = %d번, want 2(적재 뒤 틱)", len(calls))
	}
	if p := calls[1].st.Prev; p == nil || p.PublishedSeq != 3 || !calls[1].st.FenceHeld {
		t.Errorf("적재 뒤 틱의 상태 = %+v, want 적재 전 P(끝 seq 3) · fence 그대로", calls[1].st)
	}
}

// 루프 무대기(체크리스트 474 B-4 10 의 PG 없는 몫 · 판단 J92 · 설계 3.3 「오래 걸릴 수 있는 작업은 워커로 발사하고 즉시 반환」) — 실제
// 메인 루프에서 발행 작업이 2초 걸려도(발행 워커 지연 주입) 그동안 보류 틱 · 수집 결과(CollectDone) · 다른 스트림 작업의 결과(완료
// case — Lanes.Done)는 곧바로 처리된다. 완료 case 는 실제 입력으로 잰다(종결 라운드 1 CL1-7 (a)): 빠른 s2 작업의 결과가 완료
// 채널로 들어온 뒤의 보류 틱 수신 지연이 0 에 가깝고, s2 의 다음 틱이 앞 결과의 P 를 실은 채 느린 s1 작업이 끝나기 전에 선다.
// 느린 작업의 결과는 끝난 뒤 완료 case 로 들어온다.
func TestLoopDoesNotWaitForPublishWork(t *testing.T) {
	f := newPublishLoop(t)
	f.put("s1", liveLedger("s1", "S", 3, 2))
	f.put("s2", liveLedger("s2", "T", 4, 2))
	jobEnded := make(chan time.Time, 1)
	fastTicks := make(chan publish.State, 1) // s2 틱의 입력 상태 — 받는 쪽이 늦으면 버린다(작업이 매달리지 않게)
	f.work.tickFn = func(st publish.State, in publish.TickInput) publish.Outcome {
		if in.Playlist.StreamID == "s2" {
			select {
			case fastTicks <- st:
			default:
			}
			return publishedOutcome(st, in, loopBase)
		}
		time.Sleep(2 * time.Second)
		jobEnded <- time.Now()
		return publishedOutcome(st, in, loopBase)
	}
	armed := make(chan time.Time, 1)
	lf := newLoopFixture(t, false)
	holds := make(chan time.Time)
	lf.deps.ix, lf.deps.rewind, lf.deps.holdTicks = f.ix, f.rewind, holds
	lf.deps.armSweeper = func() { armed <- time.Now() }
	cancel := lf.run()
	send := func(what string, ch chan time.Time) time.Duration {
		t.Helper()
		start := time.Now()
		select {
		case ch <- time.Now():
		case <-time.After(5 * time.Second):
			t.Fatalf("루프가 %s 을 받지 않는다", what)
		}
		return time.Since(start)
	}
	fastTick := func() publish.State {
		t.Helper()
		select {
		case st := <-fastTicks:
			return st
		case <-time.After(5 * time.Second):
			t.Fatal("s2 틱이 5초 안에 서지 않았다")
			return publish.State{}
		}
	}

	f.uploaded("s1", "S", 3)
	send("보류 틱", holds) // 발행 틱을 낸다 — 작업은 2초 걸린다
	jobStarted := time.Now()
	f.uploaded("s2", "T", 3)
	send("보류 틱", holds) // s2 틱 — 결과가 곧바로 완료 채널로 들어온다
	if first := fastTick(); first.Prev != nil {
		t.Fatalf("s2 첫 틱의 P = %+v, want 없음", first.Prev)
	}
	var worst time.Duration
	for range 3 {
		worst = max(worst, send("보류 틱", holds))
	}
	f.uploaded("s2", "T", 4)
	send("보류 틱", holds) // s2 다음 틱 — 첫 결과가 완료 case 로 반영됐으면 그 P 를 싣는다
	second := fastTick()
	secondAt := time.Now()
	rescan := time.Now()
	select {
	case lf.rescans <- struct{}{}:
	case <-time.After(5 * time.Second):
		t.Fatal("루프가 재스캔 요청을 받지 않는다")
	}
	var collected time.Time
	select {
	case collected = <-armed: // 첫 완주 수집 — CollectDone case 가 처리했다
	case <-time.After(5 * time.Second):
		t.Fatal("수집 결과를 5초 안에 처리하지 않았다")
	}
	ended := <-jobEnded
	if err := lf.stop(cancel); err != nil {
		t.Fatalf("loop() = %v, want nil", err)
	}

	if worst > 500*time.Millisecond {
		t.Errorf("발행 작업이 도는 동안 보류 틱 수신 지연 = %v, want 0 에 가깝게(작업 2초를 기다리면 안 된다)", worst)
	}
	if p := second.Prev; p == nil || !secondAt.Before(ended) {
		t.Errorf("s2 다음 틱의 P = %+v(s1 작업 끝 앞인가 %t), want 앞 결과의 P · s1 작업이 도는 동안", p, secondAt.Before(ended))
	}
	if !collected.Before(ended) || collected.Sub(rescan) > time.Second {
		t.Errorf("수집 처리 = 요청 뒤 %v(작업 끝 %v 앞인가 %t), want 작업이 도는 동안 1초 안", collected.Sub(rescan), ended.Sub(jobStarted), collected.Before(ended))
	}
	slow := 0
	for _, c := range f.work.tickCalls() {
		if c.in.Playlist.StreamID == "s1" {
			slow++
		}
	}
	if slow != 1 {
		t.Errorf("s1 틱 = %d번, want 1", slow)
	}
}

// 빈 목록(체크리스트 474 A-6 2 · 312 J5 의 루프 쪽) — 소유 회차가 틱을 낼 수 있어도 창 안에 그 목록에 실을 행이 없으면(첫 조각의 ③ 가
// 아직이다) 틱을 내지 않는다: 틱은 P0 뒤 빈 창 거름으로 PUT 없이 끝나므로 P0 왕복만 쓴다. 행이 정착하면 다음 원소가 틱을 낸다.
func TestEmptyPlaylistSendsNoTick(t *testing.T) {
	f := newPublishLoop(t)
	f.put("s1", liveLedger("s1", "S", 1, -1)) // 행 0 · 1 모두 ③ 전
	f.dirty.mark(upload.DirtyEvent{Kind: upload.DirtyUploaded, StreamID: "s1", Axis: index.AxisInit, SessionID: "S"})

	f.hold(loopBase)
	f.settle(loopBase)
	if n := len(f.work.tickCalls()); n != 0 {
		t.Fatalf("빈 목록의 틱 = %d번, want 0", n)
	}
	f.cache.ApplyPlaybackUploaded("s1", 0)
	f.uploaded("s1", "S", 0)
	f.hold(loopBase.Add(time.Second))
	f.settle(loopBase.Add(time.Second))
	if calls := f.work.tickCalls(); len(calls) != 1 || !slices.Equal(rowSeqs(calls[0]), []int64{0}) {
		t.Errorf("틱 = %+v, want 행 0 한 번", calls)
	}
}

// 조립이 넘기지 않은 칸(체크리스트 474 판단 J85 ⑴ 「조립이 넘기는 값이 없을 때는 아무것도 하지 않는다」) — 감시 주기가 0 이면
// 보류 틱이 있어도 감시를 내지 않고(0 으로 나누지 않는다), Dirty 손잡이가 없으면 원소가 없는 것으로 돈다.
func TestPublishingWithoutAssembledPeriodOrDirtyHandle(t *testing.T) {
	f := newPublishLoop(t)
	f.rewind.holdEvery, f.rewind.watchEvery = time.Second, 0
	f.rewind.peekDirty, f.rewind.ackDirty = nil, nil
	f.put("s1", liveLedger("s1", "S", 3, 3))

	for i := range 3 {
		f.hold(loopBase.Add(time.Duration(i) * time.Second))
		f.settle(loopBase.Add(time.Duration(i) * time.Second))
	}

	if w, n := len(f.work.watchCalls()), len(f.work.tickCalls()); w != 0 || n != 0 {
		t.Errorf("감시 %d번 · 틱 %d번, want 0 · 0", w, n)
	}
}

// P 대조 오류의 읽기(계획 4.5 A1 결정 4) — NextDiscontinuitySequence 가 대조 불일치가 아닌 오류(끊김 표시를 셀 수 없는 행)를
// 돌려줘도 확인되지 않은 P 라 같은 처치다: 힌트 P.MSN 의 불일치로 읽고 까닭에 그 오류를 싣는다. 대조 불일치는 그대로 쓴다.
func TestPrevMismatchOfReadsEveryReconstructionError(t *testing.T) {
	m := &publish.PrevMismatchError{LoadHint: 7, Reason: "행 수 3 ≠ pc-seg-count 4"}
	if got := prevMismatchOf(m, 99); got != m {
		t.Errorf("불일치 = %+v, want 그대로", got)
	}
	other := errors.New("rewind: 빠지는 seq 9 의 회차 \"X\" 가 회차 목록에 없다")
	if got := prevMismatchOf(other, 5); got.LoadHint != 5 || got.Reason != other.Error() {
		t.Errorf("다른 오류 = %+v, want 힌트 5 · 까닭 = 오류 문구", got)
	}
}
