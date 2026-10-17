package main

// 발행 루프의 감시 · 적재 · Forget · 종료 배선(체크리스트 474 B-3 29–41 · B-1 7 · 계획 4.5 A2 결정 11 · A3 결정 1–7 · 판단 J46 ·
// J71 ⑵ · J72 · J74 · J78 ⑵ ⑶ · J79 · J85 ⑴ · J86 · J87 ⑵). 픽스처는 publish_loop_fixture_test.go 다.

import (
	"context"
	"errors"
	"log/slog"
	"slices"
	"sync/atomic"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/upload"
)

// watchEveryHold 는 감시를 holdTick 마다 내게 한다(감시 주기 = 보류 틱 — 감시 결과를 시각마다 보려는 시험).
func (f *publishLoop) watchEveryHold() { f.rewind.holdEvery = f.rewind.watchEvery }

// 감시 주기(체크리스트 474 B-3 29 · 판단 J85 ⑴ · 419 Q-2) — 감시는 발행 갈래를 도는 첫 holdTick(부팅 첫 감시)과 그 뒤 감시
// 주기 ÷ 보류 틱(30) 번째마다 한 번이다. 입력(드리프트 대조 입력)은 그 holdTick 의 Dirty 반영 뒤에 뜬다 — 같은 holdTick 에 머리를
// 민 ③ 원소가 입력에 실린다.
func TestWatchEveryPeriodWithProbesAfterDirty(t *testing.T) {
	f := newPublishLoop(t)
	f.rewind.holdEvery = time.Second // 30초 ÷ 1초 = 30 holdTick
	f.put("s1", liveLedger("s1", "S", 5, 3))

	f.hold(loopBase)
	f.settleWatch(loopBase, 1)
	for i := 1; i < 30; i++ {
		f.hold(loopBase.Add(time.Duration(i) * time.Second))
	}
	if n := len(f.work.watchCalls()); n != 1 {
		t.Fatalf("29 holdTick 동안 감시 = %d번, want 1(부팅 첫 감시뿐)", n)
	}
	f.uploaded("s1", "S", 4)
	f.hold(loopBase.Add(30 * time.Second))
	f.settleWatch(loopBase.Add(30*time.Second), 2)
	f.settle(loopBase.Add(30 * time.Second))

	w := f.work.watchCalls()
	if want := [][]index.WatchProbe{{{StreamID: "s1", NextSeq: 4}}, {{StreamID: "s1", NextSeq: 5}}}; len(w) != 2 ||
		!slices.Equal(w[0], want[0]) || !slices.Equal(w[1], want[1]) {
		t.Errorf("감시 입력 = %v, want %v — 둘째 감시는 같은 holdTick 의 ③ 4 반영 뒤 머리", w, want)
	}
}

// 느린 감시(체크리스트 474 A-5 · 판단 J80 ⑷ 의 프로세스 차선) — 감시가 도는 동안 다음 감시 차례가 오면 Lanes 가 병합하고, 결과를
// 받은 뒤 새 입력으로 한 번 더 낸다(버리지 않는다 · 겹쳐 돌지 않는다).
func TestMergedWatchRerunsOnceAfterSlowWatch(t *testing.T) {
	f := newPublishLoop(t)
	f.watchEveryHold()
	f.work.watchGate = make(chan struct{})
	gate := f.work.watchGate

	f.hold(loopBase)                       // 첫 감시 — 문에서 멈춘다
	f.hold(loopBase.Add(30 * time.Second)) // 다음 차례 — 병합
	f.work.mu.Lock()
	f.work.watchGate = nil
	f.work.mu.Unlock()
	close(gate)
	f.settleWatch(loopBase.Add(30*time.Second), 2)

	if n := len(f.work.watchCalls()); n != 2 {
		t.Errorf("감시 = %d번, want 2(병합된 차례는 끝난 뒤 한 번)", n)
	}
}

// 부팅(체크리스트 474 B-3 30 · 계획 4.5 A3 결정 6 · 뮤테이션 109 종단) — 첫 감시가 돌려준 live 회차 가운데 컷오프 있는 스트림만
// 적재하고(다음 holdTick 의 요구 발사) 적재 뒤 틱을 한 번 낸다. 부팅 때 live 회차가 없던 스트림은 다음 방송의 개시 push(뷰 없는
// 스트림 — 캐시가 스스로 적재를 연다)로 들어와 발행된다.
func TestBootWatchLoadsLiveCutoffStreamsThenPublishes(t *testing.T) {
	f := newPublishLoop(t)
	f.watchEveryHold()
	f.work.ledgers["s1"] = liveLedger("s1", "S", 3, 3)
	f.work.ledgers["s3"] = sessionLedger("s3", "N", 0, 1)
	f.work.watchFn = func([]index.WatchProbe) publish.WatchOutcome {
		return publish.WatchOutcome{RewindWatch: index.RewindWatch{Live: []index.WatchLive{
			{StreamID: "s1", SessionID: "S", InitUploaded: true, InheritAbsent: true, HasCutoff: true},
			{StreamID: "s2", SessionID: "S2", InitUploaded: true, InheritAbsent: true}, // 컷오프 없음 — 되감기를 주지 않는다
		}}}
	}

	f.hold(loopBase)
	f.settleWatch(loopBase, 1)
	f.hold(loopBase.Add(30 * time.Second))
	f.settleWatch(loopBase.Add(30*time.Second), 2)
	f.settle(loopBase.Add(30 * time.Second))

	if got := loadStreams(f.work.loadCalls()); !slices.Equal(got, []string{"s1"}) {
		t.Fatalf("부팅 적재 = %v, want [s1]", got)
	}
	if calls := f.work.tickCalls(); len(calls) != 1 || calls[0].in.Playlist.StreamID != "s1" {
		t.Fatalf("틱 = %+v, want s1 한 번(적재 뒤 틱)", calls)
	}

	f.cache.ApplyInsert("s3", 0, index.SeedResult{SessionOpened: true, SessionID: "N", DurationMS: 4000, TargetDuration: 6})
	f.hold(loopBase.Add(60 * time.Second))
	if j := f.running("s3"); j == nil || j.kind != jobLoad {
		t.Fatalf("개시 push 뒤 같은 holdTick 의 s3 차선 = %+v, want 적재", j)
	}
	f.settleWatch(loopBase.Add(60*time.Second), 3)
	f.settle(loopBase.Add(60 * time.Second))
	if calls := f.work.tickCalls(); len(calls) != 2 || calls[1].in.Playlist.StreamID != "s3" {
		t.Errorf("틱 = %+v, want s3 의 첫 목록", calls)
	}
}

// loadStreams 는 적재 입력의 스트림들이다(낸 차례).
func loadStreams(in []publish.LoadInput) []string {
	var out []string
	for _, l := range in {
		out = append(out, l.StreamID)
	}
	return out
}

// 첫 감시 실패(체크리스트 474 B-3 31 · 판단 J78 ⑵ ⑶) — 감시가 실패하면 ERROR rewind_job_failed(job=watch · first) 한 줄이고 부팅 목록은
// 다음 감시가 낸다(그 전에는 개시 · 주조 push 만 적재를 연다). 부팅 뒤의 감시 실패는 first=false 다.
func TestFirstWatchFailureDefersBootListToNextWatch(t *testing.T) {
	f := newPublishLoop(t)
	f.watchEveryHold()
	f.work.ledgers["s1"] = liveLedger("s1", "S", 3, 3)
	var n int
	f.work.watchFn = func([]index.WatchProbe) publish.WatchOutcome {
		n++
		if n%2 == 1 {
			return publish.WatchOutcome{Err: errors.New("index: 감시 시한")}
		}
		return publish.WatchOutcome{RewindWatch: index.RewindWatch{Live: []index.WatchLive{
			{StreamID: "s1", SessionID: "S", InitUploaded: true, InheritAbsent: true, HasCutoff: true},
		}}}
	}
	watch := func(at time.Duration) {
		t.Helper()
		f.hold(loopBase.Add(at))
		f.settleWatch(loopBase.Add(at), f.watched+1)
		f.settle(loopBase.Add(at))
	}

	watch(0)
	if a := f.logs.attrs("rewind_job_failed"); a["job"] != "watch" || a["first"] != true || len(f.work.loadCalls()) != 0 {
		t.Fatalf("첫 감시 실패 = %v · 적재 %d번, want job=watch · first=true · 적재 0", a, len(f.work.loadCalls()))
	}
	watch(30 * time.Second)
	watch(60 * time.Second) // 부팅 목록의 적재가 이 holdTick 에 나간다 · 이 감시는 실패
	if got := loadStreams(f.work.loadCalls()); !slices.Equal(got, []string{"s1"}) {
		t.Errorf("적재 = %v, want [s1] — 둘째 감시가 부팅 목록 몫을 했다", got)
	}
	if n := f.logs.countLevel(slog.LevelError, "rewind_job_failed"); n != 2 || f.logs.attrs("rewind_job_failed")["first"] != false {
		t.Errorf("감시 실패 = %d줄(마지막 %v), want 2 · 부팅 뒤는 first=false", n, f.logs.attrs("rewind_job_failed"))
	}
}

// 감시 실패의 처분(체크리스트 474 B-3 33 · 판단 J42 · 419 J46) — 실패한 감시는 (a) 대조 · (c) 판정 · 화해 · Forget 을 건너뛴다(세지도
// 풀지도 않는다): 부재가 이어지는 스트림의 (c) 상태가 그대로라 다음 성공 감시에 「사라짐」 줄이 없고, 그 사이 쉬게 된 스트림도
// 실패한 감시로는 버려지지 않는다(다음 성공 감시가 버린다).
func TestWatchFailureSkipsReconcileAbsentAndForget(t *testing.T) {
	f := newPublishLoop(t)
	f.watchEveryHold()
	f.put("s2", liveLedger("s2", "S", 1, 1))
	results := []publish.WatchOutcome{
		{RewindWatch: index.RewindWatch{Absent: []index.WatchAbsent{{StreamID: "a", LastWall: loopBase}}}},
		{Err: errors.New("index: 감시 시한")},
		{RewindWatch: index.RewindWatch{Absent: []index.WatchAbsent{{StreamID: "a", LastWall: loopBase}}}},
	}
	f.work.watchFn = func([]index.WatchProbe) publish.WatchOutcome {
		out := results[0]
		results = results[1:]
		return out
	}

	f.hold(loopBase)
	f.settleWatch(loopBase, 1)
	f.cache.ApplySessionEnding("s2", "S") // 이제 쉰다 — 버릴 수 있다
	f.hold(loopBase.Add(time.Hour))
	f.settleWatch(loopBase.Add(time.Hour), 2)
	if n := f.logs.count("rewind_cutoff_absent"); n != 1 || !slices.Contains(f.cache.Streams(), "s2") {
		t.Fatalf("실패한 감시 뒤 (c) %d줄 · 스트림 %v, want 1(들어옴 WARN 뿐) · s2 그대로", n, f.cache.Streams())
	}
	f.hold(loopBase.Add(2 * time.Hour))
	f.settleWatch(loopBase.Add(2*time.Hour), 3)

	if n := f.logs.count("rewind_cutoff_absent"); n != 1 {
		t.Errorf("(c) = %d줄, want 1 — 부재가 이어지는 동안 줄이 없다(실패한 감시는 세지도 풀지도 않는다)", n)
	}
	if slices.Contains(f.cache.Streams(), "s2") {
		t.Error("성공한 감시 뒤에도 s2 가 남았다")
	}
}

// 드리프트 대조 배선(watchDone 의 「성공」 행 · 설계 4.1 (a) · 판단 J39) — 감시 결과의 드리프트 재료를 캐시 AuditDrift 에 넘긴다.
// 같은 스트림이 연속 두 감시에서 어긋나면 적재를 요구하고 다음 holdTick 이 그 적재를 낸다. 적재가 끝나면 cache_drift_repaired
// 한 줄이다. 커밋 8 뮤테이션 정식 실행의 생존 뮤턴트(드리프트 대조 제거)가 드러낸 빈자리다 — 루프 층 시험이 드리프트 재료를
// 싣지 않았다.
func TestWatchDriftTwiceDemandsRepairLoad(t *testing.T) {
	f := newPublishLoop(t)
	f.watchEveryHold()
	f.put("s1", liveLedger("s1", "S", 3, 3))
	f.work.ledgers["s1"] = liveLedger("s1", "S", 3, 3)
	f.work.watchFn = func([]index.WatchProbe) publish.WatchOutcome {
		// DB 컷오프(1)가 캐시 컷오프(0)와 다르다.
		return publish.WatchOutcome{RewindWatch: index.RewindWatch{Drift: []index.WatchDrift{
			{StreamID: "s1", HasCutoff: true, CutoffSeq: 1},
		}}}
	}

	f.hold(loopBase)
	f.settleWatch(loopBase, 1)
	if n := len(f.work.loadCalls()); n != 0 {
		t.Fatalf("첫 어긋남 뒤 적재 %d번, want 0 — 연속 두 번째 어긋남에서 요구한다", n)
	}
	f.hold(loopBase.Add(30 * time.Second))
	f.settleWatch(loopBase.Add(30*time.Second), 2)
	f.hold(loopBase.Add(31 * time.Second)) // 요구 발사
	f.settle(loopBase.Add(31 * time.Second))

	if loads := f.work.loadCalls(); len(loads) != 1 || loads[0].StreamID != "s1" {
		t.Errorf("적재 = %+v, want s1 한 번", loads)
	}
	if n := f.logs.count("cache_drift_repaired"); n != 1 {
		t.Errorf("cache_drift_repaired %d줄, want 1", n)
	}
}

// 적재 뒤 틱(체크리스트 474 B-3 34 · 계획 4.5 A3 결정 2 〔r42〕 · 뮤테이션 122) — 적재가 끝난 스트림에는 새 원소 없이도 틱을 한 번
// 낸다(적재 중에 Ack 한 원소는 발행을 부르지 못했다).
func TestPublishResumesAfterLoadWithoutNewEvent(t *testing.T) {
	f := newPublishLoop(t)
	f.work.ledgers["s1"] = liveLedger("s1", "S", 3, 3)
	f.cache.DemandLoad("s1", nil)

	f.hold(loopBase)
	f.settle(loopBase)

	if calls := f.work.tickCalls(); len(calls) != 1 || !slices.Equal(rowSeqs(calls[0]), seqRange(0, 3)) {
		t.Errorf("틱 = %+v, want 적재 뒤 한 번(행 0..3)", calls)
	}
}

// 미룬 적재(체크리스트 474 A-5 · J79 ⑴) — 틱이 도는 동안 요구된 적재는 BeginLoad 를 마친 입력으로 미뤄 두고, 틱 결과를 받은 뒤
// 낸다(resume). 그 사이 더 늦은 요구가 오면 그 입력(새 토큰)으로 바꾼다 — 옛 토큰의 적재는 나가지 않는다. 틱이 도는 동안에는
// 적재 작업이 없다(차선 하나에 작업 하나).
func TestLoadDemandedWhileTickRunsStartsAfterTick(t *testing.T) {
	f := newPublishLoop(t)
	f.put("s1", liveLedger("s1", "S", 3, 2))
	f.work.ledgers["s1"] = liveLedger("s1", "S", 3, 3)
	f.work.mu.Lock()
	f.work.gate = make(chan struct{})
	f.work.mu.Unlock()
	f.uploaded("s1", "S", 3)
	f.hold(loopBase) // 틱이 문에서 멈춘다

	f.cache.DemandLoad("s1", nil)
	f.hold(loopBase.Add(time.Second)) // 요구 발사 — 차선이 돌아 미룬다
	f.cache.DemandLoad("s1", nil)
	f.hold(loopBase.Add(2 * time.Second)) // 더 늦은 요구 — 미룬 입력을 새 토큰으로 바꾼다
	if job := f.running("s1"); job == nil || job.kind != jobTick || len(f.work.loadCalls()) != 0 {
		t.Fatalf("틱이 도는 동안 차선 작업 %+v · 적재 %d번, want 틱 · 적재 0", job, len(f.work.loadCalls()))
	}
	pending := f.rewind.streams["s1"].pendingLoad
	f.work.open(&f.work.gate)
	f.settle(loopBase.Add(2 * time.Second))

	loads := f.work.loadCalls()
	if pending == nil || len(loads) != 1 || loads[0].Token != pending.Token {
		t.Fatalf("미룬 적재 %+v · 낸 적재 %+v, want 틱 결과 뒤 한 번 · 마지막 요구의 토큰", pending, loads)
	}
	if f.cache.Loading("s1") || len(f.work.tickCalls()) != 2 {
		t.Errorf("적재 중 %t · 틱 %d번, want false · 2(첫 틱 · 적재 뒤 틱)", f.cache.Loading("s1"), len(f.work.tickCalls()))
	}
}

// 적재 실패(체크리스트 474 B-3 35 · 판단 J46 · J79 ⑵) — 적재 작업이 실패하면 CompleteLoad 로 넘기지 않아 적재 중을 유지하고(그 스트림
// 틱 0), 감시 주기(30초) 뒤에 다시 요구해 낸다. 시도마다 ERROR rewind_ledger_load_degraded(reason=load_failed) 한 줄이다.
func TestLoadFailureKeepsLoadingAndRetriesAfterWatchPeriod(t *testing.T) {
	f := newPublishLoop(t)
	f.work.ledgers["s1"] = liveLedger("s1", "S", 3, 3)
	f.work.loadFn = func(in publish.LoadInput) publish.LoadOutcome {
		if len(f.work.loadCalls()) <= 2 {
			return publish.LoadOutcome{Token: in.Token, Err: errors.New("index: 적재 시한")}
		}
		return publish.LoadOutcome{Token: in.Token, RewindLedger: liveLedger("s1", "S", 3, 3)}
	}
	f.cache.DemandLoad("s1", nil)
	step := func(at time.Duration, wantLoads int) {
		t.Helper()
		f.hold(loopBase.Add(at))
		f.settle(loopBase.Add(at))
		if n := len(f.work.loadCalls()); n != wantLoads {
			t.Fatalf("t+%v 적재 = %d번, want %d", at, n, wantLoads)
		}
	}

	step(0, 1)
	if !f.cache.Loading("s1") || f.logs.countLevel(slog.LevelError, "rewind_ledger_load_degraded") != 1 {
		t.Fatalf("실패 뒤 적재 중 %t · load_failed %d줄, want true · 1", f.cache.Loading("s1"),
			f.logs.countLevel(slog.LevelError, "rewind_ledger_load_degraded"))
	}
	step(29*time.Second, 1)
	step(30*time.Second, 2)
	step(59*time.Second, 2)
	step(60*time.Second, 3)
	if n := f.logs.countLevel(slog.LevelError, "rewind_ledger_load_degraded"); n != 2 || f.cache.Loading("s1") {
		t.Errorf("load_failed %d줄 · 적재 중 %t, want 2 · false(셋째 적재가 섰다)", n, f.cache.Loading("s1"))
	}
	if n := len(f.work.tickCalls()); n != 1 {
		t.Errorf("틱 = %d번, want 1(적재가 선 뒤 한 번)", n)
	}
}

// P2′ 결과 모름(체크리스트 474 B-3 36 · 312 J3 · 계획 4.5 A2 결정 2 · 뮤테이션 129) — 틱 결과가 적재를 요구하면(서버는 계승 취소를
// 커밋했을 수 있다) 그 결과를 받은 완료 case 에서 적재를 내고(G-1 창 — publishDone), 적재가 DB 값(계승 없음 · base 0)을 실은 뒤의
// 첫 목록에는 접두가 없고 DISC-SEQ 가 0 이다.
func TestP2PrimeUnknownResultDemandsLoad(t *testing.T) {
	f := newPublishLoop(t)
	l := index.RewindLedger{HasCutoff: true, Sessions: []index.RewindSession{
		{SessionID: "P", State: "ending", InitUploaded: true, TargetDuration: 6, DiscontinuityBase: 5},
		{SessionID: "S", State: "live", InitUploaded: true, TargetDuration: 6, DiscontinuityBase: 5, InheritsSession: "P", MinSeq: 4},
	}}
	for seq := int64(0); seq <= 6; seq++ {
		sid := "P"
		if seq >= 4 {
			sid = "S"
		}
		l.Rows = append(l.Rows, segRow("s1", sid, seq, true))
	}
	f.put("s1", l)
	revoked := l
	revoked.Sessions = slices.Clone(l.Sessions)
	revoked.Sessions[1].InheritsSession, revoked.Sessions[1].DiscontinuityBase = "", 0
	f.work.ledgers["s1"] = revoked
	f.work.tickFn = func(st publish.State, in publish.TickInput) publish.Outcome {
		if len(f.work.tickCalls()) == 1 {
			return publish.Outcome{State: st, DemandLoad: true} // revoke_unknown
		}
		return publishedOutcome(st, in, loopBase)
	}
	f.uploaded("s1", "S", 6)
	f.hold(loopBase)
	f.settle(loopBase)
	f.hold(loopBase.Add(time.Second))
	f.settle(loopBase.Add(time.Second))

	calls := f.work.tickCalls()
	if len(calls) != 2 || len(f.work.loadCalls()) != 1 {
		t.Fatalf("틱 %d번 · 적재 %d번, want 2 · 1", len(calls), len(f.work.loadCalls()))
	}
	if !slices.Equal(rowSeqs(calls[1]), seqRange(4, 6)) || calls[1].in.DiscontinuitySequence != 0 {
		t.Errorf("적재 뒤 첫 목록 = 행 %v · DISC-SEQ %d, want 4..6 · 0", rowSeqs(calls[1]), calls[1].in.DiscontinuitySequence)
	}
}

// 쉬는 뷰 버리기(체크리스트 474 B-3 37 · 계획 4.5 A3 결정 5 · 뮤테이션 87 · 판단 J71 ⑵ · J72) — 성공한 감시마다 캐시가 든 스트림을
// 돌며 live 회차가 없고 마지막 행 끝이 ReconnectWindow(300초)보다 오래면 버린다. 마지막 행 끝은 인덱서 커서 꼬리 끝이고, 커서가
// 없으면 적재 완료 시각이다. 부정 표식 스트림도 버리고, 버린 스트림의 루프 칸도 지운다. live 회차가 있는 스트림은 남는다.
func TestIdleStreamViewForgottenAfterReconnectWindow(t *testing.T) {
	f := newPublishLoop(t)
	f.watchEveryHold()
	f.boot("s1") // 인덱서 커서 — 꼬리 끝 = wall(4초) + 4초
	tailEnd := f.wall(8 * time.Second)
	ended := liveLedger("s1", "S", 1, 1)
	ended.Sessions[0].State = "ending"
	f.put("s1", ended)
	f.put("s3", liveLedger("s3", "L", 1, 1))
	f.work.ledgers["s2"] = index.RewindLedger{} // 컷오프 없음 — 부정 표식
	f.cache.DemandLoad("s2", nil)

	f.hold(tailEnd) // s2 적재 완료 시각 = tailEnd
	f.settle(tailEnd)
	f.settleWatch(tailEnd, 1)
	for _, tt := range []struct {
		at   time.Duration
		want []string
	}{
		{300 * time.Second, []string{"s1", "s2", "s3"}},
		{301 * time.Second, []string{"s3"}},
	} {
		f.hold(tailEnd.Add(tt.at))
		f.settleWatch(tailEnd.Add(tt.at), f.watched+1)
		if got := f.cache.Streams(); !slices.Equal(got, tt.want) {
			t.Fatalf("끝 + %v 감시 뒤 스트림 = %v, want %v", tt.at, got, tt.want)
		}
	}
	if _, ok := f.rewind.streams["s2"]; ok {
		t.Error("버린 s2 의 루프 칸이 남았다")
	}
}

// 뷰 없는 스트림의 push(체크리스트 474 B-3 38 · 39 · 계획 4.5 A3 결정 6 ⑤ · 뮤테이션 109 · 119 종단 · 보안 미확인 4) — Forget 된(또는
// 처음 보는) 스트림의 다음 방송 개시 push 와 비개시 행의 주조 push 는 캐시가 스스로 적재를 열어 요구 목록에 올리고, 루프는 그
// holdTick 에 적재 작업을 낸다. 적재 뒤 틱이 그 방송의 첫 목록을 낸다.
func TestViewlessPushOpensLoadWithinOneHoldTickAndPublishes(t *testing.T) {
	tests := []struct {
		name string
		res  index.SeedResult
	}{
		{"다음_방송_개시", index.SeedResult{SessionOpened: true, SessionID: "N", DurationMS: 4000, TargetDuration: 6}},
		{"비개시_행_주조", index.SeedResult{Seeded: true, SessionID: "N", DurationMS: 4000}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f := newPublishLoop(t)
			f.work.ledgers["s1"] = sessionLedger("s1", "N", 7, 8)

			f.cache.ApplyInsert("s1", 8, tt.res)
			f.hold(loopBase)
			if n := len(f.work.loadCalls()); n != 1 && f.running("s1") == nil {
				t.Fatalf("같은 holdTick 의 적재 = %d번, want 1", n)
			}
			f.settle(loopBase)

			if calls := f.work.tickCalls(); len(calls) != 1 || !slices.Equal(rowSeqs(calls[0]), seqRange(7, 8)) {
				t.Errorf("틱 = %+v, want 적재 뒤 첫 목록(행 7..8)", calls)
			}
		})
	}
}

// 짧은 송출 순환의 회차 항목(체크리스트 474 B-3 40 · 판단 J87 ⑵ · 445 C5-9 · 6a 보안 미확인 3) — 재접속마다 새 회차가 열려도 발행
// 성공마다 Trim 이 남은 행 · 소유 회차 · 그 접두 어느 것도 가리키지 않는 회차를 버려, 옛 회차 항목이 쌓이지 않는다(30분 조각 —
// 창은 두 행). 발행 실패 변형(보안 참고 6) — Trim 은 발행 성공 뒤에만 불리므로 발행이 막힌 동안에는 옛 회차 항목이 남고, 다음
// 성공에서 한꺼번에 버린다.
func TestTrimBoundsSessionEntriesAcrossShortBroadcasts(t *testing.T) {
	cycle := func(f *publishLoop, i int64, at time.Time) {
		t := f.t
		t.Helper()
		sid := "R" + time.Duration(i).String()
		f.cache.ApplyInsert("s1", i, index.SeedResult{SessionOpened: true, SessionID: sid, DurationMS: 30 * 60 * 1000,
			TargetDuration: 6, PlaybackPDT: loopBase.Add(time.Duration(i) * time.Hour),
			PlaybackS3Key: "dvr/s1/seg/x.m4s"})
		f.dirty.mark(upload.DirtyEvent{Kind: upload.DirtyUploaded, StreamID: "s1", Axis: index.AxisInit, SessionID: sid})
		f.uploaded("s1", sid, i)
		f.hold(at)
		f.settle(at)
	}
	known := func(f *publishLoop, sid string) bool { _, ok := f.cache.Session("s1", sid); return ok }
	const cycles = 20

	t.Run("발행_성공", func(t *testing.T) {
		f := newPublishLoop(t)
		f.put("s1", index.RewindLedger{HasCutoff: true})
		for i := int64(0); i < cycles; i++ {
			cycle(f, i, loopBase.Add(time.Duration(i)*time.Second))
		}
		if known(f, "R0s") || !known(f, "R19ns") {
			t.Errorf("순환 %d번 뒤 첫 회차 항목 %t · 마지막 회차 %t, want false · true", cycles, known(f, "R0s"), known(f, "R19ns"))
		}
	})
	t.Run("발행_실패", func(t *testing.T) {
		f := newPublishLoop(t)
		f.put("s1", index.RewindLedger{HasCutoff: true})
		f.work.tickFn = func(st publish.State, _ publish.TickInput) publish.Outcome { return publish.Outcome{State: st} }
		for i := int64(0); i < cycles; i++ {
			cycle(f, i, loopBase.Add(time.Duration(i)*time.Second))
		}
		if !known(f, "R0s") {
			t.Fatal("발행이 막힌 동안 첫 회차 항목이 버려졌다 — Trim 은 발행 성공 뒤에만 불린다")
		}
		f.work.mu.Lock()
		f.work.tickFn = nil
		f.work.mu.Unlock()
		cycle(f, cycles, loopBase.Add(cycles*time.Second))
		if known(f, "R0s") {
			t.Error("다음 발행 성공 뒤에도 첫 회차 항목이 남았다")
		}
	})
}

// 4.1 전이의 업로더 메모리(체크리스트 474 B-3 41 · 판단 J86) — EndLive 가 1행이면(그 회차를 ending 으로 보냈다) 업로더의 그 회차
// 메모리를 한 번 지우고, 0행이면(live 회차 없음) 부르지 않는다.
func TestOfflineEndLiveForgetsUploaderSession(t *testing.T) {
	f := newOfflineLoop(t, nil)
	ups := &fakeUploader{}
	f.rewind.forgetSession = ups.forget
	all := []string{"s1", "s2", "s3"}
	f.boot(all...)
	f.ends.result = func(_ context.Context, s string) (string, error) {
		if s == "s2" {
			return "", nil
		}
		return "S-" + s, nil
	}
	f.judge(f.at(0), all...)
	if !f.judge(f.at(10*time.Second), all...) {
		t.Fatal("점검을 발사하지 않았다 — 준비가 어긋났다")
	}
	f.judge(f.at(11*time.Second), all...)

	if got := ups.forgets(); !slices.Equal(got, []string{"S-s1", "S-s3"}) {
		t.Errorf("ForgetSession = %v, want [S-s1 S-s3]", got)
	}
}

// 종료 순서(체크리스트 474 B-1 7 · A-3 3 · 판단 J74 · 계획 커밋 순서 7 〔r52〕) — 차선 ctx 를 끊고 → 떠 있는 작업 고루틴이 끝나기를
// 기다린 뒤 → fence 를 반납한다. 반납은 종료 신호로 끝난 ctx 가 아니라 취소를 떼어 낸 ctx 로 한 번 나가고, 그 오류를 돌려준다.
// 발행 몫이 없으면 끊기만 한다.
func TestStopPublishingCancelsWaitsThenReleases(t *testing.T) {
	t.Run("발행_몫", func(t *testing.T) {
		lanes := publish.NewLanes()
		laneCtx, cancelLanes := context.WithCancel(t.Context())
		var jobEnded atomic.Bool
		started := make(chan struct{})
		lanes.Start(laneCtx, "s1", func(ctx context.Context) publish.Result {
			close(started)
			<-ctx.Done()
			time.Sleep(50 * time.Millisecond) // 끊긴 뒤에도 잠시 도는 작업(늦은 문장)
			jobEnded.Store(true)
			return publish.Result{Kind: publish.KindPublish}
		})
		<-started
		runCtx, stop := context.WithCancel(t.Context())
		stop() // 종료 신호로 끝난 run ctx
		var releases []error
		errReleased := errors.New("반납 실패")

		err := stopPublishing(runCtx, cancelLanes, lanes, func(ctx context.Context) error {
			if !jobEnded.Load() {
				releases = append(releases, errors.New("작업이 끝나기 전에 반납했다"))
			}
			releases = append(releases, ctx.Err())
			return errReleased
		})

		if !errors.Is(err, errReleased) {
			t.Errorf("stopPublishing = %v, want 반납 오류", err)
		}
		if len(releases) != 1 || releases[0] != nil {
			t.Errorf("반납 = %v, want 끊기지 않은 ctx 로 한 번(작업이 끝난 뒤)", releases)
		}
		if laneCtx.Err() == nil {
			t.Error("차선 ctx 가 끊기지 않았다")
		}
	})
	t.Run("발행_몫_없음", func(t *testing.T) {
		ctx, cancel := context.WithCancel(t.Context())
		if err := stopPublishing(t.Context(), cancel, nil, nil); err != nil || ctx.Err() == nil {
			t.Errorf("stopPublishing = %v · 끊김 %v, want nil · 끊김", err, ctx.Err())
		}
	})
}

// 차선이 도는 스트림은 버리지 않는다(체크리스트 474 A-5 3 · 루프의 in-flight 표) — 틱이 도는 동안 그 회차가 끝나고 창이 지나도
// 감시의 Forget 은 그 스트림을 건너뛴다: 결과를 받을 칸(도는 작업의 종류 · 회차)이 남아야 늦게 온 결과를 반영할 수 있다. 차선이
// 빈 뒤의 감시가 버린다.
func TestForgetSkipsStreamWithBusyLane(t *testing.T) {
	f := newPublishLoop(t)
	f.watchEveryHold()
	f.put("s1", liveLedger("s1", "S", 3, 2))
	f.hold(loopBase) // 부팅 첫 감시
	f.settleWatch(loopBase, 1)
	f.work.mu.Lock()
	f.work.gate = make(chan struct{})
	f.work.mu.Unlock()
	f.publishOnceStart("s1", "S", 3, loopBase.Add(time.Second)) // 틱이 문에서 멈춘다
	f.settleWatch(loopBase.Add(time.Second), 2)
	f.cache.ApplySessionEnding("s1", "S")

	late := loopBase.Add(time.Hour) // 적재 완료 시각(영값)에서 창이 한참 지났다
	f.hold(late)
	f.settleWatch(late, 3)
	if !slices.Contains(f.cache.Streams(), "s1") || f.running("s1") == nil {
		t.Fatalf("차선이 도는 동안 스트림 %v · 차선 %+v, want s1 그대로 · 도는 틱", f.cache.Streams(), f.running("s1"))
	}
	f.work.open(&f.work.gate)
	f.settle(late)
	f.hold(late.Add(time.Second))
	f.settleWatch(late.Add(time.Second), 4)
	if slices.Contains(f.cache.Streams(), "s1") {
		t.Error("차선이 빈 뒤의 감시가 s1 을 버리지 않았다")
	}
}

// 잃은 init 결과의 감시 화해 — 루프 몫(체크리스트 474 B-3 32 의 루프 절반 · 계획 4.5 A2 결정 11 · 뮤테이션 117 · 118 종단의 캐시 쪽).
// 서버는 init 확정과 계승 해제를 커밋했는데 워커가 결과를 잃어(MarkTimeout → AlreadySame) Dirty 원소가 없으면 캐시는 init 미확정이라
// 틱이 없다. 감시 결과(DB: init 확정 · 계승 없음)를 받은 루프가 화해해(cache_drift_repaired kind=init_uploaded revoked=true 한 줄) 다음
// 원소의 첫 목록에는 접두가 없고 DISC-SEQ 가 0 이다. 서버 쪽 CAS · AlreadySame 는 PG 몫이다(B-3 32 의 나머지).
func TestWatchReconcileRestoresLostInitWithRevocation(t *testing.T) {
	f := newPublishLoop(t)
	f.watchEveryHold()
	f.rewind.booted = true // 부팅 목록과 가른다
	l := index.RewindLedger{HasCutoff: true, Sessions: []index.RewindSession{
		{SessionID: "P", State: "ending", InitUploaded: true, TargetDuration: 6, DiscontinuityBase: 5},
		{SessionID: "S", State: "live", TargetDuration: 6, DiscontinuityBase: 5, InheritsSession: "P", MinSeq: 4},
	}}
	for seq := int64(0); seq <= 6; seq++ {
		sid := "P"
		if seq >= 4 {
			sid = "S"
		}
		l.Rows = append(l.Rows, segRow("s1", sid, seq, true))
	}
	f.put("s1", l)
	f.work.watchFn = func([]index.WatchProbe) publish.WatchOutcome {
		return publish.WatchOutcome{RewindWatch: index.RewindWatch{Live: []index.WatchLive{
			{StreamID: "s1", SessionID: "S", InitUploaded: true, InheritAbsent: true, HasCutoff: true},
		}}}
	}

	f.uploaded("s1", "S", 6)
	f.hold(loopBase) // init 미확정 — 틱 0 · 감시
	f.settleWatch(loopBase, 1)
	f.settle(loopBase)
	if n := len(f.work.tickCalls()); n != 0 {
		t.Fatalf("화해 전 틱 = %d번, want 0", n)
	}
	if a := f.logs.attrs("cache_drift_repaired"); a["kind"] != "init_uploaded" || a["revoked"] != true || a["session"] != "S" {
		t.Fatalf("cache_drift_repaired = %v, want kind=init_uploaded · revoked=true · session=S", a)
	}
	f.uploaded("s1", "S", 6)
	f.hold(loopBase.Add(30 * time.Second))
	f.settleWatch(loopBase.Add(30*time.Second), 2)
	f.settle(loopBase.Add(30 * time.Second))

	calls := f.work.tickCalls()
	if len(calls) != 1 || !slices.Equal(rowSeqs(calls[0]), seqRange(4, 6)) || calls[0].in.DiscontinuitySequence != 0 {
		t.Errorf("화해 뒤 첫 틱 = %+v, want 행 4..6(접두 없음) · DISC-SEQ 0", calls)
	}
}
