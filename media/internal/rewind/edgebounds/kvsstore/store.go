// Package kvsstore 는 edgebounds.Store 의 운영 구현이다 — CloudFront KeyValueStore 데이터 평면(cloudfrontkeyvaluestore)
// 의 DescribeKeyValueStore · UpdateKeys(If-Match) · GetKey 를 부른다(POK-525 설계 3-3 · 계획 Ⓒ-5).
//
// SDK 를 쓰므로 계약 패키지 edgebounds 와 나눠 둔다(K9) — edgebounds 의 의존 목록에 SDK 가 들지 않는다. 클라이언트
// 생성(자격 · 지역)은 기동 조립이 한다. 요청은 SDK 가 SigV4A(전역 지역 집합 *)로 서명하고, 끝점은 ARN 의 계정 번호로
// 정해진다(<계정>.cloudfront-kvs.global.api.aws).
//
// 오류는 분류하지 않고 감싸 그대로 돌려준다 — 경합과 실패는 부른 쪽(Writer)이 Describe 판 대조로 가른다(Store 계약 ·
// E20). 선행 조건 실패는 실측상 ValidationException · HTTP 400 「Pre-Condition failed …」 다(83 C1 · C2).
package kvsstore

import (
	"context"
	"errors"
	"fmt"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/cloudfrontkeyvaluestore"
	"github.com/aws/aws-sdk-go-v2/service/cloudfrontkeyvaluestore/types"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/edgebounds"
)

// updateLimit 는 UpdateKeys 요청 하나의 puts + deletes 상한이다(Store 계약 「합쳐 50개 이하」).
const updateLimit = 50

// Store 는 KVS 하나에 묶인 edgebounds.Store 다.
type Store struct {
	client *cloudfrontkeyvaluestore.Client
	arn    string
}

var _ edgebounds.Store = (*Store)(nil)

// New 는 kvsARN 의 KVS 를 부르는 Store 다. ARN 꼴은 설정(REWIND_EDGE_BOUNDS_KVS_ARN)이 기동 때 본다.
func New(client *cloudfrontkeyvaluestore.Client, kvsARN string) *Store {
	return &Store{client: client, arn: kvsARN}
}

// Describe 는 KVS 의 지금 판(ETag)과 크기다. 판이 빈 응답은 오류다 — 빈 판으로는 판에 묶인 쓰기를 할 수 없다.
func (s *Store) Describe(ctx context.Context) (edgebounds.Stat, error) {
	out, err := s.client.DescribeKeyValueStore(ctx, &cloudfrontkeyvaluestore.DescribeKeyValueStoreInput{
		KvsARN: aws.String(s.arn),
	})
	if err != nil {
		return edgebounds.Stat{}, fmt.Errorf("kvsstore: DescribeKeyValueStore: %w", err)
	}
	etag := aws.ToString(out.ETag)
	if etag == "" {
		return edgebounds.Stat{}, errors.New("kvsstore: DescribeKeyValueStore 응답에 ETag 가 없다")
	}
	return edgebounds.Stat{
		ETag:  etag,
		Items: int64(aws.ToInt32(out.ItemCount)),
		Bytes: aws.ToInt64(out.TotalSizeInBytes),
	}, nil
}

// Update 는 ifMatch 판 위에서만 puts 를 쓰고 deletes 를 지운다(UpdateKeys — 전부 아니면 전무). 빈 판이거나 합쳐
// updateLimit 개를 넘으면 보내지 않고 오류다. 키 중복은 보지 않는다 — 부른 쪽이 집합으로 조립하고, 섞이면 KVS 가
// 요청 전체를 거절한다(실측 H3-5).
func (s *Store) Update(ctx context.Context, ifMatch string, puts []edgebounds.Entry, deletes []string) (string, error) {
	if ifMatch == "" {
		return "", errors.New("kvsstore: UpdateKeys 에 판(If-Match)이 없다")
	}
	if n := len(puts) + len(deletes); n > updateLimit {
		return "", fmt.Errorf("kvsstore: UpdateKeys 묶음 %d개가 상한 %d개를 넘는다", n, updateLimit)
	}
	in := &cloudfrontkeyvaluestore.UpdateKeysInput{KvsARN: aws.String(s.arn), IfMatch: aws.String(ifMatch)}
	for _, e := range puts {
		in.Puts = append(in.Puts, types.PutKeyRequestListItem{Key: aws.String(e.Key), Value: aws.String(e.Value)})
	}
	for _, k := range deletes {
		in.Deletes = append(in.Deletes, types.DeleteKeyRequestListItem{Key: aws.String(k)})
	}
	out, err := s.client.UpdateKeys(ctx, in)
	if err != nil {
		return "", fmt.Errorf("kvsstore: UpdateKeys: %w", err)
	}
	return aws.ToString(out.ETag), nil
}

// Fetch 는 key 의 값이다(GetKey). 키가 없으면(ResourceNotFoundException) found=false 다 — KVS 자체가 없을 때도 같은
// 오류라 둘을 가르지 않는다. 탐침 · 실측의 되읽기 전용이고 KVS 존재는 Describe 가 본다.
func (s *Store) Fetch(ctx context.Context, key string) (string, bool, error) {
	out, err := s.client.GetKey(ctx, &cloudfrontkeyvaluestore.GetKeyInput{KvsARN: aws.String(s.arn), Key: aws.String(key)})
	var missing *types.ResourceNotFoundException
	switch {
	case errors.As(err, &missing):
		return "", false, nil
	case err != nil:
		return "", false, fmt.Errorf("kvsstore: GetKey: %w", err)
	}
	return aws.ToString(out.Value), true, nil
}
