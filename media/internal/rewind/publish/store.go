// Package publish 는 되감기 목록(매니페스트)을 객체 저장소에 발행한다 — 설계 3.2 의 rewind/publish
// (「세대 규약과 동결」).
//
// 저장소는 Store 경계 뒤에 있다. 목록 쓰기는 늘 조건부이고(첫 판 If-None-Match: * · 갱신 If-Match), 목록의
// 자기기술은 본문 밖 사용자 메타데이터로 같은 요청에 싣는다(설계 4.4.2 — 본문은 순수 HLS 다). 읽기는
// Head(ETag · 메타)와 Get(본문 · 그 응답의 ETag)으로 나뉜다 — 화해는 Head 만 쓰므로 본문을 읽지 않는다.
//
// 지키는 불변식(프로필 4절): S3 LIST 를 쓰지 않는다. Store 에는 목록 연산이 없다 — 객체가 있는지는
// 인덱스(PG)가 말하고, 발행 경로가 저장소에 묻는 것은 이름을 아는 키 하나의 머리나 본문뿐이다.
package publish

import (
	"context"
	"errors"
	"fmt"
	"strings"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// Store 는 되감기 목록을 쓰고 읽는 객체 저장소다 — 설계 3.2 가 이름을 준 경계이고, 구현은 S3(S3Store)와
// 테스트의 인메모리 가짜 둘이다.
//
// 목록 쓰기는 언제나 조건부다. 그 목록 URL 의 첫 발행은 키에 객체가 없을 때만(IfAbsent), 갱신은 직전 판의
// ETag 가 그대로일 때만(IfMatch) 쓴다. 목록 키에 조건 없는 쓰기는 표현할 수 없다 — 두 writer 가 서로의 판을 덮지
// 못한다는 세대 규약(설계 4.4)의 바닥이자 계획 4.5 A1 G8 증명의 전제 (가)다. 조건 없이 쓰고 지우는 Copy · Delete 는
// VOD 사본(조각 · init 객체) 몫이다 — Copy 는 ③ 원본에서 그 사본 키로만, Delete 는 사본 키(와 탐침 원본)만 받는다(checkCopy
// · checkDelete). ③ 원본과 목록 문서는 이 둘로 바뀌지 않는다.
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

	// Get 은 key 의 현재 본문과 같은 응답에 실린 그 판의 ETag 를 돌려준다. 객체가 없으면 ErrNotFound 다. 본문이 그
	// 키 축의 상한(VOD 문서 rewind.MaxVODBodyBytes · 되감기 목록 rewind.MaxBodyBytes + rewind.SealLineBytes)을 넘으면
	// errBodyOverLimit 다.
	//
	// 읽은 본문을 보고 그 판을 갈아 끼울 때는 이 ETag 로 IfMatch 한다. 앞서 본 Head 의 ETag 로 걸면 Head 와 Get
	// 사이에 판이 바뀌었다가 되돌아온 경우(A → B → A) 읽지 않은 판 A 를 덮는다.
	Get(ctx context.Context, key string) (body []byte, etag string, err error)

	// Copy 는 같은 버킷의 src 객체를 dst 로 서버 측 복사한다(CopyObject · 메타 지시 COPY) — VOD 사본을 만든다. 사본의
	// 본문 · 사용자 메타 · 머리(Content-Type · Cache-Control)는 원본 그대로다. 암호화는 원본을 따르지 않는다 — 암호화
	// 머리를 싣지 않으므로 대상 버킷의 기본 암호화가 정한다. dst 에 객체가 있으면 조건 없이 덮어쓴다(사본은 원본과 같은
	// 바이트라 결과가 같다). src 가 없으면 ErrNotFound 다. 그 밖의 오류는 결과 모름이다 — 다시 불러도 결과가 같다.
	//
	// 한 번 부르면 저장소에 한 번 보낸다 — 재시도는 호출자 몫이다. src 가 ③ 조각 · init 키가 아니거나 dst 가 그 사본 키
	// (playback.VODPath(src))가 아니면 보내지 않고 errBadObjectKey 다(checkCopy).
	Copy(ctx context.Context, src, dst string) error

	// Delete 는 keys 를 지운다(DeleteObjects · maxDeleteKeys 개씩 · 없는 키도 성공). 한 묶음의 요청이 실패하거나 응답에
	// 못 지운 키가 있으면 그 묶음에서 멈추고 오류다(센티널이 아니다). 다시 부르면 같은 키를 처음부터 다시 보낸다 — 이미
	// 지운 키를 다시 지워도 성공이다. keys 가 비면 보내지 않고 성공이다.
	//
	// 묶음마다 저장소에 한 번 보낸다 — 재시도는 호출자 몫이다. 키는 사본 키(playback.ParseVODPath)여야 하고 예외는 탐침
	// 원본뿐이다 — 하나라도 아니면 아무것도 보내지 않고 errBadObjectKey 다(checkDelete).
	Delete(ctx context.Context, keys []string) error
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

// IfMatch 는 키의 현재 판 ETag 가 etag 일 때만 쓰는 조건이다(If-Match) — 갱신. etag 는 Put · Head · Get 이
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

// errBadObjectKey 는 Copy · Delete 에 보낼 수 없는 키다 — 요청을 보내지 않았으므로 확실한 미적용이다.
var errBadObjectKey = errors.New("publish: Copy · Delete 에 쓸 수 없는 키")

// probeStreamPrefix 는 객체 탐침(계획 ①-9 · 판정 D)의 시험 스트림 이름 접두다 — 탐침은 dvr/pcprobe-…/ 원본을 올려 사본을 만든
// 뒤 둘 다 지운다. 실제 스트림 ID 는 이 접두를 쓰지 않는다(탐침의 이름 검사 — E31).
const probeStreamPrefix = "pcprobe-"

// checkCopy 는 Copy(src, dst)를 보낼 수 있는지 본다 — src 는 ③ 조각 · init 키(dvr/{s}/seg/{seq}.m4s · dvr/{s}/init/{세션}.mp4)
// 이고 dst 는 그 사본 키(playback.VODPath(src))여야 한다. 아니면 errBadObjectKey 다. 인자를 뒤바꾸거나 다른 원본의 사본 자리를
// 주면 조건 없는 복사가 원본(dvr/) · 남의 사본 · 목록 문서를 덮는다 — 버킷 버전 관리가 없어 되돌릴 수 없다.
func checkCopy(src, dst string) error {
	want, err := playback.VODPath(src)
	if err != nil {
		return fmt.Errorf("%w: 원본 %.64q 는 ③ 조각 · init 키가 아니다", errBadObjectKey, src)
	}
	if dst != want {
		return fmt.Errorf("%w: 대상 %.64q 는 원본의 사본 키 %.64q 가 아니다", errBadObjectKey, dst, want)
	}
	return nil
}

// checkDelete 는 keys 전부를 지워도 되는지 본다 — 앞 묶음만 지운 채 멈추지 않게 보내기 전에 전부 본다. 키마다 사본 키
// (playback.ParseVODPath 가 받는 vod/ 조각 · init 키)여야 한다. 예외는 탐침 원본 하나다(probeStreamPrefix 스트림의 dvr/ 조각
// · init 키) — 탐침이 자기가 올린 원본을 지운다. 그 밖(③ 원본 · 목록 문서 · 이상한 키)은 errBadObjectKey 다 — 원본을 지우면
// 버킷 버전 관리가 없어 되살릴 수 없다.
func checkDelete(keys []string) error {
	for _, k := range keys {
		if _, err := playback.ParseVODPath(k); err == nil || isProbeSource(k) {
			continue
		}
		return fmt.Errorf("%w: %.64q 는 사본 키(vod/{s}/seg/{seq}.m4s · vod/{s}/init/{세션}.mp4)도 탐침 원본도 아니다", errBadObjectKey, k)
	}
	return nil
}

// isProbeSource 는 key 가 탐침 원본 — 스트림 이름이 probeStreamPrefix 로 시작하는 ③ 조각 · init 키 — 인지다.
func isProbeSource(key string) bool {
	vod, err := playback.VODPath(key)
	if err != nil {
		return false
	}
	obj, err := playback.ParseVODPath(vod)
	return err == nil && strings.HasPrefix(obj.StreamID, probeStreamPrefix)
}

// errBodyOverLimit 는 Get 한 본문이 그 키 축의 상한을 넘는다는 뜻이다 — 우리가 쓴 판은 발행 전 검사 S5 의 종류별 상한
// 안이거나, 그 안의 열린 본문에 봉인 줄 하나를 더한 판이므로(dvrGetBodyLimit) 상한을 넘는 본문은 우리가 쓴 판이 아니다. 구현이 이 패키지의 둘뿐이라(Store 설명) 내보내지 않는다.
var errBodyOverLimit = errors.New("publish: 본문이 키 축의 상한을 넘는다(우리가 쓴 판이 아니다)")
