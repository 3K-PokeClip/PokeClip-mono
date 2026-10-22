package pgconf

// AWS 에 기대는 코드는 이 파일뿐이다 — 로그인 방식을 바꾸거나 걷어 낼 때 여기만 본다.

import (
	"context"
	"fmt"

	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/feature/rds/auth"
)

// newTokenFunc 는 AWS 기본 자격 증명 사슬(env · 공유 설정 · 인스턴스 역할 IMDS 순)로 RDS IAM 토큰을 만드는
// TokenFunc 를 돌려준다. 설정은 한 번만 적재하고, 자격 증명은 SDK 의 캐시(만료 전 갱신)를 그대로 쓴다.
// 토큰은 부를 때마다 새로 서명한다.
//
// 적재 실패의 원인 원문은 싣지 않는다 — 프로필 이름 · 파일 내용이 섞일 수 있다. 타입 이름만 싣는다.
func newTokenFunc(ctx context.Context, region string) (TokenFunc, error) {
	cfg, err := config.LoadDefaultConfig(ctx, config.WithRegion(region))
	if err != nil {
		return nil, fmt.Errorf("AWS 설정 적재 실패(AWS_REGION=%s, 원인 종류 %T) — AWS_PROFILE · AWS_CONFIG_FILE 등 AWS 설정을 확인하라", region, err)
	}
	creds := cfg.Credentials
	return func(ctx context.Context, endpoint, region, user string) (string, error) {
		return auth.BuildAuthToken(ctx, endpoint, region, user, creds)
	}, nil
}
