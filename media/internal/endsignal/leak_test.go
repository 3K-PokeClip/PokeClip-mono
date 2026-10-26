package endsignal

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"strings"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/keyresolve"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
)

// P6 — 판정 갈래 전부를 지난 뒤 DEBUG 까지 받은 로그에 Authorization 값 · 파생 값 · passphrase · 본문 카나리아가 0 번이다.
// 카나리아가 실제로 입력에 들어갔음(가짜 resolve 가 카나리아 passphrase 를 돌려줌 · 맞는 파생 값이 202)을 먼저 확인한다.
func TestNoSecretsInLogsAcrossBranchesP6(t *testing.T) {
	// canaryBearer 는 꼴만 맞는 틀린 파생 값이다. 맞는 파생 값은 deriveForTest 로 따로 계산한다.
	const (
		canaryPassphrase = "CANARY-passphrase-4d-leak-test-0001"
		canaryBearer     = "6eb2d1d46e3d8a6c22ad0f0ce1dba7b9b0aa1a5ba0d9ae49c8a3f8a3b2e2cdb0"
		canaryBody       = "CANARYBODYVALUE"
	)
	derived := deriveForTest(t, canaryPassphrase)
	branches := []struct {
		name    string
		body    string
		auth    string
		verdict func(t *testing.T) (keyresolve.Verdict, error)
		apply   error
	}{
		{name: "accepted", auth: "Bearer " + derived},
		{name: "accepted_대문자", auth: "Bearer " + strings.ToUpper(derived)},
		{name: "bad_request", body: `{"streamid":"` + canaryBody + `"}`, auth: "Bearer " + derived},
		{name: "bad_auth_form", auth: "Basic " + derived},
		{name: "mismatch", auth: "Bearer " + canaryBearer},
		{name: "key_rejected", auth: "Bearer " + derived, verdict: func(*testing.T) (keyresolve.Verdict, error) {
			return keyresolve.Verdict{Reason: "REVOKED"}, nil
		}},
		{name: "resolve_undecided", auth: "Bearer " + derived, verdict: func(*testing.T) (keyresolve.Verdict, error) {
			return keyresolve.Verdict{}, &keyresolve.UndecidedError{Kind: "status", Status: 500}
		}},
		{name: "apply_failed", auth: "Bearer " + derived, apply: errors.New("pg 오류")},
	}
	var logs strings.Builder
	accepted := 0
	for _, b := range branches {
		f := newFixture(t, nil)
		f.resolve = func(context.Context, string) (keyresolve.Verdict, error) {
			if b.verdict != nil {
				return b.verdict(t)
			}
			return validVerdict(t, canaryPassphrase), nil
		}
		f.apply = func(context.Context, session.IntentionalEnd) (session.IntentionalResult, error) {
			return session.IntentionalResult{Effect: "marked"}, b.apply
		}
		body := b.body
		if body == "" {
			body = `{"note":"` + canaryBody + `","streamid":"#!::r=` + testToken + `,m=publish","elapsedSinceStopMs":1,"connectionDurationMs":5000}`
		}
		if f.post(body, b.auth).status == 202 {
			accepted++
		}
		logs.WriteString(f.log.String())
	}
	if accepted != 2 {
		t.Fatalf("카나리아 파생 값 202 = %d, want 2 — 카나리아가 흐르지 않았다", accepted)
	}
	out := logs.String()
	for name, canary := range map[string]string{
		"passphrase": canaryPassphrase, "파생값": derived, "파생값_대문자": strings.ToUpper(derived),
		"틀린_Bearer": canaryBearer, "본문": canaryBody, "Authorization": "Bearer ",
	} {
		if n := strings.Count(out, canary); n != 0 {
			t.Errorf("로그에 %s 카나리아 %d 번", name, n)
		}
	}
}

// deriveForTest 는 시험 쪽 파생 값 계산이다 — 제품 함수와 따로 계산해 서로를 검증한다.
func deriveForTest(t *testing.T, passphrase string) string {
	t.Helper()
	return hmacHexForTest(passphrase, "pokeclip-4d-v1")
}

func hmacHexForTest(key, msg string) string {
	m := hmac.New(sha256.New, []byte(key))
	m.Write([]byte(msg))
	return hex.EncodeToString(m.Sum(nil))
}
