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

// SeedResult 는 개시 트랜잭션의 산출 carrier 다(주조 + 세션 개시).
//
// 주조 결과에 더해 이 트랜잭션이 **커밋한** 세션·carrier 값을 싣는다 — 루프가 DB 를 다시
// 묻지 않고 방금 쓴 행과 새로 연 세션을 아는 통로가 이것 하나다(계획 3절 형상 결정 2).
// 이름이 주조 시절 그대로인 것은 알려진 부채다(계획 부기 16 — 개명은 M5 축).
//
// 행이 들어가지 않은 호출(오류·중복·seq 충돌)의 결과는 영값이다. 그 트랜잭션은 롤백됐으므로
// 세션을 열었어도 개시로 보고하지 않는다.
type SeedResult struct {
	Seeded  bool
	Decline SeedDecline
	// DiagErr 는 Decline **귀속 진단** 쿼리의 실패다 — 삽입·주조 결과와 무관하며 신호
	// 정밀도만 낮아진다. Insert 의 에러로 전파하면 이미 커밋된 성공 삽입이 실패로
	// 오보고돼 재시도 → 23505 → (지속 시) 크래시루프가 된다(cc 리뷰 차단 2).
	// 호출자는 WARN 으로만 소비한다.
	DiagErr error

	// SessionOpened 는 이 조각이 새 세션을 열었는가다(비분할 개시·TD 분할 공통).
	// 아래 세 필드는 이것이 참일 때만 뜻이 있다.
	SessionOpened bool
	// DiscontinuityBase 는 새 세션 행에 쓰인 discontinuity_base 다(TD 분할이면 승계한 값).
	DiscontinuityBase int64
	// InheritsSession 은 새 세션 행의 inherits_session 이다. "" 면 NULL 이다.
	InheritsSession string
	// PrevFirstLocalPath 는 계승 후보 개시에서 직전 세션 첫 조각의 local_path 다
	// (ADR-044 호환 게이트의 입력). "" 면 계승 후보가 아니므로 게이트를 적용하지 않는다.
	// 채우는 쪽은 재접속 계승 갈래(M4 PR ⓒ)이고, 그 전에는 언제나 "" 다.
	PrevFirstLocalPath string

	// SessionID 는 이 조각이 귀속된 세션이다. "" 면 NULL(비귀속)이다.
	SessionID string
	// PlaybackPDT 는 이 행의 playback_pdt 다. 영값이면 NULL 이다.
	PlaybackPDT time.Time
	// PlaybackS3Key 는 이 행의 playback_s3_key 다. "" 면 NULL 이다(키 파생 실패 포함).
	PlaybackS3Key string
	// DurationMS 는 INSERT 때의 duration_ms 다. 꼬리 교정은 그 뒤 UpdateTail 이 따로 한다.
	DurationMS int32
}
