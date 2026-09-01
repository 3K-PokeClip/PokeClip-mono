// Package config 는 환경변수를 읽어 설정으로 만든다.
//
// 인식하는 환경변수 전수 목록은 media/README.md 와 docker-compose.yml 의 서비스 블록 주석에
// 이름·기본값·의미와 함께 적혀 있다. 코드를 읽지 않고도 무엇을 켤 수 있는지 알 수 있어야 한다.
package config

import (
	"fmt"
	"log/slog"
	"net/url"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/indexer"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxhook"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/upload"
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

	// S3 는 PUT 대상 설정이다. Bucket 이 비면 업로더가 통째로 꺼진다(결정 11‴).
	S3 upload.S3Options
	// Upload 는 업로더의 정책값이다. Root 는 main 이 os.OpenRoot 로 채운다 —
	// 설정은 파일을 열지 않는다.
	Upload upload.Options

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
	hookPollInterval, err := duration(env, "HOOK_POLL_INTERVAL", mtxhook.DefaultPollInterval)
	if err != nil {
		return Config{}, err
	}

	// 루트는 **반드시 정본화해서** 담는다. 후행 슬래시가 붙으면 fsnotify 가 이중 슬래시
	// 경로를 만들고 훅 채널은 Clean 된 경로를 만들어, 같은 물리 파일에 서로 다른
	// local_path 로 행이 둘 생긴다. seq 재사용·재정렬 금지라 사후 정정이 불가능하다.
	watch := recording.DefaultWatcherOptions(
		filepath.Clean(withDefault(env, "SEGMENT_ROOT", "/recordings")), nil)
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

	// --- POK-30 업로더 (env 8개) ---
	//
	// Root 는 여기서 열지 않는다. 설정은 값만 읽고, 파일 시스템 손잡이는 main 이 만든다.
	up := upload.DefaultOptions(nil, watch.Root)
	s3 := upload.S3Options{Region: withDefault(env, "AWS_REGION", "ap-northeast-2")}

	// ★ 버킷 판정이 **제일 앞**이다. 비면 나머지 S3·업로드 설정은 읽지도 검증하지도 않는다.
	//   그 값들은 전부 꺼진 부품의 것이라 쓰이지 않는데, 여기서 파싱에 실패하면 오타 하나가
	//   인덱싱까지 통째로 죽인다 — "업로더만 꺼지고 나머지는 그대로"라는 결정 11‴·G15 위반이다.
	//   이 파일은 2·3번이 매일 띄우는 것이다.
	if bucket := strings.TrimSpace(env("S3_BUCKET")); bucket != "" {
		s3.Bucket = bucket
		s3.Endpoint = strings.TrimSpace(env("S3_ENDPOINT"))
		if s3.ForcePathStyle, err = boolean(env, "S3_FORCE_PATH_STYLE", false); err != nil {
			return Config{}, err
		}
		if up.RetryMax, err = positiveInt(env, "SEGMENT_UPLOAD_RETRY_MAX", up.RetryMax); err != nil {
			return Config{}, err
		}
		if up.SweepEvery, err = duration(env, "SEGMENT_UPLOAD_SWEEP_EVERY", up.SweepEvery); err != nil {
			return Config{}, err
		}
		// CircuitMax 만 양수 검증을 쓰지 않는다 — 0 이 "브레이커 전체 무효화"라는 뜻을 가진
		// 값이라 positiveInt 로 받으면 그 뜻을 표현할 수 없다(설계 8절 env 표).
		if up.CircuitMax, err = nonNegativeInt(env, "SEGMENT_UPLOAD_CIRCUIT_MAX", up.CircuitMax); err != nil {
			return Config{}, err
		}
		// 버킷을 쓰겠다고 했으면 이름 문법과 엔드포인트를 그 자리에서 본다.
		// 오타를 기동 때 잡지 않으면 PUT 이 전부 404 로 실패한 뒤에야 알게 된다.
		if err := validateS3(s3); err != nil {
			return Config{}, err
		}
	}

	// TailHold 는 업로더가 꺼져 있어도 인덱서가 쓴다(꼬리 보류 수명). 항상 읽는다.
	if idx.TailHold, err = duration(env, "SEGMENT_UPLOAD_TAIL_HOLD", idx.TailHold); err != nil {
		return Config{}, err
	}
	// 두 계층이 같은 유예를 봐야 인덱서의 포기 시점과 스위퍼의 자격 시점이 맞물린다(결정 4⁵).
	idx.TailGrace = up.TailGrace

	// 교차 검증 1 — 보류 상한이 스위퍼 자격 시점을 넘으면 두 계층이 같은 꼬리를 동시에 노린다.
	if idx.TailHold >= up.TailGrace {
		return Config{}, fmt.Errorf(
			"SEGMENT_UPLOAD_TAIL_HOLD(%v)는 꼬리 유예(%v)보다 짧아야 한다", idx.TailHold, up.TailGrace)
	}
	// 교차 검증 2 — 안정 판정이 끝나기도 전에 올리면 잘린 실물이 그대로 굳는다.
	if idx.TailHold < watch.Settle.SettleWait {
		return Config{}, fmt.Errorf(
			"SEGMENT_UPLOAD_TAIL_HOLD(%v)는 SEGMENT_SETTLE_WAIT(%v) 이상이어야 한다",
			idx.TailHold, watch.Settle.SettleWait)
	}

	// 워처와 인덱서는 같은 유휴 판정 기준을 써야 한다.
	// 달라지면 H4(유휴 커밋 전 mtime 재검)가 워처의 판정과 어긋나 되돌리기가 무한 반복된다.
	idx.IdleTimeout = watch.IdleTimeout
	idx.Settle = watch.Settle
	// 훅 세그먼트 경로 정본화 기준은 워처 루트와 반드시 같아야 한다.
	// 다르면 두 채널이 같은 물리 파일에 서로 다른 local_path 를 만들어 행이 둘 생긴다.
	idx.SegmentRoot = watch.Root

	// 처리 FS 격리(POK-168 M1 — ADR-063). 개별 FS 호출 상한과 수집 soft 예산.
	if idx.FSOpTimeout, err = duration(env, "FS_OP_TIMEOUT", idx.FSOpTimeout); err != nil {
		return Config{}, err
	}
	if idx.ScanCollectBudget, err = duration(env, "SCAN_COLLECT_BUDGET", idx.ScanCollectBudget); err != nil {
		return Config{}, err
	}
	// 스위퍼 arm 폴백의 정본 산식(설계 11.3): max(2×RescanEvery, 2×ScanCollectBudget).
	// 하한이 필요한 이유 — RescanEvery 는 설정값이라 30초로 낮추면 폴백 60초가 정상 첫
	// 수집(예산 45초)이 끝나기도 전에 무조건 arm 을 돌려 원 상태를 기본 경로에서 재현한다.
	up.ArmFallback = max(2*watch.RescanEvery, 2*idx.ScanCollectBudget)

	return Config{
		PGDSN:            dsn(user, password, host, port, dbName, sslMode),
		SegmentRoot:      watch.Root,
		S3:               s3,
		Upload:           up,
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

// validateS3 는 교차 검증 3 이다. 버킷이 설정됐을 때만 부른다.
func validateS3(s3 upload.S3Options) error {
	if !bucketNameRe.MatchString(s3.Bucket) {
		return fmt.Errorf(
			"S3_BUCKET(%q)이 버킷 이름 규칙(소문자·숫자·점·하이픈 3-63자)에 맞지 않는다", s3.Bucket)
	}
	// 점 두 개 연속과 IP 형식은 S3 가 거부하는 이름이다.
	if strings.Contains(s3.Bucket, "..") || ipLikeRe.MatchString(s3.Bucket) {
		return fmt.Errorf("S3_BUCKET(%q)은 연속된 점이나 IP 형식을 쓸 수 없다", s3.Bucket)
	}
	// AWS 가 예약한 접두·접미다. 통과시키면 CreateBucket 이 아니라 PUT 단계에서야 실패한다.
	for _, p := range bucketBannedPrefixes {
		if strings.HasPrefix(s3.Bucket, p) {
			return fmt.Errorf("S3_BUCKET(%q)은 예약 접두 %q 를 쓸 수 없다", s3.Bucket, p)
		}
	}
	for _, sfx := range bucketBannedSuffixes {
		if strings.HasSuffix(s3.Bucket, sfx) {
			return fmt.Errorf("S3_BUCKET(%q)은 예약 접미 %q 를 쓸 수 없다", s3.Bucket, sfx)
		}
	}
	if s3.Endpoint == "" {
		return nil
	}
	u, err := url.Parse(s3.Endpoint)
	// Hostname() 까지 본다 — "http://:9000" 은 Host 가 ":9000" 이라 비어 있지 않지만
	// 호스트가 없다. SDK 는 그대로 들고 가 연결 단계에서야 실패한다.
	if err != nil || u.Host == "" || u.Hostname() == "" {
		return fmt.Errorf("S3_ENDPOINT(%q)는 scheme 과 호스트를 포함한 URL 이어야 한다", s3.Endpoint)
	}
	// SDK 는 이상한 scheme 을 조용히 삼킨다. 우리가 쓰는 것은 둘뿐이다.
	if u.Scheme != "http" && u.Scheme != "https" {
		return fmt.Errorf("S3_ENDPOINT(%q)의 scheme 은 http 또는 https 여야 한다", s3.Endpoint)
	}
	// userinfo 거부 — endpoint 원문은 uploader_started 로그와 오류 문자열에 그대로 실린다.
	// URL 에 자격증명을 넣으면 그 로그가 유출 경로가 된다. 자격증명 자리는 env 3종뿐이다.
	// (이 오류 문구만은 원문을 되돌려주지 않는다 — 거부 사유 자체가 "비밀이 들어 있다"이므로.)
	if u.User != nil {
		return fmt.Errorf("S3_ENDPOINT 에 자격증명(user:pass@)을 넣을 수 없다 — AWS_ACCESS_KEY_ID 등 env 로 전달하라")
	}
	return nil
}

// bucketBannedPrefixes·bucketBannedSuffixes 는 AWS 가 예약한 이름 조각이다.
var (
	bucketBannedPrefixes = []string{"xn--", "sthree-", "amzn-s3-demo-"}
	bucketBannedSuffixes = []string{"-s3alias", "--ol-s3", ".mrap", "--x-s3", "--table-s3"}
)

// ipLikeRe 는 IPv4 형태의 버킷 이름을 잡는다.
var ipLikeRe = regexp.MustCompile(`^\d{1,3}(\.\d{1,3}){3}$`)

// bucketNameRe 는 S3 버킷 이름 규칙이다. 오타를 기동 때 잡지 않으면
// PUT 이 전부 404 로 실패한 뒤에야 알게 된다.
var bucketNameRe = regexp.MustCompile(`^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$`)

// nonNegativeInt 는 0 을 허용하는 정수 파서다.
// positiveInt 와 나눈 이유: SEGMENT_UPLOAD_CIRCUIT_MAX 의 0 은 실수가 아니라
// "브레이커 전체 무효화"라는 뜻을 가진 값이다.
func nonNegativeInt(env func(string) string, key string, fallback int) (int, error) {
	raw := strings.TrimSpace(env(key))
	if raw == "" {
		return fallback, nil
	}
	n, err := strconv.Atoi(raw)
	if err != nil || n < 0 {
		return 0, fmt.Errorf("%s 는 0 이상의 정수여야 한다: %q", key, raw)
	}
	return n, nil
}
