package config

import (
	"strings"
	"testing"
	"time"
)

// envOf 는 map 을 Load 가 받는 조회 함수로 바꾼다.
func envOf(m map[string]string) func(string) string {
	return func(k string) string { return m[k] }
}

func minimalEnv() map[string]string {
	return map[string]string{
		"POSTGRES_USER":     "pokeclip",
		"POSTGRES_PASSWORD": "비밀값-예시",
		"POSTGRES_DB":       "pokeclip",
	}
}

func TestLoadAppliesDefaults(t *testing.T) {
	cfg, err := Load(envOf(minimalEnv()))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}

	if cfg.SegmentRoot != "/recordings" {
		t.Errorf("SegmentRoot = %q, want /recordings", cfg.SegmentRoot)
	}
	if cfg.EnsureSchema {
		t.Error("EnsureSchema 기본값은 false 여야 한다 — 프로덕션에서 표를 함부로 만들지 않는다")
	}
	if cfg.Indexer.ExpectedDurationMS != 4000 {
		t.Errorf("ExpectedDurationMS = %d, want 4000", cfg.Indexer.ExpectedDurationMS)
	}
	if cfg.Watcher.IdleTimeout != 10*time.Second {
		t.Errorf("IdleTimeout = %v, want 10s", cfg.Watcher.IdleTimeout)
	}
	if cfg.Watcher.Settle.SettleWait != 2*time.Second {
		t.Errorf("SettleWait = %v, want 2s (recordPartDuration 1s 의 2배)", cfg.Watcher.Settle.SettleWait)
	}
	// 워처와 인덱서의 IdleTimeout 은 같아야 한다. 달라지면 H4 재검이 워처 판정과 어긋난다.
	if cfg.Indexer.IdleTimeout != cfg.Watcher.IdleTimeout {
		t.Errorf("IdleTimeout 이 인덱서(%v)와 워처(%v)에서 다르다",
			cfg.Indexer.IdleTimeout, cfg.Watcher.IdleTimeout)
	}
}

// 필수 설정 누락은 즉시 명시적 에러다. 조용히 기본값으로 때우지 않는다.
func TestLoadRequiresPostgresSettings(t *testing.T) {
	for _, key := range []string{"POSTGRES_USER", "POSTGRES_PASSWORD", "POSTGRES_DB"} {
		t.Run("미설정_"+key, func(t *testing.T) {
			env := minimalEnv()
			delete(env, key)

			_, err := Load(envOf(env))
			if err == nil {
				t.Fatalf("%s 가 없는데 에러가 없다", key)
			}
			if !strings.Contains(err.Error(), key) {
				t.Fatalf("에러 메시지에 %s 가 없다: %v", key, err)
			}
		})

		// 빈 문자열도 누락으로 취급해야 한다.
		// POSTGRES_PASSWORD= 처럼 빈 값을 "설정"하는 것은 설정이 아니다.
		t.Run("빈문자열_"+key, func(t *testing.T) {
			env := minimalEnv()
			env[key] = ""

			if _, err := Load(envOf(env)); err == nil {
				t.Fatalf("%s 가 빈 문자열인데 에러가 없다", key)
			}
		})
	}
}

// DSN 에 비밀값이 들어가므로 에러 메시지나 로그에 그대로 실리면 안 된다.
func TestLoadBuildsDSN(t *testing.T) {
	env := minimalEnv()
	env["POSTGRES_HOST"] = "db.internal"
	env["POSTGRES_PORT"] = "6543"

	cfg, err := Load(envOf(env))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	if !strings.HasPrefix(cfg.PGDSN, "postgres://") {
		t.Fatalf("DSN = %q, want postgres:// 로 시작", cfg.PGDSN)
	}
	for _, want := range []string{"db.internal", "6543", "pokeclip"} {
		if !strings.Contains(cfg.PGDSN, want) {
			t.Errorf("DSN 에 %q 가 없다: %q", want, cfg.PGDSN)
		}
	}
}

func TestLoadParsesOverrides(t *testing.T) {
	env := minimalEnv()
	env["SEGMENT_ROOT"] = "/다른/경로"
	env["ENSURE_SCHEMA"] = "true"
	env["SEGMENT_EXPECTED_DURATION_MS"] = "6000"
	env["SEGMENT_SUSPECT_BELOW_MS"] = "5800"
	env["SEGMENT_DRIFT_TOLERANCE_MS"] = "2000"
	env["SEGMENT_IDLE_TIMEOUT"] = "30s"
	env["SEGMENT_SETTLE_WAIT"] = "3s"
	env["SEGMENT_FIFO_MAX_LEN"] = "128"
	env["LOG_LEVEL"] = "debug"

	cfg, err := Load(envOf(env))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}

	if cfg.SegmentRoot != "/다른/경로" {
		t.Errorf("SegmentRoot = %q", cfg.SegmentRoot)
	}
	if !cfg.EnsureSchema {
		t.Error("ENSURE_SCHEMA=true 가 반영되지 않았다")
	}
	if cfg.Indexer.ExpectedDurationMS != 6000 || cfg.Indexer.SuspectBelowMS != 5800 {
		t.Errorf("길이 설정 = %d/%d", cfg.Indexer.ExpectedDurationMS, cfg.Indexer.SuspectBelowMS)
	}
	if cfg.Indexer.DriftToleranceMS != 2000 {
		t.Errorf("DriftToleranceMS = %d", cfg.Indexer.DriftToleranceMS)
	}
	if cfg.Watcher.IdleTimeout != 30*time.Second || cfg.Indexer.IdleTimeout != 30*time.Second {
		t.Errorf("IdleTimeout = %v / %v", cfg.Watcher.IdleTimeout, cfg.Indexer.IdleTimeout)
	}
	if cfg.Watcher.Settle.SettleWait != 3*time.Second {
		t.Errorf("SettleWait = %v", cfg.Watcher.Settle.SettleWait)
	}
	if cfg.Watcher.FIFOMaxLen != 128 {
		t.Errorf("FIFOMaxLen = %d", cfg.Watcher.FIFOMaxLen)
	}
	if cfg.LogLevel.String() != "DEBUG" {
		t.Errorf("LogLevel = %v", cfg.LogLevel)
	}
}

// 잘못된 값은 조용히 기본값으로 넘어가지 않고 실패한다.
func TestLoadRejectsMalformedValues(t *testing.T) {
	tests := []struct{ key, val string }{
		{"SEGMENT_EXPECTED_DURATION_MS", "네 글자"},
		{"SEGMENT_IDLE_TIMEOUT", "십초"},
		{"SEGMENT_FIFO_MAX_LEN", "-1"},
		{"ENSURE_SCHEMA", "아마도"},
		{"LOG_LEVEL", "시끄럽게"},
		{"POSTGRES_PORT", "포트"},
	}

	for _, tt := range tests {
		t.Run(tt.key, func(t *testing.T) {
			env := minimalEnv()
			env[tt.key] = tt.val

			if _, err := Load(envOf(env)); err == nil {
				t.Fatalf("%s=%q 인데 에러가 없다", tt.key, tt.val)
			}
		})
	}
}

// LOW — 의심 하한이 기대 길이보다 크면 H6 승격이 매번 헛돌게 된다. 기동 시 잡는다.
func TestFixRejectsSuspectAboveExpected(t *testing.T) {
	env := minimalEnv()
	env["SEGMENT_EXPECTED_DURATION_MS"] = "4000"
	env["SEGMENT_SUSPECT_BELOW_MS"] = "4500"

	if _, err := Load(envOf(env)); err == nil {
		t.Fatal("SUSPECT_BELOW > EXPECTED_DURATION 인데 에러가 없다")
	}
}

// 지적4 — 감시 디렉토리 상한을 환경변수로 조정할 수 있고 기본값이 살아 있다.
func TestFixMaxWatchDirs(t *testing.T) {
	cfg, err := Load(envOf(minimalEnv()))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	if cfg.Watcher.MaxWatchDirs != 1024 {
		t.Fatalf("MaxWatchDirs = %d, want 1024", cfg.Watcher.MaxWatchDirs)
	}

	env := minimalEnv()
	env["SEGMENT_MAX_WATCH_DIRS"] = "16"
	cfg, err = Load(envOf(env))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	if cfg.Watcher.MaxWatchDirs != 16 {
		t.Fatalf("MaxWatchDirs = %d, want 16", cfg.Watcher.MaxWatchDirs)
	}
}

// LOW — sslmode 를 DSN 에 실어 보낸다. 기본은 prefer(가능하면 TLS).
func TestFixSSLMode(t *testing.T) {
	cfg, err := Load(envOf(minimalEnv()))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	if !strings.Contains(cfg.PGDSN, "sslmode=prefer") {
		t.Fatalf("DSN 에 기본 sslmode 가 없다: %q", cfg.PGDSN)
	}

	env := minimalEnv()
	env["POSTGRES_SSLMODE"] = "require"
	cfg, err = Load(envOf(env))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	if !strings.Contains(cfg.PGDSN, "sslmode=require") {
		t.Fatalf("DSN = %q", cfg.PGDSN)
	}
}

// ---------------------------------------------------------------------------
// 훅 어댑터 설정 (ADR-027)
// ---------------------------------------------------------------------------

func TestLoadAppliesHookDefaults(t *testing.T) {
	cfg, err := Load(envOf(minimalEnv()))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}

	// 스풀 경로에는 기본값이 없다. 미설정 = 훅 어댑터 끔이 기본 상태다.
	if cfg.HookSpoolPath != "" {
		t.Errorf("HookSpoolPath = %q, want \"\"(미설정 = 끔)", cfg.HookSpoolPath)
	}
	if cfg.HookPollInterval != 200*time.Millisecond {
		t.Errorf("HookPollInterval = %v, want 200ms", cfg.HookPollInterval)
	}
	if cfg.Indexer.BreakGuard != 20*time.Millisecond {
		t.Errorf("BreakGuard = %v, want 20ms", cfg.Indexer.BreakGuard)
	}
	// 훅 세그먼트 경로 정본화 기준은 워처 루트와 반드시 같아야 한다.
	// 다르면 두 채널이 서로 다른 local_path 를 만들어 같은 파일에 행이 둘 생긴다.
	if cfg.Indexer.SegmentRoot != cfg.Watcher.Root {
		t.Errorf("SegmentRoot 가 인덱서(%q)와 워처(%q)에서 다르다",
			cfg.Indexer.SegmentRoot, cfg.Watcher.Root)
	}
}

// HOOK_SPOOL_PATH="" 는 즉시 롤백 스위치다. withDefault 를 쓰면 빈 값을 미설정으로 보고
// fallback 을 돌려주므로 그 스위치가 고장난다.
func TestEmptyHookSpoolPathStaysEmpty(t *testing.T) {
	env := minimalEnv()
	env["HOOK_SPOOL_PATH"] = ""

	cfg, err := Load(envOf(env))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	if cfg.HookSpoolPath != "" {
		t.Errorf("HookSpoolPath = %q, want \"\" — 롤백 스위치가 fallback 에 덮였다", cfg.HookSpoolPath)
	}
}

func TestLoadReadsHookOverrides(t *testing.T) {
	env := minimalEnv()
	env["HOOK_SPOOL_PATH"] = "/hooks/events.jsonl"
	env["HOOK_POLL_INTERVAL"] = "50ms"
	env["HOOK_BREAK_GUARD"] = "35ms"

	cfg, err := Load(envOf(env))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	if cfg.HookSpoolPath != "/hooks/events.jsonl" {
		t.Errorf("HookSpoolPath = %q", cfg.HookSpoolPath)
	}
	if cfg.HookPollInterval != 50*time.Millisecond {
		t.Errorf("HookPollInterval = %v, want 50ms", cfg.HookPollInterval)
	}
	if cfg.Indexer.BreakGuard != 35*time.Millisecond {
		t.Errorf("BreakGuard = %v, want 35ms", cfg.Indexer.BreakGuard)
	}
}

// 0·음수 기간은 기동 거부다. Guard 가 0 이면 파일명 시각 해상도 차이만으로 새 세션의
// 첫 조각을 놓치고, 폴 간격이 0 이면 tail 이 busy loop 가 된다.
func TestHookDurationsRejectZeroAndNegative(t *testing.T) {
	for _, key := range []string{"HOOK_POLL_INTERVAL", "HOOK_BREAK_GUARD"} {
		for _, bad := range []string{"0s", "-1ms", "빠르게"} {
			t.Run(key+"="+bad, func(t *testing.T) {
				env := minimalEnv()
				env[key] = bad

				if _, err := Load(envOf(env)); err == nil {
					t.Fatalf("%s=%q 를 받아들였다", key, bad)
				} else if !strings.Contains(err.Error(), key) {
					t.Errorf("에러에 키 이름이 없다: %v", err)
				}
			})
		}
	}
}
