// Package status 는 스트림별 되감기 상태를 정한다 — 제어면(control)이 답하는 값의 도출 규칙(Derive), 그 값을
// 스트림마다 모은 불변 스냅숏(Board), 회차 조회의 첫 단계(FindRound), M6 상태 한 건의 변환(FromPlaybackState)이다
// (설계 M5 2.1 · 4.1).
//
// 순수 패키지다. 루프 고루틴이 Board 를 만들어 Holder 에 넣고, HTTP 고루틴은 Holder 로 읽기만 한다. 그래서 처리기는
// 캐시 · 루프의 종료 항목을 직접 보지 않는다(「한 고루틴 소유」 계약 — R1 · R54 ①).
package status

import "time"

// Mode 는 응답의 mode 다. 빈 값은 JSON null — 국면을 아직 모른다.
type Mode string

// 응답 mode 의 값 넷(계약3 7-2절).
const (
	ModeLive   Mode = "live"
	ModeEnding Mode = "ending"
	ModeVOD    Mode = "vod"
	ModeEnded  Mode = "ended"
)

// Status 는 응답 하나다(계약3 7-2절 키 다섯). 시각 영값은 JSON null 이다.
type Status struct {
	Ready    bool
	Mode     Mode
	URL      string    // 절대 URL — /dvr/… 또는 /vod/…
	FirstPDT time.Time // 목록 첫 조각의 PDT
	HeadPDT  time.Time // 목록 마지막 조각의 끝(배타적) — 계약 headPdt
}

// Answer 는 Board · 변환이 내는 답의 종류다(보완 2 ㉡ — Found 는 200 전용).
type Answer int

// 값이 1 부터인 것은 채우지 않은 칸이 어느 답으로도 읽히지 않게 하려는 것이다.
const (
	Found       Answer = iota + 1 // 200 — 이 Status 로 답한다
	NotFound                      // 404 — 스트림 경로: 캐시 · 종료 항목 둘 다 없음(부팅 감시 뒤) · 발행 꺼짐 / 변환: Unknown
	Unavailable                   // 503 — 부팅 감시 전의 모르는 것 · 스냅숏 낡음 · 첫 스냅숏 전
	Miss                          // 회차 경로 전용 — Board 가 그 회차를 모른다. 처리기가 RoundLookup 으로 간다
)

// Final 은 한 스트림의 종료 뒤 항목이다. Status 는 FromPlaybackState 결과(Found)이거나 Forget 잠정 값(ending)이다.
type Final struct {
	RoundID         string // 계약9 streamId. Forget 잠정 항목은 빈 값
	LatestSessionID string // 종료 회차의 최신 세션(started_at 최대) — 경계 확정 2
	Status          Status // ending · vod · ended 가운데 하나
}

// Range 는 소유 회차의 발행 판이다 — 되감기 목록 URL 과 그 범위(4.3절).
type Range struct {
	URL         string
	First, Head time.Time
}

// Input 은 스트림 하나의 도출 입력이다. 루프가 캐시 · 발행 상태 · 종료 항목에서 채운다.
type Input struct {
	// CacheKnown 은 캐시가 그 스트림을 든다(뷰 · 부정 표식 · 적재 중). Loading 은 적재 중, HasView 는 컷오프 있는
	// 뷰가 있다.
	CacheKnown, Loading, HasView bool
	// Owner 는 캐시의 소유 회차다. nil 이면 없다(적재 중 · 뷰 없음 · 회차 없음 · 강등).
	Owner *OwnerSession
	// Published 는 소유 회차 목록의 발행 판이다. nil 이면 아직 없다.
	Published *Range
	// Final 은 그 스트림의 종료 항목이다. nil 이면 없다.
	Final *Final
}

// OwnerSession 은 캐시 소유 회차의 도출 칸이다.
type OwnerSession struct {
	SessionID    string
	State        string // live · ending · ended
	InitUploaded bool
}
