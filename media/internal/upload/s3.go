package upload

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awshttp "github.com/aws/aws-sdk-go-v2/aws/transport/http"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/smithy-go"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/s3errbody"
)

// 객체 머리 값이다(M5 설계 4.5 표). 호출자가 매번 넘기지 않는다 — 축이 고르는 계약이지 인자가 아니다(Putter
// 계약 3번).
//
//   - segmentContentType 은 fMP4 조각(② 원본 · ③ 조각)의 표준 타입이다.
//   - initContentType 은 init(MAP — ftyp+moov)의 타입이다. init 은 조각이 아니라 MP4 파일이다.
//   - immutableCacheControl 은 ③ 조각 · init 의 캐시 지시다. 같은 키의 바이트가 바뀌지 않는다(재추출 결정성 —
//     설계 10절 R13 · reextract_test.go). 엣지는 자기 상한(1일)으로 자르고 브라우저는 1년을 지킨다.
const (
	segmentContentType    = "video/iso.segment"
	initContentType       = "video/mp4"
	immutableCacheControl = "public, max-age=31536000, immutable"
)

// objectHeaders 는 한 객체의 PUT 머리다. cacheControl 이 비면 머리를 싣지 않는다.
type objectHeaders struct {
	contentType, cacheControl string
}

// headersFor 는 축별 머리 표다. ② 원본은 CDN 에 나가지 않아 캐시 지시가 없다. 표에 없는 축(미판정 영값 포함)은
// 오류다 — ② 머리로 접어 올리면 축이 틀린 객체가 조용히 생긴다.
func headersFor(axis index.Axis) (objectHeaders, error) {
	switch axis {
	case index.AxisArchive:
		return objectHeaders{contentType: segmentContentType}, nil
	case index.AxisPlayback:
		return objectHeaders{contentType: segmentContentType, cacheControl: immutableCacheControl}, nil
	case index.AxisInit:
		return objectHeaders{contentType: initContentType, cacheControl: immutableCacheControl}, nil
	default:
		return objectHeaders{}, fmt.Errorf("객체 머리 표에 없는 축(%v)이다", axis)
	}
}

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
	}, s3errbody.Limit) // 오류 응답 본문은 상한까지만 읽는다 — WithHTTPClient 로 감싸면 CA 묶음 해석이 깨진다
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

// Put 은 계약 셋을 그대로 지킨다: ctx 준수 / 처음부터 정확히 size 바이트 / 축별 머리 고정.
//
// body 를 감싸지 않고 그대로 넘기되 ContentLength 를 명시한다 — SDK 가 길이를 모르면
// 되감기를 시도하거나 청크 인코딩으로 보내는데, 둘 다 계약 2번 위반이다.
func (p *s3Putter) Put(ctx context.Context, key string, body io.Reader, size int64, axis index.Axis) error {
	h, err := headersFor(axis)
	if err != nil {
		return fmt.Errorf("PUT 거부 key=%q: %w", key, err)
	}
	in := &s3.PutObjectInput{
		Bucket:        aws.String(p.bucket),
		Key:           aws.String(key),
		Body:          io.LimitReader(body, size),
		ContentLength: aws.Int64(size),
		ContentType:   aws.String(h.contentType),
	}
	if h.cacheControl != "" {
		in.CacheControl = aws.String(h.cacheControl)
	}
	_, err = p.client.PutObject(ctx, in)
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
// 목적도(재시도 vs 축 브레이커 차단) 시점도(매 시도 vs 행 종결) 다르다(CX-2 ⑤).
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
// 그 축만 열려 그 축의 정상 트래픽까지 막는다(R2).
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

// ErrObjectNotFound 는 그 키에 객체가 없다는 뜻이다(GET 404 · NoSuchKey). 일시 실패가 아니다 — ③ 사슬 복원은 이것으로 「원천
// 부재」 를 가르고(설계 r6 2.10 「지나가기」), 그 밖의 오류(접근 거부 · 시한 · 5xx)는 행 상태를 바꾸지 않고 다시 본다.
var ErrObjectNotFound = errors.New("upload: S3 객체가 없다")

// getLimit 는 GET 한 번에 받는 객체 크기의 상한이다 — ③ 재포장 입력 상한(playback maxInputBytes, 16MiB)과 같다. ② · ③ 객체는
// 그 입력이거나 그 산출이라 넘지 않는다. 넘으면 다 읽지 않고 오류다.
const getLimit = 16 << 20

// getTimeout 은 GET · HEAD 한 번의 상한이다(본문 읽기까지 — 설계 r6 2.10 「GetTimeout 10초」).
const getTimeout = 10 * time.Second

// Getter 는 ③ 사슬 복원이 쓰는 S3 읽기다(POK-196 PDT 계획 4절 c10b · 설계 r6 2.10). Putter 와 따로 둔 이유: PUT 의 계약
// 셋(되감기 금지 등)과 무관하고, 복원이 없는 배선(시험의 가짜 Putter)은 이것을 갖지 않는다 — 업로더는 Putter 가 이것도
// 구현할 때만 복원한다(restore.go). S3 LIST 는 쓰지 않는다(인덱스 불변식 — 키는 장부가 준다).
type Getter interface {
	// Get 은 객체 본문 전체다. 없으면 ErrObjectNotFound 다.
	Get(ctx context.Context, key string) ([]byte, error)
	// Head 는 객체 길이와 있음이다. 없으면 (0, false, nil)이다.
	Head(ctx context.Context, key string) (size int64, found bool, err error)
}

// Get 은 객체를 통째로 받는다 — 상한(getLimit)을 넘으면 오류다.
func (p *s3Putter) Get(ctx context.Context, key string) ([]byte, error) {
	out, err := p.client.GetObject(ctx, &s3.GetObjectInput{Bucket: aws.String(p.bucket), Key: aws.String(key)})
	if err != nil {
		if objectMissing(err) {
			return nil, fmt.Errorf("GET key=%q: %w", key, ErrObjectNotFound)
		}
		return nil, fmt.Errorf("GET 실패 key=%q: %w", key, err)
	}
	defer out.Body.Close()
	body, err := io.ReadAll(io.LimitReader(out.Body, getLimit+1))
	if err != nil {
		return nil, fmt.Errorf("GET 본문 읽기 실패 key=%q: %w", key, err)
	}
	if len(body) > getLimit {
		return nil, fmt.Errorf("GET 거부 key=%q: 객체가 %d바이트를 넘는다", key, getLimit)
	}
	return body, nil
}

// Head 는 객체 길이와 있음이다.
func (p *s3Putter) Head(ctx context.Context, key string) (int64, bool, error) {
	out, err := p.client.HeadObject(ctx, &s3.HeadObjectInput{Bucket: aws.String(p.bucket), Key: aws.String(key)})
	if err != nil {
		if objectMissing(err) {
			return 0, false, nil
		}
		return 0, false, fmt.Errorf("HEAD 실패 key=%q: %w", key, err)
	}
	return aws.ToInt64(out.ContentLength), true, nil
}

// objectMissing 은 키에 객체가 없다는 응답인가다 — 404 이되 버킷이 없다(NoSuchBucket)는 아니다. HEAD 는 본문이 없어 버킷
// 부재도 404 하나로 오지만, 버킷 이름이 틀렸으면 PUT 이 먼저 hard 실패로 브레이커를 연다(classifyPutError).
func objectMissing(err error) bool {
	var apiErr smithy.APIError
	if errors.As(err, &apiErr) && apiErr.ErrorCode() == "NoSuchBucket" {
		return false
	}
	var respErr *awshttp.ResponseError
	return errors.As(err, &respErr) && respErr.HTTPStatusCode() == 404
}
