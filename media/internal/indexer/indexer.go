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
	// ★ 기본값을 두지 않는다. 이 값의 소유자는 upload.Options 하나이고 config 가 그 값을
	//   넣어 준다 — 여기에 같은 기본값을 두면 두 계층이 갈려도 아무도 모르게 된다.
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
		HoldTick:           time.Second,
		HoldStatBudget:     8,
		IdleTimeout:        10 * time.Second,
		BreakGuard:         20 * time.Millisecond,
		Settle:             recording.DefaultSettleOptions(),
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

	// pendingOffline 은 아직 짝지어지지 않은 offline 훅이다(스트림별 1건, 더 늦은 것만 유지).
	pendingOffline map[string]sessionMark
	// lastOnlineAt 은 스트림별 online watermark 다 — 이보다 이른 offline 은 stale 로 버린다.
	lastOnlineAt map[string]time.Time
	// breaks 는 무장된 세션 경계 **큐**다(OnlineAt 오름차순).
	// 단일 포인터면 연쇄 재접속에서 앞 경계가 덮여 영구 미탐이 되므로 큐로 둔다.
	breaks map[string][]sessionBreak
}

// New 는 인덱서를 만든다. w 에는 *recording.Watcher 를, up 에는 *upload.Uploader 를 넘긴다.
// up 이 nil 이면 아무것도 요청하지 않는 기본값이 끼워진다 — 배선 전에도 인덱서는 그대로 돈다.
func New(store index.Store, probe fmp4meta.DurationProbe, w Adopter,
	up UploadRequester, opt Options, log *slog.Logger) *Indexer {
	if up == nil {
		up = noUploader{}
	}
	return &Indexer{
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
	}
}

// Handle 은 완성된 세그먼트 1개를 설계 3절 6번의 H0-H9 순서대로 처리한다.
// 이 순서가 계약이며, 바꾸면 조용히 틀린다.
//
// 반환 에러는 "프로세스를 끝내야 하는 상황"만을 뜻한다(DB 재시도 소진, 복구 불가 seq 충돌).
// 세그먼트 1개를 건너뛰는 판정은 로그를 남기고 nil 을 돌려준다.
func (ix *Indexer) Handle(ctx context.Context, seg recording.Segment) error {
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
			if fi, statErr := os.Stat(seg.Path); statErr == nil && fi.Size() > cur.Tail.Bytes {
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
		if fi, statErr := os.Stat(seg.Path); statErr == nil && time.Since(fi.ModTime()) < ix.opt.IdleTimeout {
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
	size0, err := fileSize(seg.Path)
	if err != nil {
		ix.log.Error("stat_failed", "stream_id", seg.StreamID, "path", seg.Path, "err", err)
		return 0, 0, false
	}
	ix.warnOnWallSkew(seg)

	d := ix.probeOrZero(seg)
	size1, err := fileSize(seg.Path)
	if err != nil {
		ix.log.Error("stat_failed", "stream_id", seg.StreamID, "path", seg.Path, "err", err)
		return 0, 0, false
	}

	rounds := 0
	for ; size1 != size0 && rounds < ix.opt.MaxSettleRounds; rounds++ {
		fi, settleErr := recording.Settle(ctx, seg.Path, ix.opt.Settle)
		if settleErr != nil {
			if errors.Is(settleErr, context.Canceled) || errors.Is(settleErr, context.DeadlineExceeded) {
				return 0, 0, false
			}
			break
		}
		size0 = fi.Size()
		d = ix.probeOrZero(seg)
		size1, err = fileSize(seg.Path)
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
	if fi, err := os.Stat(seg.Path); err == nil {
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

	// 새 꼬리의 처우. NextFile 은 후속 파일이 이미 생겼다는 뜻이라 즉시 올린다.
	if seg.Reason == recording.ReasonNextFile {
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
	d, err := ix.probe(seg.Path)
	if err != nil {
		ix.log.Warn("probe_failed", "stream_id", seg.StreamID, "path", seg.Path, "err", err)
		return 0
	}
	return d
}

// warnOnWallSkew 는 파일명 시각과 mtime 이 크게 어긋나면 경고한다(D6).
// mtime 은 세그먼트의 끝 시각에 가까우므로 기대 길이만큼의 차이는 정상이다.
func (ix *Indexer) warnOnWallSkew(seg recording.Segment) {
	fi, err := os.Stat(seg.Path)
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

func fileSize(path string) (int64, error) {
	fi, err := os.Stat(path)
	if err != nil {
		return 0, err
	}
	return fi.Size(), nil
}

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
