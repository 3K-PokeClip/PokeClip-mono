// Package s3errbody 는 2xx 가 아닌 S3 응답 본문을 SDK 가 읽는 양을 제한한다.
//
// SDK 는 오류 응답 본문을 상한 없이 메모리에 복사한다(service/s3 v1.106.3 deserializers.go). 고장 나거나 적대적인 엔드포인트가
// 거대한 오류 본문을 보내면 사이드카가 그만큼 메모리를 잡는다. 이 패키지는 SDK 의 HTTP 클라이언트를 감싸 그 본문을 MaxBytes 까지만
// 읽게 한다. 상태 코드는 그대로 남으므로 상태 코드에 기댄 오류 분류는 바뀌지 않는다. S3 오류 XML 의 크기는 실측 1건(2929B)과
// 추정(수십 KB 이하)뿐이다 — 상한을 넘는 오류 본문은 코드 문자열을 잃고 상태 코드로만 분류된다.
//
// 고정판(service/s3 v1.106.3) 가정: 사이드카가 쓰는 PutObject · HeadObject · HeadBucket · GetObject 는 S3 의 「200 인데 오류」
// 커스터마이즈 비대상이라 오류 본문은 언제나 2xx 가 아닌 응답으로 온다. SDK 판을 올려 이 가정이 깨지면 sdkassume_test.go 의 가드
// 시험이 FAIL 한다 — 그때 이 패키지의 범위를 다시 정한다.
package s3errbody

import (
	"errors"
	"io"
	"net/http"

	"github.com/aws/aws-sdk-go-v2/service/s3"
)

// MaxBytes 는 2xx 가 아닌 S3 응답 본문을 읽는 상한이다. S3 오류 XML 은 실측 1건(2929B) · 추정상 수십 KB 이하다 — 넘으면 상태
// 코드로만 분류된다(코드 문자열 상실).
const MaxBytes = 64 << 10

// ErrTooLarge 는 2xx 가 아닌 응답 본문이 MaxBytes 를 넘었다는 뜻이다. SDK 는 이것을 역직렬화 오류로 감싸고, 상태 코드는
// awshttp.ResponseError 에 그대로 남는다. 한 번 넘은 뒤의 Read 는 모두 이 오류를 내고 원 본문을 다시 읽지 않는다.
var ErrTooLarge = errors.New("s3errbody: 오류 응답 본문이 상한을 넘는다")

// Limit 은 s3.New · s3.NewFromConfig 의 옵션 함수다. SDK 가 이미 확정한 HTTP 클라이언트(주입분 또는 기본)를 감싼다. SDK 는 옵션
// 함수보다 먼저 클라이언트를 확정하므로(service/s3 v1.106.3 New) o.HTTPClient 는 nil 이 아니다.
//
// awsconfig.WithHTTPClient 로 감싼 클라이언트를 넘기면 안 된다 — 설정 적재의 CA 묶음 해석은 SDK 의 BuildableClient 가 아니면
// 실패한다(config v1.32.34 resolve.go). 감싸개는 요청을 다시 보내지 않는다(재시도의 소유자는 호출자다).
func Limit(o *s3.Options) {
	o.HTTPClient = client{next: o.HTTPClient}
}

// client 는 2xx 가 아닌 응답의 본문을 limitedBody 로 바꾸는 s3.HTTPClient 다. 상태가 없어 여러 고루틴이 함께 써도 된다 —
// 계수는 응답마다 limitedBody 안에 있다.
type client struct {
	next s3.HTTPClient
}

// Do 는 next 에 요청을 한 번 보낸다. 오류가 있거나 응답이 nil 이면 그대로 돌려준다(nil 응답은 smithy-go v1.27.6 전송기
// transport/http/client.go 가 빈 응답으로 바꾼다). 2xx(SDK 와 같은 200 <= code < 300)이거나 본문이 nil 이면 원 응답을 그대로
// 돌려준다.
func (c client) Do(r *http.Request) (*http.Response, error) {
	resp, err := c.next.Do(r)
	if err != nil || resp == nil {
		return resp, err
	}
	if (resp.StatusCode >= 200 && resp.StatusCode < 300) || resp.Body == nil {
		return resp, nil
	}
	resp.Body = &limitedBody{rc: resp.Body, r: io.LimitedReader{R: resp.Body, N: MaxBytes + 1}}
	return resp, nil
}

// limitedBody 는 원 본문을 MaxBytes 까지 통과시키는 ReadCloser 다. MaxBytes+1 째 바이트를 읽으면 그 바이트는 버리고 ErrTooLarge 를
// 내며, 그 뒤의 Read 도 모두 ErrTooLarge 다 — 원 본문에서는 MaxBytes+1 바이트 넘게 읽지 않는다. Close 는 원 본문을 닫는다.
type limitedBody struct {
	rc       io.ReadCloser
	r        io.LimitedReader // 남은 허용량 N 은 MaxBytes+1 에서 시작한다(한 바이트 더 읽어야 상한과 초과를 가른다)
	tooLarge bool
}

func (b *limitedBody) Read(p []byte) (int, error) {
	if b.tooLarge {
		return 0, ErrTooLarge
	}
	n, err := b.r.Read(p)
	if b.r.N > 0 {
		return n, err
	}
	// 허용량을 다 썼다 — 이번에 읽은 마지막 바이트가 MaxBytes+1 째다.
	b.tooLarge = true
	return n - 1, ErrTooLarge
}

func (b *limitedBody) Close() error {
	return b.rc.Close()
}
