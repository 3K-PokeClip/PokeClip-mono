package main

import (
	"fmt"
	"log/slog"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgconf"
)

// 하한 · 상한(설계 2-5). 값의 근거:
//   - maxFSOpTimeout: 잠금 트랜잭션 상한(RowLockTxnTimeout 8s) 안에 UPDATE · COMMIT 2s 를 남긴다(E3′).
//   - minGrace: 계약 그레이스 8분(K5).
//   - minReconnectWindow: ADR-044 재접속 창 300초가 하한이다(m4). 사이드카 config.Load 의 ReconnectWindow 와 같아야 한다 — 결속 시험 TestReconnectWindowFloorMatchesSidecar(POK-372 할 일 3).
//   - minRecordDeleteAfter: 여유(1h)를 빼도 양수(2h).
//   - minFSOpTimeout · minInterval · maxBatch · maxMaxPerCycle: 운영자 실수로 모든 삭제가 stall 이 되거나
//     DB 를 쉬지 않고 두드리거나 한 번에 너무 많이 읽는 자기 DoS 를 막는다(t2 r1 Q8 — 보안 검수 L-1 의 예).
const (
	minFSOpTimeout       = 100 * time.Millisecond
	maxFSOpTimeout       = 6 * time.Second
	minInterval          = time.Second
	maxBatch             = 10000
	maxMaxPerCycle       = 100000
	minGrace             = 8 * time.Minute
	minReconnectWindow   = 300 * time.Second
	minRecordDeleteAfter = 2 * time.Hour
	// externalDeleteMargin 은 MediaMTX recordDeleteAfter 에서 빼는 여유다 — 그 나이를 넘긴 행의 ENOENT 만
	// 「MediaMTX 가 먼저 지움」으로 인정한다(K7).
	externalDeleteMargin = time.Hour
)

// config 는 janitor 설정이다. pg 는 사이드카와 같은 규칙(pgconf)으로 읽은 DB 접속 설정이다 — 비밀번호는 비공개 필드라
// 로그 · 오류에 실리지 않는다.
type config struct {
	enabled           bool
	pg                pgconf.Settings
	segmentRoot       string
	logLevel          slog.Level
	fsOpTimeout       time.Duration
	interval          time.Duration
	grace             time.Duration
	reconnectWindow   time.Duration
	recordDeleteAfter time.Duration
	batch             int
	maxPerCycle       int
}

// policy 는 판정 SQL 에 넘기는 기간 셋이다.
func (c config) policy() index.LocalCleanupPolicy {
	return index.LocalCleanupPolicy{Grace: c.grace, ReconnectWindow: c.reconnectWindow, ExternalDeleteAge: c.recordDeleteAfter - externalDeleteMargin}
}

// loadConfig 는 환경 변수를 읽어 검증한다. 꺼져 있어도 모두 검증한다(설계 2-4 1단계) — 끄기 전에 틀린 값을
// 켜는 날 처음 알게 되지 않게. 오류 문구에는 변수 이름만 싣고 값 가운데 비밀은 싣지 않는다.
func loadConfig(getenv func(string) string) (config, error) {
	var c config
	var err error
	if c.enabled, err = enabledSwitch(getenv("JANITOR_ENABLED")); err != nil {
		return config{}, err
	}
	if c.pg, err = pgconf.FromEnv(getenv); err != nil {
		return config{}, err
	}
	c.segmentRoot = filepath.Clean(withDefault(getenv, "SEGMENT_ROOT", "/recordings"))
	if c.logLevel, err = logLevel(getenv("LOG_LEVEL")); err != nil {
		return config{}, err
	}
	if err := c.loadDurations(getenv); err != nil {
		return config{}, err
	}
	if c.batch, err = boundedInt(getenv, "JANITOR_BATCH", 200, maxBatch); err != nil {
		return config{}, err
	}
	if c.maxPerCycle, err = boundedInt(getenv, "JANITOR_MAX_PER_CYCLE", 2000, maxMaxPerCycle); err != nil {
		return config{}, err
	}
	if c.batch > c.maxPerCycle {
		return config{}, fmt.Errorf("JANITOR_BATCH(%d)는 JANITOR_MAX_PER_CYCLE(%d) 이하여야 한다", c.batch, c.maxPerCycle)
	}
	return c, nil
}

// loadDurations 는 기간 다섯을 읽고 하한 · 상한을 본다.
func (c *config) loadDurations(getenv func(string) string) error {
	fields := []struct {
		key      string
		fallback time.Duration
		dst      *time.Duration
		min, max time.Duration // 0 = 검사 없음
	}{
		{"FS_OP_TIMEOUT", 5 * time.Second, &c.fsOpTimeout, minFSOpTimeout, maxFSOpTimeout},
		{"JANITOR_INTERVAL", 30 * time.Second, &c.interval, minInterval, 0},
		{"JANITOR_GRACE", minGrace, &c.grace, minGrace, 0},
		{"JANITOR_RECONNECT_WINDOW", minReconnectWindow, &c.reconnectWindow, minReconnectWindow, 0},
		{"JANITOR_RECORD_DELETE_AFTER", 24 * time.Hour, &c.recordDeleteAfter, minRecordDeleteAfter, 0},
	}
	for _, f := range fields {
		v, err := duration(getenv, f.key, f.fallback)
		if err != nil {
			return err
		}
		if f.min > 0 && v < f.min {
			return fmt.Errorf("%s(%v)는 %v 이상이어야 한다", f.key, v, f.min)
		}
		if f.max > 0 && v > f.max {
			return fmt.Errorf("%s(%v)는 %v 이하여야 한다 — 잠금 트랜잭션 상한(%v) 안에 UPDATE · COMMIT 을 남긴다", f.key, v, f.max, index.RowLockTxnTimeout)
		}
		*f.dst = v
	}
	return nil
}

// enabledSwitch 는 JANITOR_ENABLED 를 읽는다 — strconv.ParseBool 의 참 값이면 켬, 거짓 값과 빈 값이면 끔
// (대기), 그 밖은 기동 거부다(K6). 빈 값이 끔인 것은 형제 관례(빈 값이면 기동하지 않음)와 같다.
func enabledSwitch(raw string) (bool, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return false, nil
	}
	v, err := strconv.ParseBool(raw)
	if err != nil {
		return false, fmt.Errorf("JANITOR_ENABLED(%q)는 true · false 꼴(1 · t · TRUE · 0 · f · FALSE 등)이거나 빈 값이어야 한다", raw)
	}
	return v, nil
}

func withDefault(getenv func(string) string, key, fallback string) string {
	if v := strings.TrimSpace(getenv(key)); v != "" {
		return v
	}
	return fallback
}

func positiveInt(getenv func(string) string, key string, fallback int) (int, error) {
	raw := strings.TrimSpace(getenv(key))
	if raw == "" {
		return fallback, nil
	}
	v, err := strconv.Atoi(raw)
	if err != nil || v <= 0 {
		return 0, fmt.Errorf("%s 는 양의 정수여야 한다: %q", key, raw)
	}
	return v, nil
}

// boundedInt 는 양의 정수를 읽고 상한을 본다.
func boundedInt(getenv func(string) string, key string, fallback, limit int) (int, error) {
	v, err := positiveInt(getenv, key, fallback)
	if err != nil {
		return 0, err
	}
	if v > limit {
		return 0, fmt.Errorf("%s(%d)는 %d 이하여야 한다", key, v, limit)
	}
	return v, nil
}

func duration(getenv func(string) string, key string, fallback time.Duration) (time.Duration, error) {
	raw := strings.TrimSpace(getenv(key))
	if raw == "" {
		return fallback, nil
	}
	v, err := time.ParseDuration(raw)
	if err != nil || v <= 0 {
		return 0, fmt.Errorf("%s 는 양의 기간이어야 한다(예: 5s, 8m): %q", key, raw)
	}
	return v, nil
}

// logLevel 은 LOG_LEVEL(비면 info)을 읽는다 — 사이드카와 같은 규칙이다. 틀리면 기동 실패다.
func logLevel(raw string) (slog.Level, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return slog.LevelInfo, nil
	}
	var level slog.Level
	if err := level.UnmarshalText([]byte(raw)); err != nil {
		return 0, fmt.Errorf("LOG_LEVEL 은 debug · info · warn · error 가운데 하나여야 한다")
	}
	return level, nil
}
