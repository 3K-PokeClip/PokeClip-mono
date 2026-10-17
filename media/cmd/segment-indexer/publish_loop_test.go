package main

// 발행 루프 배선의 루프 층 검증(POK-195 M4 PR ⓒ 커밋 7b — 체크리스트 474 A-4 · A-5 · A-6 · B-3 · 판단 J78–J89). 픽스처는
// publish_loop_fixture_test.go 다. 시험 고루틴이 루프 고루틴 자리라 hold(holdTicks case) · settle(완료 case)을 차례로 부르고,
// 차선 작업 몸체는 가짜다(발행 · 적재 · 감시의 DB · 저장소 동작은 rewind/publish · index 시험이 잰다).

import (
	"log/slog"
	"slices"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/upload"
)

// rowSeqs 는 목록 행의 seq 들이다.
func rowSeqs(c tickCall) []int64 {
	var out []int64
	for _, r := range c.in.Playlist.Rows {
		out = append(out, r.Seq)
	}
	return out
}

// seqRange 는 from..to 의 seq 들이다.
func seqRange(from, to int64) []int64 {
	var out []int64
	for s := from; s <= to; s++ {
		out = append(out, s)
	}
	return out
}

// ---------------------------------------------------------------------------
// 발행 몫이 없는 루프
// ---------------------------------------------------------------------------

// 발행 몫이 없는 루프(체크리스트 474 B-1 4 의 루프 쪽 · 판단 J69 — 7b 의 run 형상: 캐시 · 차선 · 발행자 · Dirty 가 nil) — holdTicks
// case 는 4.1 종료 전이만 돌리고 완료 case 는 nil 채널이라 영구 비활성이다. 캐시가 있어도 발행자 · 차선이 없으면 발행 갈래는
// 아무것도 하지 않는다 — 쌓인 요구 적재를 비우지 않는다(낼 차선이 없다).
func TestRewindLoopWithoutPublishingShareOnlyEndsSessions(t *testing.T) {
	t.Run("캐시_없음", func(t *testing.T) {
		f := newOfflineLoop(t, nil)
		f.rewind.cache = nil
		f.boot("s1")
		f.judge(f.at(0), "s1")
		if !f.judge(f.at(10*time.Second), "s1") {
			t.Fatal("점검을 발사하지 않았다 — 준비가 어긋났다")
		}
		f.offAir("s1", f.at(11*time.Second))

		f.rewind.holdTick(t.Context(), f.at(11*time.Second))

		if got := f.ends.called(); !slices.Equal(got, []string{"s1"}) {
			t.Errorf("EndLive = %v, want [s1] — 4.1 은 발행 몫과 무관하게 돈다", got)
		}
		if f.rewind.done() != nil {
			t.Error("완료 case 채널이 nil 이 아니다 — 발행 몫이 없으면 영구 비활성이어야 한다")
		}
	})
	t.Run("캐시만_있음", func(t *testing.T) {
		f := newOfflineLoop(t, nil)
		f.cache.DemandLoad("s2", nil)

		f.rewind.holdTick(t.Context(), f.at(0))

		if d := f.cache.DemandedLoads(); len(d) != 1 || d[0].StreamID != "s2" {
			t.Errorf("요구 적재 = %+v, want [s2] 그대로 — 차선이 없으면 비우지 않는다", d)
		}
		if f.rewind.done() != nil {
			t.Error("완료 case 채널이 nil 이 아니다")
		}
	})
}

// ---------------------------------------------------------------------------
// Dirty 반영
// ---------------------------------------------------------------------------

// 틱은 Dirty 원소가 부른다(체크리스트 474 B-3 4 · 계획 뮤테이션 20 형 · 판단 J80 ⑴) — 원소가 없는 holdTick 은 틱을 내지 않고
// (P0 왕복 0), ③ 업로드 원소가 오면 캐시에 반영한 뒤 그 스트림에 틱 하나를 낸다. 입력 목록은 새로 settled 가 된 행까지다.
func TestDirtyEventTriggersOneTick(t *testing.T) {
	f := newPublishLoop(t)
	f.put("s1", liveLedger("s1", "S", 3, 2))

	f.hold(loopBase)
	if f.running("s1") != nil || len(f.work.tickCalls()) != 0 {
		t.Fatalf("원소 없는 holdTick 의 틱 = %d, want 0", len(f.work.tickCalls()))
	}
	f.uploaded("s1", "S", 3)
	f.hold(loopBase.Add(time.Second))
	f.settle(loopBase.Add(time.Second))

	calls := f.work.tickCalls()
	if len(calls) != 1 || !slices.Equal(rowSeqs(calls[0]), seqRange(0, 3)) {
		t.Fatalf("틱 = %d번(첫 목록 %v), want 1번 · 행 0..3", len(calls), calls)
	}
}

// stsd 해제가 init 확정과 한 원소로 캐시에 닿는다(체크리스트 474 B-3 1 · 계획 4.5 A2 결정 8 · 뮤테이션 106 · 116 종단 · 판단 J83) —
// 해제 참인 init 원소를 반영하면 그 회차의 계승이 풀리고 base 가 0 이라, 첫 목록에 접두(직전 회차 행)가 없고 DISC-SEQ 가 0 이다.
// 루프는 해제 확정을 WARN session_inherit_declined(confirmed) 한 줄로 남긴다. 해제 거짓인 원소는 계승을 그대로 둔다 — 첫 목록에
// 접두가 실리고 DISC-SEQ 는 승계한 base 다.
func TestStsdRevocationReachesCacheWithInitUpload(t *testing.T) {
	ledger := func() index.RewindLedger {
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
		return l
	}
	tests := []struct {
		name     string
		revoked  bool
		rows     []int64
		disc     int64
		inherits string
		declined int
	}{
		{"해제", true, seqRange(4, 6), 0, "", 1},
		{"유지", false, seqRange(0, 6), 5, "P", 0},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f := newPublishLoop(t)
			f.put("s1", ledger())
			f.dirty.mark(upload.DirtyEvent{Kind: upload.DirtyUploaded, StreamID: "s1", Axis: index.AxisInit, SessionID: "S",
				InheritRevoked: tt.revoked})

			f.hold(loopBase)
			f.settle(loopBase)

			s, _ := f.cache.Session("s1", "S")
			if !s.InitUploaded || s.InheritsSession != tt.inherits || s.DiscontinuityBase != tt.disc {
				t.Errorf("캐시 회차 S = %+v, want init 확정 · 계승 %q · base %d", s.RewindSession, tt.inherits, tt.disc)
			}
			calls := f.work.tickCalls()
			if len(calls) != 1 || !slices.Equal(rowSeqs(calls[0]), tt.rows) || calls[0].in.DiscontinuitySequence != tt.disc {
				t.Fatalf("틱 = %+v, want 한 번 · 행 %v · DISC-SEQ %d", calls, tt.rows, tt.disc)
			}
			if n := f.logs.countLevel(slog.LevelWarn, "session_inherit_declined"); n != tt.declined {
				t.Errorf("session_inherit_declined WARN = %d줄, want %d", n, tt.declined)
			}
			if tt.declined == 1 {
				if a := f.logs.attrs("session_inherit_declined"); a["stream_id"] != "s1" || a["session_id"] != "S" || a["confirmed"] != true {
					t.Errorf("session_inherit_declined 속성 = %v, want stream_id=s1 · session_id=S · confirmed=true", a)
				}
			}
		})
	}
}

// 분리 원소(체크리스트 474 B-3 2 · 판단 J86) — init 불일치로 끝난 회차의 원소를 반영하면 캐시 회차가 ending 이라 사다리가
// 꺼지고(그 회차의 머리 다음 행이 늦어도 L1 · L2 0) 발행 게이트가 닫히며(틱 0), 업로더의 그 회차 메모리를 한 번 지운다.
func TestDirtySplitEndsSessionAndForgetsUploaderMemory(t *testing.T) {
	f := newPublishLoop(t)
	f.put("s1", liveLedger("s1", "S", 3, 2)) // 행 3 이 머리 다음 행 — live 면 L1 · L2 가 설 만큼 늦다
	f.dirty.mark(upload.DirtyEvent{Kind: upload.DirtySplit, StreamID: "s1", SessionID: "S", Reason: "init_mismatch"})

	f.hold(loopBase.Add(time.Hour))
	f.settle(loopBase.Add(time.Hour))

	if got := f.sessionState("s1", "S"); got != "ending" {
		t.Errorf("캐시 회차 S state = %q, want ending", got)
	}
	if n := len(f.work.tickCalls()); n != 0 {
		t.Errorf("틱 = %d번, want 0(ending 회차)", n)
	}
	if got := f.ups.expedites(); len(got) != 0 {
		t.Errorf("Expedite = %v, want [](사다리가 꺼졌다)", got)
	}
	if got := f.ups.forgets(); !slices.Equal(got, []string{"S"}) {
		t.Errorf("ForgetSession = %v, want [S]", got)
	}
}

// 적재 중의 Dirty 원소는 한 번만 반영하고 Ack 한다(체크리스트 474 B-3 3 · 계획 4.5 A3 결정 2 「적재 중에는 로그에 적은 것을
// 반영으로 본다」 · 뮤테이션 120) — 같은 원소가 세 holdTick 동안 있어도 캐시 로그에는 한 번 적히고(넘침 0 · 적재 자기 재시작 0 —
// 루프가 낸 적재 0) 적재가 끝나면 그 push 가 재생된다.
func TestDirtyEventsAckedOnceDuringLoad(t *testing.T) {
	f := newPublishLoop(t)
	f.cache.Options.LoadPushBuffer = 2 // 같은 원소를 세 번 적으면 넘친다
	f.put("s1", liveLedger("s1", "S", 5, 4))
	token := f.cache.BeginLoad("s1", nil)
	f.uploaded("s1", "S", 5)

	for i := range 3 {
		f.hold(loopBase.Add(time.Duration(i) * time.Second))
		f.settle(loopBase.Add(time.Duration(i) * time.Second))
	}

	if p := f.dirty.pending(); len(p) != 0 {
		t.Errorf("남은 원소 = %+v, want [] — 적재 중에도 반영하면 Ack 한다", p)
	}
	if n := len(f.work.loadCalls()); n != 0 || !f.cache.Loading("s1") {
		t.Fatalf("루프가 낸 적재 = %d번 · 적재 중 %t, want 0 · true — 로그가 넘쳐 적재가 스스로 다시 열리면 안 된다", n, f.cache.Loading("s1"))
	}
	if applied, _ := f.cache.CompleteLoad("s1", token, liveLedger("s1", "S", 5, 4)); !applied {
		t.Fatal("적재 완료가 버려졌다 — 토큰이 바뀌었다(적재가 다시 열렸다)")
	}
	if rows := f.cache.Snapshot("s1").RowsFrom(5); len(rows) != 1 || !rows[0].PlaybackUploaded {
		t.Errorf("적재 뒤 행 5 = %+v, want ③ 확정 — 적재 중 반영한 push 가 재생된다", rows)
	}
}

// Ack 규칙(체크리스트 474 B-3 5 · 판단 J81 · 계획 뮤테이션 24) — 원소는 반영하고 그 스트림의 틱을 낸 뒤, 또는 틱을 낼 수 없다고
// 판정한 뒤(게이트 거짓 · 뷰 없음 · 부정 표식 · 적재 중) 같은 holdTick 에서 Ack 한다. 그래서 다음 holdTick 은 같은 원소로 틱을
// 다시 내지 않는다.
func TestDirtyAckedAfterDispatchIncludingUntickableStreams(t *testing.T) {
	f := newPublishLoop(t)
	f.put("a", liveLedger("a", "A", 3, 2))
	ending := liveLedger("b", "B", 3, 2)
	ending.Sessions[0].State = "ending"
	f.put("b", ending)
	f.put("d", index.RewindLedger{}) // 부정 표식
	f.put("e", liveLedger("e", "E", 3, 2))
	f.cache.BeginLoad("e", nil)
	for _, e := range [][3]any{{"a", "A", int64(3)}, {"b", "B", int64(3)}, {"c", "C", int64(0)}, {"d", "D", int64(0)}, {"e", "E", int64(3)}} {
		f.uploaded(e[0].(string), e[1].(string), e[2].(int64))
	}

	f.hold(loopBase)
	f.settle(loopBase)
	if p := f.dirty.pending(); len(p) != 0 {
		t.Fatalf("남은 원소 = %+v, want [] — 틱을 낸 스트림도 낼 수 없는 스트림도 Ack 한다", p)
	}
	f.hold(loopBase.Add(time.Second))
	f.settle(loopBase.Add(time.Second))

	calls := f.work.tickCalls()
	if len(calls) != 1 || calls[0].in.Playlist.StreamID != "a" {
		t.Errorf("틱 = %+v, want a 한 번 — Ack 한 원소로 다시 틱을 내지 않는다", calls)
	}
}

// ---------------------------------------------------------------------------
// 틱 디스패치 · 입력
// ---------------------------------------------------------------------------

// 발행 게이트(체크리스트 474 B-3 6 · 7 · 8 · 계획 4.5 A0 결정 3 · 뮤테이션 75 원문 · 12 종단 · 80 의 루프 쪽) — 원소가 와도
// 소유 회차가 ending 이거나(75) init 이 확정되지 않았거나(12) 스트림이 적재 중이면 틱을 내지 않는다(P0 왕복 0). 적재 중인
// 스트림은 목록을 만들어 보지도 않는다 — 요구 적재(Playlist 거짓 ②)로 그 적재를 다시 열지 않는다.
func TestGateBlocksTickForEndingUninitializedOrLoadingOwner(t *testing.T) {
	tests := []struct {
		name string
		edit func(f *publishLoop, l *index.RewindLedger)
	}{
		{"ending_회차", func(_ *publishLoop, l *index.RewindLedger) { l.Sessions[0].State = "ending" }},
		{"init_미확정", func(_ *publishLoop, l *index.RewindLedger) { l.Sessions[0].InitUploaded = false }},
		{"적재_중", nil},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f := newPublishLoop(t)
			l := liveLedger("s1", "S", 3, 2)
			if tt.edit != nil {
				tt.edit(f, &l)
			}
			f.put("s1", l)
			if tt.edit == nil {
				f.hold(loopBase.Add(-time.Second)) // 적재 전에 소유 회차를 한 번 본다 — 적재 중 옛 뷰의 회차로 목록을 만들어 보면 안 된다
				f.cache.BeginLoad("s1", nil)
			}
			f.uploaded("s1", "S", 3)

			f.hold(loopBase)
			f.settle(loopBase)

			if n, loads := len(f.work.tickCalls()), len(f.work.loadCalls()); n != 0 || loads != 0 {
				t.Errorf("틱 %d번 · 적재 %d번, want 0 · 0", n, loads)
			}
		})
	}
}

// Playlist 거짓(체크리스트 474 B-3 9 · 판단 J79 ⑵ · 계획 4.5 A3 결정 6 ②) — 소유 회차는 있는데 목록을 만들 수 없으면(창에 실린
// 접두 회차를 캐시가 모른다) 틱을 내지 않고 적재를 요구한다(같은 holdTick 에 적재 작업 하나). 적재 뒤에도 거짓이면 그
// 스트림의 마지막 요구에서 감시 주기(30초)가 지났을 때만 다시 요구한다.
func TestPlaylistMissDemandsLoadOncePerWatchPeriod(t *testing.T) {
	f := newPublishLoop(t)
	l := index.RewindLedger{HasCutoff: true, Sessions: []index.RewindSession{
		{SessionID: "S", State: "live", InitUploaded: true, TargetDuration: 6, InheritsSession: "P", MinSeq: 2},
	}}
	for seq := int64(0); seq <= 3; seq++ {
		sid := "P"
		if seq >= 2 {
			sid = "S"
		}
		l.Rows = append(l.Rows, segRow("s1", sid, seq, true))
	}
	f.put("s1", l)
	f.work.ledgers["s1"] = l // 적재도 접두 회차를 싣지 못한다

	step := func(at time.Duration, wantLoads int) {
		t.Helper()
		f.uploaded("s1", "S", 3)
		f.hold(loopBase.Add(at))
		f.settle(loopBase.Add(at))
		if n := len(f.work.loadCalls()); n != wantLoads {
			t.Fatalf("t+%v 적재 = %d번, want %d", at, n, wantLoads)
		}
	}
	step(0, 1)              // 첫 요구 — 같은 holdTick 에 적재 · 적재 뒤 틱도 거짓이지만 30초 안이라 다시 요구하지 않는다
	step(10*time.Second, 1) // 30초 안
	step(29*time.Second, 1) // 30초 안
	step(30*time.Second, 2) // 감시 주기가 지났다
	if n := len(f.work.tickCalls()); n != 0 {
		t.Errorf("틱 = %d번, want 0", n)
	}
}

// 소유 회차(체크리스트 474 B-3 10 · 판단 J71 · J62 · 363 J33) — 캐시가 live 회차 둘을 보면(재접속 개시가 직전 회차를 내리지 않은
// 창) 뒤에 연 회차의 목록에 틱을 낸다. live 회차가 없으면 마지막 회차가 소유 회차다 — 발행을 멈춘 사실을 그 회차로 한 번 WARN
// 한다(C3).
func TestOwnerIsLatestOpenedSession(t *testing.T) {
	f := newPublishLoop(t)
	l := index.RewindLedger{HasCutoff: true, Sessions: []index.RewindSession{
		{SessionID: "A", State: "live", InitUploaded: true, TargetDuration: 6},
		{SessionID: "B", State: "live", InitUploaded: true, TargetDuration: 6, MinSeq: 3},
	}}
	for seq := int64(0); seq <= 5; seq++ {
		sid := "A"
		if seq >= 3 {
			sid = "B"
		}
		l.Rows = append(l.Rows, segRow("s1", sid, seq, true))
	}
	f.put("s1", l)
	f.uploaded("s1", "B", 5)

	f.hold(loopBase)
	f.settle(loopBase)
	calls := f.work.tickCalls()
	if len(calls) != 1 || calls[0].in.Playlist.Owner != "B" || !slices.Equal(rowSeqs(calls[0]), seqRange(3, 5)) {
		t.Fatalf("틱 = %+v, want B 목록(행 3..5) 한 번", calls)
	}

	f.cache.ApplySessionEnding("s1", "A")
	f.cache.ApplySessionEnding("s1", "B")
	f.hold(loopBase.Add(time.Second))
	f.hold(loopBase.Add(2 * time.Second))
	if n := f.logs.countLevel(slog.LevelWarn, "rewind_publish_stall_seconds"); n != 1 {
		t.Fatalf("C3 WARN = %d줄, want 1", n)
	}
	if a := f.logs.attrs("rewind_publish_stall_seconds"); a["session"] != "B" || a["state"] != "ending" {
		t.Errorf("C3 WARN 속성 = %v, want session=B · state=ending", a)
	}
}

// DISC-SEQ(체크리스트 474 B-3 11 · 계획 4.5 A1 결정 1 · 6 · 뮤테이션 26) — 발행본 P 가 없으면 소유 회차의 개시 base 이고, P 가
// 있으면 P 의 DISC-SEQ 에 다음 목록에서 빠지는 끊김 표시 수를 더한 절대식이다(캐시 base 가 아니다). 픽스처: 조각 길이 30분이라 창은
// 두 행이다 — S(P 를 계승 · base 5)의 첫 행 2 에 표시가 서고, 창이 3..4 로 밀리면 그 표시가 빠진다.
func TestDiscontinuitySequenceIsAbsoluteWithPrevAndOwnerBaseWithout(t *testing.T) {
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
		r := segRow("s1", sid, seq, seq <= 3)
		r.DurationMS = 30 * 60 * 1000
		l.Rows = append(l.Rows, r)
	}
	f.put("s1", l)

	f.uploaded("s1", "S", 3)
	f.hold(loopBase)
	f.settle(loopBase)
	f.cache.ApplyPlaybackUploaded("s1", 4)
	f.uploaded("s1", "S", 4)
	f.hold(loopBase.Add(time.Second))
	f.settle(loopBase.Add(time.Second))

	calls := f.work.tickCalls()
	if len(calls) != 2 {
		t.Fatalf("틱 = %d번, want 2", len(calls))
	}
	if got := []int64{calls[0].in.DiscontinuitySequence, calls[1].in.DiscontinuitySequence}; !slices.Equal(got, []int64{5, 6}) {
		t.Errorf("DISC-SEQ = %v, want [5 6] — 첫 목록은 소유 회차 base, 다음은 P(5) + 빠진 표시 1", got)
	}
	if !slices.Equal(rowSeqs(calls[0]), []int64{2, 3}) || !slices.Equal(rowSeqs(calls[1]), []int64{3, 4}) {
		t.Errorf("목록 행 = %v · %v, want [2 3] · [3 4]", rowSeqs(calls[0]), rowSeqs(calls[1]))
	}
}

// 절대식은 한 번 축출한 뒤에도 P 의 DISC-SEQ 에서 센다(계획 4.5 A1 결정 1 · 뮤테이션 66) — 바로 위 시험의 셋째 틱이다. 둘째
// 목록에서 표시 하나가 빠져 P 의 DISC-SEQ 가 캐시 base 5 보다 큰 6 이 됐다. 셋째 창 4..5 에서는 빠지는 표시가 없어 6 그대로다.
// 캐시 base 에 이번 축출 수만 더하는 증분식이면 5 로 되돌아가 발행 전 검사 S2(DISC-SEQ 비감소)가 멈춘다. 위 시험은 P 의
// DISC-SEQ 가 캐시 base 와 같은 국면만 재서 증분식을 가르지 못했다(커밋 8 뮤테이션 정식 실행의 생존 뮤턴트).
func TestDiscontinuitySequenceAccumulatesFromPrevAfterEviction(t *testing.T) {
	f := newPublishLoop(t)
	l := index.RewindLedger{HasCutoff: true, Sessions: []index.RewindSession{
		{SessionID: "P", State: "ending", InitUploaded: true, TargetDuration: 6, DiscontinuityBase: 5},
		{SessionID: "S", State: "live", InitUploaded: true, TargetDuration: 6, DiscontinuityBase: 5, InheritsSession: "P", MinSeq: 2},
	}}
	for seq := int64(0); seq <= 5; seq++ {
		sid := "P"
		if seq >= 2 {
			sid = "S"
		}
		r := segRow("s1", sid, seq, seq <= 3)
		r.DurationMS = 30 * 60 * 1000
		l.Rows = append(l.Rows, r)
	}
	f.put("s1", l)

	f.uploaded("s1", "S", 3)
	f.hold(loopBase)
	f.settle(loopBase)
	for i, seq := range []int64{4, 5} {
		at := loopBase.Add(time.Duration(i+1) * time.Second)
		f.cache.ApplyPlaybackUploaded("s1", seq)
		f.uploaded("s1", "S", seq)
		f.hold(at)
		f.settle(at)
	}

	calls := f.work.tickCalls()
	if len(calls) != 3 {
		t.Fatalf("틱 = %d번, want 3", len(calls))
	}
	var got []int64
	for _, c := range calls {
		got = append(got, c.in.DiscontinuitySequence)
	}
	if !slices.Equal(got, []int64{5, 6, 6}) {
		t.Errorf("DISC-SEQ = %v, want [5 6 6] — 셋째는 P(6) + 빠진 표시 0", got)
	}
	if !slices.Equal(rowSeqs(calls[2]), []int64{4, 5}) {
		t.Errorf("셋째 목록 행 = %v, want [4 5]", rowSeqs(calls[2]))
	}
}
