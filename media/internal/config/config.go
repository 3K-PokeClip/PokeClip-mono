// Package config 는 환경변수를 읽어 설정으로 만든다.
//
// 인식하는 환경변수 전수 목록은 media/README.md 와 docker-compose.yml 의 서비스 블록 주석에
// 이름·기본값·의미와 함께 적혀 있다. 코드를 읽지 않고도 무엇을 켤 수 있는지 알 수 있어야 한다.
package config

import (
	"errors"
	"fmt"
	"log/slog"
	"math"
	"net"
	"net/url"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/indexer"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxhook"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxstate"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgconf"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/upload"
)

// Config 는 사이드카가 기동하는 데 필요한 전부다.
type Config struct {
	// PG 는 DB 접속 설정이다(POSTGRES_* · AWS_REGION — pgconf 가 janitor 와 같은 규칙으로 읽는다). 비밀번호는 비공개
	// 필드라 로그 · 오류에 실리지 않는다. 풀 설정은 main 이 pgconf.PoolConfig 로 만든다.
	PG           pgconf.Settings
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
	// RewindPublicBaseURL 은 되감기 목록의 조각 · MAP URI 앞머리다(REWIND_PUBLIC_BASE_URL — 계획 B-9). 끝에 '/' 가 없는
	// 절대 URL 이다(https — http 는 루프백 IP 리터럴만). S3 와 같은 블록에서만 읽는다 — S3.Bucket 이 비면 읽지 않아
	// 빈 값이고, 버킷이 있는데 비었으면 기동을 거부한다. 발행 조립이 publish.DefaultOptions 로 넘긴다(커밋 7).
	RewindPublicBaseURL string
	// RewindPublishPoolMaxConns 는 되감기 발행자 풀의 연결 상한이다(REWIND_PUBLISH_POOL_MAX_CONNS — kty 결정 C2 · 기본 4). pgx
	// 기본값(max(4, CPU 수))이면 상자가 커질수록 교체 창의 두 사이드카 합이 media_indexer 역할 상한을 넘는다. S3 와 같은
	// 블록에서만 읽는다 — 버킷이 비면 발행자 풀이 없어 0 이다.
	RewindPublishPoolMaxConns int32
	// RewindWatchEvery 는 되감기 30초 감시(설계 4.1 (a) (c))의 주기다 — 발행 루프가 이 값 ÷ 보류 틱(Indexer.HoldTick)
	// 마다 감시 작업을 낸다(판단 J85 ⑴). 설계값이라 env 가 없고 값의 집은 여기 하나다(ReconnectWindow 와 같은 규칙).
	RewindWatchEvery time.Duration
	// RewindGapRunEnd 는 L3 스위치다(REWIND_GAP_RUN_END — POK-196 M6 6절 · kty K5). 켜면 소유 회차에서 L2 GAP 이 seq 로
	// 이어서 셋 서면 그 회차를 ending(사유 gap_run)으로 보낸다. 기본 끔이고 기동 때만 읽는다 — 켜는 조건은 F-7 실측과 S3
	// 짧은 장애 주입 실측이 둘 다 참이고 kty 가 승인한 뒤다(M6 10절).
	RewindGapRunEnd bool
	// RewindStallSeal 은 L2′ 스위치다(REWIND_STALL_SEAL — POK-196 M6 6절 · kty K5 · 2026-10-27 결정 (나)). 켜면 live 소유
	// 회차의 stallAge 가 D_act 에 닿았는데 L2 가 설 수 없을 때 목록을 봉인하고 그 회차를 ending(사유 stall_seal)으로 보낸다.
	// 기본 끔이고 기동 때만 읽는다 — 켜는 조건은 L3 와 같다(M6 10절).
	RewindStallSeal bool
	// RewindPlaybackReadMax 는 상태 읽기 차선의 전체 동시 상한이다(REWIND_PLAYBACK_READ_MAX — kty 2026-10-29 · 정산 걸음 상한
	// finalize.DefaultLimits 의 전체 2 와 같은 꼴). 읽기 하나가 인덱서 풀 연결 하나를 쥐어, 일제 종료 때 스트림 수만큼 읽기가
	// 겹쳐 핫 경로와 풀을 다투지 않게 한다. 넘친 표지는 다음 holdTick 에 다시 낸다.
	RewindPlaybackReadMax int

	// HookSpoolPath 는 MediaMTX 훅이 한 줄씩 덧붙이는 공유 파일이다.
	//
	// **빈 문자열이 기본값이고, 빈 값이면 훅 어댑터를 아예 기동하지 않는다.**
	// 이것이 즉시 롤백 스위치이므로 withDefault 를 쓰면 안 된다 — 그 헬퍼는 빈 값을
	// "미설정"으로 보고 fallback 을 돌려주므로 HOOK_SPOOL_PATH="" 로 끄는 길이 막힌다.
	HookSpoolPath string
	// HookPollInterval 은 스풀 tail 주기다.
	HookPollInterval time.Duration
	// 세션 경계 판정의 Guard 는 Indexer.BreakGuard 하나에만 둔다(값의 집이 둘이면 어긋난다).

	// MTXState 는 MediaMTX Control API 관측 폴러의 설정이다(POK-195 M3).
	//
	// **APIURL 이 비면 폴러를 만들지 않는다** — 관측이 없으면 두 가지가 멈추고
	// 인덱싱·업로드는 그대로 돈다. 상태 방증 ⓐ2 가 fail-closed 되어 스캔 유입(ⓐ2) 주조가
	// 멈추고, 종료 판정이 신선한 관측을 요구하므로 4.1 종료 전이(M4 ⓒ)도 서지 않아 같은
	// 스트림의 연속 방송이 한 회차로 이어진다. HOOK_SPOOL_PATH 와 같은 형태의 즉시 롤백 스위치다.
	//
	// User 는 여기서 채우고 Pass 는 비워 둔다(POK-373) — 비밀번호는 MTXAPIPassFile 의 내용이고,
	// 설정은 파일을 열지 않으므로 main 이 읽어 채운다.
	MTXState mtxstate.Options
	// MTXAPIPassFile 은 Control API Basic 비밀번호 파일의 절대 경로다(MTX_API_PASS_FILE). 빈 값이면
	// 무인증이다. 경로 문자열만 검사했고 파일은 열지 않았다 — 읽기는 main 이 한다(Upload.Root 와 같은 규칙).
	MTXAPIPassFile string
	// ObsFresh·ObsBackfill 은 상태 방증 ⓐ2 의 시간 항이다(설계 6.5.2):
	// 관측이 이보다 낡으면 방증으로 쓰지 않고(ObsFresh), 관측 시점보다 이보다 더 과거인
	// 조각은 백로그 머리로 보아 주조하지 않는다(ObsBackfill). 판정은 indexer 가 한다.
	ObsFresh    time.Duration
	ObsBackfill time.Duration
	// SessionFloorSlack 은 세션 귀속 하한의 여유다(설계 5.4.1 ⑴ — 시계 역행 방어).
	// 판정은 session 레지스트리가 한다.
	SessionFloorSlack time.Duration
	// ReconnectWindow 는 재접속 계승 창이다 — 직전 회차 마지막 조각 끝에서 이 시간 안에 새 조각이
	// 오면 새 회차가 직전 회차를 물려받는다(ADR-044 결정 3 · kty ⑷). 설계값이라 env 가 없고 값의 집은
	// 여기 하나다. 판정은 session 레지스트리가 한다(조립점이 옮겨 담는다).
	ReconnectWindow time.Duration

	// Control 은 되감기 제어면 서버의 설정이다(CONTROL_* — 설계 M5 3.4 · 3.7).
	Control ControlOptions
	// Expiry 는 60일 만료의 설정이다(CLIP_INTERNAL_URL · CLIP_EXPIRY_TOKEN[_FILE] — 설계 M6 7절).
	Expiry ExpiryOptions
	// ResolveURL · ResolveTokenFile 은 사이드카의 4C resolve 설정이다(STREAM_KEY_RESOLVE_URL ·
	// STREAM_KEY_RESOLVE_TOKEN_FILE — POK-472). 둘 다 빈 값이면 4D 수신부를 등록하지 않는다. 토큰 파일은 경로 문자열만
	// 검사했고 열지 않았다 — 읽기는 main 이 한다(MTXAPIPassFile 과 같은 규칙).
	ResolveURL       string
	ResolveTokenFile string
	// Lifecycle 은 계약9 발행기의 설정이다(BROADCAST_TOPIC_ARN · LIFECYCLE_PUBLISH_STREAMS — POK-196 M6-8).
	Lifecycle LifecycleOptions
}

// 관측 축 기본값(설계 5.4.1·6.5.2). 판정 주체(indexer·session)는 이 값을 설정으로 받으며
// 리터럴을 그쪽에 또 적지 않는다 — 값의 집이 둘이면 언젠가 어긋난다.
// 폴러 자신의 두 값(OBS_POLL·OBS_BOOT_WAIT)은 mtxstate 가 집이라 여기서 다시 적지 않는다.
const (
	defaultObsFresh          = 30 * time.Second
	defaultObsBackfill       = 60 * time.Second
	defaultSessionFloorSlack = 1 * time.Second
)

// defaultRewindPlaybackReadMax 는 상태 읽기 차선의 전체 동시 상한 기본값이다(kty 2026-10-29 — 정산 걸음 전체 2 와 같다).
const defaultRewindPlaybackReadMax = 2

// defaultRewindPublishPoolMaxConns 는 되감기 발행자 풀 상한의 기본값이다(kty 결정 C2 — 2 vCPU 상자의 pgx 기본값과 같은 4).
const defaultRewindPublishPoolMaxConns = 4

// Load 는 환경변수를 읽어 설정을 만든다. env 는 os.Getenv 를 그대로 넘기면 된다.
//
// 필수 값이 없거나 빈 문자열이면 즉시 에러다. 빈 문자열을 "설정됨"으로 보면
// 비밀번호 없이 DB 에 붙으려다 원인 불명으로 실패한다.
func Load(env func(string) string) (Config, error) {
	// DB 설정 규칙(필수 값 · 기본값 · 로그인 방식)은 pgconf 한 곳이다 — janitor 와 같은 값을 같은 뜻으로 읽는다.
	pg, err := pgconf.FromEnv(env)
	if err != nil {
		return Config{}, err
	}

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

	// --- POK-30 업로더 (env 9개) ---
	//
	// Root 는 여기서 열지 않는다. 설정은 값만 읽고, 파일 시스템 손잡이는 main 이 만든다.
	up := upload.DefaultOptions(nil, watch.Root)
	s3 := upload.S3Options{Region: withDefault(env, "AWS_REGION", "ap-northeast-2")}
	var rewindBaseURL string
	var publishPoolMaxConns int32

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
		// 버킷이 있으면 되감기 목록도 발행하므로 URI 앞머리가 있어야 한다(계획 B-9 — 비면 기동 거부).
		// **TrimSpace 를 쓰지 않는다** — 끝의 CR · U+2028 을 조용히 떼면 제어 문자 · 비ASCII 거부가 무력해진다.
		if rewindBaseURL, err = validateRewindPublicBaseURL(env("REWIND_PUBLIC_BASE_URL")); err != nil {
			return Config{}, err
		}
		if publishPoolMaxConns, err = poolMaxConns(env, "REWIND_PUBLISH_POOL_MAX_CONNS", defaultRewindPublishPoolMaxConns); err != nil {
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
	// ③ 체제 플래그(POK-168 M2 — ADR-062 · 계약 6항 2단계). 기본 켜짐이다 — 되감기 발행과 함께 켠다
	// (kty 결정 2026-09-02): 각 스트림의 첫 주조 자격 유입이 컷오프를 만든다. 명시 false 는 그대로 받는다 —
	// 롤백 손잡이다(끄면 새 컷오프 주조만 멈추고 이미 기록된 컷오프는 유효하다).
	if idx.SeedEnabled, err = boolean(env, "REWIND_SEED_ENABLED", true); err != nil {
		return Config{}, err
	}
	gapRunEnd, err := boolean(env, "REWIND_GAP_RUN_END", false)
	if err != nil {
		return Config{}, err
	}
	stallSeal, err := boolean(env, "REWIND_STALL_SEAL", false)
	if err != nil {
		return Config{}, err
	}
	playbackReadMax, err := positiveInt(env, "REWIND_PLAYBACK_READ_MAX", defaultRewindPlaybackReadMax)
	if err != nil {
		return Config{}, err
	}
	if idx.ScanCollectBudget, err = duration(env, "SCAN_COLLECT_BUDGET", idx.ScanCollectBudget); err != nil {
		return Config{}, err
	}
	// 스위퍼 arm 폴백의 정본 산식(설계 11.3): max(2×RescanEvery, 2×ScanCollectBudget).
	// 하한이 필요한 이유 — RescanEvery 는 설정값이라 30초로 낮추면 폴백 60초가 정상 첫
	// 수집(예산 45초)이 끝나기도 전에 무조건 arm 을 돌려 원 상태를 기본 경로에서 재현한다.
	up.ArmFallback = max(2*watch.RescanEvery, 2*idx.ScanCollectBudget)

	// --- 관측 축 (POK-195 M3 — 설계 5.4.1·6.5.2, env 6종 + 자격 env 2종 — POK-373) ---
	//
	// MTX_API_URL 은 **withDefault 를 쓰지 않는다**(위 필드 주석 참조). 빈 값이
	// "관측 끔"이라는 뜻을 가진 값이므로 fallback 으로 채우면 그 스위치가 고장난다.
	mtx := mtxstate.Options{APIURL: strings.TrimSpace(env("MTX_API_URL"))}
	mtxHost, err := validateMTXAPIURL(mtx.APIURL)
	if err != nil {
		return Config{}, err
	}
	mtxAuth, err := loadMTXAPIAuth(env, mtxHost)
	if err != nil {
		return Config{}, err
	}
	mtx.User = mtxAuth.user
	if mtx.PollInterval, err = duration(env, "OBS_POLL", mtxstate.DefaultPollInterval); err != nil {
		return Config{}, err
	}
	if mtx.BootWait, err = duration(env, "OBS_BOOT_WAIT", mtxstate.DefaultBootWait); err != nil {
		return Config{}, err
	}
	obsFresh, err := duration(env, "OBS_FRESH", defaultObsFresh)
	if err != nil {
		return Config{}, err
	}
	obsBackfill, err := duration(env, "OBS_BACKFILL", defaultObsBackfill)
	if err != nil {
		return Config{}, err
	}
	// 관측이 꺼져 있어도(APIURL 빈 값) 값 검증은 건너뛰지 않는다 — SESSION_FLOOR_SLACK 은
	// 관측과 무관하게 세션 귀속 판정이 늘 쓰고, 나머지(OBS_*)도 오타를 켜는 날에야 알게 되면
	// "스캔 유입(ⓐ2)의 주조가 왜 안 되지"라는 무징후 증상으로 나타난다(ⓐ1 워처·훅 유입은 OBS_* 와 무관).
	sessionFloorSlack, err := duration(env, "SESSION_FLOOR_SLACK", defaultSessionFloorSlack)
	if err != nil {
		return Config{}, err
	}

	// ⓐ2 판정은 indexer 가 한다(계획 3절) — 두 창을 그쪽 Options 로 옮겨 담는다.
	// 값의 집은 여기 하나이고 저쪽은 받는 자리다(idx.TailGrace 와 같은 규칙).
	idx.ObsFresh = obsFresh
	idx.ObsBackfill = obsBackfill

	// 교차 검증 5 는 여기 없다 — OBS_FRESH 의 다른 하한(주조 트랜잭션 상한)은 index 패키지의
	// 상수와 비교해야 하는데 이 층은 그 패키지를 보지 않는다. 상수 사본을 두면 두 곳이 갈리므로
	// 조립 지점(cmd/segment-indexer/main.go 의 validateObservationWindow)이 그 검증을 진다.
	//
	// 교차 검증 4 — 폴 주기가 신선도 창 이상이면 다음 관측이 오기 전에 직전 관측이 만료돼
	// 신선한 관측이 끊기지 않고 존재할 수 없다. 폴은 성공하는데 스캔 유입(ⓐ2) 주조가 안 되고,
	// 4.1 종료 판정의 기다림도 관측이 끊길 때마다 지워져 종료 전이가 늦거나 서지 않을 수 있다.
	// 둘 다 로그에 드러나지 않는다. **역은 성립하지 않는다** — 짧다고 신선이 보장되지는 않는다.
	if mtx.PollInterval >= obsFresh {
		return Config{}, fmt.Errorf(
			"OBS_POLL(%v)은 OBS_FRESH(%v)보다 짧아야 한다 — 같거나 길면 다음 관측이 오기 전에 직전 "+
				"관측이 만료돼 ⓐ2 가 사실상 영구 불성립이다. 보증은 '신선한 관측이 존재할 수 있다'까지다: "+
				"짧아도 실패 폴 1 회가 상한을 소진하면 연속 성공 관측 간격이 최대 2×OBS_POLL 까지 벌어져 "+
				"그 창에서는 간헐 탈락한다(방향은 비주조라 안전)",
			mtx.PollInterval, obsFresh)
	}

	control, err := loadControl(env)
	if err != nil {
		return Config{}, err
	}
	expiryOpt, err := loadExpiry(env)
	if err != nil {
		return Config{}, err
	}
	resolve, err := loadResolve(env)
	if err != nil {
		return Config{}, err
	}
	lifecycle, err := loadLifecycle(env)
	if err != nil {
		return Config{}, err
	}

	return Config{
		PG:               pg,
		SegmentRoot:      watch.Root,
		S3:               s3,
		Upload:           up,
		EnsureSchema:     ensureSchema,
		LogLevel:         logLevel,
		Indexer:          idx,
		Watcher:          watch,
		HookSpoolPath:    hookSpoolPath,
		HookPollInterval: hookPollInterval,

		MTXState:          mtx,
		MTXAPIPassFile:    mtxAuth.passFile,
		ObsFresh:          obsFresh,
		ObsBackfill:       obsBackfill,
		SessionFloorSlack: sessionFloorSlack,
		// 설계값(ADR-044 결정 3 — IVS 300초 선례)이라 const 로 선언하지 않고 여기 한 자리에 적는다(계획 5절).
		ReconnectWindow: 300 * time.Second,
		// 설계값(계획 5절 「30 tick 감시」 · 판단 J85 ⑴)이라 같은 규칙으로 여기 한 자리에 적는다.
		RewindWatchEvery:      30 * time.Second,
		RewindGapRunEnd:       gapRunEnd,
		RewindStallSeal:       stallSeal,
		RewindPlaybackReadMax: playbackReadMax,
		// S3_BUCKET 이 비면 읽지 않아 빈 값이다(계획 B-9).
		RewindPublicBaseURL:       rewindBaseURL,
		RewindPublishPoolMaxConns: publishPoolMaxConns,
		Control:                   control,
		Expiry:                    expiryOpt,
		ResolveURL:                resolve.url,
		ResolveTokenFile:          resolve.tokenFile,
		Lifecycle:                 lifecycle,
	}, nil
}

// validateRewindPublicBaseURL 은 되감기 목록의 URI 앞머리 raw 를 기동 때 보고, 끝 '/' 하나를 뗀 값을 돌려준다(계획 B-9
// 확정 규칙). 이 값은 목록의 조각 · MAP URI 줄에 그대로 실려(앞머리 + "/" + 키) 시청자에게 가므로 문법을 좁게 본다:
//
//	https 만 받는다 — http 는 호스트가 루프백 IP 리터럴(127.0.0.0/8 · ::1)일 때만(hls.js 실재생 리그). localhost 는
//	이름 해석을 거쳐 예외가 아니다. 호스트가 있어야 하고 userinfo · query · fragment · 제어 문자 · 공백 · 따옴표 ·
//	역슬래시 · 비ASCII 는 거부한다. 포트는 1–65535 만 받는다. 끝 '/' 는 하나까지만 떼고 둘 이상이면 거부한다. 경로는
//	받되 dot 세그먼트(`.` · `..` — 퍼센트 인코딩 꼴 포함)는 거부한다 — 표준 URL 파서는 셋 다 받아 주지만 시청자 쪽에서
//	다른 자리를 가리킨다(종결 라운드 1 CL1-3).
//
// 부르는 자리는 S3_BUCKET 이 있을 때뿐이라 빈 값도 오류다(fail-closed). 원문에 비밀이 들어 있을 수 있는 갈래(userinfo ·
// query · fragment · 호스트 없음 — `//` 가 빠진 꼴은 url.Parse 가 User 를 비운다)는 오류 문구에 원문을 싣지 않는다
// (S3_ENDPOINT 선례 · 종결 라운드 1 CL1-2). 요청 헤더에서 값을 만들지 않는다(사이드카에는 HTTP 입력이 없다).
func validateRewindPublicBaseURL(raw string) (string, error) {
	if raw == "" {
		return "", fmt.Errorf("REWIND_PUBLIC_BASE_URL 이 비었다 — S3_BUCKET 을 설정하면 되감기 목록의 조각 URI 앞머리가 있어야 한다")
	}
	for i := 0; i < len(raw); i++ {
		if c := raw[i]; c <= ' ' || c == 0x7f || c == '"' || c == '\\' || c >= 0x80 {
			return "", fmt.Errorf("REWIND_PUBLIC_BASE_URL 의 %d번째 바이트 %#x 는 쓸 수 없다 — 제어 문자 · 공백 · 따옴표 · 역슬래시 · 비ASCII 는 목록 URI 줄에 그대로 실린다", i, c)
		}
	}
	u, err := url.Parse(raw)
	if err != nil {
		return "", fmt.Errorf("REWIND_PUBLIC_BASE_URL 을 URL 로 읽을 수 없다 — scheme 과 호스트를 포함한 절대 URL 이어야 한다")
	}
	if u.User != nil {
		return "", fmt.Errorf("REWIND_PUBLIC_BASE_URL 에 자격증명(user:pass@)을 넣을 수 없다 — 목록 URI 로 시청자에게 나간다")
	}
	// `?` · `#` 는 원문으로 본다 — 값 없는 `#` 은 url.Parse 가 삼켜 파싱 결과로는 구분되지 않는다(validateMTXAPIURL 과
	// 같다). 거부 문구는 어느 갈래도 원문을 싣지 않는다(종결 라운드 1 CL1-2 · CL1-3) — 질의의 서명 토큰 · opaque 꼴의
	// 비밀번호가 기동 오류 로그에 남을 수 있고, 운영자는 어느 변수인지와 위반 종류만 알면 고칠 수 있다. 위치를 가리킬
	// 값은 비밀이 들 수 없는 조각(포트 · scheme)만 싣는다.
	switch {
	case strings.ContainsAny(raw, "?#"):
		return "", fmt.Errorf("REWIND_PUBLIC_BASE_URL 에 query(?)·fragment(#)를 넣을 수 없다 — 뒤에 \"/\" + 키가 붙는다")
	case u.Host == "" || u.Hostname() == "":
		return "", fmt.Errorf("REWIND_PUBLIC_BASE_URL 은 scheme 과 호스트를 포함한 URL(scheme://호스트)이어야 한다")
	case !validPort(u.Port()):
		return "", fmt.Errorf("REWIND_PUBLIC_BASE_URL 의 포트(%s)는 1–65535 여야 한다", u.Port())
	case hasDotSegment(u.Path):
		return "", fmt.Errorf("REWIND_PUBLIC_BASE_URL 의 경로에 dot 세그먼트(. · ..)를 넣을 수 없다 — 시청자 쪽에서 다른 경로로 풀린다")
	case u.Scheme != "https" && !(u.Scheme == "http" && isLoopbackIP(u.Hostname())):
		return "", fmt.Errorf("REWIND_PUBLIC_BASE_URL 의 scheme(%s)은 https 여야 한다 — http 는 루프백 IP 리터럴(127.0.0.0/8 · ::1)만 받는다", u.Scheme)
	case strings.HasSuffix(raw, "//"):
		return "", fmt.Errorf("REWIND_PUBLIC_BASE_URL 이 '/' 둘 이상으로 끝난다 — 끝 '/' 는 하나까지만 뗀다")
	}
	return strings.TrimSuffix(raw, "/"), nil
}

// validPort 는 URL 의 포트 칸이 비었거나(scheme 기본) 1–65535 인가다. url.Parse 는 숫자만 보고 범위는 보지 않는다.
func validPort(port string) bool {
	if port == "" {
		return true
	}
	n, err := strconv.Atoi(port)
	return err == nil && n >= 1 && n <= 65535
}

// hasDotSegment 는 디코딩된 경로에 dot 세그먼트(`.` · `..`)가 있는가다 — url.Parse 가 퍼센트 인코딩(`%2e`)을 풀어 둔
// 경로를 받으므로 인코딩 꼴도 잡힌다.
func hasDotSegment(path string) bool {
	for _, seg := range strings.Split(path, "/") {
		if seg == "." || seg == ".." {
			return true
		}
	}
	return false
}

// isLoopbackIP 는 host 가 루프백 IP 리터럴(127.0.0.0/8 · ::1)인가다. 이름(localhost 등)은 해석을 거치므로 거짓이다.
func isLoopbackIP(host string) bool {
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

// validateMTXAPIURL 은 관측 폴러의 베이스 URL 문법을 기동 때 보고, 통과하면 호스트(포트 · 대괄호
// 없음)를 돌려준다 — 자격 규칙(IP 리터럴)이 그 값을 본다. 빈 값은 "관측 끔"이라 빈 호스트로 통과다.
//
// 여기서 안 잡으면 폴러가 매 주기 mtxstate_poll_failed 만 남기며 도는 상태가 되고,
// 그 증상은 "스캔 유입(ⓐ2)의 되감기가 안 된다"(ⓐ1 워처·훅 유입은 계속된다)로 한참 뒤에 나타난다.
//
// **어느 에러에도 URL 원문을 싣지 않는다**(POK-373). 원문에 자격이 섞인 채 다른 갈래(예: `ftp://u:pw@…`
// 의 scheme)에 걸리면 그 에러가 stderr 로 나가 자격이 샌다. url.Parse 의 에러도 원문을 담으므로 싣지
// 않는다. 무엇이 틀렸는지만 적는다. 같은 이유로 자격 검사를 해석 직후 가장 먼저 둔다.
func validateMTXAPIURL(raw string) (string, error) {
	if raw == "" {
		return "", nil
	}
	u, err := url.Parse(raw)
	if err != nil {
		return "", errors.New("MTX_API_URL 을 URL 로 해석하지 못했다 — scheme 과 호스트를 포함한 URL 이어야 한다(예: http://127.0.0.1:9997)")
	}
	// URL 원문은 mtxstate_poll_failed 로그에 그대로 실린다. 자격증명을 넣으면 그 로그가
	// 유출 경로가 된다 — 자격 자리는 env 두 개다.
	if u.User != nil {
		return "", errors.New("MTX_API_URL 에 자격증명(user:pass@)을 넣을 수 없다 — 자격은 MTX_API_USER · MTX_API_PASS_FILE 로 넣는다")
	}
	// Hostname() 까지 본다 — "http://:9997" 은 Host 가 ":9997" 이라 비어 있지 않지만
	// 호스트가 없다(S3_ENDPOINT 와 같은 함정).
	if u.Host == "" || u.Hostname() == "" {
		return "", errors.New("MTX_API_URL 은 scheme 과 호스트를 포함한 URL 이어야 한다(예: http://127.0.0.1:9997)")
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return "", errors.New("MTX_API_URL 의 scheme 은 http 또는 https 여야 한다")
	}
	// 폴러는 이 값 뒤에 경로를 문자열로 잇는다(mtxstate/poller.go). 그래서 `#` 은 뒤를
	// 통째로 fragment 로 잘라 요청 경로를 "/" 로 만들고, `?` 는 경로를 쿼리 안으로 밀어 넣는다.
	// 둘 다 폴이 매 주기 실패하기만 하는 상태라 문법 단계에서 끊는다.
	// 파싱 결과가 아니라 **원문**을 본다 — "http://media:9997#" 처럼 값 없는 `#` 은
	// url.Parse 가 Fragment="" 로 삼켜 파싱 결과로는 구분되지 않는다(`?` 의 ForceQuery 같은
	// 표식이 fragment 에는 없다). 베이스 URL 원문에 두 글자 중 하나라도 있으면 그 자체가 오구성이다.
	if strings.ContainsAny(raw, "?#") {
		return "", errors.New("MTX_API_URL 에 query(?)·fragment(#)를 넣을 수 없다 — 베이스 URL 뒤에 /v3/paths/list 가 붙는다")
	}
	return u.Hostname(), nil
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

// poolMaxConns 는 풀 연결 상한이다 — 양의 정수이고 pgx 의 int32 칸에 들어가야 한다.
func poolMaxConns(env func(string) string, key string, fallback int32) (int32, error) {
	v, err := positiveInt(env, key, int(fallback))
	if err != nil {
		return 0, err
	}
	if v > math.MaxInt32 {
		return 0, fmt.Errorf("%s 는 %d 이하여야 한다: %d", key, math.MaxInt32, v)
	}
	return int32(v), nil
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
// S3_BUCKET 의 거부 문구도 입력 원문을 싣지 않는다 — 버킷 이름은 공개 식별자지만 이 파일의
// URL 변수들과 같은 형식으로 통일한다. 예약 접두·접미 값은 상수라 그대로 싣는다.
func validateS3(s3 upload.S3Options) error {
	if !bucketNameRe.MatchString(s3.Bucket) {
		return fmt.Errorf("S3_BUCKET 이 버킷 이름 규칙(소문자·숫자·점·하이픈 3-63자)에 맞지 않는다")
	}
	// 점 두 개 연속과 IP 형식은 S3 가 거부하는 이름이다.
	if strings.Contains(s3.Bucket, "..") || ipLikeRe.MatchString(s3.Bucket) {
		return fmt.Errorf("S3_BUCKET 은 연속된 점이나 IP 형식을 쓸 수 없다")
	}
	// AWS 가 예약한 접두·접미다. 통과시키면 CreateBucket 이 아니라 PUT 단계에서야 실패한다.
	for _, p := range bucketBannedPrefixes {
		if strings.HasPrefix(s3.Bucket, p) {
			return fmt.Errorf("S3_BUCKET 은 예약 접두 %q 를 쓸 수 없다", p)
		}
	}
	for _, sfx := range bucketBannedSuffixes {
		if strings.HasSuffix(s3.Bucket, sfx) {
			return fmt.Errorf("S3_BUCKET 은 예약 접미 %q 를 쓸 수 없다", sfx)
		}
	}
	if s3.Endpoint == "" {
		return nil
	}
	u, err := url.Parse(s3.Endpoint)
	// Hostname() 까지 본다 — "http://:9000" 은 Host 가 ":9000" 이라 비어 있지 않지만
	// 호스트가 없다. SDK 는 그대로 들고 가 연결 단계에서야 실패한다.
	if err != nil || u.Host == "" || u.Hostname() == "" {
		return fmt.Errorf("S3_ENDPOINT 는 scheme 과 호스트를 포함한 URL 이어야 한다")
	}
	// SDK 는 이상한 scheme 을 조용히 삼킨다. 우리가 쓰는 것은 둘뿐이다.
	if u.Scheme != "http" && u.Scheme != "https" {
		return fmt.Errorf("S3_ENDPOINT 의 scheme(%s)은 http 또는 https 여야 한다", u.Scheme)
	}
	// userinfo 거부 — endpoint 원문은 uploader_started 로그와 SDK 오류 문자열에 그대로
	// 실린다. URL 에 자격증명을 넣으면 그 로그가 유출 경로가 된다. 자격증명 자리는 env
	// 3종뿐이다. 세 갈래의 거부 문구는 원문을 싣지 않는다(validateRewindPublicBaseURL 과
	// 같다 — 원문을 실으면 opaque 꼴 비밀번호 · 질의 토큰이 기동 로그에 남을 수 있어
	// 생략한다) · scheme 자리에 비밀을 넣는 운영은 없다고 본다.
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
