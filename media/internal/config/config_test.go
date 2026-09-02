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

// SEGMENT_ROOT 는 정본화해서 담는다.
//
// 후행 슬래시가 붙으면 리눅스 fsnotify 가 "/recordings//demo/x.mp4" 같은 이중 슬래시
// 경로를 만들고, 훅 채널은 Clean 을 거쳐 "/recordings/demo/x.mp4" 를 만든다. 두 문자열이
// 갈리는 순간 UNIQUE(stream_id, local_path) 가 같은 물리 파일을 다른 파일로 보아
// 행이 둘 생기며, seq 재사용·재정렬 금지 때문에 사후 정정이 불가능하다(치명).
func TestSegmentRootIsCanonicalized(t *testing.T) {
	for _, raw := range []string{"/recordings/", "/recordings//", "/recordings/./"} {
		t.Run(raw, func(t *testing.T) {
			env := minimalEnv()
			env["SEGMENT_ROOT"] = raw

			cfg, err := Load(envOf(env))
			if err != nil {
				t.Fatalf("예상 밖 에러: %v", err)
			}
			if cfg.Watcher.Root != "/recordings" {
				t.Errorf("Watcher.Root = %q, want /recordings", cfg.Watcher.Root)
			}
			if cfg.Indexer.SegmentRoot != "/recordings" {
				t.Errorf("Indexer.SegmentRoot = %q, want /recordings", cfg.Indexer.SegmentRoot)
			}
			if cfg.SegmentRoot != "/recordings" {
				t.Errorf("SegmentRoot = %q, want /recordings", cfg.SegmentRoot)
			}
		})
	}
}

// POK-30 env 8개의 기본값. 업로더는 선택 부품이므로 S3_BUCKET 이 비어 있는 것이 정상이며,
// 그때는 나머지 S3 설정을 검증하지 않는다(결정 11‴).
func TestLoadUploadDefaults(t *testing.T) {
	cfg, err := Load(envOf(minimalEnv()))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	if cfg.S3.Bucket != "" {
		t.Errorf("S3.Bucket = %q, want 빈 문자열(업로더 비활성)", cfg.S3.Bucket)
	}
	if cfg.S3.Region != "ap-northeast-2" {
		t.Errorf("S3.Region = %q, want ap-northeast-2", cfg.S3.Region)
	}
	if cfg.Upload.RetryMax != 4 || cfg.Upload.SweepEvery != 30*time.Second || cfg.Upload.CircuitMax != 3 {
		t.Errorf("업로드 기본값 = retry %d sweep %v circuit %d, want 4 / 30s / 3",
			cfg.Upload.RetryMax, cfg.Upload.SweepEvery, cfg.Upload.CircuitMax)
	}
	if cfg.Indexer.TailHold != 5*time.Second {
		t.Errorf("TailHold = %v, want 5s", cfg.Indexer.TailHold)
	}
}

// 두 값이 한 곳에서 나오는지는 **기본값이 아닌 환경**에서 봐야 한다.
// 기본값끼리는 우연히 같아서, 대입을 지워도 통과하는 과적합이 된다.
func TestUploadSharesGraceAndRootWithIndexer(t *testing.T) {
	env := minimalEnv()
	env["SEGMENT_ROOT"] = "/다른/녹화루트"
	cfg, err := Load(envOf(env))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	// 두 계층이 같은 유예를 봐야 인덱서의 포기 시점과 스위퍼의 자격 시점이 맞물린다(결정 4⁵).
	if cfg.Indexer.TailGrace != cfg.Upload.TailGrace || cfg.Indexer.TailGrace == 0 {
		t.Errorf("TailGrace 가 갈렸다: indexer %v vs upload %v",
			cfg.Indexer.TailGrace, cfg.Upload.TailGrace)
	}
	// 경로 검증(문자열)과 열기(os.Root)가 다른 값을 보면 검증을 통과한 경로가
	// 다른 루트에서 열린다.
	if cfg.Upload.SegmentRoot != "/다른/녹화루트" || cfg.SegmentRoot != "/다른/녹화루트" {
		t.Errorf("SegmentRoot 가 갈렸다: upload %q vs cfg %q", cfg.Upload.SegmentRoot, cfg.SegmentRoot)
	}
}

func TestLoadParsesUploadOverrides(t *testing.T) {
	env := minimalEnv()
	env["S3_BUCKET"] = "pokeclip-media-demo-2557"
	env["AWS_REGION"] = "us-east-1"
	env["S3_ENDPOINT"] = "http://minio:9000"
	env["S3_FORCE_PATH_STYLE"] = "true"
	env["SEGMENT_UPLOAD_RETRY_MAX"] = "2"
	env["SEGMENT_UPLOAD_SWEEP_EVERY"] = "5s"
	env["SEGMENT_UPLOAD_TAIL_HOLD"] = "3s"
	env["SEGMENT_UPLOAD_CIRCUIT_MAX"] = "0"

	cfg, err := Load(envOf(env))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	if cfg.S3.Bucket != "pokeclip-media-demo-2557" || cfg.S3.Region != "us-east-1" ||
		cfg.S3.Endpoint != "http://minio:9000" || !cfg.S3.ForcePathStyle {
		t.Errorf("S3 설정 = %+v", cfg.S3)
	}
	if cfg.Upload.RetryMax != 2 || cfg.Upload.SweepEvery != 5*time.Second {
		t.Errorf("업로드 설정 = retry %d sweep %v", cfg.Upload.RetryMax, cfg.Upload.SweepEvery)
	}
	// 0 은 "브레이커 전체 무효화"라는 뜻이 있는 값이다. 양수 검증에 걸려 거부되면 안 된다.
	if cfg.Upload.CircuitMax != 0 {
		t.Errorf("CircuitMax = %d, want 0 (브레이커 무효화)", cfg.Upload.CircuitMax)
	}
	if cfg.Indexer.TailHold != 3*time.Second {
		t.Errorf("TailHold = %v, want 3s", cfg.Indexer.TailHold)
	}
}

// 교차 검증 3개. 설정 단계에서 잡지 않으면 운영 중에야 로그로 알게 된다.
func TestLoadRejectsInconsistentUploadSettings(t *testing.T) {
	cases := []struct {
		name string
		env  map[string]string
		want string
	}{
		{
			// 보류 상한이 스위퍼 자격 시점을 넘으면 두 계층이 같은 꼬리를 동시에 노린다.
			name: "TailHold >= TailGrace",
			env:  map[string]string{"SEGMENT_UPLOAD_TAIL_HOLD": "10m"},
			want: "SEGMENT_UPLOAD_TAIL_HOLD",
		},
		{
			// 안정 판정이 끝나기도 전에 올리면 잘린 실물이 굳는다.
			name: "TailHold < SettleWait",
			env:  map[string]string{"SEGMENT_UPLOAD_TAIL_HOLD": "1s", "SEGMENT_SETTLE_WAIT": "5s"},
			want: "SEGMENT_SETTLE_WAIT",
		},
		{
			// 버킷을 쓰겠다고 했으면 이름 문법을 그 자리에서 본다.
			// 오타를 기동 때 잡지 않으면 PUT 이 전부 404 로 실패한 뒤에야 알게 된다.
			name: "S3_BUCKET 이름 문법 위반",
			env:  map[string]string{"S3_BUCKET": "Bad_Bucket!"},
			want: "S3_BUCKET",
		},
		{
			// 엔드포인트를 줬으면 URL 이어야 한다. SDK 는 이상한 값을 조용히 삼킨다.
			name: "S3_ENDPOINT 가 URL 이 아님",
			env:  map[string]string{"S3_BUCKET": "pokeclip-media-demo-2557", "S3_ENDPOINT": "minio:9000"},
			want: "S3_ENDPOINT",
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			env := minimalEnv()
			for k, v := range c.env {
				env[k] = v
			}
			_, err := Load(envOf(env))
			if err == nil {
				t.Fatal("에러가 없다 — 기동을 거부해야 한다")
			}
			if !strings.Contains(err.Error(), c.want) {
				t.Errorf("에러 = %v, want %q 를 포함", err, c.want)
			}
		})
	}
}

// S3_BUCKET 이 공백뿐이면 "비었다"로 본다 — 업로더가 꺼진다.
// 공백을 버킷 이름으로 받아들이면 PUT 이 전부 404 로 실패한다.
func TestLoadTreatsBlankBucketAsDisabled(t *testing.T) {
	env := minimalEnv()
	env["S3_BUCKET"] = "   "
	cfg, err := Load(envOf(env))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	if cfg.S3.Bucket != "" {
		t.Errorf("S3.Bucket = %q, want 빈 문자열", cfg.S3.Bucket)
	}
}

// cx HIGH — 업로더가 꺼진 환경에서는 다른 S3 값이 잘못돼도 기동이 성공해야 한다.
//
// 그 파일은 2·3번이 매일 띄우는 것이다(G15). 버킷 분기보다 파싱이 앞서면 오타 하나가
// 인덱싱까지 통째로 죽인다 — 업로더만 꺼지고 나머지는 살아야 한다는 결정 11‴ 위반이다.
func TestDisabledUploaderIgnoresOtherS3Settings(t *testing.T) {
	bad := map[string]map[string]string{
		"S3_FORCE_PATH_STYLE 가 불리언이 아님":       {"S3_FORCE_PATH_STYLE": "아마도"},
		"S3_ENDPOINT 가 URL 이 아님":              {"S3_ENDPOINT": "minio:9000"},
		"SEGMENT_UPLOAD_CIRCUIT_MAX 가 음수":     {"SEGMENT_UPLOAD_CIRCUIT_MAX": "-1"},
		"SEGMENT_UPLOAD_RETRY_MAX 가 0":        {"SEGMENT_UPLOAD_RETRY_MAX": "0"},
		"SEGMENT_UPLOAD_SWEEP_EVERY 가 기간이 아님": {"SEGMENT_UPLOAD_SWEEP_EVERY": "곧"},
	}
	for name, extra := range bad {
		t.Run(name, func(t *testing.T) {
			env := minimalEnv()
			env["S3_BUCKET"] = "" // 업로더 꺼짐
			for k, v := range extra {
				env[k] = v
			}
			cfg, err := Load(envOf(env))
			if err != nil {
				t.Fatalf("기동이 거부됐다 — 업로더만 꺼지고 인덱싱은 살아야 한다: %v", err)
			}
			if cfg.S3.Bucket != "" {
				t.Errorf("S3.Bucket = %q, want 빈 문자열", cfg.S3.Bucket)
			}
		})
	}

	// 반대로 버킷을 설정했으면 같은 값들이 기동을 막는다.
	for name, extra := range bad {
		t.Run("버킷_있음/"+name, func(t *testing.T) {
			env := minimalEnv()
			env["S3_BUCKET"] = "pokeclip-media-demo-2557"
			for k, v := range extra {
				env[k] = v
			}
			if _, err := Load(envOf(env)); err == nil {
				t.Error("에러가 없다 — 버킷을 쓰겠다고 했으면 엄격히 봐야 한다")
			}
		})
	}
}

// cx MEDIUM — 버킷 이름의 흔한 오형식과 엔드포인트 scheme 제한.
func TestLoadRejectsMalformedBucketAndEndpoint(t *testing.T) {
	cases := []struct{ name, bucket, endpoint, want string }{
		{"IP 형식", "192.168.0.1", "", "S3_BUCKET"},
		{"연속된 점", "poke..clip", "", "S3_BUCKET"},
		{"점으로 끝남", "pokeclip.", "", "S3_BUCKET"},
		{"예약 접두 xn--", "xn--pokeclip", "", "S3_BUCKET"},
		{"예약 접두 sthree-", "sthree-pokeclip", "", "S3_BUCKET"},
		{"예약 접두 amzn-s3-demo-", "amzn-s3-demo-pokeclip", "", "S3_BUCKET"},
		{"예약 접미 -s3alias", "pokeclip-s3alias", "", "S3_BUCKET"},
		{"예약 접미 --ol-s3", "pokeclip--ol-s3", "", "S3_BUCKET"},
		{"예약 접미 .mrap", "pokeclip.mrap", "", "S3_BUCKET"},
		{"예약 접미 --x-s3", "pokeclip--x-s3", "", "S3_BUCKET"},
		{"예약 접미 --table-s3", "pokeclip--table-s3", "", "S3_BUCKET"},
		{"scheme 이 http/https 가 아님", "pokeclip-media-demo-2557", "ftp://minio:9000", "S3_ENDPOINT"},
		{"호스트가 없음", "pokeclip-media-demo-2557", "http://:9000", "S3_ENDPOINT"},
		// userinfo 거부 — endpoint 원문이 uploader_started 로그·오류 문자열에 그대로 실리므로
		// URL 에 자격증명을 넣는 순간 로그가 유출 경로가 된다. 자격증명 자리는 env 3종뿐이다.
		{"자격증명(userinfo) 포함", "pokeclip-media-demo-2557", "https://user:secret@minio:9000", "S3_ENDPOINT"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			env := minimalEnv()
			env["S3_BUCKET"] = c.bucket
			if c.endpoint != "" {
				env["S3_ENDPOINT"] = c.endpoint
			}
			_, err := Load(envOf(env))
			if err == nil {
				t.Fatal("에러가 없다")
			}
			if !strings.Contains(err.Error(), c.want) {
				t.Errorf("에러 = %v, want %q 포함", err, c.want)
			}
		})
	}
}

// ---------------------------------------------------------------------------
// 관측 축 env 6종 (POK-195 M3 — 설계 5.4.1·6.5.2)
// ---------------------------------------------------------------------------

func TestLoadAppliesObservationDefaults(t *testing.T) {
	cfg, err := Load(envOf(minimalEnv()))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}

	// API URL 에는 기본값이 없다. 미설정 = 관측 끔(ⓐ2 fail-closed)이 기본 상태다.
	if cfg.MTXState.APIURL != "" {
		t.Errorf("MTXState.APIURL = %q, want \"\"(미설정 = 관측 끔)", cfg.MTXState.APIURL)
	}
	if cfg.MTXState.PollInterval != 10*time.Second {
		t.Errorf("OBS_POLL = %v, want 10s", cfg.MTXState.PollInterval)
	}
	if cfg.MTXState.BootWait != 3*time.Second {
		t.Errorf("OBS_BOOT_WAIT = %v, want 3s", cfg.MTXState.BootWait)
	}
	if cfg.ObsFresh != 30*time.Second {
		t.Errorf("OBS_FRESH = %v, want 30s", cfg.ObsFresh)
	}
	if cfg.ObsBackfill != 60*time.Second {
		t.Errorf("OBS_BACKFILL = %v, want 60s", cfg.ObsBackfill)
	}
	if cfg.SessionFloorSlack != time.Second {
		t.Errorf("SESSION_FLOOR_SLACK = %v, want 1s", cfg.SessionFloorSlack)
	}
	// 판정 창 공시(90초) = OBS_BACKFILL + OBS_FRESH. 기본값이 그 산식과 맞아야 한다.
	if got := cfg.ObsBackfill + cfg.ObsFresh; got != 90*time.Second {
		t.Errorf("OBS_BACKFILL+OBS_FRESH = %v, want 90s(판정 창 공시)", got)
	}
}

// ⓐ2 의 두 시간 창은 판정하는 층(indexer)에도 같은 값으로 도달해야 한다.
// 값의 집은 config 하나이고, 여기서 옮겨 담지 않으면 인덱서 쪽이 영값(=가장 엄격)으로
// 판정해 스캔 유입이 영영 주조되지 않는다 — 로그에는 아무것도 안 남는 종류의 고장이다.
func TestObservationWindowsReachTheIndexer(t *testing.T) {
	env := minimalEnv()
	env["OBS_FRESH"] = "45s"
	env["OBS_BACKFILL"] = "90s"

	cfg, err := Load(envOf(env))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	if cfg.Indexer.ObsFresh != cfg.ObsFresh {
		t.Errorf("Indexer.ObsFresh = %v, want %v", cfg.Indexer.ObsFresh, cfg.ObsFresh)
	}
	if cfg.Indexer.ObsBackfill != cfg.ObsBackfill {
		t.Errorf("Indexer.ObsBackfill = %v, want %v", cfg.Indexer.ObsBackfill, cfg.ObsBackfill)
	}
}

// MTX_API_URL="" 은 즉시 롤백 스위치다 — 관측이 사라지면 ⓐ2 만 fail-closed 되고
// 인덱싱은 그대로 돈다. withDefault 를 쓰면 그 스위치가 고장난다.
func TestEmptyMTXAPIURLStaysEmpty(t *testing.T) {
	env := minimalEnv()
	env["MTX_API_URL"] = ""

	cfg, err := Load(envOf(env))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	if cfg.MTXState.APIURL != "" {
		t.Errorf("MTXState.APIURL = %q, want \"\" — 롤백 스위치가 fallback 에 덮였다", cfg.MTXState.APIURL)
	}
}

func TestLoadReadsObservationOverrides(t *testing.T) {
	env := minimalEnv()
	env["MTX_API_URL"] = "http://media:9997"
	env["OBS_POLL"] = "5s"
	env["OBS_FRESH"] = "45s"
	env["OBS_BACKFILL"] = "90s"
	env["OBS_BOOT_WAIT"] = "1s"
	env["SESSION_FLOOR_SLACK"] = "2s"

	cfg, err := Load(envOf(env))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	if cfg.MTXState.APIURL != "http://media:9997" {
		t.Errorf("MTXState.APIURL = %q", cfg.MTXState.APIURL)
	}
	if cfg.MTXState.PollInterval != 5*time.Second {
		t.Errorf("OBS_POLL = %v, want 5s", cfg.MTXState.PollInterval)
	}
	if cfg.ObsFresh != 45*time.Second {
		t.Errorf("OBS_FRESH = %v, want 45s", cfg.ObsFresh)
	}
	if cfg.ObsBackfill != 90*time.Second {
		t.Errorf("OBS_BACKFILL = %v, want 90s", cfg.ObsBackfill)
	}
	if cfg.MTXState.BootWait != time.Second {
		t.Errorf("OBS_BOOT_WAIT = %v, want 1s", cfg.MTXState.BootWait)
	}
	if cfg.SessionFloorSlack != 2*time.Second {
		t.Errorf("SESSION_FLOOR_SLACK = %v, want 2s", cfg.SessionFloorSlack)
	}
}

// 0·음수·파싱 불가는 기동 거부다. 조용히 기본값으로 넘어가면 "스캔 유입(ⓐ2)의 주조가 왜 안 되지"로
// 나타난다(ⓐ1 워처·훅 유입은 OBS_* 와 무관 — config.go 의 같은 주석과 쌍).
func TestObservationDurationsRejectZeroAndNegative(t *testing.T) {
	for _, key := range []string{"OBS_POLL", "OBS_FRESH", "OBS_BACKFILL", "OBS_BOOT_WAIT", "SESSION_FLOOR_SLACK"} {
		for _, bad := range []string{"0s", "-1s", "가끔"} {
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

// 교차 검증 4 — 폴 주기가 신선도 창 이상이면 관측이 태어나자마자 낡아 ⓐ2 가 영구 불성립이다.
// 그 상태는 로그로 드러나지 않는다(폴은 성공하고 주조만 안 된다) — 기동 때 잡는다.
func TestLoadRejectsPollIntervalNotBelowFresh(t *testing.T) {
	for _, poll := range []string{"30s", "45s"} {
		t.Run("OBS_POLL="+poll, func(t *testing.T) {
			env := minimalEnv()
			env["OBS_POLL"] = poll
			env["OBS_FRESH"] = "30s"

			_, err := Load(envOf(env))
			if err == nil {
				t.Fatalf("OBS_POLL(%s) >= OBS_FRESH(30s) 를 받아들였다", poll)
			}
			if !strings.Contains(err.Error(), "OBS_POLL") || !strings.Contains(err.Error(), "OBS_FRESH") {
				t.Errorf("에러에 두 키 이름이 다 있어야 한다: %v", err)
			}
		})
	}
}

// URL 은 경계값이다. 오구성이면 폴러가 조용히 실패만 반복하므로 기동 때 문법을 본다.
func TestLoadRejectsMalformedMTXAPIURL(t *testing.T) {
	// 뒤 여섯은 query·fragment 갈래다 — poller 가 베이스 URL 뒤에 "/v3/paths/list" 를
	// 문자열로 붙이므로 `#typo` 는 경로를 "/" 로 잘라내고 `?x=1` 은 경로를 쿼리 안으로
	// 밀어 넣는다. 둘 다 폴이 영구 실패하고 증상은 "스캔 유입(ⓐ2)의 되감기가 안 된다"뿐이다.
	// 값 없는 `#`·`?` 만 붙은 형태도 같은 고장이다 — 특히 빈 fragment 는 url.Parse 가
	// Fragment="" 로 삼키므로 파싱 결과가 아니라 원문으로만 잡힌다(r5 cx 지적).
	for _, bad := range []string{
		"media:9997", "ftp://media:9997", "http://", "http://user:pw@media:9997",
		"http://media:9997#typo", "http://media:9997?x=1", "http://media:9997/#typo",
		"http://media:9997#", "http://media:9997/#", "http://media:9997?",
	} {
		t.Run(bad, func(t *testing.T) {
			env := minimalEnv()
			env["MTX_API_URL"] = bad

			if _, err := Load(envOf(env)); err == nil {
				t.Fatalf("MTX_API_URL=%q 를 받아들였다", bad)
			} else if !strings.Contains(err.Error(), "MTX_API_URL") {
				t.Errorf("에러에 키 이름이 없다: %v", err)
			}
		})
	}
}
