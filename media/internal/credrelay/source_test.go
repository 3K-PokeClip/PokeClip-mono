package credrelay

import (
	"context"
	"errors"
	"net/http"
	"testing"
	"time"

	awshttp "github.com/aws/aws-sdk-go-v2/aws/transport/http"
)

// 원천은 IMDS 가 준 실제 만료를 그대로 돌려준다(ec2rolecreds 처럼 1시간으로 자르지 않는다 — 설계 2.5 ⓒ).
func TestIMDSRoleSourceKeepsRealExpiration(t *testing.T) {
	clock := newManualClock()
	want := clock.start.Add(6 * time.Hour)
	fake := newFakeIMDS(t, want)

	got, err := fake.source(clock.now).Retrieve(t.Context())
	if err != nil {
		t.Fatalf("Retrieve 에러 %v", err)
	}
	if !got.CanExpire || !got.Expires.Equal(want) {
		t.Errorf("Retrieve: CanExpire %v · Expires %v, want 참 · %v", got.CanExpire, got.Expires, want)
	}
	if got.AccessKeyID != canaryAccessKey || got.SecretAccessKey != canarySecretKey || got.SessionToken != canarySessionToken {
		t.Error("Retrieve: 자격 세 필드가 IMDS 가 준 값과 다르다")
	}
}

// 역할 목록이 정확히 한 줄이 아니거나 · Code 가 Success 가 아니거나 · 필드가 비었거나 · 만료가 0 이거나 지났으면
// 자격을 내지 않는다(P26). 두 줄 사례는 첫 역할의 자격을 정상으로 낸다 — 첫 줄을 고르는 구현이면 받아들인다.
// 토큰 PUT 이 막히면 IMDSv1(토큰 없는 요청)로 내려가지 않는다(token_404) — 이 사례의 가짜 서버는 토큰 없는
// 요청에도 자격을 내므로, 내려가는 클라이언트(EnableFallback 기본값)면 받아들인다.
func TestIMDSRoleSourceRejectsBadRole(t *testing.T) {
	for _, c := range []struct {
		name     string
		mutate   func(f *fakeIMDS, start time.Time)
		wantKind string
	}{
		{name: "two_roles", mutate: func(f *fakeIMDS, _ time.Time) { f.roles = testRoleName + "\nother-role" }, wantKind: kindRoleCount},
		{name: "empty_roles", mutate: func(f *fakeIMDS, _ time.Time) { f.roles = "" }, wantKind: kindRoleCount},
		{name: "blank_role", mutate: func(f *fakeIMDS, _ time.Time) { f.roles = "\n" }, wantKind: kindRoleCount},
		{name: "code_failure", mutate: func(f *fakeIMDS, _ time.Time) { f.creds.Code = "Failure" }, wantKind: kindCode},
		{name: "empty_access_key", mutate: func(f *fakeIMDS, _ time.Time) { f.creds.AccessKeyID = "" }, wantKind: kindField},
		{name: "empty_secret", mutate: func(f *fakeIMDS, _ time.Time) { f.creds.SecretAccessKey = "" }, wantKind: kindField},
		{name: "empty_token", mutate: func(f *fakeIMDS, _ time.Time) { f.creds.Token = "" }, wantKind: kindField},
		{name: "zero_expiration", mutate: func(f *fakeIMDS, _ time.Time) { f.creds.Expiration = time.Time{} }, wantKind: kindExpiry},
		{name: "past_expiration", mutate: func(f *fakeIMDS, s time.Time) { f.creds.Expiration = s.Add(-time.Second) }, wantKind: kindExpiry},
		{name: "creds_500", mutate: func(f *fakeIMDS, _ time.Time) { f.credsStatus = http.StatusInternalServerError }},
		{name: "token_404", mutate: func(f *fakeIMDS, _ time.Time) { f.tokenStatus = http.StatusNotFound }},
	} {
		t.Run(c.name, func(t *testing.T) {
			clock := newManualClock()
			fake := newFakeIMDS(t, clock.start.Add(6*time.Hour))
			fake.update(func(f *fakeIMDS) { c.mutate(f, clock.start) })
			ctx, cancel := context.WithTimeout(t.Context(), sourceDeadline)
			defer cancel()

			_, err := fake.source(clock.now).Retrieve(ctx)
			if err == nil {
				t.Fatalf("Retrieve(%s) 가 자격을 냈다", c.name)
			}
			if c.wantKind != "" && sourceErrorKind(err) != c.wantKind {
				t.Errorf("Retrieve(%s) 오류 종류 %q, want %q (%v)", c.name, sourceErrorKind(err), c.wantKind, err)
			}
		})
	}
}

// 원천 호출 상한을 넘긴 실패의 종류는 deadline 이다(로그 err_type).
func TestSourceErrorKindDeadline(t *testing.T) {
	err := &sourceError{kind: kindRoleList, err: context.DeadlineExceeded}
	if got := sourceErrorKind(err); got != kindDeadline {
		t.Errorf("sourceErrorKind(상한 초과) = %q, want %q", got, kindDeadline)
	}
	if got := sourceErrorKind(errors.New("x")); got != kindOther {
		t.Errorf("sourceErrorKind(모르는 오류) = %q, want %q", got, kindOther)
	}
}

// IMDS 클라이언트는 프록시 환경 변수(HTTP_PROXY 등)를 타지 않는다(c3 보안 M) — 타면 IMDSv2 토큰과 역할 자격이
// 평문 HTTP 로 프록시를 지난다. Go 는 루프백 주소에 프록시를 쓰지 않고 환경 변수도 프로세스에서 한 번만 읽어
// httptest 로는 드러나지 않으므로 전송 설정을 직접 본다(keyresolve · expiry 의 같은 시험과 같은 꼴). SDK 의
// BuildableClient 여야 imds 가 기본 시간 제한(dial 250ms · 응답 머리 500ms)을 그 위에 얹는다.
func TestIMDSClientUsesDirectTransport(t *testing.T) {
	bc, ok := imdsOptions("").HTTPClient.(*awshttp.BuildableClient)
	if !ok {
		t.Fatalf("IMDS 클라이언트 HTTPClient = %T, want *awshttp.BuildableClient — nil 이면 프록시 환경 변수를 타는 SDK 기본 전송이다", imdsOptions("").HTTPClient)
	}
	if bc.GetTransport().Proxy != nil {
		t.Error("IMDS 클라이언트 전송에 Proxy 함수가 있다, want nil(직접 연결)")
	}
}

// 프록시를 끈 뒤에도 imds 의 응답 머리 상한(500ms — 설계 2.5 「클라이언트 기본값은 둔다」)이 산다. 가짜 IMDS 가
// 머리를 1초씩 늦게 보내면 상한 안에서 끝나지 않아 원천 실패다 — 상한이 없는 클라이언트면 머리 셋을 다 받아
// (3초 · 부르는 쪽 상한 5초 안) 자격을 낸다.
func TestIMDSClientKeepsHeaderTimeout(t *testing.T) {
	clock := newManualClock()
	fake := newFakeIMDS(t, clock.start.Add(6*time.Hour))
	fake.update(func(f *fakeIMDS) { f.headerDelay = time.Second })
	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()

	if _, err := fake.source(clock.now).Retrieve(ctx); err == nil {
		t.Error("머리가 1초씩 늦은 IMDS 에서 자격을 받았다 — 응답 머리 상한(500ms)이 빠졌다")
	}
}
