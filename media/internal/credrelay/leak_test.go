package credrelay

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// leakCanaries 는 로그 · 에러 · 오류 본문에 나오면 안 되는 합성 비밀이다 — 역할 자격 셋 · IMDS 토큰 · 호출자
// 토큰 둘. 거부 사례가 보낸 틀린 토큰도 카나리아로 넣는다(받은 Authorization 을 싣지 않는다).
const canaryWrongToken = "canaryWrongTok2r7j"

var leakCanaries = []string{canaryAccessKey, canarySecretKey, canarySessionToken, canaryIMDSToken, canaryIndexerToken, canaryJanitorToken, canaryWrongToken}

// 모든 갈래(성공 · 캐시 · 거부 둘 · 원천 실패 · 저하 · 회복 · 503 · 404 · 405 · healthz)의 로그와 200 이 아닌
// 응답 본문에 카나리아가 없다(P10). 실패 문구에는 원문 대신 「카나리아 발견」 만 적는다.
func TestNoSecretInLogsOrErrorBodies(t *testing.T) {
	r := newRelay(t, 6*time.Hour)
	var bodies []string
	keep := func(code int, body string) {
		if code != http.StatusOK {
			bodies = append(bodies, body)
		}
	}
	do := func(token string) {
		rec := r.get(token)
		keep(rec.Code, rec.Body.String())
	}

	do(canaryIndexerToken) // 성공(원천)
	do(canaryJanitorToken) // 성공(캐시)
	do(canaryWrongToken)   // 거부 mismatch
	r.clock.advance(denyLogInterval)
	do("") // 거부 missing
	r.imds.update(func(f *fakeIMDS) { f.credsStatus = http.StatusInternalServerError })
	r.clock.advance(periodicReread)
	do(canaryIndexerToken) // 원천 실패 · 저하
	r.imds.update(func(f *fakeIMDS) { f.credsStatus = 0 })
	r.clock.advance(minSourceInterval)
	do(canaryIndexerToken) // 회복
	r.imds.update(func(f *fakeIMDS) { f.credsStatus = http.StatusNotFound })
	r.clock.advance(7 * time.Hour)
	do(canaryIndexerToken) // 503
	for _, p := range []struct{ method, path string }{{http.MethodGet, "/nope"}, {http.MethodPut, CredentialsPath}, {http.MethodGet, HealthPath}} {
		req := httptest.NewRequest(p.method, p.path, nil)
		req.Header.Set("Authorization", canaryIndexerToken)
		rec := httptest.NewRecorder()
		r.h.ServeHTTP(rec, req)
		keep(rec.Code, rec.Body.String())
	}

	for _, want := range []string{"cred_served", "cred_denied", "cred_source_failed", "cred_source_degraded", "cred_unavailable"} {
		if len(r.logs.events(t, want)) == 0 {
			t.Errorf("갈래 %s 가 돌지 않았다 — 누설 시험이 그 갈래를 덮지 못한다", want)
		}
	}
	assertNoCanary(t, "로그", r.logs.String())
	for _, b := range bodies {
		assertNoCanary(t, "오류 응답 본문", b)
	}
}

// 기동 실패(토큰 파일 거부)의 에러에도 카나리아가 없다. 두 줄 파일 · other 비트 파일의 내용이 카나리아다.
func TestNoSecretInLoadTokensErrors(t *testing.T) {
	for _, c := range []struct {
		name    string
		content string
		mode    os.FileMode
	}{
		{name: "two_lines", content: canaryIndexerToken + "\n" + canaryJanitorToken, mode: 0o400},
		{name: "other_bit", content: canaryIndexerToken, mode: 0o404},
	} {
		t.Run(c.name, func(t *testing.T) {
			dir := filepath.Join(t.TempDir(), "cred-relay")
			mkdir(t, dir)
			chmod(t, writeToken(t, dir, "indexer", c.content), c.mode)
			_, err := LoadTokens(dir, []string{"indexer"})
			if err == nil {
				t.Fatalf("LoadTokens(%s) 가 받았다", c.name)
			}
			assertNoCanary(t, "LoadTokens 에러", err.Error())
		})
	}
}

func assertNoCanary(t *testing.T, where, text string) {
	t.Helper()
	for _, c := range leakCanaries {
		if strings.Contains(text, c) {
			t.Errorf("%s에서 카나리아 발견(길이 %d)", where, len(c))
		}
	}
}
