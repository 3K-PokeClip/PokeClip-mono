// Package publish 는 되감기 목록(매니페스트)을 객체 저장소에 발행한다 — 설계 3.2 의 rewind/publish
// (「세대 규약과 동결」).
//
// 저장소는 Store 경계 뒤에 있다. 쓰기는 늘 조건부이고(첫 판 If-None-Match: * · 갱신 If-Match), 목록의
// 자기기술은 본문 밖 사용자 메타데이터로 같은 요청에 싣는다(설계 4.4.2 — 본문은 순수 HLS 다). 읽기는
// Head(ETag · 메타)와 Get(본문)으로 나뉜다 — 화해는 Head 만 쓰므로 본문을 읽지 않는다.
//
// 지키는 불변식(프로필 4절): S3 LIST 를 쓰지 않는다. Store 에는 목록 연산이 없다 — 객체가 있는지는
// 인덱스(PG)가 말하고, 발행 경로가 저장소에 묻는 것은 이름을 아는 키 하나의 머리나 본문뿐이다.
package publish

import (
	"context"
	"errors"
)

// Store 는 되감기 목록을 쓰고 읽는 객체 저장소다 — 설계 3.2 가 이름을 준 경계이고, 구현은 S3(S3Store)와
// 테스트의 인메모리 가짜 둘이다.
//
// 쓰기는 언제나 조건부다. 그 목록 URL 의 첫 발행은 키에 객체가 없을 때만(IfAbsent), 갱신은 직전 판의
// ETag 가 그대로일 때만(IfMatch) 쓴다. 조건 없는 쓰기는 표현할 수 없다 — 두 writer 가 서로의 판을 덮지
// 못한다는 세대 규약(설계 4.4)의 바닥이자 계획 4.5 A1 G8 증명의 전제 (가)다.
//
// 오류는 errors.Is 로 가른다. 계획 4.4 [B-1] 의 응답 분기다:
//
//	ErrPreconditionFailed  412 — 조건이 맞지 않았다(IfAbsent 인데 객체가 있다 · IfMatch 인데 ETag 가 다르다)
//	ErrNotFound            404 — IfMatch 인데 키에 객체가 없다 · Get 할 객체가 없다
//	ErrConflict            409 — 같은 키에 충돌하는 요청이 진행 중이었다(재시도 규칙은 [B-2] — 호출자 몫)
//
// 쓰기에서 이 셋은 적용되지 않았다는 뜻이다. 그 밖의 오류(네트워크 · 5xx · ctx 만료)는 결과를 모른다 —
// 쓰기가 적용됐을 수도 있으므로 호출자는 Head 로 확인한다. 다만 요청 전 검증 오류(예: 빈 ETag 의
// IfMatch)는 요청을 보내지 않으므로 확실한 미적용이다.
//
// ctx 에는 마감이 있어야 한다. Store 는 스스로 시간을 재지 않고 ctx 가 끝날 때까지 기다린다 — S3Store 가
// 쓰는 SDK 기본 HTTP 클라이언트에는 전체 타임아웃도 응답 헤더 타임아웃도 없다.
//
// 모든 메서드는 여러 고루틴에서 동시에 불러도 된다.
type Store interface {
	// Put 은 cond 가 맞을 때만 key 에 body 를 쓰고 새 판의 ETag 를 돌려준다. meta 는 사용자 메타데이터로
	// 같은 요청에 실리고 이전 판의 메타를 통째로 갈아 끼운다. 메타 키는 소문자로 저장된다([B-3]).
	//
	// 한 번 부르면 저장소에 한 번 보낸다 — 재시도는 호출자 몫이다(설계 4.4.5 RC-19 의 시도 상한이
	// 실제 요청 수와 맞아야 한다).
	Put(ctx context.Context, key string, cond Precondition, body []byte, meta map[string]string) (etag string, err error)

	// Head 는 key 의 현재 판의 ETag 와 사용자 메타를 돌려준다. 본문은 읽지 않는다. 객체가 없으면
	// 오류가 아니라 Exists 가 거짓인 Stat 이다(설계 4.4.3 R1 · R4).
	Head(ctx context.Context, key string) (Stat, error)

	// Get 은 key 의 현재 본문을 돌려준다. 객체가 없으면 ErrNotFound 다.
	Get(ctx context.Context, key string) ([]byte, error)
}

// Precondition 은 Put 의 쓰기 조건이다 — IfAbsent 나 IfMatch 로 만든다. 영값은 IfAbsent 와 같다: 조건
// 없는 쓰기는 이 형으로 나타낼 수 없다.
type Precondition struct {
	// ifMatch 가 참이면 If-Match: etag, 거짓이면 If-None-Match: * 다.
	ifMatch bool
	etag    string
}

// IfAbsent 는 키에 객체가 없을 때만 쓰는 조건이다(If-None-Match: *) — 그 목록 URL 의 첫 발행.
func IfAbsent() Precondition { return Precondition{} }

// IfMatch 는 키의 현재 판 ETag 가 etag 일 때만 쓰는 조건이다(If-Match) — 갱신. etag 는 Put 이나 Head 가
// 돌려준 값을 그대로 넘긴다(따옴표까지 — 불투명한 문자열이다). 빈 etag 면 Put 이 보내지 않고 오류를
// 돌려준다.
func IfMatch(etag string) Precondition { return Precondition{ifMatch: true, etag: etag} }

// check 는 cond 를 보낼 수 있는지 본다 — 빈 ETag 의 IfMatch 는 보내지 않는다(errEmptyETag).
func (c Precondition) check() error {
	if c.ifMatch && c.etag == "" {
		return errEmptyETag
	}
	return nil
}

// Stat 은 Head 가 본 객체의 머리다 — 본문 없이 ETag 와 사용자 메타만 든다.
type Stat struct {
	// Exists 는 키에 객체가 있는가다. 거짓이면 나머지는 영값이다.
	Exists bool
	// ETag 는 현재 판의 ETag 다. 저장소가 준 모양 그대로이고 다음 IfMatch 에 그대로 넘긴다.
	ETag string
	// Meta 는 사용자 메타데이터다. 키는 소문자다([B-3]). 해석은 호출자 몫이다.
	Meta map[string]string
}

var (
	// ErrPreconditionFailed 는 쓰기 조건이 맞지 않아 쓰지 않았다는 뜻이다(HTTP 412).
	ErrPreconditionFailed = errors.New("publish: 쓰기 조건 불일치")
	// ErrNotFound 는 키에 객체가 없다는 뜻이다(HTTP 404) — IfMatch 쓰기와 Get 에서 온다.
	ErrNotFound = errors.New("publish: 객체 없음")
	// ErrConflict 는 같은 키에 충돌하는 요청이 진행 중이어서 쓰지 않았다는 뜻이다(HTTP 409).
	ErrConflict = errors.New("publish: 동시 요청 충돌")
)

// errEmptyETag 는 빈 ETag 로 만든 IfMatch 다. SDK 는 빈 값이어도 If-Match 헤더를 싣고(nil 만 거른다 —
// service/s3 v1.106.3 PutObject 직렬화) 저장소가 빈 조건을 어떻게 읽을지는 정해진 바가 없다. 조건 없는
// 쓰기로 읽히면 남의 판을 덮으므로 보내기 전에 막는다.
var errEmptyETag = errors.New("publish: IfMatch 의 ETag 가 비었다")
