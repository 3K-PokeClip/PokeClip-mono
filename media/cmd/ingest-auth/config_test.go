package main

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/keyresolve"
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
	"INGEST_AUTH_BASIC_USER",
	"INGEST_AUTH_BASIC_PASS",
}

// runConfigFailure 는 env 로 run 을 돌려 설정 오류로 끝나는지 본다 — 종료 2 · stderr 에 name · 기동 로그 없음 ·
// 비밀 카나리아와 extra 없음. 검사를 빠뜨린 변이가 리스너를 열어도 시험이 멈추지 않게 run 의 ctx 에 5초 상한을 둔다.
// stderr 를 돌려준다 — 원인 문구를 더 보는 시험이 쓴다.
func runConfigFailure(t *testing.T, args []string, env map[string]string, name string, extra ...string) string {
	t.Helper()
	keepDefaultLogger(t)
	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()
	stdout, stderr := newOutputLog(), newOutputLog()
	code := run(ctx, newTestSite(t, ""), args, getenvFrom(env), stdout, stderr)
	if code != 2 {
		t.Errorf("run(%q) = %d, want 2 (configuration error; stderr: %s)", args, code, outputSummary(stderr.String()))
	}
	if !strings.Contains(stderr.String(), name) {
		t.Errorf("run(%q) stderr does not name %s (stderr: %s)", args, name, outputSummary(stderr.String()))
	}
	if events := logEvents(stdout.String(), "ingestauth_started"); len(events) != 0 {
		t.Errorf("run(%q) logged ingestauth_started despite the configuration error", args)
	}
	checkNoSecrets(t, []string{stdout.String(), stderr.String()}, extra...)
	return stderr.String()
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
		// zone 붙은 주소 — 처리기는 zone 붙은 주소를 zone 없는 주소와 다르게 본다. 묶을 수 없는 주소라 검사를 빠뜨린
		// 변이도 곧바로 끝난다.
		{name: "LISTEN_zone_mapped", key: "INGEST_AUTH_LISTEN", value: "[::ffff:192.0.2.20%eth0]:0", canary: true},
		{name: "LISTEN_zone_링크_로컬", key: "INGEST_AUTH_LISTEN", value: "[fe80::1%eth0]:0", canary: true},
		{name: "CALLER_꼴_밖", key: "INGEST_AUTH_CALLER_IP", value: "synthetic-caller-canary-0001", canary: true},
		{name: "CALLER_호스트_이름", key: "INGEST_AUTH_CALLER_IP", value: "mediamtx.test", canary: true},
		{name: "CALLER_미지정_IPv4", key: "INGEST_AUTH_CALLER_IP", value: "0.0.0.0"},
		{name: "CALLER_미지정_IPv6", key: "INGEST_AUTH_CALLER_IP", value: "::"},
		{name: "CALLER_미지정_mapped", key: "INGEST_AUTH_CALLER_IP", value: "::ffff:0.0.0.0"},
		{name: "CALLER_zone_mapped", key: "INGEST_AUTH_CALLER_IP", value: "::ffff:192.0.2.10%eth0", canary: true},
		{name: "CALLER_zone_링크_로컬", key: "INGEST_AUTH_CALLER_IP", value: "fe80::1%eth0", canary: true},
		{name: "MTX_URL_https", key: "MTX_API_URL", value: "https://192.0.2.10:9997", canary: true},
		{name: "MTX_URL_호스트_이름", key: "MTX_API_URL", value: "http://mediamtx.test:9997", canary: true},
		{name: "MTX_URL_사용자_정보", key: "MTX_API_URL", value: "http://synthetic-userinfo-canary@192.0.2.10:9997", canary: true},
		{name: "MTX_URL_꼴_밖", key: "MTX_API_URL", value: "synthetic-mtx-url-canary-0001", canary: true},
		{name: "MTX_USER_쌍점", key: "MTX_API_USER", value: "synthetic:user-canary", canary: true},
		{name: "RESOLVE_URL_꼴_밖", key: "STREAM_KEY_RESOLVE_URL", value: "synthetic-resolve-url-canary-0001", canary: true},
		{name: "RESOLVE_URL_사용자_정보", key: "STREAM_KEY_RESOLVE_URL", value: "http://synthetic-userinfo-canary-0002@192.0.2.30:8082", canary: true},
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

// 기동 로그에 싣는 4C 끝점은 호스트의 부류와 포트뿐이다(설계 6절 — 주소는 싣지 않는다). 부류는 설계 2-6 의 「공인 주소면
// 403」을 가르는 데 쓴다. 포트가 없으면 전송이 다이얼하는 스킴의 기본 포트다. 사설 주소는 합성 값이다(저장소 선례).
func TestNewResolverReportsHostKindAndPort(t *testing.T) {
	for _, tc := range []struct {
		name string
		url  string
		want authTarget
	}{
		{name: "사설_IPv4", url: "http://192.168.0.1:8082", want: authTarget{hostKind: "private", port: "8082"}},
		{name: "사설_IPv6_ULA", url: "http://[fd00::1]:8082", want: authTarget{hostKind: "private", port: "8082"}},
		{name: "사설_IPv4-mapped", url: "http://[::ffff:192.168.0.1]:8082", want: authTarget{hostKind: "private", port: "8082"}},
		{name: "공인_IPv4", url: "http://198.51.100.20:8082", want: authTarget{hostKind: "public", port: "8082"}},
		{name: "공인_IPv6", url: "http://[2001:db8::20]:8082", want: authTarget{hostKind: "public", port: "8082"}},
		{name: "이름", url: "http://auth.test:8082", want: authTarget{hostKind: "name", port: "8082"}},
		{name: "루프백", url: "http://127.0.0.1:8082", want: authTarget{hostKind: "other", port: "8082"}},
		{name: "링크_로컬_zone", url: "http://[fe80::1%25eth0]:8082", want: authTarget{hostKind: "other", port: "8082"}},
		{name: "포트_없음_http", url: "http://192.168.0.1", want: authTarget{hostKind: "private", port: "80"}},
		{name: "포트_없음_https", url: "https://auth.test", want: authTarget{hostKind: "name", port: "443"}},
		{name: "경로", url: "http://198.51.100.20:8082/base", want: authTarget{hostKind: "public", port: "8082"}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			env := configEnv()
			env["STREAM_KEY_RESOLVE_URL"] = tc.url
			_, got, err := newResolver(getenvFrom(env), newSecret(testResolveToken))
			if err != nil {
				t.Fatalf("newResolver(%s) returned error: %v", tc.name, err)
			}
			if got != tc.want {
				t.Errorf("newResolver(%s) target = %+v, want %+v", tc.name, got, tc.want)
			}
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
				t.Errorf("run(cut) stderr does not name %s (stderr: %s)", tc.key, outputSummary(stderr.String()))
			}
			if stdout.String() != "" {
				t.Errorf("run(cut) wrote to stdout despite the configuration error (stdout: %s)", outputSummary(stdout.String()))
			}
			checkNoSecrets(t, []string{stdout.String(), stderr.String()}, tc.value)
		})
	}
}

// 호출자 Basic 은 자격(INGEST_AUTH_BASIC_USER · INGEST_AUTH_BASIC_PASS) 둘 다이거나 명시 끔(INGEST_AUTH_CALLER_BASIC=off)
// 하나여야 기동한다 — 설정이 빠지면 조용히 출발지 검사만 남지 않게 기동을 거부한다. 끔 값은 off 만 받는다(오타가 조용히
// 켜짐이나 꺼짐이 되지 않게).
func TestRunRequiresCallerBasicOrExplicitOff(t *testing.T) {
	for _, tc := range []struct {
		name  string
		edit  func(env map[string]string)
		wantN string // stderr 에 있어야 하는 이름
	}{
		{name: "아무것도_없음", edit: func(env map[string]string) {
			delete(env, "INGEST_AUTH_BASIC_USER")
			delete(env, "INGEST_AUTH_BASIC_PASS")
		}, wantN: "INGEST_AUTH_CALLER_BASIC=off"},
		{name: "사용자만", edit: func(env map[string]string) { delete(env, "INGEST_AUTH_BASIC_PASS") }, wantN: "INGEST_AUTH_BASIC_PASS"},
		{name: "비번만", edit: func(env map[string]string) { delete(env, "INGEST_AUTH_BASIC_USER") }, wantN: "INGEST_AUTH_BASIC_USER"},
		{name: "끔과_자격이_함께", edit: func(env map[string]string) { env["INGEST_AUTH_CALLER_BASIC"] = "off" }, wantN: "INGEST_AUTH_CALLER_BASIC"},
		{name: "끔과_사용자가_함께", edit: func(env map[string]string) {
			delete(env, "INGEST_AUTH_BASIC_PASS")
			env["INGEST_AUTH_CALLER_BASIC"] = "off"
		}, wantN: "INGEST_AUTH_CALLER_BASIC"},
		{name: "끔_값_Off", edit: func(env map[string]string) {
			delete(env, "INGEST_AUTH_BASIC_USER")
			delete(env, "INGEST_AUTH_BASIC_PASS")
			env["INGEST_AUTH_CALLER_BASIC"] = "Off"
		}, wantN: "INGEST_AUTH_CALLER_BASIC"},
		{name: "끔_값_false", edit: func(env map[string]string) {
			delete(env, "INGEST_AUTH_BASIC_USER")
			delete(env, "INGEST_AUTH_BASIC_PASS")
			env["INGEST_AUTH_CALLER_BASIC"] = "false"
		}, wantN: "INGEST_AUTH_CALLER_BASIC"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			env := configEnv()
			tc.edit(env)
			runConfigFailure(t, nil, env, tc.wantN)
		})
	}
}

// 호출자 Basic 의 사용자 이름과 비번은 URL 표준(RFC 3986)의 unreserved 문자(A-Z a-z 0-9 - . _ ~)만 받는다 — 둘 다
// authHTTPAddress URL 에 렌더되므로, 그 밖의 문자는 경계로 읽히거나(@ : / ? #) 퍼센트 디코딩되어(%41 → A) MediaMTX 가
// 싣는 값이 응답기의 값과 달라진다 — 그러면 모든 인증이 401 이다. 에러는 변수 이름을 싣고 값은 싣지 않는다.
func TestRunRejectsBadCallerBasicValues(t *testing.T) {
	type row struct{ name, user, pass string }
	var rows []row
	for _, c := range []string{"@", "/", "%", "?", "#", ":", " ", "\x01", "%41"} {
		rows = append(rows, row{name: "사용자_" + fmt.Sprintf("%q", c), user: "synthetic" + c + "user-canary-0006"})
	}
	for _, c := range []string{"@", "%", ":", "/", " "} {
		rows = append(rows, row{name: "비번_" + fmt.Sprintf("%q", c), pass: "synthetic" + c + "caller-canary-0006"})
	}
	for _, r := range rows {
		for _, viaFile := range []bool{false, true} {
			if r.user != "" && viaFile {
				continue // 사용자 이름은 비밀이 아니라 파일 입력이 없다
			}
			name := r.name
			if viaFile {
				name += "_파일"
			}
			t.Run(name, func(t *testing.T) {
				env := configEnv()
				key, value := "INGEST_AUTH_BASIC_USER", r.user
				if r.user == "" {
					key, value = "INGEST_AUTH_BASIC_PASS", r.pass
				}
				if viaFile {
					useSecretFile(t, env, secretDir(t), key, value+"\n")
				} else {
					env[key] = value
				}
				runConfigFailure(t, nil, env, key, value, secretPathCanary)
			})
		}
	}
	t.Run("unreserved_만", func(t *testing.T) {
		auth := newFakeAuth(t)
		env := responderEnv(auth.srv.URL)
		env["INGEST_AUTH_BASIC_USER"] = "Media-mtx_1.0~"
		startResponder(t, newTestSite(t, ""), env)
	})
}

// channelRecorder 는 4C 요청에 실린 출입증 머리의 이름을 적는 가짜 auth 다 — 거절하지 않고 늘 valid:true 로 답한다(창구
// 판별을 Basic · 출입증 값 판별과 섞지 않는다).
type channelRecorder struct {
	srv *httptest.Server

	mu    sync.Mutex
	names [][]string // 요청마다 실린 출입증 머리 이름(전용 · 공용 가운데)
}

func newChannelRecorder(t *testing.T) *channelRecorder {
	t.Helper()
	c := &channelRecorder{}
	c.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var names []string
		for _, h := range []string{keyresolve.Header, keyresolve.InternalHeader} {
			if r.Header.Get(h) == testResolveToken {
				names = append(names, h)
			}
		}
		c.mu.Lock()
		c.names = append(c.names, names)
		c.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"valid":true,"userId":%d,"passphrase":%q}`, testUserID, testPassphrase)
	}))
	t.Cleanup(c.srv.Close)
	return c
}

// seen 은 지금까지 요청마다 실린 출입증 머리 이름이다.
func (c *channelRecorder) seen() [][]string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return slices.Clone(c.names)
}

// STREAM_KEY_RESOLVE_CHANNEL 은 4C 출입증을 실을 창구다 — 비면 전용(resolve-token · X-Stream-Key-Resolve-Token),
// internal-token 이면 공용(X-Internal-Token)이고 머리는 늘 하나다. 기동 로그의 auth_channel 이 고른 창구다. 그 밖의
// 값은 기동 거부다 — 오타가 조용히 기본 창구가 되지 않게.
func TestRunSelectsResolveChannel(t *testing.T) {
	for _, tc := range []struct {
		name, value, wantChannel, wantHeader string
	}{
		{name: "빈_값", wantChannel: "resolve-token", wantHeader: keyresolve.Header},
		{name: "resolve-token", value: "resolve-token", wantChannel: "resolve-token", wantHeader: keyresolve.Header},
		{name: "internal-token", value: "internal-token", wantChannel: "internal-token", wantHeader: keyresolve.InternalHeader},
	} {
		t.Run(tc.name, func(t *testing.T) {
			auth := newChannelRecorder(t)
			env := responderEnv(auth.srv.URL)
			if tc.value != "" {
				env["STREAM_KEY_RESOLVE_CHANNEL"] = tc.value
			}
			r := startResponder(t, newTestSite(t, ""), env)
			if got := logEvents(r.stdout.String(), "ingestauth_started")[0]["auth_channel"]; got != tc.wantChannel {
				t.Errorf("ingestauth_started auth_channel = %s, want %q", shown(got), tc.wantChannel)
			}
			if status, _ := mustPostAuth(t, r.listen, publishBody(testToken, testConnID(1)), nil); status != http.StatusOK {
				t.Errorf("POST publish = %d, want 200", status)
			}
			if got := auth.seen(); len(got) != 1 || !slices.Equal(got[0], []string{tc.wantHeader}) {
				t.Errorf("4C resolve headers = %v, want one request carrying only %s", got, tc.wantHeader)
			}
		})
	}
	for _, typo := range []string{"internal", "Internal-Token", "resolve_token"} {
		t.Run("오타_"+typo, func(t *testing.T) {
			env := configEnv()
			env["STREAM_KEY_RESOLVE_CHANNEL"] = typo
			runConfigFailure(t, nil, env, "STREAM_KEY_RESOLVE_CHANNEL")
		})
	}
}

// 호출자 Basic 비번은 24자 이상이어야 기동한다(kty 120차) — 같은 netns 의 프로세스가 짧은 비번을 맞혀 보지 못하게.
// 값으로 주든 파일로 주든 같다. 에러는 변수 이름과 최소 길이만 싣고 값 · 실제 길이는 싣지 않는다.
func TestRunRejectsShortCallerBasicPass(t *testing.T) {
	const short = "synthetic-short-pass-00" // 23자 — unreserved 문자만
	for _, viaFile := range []bool{false, true} {
		name := "값"
		if viaFile {
			name = "파일"
		}
		t.Run(name, func(t *testing.T) {
			env := configEnv()
			if viaFile {
				useSecretFile(t, env, secretDir(t), "INGEST_AUTH_BASIC_PASS", short+"\n")
			} else {
				env["INGEST_AUTH_BASIC_PASS"] = short
			}
			stderr := runConfigFailure(t, nil, env, "INGEST_AUTH_BASIC_PASS", short, secretPathCanary)
			if !strings.Contains(stderr, "24") {
				t.Errorf("stderr does not carry the minimum length 24 (stderr: %s)", outputSummary(stderr))
			}
			if strings.Contains(stderr, "23") {
				t.Errorf("stderr carries the actual length 23, want only the minimum (stderr: %s)", outputSummary(stderr))
			}
		})
	}
	t.Run("24자", func(t *testing.T) {
		auth := newFakeAuth(t)
		env := responderEnv(auth.srv.URL)
		env["INGEST_AUTH_BASIC_PASS"] = "synthetic-pass-24-chars0"
		startResponder(t, newTestSite(t, ""), env)
	})
}
