package upload

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	awshttp "github.com/aws/aws-sdk-go-v2/aws/transport/http"
	"github.com/aws/smithy-go"
	smithyhttp "github.com/aws/smithy-go/transport/http"
)

// 상태 코드만 주는 오류(awshttp.ResponseError 모양).
type statusErr struct{ code int }

func (e statusErr) Error() string       { return fmt.Sprintf("http %d", e.code) }
func (e statusErr) HTTPStatusCode() int { return e.code }

// 코드 문자열만 주는 오류(smithy.APIError 모양).
type codeErr struct{ code string }

func (e codeErr) Error() string     { return e.code }
func (e codeErr) ErrorCode() string { return e.code }

// ⑬ 전반부 — 분류의 기본값이 soft 인 것이 핵심이다.
// 알 수 없는 오류를 hard 로 오분류하면 브레이커가 그 축만 열려 그 축의 정상 트래픽까지 막는다(R2).
func TestClassifyPutError(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want putErrClass
	}{
		{"nil", nil, putErrSoft},
		{"미상", errors.New("connection reset by peer"), putErrSoft},
		{"타임아웃", fmt.Errorf("wrapped: %w", errors.New("i/o timeout")), putErrSoft},
		{"500", statusErr{500}, putErrSoft},
		{"401", statusErr{401}, putErrHard},
		{"403", statusErr{403}, putErrHard},
		{"404", statusErr{404}, putErrHard},
		{"감싼_403", fmt.Errorf("put 실패: %w", statusErr{403}), putErrHard},
		{"PermanentRedirect", codeErr{"PermanentRedirect"}, putErrHard},
		{"NoSuchBucket", codeErr{"NoSuchBucket"}, putErrHard},
		{"SlowDown", codeErr{"SlowDown"}, putErrSoft},
	}
	for _, c := range cases {
		if got := classifyPutError(c.err); got != c.want {
			t.Errorf("classifyPutError(%s) = %v, want %v", c.name, got, c.want)
		}
	}
}

// putterEnv 는 SDK 가 자격증명을 찾으러 네트워크(IMDS·SSO)로 나가지 않게 못 박는다.
// 세션 토큰이 없으면 프로필을 타 실제 조회를 시도하고 테스트가 느려지거나 흔들린다.
func putterEnv(t *testing.T) {
	t.Helper()
	t.Setenv("AWS_ACCESS_KEY_ID", "test")
	t.Setenv("AWS_SECRET_ACCESS_KEY", "test")
	t.Setenv("AWS_SESSION_TOKEN", "test")
	t.Setenv("AWS_EC2_METADATA_DISABLED", "true")
	t.Setenv("AWS_SHARED_CREDENTIALS_FILE", filepath.Join(t.TempDir(), "none"))
	t.Setenv("AWS_CONFIG_FILE", filepath.Join(t.TempDir(), "none"))
}

// Putter 계약 2·3 — 처음부터 정확히 size 바이트만 보내고 Content-Type 을 상수로 고정한다.
//
// 차단급 이유: 깨지면 0바이트나 잘못된 Content-Type 객체가 PUT 돼도 AC1(행 수)과
// AC2(키 대조)는 전부 통과하고 재생 객체만 손상된다.
func TestPutterContract(t *testing.T) {
	putterEnv(t)

	body := []byte("0123456789abcdefghij")
	type capture struct {
		contentLength int64
		contentType   string
		got           []byte
		path          string
	}
	var seen capture
	// TLS 서버를 쓴다 — 평문 HTTP 에서는 SigV4 가 본문 해시를 요구해 되감기 불가
	// 스트림을 거부한다. 운영은 TLS 이고 그때 S3 는 UNSIGNED-PAYLOAD 를 쓴다.
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		seen = capture{
			contentLength: r.ContentLength,
			contentType:   r.Header.Get("Content-Type"),
			got:           b,
			path:          r.URL.Path,
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	cap := newLogCapture()
	p, credsOK, err := newS3Putter(context.Background(), S3Options{
		Bucket: "pokeclip-test", Region: "ap-northeast-2",
		Endpoint: srv.URL, ForcePathStyle: true,
	}, cap.logger(), srv.Client())
	if err != nil {
		t.Fatalf("NewS3Putter 실패: %v", err)
	}
	if !credsOK {
		t.Fatalf("credsOK = false — env 3종을 넣었는데 자격증명을 얻지 못했다")
	}
	if n := cap.count("credentials_ok"); n != 1 {
		t.Errorf("credentials_ok = %d건, want 1", n)
	}
	// 자격증명이 로그에 새면 안 된다(G14).
	for _, r := range cap.find("credentials_ok") {
		for k, v := range r.attrs {
			if s, ok := v.(string); ok && (s == "test" || k == "secret" || k == "token") {
				t.Errorf("credentials_ok 에 자격증명이 실렸다: %s=%v", k, v)
			}
		}
	}

	key := "streams/demo/2026-08-03/00/seg_000007.m4s"
	if err := p.Put(context.Background(), key, bytes.NewReader(body), int64(len(body))); err != nil {
		t.Fatalf("Put 실패: %v", err)
	}

	if seen.contentLength != int64(len(body)) {
		t.Errorf("ContentLength = %d, want %d", seen.contentLength, len(body))
	}
	if !bytes.Equal(seen.got, body) {
		t.Errorf("본문 = %q(%d바이트), want %q(%d바이트)", seen.got, len(seen.got), body, len(body))
	}
	if seen.contentType != segmentContentType {
		t.Errorf("Content-Type = %q, want %q", seen.contentType, segmentContentType)
	}
	if !strings.Contains(seen.path, key) {
		t.Errorf("요청 경로 %q 에 키 %q 가 없다", seen.path, key)
	}
}

// Putter 계약 1 — ctx 취소를 즉시 준수한다.
// 이 계약이 깨지면 종료 6단계의 무조건 join 이 무기한 대기가 된다(G18″).
func TestPutterRespectsContextCancellation(t *testing.T) {
	putterEnv(t)

	release := make(chan struct{})
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-release // 응답을 영원히 미룬다
	}))
	defer func() { close(release); srv.Close() }()

	p, _, err := newS3Putter(context.Background(), S3Options{
		Bucket: "pokeclip-test", Region: "ap-northeast-2",
		Endpoint: srv.URL, ForcePathStyle: true,
	}, newLogCapture().logger(), srv.Client())
	if err != nil {
		t.Fatalf("NewS3Putter 실패: %v", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		done <- p.Put(ctx, "streams/demo/2026-08-03/00/seg_000007.m4s", bytes.NewReader([]byte("x")), 1)
	}()
	time.Sleep(100 * time.Millisecond)
	cancel()

	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Errorf("err = %v, want context.Canceled 계열", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Put 이 ctx 취소 후에도 반환하지 않았다")
	}
}

// ⑬ 후반부 — 실제 SDK 오류 타입에서도 같은 판정이 나온다.
func TestClassifyPutErrorWithSDKErrorTypes(t *testing.T) {
	// smithy.APIError 구현체
	api := &smithy.GenericAPIError{Code: "PermanentRedirect", Message: "리전이 다르다"}
	if got := classifyPutError(fmt.Errorf("put: %w", api)); got != putErrHard {
		t.Errorf("smithy PermanentRedirect = %v, want hard", got)
	}
	if got := classifyPutError(&smithy.GenericAPIError{Code: "SlowDown"}); got != putErrSoft {
		t.Errorf("smithy SlowDown = %v, want soft", got)
	}

	// awshttp.ResponseError — 상태 코드만 채워지는 엔드포인트 구현을 흉내 낸다.
	respErr := &awshttp.ResponseError{
		ResponseError: &smithyhttp.ResponseError{
			Response: &smithyhttp.Response{Response: &http.Response{StatusCode: 403}},
			Err:      errors.New("거부"),
		},
	}
	if got := classifyPutError(fmt.Errorf("put: %w", respErr)); got != putErrHard {
		t.Errorf("awshttp 403 = %v, want hard", got)
	}
}
