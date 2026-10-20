package main

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"maps"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"
)

// 누설 시험의 실행 파일 쪽이다(설계 6절 누설 시험). 실행 파일의 겉 — 환경 변수 · run · healthcheck · cut · HTTP · stdout ·
// stderr · 상태 디렉터리의 기록 파일 — 만으로 돌리고 처리기 내부를 모른다. 판정표의 모든 행 · 예산 · 차례 · 기억 해제 ·
// 게이지 셋 · 절단 갈래가 내는 내용은 internal/ingestauth 의 누설 시험이 패키지 안에서 본다. 여기서는 그 출력이 실행
// 파일의 출력 자리(run 의 JSON 로그 · stderr · healthcheck 의 stderr · cut 의 한 줄 · stderr · 기록 파일)로 나갈 때와
// 실행 파일이 더하는 출력(기동 · 정지 로그 · healthz 까닭 · cut 출력)에 비밀이 없는지 본다. 설정 오류의 stderr 와 cut 의
// 실패 사유 표는 config_test · cut_test 가 본다.
//
// 흐름마다 카나리아가 실제로 입력에 들어갔음을 먼저 확인한다 — 가짜 auth 는 출입증 머리가 맞을 때만 passphrase 를
// 돌려주고, 가짜 MediaMTX 는 Basic 자격이 맞을 때만 답한다. 실패 문구에는 카나리아 이름 · 출력 자리 · 횟수만 싣는다 —
// 출력 내용과 카나리아 값은 싣지 않는다.

// MediaMTX 요청의 비밀 필드에 싣는 카나리아와 누설 시험의 Control API 사용자다(합성 — 어떤 계정의 값도 아니다). 설정값
// testAPIUser("ingest-auth")는 실행 파일 이름 · stderr 머리와 겹쳐 카나리아로 쓸 수 없다 — 누설 시험만 고유한 사용자를
// 쓴다(MTX_API_USER · 가짜 MediaMTX 의 자격 · api 행의 user).
const (
	leakRequestUser     = "synthetic-mtx-user-leak-0002"
	leakRequestPassword = "synthetic-mtx-password-leak-0002"
	leakRequestToken    = "synthetic-mtx-jwt-leak-0002"
	leakRequestQuery    = "synthetic-mtx-query-leak-0002"
	leakAPIUser         = "synthetic-mtx-api-user-leak-0003"
	// leakPathSecret 은 비밀 값을 `_FILE` 자리에 잘못 넣은 경우의 값이다 — 경로가 곧 비밀이다(계약 3-2).
	leakPathSecret = "synthetic-path-is-secret-leak-0005"
)

// leakCanary 는 출력 어디에도 나와서는 안 되는 합성 비밀 하나다. 실패 문구에는 name 만 싣는다.
type leakCanary struct {
	name  string
	value string
}

// leakCanaries 는 실행 파일 누설 시험의 카나리아다. Basic 자격은 Authorization 머리에 실리는 base64(RFC 7617 2절)라 머리째
// 새면 평문 카나리아로는 보이지 않는다.
var leakCanaries = []leakCanary{
	{name: "passphrase", value: testPassphrase},
	{name: "resolve 출입증", value: testResolveToken},
	{name: "API 사용자", value: leakAPIUser},
	{name: "API 비번", value: testAPIPass},
	{name: "Basic 자격", value: base64.StdEncoding.EncodeToString([]byte(leakAPIUser + ":" + testAPIPass))},
	{name: "MediaMTX 요청 user", value: leakRequestUser},
	{name: "MediaMTX 요청 password", value: leakRequestPassword},
	{name: "MediaMTX 요청 token", value: leakRequestToken},
	{name: "MediaMTX 요청 query", value: leakRequestQuery},
	{name: "비밀 파일 경로", value: secretPathCanary},
	{name: "경로 자리의 비밀", value: leakPathSecret},
	{name: "호출자 Basic 비번", value: testCallerPass},
	{name: "호출자 Basic 비번(16진)", value: hex.EncodeToString([]byte(testCallerPass))},
	{name: "호출자 Basic 자격", value: base64.StdEncoding.EncodeToString([]byte(testCallerUser + ":" + testCallerPass))},
}

// leakOutput 은 누설을 훑는 출력 자리 하나다. place 는 실패 문구에 싣는 자리 이름이다.
type leakOutput struct {
	place   string
	content string
}

// leakProblems 는 outputs 에서 찾은 카나리아를 실패 문구로 돌려준다 — 출력 자리 · 카나리아 이름 · 횟수만 싣는다.
func leakProblems(outputs []leakOutput) []string {
	var problems []string
	for _, o := range outputs {
		for _, c := range leakCanaries {
			if n := strings.Count(o.content, c.value); n > 0 {
				problems = append(problems, fmt.Sprintf("%s carries the %s canary %d time(s), want 0", o.place, c.name, n))
			}
		}
	}
	return problems
}

// stateFiles 는 dir 의 파일마다 「기록 파일 <이름>」 출력 자리다(기록의 세대 모두).
func stateFiles(t *testing.T, dir string) []leakOutput {
	t.Helper()
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("Setup: %v", err)
	}
	var outputs []leakOutput
	for _, e := range entries {
		if !e.Type().IsRegular() {
			continue
		}
		data, err := os.ReadFile(filepath.Join(dir, e.Name()))
		if err != nil {
			t.Fatalf("Setup: %v", err)
		}
		outputs = append(outputs, leakOutput{place: "기록 파일 " + e.Name(), content: string(data)})
	}
	return outputs
}

// leakPublishBody 는 MediaMTX 가 SRT publish 인증에 싣는 본문이다(publishBody) — publish 가 읽지 않는 user · password ·
// token · query 에 카나리아를 싣는다(설계 2-4 「읽지도 기록하지도 않는 필드」).
func leakPublishBody(token, connID string) string {
	return fmt.Sprintf(`{"ip":%q,"user":%q,"password":%q,"token":%q,"action":"publish","path":%q,"protocol":"srt","id":%q,"query":%q,"userAgent":""}`,
		testPublisherIP, leakRequestUser, leakRequestPassword, leakRequestToken, token, connID, leakRequestQuery)
}

// leakAPIBody 는 Control API 요청의 인증 본문이다(apiBody) — api 행이 보지 않는 token · query 에도 카나리아를 싣는다.
func leakAPIBody(ip, user, pass string) string {
	return fmt.Sprintf(`{"ip":%q,"user":%q,"password":%q,"token":%q,"action":"api","path":"","protocol":"","id":null,"query":%q,"userAgent":""}`,
		ip, user, pass, leakRequestToken, leakRequestQuery)
}

// badSecretFileOutputs 는 args 의 하위 명령을 쓸 수 없는 비밀 파일 둘 — 경로 자리에 비밀을 넣음(열기 실패) · other 비트가
// 있는 파일(열린 뒤의 거부) — 로 돌린 출력 자리다. name 은 비밀 환경 변수 이름이다. 둘 다 설정 오류(2)여야 하고, 그 에러가
// 변수 이름을 실어야 출력 자리를 지난 것이다(양성 대조).
func badSecretFileOutputs(t *testing.T, s site, args []string, env map[string]string, name string) []leakOutput {
	t.Helper()
	var outputs []leakOutput
	for _, c := range []struct {
		place string
		path  string
	}{
		{place: "경로 자리의 비밀", path: "/" + leakPathSecret},
		{place: "열린 뒤 거부", path: writeSecretFile(t, secretDir(t), "world", testAPIPass+"\n", 0o644)},
	} {
		bad := maps.Clone(env)
		delete(bad, name)
		bad[name+"_FILE"] = c.path
		ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
		stdout, stderr := newOutputLog(), newOutputLog()
		code := run(ctx, s, args, getenvFrom(bad), stdout, stderr)
		cancel()
		if code != 2 || !strings.Contains(stderr.String(), name+"_FILE") {
			t.Errorf("run(%q) with %s as %s_FILE = %d (stderr names it: %t), want 2 naming it", args, c.place, name, code, strings.Contains(stderr.String(), name+"_FILE"))
		}
		place := fmt.Sprintf("%q %s_FILE %s", args, name, c.place)
		outputs = append(outputs, leakOutput{place: place + " stdout", content: stdout.String()},
			leakOutput{place: place + " stderr", content: stderr.String()})
	}
	return outputs
}

// missingEvents 는 names 가운데 out 에 한 번도 없는 이벤트 이름이다.
func missingEvents(out string, names ...string) []string {
	var missing []string
	for _, name := range names {
		if len(logEvents(out, name)) == 0 {
			missing = append(missing, name)
		}
	}
	return missing
}

// run 의 한 생애 — 기동 · publish 허용(기록 회전) · 거절 · 본문 해석 실패 · api 행 · 거부 목록의 틀린 줄(healthz 실패 ·
// 게이지 set · clear) · auth 끊김(판정 불가 · auth 게이지) · 정지 — 의 출력에 비밀이 없다. 요청 본문의 비밀 필드 ·
// 출입증(환경 변수 → 4C 머리) · API 사용자 · 비번(환경 변수 · api 행 — 허용 행의 user 도 카나리아다) · passphrase(4C
// 답)가 이 흐름의 입력이다. 거부 목록에는 운영자가 토큰 자리에 출입증을 잘못 붙인 줄을 둔다. 비밀은 파일로 받고(경로의
// 디렉터리 이름도 카나리아다), 기동 전에 쓸 수 없는 비밀 파일 둘(경로 자리의 비밀 · 열린 뒤 거부)로 실패한 기동의 출력도
// 훑는다.
func TestNoSecretsInRunOutputs(t *testing.T) {
	auth := newFakeAuth(t)
	s := newTestSite(t, "")
	fillGeneration(t, s.connLogDir) // 첫 허용이 세대를 돌린다 — 새 세대의 첫 줄은 시작 표시다
	env := responderEnv(auth.srv.URL)
	env["LOG_LEVEL"] = "debug" // DEBUG 이벤트(api 행 허용)까지 훑는다
	env["MTX_API_USER"] = leakAPIUser
	keepDefaultLogger(t)
	var failed []leakOutput
	for _, v := range secretVariables {
		failed = append(failed, badSecretFileOutputs(t, s, nil, env, v.name)...)
	}
	dir := secretDir(t)
	for _, v := range secretVariables {
		useSecretFile(t, env, dir, v.name, v.value+"\n")
	}
	r := startResponder(t, s, env)
	var bodied []int // 본문을 실은 비-2xx 응답의 상태(누설 규칙 3)
	post := func(body string) (int, string) {
		t.Helper()
		status, respBody := mustPostAuth(t, r.listen, body, nil)
		if status/100 != 2 && respBody != "" {
			bodied = append(bodied, status)
		}
		return status, respBody
	}

	if status, body := post(leakPublishBody(testToken, testConnID(1))); status != http.StatusOK || body != wantAllowedBody {
		t.Fatalf("Setup: POST publish = %d (allowed body: %t), want 200 with the allowed body — the resolve token and passphrase never flowed", status, body == wantAllowedBody)
	}
	post(strings.Replace(leakPublishBody(testToken, testConnID(2)), `"protocol":"srt"`, `"protocol":"rtmp"`, 1))
	post("user=" + leakRequestUser + "&password=" + leakRequestPassword + "&token=" + leakRequestToken + "&query=" + leakRequestQuery)
	if status, _ := post(leakAPIBody("127.0.0.1", leakAPIUser, testAPIPass)); status != http.StatusOK {
		t.Errorf("POST api with the configured credentials = %d, want 200 — the API user and password never reached the api row", status)
	}
	post(leakAPIBody("127.0.0.1", leakRequestUser, leakRequestPassword))
	writeFile(t, s.denyList, testOtherToken+"\n"+testResolveToken+"\n")
	failCode, failStderr := runHealthcheck(t, r.health)
	post(leakPublishBody(testToken, testConnID(3)))
	writeFile(t, s.denyList, testOtherToken+"\n")
	okCode, okStderr := runHealthcheck(t, r.health)
	post(leakPublishBody(testOtherToken, testConnID(4)))
	auth.srv.Close()
	post(leakPublishBody(testToken, testConnID(5)))
	if code := r.stopAndWait(t); code != 0 {
		t.Errorf("run after its context ended = %d, want 0", code)
	}

	if failCode != 1 || failStderr == "" || okCode != 0 {
		t.Errorf("healthcheck with a pasted secret in the deny list = %d (reason on stderr: %t), after fixing it = %d, want 1 with a reason, then 0", failCode, failStderr != "", okCode)
	}
	stdout := r.stdout.String()
	if missing := missingEvents(stdout,
		"ingestauth_started", "ingestauth_connlog_rotated", "ingestauth_publish_allowed", "ingestauth_publish_denied",
		"ingestauth_request_invalid", "ingestauth_api_allowed", "ingestauth_api_denied", "ingestauth_denylist_error",
		"ingestauth_denylist_recovered", "ingestauth_auth_degraded", "ingestauth_stopped",
	); len(missing) != 0 {
		t.Errorf("run stdout lacks %v — the flow did not take those paths", missing)
	}
	var reasons []string
	for _, e := range logEvents(stdout, "ingestauth_publish_denied") {
		if reason, ok := e["reason"].(string); ok {
			reasons = append(reasons, reason)
		}
	}
	for _, want := range []string{"not_srt", "denylisted", "undecided"} {
		if !slices.Contains(reasons, want) {
			t.Errorf("run stdout lacks ingestauth_publish_denied with reason %s — the flow did not take that row", want)
		}
	}
	for _, status := range bodied {
		t.Errorf("a %d response carries a body, want none (누설 규칙 3)", status)
	}
	outputs := append([]leakOutput{
		{place: "run stdout", content: stdout},
		{place: "run stderr", content: r.stderr.String()},
		{place: "healthcheck stderr", content: failStderr + okStderr},
	}, stateFiles(t, s.connLogDir)...)
	outputs = append(outputs, failed...)
	for _, p := range leakProblems(outputs) {
		t.Error(p)
	}
}

// cut 의 세 갈래(기록 · 목록 · 기록 밖)와 실패 사유의 출력(stdout 한 줄 · stderr)에 비밀이 없다. 가짜 MediaMTX 는 Basic
// 자격이 맞을 때만 답하므로(실패를 넣은 갈래도 자격을 본 뒤 500 이다) 끝까지 끊은 cut 과 그 갈래의 500 이 곧 자격이
// 실린 증거다. 실패는 Control API 의 세 요청 — 목록 · kick · 조회 — 을 하나씩 500 으로 돈다: 그 에러가 cut 의 stderr 로
// 나가는 자리가 요청마다 따로다(mtxctl 의 상태 에러 반환). 사유 표 전체는 cut_test 가 평문 카나리아로 보고, 여기서는
// API 사용자와 Basic 자격(base64)까지 훑는다. Control API 비번은 파일로 받고, 쓸 수 없는 비밀 파일 둘(경로 자리의 비밀 ·
// 열린 뒤 거부)로 실패한 cut 의 출력도 훑는다.
func TestNoSecretsInCutOutputs(t *testing.T) {
	// 기록 갈래는 고정물이 둔다 — 거부 목록에 testToken, 기록과 목록에 그 토큰의 1번 연결.
	s, m, env := cutFixtureWithAPIUser(t, leakAPIUser)
	failed := badSecretFileOutputs(t, s, []string{"cut", testToken}, env, "MTX_API_PASS")
	useSecretFile(t, env, secretDir(t), "MTX_API_PASS", testAPIPass+"\n")
	m.add(testConnID(2), fakeConn{created: time.Now(), state: "publish", path: testToken})      // 목록 갈래 — 기록에 없다
	m.add(testConnID(3), fakeConn{created: time.Now().Add(-40 * time.Second), state: "idle"})   // 기록 밖 갈래 — 기록보다 먼저 생겨 30초 넘음
	m.add(testConnID(4), fakeConn{created: time.Now(), state: "publish", path: testOtherToken}) // 다른 토큰 — 남는다

	code, stdout, stderr := runCut(t, s, env, testToken)
	if completed := logEvents(stdout, "ingestauth_cut_completed"); code != 0 || len(completed) != 1 {
		t.Errorf("cut over the three branches = %d with %d cut_completed lines, want 0 with one", code, len(completed))
	} else {
		for field, want := range map[string]float64{"from_log": 1, "from_list": 1, "from_unmapped": 1, "kicked": 3} {
			// 숫자가 아니면 값을 찍지 않는다 — 그 자리에 무엇이 실렸는지 모른다.
			switch got, ok := completed[0][field].(float64); {
			case !ok:
				t.Errorf("cut_completed %s is not a number, want %v", field, want)
			case got != want:
				t.Errorf("cut_completed %s = %v, want %v", field, got, want)
			}
		}
	}
	outputs := append([]leakOutput{{place: "cut stdout (세 갈래)", content: stdout}, {place: "cut stderr (세 갈래)", content: stderr}}, failed...)

	// 실패 셋. 기록 갈래의 1번 연결은 앞의 cut 이 끊었어도 kick 은 나간다. 조회 실패는 새 송출(5번)을 kick 200 으로
	// 끊은 뒤의 확인 조회다. 그 요청의 실패가 stderr 에 적혔는지 먼저 본다(양성 대조 — 그 갈래의 에러가 출력 자리를
	// 지났다).
	for _, f := range []struct {
		place  string // 실패 문구의 출력 자리 이름
		inject func()
		failed string // 그 요청의 실패가 stderr 에 적히는 꼴(mtxctl.Error 의 문구)
	}{
		{place: "목록 500", inject: func() { m.set(func(m *fakeMediaMTX) { m.status["list"] = 500 }) }, failed: "mtxctl: list — HTTP 500"},
		{place: "kick 500", inject: func() {
			m.set(func(m *fakeMediaMTX) { delete(m.status, "list"); m.status["kick"] = 500 })
		}, failed: "mtxctl: kick — HTTP 500"},
		{place: "조회 500", inject: func() {
			m.add(testConnID(5), fakeConn{created: time.Now(), state: "publish", path: testToken})
			m.set(func(m *fakeMediaMTX) { delete(m.status, "kick"); m.status["get"] = 500 })
		}, failed: "mtxctl: get — HTTP 500"},
	} {
		f.inject()
		failCode, failStdout, failStderr := runCut(t, s, env, testToken)
		incomplete := logEvents(failStdout, "ingestauth_cut_incomplete")
		reasonOK := len(incomplete) == 1 && incomplete[0]["reason"] == "api_unreachable"
		if named := strings.Contains(failStderr, f.failed); failCode != 1 || !reasonOK || !named {
			t.Errorf("cut while the Control API answers %s = %d (one api_unreachable line: %t, that request's failure on stderr: %t), want 1 with both",
				f.place, failCode, reasonOK, named)
		}
		outputs = append(outputs, leakOutput{place: "cut stdout (" + f.place + ")", content: failStdout},
			leakOutput{place: "cut stderr (" + f.place + ")", content: failStderr})
	}
	for _, p := range leakProblems(append(outputs, stateFiles(t, s.connLogDir)...)) {
		t.Error(p)
	}
}
