package upload

import (
	"runtime"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// 회차 메모리의 복원 몫(POK-196 PDT 계획 4절 c10b · 설계 r6 2.10)을 잰다 — 끊김 줄 · 복원 줄 · 복원 링크 · 깨움 · 세션 단위
// 백오프. 네트워크 · 장부는 없다(sessionMemory 만).

func newMemoryForTest() *sessionMemory {
	return newSessionMemory(DefaultOptions(nil, ""))
}

// unknownRequest 는 인덱서가 사슬을 모를 때 보내는 ③ 요청이다 — 도장 칸이 비어 있다.
func unknownRequest(seq int64) index.UploadTarget {
	return index.UploadTarget{StreamID: "demo", Axis: index.AxisPlayback, Seq: seq, SessionID: "S-1", PosUnknown: true}
}

// 위치 모름 요청은 그 seq 부터 표를 끊는다 — 앞 줄의 보정값이 뒤 조각에 새지 않는다. 잡는 결함: 끊지 않으면 재기동 · 판독
// 포기 뒤 조각을 스위퍼가 낡은 보정값으로 만든다(겹침 · 틈).
func TestUnknownRequestBreaksOffsetTable(t *testing.T) {
	m := newMemoryForTest()
	now := time.Unix(1_000, 0)
	m.recordLive(index.UploadTarget{SessionID: "S-1", Seq: 10, StitchOffset: stitch}, now)

	m.recordLive(unknownRequest(12), now)

	if off, ok := m.offsetAt("S-1", 11); !ok || off != stitch {
		t.Errorf("seq 11 = (%v, %v), want (5s, 참) — 끊김 앞은 그대로", off, ok)
	}
	for _, seq := range []int64{12, 13} {
		if _, ok := m.offsetAt("S-1", seq); ok {
			t.Errorf("seq %d 에 보정값이 있다 — 끊김 줄 뒤는 표가 없다", seq)
		}
	}
}

// 복원 줄은 표가 모르는 seq 에만 적힌다 — 실시간이 이미 적은 줄은 덮지 않는다(실시간 값이 정본이다). 고정 링크는 고정 줄도
// 적는다. 같은 seq 의 끊김 줄은 복원 줄이 이긴다.
func TestRestoredLinesFillOnlyUnknownSeqs(t *testing.T) {
	m := newMemoryForTest()
	now := time.Unix(1_000, 0)
	m.recordLive(index.UploadTarget{SessionID: "S-1", Seq: 10, StitchOffset: stitch}, now)
	m.recordLive(unknownRequest(12), now)

	m.recordRestored("S-1", []playback.Link{
		{Seq: 11, Offset: time.Second},
		{Seq: 12, Offset: 2 * time.Second},
		{Seq: 13, Offset: 3 * time.Second, Pos: 40 * time.Second, Pinned: true},
	}, now)

	want := map[int64]time.Duration{11: stitch, 12: 2 * time.Second, 13: 3 * time.Second, 14: 3 * time.Second}
	for seq, off := range want {
		if got, ok := m.offsetAt("S-1", seq); !ok || got != off {
			t.Errorf("seq %d = (%v, %v), want (%v, 참)", seq, got, ok, off)
		}
	}
	if pos, ok := m.pinnedPos("S-1", 13); !ok || pos != 40*time.Second {
		t.Errorf("고정 줄 seq 13 = (%v, %v), want (40s, 참)", pos, ok)
	}
}

// 복원 링크는 회차마다 마지막 하나를 든다 — 인덱서가 비차단으로 읽는다.
func TestRestoredLinkIsLatestPerSession(t *testing.T) {
	m := newMemoryForTest()
	if _, ok := m.restoredLink("S-1"); ok {
		t.Fatal("복원 전인데 링크가 있다")
	}

	_, _, gen := m.tableKeys("S-1")
	m.setRestored("S-1", gen, restoredLink{link: playback.Link{Seq: 7, SessionID: "S-1"}, bytes: 64})
	m.setRestored("S-1", gen, restoredLink{link: playback.Link{Seq: 9, SessionID: "S-1"}, bytes: 64})

	if got, ok := m.restoredLink("S-1"); !ok || got.link.Seq != 9 {
		t.Errorf("링크 = (%+v, %v), want seq 9", got, ok)
	}
}

// 위치 모름 요청은 그 회차의 복원을 깨운다 — 깨움은 회차마다 하나로 합쳐지고, 실패한 회차는 백오프가 끝나기 전에는 꺼내지
// 않는다(SweepEvery · 2^n · 상한 BackoffMax). 성공하면 백오프가 풀린다.
func TestRestoreWakeMergesPerSessionAndBacksOff(t *testing.T) {
	m := newMemoryForTest()
	now := time.Unix(1_000, 0)
	m.recordLive(unknownRequest(12), now)
	m.recordLive(unknownRequest(13), now)
	select {
	case <-m.wakeCh():
	default:
		t.Fatal("깨움 신호가 없다")
	}

	got := m.takeWakes(now)
	if len(got) != 1 || got[0] != (restoreWake{streamID: "demo", sessionID: "S-1"}) {
		t.Fatalf("깨움 = %+v, want S-1 하나", got)
	}
	m.restoreFailed("S-1", now)
	m.wake("demo", "S-1")
	if got := m.takeWakes(now.Add(defaultSweepEvery - time.Second)); len(got) != 0 {
		t.Errorf("백오프 안 깨움 = %+v, want 없음", got)
	}
	m.wake("demo", "S-1")
	if got := m.takeWakes(now.Add(defaultSweepEvery)); len(got) != 1 {
		t.Errorf("백오프 뒤 깨움 = %+v, want S-1", got)
	}
	m.restoreFailed("S-1", now)
	m.restoreSucceeded("S-1")
	m.wake("demo", "S-1")
	if got := m.takeWakes(now); len(got) != 1 {
		t.Errorf("성공 뒤 깨움 = %+v, want 바로 S-1", got)
	}
}

// 같은 입력(구간 · 닻 · 대조 행 · 행 크기들)의 불일치 판정은 다시 하지 않는다(설계 r6 2.10 「끝맺음」 — r3 cc-P2). 입력이
// 바뀌면(꼬리 교정으로 크기가 바뀜) 다시 판정한다.
func TestMismatchMemoryKeyedByInputs(t *testing.T) {
	m := newMemoryForTest()
	span := holeSpan{from: 10, to: 12, anchor: 9, check: 13}
	m.recordMismatch("S-1", span, "64,64,64")

	if !m.knownMismatch("S-1", span, "64,64,64") {
		t.Error("같은 입력인데 다시 판정한다")
	}
	if m.knownMismatch("S-1", span, "64,80,64") {
		t.Error("입력이 바뀌었는데 지난 판정을 쓴다")
	}
}

// 수명이 지난 회차는 복원 실패 이력도 함께 치운다 — 남기면 그 항목이 맵에서 영영 안 지워진다(c10b cc M1 · cx M).
func TestEvictDropsRestoreFailureHistory(t *testing.T) {
	m := newMemoryForTest()
	now := time.Unix(1_000, 0)
	m.recordLive(unknownRequest(12), now)
	m.restoreFailed("S-1", now)
	m.forget("S-1")

	m.evict(now.Add(defaultSessionTTL + time.Second))

	if n := len(m.byID); n != 0 {
		t.Errorf("수명 뒤 회차 항목 %d개, want 0", n)
	}
}

// 긴 구멍의 복원 줄을 덩어리(restoredChunk)로 나눠 쓰는 동안에도 같은 회차의 읽기는 「모름」 이거나 복원이 정한 값이다 — 앞
// 덩어리 끝 줄의 보정값이 아직 안 쓴 뒤 seq 에 「아는 값」 으로 보이지 않는다(PR 종결 r1 cc H — 재현 입력: 앞 줄 seq 0 · 끊김
// 줄 seq 1 · 구멍 링크 21,600개 · 링크마다 다른 보정값). 잡는 결함: 끊김 줄을 첫 덩어리에서 걷고 seq 오름차순으로 쓰면, 그
// 사이에 스위퍼 ③ 작업이 뒤 seq 를 offsetAt 해 앞 덩어리 끝 줄의 보정값으로 만들고 CAS 로 굳힌다.
func TestRestoredChunksNeverExposeEarlierChunkOffset(t *testing.T) {
	const (
		n      = 21_600
		rounds = 10
	)
	now := time.Unix(1_000, 0)
	links := make([]playback.Link, 0, n)
	for s := int64(1); s <= n; s++ {
		links = append(links, playback.Link{Seq: s, Offset: time.Duration(s) * time.Millisecond})
	}
	var wrong, known atomic.Int64
	for range rounds {
		m := newMemoryForTest()
		m.recordLive(index.UploadTarget{SessionID: "S-1", Seq: 0}, now)
		m.recordLive(unknownRequest(1), now)
		var (
			stop atomic.Bool
			wg   sync.WaitGroup
		)
		for g := range 4 {
			wg.Add(1)
			go func() {
				defer wg.Done()
				for !stop.Load() {
					for s := int64(1500 + g); s <= n; s += 97 {
						if off, ok := m.offsetAt("S-1", s); ok {
							known.Add(1)
							if off != time.Duration(s)*time.Millisecond {
								wrong.Add(1)
							}
						}
					}
				}
			}()
		}
		m.recordRestored("S-1", links, now)
		stop.Store(true)
		wg.Wait()
	}
	if wrong.Load() > 0 {
		t.Fatalf("복원 중 틀린 보정값을 %d번 봤다(아는 값 관측 %d번) — 아직 안 쓴 seq 는 「모름」 이어야 한다", wrong.Load(), known.Load())
	}
}

// 덩어리를 쓰는 사이 스위퍼가 수명이 지난 표를 치워도(evict) 큰 seq 가 작은 seq 덩어리의 보정값으로 「아는 값」 이 되지 않는다(PR
// 종결 r2 cx H — 재현 입력: 덩어리를 쓰는 사이 수명이 지나는 표 · 앞 줄 seq 0 · 끊김 줄 seq 1 · 구멍 링크 21,600개 · 링크마다 다른
// 보정값. 복원이 이미 수명이 지난 표에 쓰면 그 표의 수명을 지금부터 다시 센다(r3 cc L) — 치움이 끼려면 쓰는 사이에 지나야 한다). 잡는
// 결함: 큰 seq 덩어리를 쓴 뒤 evict 가 표 · 끊김 줄을 지우고 작은 seq 덩어리가 새 표에 적히면, 큰 seq 의 「seq 이하 마지막 줄」 이
// 작은 덩어리 끝 줄이 되어 스위퍼 ③ 작업이 그 값으로 도장을 굳힌다. 치움 뒤 몫이 지워지는 회차와, 보류 작업이 남아 몫은 그대로
// 두고 표만 치우는 회차를 둘 다 본다.
func TestRestoredChunksSurviveTableEvictionBetweenChunks(t *testing.T) {
	for _, keepEntry := range []bool{false, true} {
		name := "몫 지움"
		if keepEntry {
			name = "몫 남음"
		}
		t.Run(name, func(t *testing.T) { checkRestoreWithEviction(t, keepEntry) })
	}
}

// checkRestoreWithEviction 은 복원 덩어리 사이에 표 치움을 끼워 틀린 「아는 값」 이 남는지 본다. keepEntry 면 치우는 시각에 보류
// 작업이 살아 있어 회차 몫은 맵에 남는다.
func checkRestoreWithEviction(t *testing.T, keepEntry bool) {
	const (
		n      = 21_600
		rounds = 20
	)
	before := time.Unix(1_000, 0)
	restoreAt := before.Add(defaultSessionTTL - time.Millisecond)
	now := before.Add(defaultSessionTTL + time.Millisecond)
	links := make([]playback.Link, 0, n)
	for s := int64(1); s <= n; s++ {
		links = append(links, playback.Link{Seq: s, Offset: time.Duration(s) * time.Millisecond})
	}
	var wrong, interleaved int
	for range rounds {
		m := newMemoryForTest()
		m.recordLive(index.UploadTarget{SessionID: "S-1", Seq: 0}, before)
		m.recordLive(unknownRequest(1), before)
		if keepEntry {
			m.hold(index.UploadTarget{SessionID: "S-1", Seq: n + 1}, now)
		}
		done := make(chan struct{})
		go func() {
			defer close(done)
			m.recordRestored("S-1", links, restoreAt)
		}()
		if evictBetweenChunks(m, n, now, done) {
			interleaved++
		}
		<-done
		if _, ok := m.byID["S-1"]; keepEntry && !ok {
			t.Fatal("보류 작업이 남은 회차의 몫이 치움에 지워졌다(전제)")
		}
		for s := int64(n); s >= 1; s -= 97 {
			if off, ok := m.offsetAt("S-1", s); ok && off != time.Duration(s)*time.Millisecond {
				wrong++
				t.Logf("seq %d = %v, want %v 또는 모름", s, off, time.Duration(s)*time.Millisecond)
				break
			}
		}
	}
	if interleaved == 0 {
		t.Fatalf("복원 덩어리 사이에 표 치움을 한 번도 끼우지 못했다(%d회) — 시험이 경합을 만들지 못한다", rounds)
	}
	if wrong > 0 {
		t.Fatalf("표 치움이 낀 복원 %d/%d회(끼운 횟수 %d)에서 틀린 보정값이 「아는 값」 으로 남았다", wrong, rounds, interleaved)
	}
}

// evictBetweenChunks 는 큰 seq 덩어리만 적힌 순간을 기다려 표를 치운다. 그 순간을 보기 전에 복원이 끝나면 거짓이다.
func evictBetweenChunks(m *sessionMemory, n int64, now time.Time, done <-chan struct{}) bool {
	for {
		m.mu.Lock()
		var high, low bool
		if e, ok := m.byID["S-1"]; ok {
			_, high = e.offsets[n]
			_, low = e.offsets[1]
		}
		m.mu.Unlock()
		if high && !low {
			m.evict(now)
			return true
		}
		select {
		case <-done:
			return false
		default:
			runtime.Gosched()
		}
	}
}

// 복원이 표 줄을 다 쓴 뒤 · 복원 링크를 게시하기 전에 수명이 표를 치우면(evict) 그 링크를 게시하지 않는다(PR 종결 r3 cx H — 재현
// 입력: 0 uploaded → 1–3 pending · 큐 포화로 구멍 행 작업 재투입이 거부됨 · 표 줄을 다 쓴 순간에 치움). 잡는 결함: 치움이 표 줄을
// 지웠는데 commit 이 마지막 링크 3 을 게시하면, 다음 복원이 그 링크에서 잇느라 1–2 를 건너뛰어 seq 1 은 보정값도 작업도 없이 남는다.
func TestRestoredLinkNotPublishedAfterTableEviction(t *testing.T) {
	b := newRestoreBench(t)
	b.contiguous(0, 1, 600*time.Second, up, pending, pending, pending)
	b.uploadAt(b.liveChain(), 0)
	seen := b.u.now().Add(-defaultSessionTTL + time.Minute) // 복원 동안은 살아 있고 치울 때는 지난 표
	b.u.sessions.recordLive(index.UploadTarget{SessionID: benchSession, Seq: 0}, seen)
	b.u.sessions.recordLive(unknownRequest(1), seen)
	g := holeGroup{anchor: new(int64(0)), first: 1, last: 3, seqs: map[int64]bool{1: true, 2: true, 3: true}}
	run, err := b.u.newGroupRun(restoreWake{streamID: benchStream, sessionID: benchSession}, g, 35, b.u.log, true)
	if err != nil {
		t.Fatal(err)
	}
	if mm, err := run.fold(run.anchorLink, run.start); mm != nil || err != nil {
		t.Fatalf("접기 = (%v, %v), want 대조 통과", mm, err)
	}
	for i := range cap(b.u.queue) {
		if got := b.u.enqueue(axisTarget(index.AxisPlayback, "other", int64(i), "S-other"), OriginLive); got != EnqueueAdmitted {
			t.Fatalf("큐 채우기 = %v", got)
		}
	}

	// 게이트 잠금이 commit 을 첫 재투입에 세운다 — 표 줄은 다 썼고 복원 링크는 아직이다.
	b.u.gate.mu.Lock()
	done := make(chan struct{})
	go func() {
		defer close(done)
		run.commit(run.anchorLink)
	}()
	for deadline := time.Now().Add(2 * time.Second); ; time.Sleep(time.Microsecond) {
		if _, ok := b.offset(1); ok {
			break
		}
		if time.Now().After(deadline) {
			b.u.gate.mu.Unlock()
			t.Fatal("표 줄이 2초 안에 적히지 않았다")
		}
	}
	b.u.sessions.evict(b.u.now().Add(2 * time.Minute))
	if _, ok := b.offset(1); ok {
		b.u.gate.mu.Unlock()
		t.Fatal("치움 뒤에도 seq 1 줄이 남았다(전제)")
	}
	b.u.gate.mu.Unlock()
	<-done

	if rl, ok := b.u.sessions.restoredLink(benchSession); ok {
		t.Errorf("치운 표 위의 복원 링크 seq %d 가 게시됐다, want 없음", rl.link.Seq)
	}
	b.queued() // 다른 회차 작업 — 구멍 행 재투입은 큐 포화로 거부됐다
	b.restore()
	if _, ok := b.offset(1); !ok {
		t.Errorf("다음 복원 뒤 seq 1 보정값 없음 (%s)", b.cap.dump())
	}
	if jobs := b.queued(); len(jobs) != 3 || jobs[0].Seq != 1 {
		t.Errorf("다음 복원이 다시 넣은 작업 = %+v, want seq 1–3", jobs)
	}
}

// 이미 수명이 지난 표(스위퍼가 아직 안 치움)에 쓴 복원 줄은 다음 tick 의 치움에 지워지지 않는다 — 복원이 쓰면 표의 수명을 지금부터
// 센다(PR 종결 r3 cc L). 잡는 결함: 수명 기준을 옛 실시간 요청 시각에 두면 tick 마다 복원 줄이 지워지고 복원이 다시 돈다.
func TestRestoredLinesOnExpiredTableSurviveNextTick(t *testing.T) {
	m := newMemoryForTest()
	before := time.Unix(1_000, 0)
	now := before.Add(defaultSessionTTL + time.Millisecond)
	m.recordLive(index.UploadTarget{SessionID: "S-1", Seq: 0}, before)
	m.recordLive(unknownRequest(1), before)

	m.recordRestored("S-1", []playback.Link{{Seq: 1, Offset: time.Second}}, now)
	m.evict(now.Add(defaultSweepEvery))

	if off, ok := m.offsetAt("S-1", 1); !ok || off != time.Second {
		t.Errorf("다음 tick 뒤 seq 1 = (%v, %v), want (1s, 참)", off, ok)
	}
}

// 표가 없는 몫(표를 만든 적 없음 · 수명이 표를 치움)의 복원 백오프는 tick 이 지우지 않는다(PR 종결 r3 보안 L1). 실패 이력은 다음
// 시도 시각부터 수명이 지나면 치운다 — 몫이 맵에 영영 남지 않는다. 잡는 결함: 영값 · 옛 수명 기준을 tick 마다 「만료」 로 쳐서 실패
// 횟수를 지우면 장애 동안 복원이 지수 백오프 없이 30초마다 되풀이된다.
func TestRestoreBackoffSurvivesTickWithoutTable(t *testing.T) {
	before := time.Unix(1_000, 0)
	now := before.Add(defaultSessionTTL + time.Millisecond)
	for name, setup := range map[string]func(m *sessionMemory){
		"표를 만든 적 없음": func(*sessionMemory) {},
		"수명이 표를 치움": func(m *sessionMemory) {
			m.recordLive(unknownRequest(1), before)
			m.hold(index.UploadTarget{SessionID: "S-1", Seq: 2}, now) // 보류 작업이 몫을 남긴다
			m.evict(now)
		},
	} {
		t.Run(name, func(t *testing.T) {
			m := newMemoryForTest()
			setup(m)
			m.restoreFailed("S-1", now)
			m.evict(now.Add(time.Second))

			next := m.restoreFailed("S-1", now.Add(time.Second))

			if want := now.Add(time.Second + 2*defaultSweepEvery); !next.Equal(want) {
				t.Errorf("두 번째 실패 뒤 다음 시도 = %v, want %v(백오프 두 배)", next, want)
			}
			m.forget("S-1")
			m.evict(next.Add(defaultSessionTTL))
			if _, ok := m.byID["S-1"]; ok {
				t.Error("다음 시도 시각부터 수명이 지났는데 몫이 남았다")
			}
		})
	}
}
