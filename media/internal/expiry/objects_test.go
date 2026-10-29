package expiry

// S3 객체 삭제 어댑터 — 로컬 TLS 서버(httptest)로 S3 DeleteObjects 를 흉내 내고 실제 SDK 클라이언트가 보낸 요청을
// 잰다(publish/s3store_test.go 와 같은 꼴). 외부 S3 로 나가는 요청은 없다.

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/s3"
)

func newObjectsStub(t *testing.T, respond func(w http.ResponseWriter, body string)) (Objects, *[]string) {
	t.Helper()
	var mu sync.Mutex
	var bodies []string
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		mu.Lock()
		bodies = append(bodies, r.Method+" "+r.URL.Path+"?"+r.URL.RawQuery+" "+string(b))
		mu.Unlock()
		respond(w, string(b))
	}))
	t.Cleanup(srv.Close)
	client := s3.New(s3.Options{
		Region:       "ap-northeast-2",
		BaseEndpoint: aws.String(srv.URL),
		UsePathStyle: true,
		HTTPClient:   srv.Client(),
		Credentials: aws.CredentialsProviderFunc(func(context.Context) (aws.Credentials, error) {
			return aws.Credentials{AccessKeyID: "test", SecretAccessKey: "test", Source: "expiry-test"}, nil
		}),
	})
	return NewS3Objects(client, "pokeclip-test"), &bodies
}

// 한 요청(Quiet)으로 키를 보내고, 응답의 키별 Error 만 실패로 돌려준다.
func TestS3ObjectsDeleteReportsPerKeyErrors(t *testing.T) {
	objs, bodies := newObjectsStub(t, func(w http.ResponseWriter, _ string) {
		_, _ = io.WriteString(w, `<?xml version="1.0" encoding="UTF-8"?><DeleteResult>`+
			`<Error><Key>b</Key><Code>AccessDenied</Code><Message>denied</Message></Error></DeleteResult>`)
	})
	failed, err := objs.Delete(context.Background(), []string{"a", "b"})
	if err != nil {
		t.Fatalf("Delete: %v", err)
	}
	if len(failed) != 1 || failed["b"] == nil || !strings.Contains(failed["b"].Error(), "AccessDenied") {
		t.Fatalf("실패 키 %v (b · AccessDenied 기대)", failed)
	}
	req := (*bodies)[0]
	if !strings.HasPrefix(req, "POST /pokeclip-test?delete") || !strings.Contains(req, "<Quiet>true</Quiet>") ||
		!strings.Contains(req, "<Key>a</Key>") || !strings.Contains(req, "<Key>b</Key>") {
		t.Fatalf("요청 %s", req)
	}
}

// 요청 자체가 실패(5xx)하면 오류다 — 어느 키도 지워졌다고 보지 않는다.
func TestS3ObjectsDeleteRequestFailure(t *testing.T) {
	objs, _ := newObjectsStub(t, func(w http.ResponseWriter, _ string) {
		w.WriteHeader(http.StatusForbidden)
		_, _ = io.WriteString(w, `<Error><Code>AccessDenied</Code></Error>`)
	})
	if _, err := objs.Delete(context.Background(), []string{"a"}); err == nil {
		t.Fatal("403 인데 오류가 없다")
	}
}

// 1000 키를 넘으면 보내지 않는다(S3 한 요청 상한).
func TestS3ObjectsDeleteRejectsOversizedBatch(t *testing.T) {
	objs, bodies := newObjectsStub(t, func(http.ResponseWriter, string) {})
	if _, err := objs.Delete(context.Background(), make([]string, objectBatch+1)); err == nil {
		t.Fatal("상한을 넘는 묶음이 통과했다")
	}
	if len(*bodies) != 0 {
		t.Fatal("요청을 보냈다")
	}
	if failed, err := objs.Delete(context.Background(), nil); err != nil || len(failed) != 0 || len(*bodies) != 0 {
		t.Fatalf("빈 묶음: %v %v", failed, err)
	}
}
