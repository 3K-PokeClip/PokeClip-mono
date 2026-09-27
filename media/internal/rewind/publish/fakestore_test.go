package publish

import (
	"bytes"
	"context"
	"crypto/md5"
	"encoding/hex"
	"fmt"
	"maps"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/service/s3"
)

// fakeStore 는 Store 의 인메모리 가짜다 — S3 조건부 쓰기의 의미론(계획 4.4 [B-1])을 흉내 낸다.
//
// 흉내 내는 성질:
//
//   - 조건은 적용하는 순간 저장된 판으로 따진다. IfAbsent 인데 객체가 있거나 IfMatch 인데 ETag 가 다르면
//     ErrPreconditionFailed(412), IfMatch 인데 객체가 없으면 ErrNotFound(404)다. 실패한 쓰기는 판을 바꾸지
//     않는다.
//   - ETag 는 본문만의 함수다(bodyETag). 메타는 섞지 않는다 — 같은 본문 · 다른 메타 판은 ETag 가 같아
//     If-Match 로 가를 수 없다(계획 4.5 A1 「ETag 성질과 선재 판정」).
//   - 메타 키는 소문자로 저장한다([B-3] — SDK 도 Head 응답의 메타 키를 소문자로 돌려준다).
//   - 목록 연산에 닿으면 테스트를 실패로 적는다(계획 6.5 ⑵).
//
// 409(동시 요청 충돌)는 뮤텍스 하나 아래에서는 생기지 않아 onPut 주입으로만 낸다. S3 가 받은 요청을 얼마나
// 늦게 적용하는지(δ)는 가정하지 않는다 — 늦게 닿는 쓰기는 테스트가 onPut 에서 붙잡아 둔 호출을 원하는
// 순간에 applyPut 으로 적용해 만든다.
//
// 여러 고루틴에서 동시에 써도 된다(동시 writer 픽스처).
type fakeStore struct {
	t testing.TB

	mu      sync.Mutex
	objects map[string]fakeObject
	// onPut 이 nil 이 아니면 적용 직전에 부른다. nil 이 아닌 오류를 돌려주면 Put 은 그 오류로 끝나고 판은
	// 그대로다(409 · 네트워크 오류). 적용된 뒤 응답만 잃은 경우(결과 모름)는 훅이 applyPut 을 먼저 부르고
	// 오류를 돌려준다.
	onPut func(putCall) error
	puts  []putCall
	heads []string
	gets  []string
}

// fakeObject 는 저장된 판 하나다.
type fakeObject struct {
	body []byte
	etag string
	meta map[string]string
}

// putCall 은 Put 호출 하나다. deadline 은 호출 ctx 의 마감이고 마감이 없으면 영값이다 — 세대 규약의 P3
// 마감(계획 4.5 A1 결정 9)을 테스트가 읽는 자리다.
type putCall struct {
	key      string
	cond     Precondition
	body     []byte
	meta     map[string]string
	deadline time.Time
}

// newFakeStore 는 빈 가짜를 만든다. t 는 목록 연산이 닿았을 때 실패를 적을 곳이다.
func newFakeStore(t testing.TB) *fakeStore {
	return &fakeStore{t: t, objects: map[string]fakeObject{}}
}

// Put 은 호출을 기록한 뒤 조건 검사 → ctx → 주입 → 적용 순으로 간다. 끝난 ctx 로 부른 호출도 기록은 남는다
// — 그런 호출이 있었는지를 테스트가 볼 수 있게.
func (f *fakeStore) Put(ctx context.Context, key string, cond Precondition, body []byte, meta map[string]string) (string, error) {
	call := putCall{key: key, cond: cond, body: bytes.Clone(body), meta: maps.Clone(meta)}
	if d, ok := ctx.Deadline(); ok {
		call.deadline = d
	}
	f.mu.Lock()
	f.puts = append(f.puts, call)
	hook := f.onPut
	f.mu.Unlock()

	if err := cond.check(); err != nil {
		return "", err
	}
	if err := ctx.Err(); err != nil {
		return "", err
	}
	if hook != nil {
		if err := hook(call); err != nil {
			return "", err
		}
	}
	return f.applyPut(call)
}

// applyPut 은 저장소가 요청을 적용하는 순간을 흉내 낸다 — 조건은 지금 저장된 판으로 따진다.
func (f *fakeStore) applyPut(call putCall) (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	cur, exists := f.objects[call.key]
	switch {
	case call.cond.ifMatch && !exists:
		return "", fmt.Errorf("%w: PUT key=%q", ErrNotFound, call.key)
	case call.cond.ifMatch && cur.etag != call.cond.etag:
		return "", fmt.Errorf("%w: PUT key=%q If-Match %s ≠ 저장된 %s", ErrPreconditionFailed, call.key, call.cond.etag, cur.etag)
	case !call.cond.ifMatch && exists:
		return "", fmt.Errorf("%w: PUT key=%q 이미 있다", ErrPreconditionFailed, call.key)
	}
	obj := fakeObject{body: bytes.Clone(call.body), etag: bodyETag(call.body), meta: lowerKeys(call.meta)}
	f.objects[call.key] = obj
	return obj.etag, nil
}

// Head 는 Store.Head 다. 본문 읽기 기록(gets)에 남지 않는다.
func (f *fakeStore) Head(ctx context.Context, key string) (Stat, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.heads = append(f.heads, key)
	if err := ctx.Err(); err != nil {
		return Stat{}, err
	}
	obj, ok := f.objects[key]
	if !ok {
		return Stat{}, nil
	}
	return Stat{Exists: true, ETag: obj.etag, Meta: maps.Clone(obj.meta)}, nil
}

// Get 은 Store.Get 이다.
func (f *fakeStore) Get(ctx context.Context, key string) ([]byte, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.gets = append(f.gets, key)
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	obj, ok := f.objects[key]
	if !ok {
		return nil, fmt.Errorf("%w: GET key=%q", ErrNotFound, key)
	}
	return bytes.Clone(obj.body), nil
}

// ListObjectsV2 를 비롯한 아래 List… 메서드 열둘은 S3 LIST 금지(프로필 4절)의 동적 그물이다 — 계획
// 6.5 ⑵. *s3.Client 의 목록 연산 전부다(TestFakeStoreFailsTestOnListCalls 가 SDK 실물과 대조한다).
// Store 에는 목록 연산이 없어 여기 닿는 길은 SDK 목록 인터페이스로의 형 단언뿐이다. 시그니처가 *s3.Client
// 와 같아서 그 단언이 이 가짜에서도 성립한다. t.Fatal 이 아니라 t.Errorf 로 적는다 — 발행 워커 고루틴에서
// 불릴 수 있다(Google Go 가이드 「Don't call t.Fatal from separate goroutines」).
func (f *fakeStore) ListObjectsV2(context.Context, *s3.ListObjectsV2Input, ...func(*s3.Options)) (*s3.ListObjectsV2Output, error) {
	return nil, f.listCalled("ListObjectsV2")
}

func (f *fakeStore) ListObjects(context.Context, *s3.ListObjectsInput, ...func(*s3.Options)) (*s3.ListObjectsOutput, error) {
	return nil, f.listCalled("ListObjects")
}

func (f *fakeStore) ListBuckets(context.Context, *s3.ListBucketsInput, ...func(*s3.Options)) (*s3.ListBucketsOutput, error) {
	return nil, f.listCalled("ListBuckets")
}

func (f *fakeStore) ListObjectVersions(context.Context, *s3.ListObjectVersionsInput, ...func(*s3.Options)) (*s3.ListObjectVersionsOutput, error) {
	return nil, f.listCalled("ListObjectVersions")
}

func (f *fakeStore) ListObjectAnnotations(context.Context, *s3.ListObjectAnnotationsInput, ...func(*s3.Options)) (*s3.ListObjectAnnotationsOutput, error) {
	return nil, f.listCalled("ListObjectAnnotations")
}

func (f *fakeStore) ListBucketAnalyticsConfigurations(context.Context, *s3.ListBucketAnalyticsConfigurationsInput, ...func(*s3.Options)) (*s3.ListBucketAnalyticsConfigurationsOutput, error) {
	return nil, f.listCalled("ListBucketAnalyticsConfigurations")
}

func (f *fakeStore) ListBucketIntelligentTieringConfigurations(context.Context, *s3.ListBucketIntelligentTieringConfigurationsInput, ...func(*s3.Options)) (*s3.ListBucketIntelligentTieringConfigurationsOutput, error) {
	return nil, f.listCalled("ListBucketIntelligentTieringConfigurations")
}

func (f *fakeStore) ListBucketInventoryConfigurations(context.Context, *s3.ListBucketInventoryConfigurationsInput, ...func(*s3.Options)) (*s3.ListBucketInventoryConfigurationsOutput, error) {
	return nil, f.listCalled("ListBucketInventoryConfigurations")
}

func (f *fakeStore) ListBucketMetricsConfigurations(context.Context, *s3.ListBucketMetricsConfigurationsInput, ...func(*s3.Options)) (*s3.ListBucketMetricsConfigurationsOutput, error) {
	return nil, f.listCalled("ListBucketMetricsConfigurations")
}

func (f *fakeStore) ListDirectoryBuckets(context.Context, *s3.ListDirectoryBucketsInput, ...func(*s3.Options)) (*s3.ListDirectoryBucketsOutput, error) {
	return nil, f.listCalled("ListDirectoryBuckets")
}

func (f *fakeStore) ListMultipartUploads(context.Context, *s3.ListMultipartUploadsInput, ...func(*s3.Options)) (*s3.ListMultipartUploadsOutput, error) {
	return nil, f.listCalled("ListMultipartUploads")
}

func (f *fakeStore) ListParts(context.Context, *s3.ListPartsInput, ...func(*s3.Options)) (*s3.ListPartsOutput, error) {
	return nil, f.listCalled("ListParts")
}

// listCalled 는 목록 연산 op 가 불렸다고 테스트에 적고 호출자에게 돌려줄 오류를 만든다.
func (f *fakeStore) listCalled(op string) error {
	f.t.Errorf("S3 LIST 금지 위반: %s 호출 — 객체가 있는지는 인덱스 upload_state 로만 판단한다(프로필 4절)", op)
	return fmt.Errorf("fakeStore: %s 는 금지된 목록 연산이다", op)
}

// putCalls 는 Put 호출 기록이다 — 실패한 호출도 들어 있다.
func (f *fakeStore) putCalls() []putCall {
	f.mu.Lock()
	defer f.mu.Unlock()
	return slices.Clone(f.puts)
}

// headKeys 는 Head 호출 기록(키)이다.
func (f *fakeStore) headKeys() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return slices.Clone(f.heads)
}

// getKeys 는 Get 호출 기록(키)이다 — 화해가 본문을 읽지 않는지(설계 4.4.2 Head/Get 분리) 재는 자리다.
func (f *fakeStore) getKeys() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return slices.Clone(f.gets)
}

// bodyETag 는 본문만으로 정해지는 ETag 다 — S3 단일 PUT(비암호화 · SSE-S3)의 모양(따옴표로 감싼 MD5
// 16진)을 따른다. 호출자는 ETag 를 불투명한 문자열로만 다룬다(계획 4.5 A1 — MD5 인지에 기대지 않는다).
func bodyETag(body []byte) string {
	sum := md5.Sum(body)
	return `"` + hex.EncodeToString(sum[:]) + `"`
}

// lowerKeys 는 meta 를 키만 소문자로 바꿔 복사한다. nil 이면 nil 이다.
func lowerKeys(meta map[string]string) map[string]string {
	if meta == nil {
		return nil
	}
	out := make(map[string]string, len(meta))
	for k, v := range meta {
		out[strings.ToLower(k)] = v
	}
	return out
}
