package index

// 컷오프 주조의 판정 입력과 결과(POK-168 r15a · ADR-062 — 계약 6항 위임의 확정).
//
// 자격 = ⓐ 라이브 방증 ∧ ⓑ 새 행 ∧ ⓒ 시작점 자격.
//   - ⓐ 의 "낡을 수 있는 항"은 시간 항뿐이다: 비시간 항(Eligible)만 Go 가 판정해 내려보내고,
//     시간 항은 SQL 이 clock_timestamp() 로 문장 실행 시점(락 대기 뒤)에 재검한다.
//   - ⓑ·ⓒ 는 인자로 내려가지 않는다 — CTE 의 INSERT … RETURNING 이 컷오프 값의 유일한
//     입력이고(과대·과소 기록이 표현 불가), ⓒ 세 열은 SQL 이 방금 쓴 값을 직접 본다.

import "time"

// SeedReason 은 ⓐ 방증의 갈래다. stream_cutoffs.seed_reason CHECK 와 1:1 이다.
type SeedReason string

const (
	// SeedReasonLiveIngress 는 ⓐ1 실시간 유입 방증이다(Reason ∈ {NextFile, Idle, Hook}).
	SeedReasonLiveIngress SeedReason = "live_ingress"
	// SeedReasonStateObs 는 ⓐ2 상태 방증이다(Control API publishing — mtxstate, M3 배선).
	SeedReasonStateObs SeedReason = "state_obs"
)

// SeedChannel 은 유입 채널이다. stream_cutoffs.seed_channel CHECK 와 1:1 이다.
type SeedChannel string

const (
	SeedChannelWatcher SeedChannel = "watcher"
	SeedChannelHook    SeedChannel = "hook"
	SeedChannelScan    SeedChannel = "scan"
	SeedChannelSlate   SeedChannel = "slate"
)

// Seed 는 INSERT 한 번에 동봉되는 주조 판정 입력이다.
type Seed struct {
	// Eligible 은 ⓐ 의 비시간 항이다. 거짓이면 주조를 시도하지 않는다(WHERE 가 막는다).
	Eligible bool
	Reason   SeedReason
	Channel  SeedChannel
	// AnchorUTC·Freshness 는 ⓐ 의 시간 항 쌍이다 — ⓐ1: (start_wall_utc, LIVE_FRESH 60초) /
	// ⓐ2: (ObservedAt, OBS_FRESH 30초). SQL 이 clock_timestamp() − Anchor ≤ Freshness 로 재검한다.
	AnchorUTC time.Time
	Freshness time.Duration
}

// SeedDecline 은 "주조가 일어나지 않은 이유"의 귀속이다(설계 6.5.5).
type SeedDecline string

const (
	// DeclineNone — 주조됐다(Seeded=true)의 짝.
	DeclineNone SeedDecline = ""
	// DeclineNoCorroboration — ⓐ 비시간 항이 거짓(Eligible=false).
	DeclineNoCorroboration SeedDecline = "no_corroboration"
	// DeclineNotSettleable — ⓒ 미충족: carrier 셋 중 하나라도 nil(값이 없어 시작점 자격이 없다).
	DeclineNotSettleable SeedDecline = "not_settleable"
	// DeclineStaleCorroboration — 시간 항 재검 탈락(락 대기 등으로 방증이 낡았다 — m1a).
	DeclineStaleCorroboration SeedDecline = "stale_corroboration"
	// DeclineSkipped — 기존 컷오프가 있어 승계했다(ON CONFLICT DO NOTHING — d5, 예외가 아니다).
	DeclineSkipped SeedDecline = "existing_cutoff"
)

// SeedResult 는 주조 시도의 결과다.
type SeedResult struct {
	Seeded  bool
	Decline SeedDecline
}
