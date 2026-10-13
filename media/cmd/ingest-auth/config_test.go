package main

import (
	"context"
	"strings"
	"testing"
	"time"
)

// 설정 검사의 시험이다(설계 2-6 — 필수값이 비면 기동 실패). 설정 오류는 종료 코드 2 이고 아무것도 열지 않는다.
// 에러에는 변수 이름만 싣는다 — 자리를 잘못 넣은 비밀이 출력으로 나가지 않게(설계 6절 누설 규칙).

// runVariables 는 run 이 받는 필수 환경 변수다(설계 2-6 변수표).
var runVariables = []string{
	"INGEST_AUTH_LISTEN",
	"INGEST_AUTH_CALLER_IP",
	"MTX_API_URL",
	"MTX_API_USER",
	"MTX_API_PASS",
	"STREAM_KEY_RESOLVE_URL",
	"STREAM_KEY_RESOLVE_TOKEN",
}

// runConfigFailure 는 env 로 run 을 돌려 설정 오류로 끝나는지 본다 — 종료 2 · stderr 에 name · 기동 로그 없음 ·
// 비밀 카나리아와 extra 없음. 검사를 빠뜨린 변이가 리스너를 열어도 시험이 멈추지 않게 run 의 ctx 에 5초 상한을 둔다.
func runConfigFailure(t *testing.T, args []string, env map[string]string, name string, extra ...string) {
	t.Helper()
	keepDefaultLogger(t)
	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()
	stdout, stderr := newOutputLog(), newOutputLog()
	code := run(ctx, newTestSite(t, ""), args, getenvFrom(env), stdout, stderr)
	if code != 2 {
		t.Errorf("run(%q) = %d, want 2 (configuration error); stderr: %s", args, code, stderr.String())
	}
	if !strings.Contains(stderr.String(), name) {
		t.Errorf("run(%q) stderr does not name %s: %q", args, name, stderr.String())
	}
	if events := logEvents(stdout.String(), "ingestauth_started"); len(events) != 0 {
		t.Errorf("run(%q) logged ingestauth_started despite the configuration error", args)
	}
	checkNoSecrets(t, []string{stdout.String(), stderr.String()}, extra...)
}

func TestRunRequiresEveryVariable(t *testing.T) {
	for _, name := range runVariables {
		t.Run(name, func(t *testing.T) {
			env := configEnv()
			delete(env, name)
			runConfigFailure(t, nil, env, name)
		})
	}
}

func TestRunRejectsInvalidValues(t *testing.T) {
	for _, tc := range []struct {
		name   string // 시험 이름
		key    string // 바꾸는 변수
		value  string // 잘못된 값
		canary bool   // 값이 출력에 실리면 안 된다 — 미지정 주소는 비밀일 수 없고 에러 문구가 규칙으로 적는다
	}{
		{name: "LISTEN_꼴_밖", key: "INGEST_AUTH_LISTEN", value: "synthetic-listen-canary-0001", canary: true},
		{name: "LISTEN_포트_없음", key: "INGEST_AUTH_LISTEN", value: "192.0.2.20", canary: true},
		{name: "LISTEN_호스트_이름", key: "INGEST_AUTH_LISTEN", value: "ingest-auth.test:8080", canary: true},
		// 미지정 주소는 묶을 수 있는 주소라 임의 포트(0)를 준다 — 검사를 빠뜨린 변이가 이 기계의 고정 포트를 잡지 않게.
		{name: "LISTEN_미지정_IPv4", key: "INGEST_AUTH_LISTEN", value: "0.0.0.0:0"},
		{name: "LISTEN_미지정_IPv6", key: "INGEST_AUTH_LISTEN", value: "[::]:0"},
		{name: "LISTEN_미지정_mapped", key: "INGEST_AUTH_LISTEN", value: "[::ffff:0.0.0.0]:0"},
		{name: "CALLER_꼴_밖", key: "INGEST_AUTH_CALLER_IP", value: "synthetic-caller-canary-0001", canary: true},
		{name: "CALLER_호스트_이름", key: "INGEST_AUTH_CALLER_IP", value: "mediamtx.test", canary: true},
		{name: "CALLER_미지정_IPv4", key: "INGEST_AUTH_CALLER_IP", value: "0.0.0.0"},
		{name: "CALLER_미지정_IPv6", key: "INGEST_AUTH_CALLER_IP", value: "::"},
		{name: "CALLER_미지정_mapped", key: "INGEST_AUTH_CALLER_IP", value: "::ffff:0.0.0.0"},
		{name: "MTX_URL_https", key: "MTX_API_URL", value: "https://192.0.2.10:9997", canary: true},
		{name: "MTX_URL_호스트_이름", key: "MTX_API_URL", value: "http://mediamtx.test:9997", canary: true},
		{name: "MTX_URL_사용자_정보", key: "MTX_API_URL", value: "http://synthetic-userinfo-canary@192.0.2.10:9997", canary: true},
		{name: "MTX_URL_꼴_밖", key: "MTX_API_URL", value: "synthetic-mtx-url-canary-0001", canary: true},
		{name: "MTX_USER_쌍점", key: "MTX_API_USER", value: "synthetic:user-canary", canary: true},
		{name: "RESOLVE_URL_꼴_밖", key: "STREAM_KEY_RESOLVE_URL", value: "synthetic-resolve-url-canary-0001", canary: true},
		{name: "LOG_LEVEL_꼴_밖", key: "LOG_LEVEL", value: "synthetic-level-canary-0001", canary: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			env := configEnv()
			env[tc.key] = tc.value
			var extra []string
			if tc.canary {
				extra = append(extra, tc.value)
			}
			runConfigFailure(t, nil, env, tc.key, extra...)
		})
	}
}

// cut 이 쓰는 변수는 Control API 셋이다. 설정 오류는 2 이고 출력 줄도 Control API 요청도 없다.
func TestCutRequiresControlVariables(t *testing.T) {
	for _, tc := range []struct {
		name  string
		key   string
		value string // 빈 값이면 변수를 지운다
	}{
		{name: "MTX_API_URL_없음", key: "MTX_API_URL"},
		{name: "MTX_API_USER_없음", key: "MTX_API_USER"},
		{name: "MTX_API_PASS_없음", key: "MTX_API_PASS"},
		{name: "MTX_API_URL_https", key: "MTX_API_URL", value: "https://192.0.2.10:9997"},
		{name: "MTX_API_URL_꼴_밖", key: "MTX_API_URL", value: "synthetic-mtx-url-canary-0002"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			env := configEnv()
			if tc.value == "" {
				delete(env, tc.key)
			} else {
				env[tc.key] = tc.value
			}
			stdout := newOutputLog()
			stderr := newOutputLog()
			keepDefaultLogger(t)
			code := run(t.Context(), newTestSite(t, testToken+"\n"), []string{"cut", testToken}, getenvFrom(env), stdout, stderr)
			if code != 2 {
				t.Errorf("run(cut) without a usable %s = %d, want 2", tc.key, code)
			}
			if !strings.Contains(stderr.String(), tc.key) {
				t.Errorf("run(cut) stderr does not name %s: %q", tc.key, stderr.String())
			}
			if stdout.String() != "" {
				t.Errorf("run(cut) wrote to stdout despite the configuration error: %q", stdout.String())
			}
			checkNoSecrets(t, []string{stdout.String(), stderr.String()}, tc.value)
		})
	}
}
