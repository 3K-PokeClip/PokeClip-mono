package config

// 관측 폴러의 Control API 자격 env(MTX_API_USER · MTX_API_PASS_FILE — POK-373)의 **동작** 시험.
// Load 만 쓴다 — 새 Config 필드를 쓰지 않아야 제품 코드만 기준 브랜치로 되돌린 트리에서도 컴파일되어
// 음성 대조가 성립한다. 값이 Config 에 실려 폴러까지 가는지는 cmd/segment-indexer 의 boot_auth_test.go 가 잰다.

import (
	"strings"
	"testing"
)

// 시험 값은 모두 합성이다. passFile 은 열지 않는 경로라 실제로 없어도 된다(config 는 파일을 열지 않는다).
const (
	authTestUser     = "sidecar"
	authTestPassFile = "/run/secrets/mtx_api_pass"
	// authTestIPURL 은 자격과 함께 쓸 수 있는 IP 리터럴 주소다(문서용 대역 RFC 5737).
	authTestIPURL = "http://192.0.2.10:9997"
)

// authEnv 는 minimalEnv 에 관측 URL 과 자격 env 를 얹는다. 빈 값은 넣지 않은 것과 같다.
func authEnv(apiURL, user, passFile string) map[string]string {
	env := minimalEnv()
	env["MTX_API_URL"] = apiURL
	env["MTX_API_USER"] = user
	env["MTX_API_PASS_FILE"] = passFile
	return env
}

// 두 env 는 짝이다 — 하나만 있으면 기동 실패다. 조용히 무인증으로 붙으면 MediaMTX 에 무인증 api 항목이
// 없을 때만 401 로 뒤늦게 드러나고(있으면 신호가 없다), 비밀번호 파일만 있으면 그 파일이 쓰이지 않는다.
// 관측이 꺼져 있어도(URL 빈 값) 짝은 본다 — OBS_* 와 같은 「꺼져도 값은 본다」 규칙이다. 에러에는 두 env
// 이름이 함께 있어야 한다.
func TestMTXAPIAuthRequiresBothOrNeither(t *testing.T) {
	for _, apiURL := range []string{authTestIPURL, ""} {
		for _, c := range []struct {
			name, user, passFile string
		}{
			{name: "USER 만", user: authTestUser},
			{name: "PASS_FILE 만", passFile: authTestPassFile},
			{name: "PASS_FILE 은 공백뿐", user: authTestUser, passFile: "   "},
		} {
			_, err := Load(envOf(authEnv(apiURL, c.user, c.passFile)))
			if err == nil {
				t.Errorf("MTX_API_URL=%q · %s: Load 가 짝이 어긋난 자격을 받았다", apiURL, c.name)
				continue
			}
			if msg := err.Error(); !strings.Contains(msg, "MTX_API_USER") || !strings.Contains(msg, "MTX_API_PASS_FILE") {
				t.Errorf("MTX_API_URL=%q · %s: 에러에 두 env 이름이 다 없다: %v", apiURL, c.name, err)
			}
		}
	}
	for _, apiURL := range []string{authTestIPURL, ""} {
		if _, err := Load(envOf(authEnv(apiURL, "", ""))); err != nil {
			t.Errorf("MTX_API_URL=%q · 둘 다 빔: Load 에러 %v, want nil(무인증 — 하위 호환)", apiURL, err)
		}
		if _, err := Load(envOf(authEnv(apiURL, authTestUser, authTestPassFile))); err != nil {
			t.Errorf("MTX_API_URL=%q · 둘 다 있음: Load 에러 %v, want nil", apiURL, err)
		}
	}
}

// 사용자 이름에 ':' 는 넣을 수 없다 — Basic 은 첫 ':' 에서 사용자와 비밀번호를 가르므로 MediaMTX 에는
// 다른 사용자로 보인다. 제어 문자도 거부한다(CRLF 로 저장된 .env 의 끝 '\r' 은 TrimSpace 가 걷지만,
// 가운데 제어 문자는 남는다). 에러에 사용자 이름 값은 싣지 않는다 — env 이름만.
func TestMTXAPIUserRejectsColonAndControl(t *testing.T) {
	for _, apiURL := range []string{authTestIPURL, ""} {
		for _, bad := range []string{"side:car", "side\x01car", "side\x7fcar", "side\tcar"} {
			_, err := Load(envOf(authEnv(apiURL, bad, authTestPassFile)))
			if err == nil {
				t.Errorf("MTX_API_URL=%q · MTX_API_USER=%q: Load 가 받았다", apiURL, bad)
				continue
			}
			if msg := err.Error(); !strings.Contains(msg, "MTX_API_USER") || strings.Contains(msg, bad) {
				t.Errorf("MTX_API_USER=%q: 에러에 env 이름이 없거나 값이 실렸다: %v", bad, err)
			}
		}
	}
	// 앞뒤 공백 · 끝 '\r' 은 TrimSpace 가 걷는다 — 거부하지 않는다.
	if _, err := Load(envOf(authEnv(authTestIPURL, " sidecar\r", authTestPassFile))); err != nil {
		t.Errorf("MTX_API_USER 앞뒤 공백 · 끝 CR: Load 에러 %v, want nil", err)
	}
}

// 자격을 넣으면 MTX_API_URL 은 IP 리터럴 호스트여야 한다 — Basic 자격이 DNS 가 가리키는 곳으로 가지
// 않게(응답기 Control API 클라이언트와 같은 규칙). M2 상자는 네트워크 네임스페이스를 공유하므로
// http://127.0.0.1:9997 로 충족된다.
func TestMTXAPIAuthRequiresIPLiteralHost(t *testing.T) {
	for _, bad := range []string{"http://media:9997", "http://localhost:9997", "https://mtx.internal.example:9997"} {
		_, err := Load(envOf(authEnv(bad, authTestUser, authTestPassFile)))
		if err == nil {
			t.Errorf("MTX_API_URL=%q + 자격: Load 가 호스트 이름을 받았다", bad)
			continue
		}
		if !strings.Contains(err.Error(), "MTX_API_URL") {
			t.Errorf("MTX_API_URL=%q + 자격: 에러에 env 이름이 없다: %v", bad, err)
		}
	}
	for _, ok := range []string{"http://127.0.0.1:9997", "http://[::1]:9997", authTestIPURL, "https://192.0.2.10:9997/"} {
		if _, err := Load(envOf(authEnv(ok, authTestUser, authTestPassFile))); err != nil {
			t.Errorf("MTX_API_URL=%q + 자격: Load 에러 %v, want nil", ok, err)
		}
	}
}

// 자격이 없으면 호스트 이름을 그대로 받는다 — dev compose 의 http://media:9997 이 계속 뜬다.
func TestMTXAPIAuthAllowsHostnameWithoutCredentials(t *testing.T) {
	if _, err := Load(envOf(authEnv("http://media:9997", "", ""))); err != nil {
		t.Errorf("자격 없는 http://media:9997: Load 에러 %v, want nil", err)
	}
}

// config 는 비밀번호 파일을 열지 않는다 — 경로 문자열만 본다(설정은 파일을 열지 않고 손잡이는 main 이
// 만든다 — SEGMENT_ROOT 선례). 그래서 없는 경로도 Load 는 통과하고, 읽기 실패는 main 이 기동 실패로 낸다.
// 경로 문자열은 절대 경로여야 한다 — 상대 경로는 작업 디렉터리에 따라 다른 파일을 가리킨다. 거부 에러에
// 값은 싣지 않는다 — 비밀번호를 경로 자리에 잘못 넣는 실수(MTX_API_PASS_FILE=<비밀번호>)가 바로 이
// 갈래에 걸리고, 그 에러는 stderr 로 나간다. 실패 문구에는 값 대신 「카나리아 발견」 만 적는다.
func TestMTXAPIAuthDoesNotOpenPassFile(t *testing.T) {
	missing := "/nonexistent-" + t.Name() + "/mtx_api_pass"
	if _, err := Load(envOf(authEnv(authTestIPURL, authTestUser, missing))); err != nil {
		t.Errorf("없는 절대 경로: Load 에러 %v, want nil(config 는 파일을 열지 않는다)", err)
	}
	const canary = "passcanary4k"
	for i, rel := range []string{"mtx_api_pass", "./secrets/mtx_api_pass", "../mtx_api_pass", canary} {
		_, err := Load(envOf(authEnv(authTestIPURL, authTestUser, rel)))
		if err == nil {
			t.Errorf("상대 경로 %d: Load 가 받았다(값 생략)", i)
			continue
		}
		msg := err.Error()
		if !strings.Contains(msg, "MTX_API_PASS_FILE") {
			t.Errorf("상대 경로 %d: 에러에 env 이름이 없다", i)
		}
		if strings.Contains(msg, rel) {
			t.Errorf("상대 경로 %d: 에러에 env 값 발견", i)
		}
	}
}

// 자격 검사의 **어느** 갈래에서도 MTX_API_USER · MTX_API_PASS_FILE 값이 에러에 실리지 않는다 — env 이름과
// 무엇이 틀렸는지만. 사용자 이름 자리에 비밀번호를 넣는 실수도 있어 두 값 모두 비밀처럼 다룬다. 갈래마다
// 두 값에 카나리아를 섞어 0회를 본다. 실패 문구에는 값 대신 「카나리아 발견」 만 적는다.
func TestMTXAPIAuthErrorsNeverEchoValues(t *testing.T) {
	const (
		userCanary = "usercanary3m"
		passCanary = "passcanary8w"
		hostCanary = "hostcanary5t"
	)
	absPass := "/run/secrets/" + passCanary
	for _, c := range []struct{ branch, apiURL, user, passFile string }{
		{branch: "짝 — USER 만", apiURL: authTestIPURL, user: userCanary},
		{branch: "짝 — PASS_FILE 만", apiURL: authTestIPURL, passFile: absPass},
		{branch: "':'", apiURL: authTestIPURL, user: userCanary + ":" + passCanary, passFile: absPass},
		{branch: "제어 문자", apiURL: authTestIPURL, user: userCanary + "\x01", passFile: absPass},
		{branch: "상대 경로", apiURL: authTestIPURL, user: userCanary, passFile: passCanary},
		{branch: "IP 리터럴", apiURL: "http://" + hostCanary + ".example:9997", user: userCanary, passFile: absPass},
	} {
		_, err := Load(envOf(authEnv(c.apiURL, c.user, c.passFile)))
		if err == nil {
			t.Errorf("%s 갈래: Load 가 받았다(값 생략)", c.branch)
			continue
		}
		msg := err.Error()
		for _, canary := range []string{userCanary, passCanary, hostCanary} {
			if strings.Contains(msg, canary) {
				t.Errorf("%s 갈래: 에러에 카나리아 발견", c.branch)
			}
		}
	}
}

// URL 에 자격(user:pass@)을 넣으면 거부하고, 에러가 자격을 넣을 자리(두 env)를 가리킨다.
func TestMTXAPIURLUserinfoPointsToNewEnv(t *testing.T) {
	_, err := Load(envOf(authEnv("http://sidecar:pw@192.0.2.10:9997", "", "")))
	if err == nil {
		t.Fatal("userinfo 가 든 MTX_API_URL 을 받았다")
	}
	if msg := err.Error(); !strings.Contains(msg, "MTX_API_USER") || !strings.Contains(msg, "MTX_API_PASS_FILE") {
		t.Errorf("userinfo 거부 에러가 자격 env 를 가리키지 않는다: %v", err)
	}
}

// MTX_API_URL 검증의 **어느** 갈래에서도 URL 원문이 에러에 실리지 않는다. 원문에 자격이 섞인 채 다른
// 갈래(예: scheme)에 걸리면 그 에러가 stderr 로 나가 자격이 샌다. 갈래마다 카나리아를 섞어 0회를 본다.
// 실패 문구에는 원문 대신 「카나리아 발견」 만 적는다.
func TestMTXAPIURLErrorsNeverEchoRaw(t *testing.T) {
	const canary = "urlcanary7q"
	for _, c := range []struct{ branch, raw string }{
		{branch: "파싱 실패", raw: "http://sidecar:" + canary + "@192.0.2.10:port"},
		{branch: "호스트 없음", raw: "http://:9997/" + canary},
		{branch: "scheme", raw: "ftp://192.0.2.10:9997/" + canary},
		{branch: "scheme 과 userinfo", raw: "ftp://sidecar:" + canary + "@192.0.2.10:9997"},
		{branch: "userinfo", raw: "http://sidecar:" + canary + "@192.0.2.10:9997"},
		{branch: "query", raw: "http://192.0.2.10:9997?pass=" + canary},
		{branch: "fragment", raw: "http://192.0.2.10:9997#" + canary},
	} {
		_, err := Load(envOf(authEnv(c.raw, "", "")))
		if err == nil {
			t.Errorf("%s 갈래: Load 가 받았다(원문 생략)", c.branch)
			continue
		}
		msg := err.Error()
		if strings.Contains(msg, canary) {
			t.Errorf("%s 갈래: 에러에 카나리아 발견 — URL 원문이 실렸다", c.branch)
		}
		if !strings.Contains(msg, "MTX_API_URL") {
			t.Errorf("%s 갈래: 에러에 env 이름이 없다", c.branch)
		}
	}
}

// 사이드카는 MTX_API_PASS(응답기의 env 값 방식)를 읽지 않는다 — 비밀번호는 파일로만 받는다(env 값은
// docker inspect 에 남는다). 그 값만 있으면 무인증으로 기동한다(kty 2026-10-19 — 확정 계약 「두 env 가
// 비면 무인증」). MediaMTX 에 무인증 api 항목이 없을 때만 401 → auth_rejected ERROR 로 드러난다.
// 규칙이 다시 생기지 않게 막는다.
func TestMTXAPIAuthIgnoresMTXAPIPass(t *testing.T) {
	env := authEnv(authTestIPURL, "", "")
	env["MTX_API_PASS"] = "synthetic-env-pass"
	if _, err := Load(envOf(env)); err != nil {
		t.Errorf("MTX_API_PASS 만 있음: Load 에러 %v, want nil(읽지 않는다)", err)
	}
}
