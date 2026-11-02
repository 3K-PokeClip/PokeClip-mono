package main

import (
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/ingestauth"
)

// IP 한도 환경 변수의 시험이다(트랙 B 설계 2-1 의 13 · 계획 4-2 의 C1–C4 · A1). 셋 다 선택이고 기본은 켜짐(B = 30 ·
// r = 3초)이다. 닫힌 집합 밖 · 범위 밖은 기동 실패(종료 2)이고 에러에는 변수 이름만 싣는다.

// C1 — 셋 다 비면 켜짐 · 30 · 3초다.
func TestLoadIPLimitDefaults(t *testing.T) {
	got, err := loadIPLimit(getenvFrom(nil))
	if err != nil {
		t.Fatalf("loadIPLimit(empty) returned error: %v", err)
	}
	if want := (ingestauth.IPLimit{Burst: 30, Interval: 3 * time.Second}); got != want {
		t.Errorf("loadIPLimit(empty) = %+v, want %+v", got, want)
	}
}

// C2 — 닫힌 집합 밖 · 범위 밖 · 꼴 밖은 기동 실패다. 오타가 조용히 꺼짐이 되지 않는다(B16).
func TestRunRejectsInvalidIPLimit(t *testing.T) {
	for _, tc := range []struct {
		name, key, value string
	}{
		{name: "LIMIT_대문자", key: "INGEST_AUTH_IP_LIMIT", value: "On"},
		{name: "LIMIT_false", key: "INGEST_AUTH_IP_LIMIT", value: "false"},
		{name: "LIMIT_공백", key: "INGEST_AUTH_IP_LIMIT", value: " on"},
		{name: "LIMIT_카나리아", key: "INGEST_AUTH_IP_LIMIT", value: "synthetic-iplimit-canary-0001"},
		{name: "BURST_0", key: "INGEST_AUTH_IP_LIMIT_BURST", value: "0"},
		{name: "BURST_1001", key: "INGEST_AUTH_IP_LIMIT_BURST", value: "1001"},
		{name: "BURST_음수", key: "INGEST_AUTH_IP_LIMIT_BURST", value: "-1"},
		{name: "BURST_카나리아", key: "INGEST_AUTH_IP_LIMIT_BURST", value: "synthetic-burst-canary-0001"},
		{name: "INTERVAL_99ms", key: "INGEST_AUTH_IP_LIMIT_INTERVAL", value: "99ms"},
		{name: "INTERVAL_10m1ns", key: "INGEST_AUTH_IP_LIMIT_INTERVAL", value: "10m0.000000001s"},
		{name: "INTERVAL_단위_없음", key: "INGEST_AUTH_IP_LIMIT_INTERVAL", value: "3"},
		{name: "INTERVAL_카나리아", key: "INGEST_AUTH_IP_LIMIT_INTERVAL", value: "synthetic-interval-canary-0001"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			env := configEnv()
			env[tc.key] = tc.value
			// 짧은 값(0 · 3 · on)은 에러 문구의 범위 · 집합 표기에 글자로 들어 있다 — 카나리아 값만 누설을 본다.
			var extra []string
			if strings.Contains(tc.value, "canary") {
				extra = append(extra, tc.value)
			}
			runConfigFailure(t, nil, env, tc.key, extra...)
		})
	}
}

// C3 — off 면 한도가 없다(영값).
func TestLoadIPLimitOff(t *testing.T) {
	got, err := loadIPLimit(getenvFrom(map[string]string{"INGEST_AUTH_IP_LIMIT": "off"}))
	if err != nil {
		t.Fatalf("loadIPLimit(off) returned error: %v", err)
	}
	if got != (ingestauth.IPLimit{}) {
		t.Errorf("loadIPLimit(off) = %+v, want the zero IPLimit", got)
	}
}

// C4 — 경계값은 받는다.
func TestLoadIPLimitAcceptsBounds(t *testing.T) {
	for _, tc := range []struct {
		burst, interval string
		want            ingestauth.IPLimit
	}{
		{"1", "100ms", ingestauth.IPLimit{Burst: 1, Interval: 100 * time.Millisecond}},
		{"1000", "10m", ingestauth.IPLimit{Burst: 1000, Interval: 10 * time.Minute}},
		{"30", "3s", ingestauth.IPLimit{Burst: 30, Interval: 3 * time.Second}},
	} {
		env := map[string]string{"INGEST_AUTH_IP_LIMIT": "on", "INGEST_AUTH_IP_LIMIT_BURST": tc.burst, "INGEST_AUTH_IP_LIMIT_INTERVAL": tc.interval}
		got, err := loadIPLimit(getenvFrom(env))
		if err != nil || got != tc.want {
			t.Errorf("loadIPLimit(burst=%s, interval=%s) = %+v, %v, want %+v", tc.burst, tc.interval, got, err, tc.want)
		}
	}
}

// publishCodes 는 run 에 publish 를 n 번 보내 200 · 401 수를 센다.
func publishCodes(t *testing.T, addr string, n int) (ok, denied int) {
	t.Helper()
	for i := range n {
		switch status, _ := mustPostAuth(t, addr, publishBody(testToken, testConnID(100+i)), nil); status {
		case http.StatusOK:
			ok++
		case http.StatusUnauthorized:
			denied++
		default:
			t.Fatalf("POST publish %d = %d, want 200 or 401", i+1, status)
		}
	}
	return ok, denied
}

// A1 — 기본 환경으로 띄우면 한도가 켜져 있다. 기동 로그에 적용값이 실리고, 한 송출자 IP 의 B+1 번째 시도부터 401 이다
// (B17 — 라이브러리 영값은 꺼짐이므로 cmd 가 넘기지 않으면 한도 없이 배포된다). 실제 시계라 보낸 동안 찬 몫을 더해
// 허용 수의 상한을 잰다.
func TestRunEnablesIPLimitByDefault(t *testing.T) {
	auth := newFakeAuth(t)
	r := startResponder(t, newTestSite(t, ""), responderEnv(auth.srv.URL))
	started := logEvents(r.stdout.String(), "ingestauth_started")[0]
	for name, want := range map[string]any{"ip_limit": "on", "ip_limit_burst": float64(30), "ip_limit_interval_ms": float64(3000)} {
		if got := started[name]; got != want {
			t.Errorf("ingestauth_started %s = %s, want %v", name, shown(got), want)
		}
	}

	begin := time.Now()
	ok, denied := publishCodes(t, r.listen, 40)
	elapsed := time.Since(begin)
	if limit := 30 + int(elapsed/(3*time.Second)); ok > limit || denied == 0 {
		t.Errorf("40 publishes from one IP in %v = %d ok, %d denied, want ≤ %d ok and some denied", elapsed, ok, denied, limit)
	}
	if got := len(logEvents(r.stdout.String(), "ingestauth_ip_limited")); got != 1 {
		t.Errorf("ingestauth_ip_limited lines = %d, want 1", got)
	}
	checkNoSecrets(t, []string{r.stdout.String(), r.stderr.String()})
}

// C3 의 조립 행 — off 로 띄우면 기동 로그가 off 이고 한 IP 의 시도가 모두 허용된다.
func TestRunHonorsIPLimitOff(t *testing.T) {
	auth := newFakeAuth(t)
	env := responderEnv(auth.srv.URL)
	env["INGEST_AUTH_IP_LIMIT"] = "off"
	r := startResponder(t, newTestSite(t, ""), env)
	if got := logEvents(r.stdout.String(), "ingestauth_started")[0]["ip_limit"]; got != "off" {
		t.Errorf("ingestauth_started ip_limit = %s, want off", shown(got))
	}
	if ok, denied := publishCodes(t, r.listen, 40); ok != 40 || denied != 0 {
		t.Errorf("40 publishes with the limit off = %d ok, %d denied, want all ok", ok, denied)
	}
}
