package finalize

// 종료 세션의 다음 걸음 판정 — 표 시험(POK-196 M6-3b 커밋 ② — 설계 M6 3.1 · 3.2 꺼짐 · 강등 표 · 9절 D8 · D56 · D72).

import (
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

var now = time.Date(2026, 10, 26, 12, 0, 0, 0, time.UTC)

const closeAfter = 360 * time.Second

func opts(m Mode) Options { return Options{Mode: m, CloseAfter: closeAfter, MaxAttempts: 3} }

// row 는 판정 입력 한 행이다 — 기본은 오프라인으로 막 끝난 꼬리(dvr open · vod none · 흔적 없음 · init 판정 전).
func row(f func(*index.EndingSession)) index.EndingSession {
	s := index.EndingSession{
		SessionID: "S", StreamID: "demo", RoundID: "R", EndReason: "offline", DVRState: "open", VODState: "none",
		EndingAt: now.Add(-time.Minute), LastEnd: now.Add(-70 * time.Second), Now: now,
	}
	if f != nil {
		f(&s)
	}
	return s
}

func frozen(s *index.EndingSession) { s.DVRState = "frozen" }

func TestNextDecidesStepByLedgerRow(t *testing.T) {
	tests := []struct {
		name string
		s    index.EndingSession
		a    Attempts
		mode Mode
		want Decision
	}{
		// phase 1 — DVR 동결 사다리(1단 FreezeDVR × 3 → 2단 Endlist × 3 → 3단 freeze_failed).
		{"dvr open → 1단", row(nil), Attempts{}, ModePublishing, Decision{Step: StepFreezeDVR}},
		{"1단 2회 실패 → 1단 다시", row(nil), Attempts{DVR: 2}, ModePublishing, Decision{Step: StepFreezeDVR}},
		{"1단 3회 실패 → 2단", row(nil), Attempts{DVR: 3}, ModePublishing, Decision{Step: StepSealDVR}},
		{"2단 3회 실패 → 3단", row(nil), Attempts{DVR: 3, Seal: 3}, ModePublishing, Decision{Step: StepFailDVR}},
		{"사다리 중 freezing 은 사다리를 잇는다", row(func(s *index.EndingSession) {
			s.DVRState, s.DVRFreezingAt = "freezing", now.Add(-2*time.Second)
		}), Attempts{DVR: 1}, ModePublishing, Decision{Step: StepFreezeDVR}},
		{"남이 동결 중(freezing 60초 안)", row(func(s *index.EndingSession) {
			s.DVRState, s.DVRFreezingAt = "freezing", now.Add(-30*time.Second)
		}), Attempts{}, ModePublishing, Decision{Step: StepWait}},
		{"좌초(freezing 60초 초과) → 좌초 회수", row(func(s *index.EndingSession) {
			s.DVRState, s.DVRFreezingAt = "freezing", now.Add(-90*time.Second)
		}), Attempts{}, ModePublishing, Decision{Step: StepRecoverOrphan}},

		// 꺼짐(S3_BUCKET 빈 값) — 흔적 없으면 빈 동결, 있으면 보류(D72).
		{"꺼짐 · 흔적 없음 → DVR 빈 동결", row(nil), Attempts{}, ModeOff, Decision{Step: StepEmptyFreezeDVR}},
		{"꺼짐 · 흔적 있음 → 보류", row(func(s *index.EndingSession) { s.HasPublishTrace = true }), Attempts{}, ModeOff,
			Decision{Step: StepHold, Hold: HoldPublishOffWithTrace}},
		{"꺼짐 · 좌초 · 흔적 없음 → 빈 동결", row(func(s *index.EndingSession) {
			s.DVRState, s.DVRFreezingAt = "freezing", now.Add(-90*time.Second)
		}), Attempts{}, ModeOff, Decision{Step: StepEmptyFreezeDVR}},
		{"꺼짐 · VOD · 흔적 없음 → VOD 빈 동결", row(func(s *index.EndingSession) { frozen(s); s.VODState = "freezing" }),
			Attempts{}, ModeOff, Decision{Step: StepEmptyFreezeVOD}},
		{"꺼짐 · VOD · 흔적 있음 → 보류", row(func(s *index.EndingSession) {
			frozen(s)
			s.VODState, s.HasPublishTrace = "freezing", true
		}), Attempts{}, ModeOff, Decision{Step: StepHold, Hold: HoldPublishOffWithTrace}},

		// 강등(버킷 설정 · 발행자 조립 실패) — S3 가 필요한 걸음은 모두 보류(10분 좌초 포기 포함 · D56).
		{"강등 · dvr open → 보류", row(nil), Attempts{}, ModeDegraded, Decision{Step: StepHold, Hold: HoldPublishDegraded}},
		{"강등 · 좌초 → 보류", row(func(s *index.EndingSession) {
			s.DVRState, s.DVRFreezingAt = "freezing", now.Add(-time.Hour)
		}), Attempts{}, ModeDegraded, Decision{Step: StepHold, Hold: HoldPublishDegraded}},
		{"강등 · VOD → 보류", row(func(s *index.EndingSession) { frozen(s); s.VODState = "freezing" }), Attempts{}, ModeDegraded,
			Decision{Step: StepHold, Hold: HoldPublishDegraded}},

		// 사슬 닫힘 — 꼬리만 · 즉시 닫는 사유 · 표식 · 창 · init 판정 뒤(D75). 장부만이라 꺼짐 · 강등에서도 같다.
		{"후속 있음 → 기다림", row(func(s *index.EndingSession) {
			frozen(s)
			s.HasSuccessor, s.EndReason, s.InitSettled = true, "intentional", true
		}), Attempts{}, ModePublishing, Decision{Step: StepWait}},
		{"intentional → 사슬 닫힘", row(func(s *index.EndingSession) {
			frozen(s)
			s.EndReason, s.InitSettled = "intentional", true
		}), Attempts{}, ModeDegraded, Decision{Step: StepCloseChain}},
		{"td_exceeded → 사슬 닫힘", row(func(s *index.EndingSession) {
			frozen(s)
			s.EndReason, s.InitSettled = "td_exceeded", true
		}), Attempts{}, ModeOff, Decision{Step: StepCloseChain}},
		{"표식 → 사슬 닫힘", row(func(s *index.EndingSession) {
			frozen(s)
			s.StopMarkedAt, s.InitSettled = now.Add(-time.Minute), true
		}), Attempts{}, ModePublishing, Decision{Step: StepCloseChain}},
		{"offline 창 안 → 기다림", row(func(s *index.EndingSession) { frozen(s); s.InitSettled = true }), Attempts{},
			ModePublishing, Decision{Step: StepWait}},
		{"offline 창 지남 → 사슬 닫힘", row(func(s *index.EndingSession) {
			frozen(s)
			s.InitSettled, s.LastEnd = true, now.Add(-361*time.Second)
		}), Attempts{}, ModePublishing, Decision{Step: StepCloseChain}},
		{"init 판정 전 → 기다림", row(func(s *index.EndingSession) { frozen(s); s.EndReason = "intentional" }), Attempts{},
			ModePublishing, Decision{Step: StepWait}},
		{"dvr freeze_failed 도 종국", row(func(s *index.EndingSession) {
			s.DVRState, s.EndReason, s.InitSettled = "freeze_failed", "intentional", true
		}), Attempts{}, ModePublishing, Decision{Step: StepCloseChain}},

		// 정산 · VOD 동결 — 정산 창(꼬리 ending_at + 15분) 안이면 포기 없이(미착지면 재시도), 지나면 포기(D8).
		{"VOD 창 안 → 포기 없이", row(func(s *index.EndingSession) { frozen(s); s.VODState = "freezing" }), Attempts{},
			ModePublishing, Decision{Step: StepFreezeVOD}},
		{"VOD 창 지남 → 포기", row(func(s *index.EndingSession) {
			frozen(s)
			s.VODState, s.EndingAt = "freezing", now.Add(-16*time.Minute)
		}), Attempts{}, ModePublishing, Decision{Step: StepFreezeVOD, Abandon: true}},
		{"VOD 3회 실패 → freeze_failed", row(func(s *index.EndingSession) { frozen(s); s.VODState = "freezing" }),
			Attempts{VOD: 3}, ModePublishing, Decision{Step: StepFailVOD}},

		// phase 4 — dvr · vod 종국 꼬리.
		{"vod frozen → phase 4", row(func(s *index.EndingSession) { frozen(s); s.VODState = "frozen" }), Attempts{},
			ModeDegraded, Decision{Step: StepMarkEnded}},
		{"vod freeze_failed → phase 4", row(func(s *index.EndingSession) { frozen(s); s.VODState = "freeze_failed" }),
			Attempts{}, ModeOff, Decision{Step: StepMarkEnded}},
		{"vod 종국이어도 후속 있으면 기다림", row(func(s *index.EndingSession) {
			frozen(s)
			s.VODState, s.HasSuccessor = "frozen_with_gaps", true
		}), Attempts{}, ModePublishing, Decision{Step: StepWait}},
		{"모르는 dvr 상태 → 기다림", row(func(s *index.EndingSession) { s.DVRState = "bogus" }), Attempts{}, ModePublishing,
			Decision{Step: StepWait}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := Next(tt.s, tt.a, opts(tt.mode)); got != tt.want {
				t.Fatalf("Next = %+v, want %+v", got, tt.want)
			}
		})
	}
}

// 실패 세기 — 재시도는 그 단의 횟수를 하나 올리고, 같은 입력으로 다시 해도 서지 않는 실패는 그 단을 끝까지 올려 다음
// 단으로 넘긴다. 정산 대기(unsettled — ③ 미착지 · 정산 창 안)는 장애가 아니라 세지 않는다(cx m62 c4 v — 3회 실패와 섞지
// 않음). 성공 · 좌초 회수 · 3단 · VOD 실패 확정의 재시도는 세지 않는다(그 걸음이 시한을 스스로 진다).
func TestAttemptsAfterOutcome(t *testing.T) {
	tests := []struct {
		name string
		a    Attempts
		step Step
		out  Outcome
		want Attempts
	}{
		{"1단 재시도", Attempts{}, StepFreezeDVR, OutcomeRetry, Attempts{DVR: 1}},
		{"1단 실패 → 2단으로", Attempts{DVR: 1}, StepFreezeDVR, OutcomeFailed, Attempts{DVR: 3}},
		{"2단 재시도", Attempts{DVR: 3}, StepSealDVR, OutcomeRetry, Attempts{DVR: 3, Seal: 1}},
		{"2단 실패 → 3단으로", Attempts{DVR: 3}, StepSealDVR, OutcomeFailed, Attempts{DVR: 3, Seal: 3}},
		{"VOD 재시도", Attempts{VOD: 1}, StepFreezeVOD, OutcomeRetry, Attempts{VOD: 2}},
		{"VOD 정산 대기는 세지 않음", Attempts{VOD: 2}, StepFreezeVOD, OutcomeUnsettled, Attempts{VOD: 2}},
		{"VOD 실패 → 확정으로", Attempts{}, StepFreezeVOD, OutcomeFailed, Attempts{VOD: 3}},
		{"성공은 그대로", Attempts{DVR: 2}, StepFreezeDVR, OutcomeDone, Attempts{DVR: 2}},
		{"좌초 회수 재시도는 세지 않음", Attempts{}, StepRecoverOrphan, OutcomeRetry, Attempts{}},
		{"3단 재시도는 세지 않음", Attempts{DVR: 3, Seal: 3}, StepFailDVR, OutcomeRetry, Attempts{DVR: 3, Seal: 3}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := tt.a.After(tt.step, tt.out, 3); got != tt.want {
				t.Fatalf("After = %+v, want %+v", got, tt.want)
			}
		})
	}
}

// 정산 대기와 장애가 섞이지 않는다 — 정산 창 안의 미착지 재시도를 몇 번 받아도 VOD 는 freeze_failed 로 가지 않고, 진짜
// 실패 셋이 쌓여야만 간다.
func TestUnsettledRetriesNeverReachFailVOD(t *testing.T) {
	s := row(func(s *index.EndingSession) { frozen(s); s.VODState = "freezing" })
	var a Attempts
	for range 50 {
		if d := Next(s, a, opts(ModePublishing)); d.Step != StepFreezeVOD {
			t.Fatalf("정산 대기 중 걸음 = %+v, want FreezeVOD", d)
		}
		a = a.After(StepFreezeVOD, OutcomeUnsettled, 3)
	}
	for range 3 {
		a = a.After(StepFreezeVOD, OutcomeRetry, 3)
	}
	if d := Next(s, a, opts(ModePublishing)); d.Step != StepFailVOD {
		t.Fatalf("장애 3회 뒤 걸음 = %+v, want FailVOD", d)
	}
}

// 동시 상한(D25 · cc m62 c5 M1) — 정산 걸음은 전체 4 개까지, 그 가운데 VOD 동결은 1 개까지다. VOD 동결 하나는 연결 둘
// (꼬리 잠금 트랜잭션 · 재독)을 쥐므로 발행자 풀(pgx 기본 max(4, CPU))에 여유를 남긴다. 장부만 쓰는 걸음은 VOD 칸을 쓰지
// 않는다.
func TestLimitsAdmit(t *testing.T) {
	l := DefaultLimits
	tests := []struct {
		name          string
		inFlight, vod int
		step          Step
		want          bool
	}{
		{"빈 차선", 0, 0, StepFreezeVOD, true},
		{"VOD 하나 도는 중 VOD", 1, 1, StepFreezeVOD, false},
		{"VOD 하나 도는 중 사슬 닫힘", 1, 1, StepCloseChain, true},
		{"전체 4 개 · DVR", 4, 0, StepFreezeDVR, false},
		{"전체 3 개 · DVR", 3, 1, StepFreezeDVR, true},
		{"전체 4 개 · phase 4", 4, 1, StepMarkEnded, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := l.Admit(tt.inFlight, tt.vod, tt.step); got != tt.want {
				t.Fatalf("Admit(%d, %d, %v) = %v, want %v", tt.inFlight, tt.vod, tt.step, got, tt.want)
			}
		})
	}
}

// 걸음 이름 — 신호 속성 step 값이다.
func TestStepString(t *testing.T) {
	if StepFreezeVOD.String() != "freeze_vod" || StepHold.String() != "hold" || Step(0).String() != "unknown" {
		t.Fatalf("Step 이름 = %q %q %q", StepFreezeVOD, StepHold, Step(0))
	}
}
