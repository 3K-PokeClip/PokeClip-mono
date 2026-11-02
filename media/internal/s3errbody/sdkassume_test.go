package s3errbody_test

// 고정판 가정의 가드(D13) — 사이드카가 부르는 S3 연산은 「200 인데 오류」 처리 대상이 아니라서, 오류 본문은 언제나 2xx 가 아닌
// 응답으로 오고 Limit 이 그것을 덮는다. SDK 판을 올려 이 처리가 사이드카 연산에 붙으면 아래 시험이 FAIL 한다. 연산 스택은 전송 전에
// 멈춰 열거하므로 망 · 자격증명 해석이 필요 없다.

import (
	"context"
	"errors"
	"runtime/debug"
	"slices"
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/smithy-go/middleware"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/s3errbody"
)

// process200ErrorID 는 S3 「200 인데 오류」 처리 미들웨어의 ID 다. 그 미들웨어는 service/s3 의 internal 패키지에 있어 import 할 수
// 없다. 출처: service/s3@v1.106.3/internal/customizations/handle_200_error.go:28-29.
const process200ErrorID = "S3:ProcessResponseFor200Error"

// s3Module 은 판을 읽을 SDK 모듈 경로다.
const s3Module = "github.com/aws/aws-sdk-go-v2/service/s3"

// errStackListed 는 스택을 열거한 APIOption 이 요청을 전송 전에 멈추려고 돌려주는 오류다.
var errStackListed = errors.New("시험: 역직렬화 단계 열거 뒤 멈춤")

// s3Op 은 사이드카가 부르는 모양으로 연산 하나를 부른다.
type s3Op func(ctx context.Context, c *s3.Client, optFn func(*s3.Options)) error

var (
	opBucket = aws.String("bucket")
	opKey    = aws.String("key")
)

// sidecarOps 는 사이드카가 부르는 S3 연산 전부다(upload/s3.go Put · rewind/publish/s3store.go Put · Head · Get ·
// cmd/segment-indexer/rewind_assembly.go checkBucket). 새 연산을 부르게 되면 여기에 더한다.
var sidecarOps = map[string]s3Op{
	"PutObject": func(ctx context.Context, c *s3.Client, optFn func(*s3.Options)) error {
		_, err := c.PutObject(ctx, &s3.PutObjectInput{Bucket: opBucket, Key: opKey}, optFn)
		return err
	},
	"HeadObject": func(ctx context.Context, c *s3.Client, optFn func(*s3.Options)) error {
		_, err := c.HeadObject(ctx, &s3.HeadObjectInput{Bucket: opBucket, Key: opKey}, optFn)
		return err
	},
	"HeadBucket": func(ctx context.Context, c *s3.Client, optFn func(*s3.Options)) error {
		_, err := c.HeadBucket(ctx, &s3.HeadBucketInput{Bucket: opBucket}, optFn)
		return err
	},
	"GetObject": func(ctx context.Context, c *s3.Client, optFn func(*s3.Options)) error {
		_, err := c.GetObject(ctx, &s3.GetObjectInput{Bucket: opBucket, Key: opKey}, optFn)
		return err
	},
}

// deserializeIDs 는 op 의 완성된 연산 스택에서 역직렬화 단계의 미들웨어 ID 를 모은다. 클라이언트는 운영과 같은 경로(s3.New + Limit)로
// 만든다. 연산 미들웨어를 다 붙인 뒤 도는 APIOption 이 ID 를 모으고 errStackListed 를 돌려줘 서명 · 전송 전에 멈춘다.
func deserializeIDs(t *testing.T, op s3Op) []string {
	t.Helper()
	client := s3.New(s3.Options{
		Region: "ap-northeast-2",
		Credentials: aws.CredentialsProviderFunc(func(context.Context) (aws.Credentials, error) {
			return aws.Credentials{AccessKeyID: "test", SecretAccessKey: "test", Source: "s3errbody-test"}, nil
		}),
	}, s3errbody.Limit)
	var ids []string
	listIDs := func(o *s3.Options) {
		o.APIOptions = append(o.APIOptions, func(stack *middleware.Stack) error {
			ids = stack.Deserialize.List()
			return errStackListed
		})
	}
	if err := op(t.Context(), client, listIDs); !errors.Is(err, errStackListed) {
		t.Fatalf("연산 스택 열거 err = %v, want errStackListed — 열거가 돌지 않았다", err)
	}
	if len(ids) == 0 {
		t.Fatal("역직렬화 단계 ID 가 비었다 — 열거가 돌지 않았다")
	}
	return ids
}

// s3ModuleVersion 은 시험 바이너리에 든 SDK service/s3 모듈의 판이다. 읽지 못하면 「판 미상」이다.
func s3ModuleVersion() string {
	info, ok := debug.ReadBuildInfo()
	if !ok {
		return "판 미상"
	}
	for _, dep := range info.Deps {
		if dep.Path != s3Module {
			continue
		}
		if dep.Replace != nil {
			return dep.Version + " => " + dep.Replace.Version
		}
		return dep.Version
	}
	return "판 미상"
}

// D13 — 사이드카 연산 넷의 스택에 「200 인데 오류」 처리가 없다(고정판 service/s3 v1.106.3 가정). 있으면 상태 200 인 오류 응답이
// 비2xx 전용 감싸개를 지나 무상한 복사로 간다.
func TestSidecarOpsHaveNo200ErrorPath(t *testing.T) {
	for name, op := range sidecarOps {
		t.Run(name, func(t *testing.T) {
			if ids := deserializeIDs(t, op); slices.Contains(ids, process200ErrorID) {
				t.Errorf("SDK 판 갱신으로 200-오류 경로가 생겼다 — s3errbody 범위 재검토: 연산 %s · %s %s", name, s3Module, s3ModuleVersion())
			}
		})
	}
}

// D13 자기 시험 — 같은 열거가 고정판에서 「200 인데 오류」 처리를 다는 CopyObject 에서는 그 ID 를 찾는다. ID 문자열이 바뀌거나
// 열거가 망가져 가드가 헛도는 것을 막는다. 출처: service/s3@v1.106.3/api_op_CopyObject.go:1038.
func TestGuardSeesCopyObject200ErrorPath(t *testing.T) {
	copyObject := func(ctx context.Context, c *s3.Client, optFn func(*s3.Options)) error {
		_, err := c.CopyObject(ctx, &s3.CopyObjectInput{Bucket: opBucket, Key: opKey, CopySource: aws.String("bucket/src")}, optFn)
		return err
	}

	if ids := deserializeIDs(t, copyObject); !slices.Contains(ids, process200ErrorID) {
		t.Errorf("CopyObject 역직렬화 단계 ID %v 에 %q 가 없다 — 가드가 헛돈다(%s %s)", ids, process200ErrorID, s3Module, s3ModuleVersion())
	}
}
