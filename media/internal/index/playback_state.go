package index

import "time"

// PlaybackPhase 는 회차 단계다. live 국면의 원천은 M5 루프 메모리라 상태 전달 · 기동 적재에는 Live 가 없다.
// PhaseLive 는 LookupRoundPlayback(회차 하나 DB 읽기)만 돌려준다.
//
// 값이 1 부터인 이유는 영값을 유효한 국면으로 만들지 않기 위해서다(Axis 와 같은 이디엄).
// M5 Drain 변환표가 이 번호에 기대고 0 · 범위 밖 값을 무효로 버린다.
type PlaybackPhase int

const (
	PhaseUnknown PlaybackPhase = iota + 1 // 장부에 없음 · 만료로 조회 제외
	PhaseLive                             // LookupRoundPlayback 전용 — 그 회차에 live 세션 있음
	PhaseEnding                           // 회차 완결 전(닫힘 전 · 닫힘 뒤 정산 · VOD 동결 중)
	PhaseVOD                              // 회차 완결 · 꼬리 사슬 VOD 문서 있음
	PhaseEnded                            // 회차 완결 · VOD 없음(freeze_failed · 빈 동결)
)

// PlaybackState 는 스트림 하나의 최신 회차 상태다. 스트림 id 는 칸으로 두지 않고
// PlaybackSink 의 인자 · 기동 적재 맵의 키로 함께 간다.
type PlaybackState struct {
	Phase   PlaybackPhase
	RoundID string // 계약9 streamId(장부에 없는 스트림의 Unknown 이면 빈 값)
	// LatestSessionID 는 그 회차의 최신 세션이다 — 회차에 live 가 있으면 그 live, 없으면 started_at 최대
	// (3.3 선택 규칙 · ClosableRound.LatestSessionID 와 같은 값 — 경계 확정 2).
	// M5 Final.LatestSessionID 가 이 값을 받는다. 꼬리 사슬 머리는 VODKey 안에만 있고 M5 는 읽지 않는다.
	LatestSessionID string
	VODKey          string    // PhaseVOD 일 때만
	FirstPDT        time.Time // PhaseVOD 일 때만 — 그 VOD 문서 첫 조각 PDT
	HeadPDT         time.Time // PhaseVOD 일 때만 — 그 VOD 문서 마지막 조각 끝(배타적)
}

// PlaybackSink 는 M5 가 준다. M6 는 루프 고루틴(완료 case)에서만 부른다 — 기동 적재는 루프 기동 전 한 번.
type PlaybackSink func(streamID string, st PlaybackState)

// PlaybackChanged 는 상태 전달의 유일한 입구다. 장부를 바꾼 쪽은 커밋 뒤 이것만 부르고 값을 계산해 보내지 않는다.
// 막히지 않고 여러 고루틴(루프 차선 결과 · 4D 수신 · 만료)에서 불러도 된다.
// 루프가 holdTick 의 받은편지함 비우기 걸음(3.2 순서)에 비운다.
type PlaybackChanged func(streamID string)
