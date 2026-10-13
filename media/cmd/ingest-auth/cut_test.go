package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/ingestauth"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxctl"
)

// cut 하위 명령의 시험이다(설계 2-6 · 3-4 · 6절). 출력은 stdout 한 줄(slog JSON)이고, 종료 코드는 0(끝까지 끊음) ·
// 1(남음 · 실패 — reason) · 2(사용법 · 설정 · 거부 목록에 없음)다. 가짜 MediaMTX 를 실제 mtxctl.Client 로 부른다.

// runCut 은 s · env 로 cut 하위 명령을 돌린다. 종료 코드 · stdout · stderr 를 돌려준다. cut 자신의 시한을 빼는 변이도
// 시험을 멈추지 않게 바깥에 15초 상한을 둔다.
func runCut(t *testing.T, s site, env map[string]string, token string) (code int, stdout, stderr string) {
	t.Helper()
	ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
	defer cancel()
	out, errOut := newOutputLog(), newOutputLog()
	code = run(ctx, s, []string{"cut", token}, getenvFrom(env), out, errOut)
	return code, out.String(), errOut.String()
}

// cutLine 은 cut 의 stdout 이 꼭 한 줄인지 보고 그 줄을 해석한다(설계 2-6 — 출력은 한 줄).
func cutLine(t *testing.T, stdout string) map[string]any {
	t.Helper()
	lines := strings.Split(strings.TrimSuffix(stdout, "\n"), "\n")
	if stdout == "" || len(lines) != 1 {
		t.Fatalf("cut wrote %d stdout lines, want exactly 1: %q", len(lines), stdout)
	}
	var line map[string]any
	if err := json.Unmarshal([]byte(lines[0]), &line); err != nil {
		t.Fatalf("cut's stdout line is not JSON: %v: %q", err, lines[0])
	}
	return line
}

// checkLine 은 line 의 필드가 want 와 같은지 본다. JSON 의 수는 float64 다.
func checkLine(t *testing.T, line, want map[string]any) {
	t.Helper()
	for name, w := range want {
		if got := line[name]; got != w {
			t.Errorf("cut line %s = %v, want %v (line %v)", name, got, w, line)
		}
	}
}

// cutFixture 는 cut 시험의 기본 재료다 — 거부 목록에 testToken, 기록에 (testToken, 1번 연결), 가짜 MediaMTX 에 그 연결이
// testToken 경로의 송출로 있다.
func cutFixture(t *testing.T) (site, *fakeMediaMTX, map[string]string) {
	t.Helper()
	m := newFakeMediaMTX(t)
	s := newTestSite(t, testToken+"\n")
	allow(t, openConnLog(t, s.connLogDir, connLogMaxBytes, nil), testToken, testConnID(1))
	m.add(testConnID(1), fakeConn{created: time.Now(), state: "publish", path: testToken})
	return s, m, cutEnv(m.srv.URL)
}

func TestCutCompletes(t *testing.T) {
	s, m, env := cutFixture(t)
	allow(t, openConnLog(t, s.connLogDir, connLogMaxBytes, nil), testOtherToken, testConnID(2))
	m.add(testConnID(2), fakeConn{created: time.Now(), state: "publish", path: testOtherToken})
	code, stdout, stderr := runCut(t, s, env, testToken)
	if code != 0 {
		t.Errorf("cut = %d, want 0; stderr: %s", code, stderr)
	}
	line := cutLine(t, stdout)
	checkLine(t, line, map[string]any{
		"msg": "ingestauth_cut_completed", "level": "WARN", "path": testToken,
		"from_log": 1.0, "from_list": 0.0, "from_unmapped": 0.0, "unmapped_young": 0.0, "kicked": 1.0, "already_gone": 0.0,
	})
	if covered, ok := line["covered_s"].(float64); !ok || covered < 0 {
		t.Errorf("cut line covered_s = %v, want a non-negative number of seconds", line["covered_s"])
	}
	for _, field := range []string{"reason", "remaining"} {
		if _, ok := line[field]; ok {
			t.Errorf("cut_completed carries %s, want it only on cut_incomplete", field)
		}
	}
	if got, want := m.kicked(), []string{testConnID(1)}; !slices.Equal(got, want) {
		t.Errorf("kicked %v, want %v", got, want)
	}
	if got, want := m.live(), []string{testConnID(2)}; !slices.Equal(got, want) {
		t.Errorf("connections left %v, want %v (the other token's)", got, want)
	}
	if stderr != "" {
		t.Errorf("cut wrote to stderr: %q, want nothing", stderr)
	}
}

func TestCutNotDenied(t *testing.T) {
	s, m, env := cutFixture(t)
	writeFile(t, s.denyList, testOtherToken+"\n")
	code, stdout, _ := runCut(t, s, env, testToken)
	if code != 2 {
		t.Errorf("cut of a token not in the deny list = %d, want 2", code)
	}
	checkLine(t, cutLine(t, stdout), map[string]any{
		"msg": "ingestauth_cut_incomplete", "level": "ERROR", "path": testToken, "reason": "not_denied",
		"remaining": 0.0, "kicked": 0.0,
	})
	if n := m.requestCount(); n != 0 {
		t.Errorf("cut of a token not in the deny list sent %d Control API requests, want 0", n)
	}
}

// cut 은 run 의 변수 가운데 Control API 셋만 쓴다 — 응답기의 변수가 비어도 돈다(응답기가 죽어 있어도 절단은
// 시도한다 — 그때 api 401 이 사유로 나온다).
func TestCutIgnoresResponderVariables(t *testing.T) {
	s, m, _ := cutFixture(t)
	env := map[string]string{"MTX_API_URL": m.srv.URL, "MTX_API_USER": testAPIUser, "MTX_API_PASS": testAPIPass}
	code, stdout, stderr := runCut(t, s, env, testToken)
	if code != 0 {
		t.Errorf("cut with only the Control API variables = %d, want 0; stderr: %s", code, stderr)
	}
	checkLine(t, cutLine(t, stdout), map[string]any{"msg": "ingestauth_cut_completed", "kicked": 1.0})
}

// cut 의 출력 줄은 LOG_LEVEL 과 무관하다 — 감사 기록이자 결과다.
func TestCutLineIgnoresLogLevel(t *testing.T) {
	s, _, env := cutFixture(t)
	env["LOG_LEVEL"] = "error"
	code, stdout, stderr := runCut(t, s, env, testToken)
	if code != 0 {
		t.Errorf("cut = %d, want 0; stderr: %s", code, stderr)
	}
	checkLine(t, cutLine(t, stdout), map[string]any{"msg": "ingestauth_cut_completed", "level": "WARN"})
}

// 끝까지 끊지 못하면 종료 1 과 그 까닭이다(설계 3-4 3 · 6절). 시한 둘은 Cut 이 내는 두 꼴 — 목록 · kick 단계의
// *mtxctl.Error{Kind: timeout} 과 확인 단계의 ctx 에러 — 을 일으킨다.
func TestCutReportsWhyItIsIncomplete(t *testing.T) {
	for _, tc := range []struct {
		name   string
		setup  func(t *testing.T, s *site, m *fakeMediaMTX, env map[string]string)
		reason string
		fields map[string]any // 그 밖에 맞아야 하는 필드
	}{
		{
			name: "거부_목록을_못_읽음",
			setup: func(t *testing.T, s *site, _ *fakeMediaMTX, _ map[string]string) {
				skipIfRoot(t)
				setMode(t, s.denyList, 0o000, 0o644)
			},
			reason: "denylist_unreadable",
			fields: map[string]any{"kicked": 0.0},
		},
		{
			name:   "기록에_줄이_없음",
			setup:  func(t *testing.T, s *site, _ *fakeMediaMTX, _ map[string]string) { s.connLogDir = t.TempDir() },
			reason: "connlog_unreadable",
			fields: map[string]any{"from_list": 1.0, "kicked": 1.0}, // 목록 갈래는 끝까지 끊었다
		},
		{
			name: "기록이_담기_전_30초_안의_판독_중_연결",
			setup: func(t *testing.T, _ *site, m *fakeMediaMTX, _ map[string]string) {
				m.add(testConnID(9), fakeConn{created: time.Now().Add(-5 * time.Second), state: "idle"})
			},
			reason: "connlog_window_short",
			fields: map[string]any{"unmapped_young": 1.0, "kicked": 1.0},
		},
		{
			name: "api_401",
			setup: func(_ *testing.T, _ *site, m *fakeMediaMTX, _ map[string]string) {
				m.set(func(m *fakeMediaMTX) { m.status["list"] = 401 })
			},
			reason: "api_auth",
		},
		{
			name: "api_500",
			setup: func(_ *testing.T, _ *site, m *fakeMediaMTX, _ map[string]string) {
				m.set(func(m *fakeMediaMTX) { m.status["list"] = 500 })
			},
			reason: "api_unreachable",
		},
		{
			name: "kick_500_뒤_조회_401", // 앞의 500 이 뒤의 401(응답기 부재)을 가리지 않는다
			setup: func(_ *testing.T, _ *site, m *fakeMediaMTX, _ map[string]string) {
				m.set(func(m *fakeMediaMTX) { m.status["kick"], m.status["get"] = 500, 401 })
			},
			reason: "api_auth",
			fields: map[string]any{"kicked": 0.0, "remaining": 1.0},
		},
		{
			name: "api_연결_거부",
			setup: func(t *testing.T, _ *site, _ *fakeMediaMTX, env map[string]string) {
				ln := listenLoopback(t)
				env["MTX_API_URL"] = "http://" + ln.Addr().String()
				ln.Close()
			},
			reason: "api_unreachable",
		},
		{
			name: "목록_경로_404",
			setup: func(_ *testing.T, _ *site, m *fakeMediaMTX, _ map[string]string) {
				m.set(func(m *fakeMediaMTX) { m.status["list"] = 404 })
			},
			reason: "api_incompatible",
		},
		{
			name: "kick_뒤에도_남음",
			setup: func(_ *testing.T, _ *site, m *fakeMediaMTX, _ map[string]string) {
				m.set(func(m *fakeMediaMTX) { m.sticky = true })
			},
			reason: "verify_timeout",
			fields: map[string]any{"remaining": 1.0, "kicked": 1.0},
		},
		{
			name: "cut_시한_목록이_멈춤",
			setup: func(_ *testing.T, s *site, m *fakeMediaMTX, _ map[string]string) {
				s.cutTimeout = 300 * time.Millisecond
				m.set(func(m *fakeMediaMTX) { m.hangList = true })
			},
			reason: "api_unreachable",
		},
		{
			name: "cut_시한_확인_중",
			setup: func(_ *testing.T, s *site, m *fakeMediaMTX, _ map[string]string) {
				s.cutTimeout = 500 * time.Millisecond
				m.set(func(m *fakeMediaMTX) { m.sticky = true })
			},
			reason: "api_unreachable",
			fields: map[string]any{"remaining": 1.0, "kicked": 1.0},
		},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s, m, env := cutFixture(t)
			tc.setup(t, &s, m, env)
			start := time.Now()
			code, stdout, stderr := runCut(t, s, env, testToken)
			// 가장 긴 행(확인 시한 2초)도 3초 안에 끝난다. 5초를 넘기면 cut 이 자기 시한으로 끝나지 않은 것이다.
			if elapsed := time.Since(start); elapsed > 5*time.Second {
				t.Errorf("cut returned after %v, want within 5s (its own deadline)", elapsed)
			}
			if code != 1 {
				t.Errorf("cut = %d, want 1; stderr: %s", code, stderr)
			}
			line := cutLine(t, stdout)
			checkLine(t, line, map[string]any{"msg": "ingestauth_cut_incomplete", "level": "ERROR", "path": testToken, "reason": tc.reason})
			checkLine(t, line, tc.fields)
			if stderr == "" {
				t.Errorf("cut wrote no reason to stderr")
			}
			checkNoSecrets(t, []string{stdout, stderr})
		})
	}
}

// cutOutcome 의 표다 — 종료 0 은 에러가 없고 보고도 끝났을 때뿐이고(설계 2-6), 까닭이 여럿이면 먼저 풀어야 하는 것을
// 고른다(설계 3-4 3 의 운영 순서).
func TestCutOutcome(t *testing.T) {
	connLogErr := fmt.Errorf("%w: synthetic", ingestauth.ErrConnLogUnreadable)
	unknown := errors.New("synthetic unknown failure")
	apiErr := func(op, kind string, status int) error { return &mtxctl.Error{Op: op, Kind: kind, Status: status} }
	for _, tc := range []struct {
		name       string
		report     ingestauth.CutReport
		err        error
		wantCode   int
		wantReason string
	}{
		{name: "끝까지_끊음", report: ingestauth.CutReport{FromLog: 1, Kicked: 1}, wantCode: 0},
		{name: "에러_없는데_남음", report: ingestauth.CutReport{Remaining: []string{testConnID(1)}}, wantCode: 1, wantReason: "internal_error"},
		{name: "에러_없는데_기록_밖_미완", report: ingestauth.CutReport{UnmappedYoung: 1}, wantCode: 1, wantReason: "internal_error"},
		{name: "거부_목록에_없음", err: ingestauth.ErrNotDenied, wantCode: 2, wantReason: "not_denied"},
		{name: "거부_목록을_못_읽음", err: fmt.Errorf("%w: synthetic", ingestauth.ErrDenyListUnreadable), wantCode: 1, wantReason: "denylist_unreadable"},
		{name: "목록_401", err: errors.Join(apiErr("list", "status", 401)), wantCode: 1, wantReason: "api_auth"},
		{name: "kick_401_과_확인_시한", err: errors.Join(apiErr("kick", "status", 401), ingestauth.ErrVerifyTimeout), wantCode: 1, wantReason: "api_auth"},
		{name: "kick_500", err: errors.Join(apiErr("kick", "status", 500)), wantCode: 1, wantReason: "api_unreachable"},
		{name: "500_뒤의_401_은_Cut_이_버림", err: errors.Join(apiErr("kick", "status", 500), ingestauth.ErrVerifyTimeout), wantCode: 1, wantReason: "api_unreachable"},
		{name: "전송_실패", err: errors.Join(apiErr("kick", "unreachable", 0)), wantCode: 1, wantReason: "api_unreachable"},
		{name: "목록_시한_mtxctl_꼴", err: errors.Join(apiErr("list", "timeout", 0)), wantCode: 1, wantReason: "api_unreachable"},
		{name: "확인_시한_ctx_꼴", err: errors.Join(context.DeadlineExceeded), wantCode: 1, wantReason: "api_unreachable"},
		{name: "취소_ctx_꼴", err: errors.Join(context.Canceled), wantCode: 1, wantReason: "api_unreachable"},
		{name: "시한과_기록", err: errors.Join(context.DeadlineExceeded, connLogErr), wantCode: 1, wantReason: "api_unreachable"},
		{name: "목록_404", err: errors.Join(apiErr("list", "status", 404)), wantCode: 1, wantReason: "api_incompatible"},
		{name: "조회_404_는_꼴_불일치가_아님", err: errors.Join(apiErr("get", "status", 404)), wantCode: 1, wantReason: "api_unreachable"},
		{name: "목록_해석_실패", err: errors.Join(apiErr("list", "parse", 0)), wantCode: 1, wantReason: "api_incompatible"},
		{name: "목록_여러_페이지", err: errors.Join(apiErr("list", "pages", 0)), wantCode: 1, wantReason: "api_incompatible"},
		{name: "모르는_꼴", err: errors.Join(unknown), wantCode: 1, wantReason: "internal_error"},
		{name: "모르는_꼴과_기록", err: errors.Join(unknown, connLogErr), wantCode: 1, wantReason: "internal_error"},
		{name: "기록_창_확인", err: errors.Join(connLogErr, ingestauth.ErrConnLogWindowShort, ingestauth.ErrVerifyTimeout), wantCode: 1, wantReason: "connlog_unreadable"},
		{name: "창_확인", err: errors.Join(ingestauth.ErrConnLogWindowShort, ingestauth.ErrVerifyTimeout), wantCode: 1, wantReason: "connlog_window_short"},
		{name: "확인_시한", err: errors.Join(ingestauth.ErrVerifyTimeout), wantCode: 1, wantReason: "verify_timeout"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			code, reason := cutOutcome(tc.report, tc.err)
			if code != tc.wantCode || reason != tc.wantReason {
				t.Errorf("cutOutcome(%+v, %v) = %d, %q, want %d, %q", tc.report, tc.err, code, reason, tc.wantCode, tc.wantReason)
			}
		})
	}
}
