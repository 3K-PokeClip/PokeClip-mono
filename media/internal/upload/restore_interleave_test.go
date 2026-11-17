package upload

import (
	"errors"
	"fmt"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// 복원과 스위퍼(evict) · 인덱서(recordLive)의 경합을 부류로 본다(PR 종결 r4 — r1 · r2 · r3 · r4 가 매번 다른 경계에서 났다). 복원이
// 회차 메모리를 놓는 모든 자리(restorePoint)마다 한 번씩, 끼움 넷을 모두 끼운다 — 폴링이 아니라 손잡이(sessionMemory.pause)라
// 코어 수 · -race 와 무관하게 결정적이다.
//
//	불변식 I0  치운 표보다 오래 사는 복원 산출이 없다 — 링크 · 구멍 줄 · 불일치 기억
//	불변식 I1  표가 「안다」 고 답하는 값은 언제나 실시간 값이다(덩어리 쓰기 — TestRestoredChunksInterleavedWithSweeper)
//	불변식 I2  치움이 낀 복원 뒤, 끼움 없는 다음 깨움이 모든 구멍 seq 에 보정값을 적고 다시 넣는다
//	불변식 I3  치울 표가 없는 tick · 치움 없는 실시간 요청은 복원을 버리지 않는다 — 같은 차례에 끝난다
//	신호 S1   복원 기준부터 끝 대조까지 세대가 바뀌었으면 INFO chain_restore_discarded 정확히 한 줄 · chain_restored 0, 아니면
//	          discarded 0 · chain_restored 1(불일치 갈래는 0) — 모든 사례 · 자리 · 끼움에서(PR 종결 r6)
//	신호 S2   reason 은 처음 거절된 대조의 단계다(expectedDiscardReason)
//	신호 S3   requeued 는 큐에 남은 그 회차 구멍 작업 수다 — 그 작업은 자기 도장을 싣고 있다(README 「③ 사슬 복원」)
//
// 앞 회차 재현이 어느 하위인가:
//
//	r4 cx H   기억 닻 / 1-anchored · 2-folded · 3-adopted / 치움 셋 — 지난 복원 링크를 닻으로 고른 뒤 치움
//	r3 cx H   장부 닻 / 5-written · 6-requeued / 몫 치움 · 표만 치움 — 표 쓰기 뒤 · 게시 전 치움(옛 TestRestoredLinkNotPublished
//	          AfterTableEviction · 「표만 치움」 은 r4 cc M 의 keepEntry 갈래)
//	r2 cx H   TestRestoredChunksInterleavedWithSweeper / 1-chunk · 2-chunk / 몫 치움 · 표만 치움(옛 TestRestoredChunksSurvive
//	          TableEvictionBetweenChunks)
//	r1 cc H   TestRestoredChunksInterleavedWithSweeper / 관찰
//	r5 cx H   큐 여유 / 5-written · 6-requeued / 치움 셋 — 게시 거절도 버림 신호 · 남은 재투입 작업
//	r5 cc M   표 없음 · 모든 사례 / 실시간만 — 실시간 요청이 세대를 바꾸면(변이 V9) 방송 중 복원이 매번 버려진다
//	r6 cx H   빈 링크 / 쓴 뒤 자리 · 게시 뒤 자리 / 치움 셋 — 게시할 링크가 없어도 버림 신호(끝 대조)
//	          · 불일치 / 모든 자리 / 치움 셋 — 불일치 기억 거절도 버림 신호(r6 cc L2 같은 자리)
//	          · 링크 있는 사례 / *-published / 치움 셋 — 게시 뒤 · 끝 전 만료도 버림
//	r7 cc L1  불일치 / *-settled / 치움 셋 — 불일치 기억을 적은 뒤 · 끝 전 만료도 버림(끝 대조를 commit 갈래로 좁히는 변이 V14)
//	r7 보안 L1 치움 셋이 낀 모든 하위 — 버린 복원은 진전이 아니다(다음 시도 시각이 선다 · 변이 V13)

// cutKind 는 복원 한 자리에 끼우는 일이다.
type cutKind int

const (
	cutEvict   cutKind = iota // 수명이 표를 치운다(보류 작업 없음)
	cutHeld                   // 보류 작업이 살아 있어 몫은 남기고 표만 치운다
	cutRebuild                // 치운 뒤 실시간 요청이 새 표를 만든다
	cutTick                   // 치울 표가 없는 tick(세대 그대로)
	cutLive                   // 치움 없이 실시간 요청 하나(세대 그대로)
)

func (c cutKind) String() string {
	return [...]string{"몫 치움", "표만 치움", "재생성", "tick", "실시간만"}[c]
}

// evicts 는 끼움이 표를 치워 세대를 바꾸는가다(표가 있는 사례에서).
func (c cutKind) evicts() bool { return c == cutEvict || c == cutHeld || c == cutRebuild }

func (p restorePoint) String() string {
	return [...]string{"based", "anchored", "folded", "adopted", "chunk", "written", "requeued", "published", "settling",
		"settled"}[p]
}

// interleaveCase 는 끼움 시험의 사례 하나다. setup 은 시험대를 차려 끼울 복원 바로 앞까지 가고, 되살릴 구멍 seq 를 돌려준다.
type interleaveCase struct {
	name     string
	setup    func(t *testing.T) (*restoreBench, []int64)
	mismatch bool // 구멍 뒤 대조에 지는 사례 — I2 대신 불일치 기억을 본다
	noTable  bool // 표 없는 회차(재기동 뒤) — 어떤 끼움도 세대를 바꾸지 않는다(I3)
	noLinks  bool // 꼬리에서 기다려 링크가 0 개인 사례
	// grow 는 다음 깨움 전에 할 일이다(빈 링크 — 꼬리를 다 쓴 ② 로 키워 다음 깨움이 접게 한다). nil 이면 없다.
	grow func(b *restoreBench)
}

// interleaveTableSeen 은 복원 동안은 살아 있고 끼움 시각(+2분)에는 수명이 지난 표의 기준 시각이다.
func interleaveTableSeen(b *restoreBench) time.Time {
	return b.u.now().Add(-defaultSessionTTL + time.Minute)
}

// liveTable 은 그 회차 표를 앞 줄 seq 0 · 끊김 줄 seq 1 로 둔다(구멍 1 부터 위치 모름).
func (b *restoreBench) liveTable() {
	seen := interleaveTableSeen(b)
	b.u.sessions.recordLive(index.UploadTarget{SessionID: benchSession, Seq: 0}, seen)
	b.u.sessions.recordLive(unknownRequest(1), seen)
}

// saturate 는 큐를 다른 회차 작업으로 채운다 — 구멍 행 재투입이 거부돼 구멍이 표 줄에만 기댄다.
func (b *restoreBench) saturate() {
	for i := range cap(b.u.queue) {
		if got := b.u.enqueue(axisTarget(index.AxisPlayback, "other", int64(i), "S-other"), OriginLive); got != EnqueueAdmitted {
			b.t.Fatalf("큐 채우기 = %v", got)
		}
	}
}

var interleaveCases = []interleaveCase{
	{name: "기억 닻", setup: func(t *testing.T) (*restoreBench, []int64) {
		// 0 up → 1–3 pending · 큐 포화 · 지난 복원이 링크 3 을 게시했다 → 4 가 더 들어온다(r4 cx 재현 입력).
		b := newRestoreBench(t)
		b.contiguous(0, 1, 600*time.Second, up, pending, pending, pending)
		b.uploadAt(b.liveChain(), 0)
		b.liveTable()
		b.saturate()
		b.restore()
		if rl, ok := b.u.sessions.restoredLink(benchSession); !ok || rl.link.Seq != 3 {
			t.Fatalf("전제: 지난 복원 링크 seq 3, got (%+v, %v)", rl, ok)
		}
		b.contiguous(4, 5, 600*time.Second+4*b.step(), pending)
		return b, []int64{1, 2, 3, 4}
	}},
	{name: "장부 닻", setup: func(t *testing.T) (*restoreBench, []int64) {
		// 0 up → 1–3 pending · 큐 포화 · 지난 복원 링크 없음(r3 cx 재현 입력).
		b := newRestoreBench(t)
		b.contiguous(0, 1, 600*time.Second, up, pending, pending, pending)
		b.uploadAt(b.liveChain(), 0)
		b.liveTable()
		b.saturate()
		return b, []int64{1, 2, 3}
	}},
	{name: "큐 여유", setup: func(t *testing.T) (*restoreBench, []int64) {
		// 장부 닻과 같되 큐가 비어 구멍 행 재투입이 들어간다 — 게시 단계에서 버린 복원이 큐에 남기는 작업을 본다(r5 cx 재현 입력).
		b := newRestoreBench(t)
		b.contiguous(0, 1, 600*time.Second, up, pending, pending, pending)
		b.uploadAt(b.liveChain(), 0)
		b.liveTable()
		return b, []int64{1, 2, 3}
	}},
	{name: "빈 링크", noLinks: true, setup: func(t *testing.T) (*restoreBench, []int64) {
		// 0 up → 1 pending(꼬리 · ② 가 반만 써짐) — 복원이 꼬리에서 기다려 링크가 0 개다(r6 cx 재현 입력).
		b := newRestoreBench(t)
		b.contiguous(0, 1, 600*time.Second, up)
		half := withMtxi(t, b.raw, recA, 2, 600*time.Second+b.step())
		b.add(benchRow{seq: 1, session: benchSession, src: half[:len(half)/2], archive: pending})
		b.uploadAt(b.liveChain(1), 0)
		b.liveTable()
		return b, []int64{1}
	}, grow: func(b *restoreBench) {
		b.add(benchRow{seq: 1, session: benchSession, src: withMtxi(b.t, b.raw, recA, 2, 600*time.Second+b.step()),
			archive: pending})
	}},
	{name: "표 없음", noTable: true, setup: func(t *testing.T) (*restoreBench, []int64) {
		// 재기동 뒤 — 회차 몫이 없다. 복원 동안 tick 이 빈 몫을 지우면 게시할 곳이 없어진다.
		b := newRestoreBench(t)
		b.contiguous(0, 1, 600*time.Second, up, pending, pending, pending)
		b.uploadAt(b.liveChain(), 0)
		return b, []int64{1, 2, 3}
	}},
	{name: "불일치", mismatch: true, setup: func(t *testing.T) (*restoreBench, []int64) {
		// 0 up → 1 pending → 2 up(20ms 어긋난 객체) — 구멍 뒤 대조에 진다.
		b := newRestoreBench(t)
		b.contiguous(0, 1, 600*time.Second, up, pending, up)
		live := b.liveChain()
		b.uploadAt(live, 0)
		b.setPlaybackObject(2, live[2].Pos+20*time.Millisecond)
		b.liveTable()
		return b, []int64{1}
	}},
}

// cut 은 끼움 c 를 지금 한다. 시각은 시험대 시계 + 2분(표의 수명이 지났다), tick 은 지금(수명 안).
func (b *restoreBench) cut(c cutKind) {
	later := b.u.now().Add(2 * time.Minute)
	switch c {
	case cutEvict:
		b.u.sessions.evict(later)
	case cutHeld:
		b.u.sessions.hold(index.UploadTarget{SessionID: benchSession, Seq: 99}, later)
		b.u.sessions.evict(later)
	case cutRebuild:
		b.u.sessions.evict(later)
		b.u.sessions.recordLive(index.UploadTarget{SessionID: benchSession, Seq: 50, StitchOffset: time.Second}, later)
	case cutTick:
		b.u.sessions.evict(b.u.now())
	case cutLive:
		b.u.sessions.recordLive(index.UploadTarget{SessionID: benchSession, Seq: 50, StitchOffset: time.Second}, b.u.now())
	}
}

// restorePoints 는 끼움 없이 그 사례의 복원이 지나는 자리 차례다.
func restorePoints(t *testing.T, tc interleaveCase) []restorePoint {
	b, _ := tc.setup(t)
	var seen []restorePoint
	b.u.sessions.pause = func(p restorePoint) { seen = append(seen, p) }
	b.restore()
	b.u.sessions.pause = nil
	return seen
}

func TestRestoreInterleavedWithSweeper(t *testing.T) {
	for _, tc := range interleaveCases {
		points := restorePoints(t, tc)
		if len(points) == 0 {
			t.Fatalf("%s: 복원이 자리를 하나도 지나지 않았다", tc.name)
		}
		for k, p := range points {
			for _, c := range []cutKind{cutEvict, cutHeld, cutRebuild, cutTick, cutLive} {
				t.Run(fmt.Sprintf("%s/%d-%v/%v", tc.name, k, p, c), func(t *testing.T) {
					checkInterleaved(t, tc, k, p, c)
				})
			}
		}
	}
}

// checkInterleaved 는 복원의 k 번째 자리(p)에 끼움 c 를 끼우고 불변식 · 신호를 본다.
func checkInterleaved(t *testing.T, tc interleaveCase, k int, p restorePoint, c cutKind) {
	b, holes := tc.setup(t)
	b.cap.reset() // 앞 복원(기억 닻)의 신호 — 이 복원의 것만 센다
	n := 0
	b.u.sessions.pause = func(restorePoint) {
		if n == k {
			b.cut(c)
		}
		n++
	}
	b.restore()
	b.u.sessions.pause = nil
	if n <= k {
		t.Fatalf("자리 %d 에 닿지 못했다(지난 자리 %d개)", k, n)
	}
	// 표 없는 회차는 치울 표가 없어 어떤 끼움도 세대를 바꾸지 않는다.
	changed := c.evicts() && !tc.noTable
	checkDiscardSignal(t, b, tc, p, changed, holes)
	switch {
	case !changed && tc.mismatch:
		if !b.u.sessions.knownMismatch(benchSession, interleaveMismatchSpan, b.ledgerFingerprint()) {
			t.Errorf("%v 이 낀 불일치 판정을 기억하지 않았다 — 치울 표가 없는데 버렸다 (%s)", c, b.cap.dump())
		}
		return
	case !changed && tc.noLinks:
		return // 꼬리에서 기다린 복원은 낼 링크 · 줄이 없다 — 신호(S1)만 본다
	case !changed:
		checkNotDiscarded(t, b, holes)
		return
	}
	checkNothingOutlivesTable(t, b, holes, tc.mismatch)
	if tc.grow != nil {
		tc.grow(b)
	}
	b.restore()
	checkNextWakeRestores(t, b, holes, tc.mismatch)
}

// checkDiscardSignal 은 S1 · S2 · S3 이다 — 세대가 바뀌었는가(changed)만으로 기대 신호가 정해진다. 큐는 여기서 비운다(다음 깨움의
// 재투입이 들어갈 자리).
func checkDiscardSignal(t *testing.T, b *restoreBench, tc interleaveCase, p restorePoint, changed bool, holes []int64) {
	t.Helper()
	discarded, restored := b.cap.find("chain_restore_discarded"), b.cap.count("chain_restored")
	full := b.saturated() // 큐를 비우기 전에 — 차 있었으면 재투입이 거부됐다
	var holeJobs int
	for _, j := range b.queued() {
		if j.SessionID == benchSession {
			holeJobs++
		}
	}
	if !changed {
		wantRestored := 1
		if tc.mismatch {
			wantRestored = 0
		}
		if len(discarded) != 0 || restored != wantRestored {
			t.Errorf("세대가 그대로인 복원: discarded %d줄 · chain_restored %d줄, want 0 · %d (%s)", len(discarded), restored,
				wantRestored, b.cap.dump())
		}
		return
	}
	if len(discarded) != 1 || restored != 0 {
		// Fatal 이 아니다 — 다음 깨움의 I2(구멍이 끝내 남는가)까지 봐야 버림을 놓친 결함의 피해가 드러난다.
		t.Errorf("치움이 낀 복원: discarded %d줄 · chain_restored %d줄, want 1 · 0 (%s)", len(discarded), restored, b.cap.dump())
		return
	}
	// 채택 없이 버린 복원은 진전이 아니다 — 다음 시도를 스위퍼 간격 뒤로 미룬다(restoreIdle). 진전으로 세면 백오프가 풀려 위치 모름
	// 요청마다 같은 구멍을 다시 접는다(r7 보안 L1). 이 시험의 사례에는 채택이 없다.
	if next := b.restoreNext(); !next.After(b.u.now()) {
		t.Errorf("버린 복원 뒤 다음 시도 시각이 없다(%v) — 진전으로 셌다", next)
	}
	if got, want := discarded[0].attrs["reason"], expectedDiscardReason(tc, p); got != want {
		t.Errorf("discarded reason = %v, want %s", got, want)
	}
	wantJobs := 0
	if requeuedBy(tc, p) && !full {
		wantJobs = len(holes)
	}
	if got := discarded[0].attrs["requeued"]; got != int64(wantJobs) {
		t.Errorf("discarded requeued = %v, want %d", got, wantJobs)
	}
	if holeJobs != wantJobs {
		t.Errorf("버린 복원이 큐에 남긴 구멍 작업 %d개, want %d", holeJobs, wantJobs)
	}
}

// expectedDiscardReason 은 자리 p 의 치움을 처음 거절하는 대조의 단계다(S2).
func expectedDiscardReason(tc interleaveCase, p restorePoint) string {
	switch {
	case tc.mismatch && p == pointSettled:
		return "table_evicted_before_end" // 불일치 기억은 적었다 — 끝 대조가 잡는다
	case tc.mismatch:
		return "table_evicted_while_settling" // 기억을 적기 전 자리 — 그 대조가 처음 거절한다
	case p == pointPublished, tc.noLinks && (p == pointWritten || p == pointRequeued):
		return "table_evicted_before_end" // 게시할 링크가 없거나 게시를 마쳤다 — 끝 대조가 잡는다
	case p == pointWritten || p == pointRequeued:
		return "table_evicted_before_publish"
	}
	return "table_evicted_while_writing"
}

// requeuedBy 는 자리 p 에 오기 전에 구멍 행 작업을 다시 넣었는가다(표 줄을 다 쓴 뒤 자리 · 낼 링크가 있는 commit 갈래).
func requeuedBy(tc interleaveCase, p restorePoint) bool {
	return !tc.mismatch && !tc.noLinks && (p == pointWritten || p == pointRequeued || p == pointPublished)
}

// restoreNext 는 시험 회차의 다음 복원 시도 시각이다(영값이면 바로 다시 깬다).
func (b *restoreBench) restoreNext() time.Time {
	m := b.u.sessions
	m.mu.Lock()
	defer m.mu.Unlock()
	if e, ok := m.byID[benchSession]; ok {
		return e.restoreNext
	}
	return time.Time{}
}

// saturated 는 큐가 다른 회차 작업으로 차 있는가다(saturate 를 불렀다).
func (b *restoreBench) saturated() bool { return len(b.u.queue) == cap(b.u.queue) }

// checkNothingOutlivesTable 은 I0 이다.
func checkNothingOutlivesTable(t *testing.T, b *restoreBench, holes []int64, mismatch bool) {
	t.Helper()
	if rl, ok := b.u.sessions.restoredLink(benchSession); ok {
		t.Errorf("치운 표 뒤 복원 링크 seq %d 가 남았다", rl.link.Seq)
	}
	for _, s := range holes {
		if off, ok := b.offset(s); ok {
			t.Errorf("치운 표 뒤 seq %d 보정값 %v 가 남았다", s, off)
		}
	}
	if mismatch && b.u.sessions.knownMismatch(benchSession, interleaveMismatchSpan, b.ledgerFingerprint()) {
		t.Error("치운 표 뒤 불일치 기억이 남았다")
	}
}

// checkNextWakeRestores 는 I2 다 — 불일치 사례면 다음 깨움이 다시 판정해 기억한다.
func checkNextWakeRestores(t *testing.T, b *restoreBench, holes []int64, mismatch bool) {
	t.Helper()
	if mismatch {
		if !b.u.sessions.knownMismatch(benchSession, interleaveMismatchSpan, b.ledgerFingerprint()) {
			t.Errorf("다음 깨움 뒤 불일치 기억 없음 (%s)", b.cap.dump())
		}
		return
	}
	live := b.liveChain()
	for _, s := range holes {
		if off, ok := b.offset(s); !ok || off != live[s].Offset {
			t.Errorf("다음 깨움 뒤 seq %d = (%v, %v), want 실시간 %v", s, off, ok, live[s].Offset)
		}
	}
	jobs := b.queued()
	if len(jobs) != len(holes) || jobs[0].Seq != holes[0] {
		t.Errorf("다음 깨움이 다시 넣은 작업 = %+v, want seq %v", jobs, holes)
	}
}

// checkNotDiscarded 는 I3 이다 — 같은 차례에 마지막 구멍까지 링크를 게시하고 줄을 적었다.
func checkNotDiscarded(t *testing.T, b *restoreBench, holes []int64) {
	t.Helper()
	last := holes[len(holes)-1]
	if rl, ok := b.u.sessions.restoredLink(benchSession); !ok || rl.link.Seq != last {
		t.Errorf("tick 이 낀 복원의 링크 = (%d, %v), want seq %d — 치울 표가 없는데 버렸다 (%s)", rl.link.Seq, ok, last, b.cap.dump())
	}
	live := b.liveChain()
	for _, s := range holes {
		if off, ok := b.offset(s); !ok || off != live[s].Offset {
			t.Errorf("tick 이 낀 복원 뒤 seq %d = (%v, %v), want 실시간 %v", s, off, ok, live[s].Offset)
		}
	}
}

// interleaveMismatchSpan 은 「불일치」 사례의 구멍 무리다(구멍 1 · 닻 0 · 대조 2).
var interleaveMismatchSpan = holeSpan{from: 1, to: 1, anchor: 0, check: 2}

// ledgerFingerprint 는 장부 전 행의 크기 지문이다 — 「불일치」 사례는 장부 구간이 전 행이라 groupRun.fingerprint 와 같다.
func (b *restoreBench) ledgerFingerprint() string {
	b.ledger.mu.Lock()
	defer b.ledger.mu.Unlock()
	parts := []string{}
	for _, s := range b.ledger.seqsLocked() {
		parts = append(parts, strconv.FormatInt(*b.ledger.rows[s].Bytes, 10))
	}
	return strings.Join(parts, ",")
}

// 덩어리 쓰기의 끼움(메모리층): 링크 3,072개(덩어리 셋) · 앞 줄 seq 0 · 끊김 줄 seq 1. 덩어리를 쓰기 전 자리마다 끼움 넷 · 관찰
// 하나를 끼운다. 옛 TestRestoredChunksSurviveTableEvictionBetweenChunks(r2)는 Gosched 폴링으로 틈을 잡아 -cpu=1 이면 한 번도 못
// 끼웠다 — 이 시험은 손잡이라 코어 수와 무관하다.
func TestRestoredChunksInterleavedWithSweeper(t *testing.T) {
	const n = 3 * restoredChunk
	links := make([]playback.Link, 0, n)
	for s := int64(1); s <= n; s++ {
		links = append(links, playback.Link{Seq: s, Offset: time.Duration(s) * time.Millisecond})
	}
	for k := range 3 {
		for _, c := range []string{"몫 치움", "표만 치움", "재생성", "tick", "관찰"} {
			t.Run(fmt.Sprintf("%d-chunk/%s", k, c), func(t *testing.T) { checkChunkInterleaved(t, links, k, c) })
		}
	}
}

// checkChunkInterleaved 는 k 번째 덩어리를 쓰기 전에 끼움 c 를 끼우고 I0 · I1 을 본다.
func checkChunkInterleaved(t *testing.T, links []playback.Link, k int, c string) {
	before := time.Unix(1_000, 0)
	restoreAt := before.Add(defaultSessionTTL - time.Millisecond) // 복원 때는 살아 있고
	cutAt := before.Add(defaultSessionTTL + time.Millisecond)     // 끼울 때는 수명이 지난 표
	m := newMemoryForTest()
	m.recordLive(index.UploadTarget{SessionID: "S-1", Seq: 0}, before)
	m.recordLive(unknownRequest(1), before)
	n, chunks := int64(len(links)), 0
	m.pause = func(p restorePoint) {
		if p != pointChunk {
			return
		}
		if chunks == k {
			cutChunk(t, m, c, n, restoreAt, cutAt)
		}
		chunks++
	}
	_, ok := restoreLinks(m, "S-1", links, restoreAt)
	m.pause = nil
	if chunks <= k {
		t.Fatalf("덩어리 자리 %d 에 닿지 못했다(지난 자리 %d개)", k, chunks)
	}
	if want := c == "tick" || c == "관찰"; ok != want {
		t.Errorf("복원 쓰기 = %v, want %v", ok, want)
	}
	for s := int64(1); s <= n; s += 7 {
		off, known := m.offsetAt("S-1", s)
		switch {
		case known && off != time.Duration(s)*time.Millisecond:
			t.Fatalf("seq %d = %v, want %v 또는 모름 — 틀린 「아는 값」", s, off, time.Duration(s)*time.Millisecond)
		case known && !ok:
			t.Fatalf("치운 표 뒤 seq %d 보정값 %v 가 남았다", s, off)
		case !known && ok:
			t.Fatalf("다 쓴 복원 뒤 seq %d 가 모름이다", s)
		}
	}
	if off, known := m.offsetAt("S-1", n+10); c == "재생성" && (!known || off != time.Second) {
		t.Errorf("재생성한 실시간 줄 = (%v, %v), want 1s", off, known)
	}
}

// cutChunk 는 덩어리 사이 끼움이다 — 「관찰」 은 치우지 않고 그 순간의 표를 읽어 I1 만 본다(r1).
func cutChunk(t *testing.T, m *sessionMemory, c string, n int64, restoreAt, cutAt time.Time) {
	t.Helper()
	switch c {
	case "몫 치움":
		m.evict(cutAt)
	case "표만 치움":
		m.hold(index.UploadTarget{SessionID: "S-1", Seq: n + 1}, cutAt)
		m.evict(cutAt)
	case "재생성":
		m.evict(cutAt)
		m.recordLive(index.UploadTarget{SessionID: "S-1", Seq: n + 10, StitchOffset: time.Second}, cutAt)
	case "tick":
		m.evict(restoreAt)
	case "관찰":
		for s := int64(1); s <= n; s += 7 {
			if off, ok := m.offsetAt("S-1", s); ok && off != time.Duration(s)*time.Millisecond {
				t.Errorf("덩어리 사이 seq %d = %v, want %v 또는 모름", s, off, time.Duration(s)*time.Millisecond)
				return
			}
		}
	}
}

// 복원이 붙든 회차 몫은 복원이 어떻게 끝나든(성공 · 오류 · 정지 · 패닉 · 버림) 놓인다 — 다 놓이면 수명 뒤 몫이 지워져 회차 몫이 0
// 이다. 잡는 결함: 놓지 않은 길이 있으면 빈 몫이 회차마다 하나씩 영영 남는다.
func TestRestoreReleasesSessionOnEveryExit(t *testing.T) {
	exits := []struct {
		name    string
		arrange func(b *restoreBench)
	}{
		{"성공", func(*restoreBench) {}},
		{"오류", func(b *restoreBench) { b.objs.headErr = errors.New("HEAD 거부") }},
		{"정지", func(b *restoreBench) { b.pauseAt(pointAnchored, func() { close(b.u.workerStop) }) }},
		{"패닉", func(b *restoreBench) { b.pauseAt(pointFolded, func() { panic("손잡이 패닉") }) }},
		{"버림", func(b *restoreBench) { b.pauseAt(pointAnchored, func() { b.cut(cutEvict) }) }},
	}
	for _, x := range exits {
		t.Run(x.name, func(t *testing.T) {
			b := newRestoreBench(t)
			b.contiguous(0, 1, 600*time.Second, up, pending, pending)
			b.uploadAt(b.liveChain(), 0)
			b.liveTable()
			x.arrange(b)
			func() {
				defer func() { _ = recover() }()
				b.restore()
			}()
			b.u.sessions.pause = nil
			if n := b.sessionHolds(); n != 0 {
				t.Errorf("복원이 끝났는데 몫을 %d번 붙들고 있다", n)
			}
			b.u.sessions.evict(b.u.now().Add(2 * defaultSessionTTL))
			if left := b.sessionCount(); left != 0 {
				t.Errorf("수명 뒤 회차 몫 %d개, want 0 — 놓지 않은 몫이 남았다", left)
			}
		})
	}
}

// pauseAt 은 복원이 자리 p 에 처음 오면 f 를 한다.
func (b *restoreBench) pauseAt(p restorePoint, f func()) {
	done := false
	b.u.sessions.pause = func(at restorePoint) {
		if at == p && !done {
			done = true
			f()
		}
	}
}

// sessionHolds 는 시험 회차 몫을 붙든 복원 수다.
func (b *restoreBench) sessionHolds() int {
	m := b.u.sessions
	m.mu.Lock()
	defer m.mu.Unlock()
	if e, ok := m.byID[benchSession]; ok {
		return e.restoring
	}
	return 0
}

// sessionCount 는 회차 몫 수다.
func (b *restoreBench) sessionCount() int {
	m := b.u.sessions
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.byID)
}
