package upload

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"

	"github.com/aws/aws-sdk-go-v2/aws"
	awshttp "github.com/aws/aws-sdk-go-v2/aws/transport/http"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/smithy-go"
)

// segmentContentType 은 세그먼트 객체의 Content-Type 이다.
//
// 호출자가 매번 넘기지 않는다 — 이것은 계약이지 인자가 아니다(Putter 계약 3번).
// fMP4 세그먼트의 표준 타입이며, CloudFront·플레이어 경로에서의 적합성은 POK-28 이월(M4)이다.
const segmentContentType = "video/iso.segment"

// S3Options 는 PUT 대상을 특정하는 설정이다. 자격증명은 여기 없다 —
// 코드가 자격증명을 직접 읽지 않는 것이 G14 이며, SDK 의 기본 체인이 env·프로필에서 찾는다.
type S3Options struct {
	Bucket, Region, Endpoint string
	ForcePathStyle           bool
}

// s3Putter 는 Putter 의 유일한 실구현이다.
type s3Putter struct {
	client *s3.Client
	bucket string
}

// NewS3Putter 의 두 실패는 뜻이 다르다:
//
//	err != nil    = 설정 자체가 잘못됐다. 기동 실패다.
//	credsOK=false = 설정은 유효하나 지금 자격증명을 얻을 수 없다. 기동은 진행하고
//	                호출자가 브레이커를 연 채로 시작한다(결정 12⁴).
//
// SDK 재시도는 끈다 — WithRetryMaxAttempts(1) 은 "총 시도 횟수"이므로 SDK 재시도 0회다.
// 재시도의 소유자는 워커 하나여야 AC3-a 의 upload_retry 계수가 실제 시도와 맞는다.
func NewS3Putter(ctx context.Context, opt S3Options, log *slog.Logger) (Putter, bool, error) {
	return newS3Putter(ctx, opt, log, nil)
}

// newS3Putter 는 HTTP 클라이언트를 갈아 끼울 수 있는 내부 생성자다.
//
// 테스트가 httptest 의 TLS 인증서를 신뢰하게 하는 유일한 이음매다 — 평문 HTTP 로는
// SigV4 가 본문 해시를 요구해 되감기 불가 스트림을 아예 거부하므로, 계약 2·3을
// 운영과 같은 조건(TLS)에서 확인하려면 이 주입이 필요하다. 공개 시그니처는 그대로다.
func newS3Putter(ctx context.Context, opt S3Options, log *slog.Logger, httpClient awsconfig.HTTPClient) (Putter, bool, error) {
	loadOpts := []func(*awsconfig.LoadOptions) error{
		awsconfig.WithRegion(opt.Region),
		awsconfig.WithRetryMaxAttempts(1),
	}
	if httpClient != nil {
		loadOpts = append(loadOpts, awsconfig.WithHTTPClient(httpClient))
	}
	if opt.Endpoint != "" {
		loadOpts = append(loadOpts, awsconfig.WithBaseEndpoint(opt.Endpoint))
	}
	cfg, err := awsconfig.LoadDefaultConfig(ctx, loadOpts...)
	if err != nil {
		return nil, false, fmt.Errorf("AWS 설정 적재 실패: %w", err)
	}

	client := s3.NewFromConfig(cfg, func(o *s3.Options) {
		o.UsePathStyle = opt.ForcePathStyle
		// 사전 체크섬 계산을 끈다. SDK 기본값(WhenSupported)은 본문 전체를 미리 읽어
		// CRC32 를 내는데, 그러려면 스트림이 되감기 가능해야 한다 — Putter 계약 2번이
		// 금지하는 바로 그 동작이고, 되감기 불가 스트림에서는 아예 실패한다.
		// 무결성은 Content-Length 와 응답 ETag 로 확인한다.
		o.RequestChecksumCalculation = aws.RequestChecksumCalculationWhenRequired
	})
	log.Info("uploader_started", "bucket", opt.Bucket, "region", opt.Region, "endpoint", opt.Endpoint)

	// 기동 시 한 번 확인한다. 실패해도 기동을 거부하지 않는다 — 자격증명은 나중에
	// 갱신될 수 있고, 그동안은 브레이커가 전역 차단을 맡는다.
	creds, err := cfg.Credentials.Retrieve(ctx)
	if err != nil {
		log.Error("credentials_unavailable", "err", err.Error())
		return &s3Putter{client: client, bucket: opt.Bucket}, false, nil
	}
	// provider 이름만 남긴다. 키·토큰·만료는 절대 로그에 싣지 않는다(G14).
	log.Info("credentials_ok", "provider", creds.Source)
	return &s3Putter{client: client, bucket: opt.Bucket}, true, nil
}

// Put 은 계약 셋을 그대로 지킨다: ctx 준수 / 처음부터 정확히 size 바이트 / Content-Type 고정.
//
// body 를 감싸지 않고 그대로 넘기되 ContentLength 를 명시한다 — SDK 가 길이를 모르면
// 되감기를 시도하거나 청크 인코딩으로 보내는데, 둘 다 계약 2번 위반이다.
func (p *s3Putter) Put(ctx context.Context, key string, body io.Reader, size int64) error {
	_, err := p.client.PutObject(ctx, &s3.PutObjectInput{
		Bucket:        aws.String(p.bucket),
		Key:           aws.String(key),
		Body:          io.LimitReader(body, size),
		ContentLength: aws.Int64(size),
		ContentType:   aws.String(segmentContentType),
	})
	if err != nil {
		return fmt.Errorf("PUT 실패 key=%q: %w", key, err)
	}
	return nil
}

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
// awshttp.ResponseError 의 상태 코드와 smithy.APIError 의 코드 문자열을 둘 다 본다 —
// 엔드포인트 구현(MinIO 등)에 따라 한쪽만 채워질 수 있다. **둘 다 없으면 soft 다.**
// soft 가 기본값인 것이 중요하다: 알 수 없는 오류를 hard 로 오분류하면 브레이커가
// 전역으로 열려 정상 트래픽까지 막는다(R2).
//
// 두 SDK 타입을 명시로 받은 뒤 같은 모양의 구조적 인터페이스도 한 번 더 본다.
// 후자는 SDK 를 임포트하지 않는 테스트 더블과 다른 엔드포인트 구현을 위한 것이다.
func classifyPutError(err error) putErrClass {
	if err == nil {
		return putErrSoft
	}

	var respErr *awshttp.ResponseError
	if errors.As(err, &respErr) {
		if _, ok := hardStatuses[respErr.HTTPStatusCode()]; ok {
			return putErrHard
		}
	}
	var apiErr smithy.APIError
	if errors.As(err, &apiErr) {
		if _, ok := hardCodes[apiErr.ErrorCode()]; ok {
			return putErrHard
		}
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
