package credrelay

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"path"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awshttp "github.com/aws/aws-sdk-go-v2/aws/transport/http"
	"github.com/aws/aws-sdk-go-v2/feature/ec2/imds"
)

// iamCredsPath 는 IMDS 의 역할 자격 경로다(역할 목록 · 역할 이름 뒤에 그 자격).
const iamCredsPath = "iam/security-credentials/"

// 원천 실패의 종류다 — cred_source_failed 의 err_type. 닫힌 집합이다.
const (
	kindRoleList  = "role_list"  // 역할 목록을 받지 못했다
	kindRoleCount = "role_count" // 역할 목록이 정확히 한 줄이 아니다
	kindRoleCreds = "role_creds" // 역할 자격을 받지 못했다
	kindDecode    = "decode"     // 역할 자격 JSON 을 풀지 못했다
	kindCode      = "code"       // Code 가 Success 가 아니다
	kindField     = "field"      // 자격 필드가 비었다
	kindExpiry    = "expiry"     // 만료가 없거나 지났다
	kindDeadline  = "deadline"   // 원천 호출 상한(sourceDeadline)을 넘겼다
	kindOther     = "other"      // 그 밖
)

// sourceProvider 는 원천 자격의 Source 이름이다.
const sourceProvider = "CredRelayIMDS"

// sourceError 는 원천 실패다. 문구에는 자격 값을 싣지 않는다(로그에는 종류만 싣는다).
type sourceError struct {
	kind string
	err  error
}

func (e *sourceError) Error() string {
	return fmt.Sprintf("credrelay: IMDS 역할 자격 원천 실패(%s): %v", e.kind, e.err)
}

func (e *sourceError) Unwrap() error { return e.err }

// sourceErrorKind 는 err 의 종류를 돌려준다. 상한 초과는 어느 단계에서 났든 deadline 이다.
func sourceErrorKind(err error) string {
	if errors.Is(err, context.DeadlineExceeded) {
		return kindDeadline
	}
	var se *sourceError
	if errors.As(err, &se) {
		return se.kind
	}
	return kindOther
}

// NewIMDSClient 는 원천이 쓰는 IMDS 클라이언트다. 토큰 PUT 이 실패하면 IMDSv1(토큰 없는 요청)로 내려가지
// 않는다(EnableFallback 끔 — 그다음 호출이 토큰을 다시 받는다). 프록시 환경 변수를 타지 않는다 — 타면 IMDS
// 토큰과 역할 자격이 평문 HTTP 로 프록시를 지난다. endpoint 가 비면 SDK 기본 주소이고, 시험은 이 주소만
// 바꾼다. 시간 제한은 SDK 기본값을 두고, 원천 호출 전체 상한은 부르는 쪽이 context 로 준다.
func NewIMDSClient(endpoint string) *imds.Client {
	return imds.New(imdsOptions(endpoint))
}

// imdsOptions 는 NewIMDSClient 의 선택지다. HTTPClient 는 SDK 의 BuildableClient 에서 Proxy 만 nil 로 바꾼
// 것이다 — BuildableClient 여야 imds 가 그 위에 기본 시간 제한(dial 250ms · 응답 머리 500ms)을 얹는다.
func imdsOptions(endpoint string) imds.Options {
	return imds.Options{
		Endpoint:       endpoint,
		EnableFallback: aws.FalseTernary,
		HTTPClient: awshttp.NewBuildableClient().WithTransportOptions(func(tr *http.Transport) {
			tr.Proxy = nil
		}),
	}
}

// imdsRoleSource 는 IMDS 의 인스턴스 역할 자격을 직접 읽는 원천이다.
type imdsRoleSource struct {
	client *imds.Client
	now    func() time.Time
}

// NewIMDSRoleSource 는 client 로 인스턴스 역할 자격을 읽는 원천을 만든다. ec2rolecreds 를 쓰지 않는다 — 그것은
// 만료를 1시간으로 잘라 실제 만료를 잃고(중계는 실제 만료까지 마지막 유효분을 준다), 역할 목록의 첫 줄을 조용히
// 고른다. 이 원천은 역할 목록이 정확히 한 줄이고 · Code 가 Success(대소문자 무시)이고 · 자격 세 필드가 비지
// 않고 · 만료가 now 보다 뒤인지 본다. 돌려주는 Expires 는 IMDS 가 준 실제 만료 그대로다.
func NewIMDSRoleSource(client *imds.Client, now func() time.Time) aws.CredentialsProvider {
	return &imdsRoleSource{client: client, now: now}
}

// roleCredsBody 는 IMDS 역할 자격 JSON 이다.
type roleCredsBody struct {
	Code            string    `json:"Code"`
	AccessKeyID     string    `json:"AccessKeyId"`
	SecretAccessKey string    `json:"SecretAccessKey"`
	Token           string    `json:"Token"`
	Expiration      time.Time `json:"Expiration"`
}

func (s *imdsRoleSource) Retrieve(ctx context.Context) (aws.Credentials, error) {
	role, err := s.roleName(ctx)
	if err != nil {
		return aws.Credentials{}, err
	}
	out, err := s.client.GetMetadata(ctx, &imds.GetMetadataInput{Path: path.Join(iamCredsPath, role)})
	if err != nil {
		return aws.Credentials{}, &sourceError{kind: kindRoleCreds, err: err}
	}
	defer out.Content.Close()
	var body roleCredsBody
	if err := json.NewDecoder(out.Content).Decode(&body); err != nil {
		return aws.Credentials{}, &sourceError{kind: kindDecode, err: errors.New("역할 자격 JSON 꼴이 아니다")}
	}
	if err := s.check(body); err != nil {
		return aws.Credentials{}, err
	}
	return aws.Credentials{
		AccessKeyID:     body.AccessKeyID,
		SecretAccessKey: body.SecretAccessKey,
		SessionToken:    body.Token,
		Source:          sourceProvider,
		CanExpire:       true,
		Expires:         body.Expiration,
	}, nil
}

// roleName 은 역할 목록을 읽어 정확히 한 줄인 역할 이름을 돌려준다.
func (s *imdsRoleSource) roleName(ctx context.Context) (string, error) {
	out, err := s.client.GetMetadata(ctx, &imds.GetMetadataInput{Path: iamCredsPath})
	if err != nil {
		return "", &sourceError{kind: kindRoleList, err: err}
	}
	defer out.Content.Close()
	var roles []string
	sc := bufio.NewScanner(io.LimitReader(out.Content, 4096))
	for sc.Scan() {
		roles = append(roles, sc.Text())
	}
	if err := sc.Err(); err != nil {
		return "", &sourceError{kind: kindRoleList, err: err}
	}
	if len(roles) != 1 || strings.TrimSpace(roles[0]) == "" {
		return "", &sourceError{kind: kindRoleCount, err: fmt.Errorf("역할 목록이 %d줄이다 — 정확히 한 줄이어야 한다", len(roles))}
	}
	return roles[0], nil
}

// check 는 역할 자격의 Code · 필드 · 만료를 본다. 문구에는 자격 값을 싣지 않는다.
func (s *imdsRoleSource) check(b roleCredsBody) error {
	switch {
	case !strings.EqualFold(b.Code, "Success"):
		return &sourceError{kind: kindCode, err: errors.New("역할 자격 Code 가 Success 가 아니다")}
	case b.AccessKeyID == "" || b.SecretAccessKey == "" || b.Token == "":
		return &sourceError{kind: kindField, err: errors.New("역할 자격 필드가 비었다")}
	case !b.Expiration.After(s.now()):
		return &sourceError{kind: kindExpiry, err: errors.New("역할 자격 만료가 없거나 지났다")}
	}
	return nil
}
