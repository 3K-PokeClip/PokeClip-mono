package credrelay

// 실제 SDK 소비자(config.LoadDefaultConfig · AWS_CONTAINER_CREDENTIALS_FULL_URI · 토큰 파일)와의 왕복 시험. 실시계를
// 쓰고 시각 허용 오차는 2초다(설계 7.4). t.Setenv 를 쓰므로 병렬로 돌리지 않는다.

import (
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/smithy-go"
)

const clockSlack = 2 * time.Second

// sdkExpiryWindow 는 소비자 SDK 캐시 창이다. SDK 캐시는 돌려주는 Expires 를 받은 Expiration 에서 이만큼 당긴다
// (aws credential_cache.go 의 AdjustExpiresBy) — 그래서 받은 값 = Expires + 이 창이다.
const sdkExpiryWindow = 5 * time.Minute

// sdkRelay 는 실제 서버로 띄운 중계(실시계)와 그 중계로 오는 요청 수다.
type sdkRelay struct {
	imds     *fakeIMDS
	requests *atomic.Int64
}

// startSDKRelay 는 원천 만료가 지금 + expiresIn 인 중계를 띄우고, 이 기계의 AWS 설정을 막은 뒤 소비자 env
// (FULL_URI · 토큰 파일)를 그 중계로 맞춘다. token 이 토큰 파일 내용이다.
func startSDKRelay(t *testing.T, expiresIn time.Duration, token string) *sdkRelay {
	t.Helper()
	fake := newFakeIMDS(t, time.Now().Add(expiresIn))
	h := NewHandler(fake.source(time.Now), loadTestTokens(t), time.Now, newTestLogger(&logBuf{}))
	var n atomic.Int64
	counted := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n.Add(1)
		h.ServeHTTP(w, r)
	})
	base := startServer(t, NewServer("", counted))

	dir := t.TempDir()
	tokenFile := writeToken(t, dir, "token", token)
	t.Setenv("AWS_CONFIG_FILE", filepath.Join(dir, "none"))
	t.Setenv("AWS_SHARED_CREDENTIALS_FILE", filepath.Join(dir, "none"))
	for _, k := range []string{"AWS_PROFILE", "AWS_DEFAULT_PROFILE", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "AWS_ROLE_ARN", "AWS_WEB_IDENTITY_TOKEN_FILE", "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI", "AWS_CONTAINER_AUTHORIZATION_TOKEN"} {
		t.Setenv(k, "")
		os.Unsetenv(k)
	}
	t.Setenv("AWS_REGION", "ap-northeast-2")
	t.Setenv("AWS_CONTAINER_CREDENTIALS_FULL_URI", base+CredentialsPath)
	t.Setenv("AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE", tokenFile)
	return &sdkRelay{imds: fake, requests: &n}
}

// 원천 만료가 멀면 SDK 는 응답 Expiration(지금 + 60분)까지 캐시해 두 번째 Retrieve 가 중계로 오지 않는다. 원천
// 만료가 3분 뒤면(계획 E8) 응답 Expiration 이 그 만료라 소비자 창(5분) 안이고, 두 번째 Retrieve 가 중계로 온다
// (P6 SDK 왕복 층).
func TestSDKConsumerRoundTrip(t *testing.T) {
	for _, c := range []struct {
		name         string
		expiresIn    time.Duration
		wantExpires  time.Duration
		wantRequests int64
	}{
		{name: "far_source", expiresIn: 6 * time.Hour, wantExpires: consumerRefreshCap, wantRequests: 1},
		{name: "near_source_3m", expiresIn: 3 * time.Minute, wantExpires: 3 * time.Minute, wantRequests: 2},
	} {
		t.Run(c.name, func(t *testing.T) {
			r := startSDKRelay(t, c.expiresIn, canaryIndexerToken)
			cfg, err := config.LoadDefaultConfig(t.Context())
			if err != nil {
				t.Fatalf("LoadDefaultConfig 에러 %v", err)
			}

			start := time.Now()
			got, err := cfg.Credentials.Retrieve(t.Context())
			if err != nil {
				t.Fatalf("첫 Retrieve 에러 %v", err)
			}
			if got.Source != "CredentialsEndpointProvider" || got.AccessKeyID != canaryAccessKey {
				t.Errorf("첫 Retrieve: Source %q · 접근 키 카나리아 일치 %v, want CredentialsEndpointProvider · 참", got.Source, got.AccessKeyID == canaryAccessKey)
			}
			if d := got.Expires.Add(sdkExpiryWindow).Sub(start.Add(c.wantExpires)); d < -clockSlack || d > clockSlack {
				t.Errorf("첫 Retrieve: 받은 Expiration 이 지금 + %v 에서 %v 어긋났다(허용 %v)", c.wantExpires, d, clockSlack)
			}
			if _, err := cfg.Credentials.Retrieve(t.Context()); err != nil {
				t.Fatalf("두 번째 Retrieve 에러 %v", err)
			}
			if n := r.requests.Load(); n != c.wantRequests {
				t.Errorf("중계로 온 요청 %d번, want %d", n, c.wantRequests)
			}
		})
	}
}

// 오류 응답을 SDK 가 {code,message} 로 읽는다(P29 — Content-Type 이 정확히 application/json 일 때만). 401 은 재시도
// 하지 않아 요청 1번이고, 503 은 SDK 기본 재시도라 요청 3번이다.
func TestSDKConsumerErrorCodes(t *testing.T) {
	for _, c := range []struct {
		name         string
		token        string
		breakSource  bool
		wantCode     string
		wantRequests int64
	}{
		{name: "wrong_token", token: "wrong-token", wantCode: "Unauthorized", wantRequests: 1},
		{name: "no_source", token: canaryIndexerToken, breakSource: true, wantCode: "CredentialsUnavailable", wantRequests: 3},
	} {
		t.Run(c.name, func(t *testing.T) {
			r := startSDKRelay(t, 6*time.Hour, c.token)
			if c.breakSource {
				r.imds.update(func(f *fakeIMDS) { f.credsStatus = http.StatusNotFound })
			}
			cfg, err := config.LoadDefaultConfig(t.Context())
			if err != nil {
				t.Fatalf("LoadDefaultConfig 에러 %v", err)
			}

			_, err = cfg.Credentials.Retrieve(t.Context())
			var apiErr smithy.APIError
			if !errors.As(err, &apiErr) || apiErr.ErrorCode() != c.wantCode {
				t.Errorf("Retrieve 오류 %v, want 오류 Code %q", err, c.wantCode)
			}
			if n := r.requests.Load(); n != c.wantRequests {
				t.Errorf("중계로 온 요청 %d번, want %d", n, c.wantRequests)
			}
		})
	}
}
