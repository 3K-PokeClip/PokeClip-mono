package main

// 정산 루프 배선 — holdTick 순서 · 꺼짐/강등 · 동시 상한 · 상태 전달 순서 · 계승 해제 · 빠른 길 · 종료 대기(POK-196 M6-3b
// 커밋 ③ — 설계 M6 3.2 · 3.4 계승 해제 · 9절 D2 · D24 · D25 · D33 · D55 · D56 · D65 · D66).

import (
	"context"
	"errors"
	"log/slog"
	"slices"
	"sync/atomic"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/finalize"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
)

// D56 (가) — 발행 꺼짐(S3_BUCKET 빈 값 · 발행자 없음)에서도 정산 스캔 · 걸음이 돈다. 차선 · 스캔 주기는 조립 밖에서
// 채워지고 완료 case 채널은 lanes 가 있으면 열린다. 변이 = finalizeTick 을 발행 몫 조기 반환 뒤로 · done() 을
// publishing() 조건으로 → FAIL.
func TestFinalizeRunsWithPublishingOffD56(t *testing.T) {
	f := newFinalLoop(t, finalize.ModeOff)
	f.work.scanRows = []index.EndingSession{endingRow("S1", nil)}
	if f.rewind.publishing() {
		t.Fatal("픽스처가 발행 몫을 가졌다 — 꺼짐이어야 한다")
	}
	f.hold(loopBase)
	if f.rewind.done() == nil {
		t.Fatal("꺼짐에서 완료 case 채널이 nil 이다 — 정산 결과를 받을 수 없다")
	}
	f.settle(loopBase)
	if got := f.work.stepsSeen(); !slices.Equal(got, []finalize.Step{finalize.StepEmptyFreezeDVR}) {
		t.Fatalf("꺼짐 걸음 = %v, want [empty_freeze_dvr]", got)
	}
}

// D56 (나) · D72 — 발행 강등이면 S3 걸음을 내지 않고 보류하며 게이지 finalize_held reason=publish_degraded 를 올린다.
// 꺼짐 · 흔적 있음도 보류(publish_off_with_trace)다. 판별은 설정값(Mode)이다 — 발행자 nil 로 가르면 강등이 빈 동결로 닫힌다.
func TestFinalizeHoldsDegradedAndTracedSessionsD56(t *testing.T) {
	tests := []struct {
		name   string
		mode   finalize.Mode
		trace  bool
		reason string
	}{
		{"강등", finalize.ModeDegraded, false, finalize.HoldPublishDegraded},
		{"꺼짐_흔적", finalize.ModeOff, true, finalize.HoldPublishOffWithTrace},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f := newFinalLoop(t, tt.mode)
			f.work.scanRows = []index.EndingSession{endingRow("S1", func(s *index.EndingSession) { s.HasPublishTrace = tt.trace })}
			f.hold(loopBase)
			f.settle(loopBase)
			if got := f.work.stepsSeen(); len(got) != 0 {
				t.Fatalf("보류인데 걸음을 냈다: %v", got)
			}
			rec := f.logs.last("finalize_held")
			if rec == nil || rec.level != slog.LevelWarn || rec.attrs["reason"] != tt.reason || rec.attrs["value"] != int64(1) {
				t.Fatalf("finalize_held = %+v, want WARN reason=%s value=1", rec, tt.reason)
			}
		})
	}
}

// 판별자는 설정값이다(3.2) — 버킷이 비면 꺼짐, 버킷이 있는데 발행자 조립이 실패하면 강등, 성공이면 정상.
func TestFinalizeModeFromConfig(t *testing.T) {
	tests := []struct {
		bucket     string
		publishing bool
		want       finalize.Mode
	}{
		{"", false, finalize.ModeOff},
		{"b", false, finalize.ModeDegraded},
		{"b", true, finalize.ModePublishing},
	}
	for _, tt := range tests {
		if got := finalizeMode(tt.bucket, tt.publishing); got != tt.want {
			t.Errorf("finalizeMode(%q, %v) = %v, want %v", tt.bucket, tt.publishing, got, tt.want)
		}
	}
}

// D25 — 옛 ending 수백 건이 한꺼번에 와도 정산 걸음은 동시 4 개 · VOD 동결은 1 개까지다. 나머지는 다음 스캔 · 빠른 길이
// 이어 간다. 변이 = 동시 상한 제거 → 최대 동시 수 단언 FAIL.
func TestFinalizeStepsRespectConcurrencyLimitsD25(t *testing.T) {
	f := newFinalLoop(t, finalize.ModePublishing)
	for i := range 10 {
		id := string(rune('a' + i))
		f.work.scanRows = append(f.work.scanRows, endingRow(id, func(s *index.EndingSession) {
			if i%2 == 0 {
				s.DVRState, s.VODState = "frozen", "freezing"
			}
		}))
	}
	f.work.gate = make(chan struct{})
	f.hold(loopBase)
	f.next(loopBase) // 스캔 결과
	time.Sleep(50 * time.Millisecond)
	close(f.work.gate)
	f.settle(loopBase)
	f.work.mu.Lock()
	defer f.work.mu.Unlock()
	if f.work.maxFly > finalize.DefaultLimits.Total || f.work.maxVODFly > finalize.DefaultLimits.VOD {
		t.Fatalf("동시 걸음 최대 %d · VOD %d, want ≤ %d · ≤ %d", f.work.maxFly, f.work.maxVODFly,
			finalize.DefaultLimits.Total, finalize.DefaultLimits.VOD)
	}
	if f.work.maxFly < finalize.DefaultLimits.Total {
		t.Fatalf("동시 걸음 최대 %d — 상한까지 내지 않았다(시험이 상한을 재지 못한다)", f.work.maxFly)
	}
}

// 재시도는 4초 뒤 · 정산 대기(unsettled)는 세지 않고 다음 스캔이 다시 낸다 · 장애 3회면 다음 단(사다리)이다.
func TestFinalizeRetriesAfterBackoffAndCountsAttempts(t *testing.T) {
	f := newFinalLoop(t, finalize.ModePublishing)
	row := endingRow("S1", nil)
	f.work.scanRows = []index.EndingSession{row}
	f.work.rows["S1"] = row
	f.work.stepOut = func(index.EndingSession, finalize.Decision) finalize.Outcome { return finalize.OutcomeRetry }
	now := loopBase
	for range 13 { // 0 · 4 · 8 · 12초
		f.hold(now)
		f.settle(now)
		now = now.Add(time.Second)
	}
	got := f.work.stepsSeen()
	want := []finalize.Step{finalize.StepFreezeDVR, finalize.StepFreezeDVR, finalize.StepFreezeDVR, finalize.StepSealDVR}
	if !slices.Equal(got, want) {
		t.Fatalf("12초 동안 걸음 = %v, want %v(4초 간격 · 1단 3회 뒤 2단)", got, want)
	}
	if rec := f.logs.last("session_freeze_retry"); rec == nil || rec.level != slog.LevelWarn || rec.attrs["axis"] != "dvr" {
		t.Fatalf("session_freeze_retry = %+v, want WARN axis=dvr", rec)
	}
}

// D66 — 감시와 정산 스캔이 같은 틱에 나도 둘 다 돈다(스캔은 예약 키 "scan:finalize" 의 스트림 차선 · 감시는 프로세스 차선).
// 같은 차선이면 병합 표지가 키당 하나라 한쪽이 버려진다. 변이 = 스캔을 StartProcess 로 → 스캔 결과 수 FAIL.
func TestFinalizeScanAndWatchBothRunEachCycleD66(t *testing.T) {
	f := newFinalLoop(t, finalize.ModeOff)
	watched := 0
	f.rewind.lanes = f.lanes
	now := loopBase
	for range 3 {
		f.lanes.StartProcess(t.Context(), func(context.Context) publish.Result { return publish.Result{Kind: publish.KindWatch} })
		f.hold(now)
		for f.rewind.fin.busy() || watched < 1 {
			select {
			case res := <-f.rewind.done():
				if res.Kind == publish.KindWatch {
					f.lanes.Finish(res)
					watched++
					continue
				}
				f.rewind.complete(t.Context(), res, now)
			case <-time.After(5 * time.Second):
				t.Fatal("결과가 오지 않았다")
			}
		}
		watched = 0
		now = now.Add(time.Second)
	}
	f.work.mu.Lock()
	defer f.work.mu.Unlock()
	if f.work.scans != 3 {
		t.Fatalf("세 주기의 스캔 = %d, want 3", f.work.scans)
	}
}

// D65 — 같은 스트림 상태 전달은 커밋 순서대로 마지막 값이 이긴다. 읽기가 도는 동안 온 표지는 병합되고, 결과를 받은 뒤
// 새로 읽는다(Finish 재실행). 변이 = 재실행 무시 → 마지막 sink 값이 옛 판이라 FAIL.
func TestPlaybackDeliveryRerunsAfterMergedMarkD65(t *testing.T) {
	f := newFinalLoop(t, finalize.ModeOff)
	var version atomic.Int32
	version.Store(1)
	f.work.playback = func(string) (index.PlaybackState, bool, error) {
		latest := map[int32]string{1: "S1", 2: "S2"}[version.Load()]
		return index.PlaybackState{Phase: index.PhaseEnding, RoundID: "R", LatestSessionID: latest}, true, nil
	}
	gate := make(chan struct{})
	f.work.readGate = gate
	f.inbox.Changed("demo")
	f.hold(loopBase) // 첫 읽기가 판 1 을 읽고 선다
	time.Sleep(20 * time.Millisecond)
	version.Store(2) // 판 2 커밋 — 표지
	f.inbox.Changed("demo")
	f.hold(loopBase.Add(time.Second)) // 도는 읽기에 병합된다
	close(gate)
	f.settle(loopBase.Add(time.Second))
	sent := f.sunk.all()
	if len(sent) == 0 || sent[len(sent)-1].st.LatestSessionID != "S2" {
		t.Fatalf("보낸 값 = %+v, want 마지막이 판 2(S2)", sent)
	}
	if rec := f.logs.last("playback_state_sent"); rec == nil || rec.attrs["stream_id"] != "demo" {
		t.Fatalf("playback_state_sent = %+v", rec)
	}
}

// D33 — live 회차는 보내지 않는다(LoadStreamPlayback ok=false) · 읽기 실패는 WARN 뒤 표지를 되돌려 다음 틱에 다시 읽는다.
func TestPlaybackDeliverySkipsLiveAndRetriesFailuresD33(t *testing.T) {
	f := newFinalLoop(t, finalize.ModeOff)
	fail := true
	f.work.playback = func(string) (index.PlaybackState, bool, error) {
		if fail {
			return index.PlaybackState{}, false, errors.New("db 끊김")
		}
		return index.PlaybackState{}, false, nil // live
	}
	f.inbox.Changed("demo")
	f.hold(loopBase)
	f.settle(loopBase)
	if rec := f.logs.last("playback_read_failed"); rec == nil || rec.level != slog.LevelWarn {
		t.Fatalf("playback_read_failed = %+v, want WARN", rec)
	}
	fail = false
	f.hold(loopBase.Add(time.Second))
	f.settle(loopBase.Add(time.Second))
	f.work.mu.Lock()
	reads := f.work.reads
	f.work.mu.Unlock()
	if reads != 2 {
		t.Fatalf("읽기 %d 번, want 2(실패 뒤 다음 틱에 다시)", reads)
	}
	if sent := f.sunk.all(); len(sent) != 0 {
		t.Fatalf("live 회차인데 보냈다: %+v", sent)
	}
}

// D55 첫 세 갈래의 루프 몫 — 계승 해제 요청은 그 스트림 차선(발행 틱과 같은 차선)에 낸다. 풀렸으면 캐시에 반영하고, 차선이
// 돌고 있으면 미뤘다가 빈 뒤에 낸다. 발행 몫이 없으면(꺼짐 · 강등) Head 를 볼 수 없어 내지 않는다.
func TestRevokeIntentRunsOnStreamLane(t *testing.T) {
	t.Run("발행 몫 없음", func(t *testing.T) {
		f := newFinalLoop(t, finalize.ModeOff)
		f.intents.Post("demo", "S-B")
		f.hold(loopBase)
		f.settle(loopBase)
		if len(f.work.revokes) != 0 {
			t.Fatalf("발행 몫 없이 해제 작업을 냈다: %v", f.work.revokes)
		}
		if rec := f.logs.last("intentional_revoke"); rec == nil || rec.attrs["reason"] != "no_publisher" {
			t.Fatalf("intentional_revoke = %+v, want reason=no_publisher", rec)
		}
	})
	t.Run("발행 중 · 차선 바쁨이면 미룸", func(t *testing.T) {
		p := newPublishLoop(t)
		w := &fakeFinalWork{rows: map[string]index.EndingSession{}, revokeOut: publish.RevokeOutcome{Revoked: true}}
		intents := session.NewIntentInbox()
		p.rewind.holdEvery = time.Second
		p.rewind.fin = newFinalizer(finalizerOptions{Mode: finalize.ModePublishing, Every: time.Hour, CloseAfter: 360 * time.Second,
			Work: w.work(), Inbox: session.NewPlaybackInbox(), Intents: intents})
		busy := make(chan struct{})
		p.lanes.Start(t.Context(), "demo", func(ctx context.Context) publish.Result {
			<-busy
			return publish.Result{Kind: publish.KindPublish}
		})
		p.rewind.stream("demo").running = &laneJob{kind: jobRenew}
		intents.Post("demo", "S-B")
		p.rewind.holdTick(t.Context(), loopBase)
		if len(w.revokes) != 0 {
			t.Fatal("차선이 바쁜데 해제 작업을 냈다")
		}
		close(busy)
		p.settle(loopBase)
		p.rewind.holdTick(t.Context(), loopBase.Add(time.Second))
		p.settle(loopBase.Add(time.Second))
		if !slices.Equal(w.revokes, []string{"demo/S-B"}) {
			t.Fatalf("해제 작업 = %v, want [demo/S-B](차선이 빈 뒤)", w.revokes)
		}
	})
}

// 빠른 길 — 오프라인 전이가 끝낸 회차는 다음 holdTick 에 그 행을 읽어 동결을 낸다(30초 스캔을 기다리지 않음). 결과가 섰으면
// 곧바로 다음 걸음을 위해 다시 읽는다.
func TestFastPathFreezesEndedSessionWithoutScan(t *testing.T) {
	f := newFinalLoop(t, finalize.ModePublishing)
	f.rewind.fin.every = time.Hour // 스캔은 첫 틱 한 번뿐
	f.rewind.fin.queueFast("demo", "S1", loopBase)
	f.work.rows["S1"] = endingRow("S1", nil)
	f.hold(loopBase)
	f.settle(loopBase)
	if got := f.work.stepsSeen(); !slices.Contains(got, finalize.StepFreezeDVR) {
		t.Fatalf("빠른 길 걸음 = %v, want freeze_dvr", got)
	}
	if !slices.Contains(f.work.loads, "S1") {
		t.Fatalf("빠른 길이 행을 읽지 않았다: %v", f.work.loads)
	}
}

// D24 — 종료 신호 중 정산 걸음이 돌고 있으면, 종료 순서(stop)는 차선 ctx 를 끊고 걸음이 돌아오기를 기다린 뒤 끝난다 — 풀이
// 닫히기 전에 작업이 끝난다(main.go 의 defer LIFO). 발행 몫이 없어도(반납할 fence 가 없어도) 기다린다. 변이 = 반납 칸이
// 없을 때 기다리지 않음 → FAIL.
func TestStopWaitsForFinalizeStepWithoutPublisherD24(t *testing.T) {
	f := newFinalLoop(t, finalize.ModeOff)
	f.work.scanRows = []index.EndingSession{endingRow("S1", nil)}
	f.work.gate = make(chan struct{})           // 닫지 않는다 — ctx 취소로만 돌아온다
	f.work.cancelDelay = 300 * time.Millisecond // 취소 뒤에도 문장 하나가 끝나기를 흉내 낸다 — 기다리지 않으면 반드시 보인다
	laneCtx, cancel := context.WithCancel(t.Context())
	f.rewind.holdTick(laneCtx, loopBase)
	select {
	case res := <-f.rewind.done():
		f.rewind.complete(laneCtx, res, loopBase)
	case <-time.After(5 * time.Second):
		t.Fatal("스캔 결과가 오지 않았다")
	}
	waitStepInFlight(t, f.work)
	stopped := make(chan struct{})
	go func() {
		f.rewind.stop(t.Context(), cancel)
		close(stopped)
	}()
	select {
	case <-stopped:
	case <-time.After(5 * time.Second):
		t.Fatal("stop 이 돌아오지 않았다")
	}
	f.work.mu.Lock()
	defer f.work.mu.Unlock()
	if f.work.inFlight != 0 {
		t.Fatalf("stop 뒤에도 걸음 %d 개가 돈다 — 풀이 닫힌 뒤 문장이 나갈 수 있다", f.work.inFlight)
	}
}

// D2 — 정산 걸음(S1 동결)이 차선에서 도는 동안 루프 고루틴이 같은 스트림의 캐시를 쓴다(S2 live 의 적재 요구 · 반영). 작업
// 몸체는 값만 받아 장부만 보고 값만 돌려주므로 둘 사이에 경쟁이 없다. 캐시는 잠금이 없는 루프 전용 타입이라, 차선에서 캐시를
// 부르면 -race 가 경쟁으로 잡는다. 변이 = 걸음 작업 안에서 캐시 호출(r.cache.Snapshot) → -race FAIL.
func TestFinalizeLaneWorkLeavesCacheToLoopD2(t *testing.T) {
	f := newFinalLoop(t, finalize.ModePublishing)
	f.work.scanRows = []index.EndingSession{endingRow("S1", nil)}
	f.work.gate = make(chan struct{})
	f.hold(loopBase)
	if kind := f.next(loopBase); kind != publish.KindFinalizeScan {
		t.Fatalf("첫 결과 = %v, want 정산 스캔", kind)
	}
	if len(f.rewind.fin.running) != 1 {
		t.Fatalf("도는 걸음 = %v, want S1 하나", f.rewind.fin.running)
	}
	for range 50 { // 걸음이 서 있는 동안 루프가 같은 스트림의 캐시 상태를 쓴다(적재 열기 — 스트림 맵 · 적재 칸)
		f.rewind.cache.BeginLoad("demo", nil)
	}
	close(f.work.gate)
	f.settle(loopBase)
	if got := f.work.stepsSeen(); !slices.Equal(got, []finalize.Step{finalize.StepFreezeDVR}) {
		t.Fatalf("걸음 = %v, want [freeze_dvr]", got)
	}
}

// 발행 층 결과를 판정의 결과로 줄이는 표 — 정산 대기(unsettled)는 장애 재시도와 갈라 받는다(cx m62 c4 v 주의 — 정산 창
// 만료 전의 대기를 VOD 장애 3회에 섞으면 정산 중인 사슬이 freeze_failed 로 끝난다). 변이 = unsettled 갈래 제거 → FAIL.
func TestOutcomeOfSeparatesUnsettledFromRetry(t *testing.T) {
	tests := []struct {
		name string
		in   publish.FreezeOutcome
		want finalize.Outcome
	}{
		{"섬", publish.FreezeOutcome{Result: publish.ResultFrozen}, finalize.OutcomeDone},
		{"GAP 있는 섬", publish.FreezeOutcome{Result: publish.ResultFrozenWithGaps}, finalize.OutcomeDone},
		{"빈 동결", publish.FreezeOutcome{Result: publish.ResultEmpty}, finalize.OutcomeDone},
		{"정산 대기", publish.FreezeOutcome{Retry: true, Reason: publish.ReasonUnsettled}, finalize.OutcomeUnsettled},
		{"장애 재시도", publish.FreezeOutcome{Retry: true, Reason: "db"}, finalize.OutcomeRetry},
		{"실패", publish.FreezeOutcome{Result: publish.ResultFailed}, finalize.OutcomeFailed},
	}
	for _, tt := range tests {
		if got := outcomeOf(tt.in); got != tt.want {
			t.Errorf("%s: outcomeOf(%+v) = %v, want %v", tt.name, tt.in, got, tt.want)
		}
	}
}

// waitStepInFlight 는 가짜 걸음 하나가 몸체에 들어설 때까지 기다린다 — 차선 고루틴이 아직 뜨기 전이면 기다림을 재지 못한다.
func waitStepInFlight(t *testing.T, w *fakeFinalWork) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for {
		w.mu.Lock()
		n := w.inFlight
		w.mu.Unlock()
		if n > 0 {
			return
		}
		if time.Now().After(deadline) {
			t.Fatal("걸음이 5초 안에 시작하지 않았다")
		}
		time.Sleep(time.Millisecond)
	}
}
