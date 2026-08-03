// Package config 는 환경변수를 읽어 설정으로 만든다.
//
// 인식하는 환경변수 전수 목록은 media/README.md 와 docker-compose.yml 의 서비스 블록 주석에
// 이름·기본값·의미와 함께 적혀 있다. 코드를 읽지 않고도 무엇을 켤 수 있는지 알 수 있어야 한다.
package config

import (
	"fmt"
	"log/slog"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/indexer"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// Config 는 사이드카가 기동하는 데 필요한 전부다.
type Config struct {
	// PGDSN 은 비밀값을 포함한다. 로그나 에러 메시지에 그대로 실으면 안 된다.
	PGDSN        string
	SegmentRoot  string
	EnsureSchema bool
	LogLevel     slog.Level
	Indexer      indexer.Options
	Watcher      recording.WatcherOptions

	// HookSpoolPath 는 MediaMTX 훅이 한 줄씩 덧붙이는 공유 파일이다.
	//
	// **빈 문자열이 기본값이고, 빈 값이면 훅 어댑터를 아예 기동하지 않는다.**
	// 이것이 즉시 롤백 스위치이므로 withDefault 를 쓰면 안 된다 — 그 헬퍼는 빈 값을
	// "미설정"으로 보고 fallback 을 돌려주므로 HOOK_SPOOL_PATH="" 로 끄는 길이 막힌다.
	HookSpoolPath string
	// HookPollInterval 은 스풀 tail 주기다.
	HookPollInterval time.Duration
	// 세션 경계 판정의 Guard 는 Indexer.BreakGuard 하나에만 둔다(값의 집이 둘이면 어긋난다).
}

// Load 는 환경변수를 읽어 설정을 만든다. env 는 os.Getenv 를 그대로 넘기면 된다.
//
// 필수 값이 없거나 빈 문자열이면 즉시 에러다. 빈 문자열을 "설정됨"으로 보면
// 비밀번호 없이 DB 에 붙으려다 원인 불명으로 실패한다.
func Load(env func(string) string) (Config, error) {
	user, err := required(env, "POSTGRES_USER")
	if err != nil {
		return Config{}, err
	}
	password, err := required(env, "POSTGRES_PASSWORD")
	if err != nil {
		return Config{}, err
	}
	dbName, err := required(env, "POSTGRES_DB")
	if err != nil {
		return Config{}, err
	}
	host := withDefault(env, "POSTGRES_HOST", "postgres")
	port, err := positiveInt(env, "POSTGRES_PORT", 5432)
	if err != nil {
		return Config{}, err
	}
	// prefer = TLS 를 시도하되 서버가 못 하면 평문으로 내려간다. 로컬 compose 기본에 맞춘 값이며,
	// 네트워크를 건너는 배포에서는 require 이상으로 올린다.
	sslMode := withDefault(env, "POSTGRES_SSLMODE", "prefer")

	logLevel, err := level(env, "LOG_LEVEL", slog.LevelInfo)
	if err != nil {
		return Config{}, err
	}
	ensureSchema, err := boolean(env, "ENSURE_SCHEMA", false)
	if err != nil {
		return Config{}, err
	}

	idx := indexer.DefaultOptions()
	if idx.ExpectedDurationMS, err = positiveInt64(env, "SEGMENT_EXPECTED_DURATION_MS", idx.ExpectedDurationMS); err != nil {
		return Config{}, err
	}
	if idx.SuspectBelowMS, err = positiveInt64(env, "SEGMENT_SUSPECT_BELOW_MS", idx.SuspectBelowMS); err != nil {
		return Config{}, err
	}
	if idx.DriftToleranceMS, err = positiveInt64(env, "SEGMENT_DRIFT_TOLERANCE_MS", idx.DriftToleranceMS); err != nil {
		return Config{}, err
	}
	if idx.InsertRetryMax, err = positiveInt(env, "SEGMENT_INSERT_RETRY_MAX", idx.InsertRetryMax); err != nil {
		return Config{}, err
	}
	// duration 헬퍼가 이미 0·음수·형식 오류를 거부한다. Guard 가 0 이면 파일명 시각 해상도
	// 차이만으로 새 세션의 첫 조각을 놓친다.
	if idx.BreakGuard, err = duration(env, "HOOK_BREAK_GUARD", idx.BreakGuard); err != nil {
		return Config{}, err
	}

	// HOOK_SPOOL_PATH 는 **withDefault 를 쓰지 않는다**(위 필드 주석 참조).
	hookSpoolPath := strings.TrimSpace(env("HOOK_SPOOL_PATH"))
	hookPollInterval, err := duration(env, "HOOK_POLL_INTERVAL", 200*time.Millisecond)
	if err != nil {
		return Config{}, err
	}

	watch := recording.DefaultWatcherOptions(withDefault(env, "SEGMENT_ROOT", "/recordings"), nil)
	if watch.IdleTimeout, err = duration(env, "SEGMENT_IDLE_TIMEOUT", watch.IdleTimeout); err != nil {
		return Config{}, err
	}
	if watch.RescanEvery, err = duration(env, "SEGMENT_RESCAN_EVERY", watch.RescanEvery); err != nil {
		return Config{}, err
	}
	if watch.Settle.SettleWait, err = duration(env, "SEGMENT_SETTLE_WAIT", watch.Settle.SettleWait); err != nil {
		return Config{}, err
	}
	if watch.Settle.PollInterval, err = duration(env, "SEGMENT_SETTLE_POLL", watch.Settle.PollInterval); err != nil {
		return Config{}, err
	}
	if watch.Settle.MaxSettle, err = duration(env, "SEGMENT_SETTLE_MAX", watch.Settle.MaxSettle); err != nil {
		return Config{}, err
	}
	if watch.FIFOWarnLen, err = positiveInt(env, "SEGMENT_FIFO_WARN_LEN", watch.FIFOWarnLen); err != nil {
		return Config{}, err
	}
	if watch.FIFOMaxLen, err = positiveInt(env, "SEGMENT_FIFO_MAX_LEN", watch.FIFOMaxLen); err != nil {
		return Config{}, err
	}
	if watch.MaxWatchDirs, err = positiveInt(env, "SEGMENT_MAX_WATCH_DIRS", watch.MaxWatchDirs); err != nil {
		return Config{}, err
	}

	// 의심 하한이 기대 길이보다 크면 H6 승격이 모든 세그먼트에서 헛돌고 WARN 만 쌓인다.
	// 설정 단계에서 잡지 않으면 운영 중에야 로그로 알게 된다.
	if idx.SuspectBelowMS > idx.ExpectedDurationMS {
		return Config{}, fmt.Errorf(
			"SEGMENT_SUSPECT_BELOW_MS(%d)는 SEGMENT_EXPECTED_DURATION_MS(%d) 이하여야 한다",
			idx.SuspectBelowMS, idx.ExpectedDurationMS)
	}

	// 워처와 인덱서는 같은 유휴 판정 기준을 써야 한다.
	// 달라지면 H4(유휴 커밋 전 mtime 재검)가 워처의 판정과 어긋나 되돌리기가 무한 반복된다.
	idx.IdleTimeout = watch.IdleTimeout
	idx.Settle = watch.Settle
	// 훅 세그먼트 경로 정본화 기준은 워처 루트와 반드시 같아야 한다.
	// 다르면 두 채널이 같은 물리 파일에 서로 다른 local_path 를 만들어 행이 둘 생긴다.
	idx.SegmentRoot = watch.Root

	return Config{
		PGDSN:            dsn(user, password, host, port, dbName, sslMode),
		SegmentRoot:      watch.Root,
		EnsureSchema:     ensureSchema,
		LogLevel:         logLevel,
		Indexer:          idx,
		Watcher:          watch,
		HookSpoolPath:    hookSpoolPath,
		HookPollInterval: hookPollInterval,
	}, nil
}

// dsn 은 사용자명과 비밀번호를 URL 인코딩해 넣는다. 특수문자가 든 비밀번호도 깨지지 않는다.
func dsn(user, password, host string, port int, dbName, sslMode string) string {
	u := url.URL{
		Scheme:   "postgres",
		User:     url.UserPassword(user, password),
		Host:     fmt.Sprintf("%s:%d", host, port),
		Path:     "/" + dbName,
		RawQuery: url.Values{"sslmode": {sslMode}}.Encode(),
	}
	return u.String()
}

func required(env func(string) string, key string) (string, error) {
	v := strings.TrimSpace(env(key))
	if v == "" {
		return "", fmt.Errorf("필수 환경변수 %s 가 비어 있다", key)
	}
	return v, nil
}

func withDefault(env func(string) string, key, fallback string) string {
	if v := strings.TrimSpace(env(key)); v != "" {
		return v
	}
	return fallback
}

func positiveInt(env func(string) string, key string, fallback int) (int, error) {
	raw := strings.TrimSpace(env(key))
	if raw == "" {
		return fallback, nil
	}
	v, err := strconv.Atoi(raw)
	if err != nil || v <= 0 {
		return 0, fmt.Errorf("%s 는 양의 정수여야 한다: %q", key, raw)
	}
	return v, nil
}

func positiveInt64(env func(string) string, key string, fallback int64) (int64, error) {
	raw := strings.TrimSpace(env(key))
	if raw == "" {
		return fallback, nil
	}
	v, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || v <= 0 {
		return 0, fmt.Errorf("%s 는 양의 정수여야 한다: %q", key, raw)
	}
	return v, nil
}

func duration(env func(string) string, key string, fallback time.Duration) (time.Duration, error) {
	raw := strings.TrimSpace(env(key))
	if raw == "" {
		return fallback, nil
	}
	v, err := time.ParseDuration(raw)
	if err != nil || v <= 0 {
		return 0, fmt.Errorf("%s 는 양의 기간이어야 한다(예: 10s, 5m): %q", key, raw)
	}
	return v, nil
}

func boolean(env func(string) string, key string, fallback bool) (bool, error) {
	raw := strings.TrimSpace(env(key))
	if raw == "" {
		return fallback, nil
	}
	v, err := strconv.ParseBool(raw)
	if err != nil {
		return false, fmt.Errorf("%s 는 true 또는 false 여야 한다: %q", key, raw)
	}
	return v, nil
}

func level(env func(string) string, key string, fallback slog.Level) (slog.Level, error) {
	raw := strings.TrimSpace(env(key))
	if raw == "" {
		return fallback, nil
	}
	var l slog.Level
	if err := l.UnmarshalText([]byte(raw)); err != nil {
		return 0, fmt.Errorf("%s 는 debug/info/warn/error 중 하나여야 한다: %q", key, raw)
	}
	return l, nil
}
