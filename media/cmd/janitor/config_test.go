package main

import (
	"strings"
	"testing"
	"time"
)

// envOf 는 필수 값을 채운 환경에 over 를 덮은 getenv 다.
func envOf(over map[string]string) func(string) string {
	env := map[string]string{"POSTGRES_USER": "u", "POSTGRES_PASSWORD": "p@ss word", "POSTGRES_DB": "d", "JANITOR_ENABLED": "true"}
	for k, v := range over {
		env[k] = v
	}
	return func(k string) string { return env[k] }
}

func TestLoadConfigDefaults(t *testing.T) {
	cfg, err := loadConfig(envOf(nil))
	if err != nil {
		t.Fatal(err)
	}
	want := config{
		enabled: true, segmentRoot: "/recordings", fsOpTimeout: 5 * time.Second, interval: 30 * time.Second,
		grace: 8 * time.Minute, reconnectWindow: 300 * time.Second, recordDeleteAfter: 24 * time.Hour,
		batch: 200, maxPerCycle: 2000,
	}
	cfg.pgDSN, cfg.logLevel = "", 0
	if cfg != want {
		t.Errorf("loadConfig = %+v, want %+v", cfg, want)
	}
}

// 비밀번호는 URL 인코딩해 DSN 에 싣는다 — 특수문자가 든 비밀번호도 깨지지 않는다.
func TestLoadConfigBuildsDSN(t *testing.T) {
	cfg, err := loadConfig(envOf(map[string]string{"POSTGRES_HOST": "db", "POSTGRES_PORT": "6543", "POSTGRES_SSLMODE": "require"}))
	if err != nil {
		t.Fatal(err)
	}
	if want := "postgres://u:p%40ss%20word@db:6543/d?sslmode=require"; cfg.pgDSN != want {
		t.Errorf("pgDSN = %q, want %q", cfg.pgDSN, want)
	}
}

// 정책 값 — ExternalDeleteAge 는 RECORD_DELETE_AFTER − 1h(여유)다.
func TestConfigPolicyDerivesExternalDeleteAge(t *testing.T) {
	cfg, err := loadConfig(envOf(map[string]string{"JANITOR_RECORD_DELETE_AFTER": "2h"}))
	if err != nil {
		t.Fatal(err)
	}
	p := cfg.policy()
	if p.ExternalDeleteAge != time.Hour || p.Grace != 8*time.Minute || p.ReconnectWindow != 300*time.Second {
		t.Errorf("policy = %+v", p)
	}
}

// D31 · K6 — JANITOR_ENABLED 는 strconv.ParseBool: 참 값이면 켬, 거짓 값 · 빈 값이면 끔, 그 밖은 거부.
func TestLoadConfigEnabledSwitch(t *testing.T) {
	for _, v := range []string{"1", "t", "T", "TRUE", "true", "True"} {
		if cfg, err := loadConfig(envOf(map[string]string{"JANITOR_ENABLED": v})); err != nil || !cfg.enabled {
			t.Errorf("JANITOR_ENABLED=%q → enabled=%v err=%v, want 켬", v, cfg.enabled, err)
		}
	}
	for _, v := range []string{"", "0", "f", "F", "FALSE", "false", "False"} {
		if cfg, err := loadConfig(envOf(map[string]string{"JANITOR_ENABLED": v})); err != nil || cfg.enabled {
			t.Errorf("JANITOR_ENABLED=%q → enabled=%v err=%v, want 끔(변이 — 빈 값을 켬으로)", v, cfg.enabled, err)
		}
	}
	for _, v := range []string{"yes", "on", "2"} {
		if _, err := loadConfig(envOf(map[string]string{"JANITOR_ENABLED": v})); err == nil {
			t.Errorf("JANITOR_ENABLED=%q 를 받았다, want 기동 거부", v)
		}
	}
}

// D18 — 설정 오류는 기동 거부다(각 하한 · 상한). 문구에 변수 이름이 있어야 고칠 수 있다.
func TestLoadConfigRejectsUnsafeValues(t *testing.T) {
	cases := map[string]map[string]string{
		"FS_OP_TIMEOUT":               {"FS_OP_TIMEOUT": "0s"},
		"FS_OP_TIMEOUT 음수":            {"FS_OP_TIMEOUT": "-1s"},
		"FS_OP_TIMEOUT > 6s":          {"FS_OP_TIMEOUT": "6001ms"},
		"JANITOR_GRACE < 8m":          {"JANITOR_GRACE": "7m59s"},
		"JANITOR_RECONNECT_WINDOW":    {"JANITOR_RECONNECT_WINDOW": "299s"},
		"JANITOR_RECORD_DELETE_AFTER": {"JANITOR_RECORD_DELETE_AFTER": "1h59m"},
		"JANITOR_INTERVAL":            {"JANITOR_INTERVAL": "0s"},
		"JANITOR_BATCH":               {"JANITOR_BATCH": "0"},
		"JANITOR_BATCH > MAX":         {"JANITOR_BATCH": "2001"},
		"JANITOR_MAX_PER_CYCLE":       {"JANITOR_MAX_PER_CYCLE": "-1"},
		// Q8(t2 r1) — 운영자 실수성 자기 DoS 를 막는 하한 · 상한.
		"FS_OP_TIMEOUT < 100ms":          {"FS_OP_TIMEOUT": "99ms"},
		"JANITOR_INTERVAL < 1s":          {"JANITOR_INTERVAL": "999ms"},
		"JANITOR_BATCH > 10000":          {"JANITOR_BATCH": "10001", "JANITOR_MAX_PER_CYCLE": "20000"},
		"JANITOR_MAX_PER_CYCLE > 100000": {"JANITOR_MAX_PER_CYCLE": "100001"},
		"POSTGRES_USER":                  {"POSTGRES_USER": ""},
		"POSTGRES_PORT":                  {"POSTGRES_PORT": "x"},
		"LOG_LEVEL":                      {"LOG_LEVEL": "loud"},
	}
	for name, over := range cases {
		_, err := loadConfig(envOf(over))
		if err == nil {
			t.Errorf("%s: 거부하지 않았다", name)
			continue
		}
		key := strings.Fields(name)[0]
		if !strings.Contains(err.Error(), key) {
			t.Errorf("%s: 오류 %q 에 변수 이름 %s 가 없다", name, err, key)
		}
		if strings.Contains(err.Error(), "p@ss") {
			t.Errorf("%s: 오류에 비밀번호가 실렸다", name)
		}
	}
}

// 경계 값은 받는다 — 상한 · 하한이 「초과 · 미만」만 거부하는지.
func TestLoadConfigAcceptsBoundaries(t *testing.T) {
	_, err := loadConfig(envOf(map[string]string{
		"FS_OP_TIMEOUT": "6s", "JANITOR_GRACE": "8m", "JANITOR_RECONNECT_WINDOW": "300s",
		"JANITOR_RECORD_DELETE_AFTER": "2h", "JANITOR_BATCH": "2000", "JANITOR_MAX_PER_CYCLE": "2000",
	}))
	if err != nil {
		t.Fatalf("경계 값 거부: %v", err)
	}
	_, err = loadConfig(envOf(map[string]string{
		"FS_OP_TIMEOUT": "100ms", "JANITOR_INTERVAL": "1s", "JANITOR_BATCH": "10000", "JANITOR_MAX_PER_CYCLE": "100000",
	}))
	if err != nil {
		t.Fatalf("경계 값(Q8 하한 · 상한) 거부: %v", err)
	}
}
