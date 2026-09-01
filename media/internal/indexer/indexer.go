// Package indexer 는 두뇌다. 세그먼트에 몇 번(seq)을 주고 재생 위치(start_pts_ms)를
// 얼마로 할지 결정하는 정책이 오직 여기에만 있다.
//
// 대신 강한 제약이 하나 붙는다 — Scan·Handle 은 반드시 동일한 단일 고루틴에서만
// 호출해야 한다(cmd/segment-indexer 의 main 루프). 이 규약을 깨면 seq 중복이 발생한다.
// 락 대신 "호출자를 한 명으로 제한한다"는 계약으로 해결했다(D10). 락은 코드를 복잡하게
// 만들고 버그도 조용하지만, 호출 지점이 한 곳이면 눈으로 검증된다.
package indexer

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"math"
	"os"
	"slices"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/fmp4meta"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/fsop"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// Adopter 는 인덱서가 워처에게 파일을 되돌려 줄 때 쓰는 유일한 통로다.
//
// 인터페이스를 recording 이 아니라 쓰는 쪽인 여기에 둔 이유: recording.Watcher 는 구조체
// 그대로이며 병행 구현을 만들지 않는다. 이 메서드 1개짜리 선언은 인덱서 테스트가 워처
// 전체를 띄우지 않아도 되게 하는 이음매일 뿐이다. *recording.Watcher 가 그대로 만족한다.
type Adopter interface {
	Adopt(seg recording.Segment)
}

// UploadRequester 는 인덱서가 업로더에게 일을 넘기는 유일한 통로다.
//
// 인터페이스를 쓰는 쪽인 여기에 둔 이유는 Adopter 와 같다. *upload.Uploader 가 그대로
// 만족하며, 그래서 indexer 는 upload 패키지를 임포트하지 않는다 — "이 조각이 지금 올릴
// 대상인가"의 판정(여기)과 "어떻게 올리는가"의 정책(거기)은 변경 이유가 다르다.
//
// 반드시 논블로킹이어야 한다. 여기서 막히면 D10 메인 루프가 통째로 선다.
type UploadRequester interface {
	RequestUpload(t index.UploadTarget) (accepted bool)
}

// Options 는 두뇌의 판단 기준이 되는 숫자들이다.
type Options struct {
	// ExpectedDurationMS 는 세그먼트 1개의 기대 길이다.
	// mediamtx.yml 의 recordSegmentDuration 4s 와 손으로 맞춘 값이다 — 사이드카는 그 설정
	// 파일을 마운트하지 않으므로 실행 중에 대조할 방법이 없다.
	ExpectedDurationMS int64
	// SuspectBelowMS 보다 짧게 측정되면 덜 써진 파일을 읽은 게 아닐까 의심하고 다시 잰다(H6).
	// 학습으로 상향만 가능하다.
	SuspectBelowMS int64
	// DriftToleranceMS 를 넘게 어긋나면 끊긴 것으로 판정한다(H8).
	DriftToleranceMS int64
	// MaxSettleRounds 는 크기가 계속 변할 때 재측정을 몇 번까지 반복할지다.
	MaxSettleRounds int
	// ReprobeMaxAttempts 는 짧게 나온 길이를 몇 번까지 다시 잴지다.
	ReprobeMaxAttempts int
	// ReprobeFailStreak 만큼 연속 실패하면 그 스트림의 승격을 비활성화한다.
	ReprobeFailStreak int
	// LearnSampleCount 는 기준을 학습하는 데 쓸 표본 수다.
	LearnSampleCount int
	// InsertRetryMax 는 INSERT 재시도 상한이다(지수 백오프).
	InsertRetryMax int
	// PoisonStreakMax 는 같은 스트림에서 연달아 허용하는 poison INSERT 수다.
	// 한두 건은 그 행의 값 문제지만, 연달아 나면 전역 이상(스키마·인코딩 어긋남)이므로
	// 계속 건너뛰며 인덱스를 조용히 비워 가는 대신 프로세스를 끝낸다.
	PoisonStreakMax int
	// TailHold 는 유휴로 확정된 꼬리를 올리기 전에 기다리는 시간이다.
	// SettleWait 이상이어야 한다 — 더 짧으면 안정 판정이 끝나기도 전에 올린다.
	TailHold time.Duration
	// TailGrace 는 인덱서가 꼬리를 붙들 수 있는 상한이다. 이 시각을 넘긴 꼬리는
	// 스위퍼의 꼬리 예외가 집는다.
	//
	// 값의 소유자는 upload.Options 이고 config 가 그 값을 여기에 넣어 준다. 그럼에도
	// 여기에 같은 기본값을 두는 이유: 0 이면 eligibleAt == StartWallUTC 가 되어
	// Idle·Scan 꼬리가 TailHold 를 채우기도 전에 첫 틱에서 폐기된다. config 를 거치지
	// 않는 호출자에게 그 조합은 "업로드 요청을 조용히 잃는" 자체 모순이다(결정 4⁵·5⁵).
	TailGrace time.Duration
	// HoldTick 은 ReleaseHeldTails 를 부르는 주기다.
	// 초 단위여야 한다 — 30s 급이면 마지막 조각의 총 지연이 48초까지 늘어 AC1 재현성이 깨진다.
	HoldTick time.Duration
	// HoldStatBudget 은 한 틱에서 쓰는 os.Stat 예산이다.
	// upload 가 아니라 여기가 소유한다 — ReleaseHeldTails 가 인덱서의 메서드이고,
	// 저쪽에 두면 인덱서가 upload 를 임포트해야 해 위 계약이 깨진다.
	HoldStatBudget int
	// InsertRetryBase 는 지수 백오프의 첫 간격이다.
	// 기본 2s + InsertRetryMax 5회 = 시도 사이 간격 2+4+8+16 = 정확히 30s(설계 2.4절 "총 ~30s").
	InsertRetryBase time.Duration
	// IdleTimeout 은 워처와 같은 값을 쓴다. H4(유휴 커밋 전 mtime 재검)와
	// Scan(d)(최신 파일 분기)의 판정 기준이다.
	IdleTimeout time.Duration
	// Settle 은 H5 와 correctTail 이 크기 안정을 기다릴 때 쓰는 설정이다.
	Settle recording.SettleOptions
	// BreakGuard 는 "새 세션의 첫 조각인가"를 판정할 때의 하한 여유다.
	// seg.StartWall >= OnlineAt - BreakGuard 를 만족해야 그 경계를 소비한다.
	//
	// 20ms 는 실측 짝짓기 오차 ±2ms 의 10배다. 파일명 시각 해상도와 훅 기록 시각의 미세
	// 차이만 흡수하며 그 이상의 의미는 없다 — 크게 잡으면 이전 세션의 조각이 새 세션의
	// 표시를 가로챈다.
	BreakGuard time.Duration
	// SegmentRoot 는 훅이 준 세그먼트 경로를 정본화할 때 쓰는 기준 루트다(= SEGMENT_ROOT).
	// 워처와 같은 값이어야 두 채널이 같은 local_path 문자열을 만든다(GH7).
	// 기본값을 두지 않는다 — 워처 루트와 한 곳에서 맞추는 것이 config.Load 의 책임이다.
	SegmentRoot string
	// FSOpTimeout 은 개별 FS 호출(stat·프로브)의 워커 상한이다(m2 — 처리 FS 격리).
	// 멈춘 파일시스템에서 D10 루프가 이 시간 이상 잡히지 않게 하는 값이다.
	FSOpTimeout time.Duration
	// ScanCollectBudget 은 전수 수집(collectTree)의 soft 예산이다. 넘기면 절단하고
	// 걷은 데까지 처리한다 — 정지 판정(scan_collect_stalled)은 이 값 × k 로 holdTicks 가 한다.
	ScanCollectBudget time.Duration
}

// DefaultOptions 는 설계 2.4절의 기본값이다.
func DefaultOptions() Options {
	return Options{
		ExpectedDurationMS: 4000,
		SuspectBelowMS:     3850,
		DriftToleranceMS:   1500,
		MaxSettleRounds:    2,
		ReprobeMaxAttempts: 3,
		ReprobeFailStreak:  3,
		LearnSampleCount:   5,
		InsertRetryMax:     5,
		PoisonStreakMax:    5,
		InsertRetryBase:    2 * time.Second,
		TailHold:           5 * time.Second,
		TailGrace:          2 * time.Minute,
		HoldTick:           time.Second,
		HoldStatBudget:     8,
		IdleTimeout:        10 * time.Second,
		BreakGuard:         20 * time.Millisecond,
		Settle:             recording.DefaultSettleOptions(),
		FSOpTimeout:        fsop.DefaultOpTimeout,
		ScanCollectBudget:  45 * time.Second,
	}
}

// Indexer 는 고루틴 안전하지 않다. D10 단일 호출자 규약이 락을 대신한다.
type Indexer struct {
	store  index.Store
	probe  fmp4meta.DurationProbe
	adopt  Adopter
	upload UploadRequester
	opt    Options
	log    *slog.Logger

	// fsLatch 는 FS 열화 래치다(m2). loop 단일 고루틴만 만진다 — D10 규약이 락을 대신한다.
	fsLatch *fsop.Latch
	// statFn·probeFn 은 fsop 워커 경유의 기본값을 담는 주입점이다. 교체는 테스트뿐이다.
	statFn  func(string) (os.FileInfo, error)
	probeFn func(string) (int64, error)

	// 아래 상태는 전부 스트림별이다. 하나라도 전역으로 뭉개면 다중 스트림에서 조용히 틀린다.
	cursors map[string]*index.Cursor
	// indexed 는 이미 기록한 경로의 메모리 이력이다.
	//
	// TODO(C3): 정리 정책 없음. 트리거는 "단일 프로세스 장기 실행"이며 약 1.3MB/일/스트림 자란다.
	// Scan 재호출 시 ExistingPaths 결과로 맵을 교체하지만, 그것이 C3 를 해소하지는 않는다 —
	// DB 행 자체가 계속 늘면 맵도 늘기 때문이다.
	indexed map[string]map[string]struct{}

	samples         map[string][]int64
	learnedMS       map[string]int64
	failStreak      map[string]int
	reprobeDisabled map[string]bool
	poisonStreak    map[string]int
	// warnedRejected 는 화이트리스트를 통과하지 못한 디렉토리에 대해 WARN 을
	// 한 번만 내기 위한 표시다. 재스캔은 5분마다 도는데 매번 경고하면 소음이 된다.
	warnedRejected map[string]bool

	// held 는 아직 올리지 않고 붙들고 있는 꼬리다(스트림당 최대 1개 — 꼬리는 하나뿐이다).
	held map[string]heldTail
	// requested 는 업로더에게 넘긴 꼬리의 seq 다. 확인 2.5 가 이 값을 본다.
	requested map[string]int64

	// --- 훅 세션 경계 상태(ADR-027). 전부 프로세스 메모리에만 있다. ---
	//
	// 세 맵의 키 계약: **세션 훅의 MTX_PATH 원문 문자열**이며, 소비 측 peekBreak 의 조회 키는
	// seg.StreamID(= recording.ParseSegmentPath 가 파일 경로의 디렉토리 부분에서 뽑은 값)다.
	// 두 문자열이 바이트 동일해야 GH1 이 동작한다 — 하나라도 어긋나면 무장은 되지만 영원히
	// 소비되지 않는다(조용한 미탐 + 큐 누수). 그래서 HandleHook 은 ev.StreamID 를
	// **정규화·변형하지 않는다**(Clean·Trim·소문자화 금지).
	//
	// 등식의 성립 근거는 단일 레벨 %path 전제다. recordPath 가 /recordings/%path/... 이므로
	// %path 가 곧 스트림 디렉토리 1레벨이고, name.go 의 화이트리스트가 슬래시를 허용하지 않아
	// 중첩 %path 는 애초에 인덱싱되지 않는다(= 새 실패 모드가 아니라 범위 밖).

	// --- 전수 수집 상태(collect.go). 채널만 워커와 공유하고 나머지는 loop 전용이다. ---

	collectDoneCh chan collectResult
	// collectInflight 는 단일 비행 표식이다. ApplyCollect 만 내린다(세대 토큰 불요의 근거).
	collectInflight bool
	collectStart    time.Time
	// collectStalledWarned 는 "한 시도에 stalled 한 번" 규칙이다. StartCollect 가 리셋한다.
	collectStalledWarned bool
	// firstCollectDone 은 첫 완주 여부다 — ApplyCollect 의 firstComplete 반환이 한 번만
	// 참이 되게 한다(스위퍼 arm 은 한 번이면 된다).
	firstCollectDone bool
	// handleCount 는 Handle 호출 누계다(f6n — 주기당 호출 수로 국면을 가른다).
	// 단일 고루틴(D10)이라 원자 연산이 필요 없다.
	handleCount int64

	// pendingOffline 은 아직 짝지어지지 않은 offline 훅이다(스트림별 1건, 더 늦은 것만 유지).
	pendingOffline map[string]sessionMark
	// lastOnlineAt 은 스트림별 online watermark 다 — 이보다 이른 offline 은 stale 로 버린다.
	lastOnlineAt map[string]time.Time
	// breaks 는 무장된 세션 경계 **큐**다(OnlineAt 오름차순).
	// 단일 포인터면 연쇄 재접속에서 앞 경계가 덮여 영구 미탐이 되므로 큐로 둔다.
	breaks map[string][]sessionBreak
}

// noAdopter 는 워처 부재(강등) 국면의 널 오브젝트다(m3 — ADR-063 결정 2).
// nil 대신 no-op 구현을 끼우면 호출점 개수와 무관하게 안전하다 — 호출점 열거는
// r15a 검증에서 두 번 깨졌다(1/3 · 4/14). 되돌림 실패 = 그 파일을 미기록으로 남김 →
// 다음 수집이 회수한다. 매 호출이 adopt_dropped_degraded 로 계수된다(f6e).
type noAdopter struct{ log *slog.Logger }

func (n noAdopter) Adopt(seg recording.Segment) {
	n.log.Warn("adopt_dropped_degraded", "stream_id", seg.StreamID, "path", seg.Path,
		"note", "워처 부재 — 미기록으로 남기고 다음 수집이 회수한다")
}

// SetAdopter 는 워처 복귀(재장착) 때 loop 이 부른다. nil 은 널 오브젝트로 편다(m3b —
// plain nil 한정: typed-nil 은 호출부의 인터페이스 변수 선언(겹 1)과 이 가드(겹 2)가 막고,
// setter 에서 일반적으로 잡으려면 리플렉션이 필요해 과설계다 — 닫지 않는다).
func (ix *Indexer) SetAdopter(a Adopter) {
	if a == nil {
		a = noAdopter{log: ix.log}
	}
	ix.adopt = a
}

// New 는 인덱서를 만든다. w 에는 *recording.Watcher 를, up 에는 *upload.Uploader 를 넘긴다.
// up 이 nil 이면 아무것도 요청하지 않는 기본값이, w 가 nil 이면(워처 강등) 널 오브젝트가
// 끼워진다(m3a) — 배선 전에도 인덱서는 그대로 돈다.
func New(store index.Store, probe fmp4meta.DurationProbe, w Adopter,
	up UploadRequester, opt Options, log *slog.Logger) *Indexer {
	if up == nil {
		up = noUploader{}
	}
	if w == nil {
		w = noAdopter{log: log}
	}
	if opt.FSOpTimeout <= 0 {
		// 0 이면 모든 FS 워커가 즉시 타임아웃해 처리 전체가 조용히 멈춘다.
		// config 를 거치지 않는 호출자에게 그 조합은 자체 모순이다(TailGrace 와 같은 규칙).
		opt.FSOpTimeout = fsop.DefaultOpTimeout
	}
	if opt.ScanCollectBudget <= 0 {
		// 0 이면 첫 항목에서 즉시 절단돼 수집이 영구 공전한다 — 같은 규칙으로 보정한다.
		opt.ScanCollectBudget = 45 * time.Second
	}
	ix := &Indexer{
		store:           store,
		probe:           probe,
		adopt:           w,
		upload:          up,
		opt:             opt,
		log:             log,
		cursors:         map[string]*index.Cursor{},
		indexed:         map[string]map[string]struct{}{},
		samples:         map[string][]int64{},
		learnedMS:       map[string]int64{},
		failStreak:      map[string]int{},
		reprobeDisabled: map[string]bool{},
		poisonStreak:    map[string]int{},
		warnedRejected:  map[string]bool{},
		held:            map[string]heldTail{},
		requested:       map[string]int64{},
		pendingOffline:  map[string]sessionMark{},
		lastOnlineAt:    map[string]time.Time{},
		breaks:          map[string][]sessionBreak{},
		collectDoneCh:   make(chan collectResult, 1),
	}
	ix.fsLatch = fsop.NewLatch(log)
	ix.statFn = func(p string) (os.FileInfo, error) { return fsop.StatT(p, ix.opt.FSOpTimeout) }
	ix.probeFn = func(p string) (int64, error) { return fsop.ProbeT(p, ix.opt.FSOpTimeout, ix.probe) }
	// Settle 옵션의 단일 생성점(m2-ⓑ)이다. 전 호출점이 이 주입을 자동으로 받고,
	// 미래에 추가되는 호출점도 자동으로 덮인다 — static_rules_test 가 이 자리를 단언한다.
	ix.opt.Settle = newSettleOptions(ix.opt.Settle, func(p string) (os.FileInfo, error) {
		return ix.statT(p, "settle")
	})
	return ix
}

// newSettleOptions 는 fsop 주입 Settle 옵션의 단일 생성점이다(m2-ⓑ).
// 다른 자리에서 recording.SettleOptions 를 만들면 static_rules_test 가 막는다.
func newSettleOptions(base recording.SettleOptions, stat func(string) (os.FileInfo, error)) recording.SettleOptions {
	base.Stat = stat
	return base
}

// statT 는 stat 의 유일한 관문이다. 타임아웃(ErrStalled)을 정상 실패와 구분해
// fs_op_stalled 신호와 래치 트립으로 잇는다(f6m ⓓ). 정상 os 에러는 그대로 통과시킨다.
//
// 관문 머리의 래치 확인이 "트립 후 새 FS 워커 0"의 구조 강제다 — 진입점(Handle 등)
// 확인만으로는 같은 이벤트 처리 중간에 트립됐을 때 후속 호출(promote 재프로브 등)이
// 워커를 계속 만든다(cx 리뷰 차단 3). 이미 트립이면 워커 없이 ErrStalled 로 즉답한다.
func (ix *Indexer) statT(path, site string) (os.FileInfo, error) {
	if ix.fsLatch.Tripped() {
		return nil, fmt.Errorf("%w: op=stat site=%s (latched)", fsop.ErrStalled, site)
	}
	fi, err := ix.statFn(path)
	if errors.Is(err, fsop.ErrStalled) {
		ix.log.Error("fs_op_stalled", "op", "stat", "site", site, "path", path)
		ix.fsLatch.Trip(path, site)
	}
	return fi, err
}

// probeT 는 프로브의 유일한 관문이다. 열고·읽고·닫기 전체가 워커 안에서 끝난다(fsop 계약 2).
// 래치 확인 규약은 statT 와 같다 — 트립 후에는 워커를 만들지 않는다.
func (ix *Indexer) probeT(path string) (int64, error) {
	if ix.fsLatch.Tripped() {
		return 0, fmt.Errorf("%w: op=probe (latched)", fsop.ErrStalled)
	}
	d, err := ix.probeFn(path)
	if errors.Is(err, fsop.ErrStalled) {
		ix.log.Error("fs_op_stalled", "op", "probe", "site", "probe", "path", path)
		ix.fsLatch.Trip(path, "probe")
	}
	return d, err
}

// fileSizeT 는 크기만 필요한 호출자를 위한 statT 축약이다.
func (ix *Indexer) fileSizeT(path, site string) (int64, error) {
	fi, err := ix.statT(path, site)
	if err != nil {
		return 0, err
	}
	return fi.Size(), nil
}

// Handle 은 완성된 세그먼트 1개를 설계 3절 6번의 H0-H9 순서대로 처리한다.
// 이 순서가 계약이며, 바꾸면 조용히 틀린다.
//
// 반환 에러는 "프로세스를 끝내야 하는 상황"만을 뜻한다(DB 재시도 소진, 복구 불가 seq 충돌).
// 세그먼트 1개를 건너뛰는 판정은 로그를 남기고 nil 을 돌려준다.
func (ix *Indexer) Handle(ctx context.Context, seg recording.Segment) error {
	// 래치 확인(m2 ⓒ) — 트립 상태에서는 이벤트마다 새 FS 워커를 남기지 않는다.
	// 조기 반환은 반드시 nil 이다: loop 은 Handle 에러를 프로세스 종료로 번역하므로
	// 여기서 에러를 돌려주면 hung FS 가 곧 프로세스 사망이 된다(격리 장치 ≠ 사망 장치).
	// 이 조각은 미기록으로 남고 다음 수집이 회수한다.
	if ix.fsLatch.Tripped() {
		ix.log.Warn("fs_latch_early_return", "site", "handle",
			"stream_id", seg.StreamID, "path", seg.Path)
		return nil
	}
	ix.handleCount++ // f6n 국면 판별 재료 — 래치 조기 반환은 처리가 아니므로 세지 않는다

	// H0. 사유 검증 — zero value 가 정상 경로로 흘러드는 것을 원천 차단한다.
	if seg.Reason == recording.ReasonUnknown {
		ix.log.Error("unknown_completion_reason", "stream_id", seg.StreamID, "path", seg.Path)
		return nil
	}

	cur, indexed, err := ix.state(ctx, seg.StreamID)
	if err != nil {
		return err
	}

	// 훅 세션 경계 정리 — H1/H2 조기 반환보다 **앞**이어야 한다.
	// 중복(H2)이나 늦은 조각(H3)으로 commit 에 닿지 못해도 "이미 지나간 경계"는 정리돼야
	// 한다. 뒤로 미루면 그 경계가 다음 조각에 붙어 영속 오표시가 된다.
	ix.reconcileBreaks(seg.StreamID, cur)

	// H1. 교정 분기 — 재성장은 새 행이 아니라 기존 행을 고치는 완전히 다른 처리다.
	if seg.Reason == recording.ReasonRegrown {
		return ix.correctTail(ctx, seg)
	}

	// H2. 경로 중복 확인 — 늦은 wall 검사(H3)보다 반드시 먼저.
	// Scan 과 워처가 같은 파일을 둘 다 집는 것은 설계상 허용이며, 이 순서를 뒤집으면
	// 정상 기록된 파일이 늦은 세그먼트로 오해받아 ERROR 가 쏟아진다(리뷰 지적 X1).
	if _, ok := indexed[seg.Path]; ok {
		if cur.Tail != nil && cur.Tail.LocalPath == seg.Path {
			if fi, statErr := ix.statT(seg.Path, "h2_regrow"); statErr == nil && fi.Size() > cur.Tail.Bytes {
				// 중복이 아니라 재성장이다.
				return ix.correctTail(ctx, seg)
			}
		}
		ix.log.Debug("duplicate_path_skipped", "stream_id", seg.StreamID, "path", seg.Path)
		return nil
	}

	// H3. 늦은 세그먼트 검사(D12) — 지금 넣으면 seq 순서와 시간 순서가 어긋나 G8 이 깨진다.
	if cur.Tail != nil && !seg.StartWall.After(cur.LastStartWall()) {
		// 메모리 이력이 낡아서 오해한 것일 수 있으니 DB 에서 한 번 다시 읽어 확인한다.
		fresh, reloadErr := ix.store.ExistingPaths(ctx, seg.StreamID)
		if reloadErr != nil {
			return reloadErr
		}
		ix.indexed[seg.StreamID] = fresh
		if _, ok := fresh[seg.Path]; ok {
			ix.log.Debug("duplicate_path_skipped", "stream_id", seg.StreamID, "path", seg.Path)
			return nil
		}
		// 진짜 유실이다. 자동 복구는 없으므로(9절 L5) 사람이 보도록 크게 남긴다.
		ix.log.Error("late_segment_skipped",
			"stream_id", seg.StreamID, "path", seg.Path,
			"seg_wall", seg.StartWall, "last_indexed_wall", cur.LastStartWall(),
			"last_seq", cur.Tail.Seq)
		return nil
	}

	// H4. 유휴 커밋 전 mtime 재검(D13-예방) — 방금 전까지 쓰이고 있었다면 아직 녹화 중이다.
	if seg.Reason == recording.ReasonIdle {
		if fi, statErr := ix.statT(seg.Path, "h4_idle"); statErr == nil && time.Since(fi.ModTime()) < ix.opt.IdleTimeout {
			// 무음으로 두면 "왜 이 파일이 안 들어오지"를 로그에서 추적할 수 없다.
			ix.log.Debug("idle_readopted",
				"stream_id", seg.StreamID, "path", seg.Path,
				"mtime_age", time.Since(fi.ModTime()), "idle_timeout", ix.opt.IdleTimeout)
			ix.adopt.Adopt(seg)
			return nil
		}
	}

	// H5. 측정 — probe 먼저, 크기 변동 시에만 Settle (정상 경로 추가 지연 0).
	d, size, ok := ix.measure(ctx, seg)
	if !ok {
		return nil
	}

	// H6. 재프로브 승격 — ReasonNextFile 한정.
	d, size = ix.promote(ctx, seg, d, size)

	// H7. 폭 검증 — int64 에서 int32 로 좁히는 지점은 여기와 correctTail 두 곳뿐이다.
	if d <= 0 || d > math.MaxInt32 {
		ix.log.Error("invalid_duration",
			"stream_id", seg.StreamID, "path", seg.Path, "duration_ms", d, "reason", seg.Reason)
		return nil
	}

	// H8 + H9.
	return ix.commit(ctx, seg, d, size)
}

// state 는 스트림의 커서와 이력 맵을 준비한다. 처음 보는 스트림이면 DB 에서 적재한다.
func (ix *Indexer) state(ctx context.Context, streamID string) (*index.Cursor, map[string]struct{}, error) {
	if cur, ok := ix.cursors[streamID]; ok {
		return cur, ix.indexed[streamID], nil
	}

	cur, err := ix.store.LoadCursor(ctx, streamID)
	if err != nil {
		return nil, nil, err
	}
	paths, err := ix.store.ExistingPaths(ctx, streamID)
	if err != nil {
		return nil, nil, err
	}
	ix.cursors[streamID] = &cur
	ix.indexed[streamID] = paths
	return &cur, paths, nil
}

// measure 는 H5 를 수행한다. 반환 ok 가 false 면 이번 사이클에서는 커밋하지 않는다.
//
// 발상: 대부분의 파일은 이미 다 써져 있다. 먼저 재 보고, 재는 동안 크기가 변했을 때만
// 안정될 때까지 기다린다 -> 정상 상황에서는 대기 시간이 0이다.
func (ix *Indexer) measure(ctx context.Context, seg recording.Segment) (durationMS int64, size int64, ok bool) {
	size0, err := ix.fileSizeT(seg.Path, "measure")
	if err != nil {
		ix.log.Error("stat_failed", "stream_id", seg.StreamID, "path", seg.Path, "err", err)
		return 0, 0, false
	}
	ix.warnOnWallSkew(seg)

	d := ix.probeOrZero(seg)
	size1, err := ix.fileSizeT(seg.Path, "measure")
	if err != nil {
		ix.log.Error("stat_failed", "stream_id", seg.StreamID, "path", seg.Path, "err", err)
		return 0, 0, false
	}

	rounds := 0
	for ; size1 != size0 && rounds < ix.opt.MaxSettleRounds; rounds++ {
		// 남은 라운드 래치 확인(m2 ⓑ) — 앞 라운드에서 트립됐으면 더 반복하지 않는다.
		if ix.fsLatch.Tripped() {
			ix.log.Warn("fs_latch_early_return", "site", "measure",
				"stream_id", seg.StreamID, "path", seg.Path)
			return 0, 0, false
		}
		fi, settleErr := recording.Settle(ctx, seg.Path, ix.opt.Settle)
		if settleErr != nil {
			if errors.Is(settleErr, context.Canceled) || errors.Is(settleErr, context.DeadlineExceeded) {
				return 0, 0, false
			}
			break
		}
		size0 = fi.Size()
		d = ix.probeOrZero(seg)
		size1, err = ix.fileSizeT(seg.Path, "measure")
		if err != nil {
			ix.log.Error("stat_failed", "stream_id", seg.StreamID, "path", seg.Path, "err", err)
			return 0, 0, false
		}
	}

	if size1 != size0 {
		// 상한을 소진해도 계속 자란다 = 아직 완성되지 않았다.
		// 잘린 값을 채택해 INSERT 하면 교정망(꼬리·pending 한정)으로도 되돌릴 수 없는 구간이
		// 생긴다. 커밋하지 않고 워처에 되돌려 다음 확정 사이클에 재시도한다(H4 와 같은 패턴).
		ix.log.Error("unsettled_giving_up",
			"stream_id", seg.StreamID, "path", seg.Path, "reason", seg.Reason,
			"rounds", rounds, "size0", size0, "size1", size1)
		ix.adopt.Adopt(seg)
		return 0, 0, false
	}
	return d, size1, true
}

// promote 는 H6 를 수행한다. "다음 파일이 생겨서 완성"인 경우만 4000ms 를 기대할 수 있다.
func (ix *Indexer) promote(ctx context.Context, seg recording.Segment, d, size int64) (int64, int64) {
	suspect := ix.suspectBelowMS(seg.StreamID)
	if seg.Reason != recording.ReasonNextFile || ix.reprobeDisabled[seg.StreamID] || d >= suspect {
		// 재프로브가 필요 없을 만큼 정상인 세그먼트도 연속 실패 기록을 지운다.
		// 그러지 않으면 과거의 짧은 조각 몇 개 때문에 승격이 영구 비활성화된 채 남는다.
		if seg.Reason == recording.ReasonNextFile && d >= suspect {
			ix.failStreak[seg.StreamID] = 0
		}
		return d, size
	}

	for range ix.opt.ReprobeMaxAttempts {
		if !sleepCtx(ctx, ix.opt.Settle.SettleWait) {
			return d, size
		}
		d2 := ix.probeOrZero(seg)
		if d2 <= d {
			break // 실제로 짧은 세그먼트다
		}
		d = d2
		if d >= suspect {
			break // 승격 성공
		}
	}

	// 재프로브 동안 파일이 더 써졌을 수 있으므로 성공·실패 어느 쪽이든 크기를 다시 읽는다.
	// 낡은 크기를 기록하면 다음 Scan(a)가 그것을 재성장으로 오인해 쓸데없이 UpdateTail 한다.
	if fi, err := ix.statT(seg.Path, "promote"); err == nil {
		size = fi.Size()
	}

	if d >= suspect {
		ix.failStreak[seg.StreamID] = 0
		return d, size
	}

	// 승격 후에도 짧다 = GOP 정렬 이탈 신호.
	ix.failStreak[seg.StreamID]++
	ix.log.Warn("short_segment_after_reprobe",
		"stream_id", seg.StreamID, "path", seg.Path, "duration_ms", d,
		"suspect_below_ms", suspect, "fail_streak", ix.failStreak[seg.StreamID])

	if ix.failStreak[seg.StreamID] >= ix.opt.ReprobeFailStreak && !ix.reprobeDisabled[seg.StreamID] {
		ix.reprobeDisabled[seg.StreamID] = true
		ix.log.Warn("reprobe_disabled", "stream_id", seg.StreamID,
			"fail_streak", ix.failStreak[seg.StreamID])
	}
	return d, size
}

// commit 은 H8(PTS·discontinuity)와 H9(INSERT)를 수행한다.
func (ix *Indexer) commit(ctx context.Context, seg recording.Segment, d, size int64) error {
	cur := ix.cursors[seg.StreamID]

	// 무장 조회는 여기서 딱 한 번이다. 해제는 INSERT 결과가 나온 뒤에 한다.
	dec := ix.peekBreak(cur, seg)
	if dec.Index >= 0 && !dec.Apply {
		// 꼬리가 없어 표시가 무의미한 경우다. INSERT 결과와 무관하므로 즉시 해제한다.
		ix.releaseBreak(seg.StreamID, dec)
		ix.log.Info("hook_break_discarded",
			"stream_id", seg.StreamID, "path", seg.Path, "reason", "no_tail")
		dec = noBreak
	}

	rec := ix.buildRecord(cur, seg, d, size, dec.Apply)

	outcome, poisoned, err := ix.insertWithRetry(ctx, rec)
	if err != nil {
		return err
	}
	if poisoned {
		// 커서를 전진시키지 않는다. 이 세그먼트만 인덱스에 없는 채로 남는다.
		//
		// 무장도 해제하지 않는다 — 이것이 무장이 메모리에 잔존하는 **유일한** 경로다
		// (재시도 소진은 err 반환 → 프로세스 종료 → 메모리 무장도 함께 소멸).
		// 다음 INSERT 조각은 "무장 이후 처음 장부에 오르는 조각"이므로 표시가 정당하며,
		// 아예 미표시보다 늦은 표시가 낫다(오탐 방향이 안전하다).
		return nil
	}

	if outcome == index.InsertSeqConflict {
		// 커서가 DB 보다 뒤처졌다 = 단일 쓰기자 전제(D10)가 깨졌다는 신호.
		// 재적재 후 1회만 재시도한다 — 반복 재시도는 다른 쓰기자와 seq 경합을 벌이는 것이며
		// G3(seq 재사용·재정렬 금지)를 위협한다.
		reloaded, reloadErr := ix.store.LoadCursor(ctx, seg.StreamID)
		if reloadErr != nil {
			return reloadErr
		}
		ix.cursors[seg.StreamID] = &reloaded
		ix.reconcileUploadState(seg.StreamID)
		cur = &reloaded
		// dec 는 다시 계산하지 않는다. 재적재로 Tail 이 바뀌어도 "어느 경계를 소비할지"는
		// 이미 정해졌다 — 여기서 재계산하면 같은 세그먼트의 판정이 재시도 여부에 따라 달라진다.
		rec = ix.buildRecord(cur, seg, d, size, dec.Apply)

		outcome, poisoned, err = ix.insertWithRetry(ctx, rec)
		if err != nil {
			return err
		}
		if poisoned {
			return nil
		}
		if outcome == index.InsertSeqConflict {
			ix.log.Error("seq_conflict_unrecoverable",
				"stream_id", seg.StreamID, "seq", rec.Seq, "path", seg.Path)
			return fmt.Errorf("seq 충돌이 재적재 후에도 계속된다 stream_id=%q seq=%d", seg.StreamID, rec.Seq)
		}
	}

	switch outcome {
	case index.InsertDuplicatePath:
		// 정상 멱등. 커서를 전진시키면 번호가 하나 건너뛰므로 절대 금지.
		if dec.Index >= 0 {
			// 그 조각의 행이 무장 전에 이미 존재했다 = 경계가 그 조각을 놓쳤다.
			// 다음 조각에 넘기면 영속 오표시가 되므로 폐기하고 미탐으로 강등한다.
			// 1차 방어는 Handle 진입부의 reconcile 이며 이 경로는 잔여 방어다(메모리 맵이 낡은 경우).
			ix.releaseBreak(seg.StreamID, dec)
			ix.log.Info("hook_break_discarded",
				"stream_id", seg.StreamID, "path", seg.Path, "reason", "duplicate_path",
				"offline_at", dec.Brk.OfflineAt, "online_at", dec.Brk.OnlineAt)
		}
		ix.log.Debug("duplicate_path_skipped", "stream_id", seg.StreamID, "path", seg.Path)
		return nil
	case index.InsertInserted:
		ix.advance(cur, seg, rec)
		if dec.Index >= 0 {
			ix.releaseBreak(seg.StreamID, dec)
			ix.log.Info("hook_break_consumed",
				"stream_id", seg.StreamID, "path", seg.Path, "seq", rec.Seq,
				"offline_at", dec.Brk.OfflineAt, "online_at", dec.Brk.OnlineAt,
				"on_source", dec.Brk.OnSource)
		}
		return nil
	default:
		return fmt.Errorf("알 수 없는 INSERT 결과 %v", outcome)
	}
}

// buildRecord 는 H8 을 수행해 DB 한 줄을 만든다.
//
// breakHit 은 "훅으로 확인된 세션 경계를 이 조각이 소비한다"는 판정이다(peekBreak 산출).
// 여기가 IsDiscontinuity 의 **유일한 대입 지점**이다 — 대입 지점을 늘리면 어느 경로로
// 참이 됐는지 추적할 수 없어진다.
func (ix *Indexer) buildRecord(cur *index.Cursor, seg recording.Segment, d, size int64, breakHit bool) index.Record {
	startPTS := int64(0)
	isDiscont := false

	if cur.Tail != nil {
		drift := seg.StartWall.Sub(cur.ExpectedNextWall()).Milliseconds()
		if abs64(drift) <= ix.opt.DriftToleranceMS {
			startPTS = cur.NextPTSMS()
			if drift < 0 {
				// 톨러런스 이내의 음수 drift 는 무음으로 두지 않되 ERROR 로 올리지도 않는다.
				// duration 은 미디어 타임라인에서, wall 은 파일명에서 오므로 작은 음수는
				// 상시 발생하는 측정 입도 차이다(60초 방송 실측: 전이 14건 중 13건이 -1~-22ms).
				// ERROR 로 올리면 그 신호가 소음에 묻혀 진짜 사고를 못 보게 된다.
				ix.log.Debug("negative_drift_within_tolerance",
					"stream_id", seg.StreamID, "path", seg.Path, "drift_ms", drift,
					"seg_wall", seg.StartWall, "expected_next_wall", cur.ExpectedNextWall())
			}
		} else {
			// max(0, drift) 가 G8(단조 비감소)을 식 수준에서 보장하는 유일한 지점이다.
			// drift 가 음수일 때 그대로 더하면 PTS 가 뒤로 후퇴해 구간이 겹친다.
			startPTS = cur.NextPTSMS() + max(int64(0), drift)
			isDiscont = true
			if drift < 0 {
				// 시계 역행 또는 NTP step.
				ix.log.Error("negative_drift",
					"stream_id", seg.StreamID, "path", seg.Path, "drift_ms", drift,
					"seg_wall", seg.StartWall, "expected_next_wall", cur.ExpectedNextWall())
			}
		}
	}

	// 훅 판정을 합성한다. PTS 식은 손대지 않는다 — 0.9s 재접속의 drift 는 tolerance 이내라
	// 타임라인은 그대로 이어 붙고 플래그만 선다. POK-36 케이스에서 원하던 결과다.
	//
	// cur.Tail == nil 이면 breakHit 은 구조적으로 false 다(peekBreak 이 Apply 를 주지 않는다).
	isDiscont = isDiscont || breakHit

	return index.Record{
		StreamID:        seg.StreamID,
		Seq:             cur.NextSeq,
		StartPTSMS:      startPTS,
		StartWallUTC:    seg.StartWall,
		DurationMS:      int32(d),
		S3Key:           index.S3Key(seg.StreamID, cur.NextSeq, seg.StartWall),
		LocalPath:       seg.Path,
		UploadState:     index.UploadStatePending,
		Bytes:           size,
		IsDiscontinuity: isDiscont,
	}
}

// advance 는 커서를 한 칸 전진시킨다.
// 꼬리 행 하나만 갈아 끼우면 파생 3값이 동시에 맞는다 — 빠뜨릴 곳 자체가 없어진다.
func (ix *Indexer) advance(cur *index.Cursor, seg recording.Segment, rec index.Record) {
	// 직전 꼬리를 붙잡아 둔다 — 이 INSERT 로 그 행은 더 이상 꼬리가 아니게 되므로,
	// 보류 중이었다면 지금이 올릴 때다(파일이 더 자랄 수 없다).
	prev := cur.Tail

	cur.Tail = &index.TailRow{
		Seq: rec.Seq, StartPTSMS: rec.StartPTSMS, StartWallUTC: rec.StartWallUTC,
		DurationMS: rec.DurationMS, Bytes: rec.Bytes, LocalPath: rec.LocalPath,
		UploadState: index.UploadStatePending,
		// ★ S3Key 를 빠뜨려도 컴파일이 통과하고 빈 문자열이 된다. 그 상태로 held 가 승격되면
		//   키가 빈 채 요청되어 대상 검증에 걸리고 그 조각은 영구 미업로드된다(R-a).
		S3Key: rec.S3Key,
	}
	cur.NextSeq = rec.Seq + 1

	if ix.indexed[seg.StreamID] == nil {
		ix.indexed[seg.StreamID] = map[string]struct{}{}
	}
	ix.indexed[seg.StreamID][rec.LocalPath] = struct{}{}

	if seg.Reason == recording.ReasonNextFile {
		ix.learn(seg.StreamID, int64(rec.DurationMS))
	}

	// reason 은 채널별 기여도(훅 vs 파일)를 재는 유일한 창이다.
	// 이 값의 분포가 무너지는 것이 "훅 채널이 무징후로 죽었다"의 유일한 신호다.
	ix.log.Info("segment_indexed",
		"stream_id", seg.StreamID, "seq", rec.Seq, "duration_ms", rec.DurationMS,
		"start_pts_ms", rec.StartPTSMS, "is_discontinuity", rec.IsDiscontinuity,
		"bytes", rec.Bytes, "path", rec.LocalPath, "reason", seg.Reason)

	now := time.Now()
	// 보류 중이던 직전 꼬리를 승격한다. 더 이상 꼬리가 아니므로 IsTail=false 다 —
	// 워커의 크기 재확인이 이 값으로 갈린다(결정 4⁵).
	if h, ok := ix.held[seg.StreamID]; ok && prev != nil && h.seq == prev.Seq {
		if !ix.requestUpload(seg.StreamID, prev, false) {
			ix.holdAfterRejection(seg.StreamID, prev, now)
		}
	}

	// 새 꼬리의 처우. 더 자라지 않는다고 확증된 사유는 즉시 올린다.
	if growthConfirmed(seg.Reason) {
		if !ix.requestUpload(seg.StreamID, cur.Tail, true) {
			ix.holdAfterRejection(seg.StreamID, cur.Tail, now)
		}
		return
	}
	// Idle·Scan 으로 확정된 꼬리는 아직 자랄 수 있다. 붙들었다가 다음 INSERT 나
	// TailHold 경과 + 크기 일치에서 올린다.
	ix.holdTail(seg.StreamID, cur.Tail, now)
}

// correctTail 은 이미 넣은 마지막 행의 길이와 크기를 사후에 고친다(D13-교정).
// 그 행이 아직 꼬리이고 아직 pending 일 때만 허용된다.
func (ix *Indexer) correctTail(ctx context.Context, seg recording.Segment) error {
	cur := ix.cursors[seg.StreamID]

	// 확인 1: 정말 꼬리인가. 꼬리가 아닌 행을 고치면 뒤 행들의 PTS 가 전부 어긋난다.
	if cur == nil || cur.Tail == nil || cur.Tail.LocalPath != seg.Path {
		ix.log.Error("regrow_not_tail", "stream_id", seg.StreamID, "path", seg.Path)
		return nil
	}
	// 확인 2·2.5: 커서 상태별 3분기. 공통 게이트는 "실제로 자랐는가" 하나다.
	//
	// 삽입 지점이 Settle 보다 **앞**인 것이 계약이다 — Settle 은 최대 30초 블로킹이고
	// 이 함수는 메인 루프에서 돌기 때문에, 뒤로 옮기면 자라지도 않은 파일 때문에
	// 루프가 30초씩 선다.
	if cur.Tail.UploadState != index.UploadStatePending {
		fileBytes, grew, statErr := ix.tailGrew(cur.Tail)
		switch {
		case statErr != nil:
			// 확정된 행의 파일이 사라진 것은 recordDeleteAfter·janitor 의 정상 동작이다.
			ix.log.Debug("tail_file_gone", "stream_id", seg.StreamID, "seq", cur.Tail.Seq,
				"path", seg.Path, "upload_state", string(cur.Tail.UploadState))
		case !grew:
			ix.log.Debug("regrow_after_upload_noop", "stream_id", seg.StreamID,
				"seq", cur.Tail.Seq, "path", seg.Path,
				"db_bytes", cur.Tail.Bytes, "file_bytes", fileBytes)
		case cur.Tail.UploadState == index.UploadStateUploaded:
			// 확정된 조각이 자랐다 = 잘린 실물이 S3 에 굳었다. 자동 복구가 없는 사고다(L11‴).
			ix.log.Error("regrow_after_upload_ignored",
				"stream_id", seg.StreamID, "seq", cur.Tail.Seq, "path", seg.Path,
				"db_bytes", cur.Tail.Bytes, "file_bytes", fileBytes, "delta", fileBytes-cur.Tail.Bytes)
		default:
			// failed 는 실물이 올라가지 않았으므로 굳은 것이 아니다.
			// 다만 그 행이 계속 꼬리면 자동 회복이 보장되지 않는다(L20).
			ix.log.Warn("regrow_after_failed_ignored",
				"stream_id", seg.StreamID, "seq", cur.Tail.Seq, "path", seg.Path,
				"db_bytes", cur.Tail.Bytes, "file_bytes", fileBytes, "delta", fileBytes-cur.Tail.Bytes)
		}
		return nil
	}

	// 확인 2.5: pending 인데 이미 업로더에게 넘긴 행이다. 자라지 않았으면 건드릴 이유가 없고,
	// 자랐으면 통상 경로로 계속 간다 — UpdateTail 이 성공하면 장부가 실물을 따라잡아
	// 다음 PUT 의 CAS 기대값이 맞춰지며 스스로 치유된다.
	if requested, ok := ix.requested[seg.StreamID]; ok && requested == cur.Tail.Seq {
		fileBytes, grew, statErr := ix.tailGrew(cur.Tail)
		if statErr == nil && !grew {
			ix.log.Debug("regrow_after_upload_noop", "stream_id", seg.StreamID,
				"seq", cur.Tail.Seq, "path", seg.Path,
				"db_bytes", cur.Tail.Bytes, "file_bytes", fileBytes)
			return nil
		}
		if statErr == nil {
			ix.log.Warn("regrow_after_upload_requested",
				"stream_id", seg.StreamID, "seq", cur.Tail.Seq, "path", seg.Path,
				"db_bytes", cur.Tail.Bytes, "file_bytes", fileBytes, "delta", fileBytes-cur.Tail.Bytes)
		}
	}

	fi, err := recording.Settle(ctx, seg.Path, ix.opt.Settle)
	if err != nil {
		if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			return nil
		}
		ix.log.Error("tail_settle_failed", "stream_id", seg.StreamID, "path", seg.Path, "err", err)
		return nil
	}
	d := ix.probeOrZero(seg)

	// 폭 검증 — H7 과 같은 규칙. 검증 없이 UpdateTail 하면 정상 커밋 경로가 막아 둔 잘못된
	// 값이 교정 경로로 우회 진입한다. 정문에 검문소를 세우고 뒷문을 열어 두는 셈이 된다.
	if d <= 0 || d > math.MaxInt32 {
		ix.log.Error("tail_probe_invalid",
			"stream_id", seg.StreamID, "seq", cur.Tail.Seq, "path", seg.Path, "duration_ms", d)
		return nil
	}

	// 확인 3: 실제로 달라진 게 없으면 DB 를 건드리지 않는다.
	if d <= int64(cur.Tail.DurationMS) && fi.Size() <= cur.Tail.Bytes {
		return nil
	}

	// 확인 4: 최종 판정은 DB 가 내린다. 메모리와 DB 가 어긋나 있을 수 있다.
	updated, err := ix.store.UpdateTail(ctx, seg.StreamID, cur.Tail.Seq, int32(d), fi.Size())
	if err != nil {
		return err
	}
	if !updated {
		// 후속 행이 생겼거나 이미 uploaded 다. 후속 행 존재는 파일이 닫혔다는 뜻이므로
		// 정상 재성장에서는 발생하지 않는다. 그럼에도 발생하면 PTS 정합은 다음 세그먼트의
		// H8 drift 감시가 처리한다.
		ix.log.Error("tail_update_rejected",
			"stream_id", seg.StreamID, "seq", cur.Tail.Seq, "path", seg.Path)
		return nil
	}

	oldDuration, oldBytes := cur.Tail.DurationMS, cur.Tail.Bytes
	cur.Tail.DurationMS = int32(d)
	cur.Tail.Bytes = fi.Size()
	// 이것으로 파생 NextPTSMS / ExpectedNextWall 이 자동 교정된다.

	// 교정이 보류 시계를 리셋한다 — 방금 자란 것을 확인했으므로 TailHold 를 다시 센다.
	// eligibleAt 은 start_wall_utc 기반이라 불변이며, 그래야 스위퍼 자격 시점과 계속 맞물린다(R9).
	if h, ok := ix.held[seg.StreamID]; ok && h.seq == cur.Tail.Seq {
		h.since = time.Now()
		h.nextTry = h.since
		ix.held[seg.StreamID] = h
	}

	ix.log.Info("tail_corrected",
		"stream_id", seg.StreamID, "seq", cur.Tail.Seq,
		"old_duration_ms", oldDuration, "new_duration_ms", cur.Tail.DurationMS,
		"old_bytes", oldBytes, "new_bytes", cur.Tail.Bytes)
	return nil
}

// probeOrZero 는 프로브 실패를 0 으로 바꿔 돌려준다.
// 미완성 파일은 박스에 길이가 없어 에러가 나는데, 이는 H6 재프로브 승격과 H7 폭 검증이
// 이미 다루는 상황이므로 별도 분기를 만들지 않는다.
func (ix *Indexer) probeOrZero(seg recording.Segment) int64 {
	d, err := ix.probeT(seg.Path)
	if err != nil {
		ix.log.Warn("probe_failed", "stream_id", seg.StreamID, "path", seg.Path, "err", err)
		return 0
	}
	return d
}

// warnOnWallSkew 는 파일명 시각과 mtime 이 크게 어긋나면 경고한다(D6).
// mtime 은 세그먼트의 끝 시각에 가까우므로 기대 길이만큼의 차이는 정상이다.
func (ix *Indexer) warnOnWallSkew(seg recording.Segment) {
	fi, err := ix.statT(seg.Path, "wall_skew")
	if err != nil {
		return
	}
	expectedEnd := seg.StartWall.Add(time.Duration(ix.opt.ExpectedDurationMS) * time.Millisecond)
	if gap := absDuration(fi.ModTime().UTC().Sub(expectedEnd)); gap > wallSkewWarnThreshold {
		ix.log.Warn("wall_mtime_skew",
			"stream_id", seg.StreamID, "path", seg.Path,
			"start_wall", seg.StartWall, "mtime", fi.ModTime().UTC(), "gap", gap)
	}
}

// wallSkewWarnThreshold 는 D6 의 "mtime 5초 이상 괴리 시 WARN" 기준이다.
const wallSkewWarnThreshold = 5 * time.Second

// ---------------------------------------------------------------------------
// 학습 — X10(학습 오염) 대응. 상향만 가능하며 하향은 없다.
// ---------------------------------------------------------------------------

// expectedMS 는 이 스트림의 학습된 기대 길이다. 초기값은 Options 의 값이며 내려가지 않는다.
func (ix *Indexer) expectedMS(streamID string) int64 {
	if v, ok := ix.learnedMS[streamID]; ok && v > ix.opt.ExpectedDurationMS {
		return v
	}
	return ix.opt.ExpectedDurationMS
}

// suspectBelowMS 는 이 스트림의 의심 하한이다. 기대 길이와 같은 폭만큼 아래에 둔다.
func (ix *Indexer) suspectBelowMS(streamID string) int64 {
	margin := ix.opt.ExpectedDurationMS - ix.opt.SuspectBelowMS
	return ix.expectedMS(streamID) - margin
}

// learn 은 ReasonNextFile 로 확정된 최종 길이만 표본으로 쌓는다.
// 짧은 세션이나 잘린 파일이 기준을 끌어내리지 않도록 상향만 반영한다.
func (ix *Indexer) learn(streamID string, durationMS int64) {
	s := append(ix.samples[streamID], durationMS)
	if len(s) < ix.opt.LearnSampleCount {
		ix.samples[streamID] = s
		return
	}

	sorted := slices.Clone(s)
	slices.Sort(sorted)
	median := sorted[len(sorted)/2]
	if median > ix.expectedMS(streamID) {
		ix.learnedMS[streamID] = median
	}
	ix.samples[streamID] = nil
}

// ---------------------------------------------------------------------------
// 작은 도우미
// ---------------------------------------------------------------------------

// sleepCtx 는 ctx 취소 시 false 를 돌려준다.
func sleepCtx(ctx context.Context, d time.Duration) bool {
	if d <= 0 {
		return ctx.Err() == nil
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-t.C:
		return true
	}
}

func abs64(v int64) int64 {
	if v < 0 {
		return -v
	}
	return v
}

func absDuration(d time.Duration) time.Duration {
	if d < 0 {
		return -d
	}
	return d
}
