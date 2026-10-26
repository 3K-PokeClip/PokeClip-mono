package main

// 발행 루프 완료 case 의 발행 결과 반영과 P 재구성 대조(체크리스트 474 A-5 1 · A-6 3 · B-3 12–21 · 판단 J18 · J26 · J78 ⑴ · J79 ⑵ ·
// J80 ⑶ ⑷ · J84). 픽스처는 publish_loop_fixture_test.go 다.

import (
	"context"
	"errors"
	"log/slog"
	"slices"
	"sync/atomic"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/upload"
)

// ---------------------------------------------------------------------------
// P 재구성 대조
// ---------------------------------------------------------------------------

// badPrev 는 첫 틱이 P 로 남길 판이다 — 끝 seq 9 · 행 10개라 캐시(행 0..5)에서 다시 뽑은 행 수가 맞지 않는다.
func badPrev(st publish.State) publish.Outcome {
	st.Gen, st.FenceHeld = 1, true
	st.Prev = &publish.Manifest{Published: rewind.Published{Gen: 1, MediaSequence: 0, PublishedSeq: 9, SegmentCount: 10}, ETag: "e1"}
	return publish.Outcome{State: st, Published: true}
}

// P 재구성 불일치(체크리스트 474 B-3 12 · 312 J3 · 계획 4.5 A1 결정 4 · 뮤테이션 68 · 판단 J84) — 루프가 캐시에서 다시 뽑은 P 의
// 행이 P 의 자기기술과 맞지 않으면 틱을 내지 않고(확인되지 않은 행으로 DISC-SEQ 를 덜 세지 않는다) 그 스트림에 적재를 요구하며
// (힌트 P.MSN — 같은 holdTick 에 적재 작업) ERROR rewind_ledger_load_degraded(reason=prev_mismatch) 한 줄을 남긴다.
// 지속 불일치(체크리스트 474 B-3 13 · 뮤테이션 112 · 판단 J79 ⑵) — 재적재 뒤에도 맞지 않으면 그 스트림의 마지막 요구에서 감시
// 주기(30초)가 지났을 때만 다시 요구하고, 다시 요구할 때마다 ERROR 한 줄이다.
func TestPrevMismatchHaltsAndDemandsReloadAtWatchCadence(t *testing.T) {
	f := newPublishLoop(t)
	f.put("s1", liveLedger("s1", "S", 5, 3))
	f.work.ledgers["s1"] = liveLedger("s1", "S", 5, 5) // 재적재도 행 0..5 뿐이다 — 불일치가 이어진다
	f.work.tickFn = func(st publish.State, _ publish.TickInput) publish.Outcome { return badPrev(st) }
	f.uploaded("s1", "S", 3)
	f.hold(loopBase)
	f.settle(loopBase)

	mismatches := func() int { return f.logs.countLevel(slog.LevelError, "rewind_ledger_load_degraded") }
	step := func(at time.Duration, wantLoads, wantLines int) {
		t.Helper()
		f.uploaded("s1", "S", 4)
		f.hold(loopBase.Add(at))
		f.settle(loopBase.Add(at))
		if n, lines := len(f.work.loadCalls()), mismatches(); n != wantLoads || lines != wantLines {
			t.Fatalf("t+%v 적재 %d번 · prev_mismatch %d줄, want %d · %d", at, n, lines, wantLoads, wantLines)
		}
	}
	step(time.Second, 1, 1) // 첫 불일치 — 요구 · 적재 · 적재 뒤 틱도 불일치지만 30초 안이다
	loads := f.work.loadCalls()
	if h := loads[0].Bounds.Hint; h == nil || *h != 0 {
		t.Errorf("적재 힌트 = %v, want P.MSN 0", h)
	}
	if a := f.logs.attrs("rewind_ledger_load_degraded"); a["reason"] != "prev_mismatch" || a["hint"] != int64(0) || a["stream"] != "s1" {
		t.Errorf("prev_mismatch 속성 = %v, want reason=prev_mismatch · hint=0 · stream=s1", a)
	}
	step(10*time.Second, 1, 1)
	step(30*time.Second, 1, 1) // 마지막 요구(t+1s)에서 29초
	step(31*time.Second, 2, 2) // 30초 — 다시 요구하고 다시 남긴다
	if n := len(f.work.tickCalls()); n != 1 {
		t.Errorf("틱 = %d번, want 1(첫 틱뿐 — 불일치 뒤로는 틱 0)", n)
	}
}

// ---------------------------------------------------------------------------
// 완료 case — 발행 결과
// ---------------------------------------------------------------------------

// 결과 칸은 Err 가 있어도 모두 반영한다(체크리스트 474 B-3 14 · 계획 커밋 순서 7 ⓒ) — 같은 결과의 Revoked · GapSeq 를 그 스트림(차선
// 키)의 캐시에 넣는다: 계승을 풀고 · 행을 GAP 으로 둔다. DemandLoad 는 같은 완료 case 에서 적재를 연다(G-1 창 — 아래
// TestUnknownGapCommitHoldsTicksUntilLoad) — 적재가 뷰를 갈아 끼우므로 두 칸의 반영은 DemandLoad 없는 결과로 본다.
func TestOutcomeFieldsReflectedEvenWithErr(t *testing.T) {
	t.Run("Revoked_GapSeq", testOutcomeRevokedGapWithErr)
	t.Run("DemandLoad", testOutcomeDemandLoadWithErr)
}

func testOutcomeRevokedGapWithErr(t *testing.T) {
	f := newPublishLoop(t)
	l := index.RewindLedger{HasCutoff: true, Sessions: []index.RewindSession{
		{SessionID: "P", State: "ending", InitUploaded: true, TargetDuration: 6, DiscontinuityBase: 5},
		{SessionID: "S", State: "live", InitUploaded: true, TargetDuration: 6, DiscontinuityBase: 5, InheritsSession: "P", MinSeq: 2},
	}}
	for seq := int64(0); seq <= 4; seq++ {
		sid := "P"
		if seq >= 2 {
			sid = "S"
		}
		l.Rows = append(l.Rows, segRow("s1", sid, seq, seq != 4))
	}
	f.put("s1", l)
	gap := int64(4)
	f.work.tickFn = func(st publish.State, _ publish.TickInput) publish.Outcome {
		return publish.Outcome{State: st, Revoked: true, GapSeq: &gap, Err: errors.New("publish: 화해 DB 쓰기 실패")}
	}
	f.uploaded("s1", "S", 3)

	f.hold(loopBase)
	f.settle(loopBase)

	if s, _ := f.cache.Session("s1", "S"); s.InheritsSession != "" || s.DiscontinuityBase != 0 {
		t.Errorf("캐시 회차 S = %+v, want 계승 풀림 · base 0", s.RewindSession)
	}
	if rows := f.cache.Snapshot("s1").RowsFrom(4); len(rows) != 1 || !rows[0].IsGap {
		t.Errorf("행 4 = %+v, want GAP", rows)
	}
	if n := len(f.work.loadCalls()); n != 0 {
		t.Errorf("적재 = %d번, want 0(DemandLoad 없음)", n)
	}
}

func testOutcomeDemandLoadWithErr(t *testing.T) {
	f := newPublishLoop(t)
	l := liveLedger("s1", "S", 4, 3)
	f.put("s1", l)
	f.work.ledgers["s1"] = l
	var demanded atomic.Bool
	f.work.tickFn = func(st publish.State, in publish.TickInput) publish.Outcome {
		if demanded.CompareAndSwap(false, true) {
			return publish.Outcome{State: st, DemandLoad: true, Err: errors.New("publish: 화해 DB 쓰기 실패")}
		}
		return publishedOutcome(st, in, loopBase)
	}
	f.uploaded("s1", "S", 3)

	f.hold(loopBase)
	f.settle(loopBase) // 결과 → 같은 완료 case 에서 적재 → 적재 뒤 틱

	if n := len(f.work.loadCalls()); n != 1 {
		t.Errorf("적재 = %d번, want 1(DemandLoad — 다음 holdTick 을 기다리지 않는다)", n)
	}
	if n := len(f.work.tickCalls()); n != 2 {
		t.Errorf("틱 = %d번, want 2(첫 틱 · 적재 뒤 틱)", n)
	}
}

// 계승 취소는 되살아나지 않는다(체크리스트 474 B-3 15 · 계획 뮤테이션 35 종단 · 4.5 A2 결정 2) — 첫 PUT 전 P2′ 가 계승을 풀었다는
// 결과(Revoked)를 캐시에 반영한 뒤에는, 접두 회차의 init 이 늦게 확정돼도 다음 목록에 접두가 다시 실리지 않는다 — MSN 이
// 거꾸로 가지 않는다.
func TestRevokedInheritanceNotRestoredAfterLateInit(t *testing.T) {
	f := newPublishLoop(t)
	l := index.RewindLedger{HasCutoff: true, Sessions: []index.RewindSession{
		{SessionID: "P", State: "ending", TargetDuration: 6, DiscontinuityBase: 5},
		{SessionID: "S", State: "live", InitUploaded: true, TargetDuration: 6, DiscontinuityBase: 5, InheritsSession: "P", MinSeq: 4},
	}}
	for seq := int64(0); seq <= 7; seq++ {
		sid := "P"
		if seq >= 4 {
			sid = "S"
		}
		l.Rows = append(l.Rows, segRow("s1", sid, seq, seq <= 6))
	}
	f.put("s1", l)
	f.work.tickFn = func(st publish.State, in publish.TickInput) publish.Outcome {
		if len(f.work.tickCalls()) > 1 {
			return publishedOutcome(st, in, loopBase)
		}
		in.Playlist.Rows = in.Playlist.Rows[4:] // P2′ 가 접두를 뺀 목록을 올렸다
		out := publishedOutcome(st, in, loopBase)
		out.Revoked = true
		return out
	}
	f.uploaded("s1", "S", 6)
	f.hold(loopBase)
	f.settle(loopBase)

	f.dirty.mark(upload.DirtyEvent{Kind: upload.DirtyUploaded, StreamID: "s1", Axis: index.AxisInit, SessionID: "P"})
	f.cache.ApplyPlaybackUploaded("s1", 7)
	f.uploaded("s1", "S", 7)
	f.hold(loopBase.Add(time.Second))
	f.settle(loopBase.Add(time.Second))

	calls := f.work.tickCalls()
	if len(calls) != 2 {
		t.Fatalf("틱 = %d번, want 2", len(calls))
	}
	if got := rowSeqs(calls[1]); !slices.Equal(got, seqRange(4, 7)) {
		t.Errorf("둘째 목록 행 = %v, want 4..7 — 접두가 다시 실리면 MSN 이 거꾸로 간다", got)
	}
}

// GAP 틱이 DB 에서 후보를 이미 uploaded 로 봤다는 결과(체크리스트 474 B-3 16 · 363 J18)는 ③ 확정으로 먼저 반영한다 — 다음 평시
// 틱이 그 행을 GAP 이 아닌 일반 줄로 싣는다.
func TestGapTickUploadedSeqReflectedAsPlaybackUploaded(t *testing.T) {
	f := newPublishLoop(t)
	l := liveLedger("s1", "S", 5, 3)
	l.Rows[5].PlaybackUploaded = true // 행 4 만 캐시에 미확정이다
	f.put("s1", l)
	f.work.tickFn = func(st publish.State, in publish.TickInput) publish.Outcome {
		if in.GapCandidate != nil {
			k := *in.GapCandidate
			return publish.Outcome{State: st, UploadedSeq: &k}
		}
		return publishedOutcome(st, in, loopBase)
	}
	late := loopBase.Add(30 * time.Second) // 행 4 의 holdAge 8초 — L2

	f.hold(late)
	f.settle(late)
	f.uploaded("s1", "S", 5)
	f.hold(late.Add(time.Second))
	f.settle(late.Add(time.Second))

	calls := f.work.tickCalls()
	if len(calls) != 2 || calls[0].in.GapCandidate == nil || *calls[0].in.GapCandidate != 4 || calls[1].in.GapCandidate != nil {
		t.Fatalf("틱 = %+v, want GAP 틱(후보 4) 뒤 평시 틱", calls)
	}
	rows := calls[1].in.Playlist.Rows
	if !slices.Equal(rowSeqs(calls[1]), seqRange(0, 5)) || rows[4].IsGap || !rows[4].PlaybackUploaded {
		t.Errorf("평시 목록 = %+v, want 행 0..5 · 행 4 는 ③ 확정 일반 줄", rows)
	}
}

// 서지 않은 GAP(체크리스트 474 B-3 17 · 363 ⓐ · J26) — 결과가 돌아온 GAP 틱에 GapSeq 도 UploadedSeq 도 없으면 그 후보를 다음 평가의
// UnstoodGapSeq 로 싣고, GAP 이 선 결과는 그것을 비운다. 겹침 창의 꼬리는 GAP 이 선 뒤에만 다음 계산의 prevTail 이다 — 서지 않은 GAP
// 틱의 겹침 꼬리를 쓰면 그 뒤의 평시 틱(여기서는 GAP 틱이 도는 동안 병합된 요청의 재실행)이 미정착 행 뒤에서 스캔을 시작해 그 구멍을
// 건너뛴 목록을 낸다(30분 조각 — 창은 두 행 · 행 4 미정착 · 행 5 · 6 정착).
func TestUnstoodGapSeqLifetimeAndOverlapTail(t *testing.T) {
	f := newPublishLoop(t)
	l := liveLedger("s1", "S", 6, 3)
	l.Rows[5].PlaybackUploaded, l.Rows[6].PlaybackUploaded = true, true
	for i := range l.Rows {
		l.Rows[i].DurationMS = 30 * 60 * 1000
	}
	f.put("s1", l)
	var gapStands bool
	f.work.tickFn = func(st publish.State, in publish.TickInput) publish.Outcome {
		if in.GapCandidate != nil && !gapStands {
			return publish.Outcome{State: st} // DB 가 부적격으로 봤다 — 서지 않았다
		}
		return publishedOutcome(st, in, loopBase)
	}
	f.publishOnce("s1", "S", 3, loopBase) // 목록 [2 3]
	late := loopBase.Add(3 * time.Hour)   // 행 4 의 holdAge 가 GAP_HOLD 를 넘었다 — L2

	f.work.mu.Lock()
	f.work.gate = make(chan struct{})
	f.work.mu.Unlock()
	f.hold(late) // GAP 틱(후보 4)이 문에서 멈춘다
	f.uploaded("s1", "S", 3)
	f.hold(late.Add(time.Second)) // 틱 요청 — 병합
	f.work.open(&f.work.gate)
	f.settle(late.Add(time.Second)) // GAP 결과(서지 않음) → 재실행 평시 틱

	if g := f.rewind.streams["s1"].unstoodGap; g == nil || *g != 4 {
		t.Fatalf("UnstoodGapSeq = %v, want 4 — 서지 않은 GAP 틱의 후보", g)
	}
	calls := f.work.tickCalls()
	if len(calls) != 3 || calls[1].in.GapCandidate == nil || calls[2].in.GapCandidate != nil {
		t.Fatalf("틱 = %+v, want 평시 · GAP · 재실행 평시", calls)
	}
	if got := rowSeqs(calls[2]); !slices.Equal(got, []int64{2, 3}) {
		t.Errorf("재실행 평시 목록 = %v, want [2 3] — 미정착 행 4 를 건너뛰면 안 된다(겹침 꼬리가 prevTail 로 새었다)", got)
	}
	gapStands = true
	f.hold(late.Add(2 * time.Second))
	f.settle(late.Add(2 * time.Second))
	if g := f.rewind.streams["s1"].unstoodGap; g != nil {
		t.Errorf("GAP 이 선 뒤 UnstoodGapSeq = %d, want nil", *g)
	}
}

// 틱이 도는 동안 소유 회차가 바뀌면(체크리스트 474 A-5 1 「소유 회차가 바뀌었으면 영값에서 다시 시작한다」) 늦게 온 옛 회차의 결과는
// 새 회차의 발행 상태를 덮지 않는다 — 새 회차의 첫 틱은 P 없이(영값) 나간다. 옛 회차의 P 로 DISC-SEQ 를 세면 새 회차의 행과 대조가
// 어긋난다.
func TestOwnerChangeDuringTickStartsNewOwnerFromZero(t *testing.T) {
	f := newPublishLoop(t)
	f.put("s1", liveLedger("s1", "A", 3, 2))
	f.work.mu.Lock()
	f.work.gate = make(chan struct{})
	f.work.mu.Unlock()
	f.publishOnceStart("s1", "A", 3, loopBase) // A 의 틱이 문에서 멈춘다
	r := segRow("s1", "B", 4, false)
	f.cache.ApplyInsert("s1", 4, index.InsertCommit{SessionOpened: true, SessionID: "B", DurationMS: r.DurationMS,
		TargetDuration: 6, PlaybackPDT: r.PlaybackPDT, PlaybackS3Key: r.PlaybackS3Key})
	f.cache.ApplyInitUploaded("s1", "B", false)
	f.hold(loopBase.Add(time.Second)) // 소유 회차가 B 로 바뀐 것을 본다
	f.work.open(&f.work.gate)
	f.settle(loopBase.Add(time.Second)) // A 의 결과(발행 성공)가 늦게 온다

	f.cache.ApplyPlaybackUploaded("s1", 4)
	f.uploaded("s1", "B", 4)
	f.hold(loopBase.Add(2 * time.Second))
	f.settle(loopBase.Add(2 * time.Second))

	calls := f.work.tickCalls()
	if len(calls) != 2 || calls[1].in.Playlist.Owner != "B" {
		t.Fatalf("틱 = %+v, want A 한 번 · B 한 번", calls)
	}
	if calls[1].st.Prev != nil {
		t.Errorf("B 의 첫 틱 상태 P = %+v, want nil — 옛 회차 A 의 결과가 새 회차의 상태를 덮었다", calls[1].st.Prev)
	}
}

// 발행 성공(체크리스트 474 B-3 18 · 계획 뮤테이션 86 · 판단 J29) — 결과의 Published 를 받으면 그 시각을 PublishedAt 으로 적고(사다리
// stallAge 는 그때부터 — 처음 소유 회차로 본 시각보다 늦다) 마지막으로 계산한 꼬리와 P.MSN 가운데 앞쪽 앞의 행을 뷰에서 버린다
// (Trim — 30분 조각이라 창은 두 행).
func TestPublishedRecordsTimeAndTrimsView(t *testing.T) {
	f := newPublishLoop(t)
	l := liveLedger("s1", "S", 4, 4)
	for i := range l.Rows {
		l.Rows[i].DurationMS = 30 * 60 * 1000
	}
	f.put("s1", l)
	f.hold(loopBase) // 처음 소유 회차로 본 시각
	published := loopBase.Add(5 * time.Second)
	f.uploaded("s1", "S", 4)
	f.hold(published)
	f.settle(published)

	if rows := f.cache.Snapshot("s1").RowsFrom(0); len(rows) == 0 || rows[0].Seq != 3 {
		t.Errorf("뷰 첫 행 = %+v, want seq 3 — min(꼬리 3, P.MSN 3) 앞을 버린다", rows)
	}
	for _, d := range []time.Duration{7499 * time.Millisecond, 7500 * time.Millisecond} {
		f.hold(published.Add(d))
		f.settle(published.Add(d))
	}
	if a := f.logs.attrs("rewind_publish_stall_seconds"); a == nil || a["seconds"] != 7.5 {
		t.Errorf("L4 = %v, want 발행 성공 7.5초 뒤 한 줄(seconds=7.5 — PublishedAt 부터)", a)
	}
}

// 재발행(체크리스트 474 B-3 19 · 계획 뮤테이션 32 · 판단 J80 ⑶) — 발행 틱이 Published 거짓이고 Err 가 있거나 fence 를 쥔 채 화해가
// 예약됐으면 실패라 다음 holdTick 이 원소 없이도 틱을 다시 낸다. 같은 본문 생략(세대만 소모) · 다른 writer 의 fence(획득 0행 —
// fence 를 쥐지 못했다)는 실패가 아니다 — 다시 내면 평시 상한이 깨진다. 화해 예약이 남은 채 fence 를 쥐지 못한 결과도 획득 0행이다.
func TestFailedTickRepublishesOnNextHoldTick(t *testing.T) {
	tests := []struct {
		name      string
		out       func(st publish.State) publish.Outcome
		republish bool
	}{
		{"표_밖_실패", func(st publish.State) publish.Outcome {
			st.FenceHeld = true
			return publish.Outcome{State: st, Err: errors.New("publish: fence 획득 실패")}
		}, true},
		{"화해_예약", func(st publish.State) publish.Outcome {
			st.FenceHeld, st.ReconcileDue = true, true
			return publish.Outcome{State: st}
		}, true},
		{"같은_본문", func(st publish.State) publish.Outcome {
			st.FenceHeld, st.Gen = true, st.Gen+1
			return publish.Outcome{State: st}
		}, false},
		{"획득_0행", func(st publish.State) publish.Outcome { return publish.Outcome{State: st} }, false},
		{"획득_0행_화해_예약_남음", func(st publish.State) publish.Outcome {
			st.FenceHeld, st.ReconcileDue = false, true
			return publish.Outcome{State: st}
		}, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f := newPublishLoop(t)
			f.put("s1", liveLedger("s1", "S", 3, 3))
			f.work.tickFn = func(st publish.State, in publish.TickInput) publish.Outcome {
				if len(f.work.tickCalls()) == 1 {
					return tt.out(st)
				}
				return publishedOutcome(st, in, loopBase)
			}
			f.uploaded("s1", "S", 3)
			f.hold(loopBase)
			f.settle(loopBase)

			f.hold(loopBase.Add(time.Second)) // 원소 없음
			f.settle(loopBase.Add(time.Second))
			f.hold(loopBase.Add(2 * time.Second))
			f.settle(loopBase.Add(2 * time.Second))

			want := 1
			if tt.republish {
				want = 2 // 재발행 한 번 — 성공하면 표지가 풀린다
			}
			if n := len(f.work.tickCalls()); n != want {
				t.Errorf("틱 = %d번, want %d", n, want)
			}
		})
	}
}

// 병합(체크리스트 474 B-3 20 · 판단 J80 ⑷ · 계획 커밋 순서 7 〔r51a〕) — 틱이 도는 동안 온 틱 요청은 버리지 않고 병합해, 결과를 받은
// 뒤 새 입력으로 한 번 더 낸다(요청 때 입력이 아니라 그때의 캐시). lazy 갱신은 차선이 빈 스트림에만 내므로 도는 틱에 병합되지
// 않는다 — 그 사이 lazy 차례가 와도 갱신 0 · 추가 틱 0 이다.
func TestMergedTickRerunsOnceWithFreshInput(t *testing.T) {
	f := newPublishLoop(t)
	f.put("s1", liveLedger("s1", "S", 6, 3))
	f.uploaded("s1", "S", 3)
	f.hold(loopBase)
	f.settle(loopBase) // fence 를 쥐었다 — lease 갱신 시각 loopBase

	f.work.gate = make(chan struct{})
	f.cache.ApplyPlaybackUploaded("s1", 4)
	f.uploaded("s1", "S", 4)
	f.hold(loopBase.Add(time.Second)) // 둘째 틱 — 문에서 멈춘다
	f.cache.ApplyPlaybackUploaded("s1", 5)
	f.uploaded("s1", "S", 5)
	f.hold(loopBase.Add(4 * time.Second)) // 틱이 돈다 — 병합 · lazy 차례(3.5초)지만 차선이 비지 않았다
	if n := len(f.work.renewCalls()); n != 0 {
		t.Fatalf("도는 틱 옆 lazy 갱신 = %d번, want 0", n)
	}
	gate := f.work.gate
	f.work.mu.Lock()
	f.work.gate = nil
	f.work.mu.Unlock()
	close(gate)
	f.settle(loopBase.Add(4 * time.Second))

	calls := f.work.tickCalls()
	if len(calls) != 3 {
		t.Fatalf("틱 = %d번, want 3(병합된 요청은 끝난 뒤 한 번)", len(calls))
	}
	if got := rowSeqs(calls[2]); !slices.Equal(got, seqRange(0, 5)) {
		t.Errorf("재실행 목록 행 = %v, want 0..5 — 결과를 받은 뒤의 캐시로 만든다", got)
	}
	if n := len(f.work.renewCalls()); n != 0 {
		t.Errorf("lazy 갱신 = %d번, want 0", n)
	}
}

// 표 밖 실패의 로그(체크리스트 474 B-3 21 · 판단 J78 ⑴ · kty 결정 ⑤) — 발행 작업의 Outcome.Err 는 ERROR rewind_job_failed(job=publish ·
// stream · session · err) 이고 같은 스트림에서 이어지는 같은 오류는 한 줄이다(오류 없는 결과가 끼면 다시 남긴다). 루프 수명
// ctx 가 끝나 멈춘 틱의 context.Canceled 는 종료라 줄 0 이다.
func TestJobErrorLoggedOncePerRunAndQuietWhenLoopEnds(t *testing.T) {
	t.Run("같은_오류_한_줄", func(t *testing.T) {
		f := newPublishLoop(t)
		f.put("s1", liveLedger("s1", "S", 3, 3))
		fail := true
		f.work.tickFn = func(st publish.State, in publish.TickInput) publish.Outcome {
			if fail {
				return publish.Outcome{State: st, Err: errors.New("publish: fence 획득 session=\"S\": 시한")}
			}
			return publishedOutcome(st, in, loopBase)
		}
		for i := range 3 { // 재발행이 같은 오류를 되풀이한다
			f.uploaded("s1", "S", 3)
			f.hold(loopBase.Add(time.Duration(i) * time.Second))
			f.settle(loopBase.Add(time.Duration(i) * time.Second))
		}
		if n := f.logs.countLevel(slog.LevelError, "rewind_job_failed"); n != 1 {
			t.Fatalf("rewind_job_failed = %d줄, want 1(같은 오류는 한 줄)", n)
		}
		if a := f.logs.attrs("rewind_job_failed"); a["job"] != "publish" || a["stream"] != "s1" || a["session"] != "S" {
			t.Errorf("속성 = %v, want job=publish · stream=s1 · session=S", a)
		}
		fail = false
		f.uploaded("s1", "S", 3)
		f.hold(loopBase.Add(3 * time.Second))
		f.settle(loopBase.Add(3 * time.Second))
		fail = true
		f.uploaded("s1", "S", 3)
		f.hold(loopBase.Add(4 * time.Second))
		f.settle(loopBase.Add(4 * time.Second))
		if n := f.logs.countLevel(slog.LevelError, "rewind_job_failed"); n != 2 {
			t.Errorf("성공 뒤 같은 오류 = %d줄, want 2(다시 남긴다)", n)
		}
	})
	t.Run("루프_종료", func(t *testing.T) {
		f := newPublishLoop(t)
		f.put("s1", liveLedger("s1", "S", 3, 3))
		f.work.tickFn = func(st publish.State, _ publish.TickInput) publish.Outcome {
			return publish.Outcome{State: st, Err: context.Canceled}
		}
		f.uploaded("s1", "S", 3)
		f.hold(loopBase)
		res := <-f.rewind.done()

		f.rewind.complete(cancelledContext(t), res, loopBase)

		if n := f.logs.count("rewind_job_failed"); n != 0 {
			t.Errorf("rewind_job_failed = %d줄, want 0 — 멈춘 틱은 종료다", n)
		}
	})
}

// G-1 창(계획 커밋 순서 5 〔r53〕 · 체크리스트 474 B-4 6 의 루프 층 · 보안 c4 r1–r4 미확인 1) — GAP 틱의 COMMIT 결과를 모르면
// (stage=commit — Outcome.DemandLoad) 서버가 커밋했을 수 있는 GAP 행을 캐시가 모른다. 그 뒤 k 의 ③ 결과가 먼저 닿아도 적재가 끝나기
// 전에는 그 스트림에 틱을 내지 않는다 — 내면 k 가 일반 줄로 올라간 뒤 적재가 그 줄을 GAP 으로 바꾼다. ③ 결과가 GAP 틱이 도는
// 동안 닿아 병합된 요청(재실행)도 같다. 적재 뒤 틱의 목록에서 k 는 GAP 줄이다(원장이 ③ 확정을 이긴다).
func TestUnknownGapCommitHoldsTicksUntilLoad(t *testing.T) {
	tests := []struct {
		name   string
		merged bool // ③ 결과가 GAP 틱이 도는 동안 닿아 틱 요청이 병합된다
	}{
		{"결과_뒤_도착", false},
		{"도는_중_도착_병합", true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f := newPublishLoop(t)
			l := liveLedger("s1", "S", 5, 3)
			l.Rows[5].PlaybackUploaded = true // 행 4 만 미확정 — L2 후보
			f.put("s1", l)
			loaded := liveLedger("s1", "S", 5, 5) // 서버가 커밋한 GAP 행 4 와 그 뒤 ③ 확정이 함께 든 장부
			loaded.Rows[4].IsGap = true
			f.work.ledgers["s1"] = loaded
			f.work.tickFn = func(st publish.State, in publish.TickInput) publish.Outcome {
				if in.GapCandidate != nil {
					return publish.Outcome{State: st, DemandLoad: true} // gap_tx_failed(stage=commit)
				}
				return publishedOutcome(st, in, loopBase)
			}
			late := loopBase.Add(30 * time.Second) // 행 4 의 holdAge 8초 — L2
			f.work.mu.Lock()
			f.work.loadGate = make(chan struct{})
			if tt.merged {
				f.work.gate = make(chan struct{})
			}
			f.work.mu.Unlock()

			f.hold(late) // GAP 틱(후보 4)
			if tt.merged {
				f.uploaded("s1", "S", 4)
				f.hold(late.Add(time.Second)) // ③ 4 반영 · 틱 요청 — 병합
				f.work.open(&f.work.gate)
			}
			f.next(late.Add(time.Second)) // GAP 결과(결과 모름 — 적재 요구)
			if !tt.merged {
				f.uploaded("s1", "S", 4)
				f.hold(late.Add(2 * time.Second)) // ③ 4 가 다음 holdTick 에 닿는다
			}
			if job := f.running("s1"); job == nil || job.kind != jobLoad {
				t.Fatalf("적재 전 차선 작업 = %+v, want 적재(문에서 멈춤) — 결과 모름 뒤 틱이 먼저 나가면 k 가 일반 줄로 올라간다(G-1)", job)
			}
			if calls := f.work.tickCalls(); len(calls) != 1 {
				t.Fatalf("적재 전 틱 %d번, want GAP 틱 하나(G-1)", len(calls))
			}
			f.work.open(&f.work.loadGate)
			f.settle(late.Add(3 * time.Second))

			calls := f.work.tickCalls()
			if len(f.work.loadCalls()) != 1 || len(calls) != 2 {
				t.Fatalf("적재 %d번 · 틱 %d번, want 적재 1 · 틱 2(GAP · 적재 뒤)", len(f.work.loadCalls()), len(calls))
			}
			if rows := calls[1].in.Playlist.Rows; !slices.Equal(rowSeqs(calls[1]), seqRange(0, 5)) || !rows[4].IsGap {
				t.Errorf("적재 뒤 목록 = %+v, want 행 0..5 · 행 4 는 GAP 줄", rows)
			}
		})
	}
}
