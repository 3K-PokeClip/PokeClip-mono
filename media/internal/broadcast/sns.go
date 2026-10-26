package broadcast

// SNS FIFO 어댑터(설계 M6 15.7) — Publisher 의 발행 칸이다. 클라이언트 생성(자격 · 지역)은 기동 조립이 한다.

import (
	"context"
	"errors"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sns"
	"github.com/aws/smithy-go"
)

// NewSNSPublish 는 topicARN 주제로 편지를 내는 발행 칸이다. 주제는 내용 기반 중복 제거를 끈 FIFO 라
// MessageDeduplicationId(= eventId)가 중복 제거의 열쇠다(broadcast_events.tf).
func NewSNSPublish(client *sns.Client, topicARN string) func(ctx context.Context, m Message) error {
	return func(ctx context.Context, m Message) error {
		_, err := client.Publish(ctx, &sns.PublishInput{
			TopicArn:               aws.String(topicARN),
			Message:                aws.String(string(m.Body)),
			MessageGroupId:         aws.String(m.GroupID),
			MessageDeduplicationId: aws.String(m.DeduplicationID),
		})
		return err
	}
}

// snsErrorKind 는 발행 오류를 짧은 종류로 바꾼다 — SNS 오류 코드 · 시한 · 그 밖. last_error · WARN 에 싣는 값이다.
func snsErrorKind(err error) string {
	var api smithy.APIError
	switch {
	case errors.As(err, &api):
		return "sns: " + api.ErrorCode()
	case errors.Is(err, context.DeadlineExceeded):
		return "sns: timeout"
	}
	return "sns: unknown"
}
