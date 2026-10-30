// Package finalize 는 끝난 세션(state='ending')의 다음 걸음을 정하는 순수 판정이다(POK-196 M6-3b 커밋 ② — 설계 M6
// 2절 · 3.1 상태 전이도 · 3.2 꺼짐 · 강등 표 · 9절 D8 · D56 · D72).
//
// 입력은 정산 스캔 · 빠른 길이 같은 판독식으로 채운 장부 한 행(index.EndingSession)과 루프 메모리의 실패 횟수뿐이다. DB ·
// 저장소 · 시계를 보지 않는다 — 시각은 행의 Now(그 문장의 DB now())다. 걸음을 실제로 하는 것은 루프의 차선 작업이다.
//
//	phase 1   DVR 동결 — 1단 FreezeDVR × 3 → 2단 Endlist × 3 → 3단 freeze_failed · 좌초(freezing 60초 초과)는 좌초 회수
//	사슬 닫힘 꼬리 · 후속 없음 · init 판정 끝 · (즉시 닫는 사유 ∨ 표식 ∨ 창 지남) → 사슬 전원 vod freezing
//	phase 3   VOD 동결 — 정산 창(꼬리 ending_at + 15분) 안이면 미착지를 기다리고 지나면 GAP 으로 포기 · 장애 3회 → freeze_failed
//	phase 4   dvr · vod 종국 꼬리 → 사슬 전원 ended
//
// 발행 꺼짐(S3_BUCKET 빈 값)은 발행 흔적이 없을 때만 PUT 없이 빈 동결로 닫고, 흔적이 있으면 보류한다 — 버킷 있던 판의 /dvr
// 판 · ③ 객체가 남아 있어 빈 동결로 닫으면 다시보기가 영구히 없다(D72). 발행 강등(발행자 조립 실패)은 S3 가 필요한 걸음을
// 모두 보류한다 — 10분 좌초 포기(freeze_failed)도 보류다. 강등 중 시간이 흘렀다고 비가역 종국을 쓰지 않는다(D56). 사슬
// 닫힘 · phase 4 는 장부만 쓰므로 세 상태 모두 같다.
package finalize

import (
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// Mode 는 발행 몫의 상태다 — 판별자는 설정값이다(cfg.S3.Bucket 과 발행자 조립 결과 · 설계 3.2). 발행자 포인터가 nil 인가로
// 가르지 않는다(꺼짐과 강등이 둘 다 nil 이다). 값은 1 부터지만 Next 는 영값을 거르지 않는다 — 꺼짐 · 강등 갈래에 걸리지 않아
// ModePublishing 처럼 걸음을 낸다. 운영 조립(finalizeMode)은 늘 셋 중 하나를 채운다(cc · cx c2 M1).
type Mode int

const (
	ModePublishing Mode = iota + 1 // 버킷 설정 · 발행자 조립 성공
	ModeOff                        // S3_BUCKET 빈 값
	ModeDegraded                   // 버킷 설정 · 발행자 조립 실패(ADR-063 결정 1)
)

// Step 은 다음 걸음이다.
type Step int

const (
	StepWait           Step = iota + 1 // 지금 할 일이 없다(다른 writer 가 동결 중 · 창 안 · 후속 대기 · init 판정 전)
	StepHold                           // 보류(Decision.Hold 가 사유) — 재기동(발행 몫 회복)이 이어 간다
	StepFreezeDVR                      // 1단 — publish.FreezeDVR
	StepSealDVR                        // 2단 — publish.Endlist(본문 끝 ENDLIST 한 줄)
	StepFailDVR                        // 3단 — publish.FailDVR(freeze_failed)
	StepRecoverOrphan                  // 좌초 회수 — publish.RecoverOrphan(10분 넘으면 그 안에서 freeze_failed)
	StepEmptyFreezeDVR                 // 꺼짐 빈 동결 — index.FreezeEmptyDVR
	StepCloseChain                     // 사슬 닫힘 — index.CloseChain
	StepFreezeVOD                      // phase 3 — publish.FreezeVOD(Decision.Abandon)
	StepEmptyFreezeVOD                 // 꺼짐 VOD 빈 동결 — index.FreezeEmptyVOD
	StepFailVOD                        // VOD 실패 확정 — index.FailChainVOD
	StepMarkEnded                      // phase 4 — index.MarkChainEnded
)

var stepNames = map[Step]string{
	StepWait: "wait", StepHold: "hold", StepFreezeDVR: "freeze_dvr", StepSealDVR: "seal_dvr", StepFailDVR: "fail_dvr",
	StepRecoverOrphan: "recover_orphan", StepEmptyFreezeDVR: "empty_freeze_dvr", StepCloseChain: "close_chain",
	StepFreezeVOD: "freeze_vod", StepEmptyFreezeVOD: "empty_freeze_vod", StepFailVOD: "fail_vod",
	StepMarkEnded: "mark_ended",
}

// String 은 신호 속성 step 의 값이다.
func (s Step) String() string {
	if n, ok := stepNames[s]; ok {
		return n
	}
	return "unknown"
}

// 보류 사유 — 게이지 finalize_held 의 reason 값이다(설계 8절).
const (
	HoldPublishOffWithTrace = "publish_off_with_trace"
	HoldPublishDegraded     = "publish_degraded"
)

// Options 는 판정의 설정이다.
type Options struct {
	Mode Mode
	// CloseAfter 는 마지막 조각 끝에서 사슬을 닫기까지의 시간이다(재접속 창 300초 + 닫힘 여유 60초).
	CloseAfter time.Duration
	// MaxAttempts 는 단마다의 실패 상한이다(4초 × 3 — 설계 3.1).
	MaxAttempts int
}

// Decision 은 판정 결과다. Abandon 은 StepFreezeVOD 에서만 뜻이 있다(정산 창이 지났다 — 미착지를 GAP 으로 포기). Hold 는
// StepHold 의 사유다.
type Decision struct {
	Step    Step
	Abandon bool
	Hold    string
}

// Attempts 는 세션 하나의 단별 실패 횟수다 — 루프 메모리에 두고 재기동하면 0 에서 다시 센다.
type Attempts struct {
	DVR  int // 1단 FreezeDVR
	Seal int // 2단 Endlist
	VOD  int // phase 3 FreezeVOD(정산 대기는 세지 않는다)
}

// Outcome 은 걸음 하나의 결과를 판정이 쓰는 꼴로 줄인 것이다(발행 층의 FreezeOutcome 을 차선 작업이 옮긴다).
type Outcome int

const (
	OutcomeDone      Outcome = iota + 1 // 섰다(장부가 다음 상태로 갔다)
	OutcomeRetry                        // 이번에는 서지 않았다 — 같은 걸음을 다시 한다
	OutcomeUnsettled                    // VOD 정산 대기(③ 미착지 · 정산 창 안) — 장애가 아니다
	OutcomeFailed                       // 같은 입력으로 다시 해도 서지 않는다 — 다음 단으로
)

// After 는 걸음 step 의 결과 out 을 센다. 재시도는 그 단을 하나 올리고, 실패는 그 단을 상한까지 올려 다음 단으로 넘긴다.
// 정산 대기 · 성공은 세지 않는다. 좌초 회수 · 3단 · 장부 걸음은 단이 없다(시한 · 재시도를 그 걸음이 스스로 진다).
func (a Attempts) After(step Step, out Outcome, limit int) Attempts {
	var n *int
	switch step {
	case StepFreezeDVR:
		n = &a.DVR
	case StepSealDVR:
		n = &a.Seal
	case StepFreezeVOD:
		n = &a.VOD
	default:
		return a
	}
	switch out {
	case OutcomeRetry:
		*n++
	case OutcomeFailed:
		*n = limit
	}
	return a
}

// Limits 는 정산 걸음의 동시 상한이다(D25 — 옛 ending 수백 건이 차선 · DB 를 폭주시키지 않게).
type Limits struct {
	Total int // 동시에 도는 정산 걸음 전체
	VOD   int // 그 가운데 VOD 동결 — 발행자 풀 연결 하나를 쥔다(장부 재독은 꼬리 잠금 트랜잭션의 같은 연결 — 78acee38)
	// PerStream 은 한 스트림에서 동시에 도는 정산 걸음이다 — 같은 스트림 회차의 걸음은 같은 행 · 위상 잠금을 다투고, 진
	// 쪽은 풀 연결을 쥔 채 기다린다(db c3 M3 — 6연결 풀 바닥 · 핫 INSERT 4.71초 대기 실측).
	PerStream int
}

// DefaultLimits 는 운영 값이다 — 전체 2 · 같은 스트림 1 · VOD 1(kty 2026-10-27 결정 (가)). 빈 동결 · 사슬 닫힘 · VOD 실패
// 확정 · ended 표시 걸음은 인덱서 풀(상한 6)에서 연결을 쥐고 그 풀의 산식(핫 경로 3 · 회차 조회 2 · 4D 1) 밖이다 — 전체 2
// 면 그 풀에서 많아야 둘이다. DVR · VOD 동결 걸음은 발행자 풀을 쓴다(cmd/segment-indexer indexerPoolMaxConns 주석).
var DefaultLimits = Limits{Total: 2, VOD: 1, PerStream: 1}

// Admit 은 지금 도는 걸음 수(inFlight · 그 가운데 VOD vodInFlight · 그 스트림 streamInFlight)에서 걸음 s 를 하나 더 낼 수
// 있는가다.
func (l Limits) Admit(inFlight, vodInFlight, streamInFlight int, s Step) bool {
	if inFlight >= l.Total || streamInFlight >= l.PerStream {
		return false
	}
	return s != StepFreezeVOD || vodInFlight < l.VOD
}

// Next 는 행 s 의 다음 걸음이다(설계 3.1 · 3.2 표). 행은 state='ending' 이다.
func Next(s index.EndingSession, a Attempts, o Options) Decision {
	switch s.DVRState {
	case "open", "freezing":
		return nextDVR(s, a, o)
	case "frozen", "freeze_failed":
		return nextAfterDVR(s, a, o)
	}
	return Decision{Step: StepWait}
}

// nextDVR 는 phase 1 이다.
func nextDVR(s index.EndingSession, a Attempts, o Options) Decision {
	switch o.Mode {
	case ModeOff:
		if s.HasPublishTrace {
			return Decision{Step: StepHold, Hold: HoldPublishOffWithTrace}
		}
		return Decision{Step: StepEmptyFreezeDVR}
	case ModeDegraded:
		return Decision{Step: StepHold, Hold: HoldPublishDegraded}
	}
	if s.DVRState == "freezing" && a.DVR == 0 && a.Seal == 0 {
		// 이 루프가 사다리를 돌던 회차가 아니다 — 다른 writer(또는 죽은 앞 프로세스)가 동결 중이다.
		if s.Now.Sub(s.DVRFreezingAt) <= index.FreezeOrphanAfter {
			return Decision{Step: StepWait}
		}
		return Decision{Step: StepRecoverOrphan}
	}
	switch {
	case a.DVR < o.MaxAttempts:
		return Decision{Step: StepFreezeDVR}
	case a.Seal < o.MaxAttempts:
		return Decision{Step: StepSealDVR}
	}
	return Decision{Step: StepFailDVR}
}

// nextAfterDVR 는 dvr 종국 뒤 — 사슬 닫힘 · phase 3 · phase 4 다. 걸음은 꼬리(같은 회차 후속 없음)만 몬다.
func nextAfterDVR(s index.EndingSession, a Attempts, o Options) Decision {
	if s.HasSuccessor {
		return Decision{Step: StepWait}
	}
	switch s.VODState {
	case "none":
		if !s.InitSettled || !s.ChainCloseDue(o.CloseAfter) {
			return Decision{Step: StepWait}
		}
		return Decision{Step: StepCloseChain}
	case "freezing":
		return nextVOD(s, a, o)
	}
	return Decision{Step: StepMarkEnded}
}

// nextVOD 는 phase 2 · 3 이다 — 정산 대기는 FreezeVOD(포기 없음)의 unsettled 재시도가 맡는다.
func nextVOD(s index.EndingSession, a Attempts, o Options) Decision {
	switch o.Mode {
	case ModeOff:
		if s.HasPublishTrace {
			return Decision{Step: StepHold, Hold: HoldPublishOffWithTrace}
		}
		return Decision{Step: StepEmptyFreezeVOD}
	case ModeDegraded:
		return Decision{Step: StepHold, Hold: HoldPublishDegraded}
	}
	if a.VOD >= o.MaxAttempts {
		return Decision{Step: StepFailVOD}
	}
	return Decision{Step: StepFreezeVOD, Abandon: !s.Now.Before(s.EndingAt.Add(index.SettleWindow))}
}
