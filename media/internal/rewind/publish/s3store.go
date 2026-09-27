package publish

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"

	"github.com/aws/aws-sdk-go-v2/aws"
	awshttp "github.com/aws/aws-sdk-go-v2/aws/transport/http"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/smithy-go"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
)

// S3Store 는 Store 의 S3 구현이다 — 버킷 하나에 쓰고 읽는다. upload.Putter(세그먼트 PUT — 조건 · 메타
// 없음)와는 다른 경계다.
type S3Store struct {
	client *s3.Client
	bucket string
}

// NewS3Store 는 bucket 에 쓰는 S3Store 를 만든다. 자격증명 · 리전 · 엔드포인트는 client 를 만든 조립점이
// 정한다. 시도 수와 본문 모양은 client 설정과 무관하게 요청마다 고정한다(requestOptions).
func NewS3Store(client *s3.Client, bucket string) *S3Store {
	return &S3Store{client: client, bucket: bucket}
}

// Put 은 Store.Put 이다 — cond 를 조건 헤더 하나(If-None-Match: * 또는 If-Match)로, meta 를
// x-amz-meta-* 헤더로 같은 요청에 싣는다. 200 인데 ETag 가 없으면 성공으로 보고하지 않는다 — 다음 IfMatch 를
// 만들 수 없다. 그 오류는 센티널이 아니다: 쓰기는 적용됐으나 새 판의 ETag 를 모르므로 호출자는 결과
// 모름과 같이 Head 로 확인한다.
func (s *S3Store) Put(ctx context.Context, key string, cond Precondition, body []byte, meta map[string]string) (string, error) {
	if err := cond.check(); err != nil {
		return "", err
	}
	in := &s3.PutObjectInput{
		Bucket:   aws.String(s.bucket),
		Key:      aws.String(key),
		Body:     bytes.NewReader(body),
		Metadata: meta,
	}
	if cond.ifMatch {
		in.IfMatch = aws.String(cond.etag)
	} else {
		in.IfNoneMatch = aws.String("*")
	}
	out, err := s.client.PutObject(ctx, in, requestOptions)
	if err != nil {
		return "", storeError("PUT", key, err)
	}
	if aws.ToString(out.ETag) == "" {
		return "", fmt.Errorf("publish: PUT key=%q: 응답에 ETag 가 없다", key)
	}
	return *out.ETag, nil
}

// Head 는 Store.Head 다 — HEAD 요청이라 본문을 받지 않는다.
//
// 404 는 부재다. HEAD 응답에는 본문이 없어 키 부재와 버킷 부재를 가르지 못하므로 둘 다 부재로 읽는다(버킷
// 부재는 이어지는 PUT 이 NoSuchBucket 으로 드러낸다). 403 은 부재로 읽지 않고 오류로 돌려준다 —
// s3:ListBucket 권한이 없으면 없는 키에도 403 이 오지만(service/s3 v1.106.3 HeadObject 문서) 있는 키의 권한
// 오류와 가를 수 없다.
func (s *S3Store) Head(ctx context.Context, key string) (Stat, error) {
	out, err := s.client.HeadObject(ctx, &s3.HeadObjectInput{
		Bucket: aws.String(s.bucket),
		Key:    aws.String(key),
	}, requestOptions)
	if err != nil {
		if httpStatus(err) == http.StatusNotFound {
			return Stat{}, nil
		}
		return Stat{}, fmt.Errorf("publish: HEAD key=%q: %w", key, err)
	}
	if aws.ToString(out.ETag) == "" {
		return Stat{}, fmt.Errorf("publish: HEAD key=%q: 응답에 ETag 가 없다", key)
	}
	return Stat{Exists: true, ETag: *out.ETag, Meta: out.Metadata}, nil
}

// Get 은 Store.Get 이다. 본문은 rewind.MaxBodyBytes(발행 전 검사 S5 의 목록 본문 상한)까지만 읽는다 — 발행한
// 목록은 S5 를 통과했으므로 그보다 큰 본문은 우리가 쓴 판이 아니고, 상한이 없으면 저장소가 보내는 만큼
// 메모리를 잡는다. 넘으면 센티널이 아닌 오류다.
func (s *S3Store) Get(ctx context.Context, key string) ([]byte, error) {
	out, err := s.client.GetObject(ctx, &s3.GetObjectInput{
		Bucket: aws.String(s.bucket),
		Key:    aws.String(key),
	}, requestOptions)
	if err != nil {
		return nil, storeError("GET", key, err)
	}
	defer out.Body.Close()
	// 한 바이트 더 읽어야 상한 크기와 초과를 가른다.
	body, err := io.ReadAll(io.LimitReader(out.Body, rewind.MaxBodyBytes+1))
	if err != nil {
		return nil, fmt.Errorf("publish: GET key=%q: 본문 읽기: %w", key, err)
	}
	if len(body) > rewind.MaxBodyBytes {
		return nil, fmt.Errorf("publish: GET key=%q: 본문이 상한 %d바이트를 넘는다", key, rewind.MaxBodyBytes)
	}
	return body, nil
}

// requestOptions 는 S3Store 의 모든 요청에 붙이는 SDK 설정이다. client 를 어떻게 만들었든 두 가지를
// 요청마다 고정한다.
//
//   - 시도는 한 번이다(RetryMaxAttempts 는 총 시도 수라 1 이면 재시도 0회). 재시도의 소유자는 호출자
//     하나다 — 세대 규약의 시도 상한(설계 4.4.5 RC-19)과 PUT 예산(T_pub)이 실제 요청 수와 맞아야 한다.
//     SDK 가 조건부 PUT 을 몰래 다시 보내면, 첫 시도가 이미 적용된 경우 둘째가 412 로 돌아와 성공을
//     실패로 보고한다.
//   - 본문을 그대로 보낸다(요청 체크섬은 필수인 연산에서만). LoadDefaultConfig 의 기본값(WhenSupported)이면
//     SDK 가 PUT 본문을 aws-chunked 로 감싸 CRC32 를 꼬리에 붙이는데, S3 호환 엔드포인트(업로더의
//     S3Options.Endpoint 가 여는 길)가 그 형식을 받는다는 보장이 없다. 업로더(upload.NewS3Putter)와
//     같은 요청 모양이다.
func requestOptions(o *s3.Options) {
	o.RetryMaxAttempts = 1
	o.RequestChecksumCalculation = aws.RequestChecksumCalculationWhenRequired
}

// storeError 는 SDK 오류를 Store 의 오류 공간(Store 설명의 표)으로 옮긴다. 412 · 409 · 404 는 센티널을 앞에
// 두고 원 오류는 문장으로만 남긴다 — 호출자가 SDK 형에 기대지 않게 하는 경계다. 버킷이 없다는
// 404(NoSuchBucket)는 키 부재가 아니라 설정 결함이라 ErrNotFound 로 옮기지 않는다. 나머지는 원 오류를
// 감싸 돌려준다 — ctx 만료 같은 결과 모름을 errors.Is 로 볼 수 있다.
func storeError(op, key string, err error) error {
	switch httpStatus(err) {
	case http.StatusPreconditionFailed:
		return fmt.Errorf("%w: %s key=%q: %v", ErrPreconditionFailed, op, key, err)
	case http.StatusConflict:
		return fmt.Errorf("%w: %s key=%q: %v", ErrConflict, op, key, err)
	case http.StatusNotFound:
		if apiErrorCode(err) != "NoSuchBucket" {
			return fmt.Errorf("%w: %s key=%q: %v", ErrNotFound, op, key, err)
		}
	}
	return fmt.Errorf("publish: %s key=%q: %w", op, key, err)
}

// httpStatus 는 err 에 실린 HTTP 응답 상태 코드다. 응답을 받지 못한 오류(네트워크 · ctx)면 0 이다.
func httpStatus(err error) int {
	var respErr *awshttp.ResponseError
	if errors.As(err, &respErr) {
		return respErr.HTTPStatusCode()
	}
	return 0
}

// apiErrorCode 는 S3 오류 응답의 코드 문자열(예: NoSuchKey)이다. 없으면 "" 다.
func apiErrorCode(err error) string {
	var apiErr smithy.APIError
	if errors.As(err, &apiErr) {
		return apiErr.ErrorCode()
	}
	return ""
}
