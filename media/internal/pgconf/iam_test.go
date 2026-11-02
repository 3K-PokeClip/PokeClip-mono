package pgconf

// 기본 TokenFunc(실제 SDK) 시험. 망에 나가지 않는다 — 자격 증명은 env 의 합성 값이거나 실패하는 credential_process 다.
// t.Setenv 를 쓰므로 병렬로 돌리지 않는다.

import (
	"context"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// isolateAWSEnv 는 이 기계의 AWS 설정 · 자격 증명이 시험에 끼어들지 않게 막는다. configBody 가 AWS_CONFIG_FILE 내용이다.
func isolateAWSEnv(t *testing.T, configBody string) {
	t.Helper()
	dir := t.TempDir()
	t.Setenv("AWS_CONFIG_FILE", writeTestFile(t, dir, "config", []byte(configBody)))
	t.Setenv("AWS_SHARED_CREDENTIALS_FILE", writeTestFile(t, dir, "credentials", nil))
	t.Setenv("AWS_EC2_METADATA_DISABLED", "true")
	for _, k := range []string{"AWS_PROFILE", "AWS_DEFAULT_PROFILE", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "AWS_ROLE_ARN", "AWS_WEB_IDENTITY_TOKEN_FILE", "AWS_CONTAINER_CREDENTIALS_FULL_URI", "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI", "AWS_REGION", "AWS_DEFAULT_REGION"} {
		t.Setenv(k, "")
		os.Unsetenv(k)
	}
}

// 토큰 모양(D8): URL 로 해석해 host · port · 서명 쿼리를 본다. 경로의 '/' 유무는 고정하지 않는다.
func TestDefaultTokenFuncBuildsSignedConnectURL(t *testing.T) {
	isolateAWSEnv(t, "")
	t.Setenv("AWS_ACCESS_KEY_ID", "AKIDEXAMPLE")
	t.Setenv("AWS_SECRET_ACCESS_KEY", canary)

	tf, err := newTokenFunc(context.Background(), "ap-northeast-2")
	if err != nil {
		t.Fatalf("newTokenFunc 에러 %v", err)
	}
	tok, err := tf(context.Background(), "db.example.internal:5432", "ap-northeast-2", "media_indexer")
	if err != nil {
		t.Fatalf("토큰 생성 에러 %v", err)
	}
	if strings.Contains(tok, canary) {
		t.Fatal("토큰에 비밀 키 원문 발견")
	}
	u, err := url.Parse("https://" + tok)
	if err != nil {
		t.Fatalf("토큰이 URL 꼴이 아니다: %v", err)
	}
	if u.Hostname() != "db.example.internal" || u.Port() != "5432" {
		t.Errorf("토큰 host · port = %q · %q, want db.example.internal · 5432", u.Hostname(), u.Port())
	}
	q := u.Query()
	for k, want := range map[string]string{"Action": "connect", "DBUser": "media_indexer", "X-Amz-Algorithm": "AWS4-HMAC-SHA256", "X-Amz-Expires": "900"} {
		if got := q.Get(k); got != want {
			t.Errorf("쿼리 %s = %q, want %q", k, got, want)
		}
	}
	if cred := q.Get("X-Amz-Credential"); !strings.HasPrefix(cred, "AKIDEXAMPLE/") || !strings.HasSuffix(cred, "/ap-northeast-2/rds-db/aws4_request") {
		t.Errorf("X-Amz-Credential = %q, want AKIDEXAMPLE/<날짜>/ap-northeast-2/rds-db/aws4_request", cred)
	}
	if !regexp.MustCompile(`^[0-9a-f]{64}$`).MatchString(q.Get("X-Amz-Signature")) {
		t.Error("X-Amz-Signature 가 64자리 16진수가 아니다")
	}
}

// D1: credential_process 의 출력이 JSON 이 아니면 SDK 는 그 출력 원문을 오류에 넣는다(processcreds
// provider.go:190 「parse failed of process output: %s」). 그 원문이 BeforeConnect 의 오류에서 사라져야 한다.
// 전제(원문이 SDK 오류에 실림)를 먼저 확인한다 — 전제가 깨지면 이 시험은 아무것도 재지 않는다.
func TestTokenErrorHidesCredentialProcessOutput(t *testing.T) {
	isolateAWSEnv(t, "[default]\ncredential_process = /bin/sh -c 'echo "+canary+"'\n")

	tf, err := newTokenFunc(context.Background(), "ap-northeast-2")
	if err != nil {
		t.Fatalf("newTokenFunc 에러 %v", err)
	}
	if _, raw := tf(context.Background(), "db.example.internal:5432", "ap-northeast-2", "media_indexer"); raw == nil || !strings.Contains(raw.Error(), canary) {
		t.Fatalf("전제 깨짐 — SDK 원인 오류에 도구 출력이 없다: %v", raw != nil)
	}

	caFile := writeTestFile(t, t.TempDir(), "ca.pem", newTestCA(t, "pgconf-test-ca").pem)
	s, err := FromEnv(envOf(with(iamEnv(), "POSTGRES_SSLROOTCERT", caFile)))
	if err != nil {
		t.Fatal(err)
	}
	pc, err := PoolConfig(context.Background(), s) // 기본 TokenFunc
	if err != nil {
		t.Fatalf("PoolConfig 에러 %v", err)
	}
	err = pc.BeforeConnect(context.Background(), pc.ConnConfig.Copy())
	if err == nil {
		t.Fatal("BeforeConnect 에러 nil, want 자격 증명 실패")
	}
	if strings.Contains(err.Error(), canary) {
		t.Error("BeforeConnect 오류에 카나리아 발견 — 자격 증명 도구 출력이 샜다")
	}
	for e := err; e != nil; {
		u, ok := e.(interface{ Unwrap() error })
		if !ok {
			break
		}
		if e = u.Unwrap(); e != nil {
			t.Errorf("오류가 원인 오류를 감싸고 있다(%T)", e)
		}
	}
}

// AWS 설정 적재가 실패해도 원인 원문은 싣지 않고 변수 이름을 안내한다.
func TestPoolConfigAWSConfigFailureHidesCause(t *testing.T) {
	missing := filepath.Join(t.TempDir(), canary)
	isolateAWSEnv(t, "")
	t.Setenv("AWS_PROFILE", "missing-"+canary)
	t.Setenv("AWS_CONFIG_FILE", missing)
	caFile := writeTestFile(t, t.TempDir(), "ca.pem", newTestCA(t, "pgconf-test-ca").pem)
	s, err := FromEnv(envOf(with(iamEnv(), "POSTGRES_SSLROOTCERT", caFile)))
	if err != nil {
		t.Fatal(err)
	}
	_, err = PoolConfig(context.Background(), s)
	if err == nil {
		t.Fatal("PoolConfig 에러 nil, want AWS 설정 적재 실패(없는 프로필)")
	}
	if strings.Contains(err.Error(), canary) {
		t.Error("오류에 카나리아 발견")
	}
	if !strings.Contains(err.Error(), "AWS") {
		t.Errorf("오류 %q 에 AWS 설정 안내가 없다", err)
	}
}
