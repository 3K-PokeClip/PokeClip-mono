package main

import (
	"strings"
	"testing"
)

// 하위 명령 분기의 시험이다. 사용법 오류는 종료 코드 2 이고 아무것도 시작하지 않는다(설계 2-6 · 패키지 문서).

func TestRunRejectsUsageErrors(t *testing.T) {
	for _, tc := range []struct {
		name string
		args []string
	}{
		{name: "모르는_하위_명령", args: []string{"serve"}},
		{name: "run_에_인자", args: []string{"run", "extra"}},
		{name: "healthcheck_에_인자", args: []string{"healthcheck", "extra"}},
		{name: "cut_에_토큰_없음", args: []string{"cut"}},
		{name: "cut_에_인자_둘", args: []string{"cut", testToken, testOtherToken}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			keepDefaultLogger(t)
			stdout, stderr := newOutputLog(), newOutputLog()
			code := run(t.Context(), newTestSite(t, ""), tc.args, getenvFrom(configEnv()), stdout, stderr)
			if code != 2 {
				t.Errorf("run(%q) = %d, want 2 (usage error)", tc.args, code)
			}
			if stderr.String() == "" {
				t.Errorf("run(%q) wrote no usage to stderr", tc.args)
			}
			if stdout.String() != "" {
				t.Errorf("run(%q) wrote to stdout (%s), want nothing", tc.args, outputSummary(stdout.String()))
			}
		})
	}
}

// 토큰 꼴이 아닌 cut 인자는 토큰 자리에 잘못 붙인 비밀일 수 있다 — 어디에도 싣지 않고 2 로 끝난다.
func TestCutRejectsNonTokenArgumentWithoutEchoingIt(t *testing.T) {
	for _, tc := range []struct {
		name string
		arg  string
	}{
		{name: "비밀을_잘못_붙임", arg: "synthetic-cut-arg-canary-0001"},
		{name: "소문자_토큰", arg: strings.ToLower(testToken)},
		{name: "25자", arg: testToken[:25]},
	} {
		t.Run(tc.name, func(t *testing.T) {
			keepDefaultLogger(t)
			m := newFakeMediaMTX(t)
			stdout, stderr := newOutputLog(), newOutputLog()
			code := run(t.Context(), newTestSite(t, tc.arg+"\n"), []string{"cut", tc.arg}, getenvFrom(cutEnv(m.srv.URL)), stdout, stderr)
			if code != 2 {
				t.Errorf("run(cut <%s>) = %d, want 2", tc.name, code)
			}
			if strings.Contains(stdout.String(), tc.arg) || strings.Contains(stderr.String(), tc.arg) {
				t.Errorf("run(cut <%s>) echoed its argument", tc.name)
			}
			if n := m.requestCount(); n != 0 {
				t.Errorf("run(cut <%s>) sent %d Control API requests, want 0", tc.name, n)
			}
		})
	}
}
