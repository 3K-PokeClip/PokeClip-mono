// Package session 은 조각 하나가 어느 세션(회차)에 속하는지를 **트랜잭션 안에서** 정한다.
//
// 경계가 좁은 이유(설계 5.4.1·6.5.2 · 계획 3절):
//   - **세션 결정만 안다.** stream_segments 를 읽지 않는다 — PDT 기저 행 조회는 그 표의
//     주인(index)이 하고, 여기는 "어느 세션인가"와 "세션 표에 무엇을 쓰는가"만 답한다.
//   - **트랜잭션을 만들지 않고 받는다.** 세션 결정·PDT·행 삽입이 한 트랜잭션이어야 하는데
//     그 트랜잭션의 주인은 index 다. 여기서 따로 열면 원자성이 깨진다.
//   - **재시도하지 않는다.** DB 오류는 감싸되 감추지 않고 그대로 올린다 — 경합의 처분
//     (one_live_uq 23505 → 백오프 재시도)은 호출자가 자기 재시도 사다리에서 정한다.
//     여기서 삼키면 "정책상 안 열었다"와 "DB 가 막았다"가 구분되지 않는다(계획 4.3 ⒉).
//
// **호출은 둘로 나뉜다**(계획 3절 소유자 확정 · 4.1):
//
//	Decide → 갈래와 기저 세션 ID (세션 표를 읽기만 한다)
//	  ↓ 그 사이에 호출자가 자기 표에서 기저 행을 읽어 playback_pdt 를 산출한다
//	Open   → first_pdt 를 인자로 받아 세션 표에 쓴다 (개시 갈래일 때만)
//
// 나누지 않으면 first_pdt 산출에 필요한 값이 index 소유 표에 있어 이 패키지가 그 표를
// 읽어야 하고, 합치는 대신 나눈 대가로 "개시 뒤 first_pdt 를 UPDATE 하는 경로"가 없다 —
// 세션 행의 값은 개시 1회로 굳는다.
package session

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"math"
	"time"

	"github.com/jackc/pgx/v5"
)

// Op 는 호출자가 지정하는 세션 결정 연산이다(설계 5.4 유입 표 · 6.5.2).
//
// **레지스트리는 연산을 고르지 않는다** — 유입 사유(워처·훅·스캔·슬레이트)를 연산으로
// 접는 것은 호출자(indexer) 몫이고, 여기는 지정된 연산을 수행할 뿐이다. 값이 1 부터인
// 이유는 영값을 유효한 연산으로 만들지 않기 위해서다: 안 채운 필드가 조용히 어느 갈래로
// 접히면 배선 누락이 로그 한 줄 없이 새어 나간다.
type Op int

const (
	// OpenOrCurrent 는 ⓐ1(실시간 유입 방증) 축이다 — 현 세션이 있으면 귀속하고 없으면 연다.
	// 관측(ⓐ2)을 보지 않는다: 파일·훅 유입 자체가 "지금 방송이 흐른다"의 방증이다.
	OpenOrCurrent Op = iota + 1
	// CurrentOrOpenIfCorroborated 는 ⓐ2(상태 방증) 축이다 — 현 세션이 있으면 귀속하고,
	// 없을 때는 **관측이 자격을 만족할 때만** 연다. 스캔·슬레이트 유입이 이것을 부른다:
	// 유입 자체가 라이브의 증거가 아니므로(옛 잔존물일 수 있다) 관측이 대신 보증한다.
	CurrentOrOpenIfCorroborated
	// CurrentOnly 는 열지 않는 축이다 — 현 세션이 있으면 귀속하고 없으면 비운다.
	// "언제나 비운다"가 아니다: 세션 중간에 NULL 구멍이 나면 그 조각은 되감기에서 사라진다.
	CurrentOnly
)

// Outcome 은 결정의 갈래다(설계 6.5.2 네 갈래).
//
// 영값이 OutcomeNone 인 것은 안전 방향이다 — 갈래를 못 정한 결정은 아무것도 귀속시키지
// 않고 아무 세션도 열지 않는다.
type Outcome int

const (
	// OutcomeNone 은 비귀속·비개시다. 호출자는 carrier 3 열을 NULL 로 남기고
	// 기저 행 조회·세션 쓰기(⑷⑸)를 건너뛴다.
	//
	// **두 가지 원인이 같은 갈래로 온다**: 정책 거부(귀속 하한·모호 구간 — 신호가 뜬다)와
	// 평시 상태(CurrentOnly 인데 현 세션이 없다 — 신호가 없다). 값이 같은 이유는 장부에
	// 남는 결과가 같기 때문이고, 구분은 신호가 진다.
	OutcomeNone Outcome = iota
	// OutcomeCurrent 는 현 live 세션 귀속이다. 세션 표는 바뀌지 않는다.
	OutcomeCurrent
	// OutcomeOpen 은 새 세션 개시다(현 live 부재).
	OutcomeOpen
	// OutcomeOpenFresh 는 TD 분할이다 — 현 live 를 ending 으로 보내고 새 세션을 연다.
	// 이 갈래만은 호출자가 지정하는 것이 아니라 조각 길이가 정한다.
	OutcomeOpenFresh
)

// String 은 로그와 테스트 실패 메시지에서 숫자 대신 이름이 보이게 한다.
func (o Outcome) String() string {
	switch o {
	case OutcomeNone:
		return "none"
	case OutcomeCurrent:
		return "current"
	case OutcomeOpen:
		return "open"
	case OutcomeOpenFresh:
		return "open_fresh"
	default:
		return fmt.Sprintf("unknown(%d)", int(o))
	}
}

// Observation 은 관측 스냅샷이다 — 호출자가 mtxstate 의 관측을 접어 넘긴다.
//
// **이 패키지는 mtxstate 를 임포트하지 않는다.** 관측의 집은 거기지만 판정에 필요한 것은
// 다섯 값뿐이고, 특히 에폭 여유는 **tier 가 아니라 값**으로 받는다(계획 3절): tier→여유의
// 대응표를 여기서 다시 들면 관측 등급이 늘 때마다 판정부가 함께 바뀐다. 값으로 받으면
// 바뀌는 축(등급 체계)과 안 바뀌는 축(부등식)이 갈린다.
//
// 영값은 fail-closed 다 — EpochKnown=false 이므로 ⓐ2 는 성립하지 않는다.
type Observation struct {
	// Publishing 은 "지금 송출 중인가"다.
	Publishing bool
	// ObservedAt 은 그 관측을 만든 poll 의 시작 시각이다. 신선도 판정의 기준점이다.
	ObservedAt time.Time
	// EpochStartedAt 은 현재 송출이 시작된 시각이다. Publishing 이 참일 때만 뜻이 있다.
	EpochStartedAt time.Time
	// EpochKnown 이 거짓이면 ⓐ2 는 성립하지 않는다(설계 5.4.1 ⑵ fail-closed).
	EpochKnown bool
	// EpochSlack 은 에폭 하한의 여유다(설계 5.4.1 ⑵ 의 EPOCH_SLACK).
	// 0 이면 하한이 에폭 시각 자체이고 모호 구간(⑶)은 공집합이 된다.
	EpochSlack time.Duration
}

// Input 은 세션 결정의 per-call 입력이다. 조각 하나와 그 조각에 딸린 관측이 전부다.
type Input struct {
	StreamID string
	// Seq 는 이 조각의 번호다. 개시 갈래에서 새 세션 id 의 재사용 불가 성분이 된다.
	Seq int64
	// StartWallUTC 는 이 조각의 벽시계 시작이다. 귀속 하한·에폭 하한의 피연산자이고,
	// 개시 갈래에서는 그대로 새 세션의 started_at 이 된다.
	StartWallUTC time.Time
	// DurationMS 는 이 조각의 길이다. TD 판정과 개시 세션의 target_duration 이 이 값을 쓴다.
	DurationMS int32
	Op         Op
	Obs        Observation
}

// Decision 은 갈래와 세션 ID 두 개다 — 호출자가 carrier 와 기저 행 조회에 쓴다.
type Decision struct {
	Outcome Outcome
	// SessionID 는 이 조각이 귀속될 세션이다. "" 면 없다(SQL NULL 로 간다).
	// 빈 문자열을 쓰는 이유: session_id 는 text PK 라 "" 가 유효한 값일 수 없고,
	// FK 가 NOT VALID 로 걸려 있어 "" 를 그대로 INSERT 하면 23503 이다.
	SessionID string
	// BaseSessionID 는 PDT 기저 행을 찾을 세션이다. "" 면 기저 세션 부재다.
	// 귀속 세션과 다를 수 있다 — 개시 갈래에서는 아직 없는 새 세션이 아니라
	// **직전 세션**에서 이어받아야 세션 경계에서 PDT 가 끊기지 않는다.
	BaseSessionID string
	// plan 은 Open 이 쓸 값이다. 비공개인 이유는 호출자가 다른 조각의 값으로 세션을
	// 열 수 없게 하기 위해서다 — 결정과 쓰기가 같은 입력을 본다는 것이 구조로 보장된다.
	plan *openPlan
}

// openPlan 은 개시 갈래가 확정한 새 세션 행의 값이다(계획 3절 (가) 명시 대입 7 컬럼 중
// session_id 파생 재료와 값 3 개). first_pdt 만 Open 의 인자로 따로 온다 — 그 값은
// 호출자가 자기 표를 읽어 산출하기 때문이다.
type openPlan struct {
	streamID          string
	seq               int64
	startedAt         time.Time
	targetDuration    int32
	discontinuityBase int64
	// endingSessionID 는 OpenFresh 에서 ending 으로 보낼 현 live 세션이다. "" 면 비분할 개시다.
	endingSessionID string
}

// Options 는 정책 값이다.
//
// **기본값을 여기 두지 않는다** — 두 값의 집은 config(SESSION_FLOOR_SLACK·OBS_FRESH)이고,
// 여기에 또 적으면 두 곳이 언젠가 어긋난다. 영값으로 만들면 하한은 엄격해지고 관측은
// 언제나 낡은 것으로 판정되는데, 둘 다 안전 방향(비귀속·비개시)이다.
type Options struct {
	// FloorSlack 은 세션 귀속 하한의 여유다(설계 5.4.1 ⑴ — 시계 역행 방어).
	FloorSlack time.Duration
	// ObsFresh 는 상태 방증으로 쓸 수 있는 관측의 나이 상한이다(설계 6.5.2 ⓐ2).
	ObsFresh time.Duration
	Log      *slog.Logger
}

// Registry 는 세션 결정자다. 상태를 갖지 않으며 트랜잭션마다 같은 값으로 답한다.
type Registry struct {
	opt Options
}

// New 는 레지스트리를 만든다.
func New(opt Options) *Registry {
	if opt.Log == nil {
		opt.Log = slog.Default()
	}
	return &Registry{opt: opt}
}

// Decide 는 현 live 세션을 읽어 갈래와 기저 세션 ID 를 정한다(계획 4.1 ⑵⑶).
//
// now 를 인자로 따로 받는 이유: 관측 스냅샷은 재시도 사이에 **재사용**되지만 판정 시각은
// 시도마다 새로 재야 한다(계획 4.1 ⑶ "시도별 재검"). 한 구조체에 섞어 두면 호출자가
// 스냅샷과 함께 시각까지 재사용해 "대기 중 낡은" 관측으로 세션이 열린다.
// 호출자는 직렬화 락을 잡은 직후의 시각을 넣는다.
func (r *Registry) Decide(ctx context.Context, tx pgx.Tx, in Input, now time.Time) (Decision, error) {
	live, err := r.loadLive(ctx, tx, in.StreamID)
	if err != nil {
		return Decision{}, err
	}
	d, err := r.decide(live, in, now)
	if err != nil || d.Outcome != OutcomeOpen {
		return d, err
	}
	// 개시 갈래만 기저 세션을 **조회**한다. 계속·OpenFresh 는 손에 쥔 현 live 가 기저이고,
	// 비귀속·비개시는 ⑷⑸ 를 건너뛰므로 기저가 필요 없다.
	if d.BaseSessionID, err = r.latestSession(ctx, tx, in.StreamID); err != nil {
		return Decision{}, err
	}
	return d, nil
}

// currentLiveSQL 은 stream_sessions_one_live_uq(부분 UNIQUE 인덱스) 축의 조회다.
// 그 인덱스가 스트림당 live 행이 하나임을 물리로 보증하므로 LIMIT 을 두지 않는다.
// state='live' 술어를 빼면 인덱스를 벗어나고, ending 세션까지 현 세션으로 보게 된다.
const currentLiveSQL = `
SELECT session_id, started_at, target_duration, discontinuity_base
  FROM stream_sessions
 WHERE stream_id = $1 AND state = 'live'`

func (r *Registry) loadLive(ctx context.Context, tx pgx.Tx, streamID string) (*liveSession, error) {
	var s liveSession
	err := tx.QueryRow(ctx, currentLiveSQL, streamID).
		Scan(&s.id, &s.startedAt, &s.targetDuration, &s.discontinuityBase)
	// 행이 없다 = 현 live 세션이 없다. 에러가 아니라 정상 상태(개시 갈래)다.
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("현 live 세션 조회 실패 stream_id=%q: %w", streamID, err)
	}
	// 저장은 UTC 강제지만 드라이버가 로컬 위치로 실어 올 수 있어 여기서 한 번 못 박는다.
	s.startedAt = s.startedAt.UTC()
	return &s, nil
}

// latestSessionSQL 은 stream_sessions_stream_idx (stream_id, started_at DESC) 축의 조회다.
//
// **상태 필터가 없는 것이 계약이다**(계획 4.1 ⑶): PDT 재귀식의 k−1 은 세션 무관 시간축이라
// ending·ended 세션도 기저 후보다. state 를 걸면 세션 경계에서 PDT 연속성이 끊긴다.
// session_id DESC 는 started_at 동률의 타이브레이커일 뿐 값에 뜻을 부여하지 않는다 —
// 인덱스가 2열이라 동률 묶음에서 정렬 노드가 끼는 것이 정상이다(스트림당 세션 수는 유계).
// 이 조회는 **새 세션 INSERT 전**이라 자기 자신이 잡히지 않는다.
const latestSessionSQL = `
SELECT session_id FROM stream_sessions
 WHERE stream_id = $1
 ORDER BY started_at DESC, session_id DESC
 LIMIT 1`

func (r *Registry) latestSession(ctx context.Context, tx pgx.Tx, streamID string) (string, error) {
	var id string
	err := tx.QueryRow(ctx, latestSessionSQL, streamID).Scan(&id)
	// 행이 없다 = 이 스트림의 첫 세션이다 — 기저 세션 부재(재귀식의 벽시계 항이 받는다).
	if errors.Is(err, pgx.ErrNoRows) {
		return "", nil
	}
	if err != nil {
		return "", fmt.Errorf("직전 세션 조회 실패 stream_id=%q: %w", streamID, err)
	}
	return id, nil
}

// liveSession 은 현 live 세션 행에서 읽는 값이다. 판정에 쓰는 네 열이 전부다.
type liveSession struct {
	id                string
	startedAt         time.Time
	targetDuration    int32
	discontinuityBase int64
}

// decide 는 DB 를 보지 않는 판정부다 — 현 live 세션(없으면 nil)과 입력만으로 갈래를 정한다.
//
// **순서가 계약이다**(설계 4.9.1 · 5.4.1):
//  1. TD 판정이 먼저다. 유입·관측과 무관하게 "이 조각이 현 세션의 TD 를 넘는가"가 갈래를
//     가른다 — 분할이면 새 세션이 이 조각에서 시작하므로 아래 하한은 자동으로 만족된다.
//  2. 귀속 하한은 **항상** 건다(장부 축). 연산과 무관하다.
//  3. 마지막에야 연산이 "현 세션이 없을 때 열 것인가"를 가른다.
func (r *Registry) decide(live *liveSession, in Input, now time.Time) (Decision, error) {
	switch in.Op {
	case OpenOrCurrent, CurrentOrOpenIfCorroborated, CurrentOnly:
	default:
		return Decision{}, fmt.Errorf("session: 세션 결정 연산이 지정되지 않았다(%d) stream_id=%q seq=%d",
			in.Op, in.StreamID, in.Seq)
	}

	// TD 판정은 현 live 행의 target_duration 을 피연산자로 쓴다 — 그래서 이 판정이
	// 트랜잭션 밖이 아니라 여기 있다(밖에서 읽으면 낡은 세션 값으로 판정한다).
	// 현 live 가 없으면 판정 자체가 성립하지 않고 개시 갈래로 간다.
	if live != nil && exceedsTargetDuration(in.DurationMS, live.targetDuration) {
		return openDecision(in, live), nil
	}
	if live != nil {
		// 귀속 하한(설계 5.4.1 ⑴) — 세션이 시작하기 한참 전의 조각은 그 세션의 것이 아니다.
		// 막는 대상은 시계 역행과 빠른 재방송이며, 위반은 거부(NULL)이지 오류가 아니다.
		if in.StartWallUTC.Before(live.startedAt.Add(-r.opt.FloorSlack)) {
			r.opt.Log.Warn("session_floor_rejected",
				"stream_id", in.StreamID, "seq", in.Seq,
				"start_wall_utc", in.StartWallUTC, "session_id", live.id,
				"started_at", live.startedAt, "slack", r.opt.FloorSlack)
			return Decision{Outcome: OutcomeNone}, nil
		}
		return Decision{Outcome: OutcomeCurrent, SessionID: live.id, BaseSessionID: live.id}, nil
	}

	// 여기부터는 현 live 세션이 없다 — 열 것인가를 연산이 가른다.
	switch in.Op {
	case OpenOrCurrent:
		return openDecision(in, nil), nil
	case CurrentOrOpenIfCorroborated:
		return r.decideCorroborated(in, now), nil
	}
	return Decision{Outcome: OutcomeNone}, nil
}

// decideCorroborated 는 ⓐ2 개시 자격이다(설계 6.5.2 · 5.4.1 ⑵⑶). 현 live 가 없을 때만 온다.
//
// **에폭 하한과 모호 구간의 순서가 판정을 가른다**: 모호 구간 [에폭−여유, 에폭) 은 하한을
// 통과한 것들 중 "에폭보다는 이른" 좁은 띠다. 하한을 먼저 보면 셋이 정확히 갈린다 —
// 하한 밖(옛 방송 잔존물, 조용히 접는다) / 모호 구간(경계가 흐려 안 연다, 신호를 남긴다) /
// 에폭 이후(연다). 자격 불충족은 전부 CurrentOnly 로 접히고, 현 live 가 없으므로 값은 빈다.
func (r *Registry) decideCorroborated(in Input, now time.Time) Decision {
	obs := in.Obs
	if !obs.Publishing || !obs.EpochKnown {
		return Decision{Outcome: OutcomeNone}
	}
	// 관측이 낡았으면 방증이 아니다. 시도마다 다시 재는 항이라 now 를 인자로 받는다.
	if now.Sub(obs.ObservedAt) > r.opt.ObsFresh {
		return Decision{Outcome: OutcomeNone}
	}
	if in.StartWallUTC.Before(obs.EpochStartedAt.Add(-obs.EpochSlack)) {
		return Decision{Outcome: OutcomeNone}
	}
	if in.StartWallUTC.Before(obs.EpochStartedAt) {
		// 여유가 0 이면 이 구간은 공집합이라 여기 닿지 않는다. 조건을 남겨 두는 이유는
		// 여유가 값 축이기 때문이다 — 0 이 아닌 값이 들어오는 순간 그대로 산다.
		r.opt.Log.Warn("session_epoch_ambiguous",
			"stream_id", in.StreamID, "seq", in.Seq,
			"start_wall_utc", in.StartWallUTC,
			"epoch_started_at", obs.EpochStartedAt, "epoch_slack", obs.EpochSlack,
			"note", "에폭 경계가 흐려 세션을 열지 않는다. 다음 확실한 조각이 연다")
		return Decision{Outcome: OutcomeNone}
	}
	return Decision{Outcome: OutcomeOpen}
}

// openDecision 은 개시 갈래의 결정이다 — Open 이 쓸 값(계획 3절 (가))을 함께 싣는다.
//
// split 이 nil 이면 비분할 개시(현 live 부재)이고, 값이 있으면 TD 분할이다:
// 세 개시 연산이 새 세션 행에 쓰는 값은 한 규칙이고 갈라지는 것은 두 가지뿐이다 —
// 불연속 기준의 승계 여부와, 직전 세션을 ending 으로 보내는지.
func openDecision(in Input, split *liveSession) Decision {
	p := &openPlan{
		streamID:       in.StreamID,
		seq:            in.Seq,
		startedAt:      in.StartWallUTC,
		targetDuration: openTargetDuration(in.DurationMS),
	}
	if split == nil {
		// discontinuity_base 는 DDL 기본값과 같은 0 이다 — 설계가 비분할 개시의 승계를
		// 규정하지 않았고 소비자(렌더)가 M4 라 승계 산식을 지어내지 않는다.
		return Decision{Outcome: OutcomeOpen, plan: p}
	}
	p.discontinuityBase = split.discontinuityBase // 설계 4.9.1 "discontinuity_base 승계"
	p.endingSessionID = split.id
	// 기저 세션은 곧 ending 으로 보낼 그 세션이다 — ID 를 손에 쥐고 있으므로 조회하지 않는다.
	// 조회로 찾으면 started_at 최신이 새 세션(또는 더 늦게 시작한 옛 세션)이라 어긋난다.
	return Decision{Outcome: OutcomeOpenFresh, BaseSessionID: split.id, plan: p}
}

// endSessionSQL 은 TD 분할에서 직전 세션을 닫는 문장이다.
//
// end_reason 의 'td_exceeded' 는 설계 4.9.1 이 OpenFresh 의 사유 인자로 쓴 문자열 그대로다
// (어휘를 새로 만들지 않는다). 장부가 "왜 끝났는가"를 잃지 않게 남긴다.
// 이 UPDATE 가 새 세션 INSERT 보다 **먼저**여야 한다 — 순서가 뒤집히면 한 스트림에 live
// 세션이 둘이 되려 하므로 stream_sessions_one_live_uq 가 막는다.
const endSessionSQL = `
UPDATE stream_sessions
   SET state = 'ending', ending_at = now(), end_reason = 'td_exceeded'
 WHERE session_id = $1`

// openSessionSQL 은 개시 트랜잭션의 유일한 세션 INSERT 다.
//
// 값을 대입하는 컬럼이 **7 개뿐인 것이 계약이다**(계획 3절 (가)) — 나머지는 DDL 기본값이
// 곧 규칙이고, 그래서 M3 는 stream_sessions DDL 을 건드리지 않는다(스키마 승인 표면 0).
// state 는 DDL 기본과 같은 값이지만 명시로 적는다: 개시가 무엇을 만드는지 문장이 말한다.
const openSessionSQL = `
INSERT INTO stream_sessions
    (session_id, stream_id, started_at, state, first_pdt, target_duration, discontinuity_base)
VALUES ($1, $2, $3, 'live', $4, $5, $6)`

// Open 은 결정이 개시 갈래일 때 세션 표에 쓰고 새 session_id 를 돌려준다(계획 4.1 ⑸).
//
// firstPDT 는 호출자가 기저 행에서 산출한 값이며 **INSERT 에 단일 대입**된다 —
// 사후 UPDATE 경로를 만들지 않는다. 세션의 first_pdt 는 개시 1회로 굳고 60일 잔존하므로,
// 나중에 고치는 길을 열어 두면 그 길로 잘못된 값이 들어온다.
//
// 오류는 감싸되 감추지 않는다: one_live_uq 경합(23505)도 그대로 올라가 호출자가
// errors.As 로 제약 이름을 보고 처분한다.
func (r *Registry) Open(ctx context.Context, tx pgx.Tx, d Decision, firstPDT time.Time) (string, error) {
	if d.plan == nil {
		return "", fmt.Errorf("session: 개시 갈래가 아닌 결정(%s)으로는 세션을 열 수 없다", d.Outcome)
	}
	if firstPDT.IsZero() {
		return "", fmt.Errorf("session: first_pdt 가 비었다 stream_id=%q seq=%d — 개시 1회로 굳는 값이라 영값을 쓰지 않는다",
			d.plan.streamID, d.plan.seq)
	}
	p := d.plan
	if p.endingSessionID != "" {
		if _, err := tx.Exec(ctx, endSessionSQL, p.endingSessionID); err != nil {
			return "", fmt.Errorf("직전 세션 종료 표시 실패 session_id=%q: %w", p.endingSessionID, err)
		}
	}
	id := sessionID(p.streamID, p.startedAt, p.seq)
	if _, err := tx.Exec(ctx, openSessionSQL,
		id, p.streamID, p.startedAt.UTC(), firstPDT.UTC(), p.targetDuration, p.discontinuityBase); err != nil {
		return "", fmt.Errorf("세션 개시 INSERT 실패 session_id=%q stream_id=%q: %w", id, p.streamID, err)
	}
	return id, nil
}

// sessionID 는 새 세션의 PK 를 만든다.
//
// kty 확정(2026-09-02) — 계약3 최초 규정 `S-{YYYYMMDD}-{HHMMSS}-{streamID}-{seq}`(UTC).
//
// 설계 원문에 생성 규칙이 없고(골든 픽스처 리터럴 `S-20260831-0107` 뿐), 계약3 도 규정하지
// 않는다. session_id 는 **전역 PK** 라 서로 다른 스트림이 같은 분에 시작하면 충돌하므로,
// 기존 결정적 파생 이디엄(index.S3Key)을 그대로 따르되 두 축을 넣는다:
//
//	스트림 축      — 스트림이 다르면 id 가 다르다(전역 PK 충돌 소멸)
//	재사용 불가 축 — 개시 행의 seq. seq 는 스트림 내 단조·재사용 금지가 불변식이라
//	                 (stream_id, seq) 가 이미 전역 유일성을 물리로 보증한다. 같은 스트림이
//	                 같은 초에 다시 열려도(TD 분할 직후) 새 seq 가 새 id 를 준다.
//
// 그 결과 stream_sessions_pkey 충돌은 규칙 자체로 도달 불가가 된다.
// 형식은 URL 안전 문자만 쓴다 — 되감기 URL 경로에 그대로 실린다(계약3 7-1).
//
// **이 함수 밖에서는 형식에 의존하지 않는다** — 어디서도 파싱하지 않는다.
// 그래서 규칙이 바뀌어도 갈아 끼울 자리가 여기 하나다.
func sessionID(streamID string, startWall time.Time, seq int64) string {
	utc := startWall.UTC()
	return fmt.Sprintf("S-%s-%s-%s-%d", utc.Format("20060102"), utc.Format("150405"), streamID, seq)
}

// exceedsTargetDuration 은 TD 판정이다 — 반올림한 초가 세션의 target_duration 을 넘는가.
func exceedsTargetDuration(durationMS int32, targetDuration int32) bool {
	return roundedSeconds(durationMS) > float64(targetDuration)
}

// openTargetDuration 은 새 세션의 target_duration 이다 — max(6, round(dur/1000)),
// 설계 4.9.1 의 공식 그대로다.
//
// 하한이 필요한 이유가 아니라 **개시 갈래에도 이 공식이 필요한 이유**를 적어 둔다:
// TD 판정은 현 live 세션 행을 읽으므로 세션이 없는 시점(방송 첫 조각·ending 직후 첫 조각)
// 에는 성립하지 않고 그대로 개시로 간다. 그때 값 규칙이 없으면 DDL 기본값 6 이 굳어,
// 8 초짜리 조각이 연 세션이 "TD 6 ∧ 개시 행 EXTINF 8" 로 장부에 남는다 — 발행 층(M4)이
// 그 세션을 영영 내보내지 못하고, TD 는 수명 중 변경 불가라 소급 복구도 안 된다.
func openTargetDuration(durationMS int32) int32 {
	if s := int32(roundedSeconds(durationMS)); s > minTargetDuration {
		return s
	}
	return minTargetDuration
}

// minTargetDuration 은 DDL 기본값과 같은 하한이다(ddl.go 의 target_duration DEFAULT 6).
const minTargetDuration int32 = 6

// roundedSeconds 는 밀리초를 초로 반올림한다.
//
// **정수 나눗셈을 쓰지 않는 자리가 여기 하나다**: 6500ms 는 7 이지 6 이 아니다.
// 정수 나눗셈이면 EXTINF 6.5 조각이 TD 6 세션에 들어가 분할이 일어나지 않고,
// 그 어긋남은 조각마다 조용히 쌓인다.
func roundedSeconds(durationMS int32) float64 {
	return math.Round(float64(durationMS) / 1000)
}
