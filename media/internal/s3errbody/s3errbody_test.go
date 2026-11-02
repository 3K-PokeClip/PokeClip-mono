package s3errbody_test

// Limit 의 계약 — 2xx 는 원 응답 그대로 · 2xx 가 아니면 본문을 MaxBytes 까지만 · 넘으면 ErrTooLarge 를 계속 낸다 · Close 는 원
// 본문까지 · 응답마다 따로 센다. 끝의 시험은 실제 SDK 클라이언트(s3.New)가 로컬 TLS 서버(httptest)에 보낸 요청으로 잰다(Google Go
// 가이드 「Use real transports」). 외부 S3 로 나가는 요청은 없다.

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws"
	awshttp "github.com/aws/aws-sdk-go-v2/aws/transport/http"
	"github.com/aws/aws-sdk-go-v2/service/s3"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/s3errbody"
)

// hugeSize 는 상한을 크게 넘는 본문 크기(32MiB)다. 끝없는 본문 대신 쓴다 — 상한이 작동하지 않는 판에서도 시험이 묶이지 않고
// 단언으로 FAIL 한다.
const hugeSize = 32 << 20

// countingBody 는 원 본문 자리의 ReadCloser 다 — 감싸개가 원 본문에서 읽은 바이트와 Close 횟수를 센다. 내용은 'x' 의 반복이고
// 할당하지 않는다. Read 는 한 고루틴만 부른다(응답 하나의 본문이다).
type countingBody struct {
	left   int64
	read   atomic.Int64
	closes atomic.Int32
}

func newCountingBody(size int64) *countingBody {
	return &countingBody{left: size}
}

func (b *countingBody) Read(p []byte) (int, error) {
	if b.left == 0 {
		return 0, io.EOF
	}
	if int64(len(p)) > b.left {
		p = p[:b.left]
	}
	for i := range p {
		p[i] = 'x'
	}
	b.left -= int64(len(p))
	b.read.Add(int64(len(p)))
	return len(p), nil
}

func (b *countingBody) Close() error {
	b.closes.Add(1)
	return nil
}

// doerFunc 는 함수 하나로 된 s3.HTTPClient 다.
type doerFunc func(*http.Request) (*http.Response, error)

func (f doerFunc) Do(r *http.Request) (*http.Response, error) { return f(r) }

// respondBody 는 status 와 body 로 답하는 s3.HTTPClient 다(망 없음).
func respondBody(status int, body io.ReadCloser) s3.HTTPClient {
	return doerFunc(func(r *http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: status, Header: http.Header{}, Body: body, Request: r}, nil
	})
}

// limited 는 next 를 Limit 에 통과시킨 HTTP 클라이언트다 — s3.New 가 옵션 함수에 넘기는 모양 그대로다.
func limited(next s3.HTTPClient) s3.HTTPClient {
	o := s3.Options{HTTPClient: next}
	s3errbody.Limit(&o)
	return o.HTTPClient
}

// doOrFatal 은 c 로 GET 하나를 보내고 응답을 돌려준다.
func doOrFatal(t *testing.T, c s3.HTTPClient) *http.Response {
	t.Helper()
	req, err := http.NewRequest(http.MethodGet, "https://s3.example.com/bucket/key", nil)
	if err != nil {
		t.Fatalf("http.NewRequest 실패: %v", err)
	}
	resp, err := c.Do(req)
	if err != nil {
		t.Fatalf("Do 실패: %v", err)
	}
	return resp
}

// D1 — 2xx(SDK 와 같은 200 <= code < 300)는 원 응답 본문 그대로다. 발행 GET 의 2xx 본문은 MaxBytes 보다 큰 512KiB 까지라 잘리면
// 깨진다. 2xx 가 아니면(3xx 포함) 감싼다.
func TestLimitWrapsOnlyNon2xx(t *testing.T) {
	for _, status := range []int{200, 204, 299, 300, 301, 404, 500} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			body := newCountingBody(s3errbody.MaxBytes + 1)
			var want *http.Response
			next := doerFunc(func(r *http.Request) (*http.Response, error) {
				want = &http.Response{StatusCode: status, Header: http.Header{}, Body: body, Request: r}
				return want, nil
			})
			resp := doOrFatal(t, limited(next))

			got, err := io.ReadAll(resp.Body)

			if status >= 200 && status < 300 {
				if resp != want {
					t.Errorf("상태 %d 응답이 다른 객체다, want next 가 준 응답 그대로", status)
				}
				if resp.Body != io.ReadCloser(body) {
					t.Errorf("상태 %d 응답 본문이 감싸졌다, want 원 본문 그대로", status)
				}
				if err != nil || len(got) != s3errbody.MaxBytes+1 {
					t.Errorf("상태 %d 본문 = %d바이트 · err %v, want %d바이트 · nil", status, len(got), err, s3errbody.MaxBytes+1)
				}
				return
			}
			if !errors.Is(err, s3errbody.ErrTooLarge) {
				t.Errorf("상태 %d 본문 읽기 err = %v, want ErrTooLarge", status, err)
			}
		})
	}
}

// next 가 오류 없이 nil 응답을 주면 그대로 (nil, nil) 이다 — 감싸개가 역참조하지 않는다. 빈 응답으로 바꾸는 일은 SDK 의 전송기가
// 맡는다(smithy-go v1.27.6 transport/http/client.go ClientHandler.Handle).
func TestLimitPassesNilResponse(t *testing.T) {
	req, err := http.NewRequest(http.MethodGet, "https://s3.example.com/bucket/key", nil)
	if err != nil {
		t.Fatalf("http.NewRequest 실패: %v", err)
	}
	c := limited(doerFunc(func(*http.Request) (*http.Response, error) { return nil, nil }))

	resp, err := c.Do(req)

	if resp != nil || err != nil {
		t.Errorf("Do = (%v, %v), want (nil, nil)", resp, err)
	}
}

// 2xx 가 아닌데 본문이 nil 인 응답은 감싸지 않고 같은 응답 그대로다 — 감싸면 첫 Read 가 nil 을 역참조한다.
func TestLimitLeavesNilBodyUnwrapped(t *testing.T) {
	want := &http.Response{StatusCode: http.StatusInternalServerError, Header: http.Header{}}
	c := limited(doerFunc(func(*http.Request) (*http.Response, error) { return want, nil }))

	resp := doOrFatal(t, c)

	if resp != want || resp.Body != nil {
		t.Errorf("Do = %p · 본문 %T, want 같은 응답 %p · 본문 nil", resp, resp.Body, want)
	}
}

// D2 — 정확히 MaxBytes 바이트는 그대로 읽히고, MaxBytes+1 째 바이트에서 ErrTooLarge 다. 넘는 본문도 상한까지는 전달한다.
func TestLimitBoundary(t *testing.T) {
	for _, c := range []struct {
		name    string
		size    int64
		wantLen int
		wantErr error
	}{
		{name: "상한", size: s3errbody.MaxBytes, wantLen: s3errbody.MaxBytes},
		{name: "상한+1", size: s3errbody.MaxBytes + 1, wantLen: s3errbody.MaxBytes, wantErr: s3errbody.ErrTooLarge},
	} {
		t.Run(c.name, func(t *testing.T) {
			resp := doOrFatal(t, limited(respondBody(http.StatusInternalServerError, newCountingBody(c.size))))

			got, err := io.ReadAll(resp.Body)

			if len(got) != c.wantLen || !errors.Is(err, c.wantErr) {
				t.Errorf("%d바이트 본문 = %d바이트 · err %v, want %d바이트 · %v", c.size, len(got), err, c.wantLen, c.wantErr)
			}
			if !bytes.Equal(got, bytes.Repeat([]byte("x"), len(got))) {
				t.Error("읽은 본문이 원 본문과 다르다")
			}
		})
	}
}

// D5 — 감싼 본문의 Close 는 원 본문을 닫는다(상한 아래 · 초과 둘 다). 닿지 않으면 연결이 샌다.
func TestLimitClosesOriginalBody(t *testing.T) {
	for _, c := range []struct {
		name string
		size int64
	}{
		{name: "상한_아래", size: 10},
		{name: "초과", size: hugeSize},
	} {
		t.Run(c.name, func(t *testing.T) {
			body := newCountingBody(c.size)
			resp := doOrFatal(t, limited(respondBody(http.StatusForbidden, body)))
			_, _ = io.ReadAll(resp.Body)

			if err := resp.Body.Close(); err != nil {
				t.Errorf("Close = %v, want nil", err)
			}
			if n := body.closes.Load(); n != 1 {
				t.Errorf("원 본문 Close %d번, want 1", n)
			}
		})
	}
}

// D12 — 넘은 뒤의 Read 는 모두 ErrTooLarge 이고 원 본문을 다시 읽지 않는다. SDK 는 역직렬화 오류 뒤 남은 본문을 io.Discard 로
// 끝까지 비우므로(smithy-go v1.27.6 closeResponseBody) 다시 읽으면 끝없는 본문을 ctx 시한까지 망에서 읽는다.
func TestLimitKeepsFailingAfterTooLarge(t *testing.T) {
	body := newCountingBody(hugeSize)
	resp := doOrFatal(t, limited(respondBody(http.StatusInternalServerError, body)))
	if _, err := io.ReadAll(resp.Body); !errors.Is(err, s3errbody.ErrTooLarge) {
		t.Fatalf("첫 읽기 err = %v, want ErrTooLarge", err)
	}

	buf := make([]byte, 32<<10)
	for i := range 3 {
		if n, err := resp.Body.Read(buf); n != 0 || !errors.Is(err, s3errbody.ErrTooLarge) {
			t.Errorf("넘은 뒤 Read %d = (%d, %v), want (0, ErrTooLarge)", i, n, err)
		}
	}
	if _, err := io.Copy(io.Discard, resp.Body); !errors.Is(err, s3errbody.ErrTooLarge) {
		t.Errorf("SDK 식 비우기 err = %v, want ErrTooLarge", err)
	}
	if n := body.read.Load(); n > s3errbody.MaxBytes+1 {
		t.Errorf("원 본문에서 %d바이트 읽음, want %d 이하", n, s3errbody.MaxBytes+1)
	}
}

// D9 — 같은 클라이언트로 두 고루틴이 동시에 받은 응답은 따로 센다. 둘 다 Do 를 마친 뒤 함께 정확히 MaxBytes 바이트를 끝까지 읽어도
// 어느 쪽도 ErrTooLarge 가 아니다(계수를 클라이언트에 두면 합이 상한을 넘거나 -race 가 잡는다).
func TestLimitCountsEachResponseSeparately(t *testing.T) {
	c := limited(doerFunc(func(r *http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: http.StatusNotFound, Header: http.Header{}, Body: newCountingBody(s3errbody.MaxBytes), Request: r}, nil
	}))
	var bothDone sync.WaitGroup
	bothDone.Add(2)
	errs := make(chan error, 2)
	for range 2 {
		go func() {
			req, err := http.NewRequest(http.MethodGet, "https://s3.example.com/bucket/key", nil)
			if err != nil {
				bothDone.Done()
				errs <- err
				return
			}
			resp, err := c.Do(req)
			bothDone.Done()
			bothDone.Wait()
			if err != nil {
				errs <- err
				return
			}
			got, err := io.ReadAll(resp.Body)
			if err == nil && len(got) != s3errbody.MaxBytes {
				err = fmt.Errorf("본문 %d바이트, want %d", len(got), s3errbody.MaxBytes)
			}
			errs <- err
		}()
	}
	for range 2 {
		if err := <-errs; err != nil {
			t.Errorf("동시 응답 읽기 err = %v, want nil", err)
		}
	}
}

// hugeChunk 는 거대 본문 흉내 서버가 되풀이해 쓰는 32KiB 조각이다 — 한 번만 할당한다.
var hugeChunk = bytes.Repeat([]byte("x"), 32<<10)

// respondHuge 는 status 와 hugeChunk 를 chunks 번 이은 본문으로 답한다(HEAD 면 본문 없음). 클라이언트가 먼저 닫으면 쓰기를 멈춘다.
func respondHuge(status, chunks int) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/xml")
		w.WriteHeader(status)
		if r.Method == http.MethodHead {
			return
		}
		for range chunks {
			if _, err := w.Write(hugeChunk); err != nil {
				return
			}
		}
	}
}

// countingClient 는 next 가 받은 응답 본문을 countingBody 처럼 세는 주입 클라이언트다 — 감싸개 아래(원 본문)에서 잰다.
type countingClient struct {
	next   s3.HTTPClient
	read   atomic.Int64
	closes atomic.Int32
}

func (c *countingClient) Do(r *http.Request) (*http.Response, error) {
	resp, err := c.next.Do(r)
	if err != nil {
		return resp, err
	}
	resp.Body = &countedBody{rc: resp.Body, c: c}
	return resp, nil
}

// countedBody 는 countingClient 가 센 응답 본문이다.
type countedBody struct {
	rc io.ReadCloser
	c  *countingClient
}

func (b *countedBody) Read(p []byte) (int, error) {
	n, err := b.rc.Read(p)
	b.c.read.Add(int64(n))
	return n, err
}

func (b *countedBody) Close() error {
	b.c.closes.Add(1)
	return b.rc.Close()
}

// D12 · D4 — 실제 s3.New 에 Limit 을 붙인 클라이언트는 32MiB 오류 본문에서 원 본문을 MaxBytes+1 바이트 넘게 읽지 않는다. SDK 의
// 읽기(역직렬화 복사 · 오류 뒤 비우기)는 모두 감싸개를 거치므로 그 몫도 계수 안에 든다. 클라이언트는 주입분이다 — 주입이어도
// 감싼다(분기 없음). 상태 코드는 awshttp.ResponseError 에 남고, 원 본문은 한 번 닫힌다.
func TestLimitThroughSDKReadsAtMostMaxBytesPlusOne(t *testing.T) {
	srv := httptest.NewTLSServer(respondHuge(http.StatusInternalServerError, 1024))
	t.Cleanup(srv.Close)
	counting := &countingClient{next: srv.Client()}
	client := s3.New(s3.Options{
		Region:           "ap-northeast-2",
		BaseEndpoint:     aws.String(srv.URL),
		UsePathStyle:     true,
		HTTPClient:       counting,
		RetryMaxAttempts: 1,
		Credentials: aws.CredentialsProviderFunc(func(context.Context) (aws.Credentials, error) {
			return aws.Credentials{AccessKeyID: "test", SecretAccessKey: "test", Source: "s3errbody-test"}, nil
		}),
	}, s3errbody.Limit)

	_, err := client.GetObject(t.Context(), &s3.GetObjectInput{Bucket: aws.String("bucket"), Key: aws.String("key")})

	if !errors.Is(err, s3errbody.ErrTooLarge) {
		t.Errorf("GetObject err = %v, want ErrTooLarge", err)
	}
	var respErr *awshttp.ResponseError
	if !errors.As(err, &respErr) || respErr.HTTPStatusCode() != http.StatusInternalServerError {
		t.Errorf("GetObject err = %v, want 상태 500 인 awshttp.ResponseError", err)
	}
	if n := counting.read.Load(); n > s3errbody.MaxBytes+1 {
		t.Errorf("원 본문에서 %d바이트 읽음, want %d 이하", n, s3errbody.MaxBytes+1)
	}
	if n := counting.closes.Load(); n != 1 {
		t.Errorf("원 본문 Close %d번, want 1", n)
	}
}
