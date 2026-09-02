package index

// 세션 결정 협력자의 계약(POK-195 M3 — 계획 3절 모듈 경계 · 4.1 트랜잭션 형상).
//
// 여기 있는 것은 **계약뿐이고 판정은 없다.** 판정의 집은 session 패키지이며,
// 이 패키지는 그 결과를 자기 트랜잭션 안에서 쓰는 쪽이다.

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

// SessionDecider 는 "이 조각이 어느 세션(회차)에 속하는가"를 정하는 협력자다.
//
// **인터페이스를 구현하는 쪽이 아니라 쓰는 쪽인 여기에 둔 이유**는 indexer.Adopter·
// UploadRequester 와 같다: 세션 판정의 정책과 장부 트랜잭션의 순서는 바뀌는 이유가 다르다.
// 그래서 index 는 session 패키지를 임포트하지 않고 조립 지점이 어댑터로 잇는다 —
// 여기서 임포트하면 "장부가 세션 정책을 안다"가 되어 두 축이 한 덩어리로 굳는다.
//
// **호출이 둘인 것이 계약이다**(계획 4.1): Decide 가 갈래와 기저 세션을 정하고,
// 그 사이에 이 패키지가 **자기 표에서** 기저 행을 읽어 first_pdt 를 산출한 뒤 Open 이 쓴다.
// 하나로 합치면 결정자가 stream_segments 를 읽어야 해 경계가 무너지고, 순서를 뒤집으면
// 세션을 연 뒤 first_pdt 를 UPDATE 하는 경로가 생긴다(그 값은 개시 1회로 굳어야 한다).
//
// tx 는 이 패키지가 연 트랜잭션이다 — 결정자는 트랜잭션을 만들지 않고 받는다.
// 실패는 감추지 않고 그대로 올린다: "정책상 안 열었다"(Decision 으로 표현)와
// "DB 가 막았다"(error)가 구분되지 않으면 재시도가 엉뚱한 방향으로 간다.
type SessionDecider interface {
	Decide(ctx context.Context, tx pgx.Tx, in SessionInput, now time.Time) (SessionDecision, error)
	Open(ctx context.Context, tx pgx.Tx, d SessionDecision, firstPDT time.Time) (sessionID string, err error)
}

// SessionOp 는 세션 결정 연산이다(설계 5.4 유입 표 · 6.5.2 네 갈래).
//
// 값이 1 부터인 이유는 영값을 유효한 연산으로 만들지 않기 위해서다 — 안 채운 필드가
// 조용히 어느 갈래로 접히면 배선 누락이 로그 한 줄 없이 새어 나간다.
// 유입 사유를 이 연산으로 접는 것은 호출자(indexer) 몫이다.
type SessionOp int

const (
	// SessionOpenOrCurrent 는 ⓐ1(실시간 유입 방증) 축이다 — 현 세션이 있으면 귀속하고
	// 없으면 연다. 파일·훅 유입 자체가 "지금 방송이 흐른다"의 방증이라 관측을 보지 않는다.
	SessionOpenOrCurrent SessionOp = iota + 1
	// SessionCurrentOrOpenIfCorroborated 는 ⓐ2(상태 방증) 축이다 — 현 세션이 없을 때는
	// 관측이 자격을 만족할 때만 연다. 스캔·슬레이트 유입은 옛 잔존물일 수 있기 때문이다.
	SessionCurrentOrOpenIfCorroborated
	// SessionCurrentOnly 는 열지 않는 축이다 — 있으면 귀속, 없으면 비운다.
	SessionCurrentOnly
)

// SessionObservation 은 판정에 쓰는 관측 스냅샷이다(mtxstate 의 관측을 접어 넘긴 것).
//
// 영값이 fail-closed 다 — EpochKnown=false 이므로 ⓐ2 는 성립하지 않는다(설계 5.4.1 ⑵).
// 에폭 여유를 등급이 아니라 **값**으로 받는 것은 session 패키지의 계약 그대로다.
type SessionObservation struct {
	Publishing     bool
	ObservedAt     time.Time
	EpochStartedAt time.Time
	EpochKnown     bool
	EpochSlack     time.Duration
}

// SessionSource 는 Insert 의 per-call 세션 결정 입력이다 — **호출자가 채운다.**
//
// 이 조각이 어느 유입으로 들어왔는지(연산)와 그때의 상태 관측이 전부다.
// 조각 자신의 값(스트림·seq·벽시계·길이)은 여기 싣지 않는다: 이미 Record 에 있고,
// 같은 값이 한 호출에 두 자리로 실리면 언젠가 둘이 어긋난다(파생값 이중 보관 금지).
type SessionSource struct {
	Op  SessionOp
	Obs SessionObservation
}

// SessionInput 은 결정자에게 내려가는 입력이다 — **Store 가 조립한다.**
// 조각에서 오는 네 값(귀속 하한·에폭 하한·TD 판정·세션 id 파생의 피연산자)에
// 호출자가 준 SessionSource 를 얹은 것이다.
type SessionInput struct {
	StreamID     string
	Seq          int64
	StartWallUTC time.Time
	DurationMS   int32
	SessionSource
}

// SessionPlan 은 결정자가 Decide 에서 만들어 Open 에서 되받는 **불투명 값**이다.
//
// index 는 이 값을 열어 보지 않는다 — 개시 갈래가 세션 행에 쓰는 값(설계 4.9.1·계획 3절)은
// 결정자 소유이고, 여기서 해석하면 그 규칙이 두 패키지에 복제된다. 결정과 쓰기가 같은
// 입력을 본다는 것을 이 왕복이 구조로 보장한다.
type SessionPlan interface{}

// SessionDecision 은 갈래와 두 세션 ID 다 — Store 가 carrier 와 기저 행 조회에 쓴다.
type SessionDecision struct {
	// Opens 가 참이면 Open 을 불러야 한다(새 세션 개시 · TD 분할).
	Opens bool
	// SessionID 는 이 조각이 귀속될 현 세션이다. "" 면 비귀속이다.
	// 개시 갈래에서는 아직 "" 이고 값은 Open 이 돌려준다.
	SessionID string
	// BaseSessionID 는 PDT 기저 행을 찾을 세션이다. "" 면 기저 세션 부재다.
	// 귀속 세션과 다를 수 있다 — 개시 갈래는 **직전 세션**에서 이어받아야
	// 세션 경계에서 PDT 가 끊기지 않는다.
	BaseSessionID string
	Plan          SessionPlan
}

// PlaybackKeyFunc 는 ③ 조각 키 파생이다(설계 5.2 — playback.SegKey).
//
// 함수로 주입하는 이유: 파생은 순수하고 구현이 하나이며 상태가 없다. 이 한 줄을 위해
// 인터페이스를 만들면 읽는 사람이 구현을 찾아 헤맨다(원칙 5).
type PlaybackKeyFunc func(streamID string, seq int64) (string, error)

// noSessionDecider 는 결정자를 끼우지 않은 프로세스의 널 오브젝트다.
//
// 언제나 비귀속·비개시라 carrier 3열이 NULL 로 남고 ⓒ 가 주조를 막는다 — 배선을
// 빠뜨려도 방향은 안전(비주조)하다(설계 S3). 이것이 M2 시점의 동작과 같은 형상이며,
// 그 동작을 재는 테스트가 그대로 살아 있게 하는 자리이기도 하다.
type noSessionDecider struct{}

func (noSessionDecider) Decide(context.Context, pgx.Tx, SessionInput, time.Time) (SessionDecision, error) {
	return SessionDecision{}, nil
}

// Open 은 도달하지 않는다(Decide 가 Opens=false 만 돌려주므로). 도달하면 배선이 아니라
// 이 패키지의 순서가 깨진 것이므로 조용히 넘기지 않는다.
func (noSessionDecider) Open(context.Context, pgx.Tx, SessionDecision, time.Time) (string, error) {
	return "", errors.New("index: 세션 결정자가 없는데 개시가 요청됐다")
}

// noPlaybackKey 는 키 파생을 끼우지 않은 프로세스의 널 오브젝트다 —
// 파생 실패와 같은 갈래로 흘러 playback_s3_key 만 NULL 로 남는다(설계 5.2).
func noPlaybackKey(string, int64) (string, error) {
	return "", errors.New("index: ③ 키 파생 함수가 주입되지 않았다")
}
