package broadcast

// SNS 어댑터 시험(POK-196 M6-8 커밋 ③ · 설계 M6 15.7). 실 AWS 도 LocalStack 도 쓰지 않는다 — 시험 HTTP 서버를 끝점으로
// 주고 SDK 가 보내는 요청(쿼리 프로토콜 폼)과 오류 분류를 잰다.

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"sync"
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/sns"
)

const topicARN = "arn:aws:sns:ap-northeast-2:000000000000:pokeclip-test-broadcast-lifecycle.fifo"

// snsServer 는 SNS 쿼리 프로토콜 흉내다. status 가 200 이 아니면 body 를 오류 응답으로 낸다.
func snsServer(t *testing.T, status int, body string) (*sns.Client, func() url.Values) {
	t.Helper()
	var (
		mu   sync.Mutex
		form url.Values
	)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		mu.Lock()
		form, _ = url.ParseQuery(string(raw))
		mu.Unlock()
		w.Header().Set("Content-Type", "text/xml")
		w.WriteHeader(status)
		io.WriteString(w, body)
	}))
	t.Cleanup(srv.Close)
	client := sns.New(sns.Options{
		Region:           "ap-northeast-2",
		Credentials:      credentials.NewStaticCredentialsProvider("AKIDTEST", "secret-test", ""),
		BaseEndpoint:     aws.String(srv.URL),
		HTTPClient:       srv.Client(),
		RetryMaxAttempts: 1,
	})
	return client, func() url.Values {
		mu.Lock()
		defer mu.Unlock()
		return form
	}
}

const publishOK = `<PublishResponse xmlns="http://sns.amazonaws.com/doc/2010-03-31/"><PublishResult>` +
	`<MessageId>m-1</MessageId><SequenceNumber>1</SequenceNumber></PublishResult>` +
	`<ResponseMetadata><RequestId>r-1</RequestId></ResponseMetadata></PublishResponse>`

// 어댑터는 주제 ARN · 본문 · MessageGroupId · MessageDeduplicationId 를 그대로 싣는다(계약9 2절).
func TestSNSPublishSendsFIFOFields(t *testing.T) {
	client, got := snsServer(t, http.StatusOK, publishOK)
	publish := NewSNSPublish(client, topicARN)

	err := publish(context.Background(), Message{GroupID: "S-1", DeduplicationID: "S-1.started", Body: []byte(`{"a":1}`)})

	if err != nil {
		t.Fatalf("publish = %v, want nil", err)
	}
	form := got()
	for k, want := range map[string]string{
		"Action": "Publish", "TopicArn": topicARN, "Message": `{"a":1}`,
		"MessageGroupId": "S-1", "MessageDeduplicationId": "S-1.started",
	} {
		if form.Get(k) != want {
			t.Errorf("요청 %s = %q, want %q", k, form.Get(k), want)
		}
	}
}

// 오류는 SNS 오류 코드로 분류한다 — last_error · WARN 에는 이 짧은 종류만 싣는다.
func TestSNSErrorKindUsesAPIErrorCode(t *testing.T) {
	client, _ := snsServer(t, http.StatusBadRequest,
		`<ErrorResponse><Error><Type>Sender</Type><Code>InvalidParameter</Code><Message>bad</Message></Error>`+
			`<RequestId>r-1</RequestId></ErrorResponse>`)

	err := NewSNSPublish(client, topicARN)(context.Background(), Message{GroupID: "S-1", DeduplicationID: "S-1.started"})

	if got, want := snsErrorKind(err), "sns: InvalidParameter"; got != want {
		t.Errorf("snsErrorKind = %q, want %q (err %v)", got, want, err)
	}
	for _, tt := range []struct {
		err  error
		want string
	}{{context.DeadlineExceeded, "sns: timeout"}, {io.ErrUnexpectedEOF, "sns: unknown"}} {
		if got := snsErrorKind(tt.err); got != tt.want {
			t.Errorf("snsErrorKind(%v) = %q, want %q", tt.err, got, tt.want)
		}
	}
}
