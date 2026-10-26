package main

// 루프의 제어면 상태 몫(설계 M5 2.1 Drain 변환 · 3.3 · 4.1 · 4.3 · 4.4 — rewind_status.go)의 시험이다. 루프는 매 holdTick
// 끝에 종료 항목과 캐시 · 발행 상태로 Board 를 새로 만들고, HTTP 고루틴은 그 Board 만 읽는다.

import (
	"context"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/status"
)

const statusStale = 30 * time.Second

// statusLoop 은 발행 몫 위에 상태 몫을 얹은 루프 픽스처다(버킷 있음 · seed 없음). 부팅 감시는 끝난 것으로 둔다(warm).
func statusLoop(t *testing.T) *publishLoop {
	t.Helper()
	f := newPublishLoop(t)
	assembleStatus(t.Context(), bucketConfig(), f.rewind, nil)
	f.rewind.booted = true
	return f
}

func (f *publishLoop) find(s string, now time.Time) (status.Status, status.Answer) {
	return f.rewind.board.Load().Find(s, now, statusStale)
}

func (f *publishLoop) findRound(s, r string, now time.Time) (status.Status, status.Answer, bool) {
	return f.rewind.board.Load().FindRound(s, r, now, statusStale)
}

// 2.1절 Drain 변환: Unknown 은 지우고 · Ending · VOD · Ended 는 덮고 · Live · 영값 · 빈 VOD 는 버리고 WARN 이다. 반영한
// Status 는 FromPlaybackState(회차 경로 RoundLookup 이 쓰는 함수)의 결과와 같다 — 표 하나를 두 호출 자리가 공유한다.
func TestDrainAppliesM6States(t *testing.T) {
	vod := index.PlaybackState{Phase: index.PhaseVOD, RoundID: "A", LatestSessionID: "SA", VODKey: "vod/s/A/index.m3u8",
		FirstPDT: loopBase, HeadPDT: loopBase.Add(time.Hour)}
	for _, tt := range []struct {
		name   string
		post   index.PlaybackState
		apply  bool   // 종료 항목이 이 값으로 바뀌는가
		remove bool   // 종료 항목이 지워지는가
		reason string // 버릴 때의 rewind_final_invalid reason
	}{
		{name: "ending", post: index.PlaybackState{Phase: index.PhaseEnding, RoundID: "A", LatestSessionID: "SA"}, apply: true},
		{name: "ended", post: index.PlaybackState{Phase: index.PhaseEnded, RoundID: "A", LatestSessionID: "SA"}, apply: true},
		{name: "vod", post: vod, apply: true},
		{name: "unknown", post: index.PlaybackState{Phase: index.PhaseUnknown}, remove: true},
		{name: "live", post: index.PlaybackState{Phase: index.PhaseLive, RoundID: "A", LatestSessionID: "SA"}, reason: "live_not_from_m6"},
		{name: "영값", post: index.PlaybackState{}, reason: status.ReasonOutOfRange},
		{name: "빈_vod", post: index.PlaybackState{Phase: index.PhaseVOD, RoundID: "A"}, reason: status.ReasonVODIncomplete},
	} {
		t.Run(tt.name, func(t *testing.T) {
			f := statusLoop(t)
			old := status.Final{RoundID: "Z", LatestSessionID: "SZ", Status: status.Status{Mode: status.ModeEnded}}
			f.rewind.final["s"] = old
			f.rewind.finals.Post("s", tt.post)
			f.hold(loopBase)
			got, ok := f.rewind.final["s"]
			switch {
			case tt.apply:
				want, _, err := status.FromPlaybackState(tt.post, f.rewind.baseURL)
				if err != nil || !ok || got.Status != want || got.RoundID != tt.post.RoundID || got.LatestSessionID != tt.post.LatestSessionID {
					t.Errorf("final = %+v(%v), want {%s %s %+v}", got, ok, tt.post.RoundID, tt.post.LatestSessionID, want)
				}
				if a := f.logs.attrs("rewind_final_applied"); a["source"] != "m6" || a["mode"] != string(want.Mode) || a["removed"] != false {
					t.Errorf("rewind_final_applied = %v, want source=m6 mode=%s removed=false", a, want.Mode)
				}
			case tt.remove:
				if ok {
					t.Errorf("Unknown 뒤 final = %+v, want 없음", got)
				}
				if a := f.logs.attrs("rewind_final_applied"); a["removed"] != true || a["reason"] != "unknown" {
					t.Errorf("rewind_final_applied = %v, want removed=true reason=unknown", a)
				}
			default:
				if got != old {
					t.Errorf("버릴 값 뒤 final = %+v, want 그대로 %+v", got, old)
				}
				f.rewind.finals.Post("s", tt.post) // 같은 값을 다시 — WARN 은 한 번
				f.hold(loopBase.Add(time.Second))
				a := f.logs.attrs("rewind_final_invalid")
				if f.logs.countLevel(slog.LevelWarn, "rewind_final_invalid") != 1 || a["reason"] != tt.reason || a["stream_id"] != "s" {
					t.Errorf("rewind_final_invalid = %v(%d줄), want reason=%s 한 줄", a, f.logs.count("rewind_final_invalid"), tt.reason)
				}
			}
		})
	}
}

// 착수 체크리스트 ⓕ: rewind_final_invalid 의 phase 는 글자다(phase=live).
func TestDrainInvalidPhaseIsText(t *testing.T) {
	f := statusLoop(t)
	f.rewind.finals.Post("s", index.PlaybackState{Phase: index.PhaseLive})
	f.hold(loopBase)
	if got := f.logs.attrs("rewind_final_invalid")["phase"]; got != "live" {
		t.Errorf("phase = %#v, want \"live\"", got)
	}
}

// endedLedger 는 회차 sid 가 끝난(ending) 스트림 s 의 장부다.
func endedLedger(s, sid string) index.RewindLedger {
	l := liveLedger(s, sid, 1, 1)
	l.Sessions[0].State = "ending"
	return l
}

// forgetAt 은 s1 의 뷰(끝난 회차 sid)를 세우고 감시가 그것을 버리게 한다 — 버린 감시 뒤의 시각을 돌려준다. 버린 감시는
// 그 holdTick 의 publishStatus 뒤에 반영되므로 Board 반영은 다음 holdTick 부터다.
func forgetAt(f *publishLoop, sid string) time.Time {
	f.t.Helper()
	f.watchEveryHold()
	f.boot("s1")
	tailEnd := f.wall(8 * time.Second)
	f.put("s1", endedLedger("s1", sid))
	f.hold(tailEnd)
	f.settleWatch(tailEnd, f.watched+1)
	gone := tailEnd.Add(301 * time.Second)
	f.hold(gone)
	f.settleWatch(gone, f.watched+1)
	if len(f.cache.Streams()) != 0 {
		f.t.Fatalf("301초 감시 뒤에도 캐시가 %v 를 든다 — Forget 이 서지 않았다", f.cache.Streams())
	}
	return gone
}

// R41 · 3.3절: Forget 한 감시 뒤 다음 holdTick 스냅숏은 mode:ending 이다(소유 회차를 LatestSessionID 로 · RoundID 빈 값 —
// 회차 경로는 hit 하지 않는다). M6 Live 값을 섞어도 같고 WARN 한 줄이다.
func TestForgetLeavesProvisionalEnding(t *testing.T) {
	f := statusLoop(t)
	gone := forgetAt(f, "S")
	f.rewind.finals.Post("s1", index.PlaybackState{Phase: index.PhaseLive, RoundID: "R", LatestSessionID: "S"})
	next := gone.Add(time.Second)
	f.hold(next)
	if st, answer := f.find("s1", next); answer != status.Found || st.Mode != status.ModeEnding || st.Ready {
		t.Errorf("Forget 다음 스냅숏 = (%+v, %v), want ending Found", st, answer)
	}
	if got := f.rewind.final["s1"]; got.LatestSessionID != "S" || got.RoundID != "" {
		t.Errorf("잠정 항목 = %+v, want LatestSessionID=S · RoundID 빈 값", got)
	}
	if a := f.logs.attrs("rewind_final_applied"); a["source"] != "forget_provisional" || a["latest_session_id"] != "S" {
		t.Errorf("rewind_final_applied = %v, want source=forget_provisional latest_session_id=S", a)
	}
	if f.logs.count("rewind_final_invalid") != 1 {
		t.Errorf("rewind_final_invalid = %d줄, want 1(live_not_from_m6)", f.logs.count("rewind_final_invalid"))
	}
}

// 3.3절 Forget 덮기 규칙: 같은 회차(LatestSessionID == 소유 회차)의 종료 항목은 그대로 두고, 다른 회차의 것은 덮는다.
func TestForgetKeepsSameRoundFinal(t *testing.T) {
	for _, tt := range []struct {
		name      string
		latest    string
		wantMode  status.Mode
		wantRound string
	}{
		{"같은_회차_vod_유지", "S", status.ModeVOD, "R"},
		{"옛_회차_덮음", "OLD", status.ModeEnding, ""},
	} {
		t.Run(tt.name, func(t *testing.T) {
			f := statusLoop(t)
			f.rewind.final["s1"] = status.Final{RoundID: "R", LatestSessionID: tt.latest,
				Status: status.Status{Ready: true, Mode: status.ModeVOD, URL: "https://media.example.com/vod/x"}}
			forgetAt(f, "S")
			if got := f.rewind.final["s1"]; got.Status.Mode != tt.wantMode || got.RoundID != tt.wantRound {
				t.Errorf("Forget 뒤 final = %+v, want mode=%s round=%q", got, tt.wantMode, tt.wantRound)
			}
		})
	}
}

// R27: 같은 스트림이 Forget 을 되풀이해도 종료 항목은 하나다(스트림 키 수에 갇힌다 — 기록).
func TestForgetRepeatedKeepsOneEntry(t *testing.T) {
	f := statusLoop(t)
	now := forgetAt(f, "S1")
	for _, sid := range []string{"S2", "S3"} {
		f.put("s1", endedLedger("s1", sid))
		now = now.Add(time.Second)
		f.hold(now)
		f.settleWatch(now, f.watched+1)
		now = now.Add(301 * time.Second)
		f.hold(now)
		f.settleWatch(now, f.watched+1)
	}
	if len(f.rewind.final) != 1 || f.rewind.final["s1"].LatestSessionID != "S3" {
		t.Errorf("final = %+v, want s1 하나(S3)", f.rewind.final)
	}
}

// R51 · 경계 확정 1: 캐시 소유 회차가 새 live 면 매 틱 종료 항목을 지운다 — 늦게 온 옛 회차 값이 Drain 으로 덮여도 같은 틱
// 끝에서 지워지고, 그 틱 스냅숏은 live 이며 옛 회차의 회차 조회는 Miss 다.
func TestSessionOpenedDeletesFinalEveryTick(t *testing.T) {
	f := statusLoop(t)
	f.rewind.final["s"] = status.Final{RoundID: "A", LatestSessionID: "SA", Status: status.Status{Mode: status.ModeEnded}}
	f.put("s", liveLedger("s", "SB", 1, 1))
	f.hold(loopBase)
	if _, ok := f.rewind.final["s"]; ok {
		t.Fatal("새 회차 개시 틱 뒤에도 종료 항목이 남았다")
	}
	if a := f.logs.attrs("rewind_final_applied"); a["removed"] != true || a["reason"] != "session_opened" {
		t.Errorf("rewind_final_applied = %v, want removed=true reason=session_opened", a)
	}
	f.rewind.finals.Post("s", index.PlaybackState{Phase: index.PhaseEnded, RoundID: "A", LatestSessionID: "SA"})
	next := loopBase.Add(time.Second)
	f.hold(next)
	if _, ok := f.rewind.final["s"]; ok {
		t.Error("늦게 온 옛 회차 값이 개시 뒤 틱 끝에도 남았다 — 개시 삭제를 매 틱 평가하지 않는다")
	}
	if st, _ := f.find("s", next); st.Mode != status.ModeLive {
		t.Errorf("그 틱 스트림 경로 mode = %q, want live", st.Mode)
	}
	if _, answer, hit := f.findRound("s", "A", next); hit || answer != status.Miss {
		t.Errorf("FindRound(s, A) = (%v, %v), want Miss", answer, hit)
	}
}

// 4.2 · 4.3절: 소유 회차 live ∧ init 확정 ∧ 발행 판이 있으면 ready:true · url = 그 회차 목록 · 범위 = 발행 판의 첫 · 끝 행.
func TestPublishStatusLiveReady(t *testing.T) {
	f := statusLoop(t)
	f.put("s", liveLedger("s", "S", 5, 3))
	if st, _ := f.publishedOnce("s", 3); !st.Ready {
		t.Fatalf("발행 뒤 스냅숏 = %+v, want ready", st)
	}
	st, _ := f.find("s", loopBase.Add(time.Second))
	prev := f.rewind.streams["s"].pub.Prev
	wantFirst := segRow("s", "S", prev.MediaSequence, true).PlaybackPDT
	last := segRow("s", "S", prev.PublishedSeq, true)
	want := status.Status{Ready: true, Mode: status.ModeLive, URL: "https://media.example.com/dvr/s/S/index.m3u8",
		FirstPDT: wantFirst, HeadPDT: last.PlaybackPDT.Add(4 * time.Second)}
	if st != want {
		t.Errorf("스냅숏 = %+v, want %+v", st, want)
	}
}

// publishedOnce 는 회차 S 의 ③ seq 업로드 원소로 첫 발행을 받고 다음 holdTick 의 스냅숏을 돌려준다.
func (f *publishLoop) publishedOnce(s string, seq int64) (status.Status, status.Answer) {
	f.t.Helper()
	f.uploaded(s, "S", seq)
	f.hold(loopBase)
	f.settle(loopBase)
	f.hold(loopBase.Add(time.Second))
	return f.find(s, loopBase.Add(time.Second))
}

// 4.3절: 발행 판의 행이 뷰에 다 있지 않으면(끝 행이 빠짐) ready 를 거짓으로 두고 WARN rewind_status_range_missing 을
// 회차당 한 번 남긴다. PDT NULL 행은 발행 전 검사(S7)가 첫 자리 말고는 막으므로 같은 갈래(PDTRange 거짓)로 덮는다.
func TestPublishStatusRangeMissingWarnsOnce(t *testing.T) {
	f := statusLoop(t)
	f.put("s", liveLedger("s", "S", 5, 3))
	if st, _ := f.publishedOnce("s", 3); !st.Ready {
		t.Fatalf("발행 뒤 스냅숏 = %+v, want ready", st)
	}
	prev := *f.rewind.streams["s"].pub.Prev
	prev.PublishedSeq = 99 // 뷰에 없는 끝 행
	f.rewind.streams["s"].pub.Prev = &prev
	for i := range 2 {
		now := loopBase.Add(time.Duration(2+i) * time.Second)
		f.hold(now)
		if st, _ := f.find("s", now); st.Ready || st.Mode != status.ModeLive {
			t.Errorf("범위 빠짐 스냅숏 = %+v, want ready:false · live", st)
		}
	}
	a := f.logs.attrs("rewind_status_range_missing")
	if f.logs.countLevel(slog.LevelWarn, "rewind_status_range_missing") != 1 || a["session_id"] != "S" || a["to"] != int64(99) {
		t.Errorf("rewind_status_range_missing = %v(%d줄), want session_id=S to=99 한 줄", a, f.logs.count("rewind_status_range_missing"))
	}
}

// 3.2절: 부팅 감시 전에는 모르는 스트림이 503, 뒤에는 404 다.
func TestPublishStatusWarmAfterBootWatch(t *testing.T) {
	f := statusLoop(t)
	f.rewind.booted = false
	f.hold(loopBase)
	if _, answer := f.find("x", loopBase); answer != status.Unavailable {
		t.Errorf("부팅 감시 전 모르는 스트림 = %v, want Unavailable", answer)
	}
	f.rewind.booted = true
	f.hold(loopBase.Add(time.Second))
	if _, answer := f.find("x", loopBase.Add(time.Second)); answer != status.NotFound {
		t.Errorf("부팅 감시 뒤 모르는 스트림 = %v, want NotFound", answer)
	}
}

// degradedLoop 은 강등 루프다 — 버킷은 있는데 발행자 조립이 실패해 발행 몫 · 캐시가 없다(보완 2 ㉢).
func degradedLoop(t *testing.T, seed statusSeed) *offlineLoop {
	t.Helper()
	f := newOfflineLoop(t, nil)
	f.rewind.cache = nil
	assembleStatus(t.Context(), bucketConfig(), f.rewind, seed)
	return f
}

func (f *offlineLoop) find(s string, now time.Time) (status.Status, status.Answer) {
	return f.rewind.board.Load().Find(s, now, statusStale)
}

// R59 ①: 강등에서도 Board 는 매 틱 새로 서고 warm 이라 30초 뒤에도 모르는 스트림은 404(503 아님)다. M6 가 보낸 종료 항목은
// 스트림 경로 mode:ended · 회차 경로 hit 다.
func TestDegradedBoardStaysFreshAndServesM6(t *testing.T) {
	f := degradedLoop(t, nil)
	f.rewind.holdTick(t.Context(), loopBase)
	f.rewind.finals.Post("s", index.PlaybackState{Phase: index.PhaseEnded, RoundID: "A", LatestSessionID: "SA"})
	later := loopBase.Add(31 * time.Second)
	f.rewind.holdTick(t.Context(), later)
	if _, answer := f.find("x", later); answer != status.NotFound {
		t.Errorf("강등 31초 뒤 모르는 스트림 = %v, want NotFound", answer)
	}
	if st, _ := f.find("s", later); st.Mode != status.ModeEnded {
		t.Errorf("강등 스트림 경로 mode = %q, want ended", st.Mode)
	}
	if _, answer, hit := f.rewind.board.Load().FindRound("s", "A", later, statusStale); !hit || answer != status.Found {
		t.Errorf("강등 회차 경로 = (%v, %v), want hit Found", answer, hit)
	}
}

// R59 ②: 강등 + 재기동 적재(Ended) → 첫 holdTick 스냅숏이 mode:ended · 회차 hit 이다.
func TestDegradedSeedServesEnded(t *testing.T) {
	seed := func(context.Context) (map[string]index.PlaybackState, error) {
		return map[string]index.PlaybackState{"s": {Phase: index.PhaseEnded, RoundID: "A", LatestSessionID: "SA"}}, nil
	}
	f := degradedLoop(t, seed)
	f.rewind.holdTick(t.Context(), loopBase)
	if st, _ := f.find("s", loopBase); st.Mode != status.ModeEnded {
		t.Errorf("강등 + 적재 스트림 경로 mode = %q, want ended", st.Mode)
	}
	if _, _, hit := f.rewind.board.Load().FindRound("s", "A", loopBase, statusStale); !hit {
		t.Error("강등 + 적재 회차 경로가 hit 하지 않는다")
	}
}

// R59 ③: 꺼짐 + M6 Post → 패닉 없음 · 스트림 경로 404(DisabledBoard 그대로).
func TestDisabledIgnoresPostsWithoutPanic(t *testing.T) {
	f := newOfflineLoop(t, nil)
	f.rewind.cache = nil
	assembleStatus(t.Context(), config.Config{}, f.rewind, nil) // 꺼짐
	f.rewind.finals.Post("s", index.PlaybackState{Phase: index.PhaseEnded, RoundID: "A", LatestSessionID: "SA"})
	f.rewind.holdTick(t.Context(), loopBase)
	if _, answer := f.find("s", loopBase.Add(time.Hour)); answer != status.NotFound {
		t.Errorf("꺼짐 스트림 경로 = %v, want NotFound", answer)
	}
}

// cx c3 M1 · 3.3절 「꺼짐과 강등」: 꺼짐에서는 Drain 이 받은편지함만 비우고 종료 항목에 반영하지 않는다 — Board 가 늘
// 404 인데 서로 다른 스트림의 종료 값이 루프 메모리에 쌓이지 않게. 무효 값의 WARN 도 쓰지 않는다.
func TestDisabledDrainDiscardsFinals(t *testing.T) {
	f := newOfflineLoop(t, nil)
	f.rewind.cache = nil
	assembleStatus(t.Context(), config.Config{}, f.rewind, nil) // 꺼짐
	f.rewind.finals.Post("s", index.PlaybackState{Phase: index.PhaseEnded, RoundID: "A", LatestSessionID: "SA"})
	f.rewind.finals.Post("t", index.PlaybackState{Phase: index.PhaseLive, RoundID: "B", LatestSessionID: "SB"})
	f.rewind.holdTick(t.Context(), loopBase)
	if n := len(f.rewind.final); n != 0 {
		t.Errorf("꺼짐 Drain 뒤 종료 항목 = %d, want 0(버림)", n)
	}
	if left := f.rewind.finals.Drain(); len(left) != 0 {
		t.Errorf("꺼짐 Drain 뒤 받은편지함 = %v, want 빔", left)
	}
	if n := len(f.rewind.invalid); n != 0 {
		t.Errorf("꺼짐 Drain 뒤 무효 기록 = %d, want 0", n)
	}
}

// R60(한계 고정): 강등은 캐시가 없어 개시 삭제가 서지 않는다 — 같은 키로 새 방송이 송출돼도 스트림 경로는 지난 방송 A 의
// vod 를 낸다. 이 동작이 바뀌면 계약3 7-2 단서도 고친다.
func TestDegradedKeepsPreviousRoundFinal(t *testing.T) {
	f := degradedLoop(t, nil)
	f.rewind.finals.Post("s1", index.PlaybackState{Phase: index.PhaseVOD, RoundID: "A", LatestSessionID: "SA",
		VODKey: "vod/s1/A/index.m3u8", FirstPDT: loopBase, HeadPDT: loopBase.Add(time.Hour)})
	f.handle(f.makeFile("s1", f.t0), recording.ReasonNextFile) // 새 방송 B 의 조각
	f.rewind.holdTick(t.Context(), f.t0.Add(time.Second))
	if st, _ := f.find("s1", f.t0.Add(time.Second)); st.Mode != status.ModeVOD {
		t.Errorf("강등 중 새 송출의 스트림 경로 mode = %q, want vod(A — 한계)", st.Mode)
	}
}

// streamFor 는 스트림 s 에 base 부터 4초 간격 조각 n 개를 넣는다 — 커서 꼬리 끝이 base + 4n 초 근처라 그때까지 송출 중이다.
func (f *publishLoop) streamFor(s string, base time.Time, from, n int) {
	f.t.Helper()
	for i := from; i < from+n; i++ {
		f.handle(f.makeFile(s, base.Add(time.Duration(i)*4*time.Second)), recording.ReasonNextFile)
	}
}

// R31 · 4.4절: 컷오프 없는 스트림(부정 표식)이 송출 중인 채 60초를 넘기면 WARN rewind_ready_delayed value=1
// reason=no_cutoff 한 번이고, 송출이 멈추면 WARN value=0 reason=ingest_stopped 다. 「송출 중」은 IngestStopped 한 술어다.
func TestReadyDelayedAlarmForNoCutoffStream(t *testing.T) {
	f := statusLoop(t)
	f.work.ledgers["s"] = index.RewindLedger{} // 컷오프 없음
	f.cache.DemandLoad("s", nil)
	base := f.t0
	f.streamFor("s", base, 0, 1)
	f.hold(base.Add(4 * time.Second))
	f.settle(base.Add(4 * time.Second))
	for i := 1; i <= 17; i++ { // 4초마다 조각 하나 — 68초 동안 송출 중
		f.streamFor("s", base, i, 1)
		f.hold(base.Add(time.Duration(i) * 4 * time.Second))
	}
	if f.logs.countLevel(slog.LevelWarn, "rewind_ready_delayed") != 1 {
		t.Fatalf("rewind_ready_delayed WARN = %d줄, want 1", f.logs.countLevel(slog.LevelWarn, "rewind_ready_delayed"))
	}
	if a := f.logs.attrs("rewind_ready_delayed"); a["value"] != int64(1) || a["reason"] != "no_cutoff" || a["stream_id"] != "s" {
		t.Errorf("rewind_ready_delayed = %v, want value=1 reason=no_cutoff stream_id=s", a)
	}
	f.hold(base.Add(17*4*time.Second + time.Minute)) // 꼬리 끝 + 3 × 4초를 넘김
	if a := f.logs.attrs("rewind_ready_delayed"); a["value"] != int64(0) || a["reason"] != "ingest_stopped" {
		t.Errorf("송출 정지 뒤 rewind_ready_delayed = %v, want value=0 reason=ingest_stopped", a)
	}
}

// 4.4절: 송출 중이어도 60초 안이면 세우지 않는다.
func TestReadyDelayedAlarmWaitsSixtySeconds(t *testing.T) {
	f := statusLoop(t)
	f.work.ledgers["s"] = index.RewindLedger{}
	f.cache.DemandLoad("s", nil)
	base := f.t0
	f.streamFor("s", base, 0, 1)
	f.hold(base.Add(4 * time.Second))
	f.settle(base.Add(4 * time.Second))
	for i := 1; i <= 14; i++ { // 56초
		f.streamFor("s", base, i, 1)
		f.hold(base.Add(time.Duration(i) * 4 * time.Second))
	}
	if n := f.logs.count("rewind_ready_delayed"); n != 0 {
		t.Errorf("56초 안 rewind_ready_delayed = %d줄, want 0", n)
	}
}

// raiseNoCutoffAlarm 은 컷오프 없는 스트림 s 를 68초 송출해 rewind_ready_delayed 를 세우고 마지막 틱 시각을 돌려준다.
func (f *publishLoop) raiseNoCutoffAlarm() time.Time {
	f.t.Helper()
	f.work.ledgers["s"] = index.RewindLedger{}
	f.cache.DemandLoad("s", nil)
	base := f.t0
	f.streamFor("s", base, 0, 1)
	f.hold(base.Add(4 * time.Second))
	f.settle(base.Add(4 * time.Second))
	last := base
	for i := 1; i <= 17; i++ {
		f.streamFor("s", base, i, 1)
		last = base.Add(time.Duration(i) * 4 * time.Second)
		f.hold(last)
	}
	if n := f.logs.countLevel(slog.LevelWarn, "rewind_ready_delayed"); n != 1 {
		f.t.Fatalf("세움 rewind_ready_delayed WARN = %d줄, want 1", n)
	}
	return last
}

// 하네스 축 「관측 신호 등급 set/clear 대칭」: 내림은 세움과 같은 WARN 이다. LOG_LEVEL=warn 으로 INFO 를 걸러도 세 갈래
// (준비됨 · 송출 정지 · Forget) 모두 value=0 줄이 남아야 로그 게이지가 세움에 굳지 않는다 — 내림 뒤 상태를 지워 다시
// 쓰지 않기 때문이다.
func TestReadyDelayedClearKeepsWarnLevel(t *testing.T) {
	for _, tt := range []struct {
		name   string
		reason string
		clear  func(f *publishLoop, last time.Time)
	}{
		{name: "준비됨", reason: "ready", clear: func(f *publishLoop, last time.Time) {
			f.rewind.observeReady("s", true, "", nil, last)
		}},
		{name: "송출_정지", reason: "ingest_stopped", clear: func(f *publishLoop, last time.Time) {
			f.hold(last.Add(time.Minute)) // 꼬리 끝 + 3 × 4초를 넘김
		}},
		{name: "forget", reason: "ingest_stopped", clear: func(f *publishLoop, last time.Time) {
			f.rewind.dropAlarmsOutside(nil, last) // 캐시가 더는 들지 않는 스트림
		}},
	} {
		t.Run(tt.name, func(t *testing.T) {
			f := statusLoop(t)
			f.logs.warnOnly.Store(true)
			tt.clear(f, f.raiseNoCutoffAlarm())
			if n := f.logs.countLevel(slog.LevelWarn, "rewind_ready_delayed"); n != 2 {
				t.Fatalf("WARN 만 받을 때 rewind_ready_delayed = %d줄, want 2(세움 · 내림)", n)
			}
			if a := f.logs.attrs("rewind_ready_delayed"); a["value"] != int64(0) || a["reason"] != tt.reason {
				t.Errorf("내림 줄 = %v, want value=0 reason=%s", a, tt.reason)
			}
		})
	}
}

// R1 · R54 경합: 처리기 100 동시 요청과 루프의 publishStatus 반복(종료 항목을 매 틱 바꿈)이 함께 돌아도 경합이 없다 —
// 처리기는 Holder 의 불변 Board 만 읽는다(-race).
func TestHandlersRaceFreeWithPublishStatus(t *testing.T) {
	f := statusLoop(t)
	lookup := func(context.Context, string, string) (status.Status, status.Answer, error) {
		return status.Status{Mode: status.ModeEnded}, status.Found, nil
	}
	cfg := controlConfig(1000)
	stop := startControl(cfg, f.rewind.board, slog.New(f.logs), lookup)
	defer stop()
	base := startedAddr(t, f.logs)
	var wg sync.WaitGroup
	done := make(chan struct{})
	for i := range 100 {
		wg.Go(func() {
			for {
				select {
				case <-done:
					return
				default:
				}
				if i%2 == 0 {
					httpGet(t, base+"/api/streams/s/rewind")
				} else {
					httpGet(t, base+"/api/streams/s/rounds/A/rewind")
				}
			}
		})
	}
	now := time.Now()
	for i := range 2000 {
		phase := index.PhaseEnded
		if i%2 == 0 {
			phase = index.PhaseEnding
		}
		f.rewind.finals.Post("s", index.PlaybackState{Phase: phase, RoundID: "A", LatestSessionID: "SA"})
		f.rewind.holdTick(t.Context(), now.Add(time.Duration(i)*time.Millisecond))
	}
	close(done)
	wg.Wait()
}
