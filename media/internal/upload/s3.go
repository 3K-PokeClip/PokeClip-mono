package upload

import "errors"

// putErrClass 는 브레이커 집계에만 쓰는 오류 분류다.
//
// 결정 8‴의 "무분류"와 모순되지 않는다 — 그 무분류는 **재시도 여부**에 대한 것이다.
// 어떤 오류든 RetryMax 까지 똑같이 재시도한다. 이 함수는 재시도가 전부 끝난 뒤
// "이 실패가 설정 문제인가"만 판정해 브레이커 streak 에 넣는다.
// 목적도(재시도 vs 전역 차단) 시점도(매 시도 vs 행 종결) 다르다(CX-2 ⑤).
type putErrClass int

const (
	putErrSoft putErrClass = iota
	putErrHard
)

func (c putErrClass) String() string {
	if c == putErrHard {
		return "hard"
	}
	return "soft"
}

// hardStatuses 는 "설정이 틀렸다"로 읽는 HTTP 상태다.
// 401·403 = 자격증명·권한, 404 = 버킷 이름.
var hardStatuses = map[int]struct{}{401: {}, 403: {}, 404: {}}

// hardCodes 는 같은 뜻의 오류 코드 문자열이다. PermanentRedirect 는 리전 불일치다.
var hardCodes = map[string]struct{}{
	"PermanentRedirect":     {},
	"AccessDenied":          {},
	"InvalidAccessKeyId":    {},
	"SignatureDoesNotMatch": {},
	"NoSuchBucket":          {},
}

// classifyPutError 는 마지막 시도의 오류로 판정한다.
//
// 상태 코드와 코드 문자열을 둘 다 본다 — 엔드포인트 구현(MinIO 등)에 따라 한쪽만
// 채워질 수 있다. **둘 다 없으면 soft 다.** soft 가 기본값인 것이 중요하다:
// 알 수 없는 오류를 hard 로 오분류하면 브레이커가 전역으로 열려 정상 트래픽까지 막는다(R2).
//
// 구조적 인터페이스로 받는 이유: 상태 코드는 awshttp.ResponseError 가,
// 코드 문자열은 smithy.APIError 가 제공하는데, 둘 다 메서드 하나로 식별된다.
// 타입 자체에 묶으면 이 판정이 SDK 버전에 인질로 잡힌다.
func classifyPutError(err error) putErrClass {
	if err == nil {
		return putErrSoft
	}
	var withStatus interface{ HTTPStatusCode() int }
	if errors.As(err, &withStatus) {
		if _, ok := hardStatuses[withStatus.HTTPStatusCode()]; ok {
			return putErrHard
		}
	}
	var withCode interface{ ErrorCode() string }
	if errors.As(err, &withCode) {
		if _, ok := hardCodes[withCode.ErrorCode()]; ok {
			return putErrHard
		}
	}
	return putErrSoft
}
