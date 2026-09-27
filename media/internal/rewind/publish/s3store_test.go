package publish

// S3Store 의 요청 모양과 응답 분기 — 로컬 TLS 서버(httptest)로 S3 를 흉내 내고, 실제 SDK 클라이언트가 그
// 서버에 보낸 요청을 잰다(Google Go 가이드 「Use real transports」). 외부 S3 로 나가는 요청은 없다.

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"maps"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/s3"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
)

const testBucket = "pokeclip-test"

var _ Store = (*S3Store)(nil)

// s3Request 는 흉내 서버가 받은 요청 하나다.
type s3Request struct {
	method string
	path   string
	header http.Header
	body   []byte
}

// s3Stub 은 흉내 서버가 받은 요청의 기록이다.
type s3Stub struct {
	mu       sync.Mutex
	requests []s3Request
}

func (s *s3Stub) count() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.requests)
}

// only 는 받은 요청이 정확히 하나일 때 그것을 돌려준다.
func (s *s3Stub) only(t *testing.T) s3Request {
	t.Helper()
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.requests) != 1 {
		t.Fatalf("흉내 서버가 받은 요청 %d건, want 1", len(s.requests))
	}
	return s.requests[0]
}

// newStubbedStore 는 respond 로 답하는 로컬 S3 흉내 서버와 그 서버에 붙은 S3Store 를 만든다.
//
// 클라이언트는 조립점이 LoadDefaultConfig 로 만들 클라이언트의 기본값을 그대로 가진다 — 재시도 3회 ·
// 요청 체크섬 WhenSupported. 시도 수와 본문 모양을 S3Store 가 요청마다 고정하는지 보려면 그 기본값이 살아
// 있어야 한다. 자격증명은 서명에만 쓰는 가짜 값이다.
func newStubbedStore(t *testing.T, respond http.HandlerFunc) (*S3Store, *s3Stub) {
	t.Helper()
	stub := &s3Stub{}
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Errorf("요청 본문 읽기 실패: %v", err)
		}
		stub.mu.Lock()
		stub.requests = append(stub.requests, s3Request{method: r.Method, path: r.URL.Path, header: r.Header.Clone(), body: body})
		stub.mu.Unlock()
		respond(w, r)
	}))
	t.Cleanup(srv.Close)

	client := s3.New(s3.Options{
		Region:       "ap-northeast-2",
		BaseEndpoint: aws.String(srv.URL),
		UsePathStyle: true,
		HTTPClient:   srv.Client(),
		Credentials: aws.CredentialsProviderFunc(func(context.Context) (aws.Credentials, error) {
			return aws.Credentials{AccessKeyID: "test", SecretAccessKey: "test", Source: "publish-test"}, nil
		}),
		RequestChecksumCalculation: aws.RequestChecksumCalculationWhenSupported,
	})
	return NewS3Store(client, testBucket), stub
}

// respondObject 는 200 으로 답한다. etag 가 비면 ETag 헤더를 싣지 않는다. meta 는 x-amz-meta-* 헤더로,
// body 는 응답 본문으로 싣는다(HEAD 면 본문 없음).
func respondObject(etag string, meta map[string]string, body []byte) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if etag != "" {
			w.Header().Set("ETag", etag)
		}
		for k, v := range meta {
			w.Header().Set("X-Amz-Meta-"+k, v)
		}
		if r.Method == http.MethodHead {
			return
		}
		// 쓰기가 실패하면 클라이언트가 받은 본문이 달라져 본문 단언이 잡는다.
		_, _ = w.Write(body)
	}
}

// respondError 는 S3 오류 응답(XML 본문)으로 답한다. HEAD 에는 본문이 없다 — S3 도 그렇다.
func respondError(status int, code string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/xml")
		w.WriteHeader(status)
		if r.Method == http.MethodHead {
			return
		}
		fmt.Fprintf(w, `<?xml version="1.0" encoding="UTF-8"?>`+"\n"+
			`<Error><Code>%s</Code><Message>stub</Message><RequestId>req-1</RequestId><HostId>host-1</HostId></Error>`, code)
	}
}

// metaHeaders 는 요청의 x-amz-meta-* 헤더다(이름은 Go 가 정규화한 모양).
func metaHeaders(h http.Header) map[string]string {
	out := map[string]string{}
	for k, v := range h {
		if strings.HasPrefix(k, "X-Amz-Meta-") {
			out[k] = strings.Join(v, ",")
		}
	}
	return out
}

// sentinels 는 Store 가 응답 분기로 돌려주는 오류다(Store 설명의 표).
var sentinels = []error{ErrPreconditionFailed, ErrNotFound, ErrConflict}

// 조건부 PUT 의 요청 모양 — 조건 헤더는 정확히 하나이고(첫 판 If-None-Match: * · 갱신 If-Match: <ETag>),
// 메타는 x-amz-meta-* 로 같은 요청에 실리며(설계 4.4.2 원자성), 본문은 그대로 간다(aws-chunked 로 감싸지
// 않는다). 응답의 ETag 를 그대로 돌려준다.
func TestS3StorePutSendsConditionAndMeta(t *testing.T) {
	cases := []struct {
		name            string
		cond            Precondition
		wantIfMatch     []string
		wantIfNoneMatch []string
	}{
		{name: "IfAbsent", cond: IfAbsent(), wantIfNoneMatch: []string{"*"}},
		{name: "IfMatch", cond: IfMatch(`"prev-etag"`), wantIfMatch: []string{`"prev-etag"`}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			store, stub := newStubbedStore(t, respondObject(`"new-etag"`, nil, nil))
			meta := map[string]string{"pc-gen": "7", "pc-session": "sess-1"}

			etag, err := store.Put(context.Background(), testKey, tc.cond, bodyA, meta)
			if err != nil {
				t.Fatalf("Put(%+v) 실패: %v", tc.cond, err)
			}
			if etag != `"new-etag"` {
				t.Errorf("Put 의 ETag = %q, want %q(응답 그대로)", etag, `"new-etag"`)
			}

			req := stub.only(t)
			if req.method != http.MethodPut || req.path != "/"+testBucket+"/"+testKey {
				t.Errorf("요청 = %s %s, want PUT /%s/%s", req.method, req.path, testBucket, testKey)
			}
			if got := req.header.Values("If-Match"); !slices.Equal(got, tc.wantIfMatch) {
				t.Errorf("If-Match = %q, want %q", got, tc.wantIfMatch)
			}
			if got := req.header.Values("If-None-Match"); !slices.Equal(got, tc.wantIfNoneMatch) {
				t.Errorf("If-None-Match = %q, want %q", got, tc.wantIfNoneMatch)
			}
			wantMeta := map[string]string{"X-Amz-Meta-Pc-Gen": "7", "X-Amz-Meta-Pc-Session": "sess-1"}
			if got := metaHeaders(req.header); !maps.Equal(got, wantMeta) {
				t.Errorf("메타 헤더 = %v, want %v", got, wantMeta)
			}
			if !bytes.Equal(req.body, bodyA) {
				t.Errorf("요청 본문 = %q, want %q(그대로)", req.body, bodyA)
			}
		})
	}
}

// 4.4 [B-1] 응답 분기의 매핑 — 412 · 409 · 404(키 부재)는 센티널로, 나머지는 센티널이 아닌 오류로 온다.
// 버킷이 없다는 404(NoSuchBucket)는 키 부재가 아니라 설정 결함이다. 어느 분기든 시도는 한 번이다 — 기본
// 재시도가 3회인 클라이언트로도 503 에 요청이 하나만 나간다(재시도는 호출자 몫 — RC-19 시도 상한).
func TestS3StorePutMapsResponseStatus(t *testing.T) {
	cases := []struct {
		name   string
		status int
		code   string
		// want 는 errors.Is 가 참이어야 할 센티널이다. nil 이면 어느 센티널도 아니어야 한다.
		want error
	}{
		{name: "412_PreconditionFailed", status: http.StatusPreconditionFailed, code: "PreconditionFailed", want: ErrPreconditionFailed},
		{name: "409_ConditionalRequestConflict", status: http.StatusConflict, code: "ConditionalRequestConflict", want: ErrConflict},
		{name: "404_NoSuchKey", status: http.StatusNotFound, code: "NoSuchKey", want: ErrNotFound},
		{name: "404_NoSuchBucket", status: http.StatusNotFound, code: "NoSuchBucket"},
		{name: "403_AccessDenied", status: http.StatusForbidden, code: "AccessDenied"},
		{name: "503_SlowDown", status: http.StatusServiceUnavailable, code: "SlowDown"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			store, stub := newStubbedStore(t, respondError(tc.status, tc.code))

			_, err := store.Put(context.Background(), testKey, IfMatch(`"prev-etag"`), bodyA, nil)
			if err == nil {
				t.Fatalf("%d %s 응답에 Put 오류가 없다", tc.status, tc.code)
			}
			for _, s := range sentinels {
				if got, want := errors.Is(err, s), s == tc.want; got != want {
					t.Errorf("errors.Is(%v, %v) = %t, want %t", err, s, got, want)
				}
			}
			if n := stub.count(); n != 1 {
				t.Errorf("요청 %d건, want 1 — 시도는 한 번이다", n)
			}
		})
	}
}

// 빈 ETag 의 IfMatch 는 보내지 않는다 — SDK 는 빈 If-Match 헤더도 싣고, 저장소가 그것을 조건 없는 쓰기로
// 읽으면 남의 판을 덮는다.
func TestS3StorePutRejectsEmptyIfMatch(t *testing.T) {
	store, stub := newStubbedStore(t, respondObject(`"new-etag"`, nil, nil))

	if _, err := store.Put(context.Background(), testKey, IfMatch(""), bodyA, nil); !errors.Is(err, errEmptyETag) {
		t.Errorf("Put(IfMatch(\"\")) 오류 = %v, want errEmptyETag", err)
	}
	if n := stub.count(); n != 0 {
		t.Errorf("빈 If-Match 로 요청이 %d건 나갔다, want 0", n)
	}
}

// 200 인데 ETag 가 없으면 성공으로 보고하지 않는다 — 다음 IfMatch 를 만들 수 없다. 쓰기는 적용됐으므로
// 「적용되지 않았다」는 센티널도 아니다(새 ETag 를 모른다 — 호출자는 결과 모름과 같이 Head 로 확인한다).
func TestS3StorePutWithoutETagIsUnknownResult(t *testing.T) {
	store, stub := newStubbedStore(t, respondObject("", nil, nil))

	_, err := store.Put(context.Background(), testKey, IfAbsent(), bodyA, nil)
	if err == nil {
		t.Fatal("ETag 없는 200 을 성공으로 보고했다")
	}
	for _, s := range sentinels {
		if errors.Is(err, s) {
			t.Errorf("ETag 없는 200 의 오류가 %v 다 — 적용된 쓰기를 적용되지 않았다고 보고한다", s)
		}
	}
	if req := stub.only(t); req.method != http.MethodPut {
		t.Errorf("요청 = %s, want PUT(보낸 뒤 응답을 판정한다)", req.method)
	}
}

// ctx 마감이 지나면 응답을 기다리지 않고 돌아오고, 그 오류는 결과 모름이다 — 센티널이 아니고
// errors.Is(err, context.DeadlineExceeded) 가 참이다. 세대 규약의 P3 마감(계획 4.5 A1 결정 9)이 이
// 경로로 끝난다.
func TestS3StorePutHonorsContextDeadline(t *testing.T) {
	release := make(chan struct{})
	store, _ := newStubbedStore(t, func(http.ResponseWriter, *http.Request) { <-release })
	t.Cleanup(func() { close(release) })

	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	done := make(chan error, 1)
	go func() {
		_, err := store.Put(ctx, testKey, IfAbsent(), bodyA, nil)
		done <- err
	}()

	select {
	case err := <-done:
		if !errors.Is(err, context.DeadlineExceeded) {
			t.Errorf("마감 지난 Put 의 오류 = %v, want context.DeadlineExceeded 계열", err)
		}
		for _, s := range sentinels {
			if errors.Is(err, s) {
				t.Errorf("마감 지난 Put 의 오류가 %v 다 — 결과 모름을 「적용되지 않았다」로 보고한다", s)
			}
		}
	case <-time.After(3 * time.Second):
		t.Fatal("ctx 마감이 지났는데 Put 이 돌아오지 않는다")
	}
}

// Head 는 HEAD 요청 하나로 ETag 와 메타를 읽는다 — 본문을 받지 않는다(설계 4.4.2 Head/Get 분리). 404 는 부재
// (Exists 거짓)이고 오류가 아니다. 403 은 부재로 읽지 않는다: s3:ListBucket 권한이 없으면 없는 키에도 403
// 이 오지만(service/s3 v1.106.3 HeadObject 문서) 있는 키의 권한 오류와 가를 수 없다.
func TestS3StoreHead(t *testing.T) {
	cases := []struct {
		name    string
		respond http.HandlerFunc
		want    Stat
		wantErr bool
	}{
		{
			name:    "200_etag_and_meta",
			respond: respondObject(`"e1"`, map[string]string{"Pc-Gen": "7", "PC-Pub-Seq": "12"}, nil),
			want:    Stat{Exists: true, ETag: `"e1"`, Meta: map[string]string{"pc-gen": "7", "pc-pub-seq": "12"}},
		},
		{name: "404_absent", respond: respondError(http.StatusNotFound, "NotFound"), want: Stat{}},
		{name: "403_not_absence", respond: respondError(http.StatusForbidden, "AccessDenied"), wantErr: true},
		{name: "503_error", respond: respondError(http.StatusServiceUnavailable, "SlowDown"), wantErr: true},
		{name: "200_without_etag", respond: respondObject("", nil, nil), wantErr: true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			store, stub := newStubbedStore(t, tc.respond)

			st, err := store.Head(context.Background(), testKey)
			if gotErr := err != nil; gotErr != tc.wantErr {
				t.Fatalf("Head 오류 = %v, want 오류 있음 = %t", err, tc.wantErr)
			}
			if !tc.wantErr && !sameStat(st, tc.want) {
				t.Errorf("Head = %+v, want %+v", st, tc.want)
			}
			req := stub.only(t)
			if req.method != http.MethodHead || req.path != "/"+testBucket+"/"+testKey {
				t.Errorf("요청 = %s %s, want HEAD /%s/%s", req.method, req.path, testBucket, testKey)
			}
		})
	}
}

// Get 은 GET 요청 하나로 본문을 읽는다. 키 부재(404 NoSuchKey)는 ErrNotFound, 버킷 부재는 설정 결함이라
// ErrNotFound 가 아니다. 본문은 rewind.MaxBodyBytes(발행 전 검사 S5 의 상한)까지 받는다 — 상한 크기는 읽고,
// 한 바이트라도 넘으면 센티널이 아닌 오류다.
func TestS3StoreGet(t *testing.T) {
	atLimit := bytes.Repeat([]byte{'x'}, rewind.MaxBodyBytes)
	overLimit := bytes.Repeat([]byte{'x'}, rewind.MaxBodyBytes+1)
	cases := []struct {
		name         string
		respond      http.HandlerFunc
		want         []byte
		wantNotFound bool
		wantErr      bool
	}{
		{name: "200_body", respond: respondObject(`"e1"`, nil, bodyA), want: bodyA},
		{name: "200_body_at_limit", respond: respondObject(`"e1"`, nil, atLimit), want: atLimit},
		{name: "200_body_over_limit", respond: respondObject(`"e1"`, nil, overLimit), wantErr: true},
		{name: "404_NoSuchKey", respond: respondError(http.StatusNotFound, "NoSuchKey"), wantNotFound: true, wantErr: true},
		{name: "404_NoSuchBucket", respond: respondError(http.StatusNotFound, "NoSuchBucket"), wantErr: true},
		{name: "503_error", respond: respondError(http.StatusServiceUnavailable, "SlowDown"), wantErr: true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			store, stub := newStubbedStore(t, tc.respond)

			body, err := store.Get(context.Background(), testKey)
			if gotErr := err != nil; gotErr != tc.wantErr {
				t.Fatalf("Get 오류 = %v, want 오류 있음 = %t", err, tc.wantErr)
			}
			if got := errors.Is(err, ErrNotFound); got != tc.wantNotFound {
				t.Errorf("errors.Is(%v, ErrNotFound) = %t, want %t", err, got, tc.wantNotFound)
			}
			if !bytes.Equal(body, tc.want) {
				// 상한 크기 본문을 통째로 찍지 않게 앞부분만 싣는다.
				t.Errorf("Get 본문 = %.64q(%d바이트), want %.64q(%d바이트)", body, len(body), tc.want, len(tc.want))
			}
			req := stub.only(t)
			if req.method != http.MethodGet || req.path != "/"+testBucket+"/"+testKey {
				t.Errorf("요청 = %s %s, want GET /%s/%s", req.method, req.path, testBucket, testKey)
			}
		})
	}
}

// Get 은 상한을 넘는 순간 읽기를 멈춘다 — 본문 끝을 기다리지 않는다. 흉내 서버는 상한+1 바이트를 보낸 뒤
// 응답을 붙잡아 둔다: 끝까지 읽고 나서 크기를 재는 구현은 여기서 묶이고, 끝없이 흘리는 저장소에는 메모리를
// 잡힌다.
func TestS3StoreGetStopsReadingPastLimit(t *testing.T) {
	release := make(chan struct{})
	store, _ := newStubbedStore(t, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("ETag", `"e1"`)
		_, _ = w.Write(bytes.Repeat([]byte{'x'}, rewind.MaxBodyBytes+1))
		w.(http.Flusher).Flush()
		<-release
	})
	t.Cleanup(func() { close(release) })

	done := make(chan error, 1)
	go func() {
		_, err := store.Get(context.Background(), testKey)
		done <- err
	}()

	select {
	case err := <-done:
		if err == nil {
			t.Error("상한을 넘는 본문의 Get 이 오류가 아니다")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("상한을 넘겼는데 Get 이 본문 끝을 기다린다 — 상한에서 읽기를 멈추지 않는다")
	}
}
