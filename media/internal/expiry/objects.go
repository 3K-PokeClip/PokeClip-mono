package expiry

import (
	"context"
	"errors"
	"fmt"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/aws-sdk-go-v2/service/s3/types"
)

// Objects 는 만료가 쓰는 객체 삭제다(소비자 정의). 키는 장부 저장값 그대로 받는다 — LIST 는 하지 않는다(불변식).
type Objects interface {
	// Delete 는 keys(objectBatch 개 이하)를 지우고 지우지 못한 키와 원인을 돌려준다. err 는 요청 자체의 실패다 — 그때는
	// 어느 키도 지워졌다고 보지 않는다. 없는 키 삭제는 성공이다(S3 DeleteObjects 의 뜻 — 재시도가 무해하다).
	Delete(ctx context.Context, keys []string) (failed map[string]error, err error)
}

// S3Objects 는 Objects 의 S3 구현이다(DeleteObjects · Quiet — 응답에는 실패한 키만 온다).
type S3Objects struct {
	client *s3.Client
	bucket string
}

// NewS3Objects 는 bucket 에서 지우는 Objects 다.
func NewS3Objects(client *s3.Client, bucket string) *S3Objects {
	return &S3Objects{client: client, bucket: bucket}
}

// Delete 는 한 요청으로 지운다.
func (o *S3Objects) Delete(ctx context.Context, keys []string) (map[string]error, error) {
	if len(keys) > objectBatch {
		return nil, fmt.Errorf("expiry: 객체 삭제 묶음 %d키 — 상한 %d", len(keys), objectBatch)
	}
	if len(keys) == 0 {
		return map[string]error{}, nil
	}
	ids := make([]types.ObjectIdentifier, 0, len(keys))
	for _, k := range keys {
		ids = append(ids, types.ObjectIdentifier{Key: aws.String(k)})
	}
	out, err := o.client.DeleteObjects(ctx, &s3.DeleteObjectsInput{
		Bucket: aws.String(o.bucket),
		Delete: &types.Delete{Objects: ids, Quiet: aws.Bool(true)},
	})
	if err != nil {
		return nil, fmt.Errorf("S3 DeleteObjects 실패 bucket=%q keys=%d: %w", o.bucket, len(keys), err)
	}
	failed := make(map[string]error, len(out.Errors))
	for _, e := range out.Errors {
		failed[aws.ToString(e.Key)] = errors.New(aws.ToString(e.Code) + ": " + aws.ToString(e.Message))
	}
	return failed, nil
}
