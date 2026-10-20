package main

import (
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// 비밀의 `_FILE` 입력 시험이다(계약 3-2 — 비밀은 값으로도, 그 값이 든 파일의 경로로도 받는다). 파일의 검사 규칙
// 자체(종류 · 권한 · 크기 · 끝 줄바꿈)는 internal/secretfile 이 패키지 안에서 보고, 여기서는 run · cut 의 경계 —
// 어느 변수를 읽는가 · 둘 다 있거나 없으면 어떻게 되는가 · 에러에 경로가 실리지 않는가 — 를 본다.

// secretVariables 는 값과 `_FILE` 둘 중 하나로 받는 비밀 환경 변수와 그 합성 값이다.
var secretVariables = []struct {
	name, value string
}{
	{name: "MTX_API_PASS", value: testAPIPass},
	{name: "STREAM_KEY_RESOLVE_TOKEN", value: testResolveToken},
}

// secretPathCanary 는 비밀 파일 경로의 디렉터리 이름이다 — 경로가 어느 출력에 실리면 이 값이 보인다.
const secretPathCanary = "synthetic-secret-dir-canary-0004"

// secretDir 는 경로에 secretPathCanary 가 든 빈 디렉터리다.
func secretDir(t *testing.T) string {
	t.Helper()
	dir := filepath.Join(t.TempDir(), secretPathCanary)
	if err := os.Mkdir(dir, 0o700); err != nil {
		t.Fatalf("Setup: mkdir %s: %v", dir, err)
	}
	return dir
}

// writeSecretFile 은 dir 아래에 content 를 담은 파일을 mode 로 만든다. WriteFile 의 모드는 umask 를 거치므로 Chmod 로
// 다시 박는다.
func writeSecretFile(t *testing.T, dir, name, content string, mode os.FileMode) string {
	t.Helper()
	path := filepath.Join(dir, name)
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatalf("Setup: could not write %s: %v", path, err)
	}
	if err := os.Chmod(path, mode); err != nil {
		t.Fatalf("Setup: could not chmod %s to %v: %v", path, mode, err)
	}
	return path
}

// useSecretFile 은 env 의 name 을 지우고 name_FILE 이 content 를 담은 0400 파일을 가리키게 한다. 파일 경로를 돌려준다.
func useSecretFile(t *testing.T, env map[string]string, dir, name, content string) string {
	t.Helper()
	path := writeSecretFile(t, dir, strings.ToLower(name), content, 0o400)
	delete(env, name)
	env[name+"_FILE"] = path
	return path
}

// 세 비밀을 모두 파일로 주면 기동하고, 파일 내용(끝 줄바꿈을 걷은 값)이 쓰인다 — 가짜 4C 는 출입증 머리가 맞을 때만
// passphrase 를 돌려주고(publish 200 · 허용 본문), api 행은 Control API 비번이 맞을 때만 200 이다.
func TestRunReadsSecretsFromFiles(t *testing.T) {
	auth := newFakeAuth(t)
	env := responderEnv(auth.srv.URL)
	dir := secretDir(t)
	for _, v := range secretVariables {
		useSecretFile(t, env, dir, v.name, v.value+"\n")
	}
	r := startResponder(t, newTestSite(t, ""), env)

	if status, body := mustPostAuth(t, r.listen, publishBody(testToken, testConnID(1)), nil); status != http.StatusOK || body != wantAllowedBody {
		t.Errorf("POST publish with the resolve token from a file = %d (allowed body: %t), want 200 with the allowed body", status, body == wantAllowedBody)
	}
	if status, _ := mustPostAuth(t, r.listen, apiBody("127.0.0.1", testAPIUser, testAPIPass), nil); status != http.StatusOK {
		t.Errorf("POST api with the API password from a file = %d, want 200", status)
	}
	checkNoSecrets(t, []string{r.stdout.String(), r.stderr.String()}, secretPathCanary)
}

// 값과 파일을 함께 주면 기동하지 않는다 — 한쪽이 조용히 이기면 교체한 뒤에도 옛 값을 쓴다. 에러는 두 이름을 싣고 값 ·
// 경로는 싣지 않는다.
func TestRunRejectsSecretValueAndFileTogether(t *testing.T) {
	for _, v := range secretVariables {
		t.Run(v.name, func(t *testing.T) {
			env := configEnv()
			env[v.name+"_FILE"] = writeSecretFile(t, secretDir(t), "secret", v.value+"\n", 0o400)
			stderr := runConfigFailure(t, nil, env, v.name+"_FILE", secretPathCanary)
			if !strings.Contains(stderr, v.name+" ") {
				t.Errorf("stderr does not name %s on its own as well (stderr: %s)", v.name, outputSummary(stderr))
			}
		})
	}
}

// 값도 파일도 없으면 기동하지 않는다. 에러는 두 대안을 함께 이름 붙인다 — 운영자가 파일 입력이 있다는 것을 안다.
func TestRunRequiresSecretValueOrFile(t *testing.T) {
	for _, v := range secretVariables {
		t.Run(v.name, func(t *testing.T) {
			env := configEnv()
			delete(env, v.name)
			runConfigFailure(t, nil, env, v.name+" 또는 "+v.name+"_FILE")
		})
	}
}

// 쓸 수 없는 비밀 파일은 기동 실패다. 열기 실패든 열린 뒤의 거부든 에러는 경로를 싣지 않고(계약 3-2 — 경로 대신 변수
// 이름) 변수 이름과 원인만 싣는다. 경로의 디렉터리 이름이 카나리아다.
func TestRunRejectsBadSecretFiles(t *testing.T) {
	for _, v := range secretVariables {
		for _, tc := range []struct {
			name  string
			path  func(t *testing.T, dir string) string
			cause string // 에러에 있어야 하는 원인
		}{
			{name: "상대_경로", path: func(*testing.T, string) string { return secretPathCanary + "/secret" }, cause: "절대 경로"},
			{name: "없는_파일", path: func(_ *testing.T, dir string) string { return filepath.Join(dir, "missing") }, cause: "no such file"},
			{name: "other_비트", path: func(t *testing.T, dir string) string { return writeSecretFile(t, dir, "s", v.value+"\n", 0o604) }, cause: "other 권한"},
			{name: "group_쓰기", path: func(t *testing.T, dir string) string { return writeSecretFile(t, dir, "s", v.value+"\n", 0o620) }, cause: "group 쓰기"},
			{name: "빈_내용", path: func(t *testing.T, dir string) string { return writeSecretFile(t, dir, "s", "", 0o400) }, cause: "비었다"},
			{name: "두_줄", path: func(t *testing.T, dir string) string { return writeSecretFile(t, dir, "s", v.value+"\nmore\n", 0o400) }, cause: "한 줄만"},
			{name: "4KiB_초과", path: func(t *testing.T, dir string) string {
				return writeSecretFile(t, dir, "s", strings.Repeat("x", 4097), 0o400)
			}, cause: "4096 바이트"},
			{name: "디렉터리", path: func(t *testing.T, dir string) string { return dir }, cause: "일반 파일"},
		} {
			t.Run(v.name+"/"+tc.name, func(t *testing.T) {
				env := configEnv()
				delete(env, v.name)
				env[v.name+"_FILE"] = tc.path(t, secretDir(t))
				stderr := runConfigFailure(t, nil, env, v.name+"_FILE", secretPathCanary)
				if !strings.Contains(stderr, tc.cause) {
					t.Errorf("stderr does not carry the cause %q (stderr: %s)", tc.cause, outputSummary(stderr))
				}
			})
		}
	}
}

// 응답기 계정이 읽을 수 없는 파일(배치의 소유가 어긋남)은 원인 permission denied 와 변수 이름으로 드러난다. root 는 권한
// 비트와 무관하게 읽으므로 건너뛴다.
func TestRunRejectsUnreadableSecretFile(t *testing.T) {
	skipIfRoot(t)
	for _, v := range secretVariables {
		t.Run(v.name, func(t *testing.T) {
			env := configEnv()
			delete(env, v.name)
			env[v.name+"_FILE"] = writeSecretFile(t, secretDir(t), "s", v.value+"\n", 0o000)
			stderr := runConfigFailure(t, nil, env, v.name+"_FILE", secretPathCanary)
			if !strings.Contains(stderr, "permission denied") {
				t.Errorf("stderr does not carry permission denied (stderr: %s)", outputSummary(stderr))
			}
		})
	}
}

// 끝 줄바꿈은 하나(\n 또는 \r\n)만 걷는다. 끝 줄바꿈이 둘이면 거부하고, 앞뒤 공백이 든 출입증은 머리 값으로 실을 수
// 없어 기동을 거부한다 — 걷지 않은 공백이 4C 의 401 영구 고장으로 이어지지 않게 기동에서 드러낸다.
func TestRunStripsOneTrailingNewlineFromSecretFiles(t *testing.T) {
	for _, ending := range []string{"\n", "\r\n"} {
		t.Run(strings.ReplaceAll(strings.ReplaceAll(ending, "\r", "CR"), "\n", "LF"), func(t *testing.T) {
			auth := newFakeAuth(t)
			env := responderEnv(auth.srv.URL)
			useSecretFile(t, env, secretDir(t), "STREAM_KEY_RESOLVE_TOKEN", testResolveToken+ending)
			r := startResponder(t, newTestSite(t, ""), env)
			if status, body := mustPostAuth(t, r.listen, publishBody(testToken, testConnID(1)), nil); status != http.StatusOK || body != wantAllowedBody {
				t.Errorf("POST publish = %d (allowed body: %t), want 200 — the file's trailing newline was not stripped once", status, body == wantAllowedBody)
			}
		})
	}
	for _, tc := range []struct{ name, content string }{
		{name: "끝_줄바꿈_둘", content: testResolveToken + "\n\n"},
		{name: "끝_공백", content: testResolveToken + " \n"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			env := configEnv()
			useSecretFile(t, env, secretDir(t), "STREAM_KEY_RESOLVE_TOKEN", tc.content)
			runConfigFailure(t, nil, env, "STREAM_KEY_RESOLVE_TOKEN", secretPathCanary)
		})
	}
}
