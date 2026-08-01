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

	"github.com/jackc/pgx/v5/pgconn"

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
	// InsertRetryBase 는 지수 백오프의 첫 간격이다.
	// 기본 2s + InsertRetryMax 5회 = 시도 사이 간격 2+4+8+16 = 정확히 30s(설계 2.4절 "총 ~30s").
	InsertRetryBase time.Duration
	// IdleTimeout 은 워처와 같은 값을 쓴다. H4(유휴 커밋 전 mtime 재검)와
	// Scan(d)(최신 파일 분기)의 판정 기준이다.
	IdleTimeout time.Duration
	// Settle 은 H5 와 correctTail 이 크기 안정을 기다릴 때 쓰는 설정이다.
	Settle recording.SettleOptions
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
		InsertRetryBase:    2 * time.Second,
		IdleTimeout:        10 * time.Second,
		Settle:             recording.DefaultSettleOptions(),
	}
}

// Indexer 는 고루틴 안전하지 않다. D10 단일 호출자 규약이 락을 대신한다.
type Indexer struct {
	store index.Store
	probe fmp4meta.DurationProbe
	adopt Adopter
	opt   Options
	log   *slog.Logger

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
	// warnedRejected 는 화이트리스트를 통과하지 못한 디렉토리에 대해 WARN 을
	// 한 번만 내기 위한 표시다. 재스캔은 5분마다 도는데 매번 경고하면 소음이 된다.
	warnedRejected map[string]bool
}

// New 는 인덱서를 만든다. w 에는 *recording.Watcher 를 그대로 넘긴다.
func New(store index.Store, probe fmp4meta.DurationProbe, w Adopter, opt Options, log *slog.Logger) *Indexer {
	return &Indexer{
		store:           store,
		probe:           probe,
		adopt:           w,
		opt:             opt,
		log:             log,
		cursors:         map[string]*index.Cursor{},
		indexed:         map[string]map[string]struct{}{},
		samples:         map[string][]int64{},
		learnedMS:       map[string]int64{},
		failStreak:      map[string]int{},
		reprobeDisabled: map[string]bool{},
		warnedRejected:  map[string]bool{},
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
	rec := ix.buildRecord(cur, seg, d, size)

	outcome, poisoned, err := ix.insertWithRetry(ctx, rec)
	if err != nil {
		return err
	}
	if poisoned {
		return nil // 커서를 전진시키지 않는다. 이 세그먼트만 인덱스에 없는 채로 남는다.
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
		cur = &reloaded
		rec = ix.buildRecord(cur, seg, d, size)

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
		ix.log.Debug("duplicate_path_skipped", "stream_id", seg.StreamID, "path", seg.Path)
		return nil
	case index.InsertInserted:
		ix.advance(cur, seg, rec)
		return nil
	default:
		return fmt.Errorf("알 수 없는 INSERT 결과 %v", outcome)
	}
}

// buildRecord 는 H8 을 수행해 DB 한 줄을 만든다.
func (ix *Indexer) buildRecord(cur *index.Cursor, seg recording.Segment, d, size int64) index.Record {
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

// insertFate 는 INSERT 에러를 어떻게 다룰지 셋으로 가른다.
type insertFate int

const (
	// fateRetry — 시간이 지나면 나아질 수 있는 실패(연결 끊김, 자원 부족, 운영자 개입).
	fateRetry insertFate = iota
	// fatePoison — 몇 번을 다시 넣어도 같은 결과인 실패. 그 세그먼트만 격리하고 계속 간다.
	fatePoison
	// fateFatal — 설정이나 스키마가 잘못됐다는 뜻. 사람이 고쳐야 하므로 프로세스를 끝낸다.
	fateFatal
)

// classifyInsertError 는 SQLSTATE 앞 두 자리(클래스)로 대응을 정한다.
//
// 재시도해서 될 일과 안 될 일을 가르지 않으면, 잘못된 값 하나가 30초씩 파이프라인을
// 붙잡고 끝내 프로세스를 죽인다. 그 사이 멀쩡한 세그먼트들도 함께 밀린다.
func classifyInsertError(err error) insertFate {
	var pgErr *pgconn.PgError
	if !errors.As(err, &pgErr) {
		// 네트워크 오류, 타임아웃 등 드라이버 계층 실패는 재시도 가치가 있다.
		return fateRetry
	}

	switch class := sqlStateClass(pgErr.Code); class {
	case "08", "53", "57":
		// 08 연결 예외 / 53 자원 부족 / 57 운영자 개입(셧다운 등)
		return fateRetry
	case "22", "54":
		// 22 데이터 예외(범위 초과, 잘못된 형식) / 54 한도 초과(값이 너무 김)
		return fatePoison
	case "23":
		// 23 무결성 위반. 23505(unique)는 Store 가 이미 InsertOutcome 으로 걸러 내므로
		// 여기 오는 것은 NOT NULL, CHECK, FK 위반이며 전부 값 자체의 문제다.
		return fatePoison
	default:
		// 42 문법·권한, 28 인증 등. 재시도해도 같고, 조용히 넘기면 전 세그먼트가 사라진다.
		return fateFatal
	}
}

func sqlStateClass(code string) string {
	if len(code) < 2 {
		return ""
	}
	return code[:2]
}

// insertWithRetry 는 H9 의 지수 백오프 재시도다.
// 재시도 상한을 소진하면 에러를 올린다 -> main 이 exit 1 -> compose 재기동 -> Scan 복구(D8).
//
// poisoned 가 true 면 이 세그먼트 하나만 버리고 프로세스는 계속 간다.
func (ix *Indexer) insertWithRetry(ctx context.Context, rec index.Record) (outcome index.InsertOutcome, poisoned bool, err error) {
	var lastErr error
	backoff := ix.opt.InsertRetryBase

	for attempt := range ix.opt.InsertRetryMax {
		result, insertErr := ix.store.Insert(ctx, rec)
		if insertErr == nil {
			return result, false, nil
		}
		lastErr = insertErr

		switch classifyInsertError(insertErr) {
		case fatePoison:
			ix.log.Error("insert_poisoned",
				"stream_id", rec.StreamID, "seq", rec.Seq, "path", rec.LocalPath,
				"err", insertErr,
				"note", "재시도해도 같은 결과다. 이 세그먼트만 건너뛴다")
			return index.InsertInserted, true, nil
		case fateFatal:
			return index.InsertInserted, false, fmt.Errorf(
				"복구 불가한 INSERT 오류 stream_id=%q seq=%d: %w", rec.StreamID, rec.Seq, insertErr)
		}

		ix.log.Warn("insert_retry", "stream_id", rec.StreamID, "seq", rec.Seq,
			"attempt", attempt+1, "err", insertErr)

		if attempt == ix.opt.InsertRetryMax-1 {
			break
		}
		if !sleepCtx(ctx, backoff) {
			return index.InsertInserted, false, ctx.Err()
		}
		backoff *= 2
	}
	return index.InsertInserted, false, fmt.Errorf("INSERT 재시도 상한 소진 stream_id=%q seq=%d: %w",
		rec.StreamID, rec.Seq, lastErr)
}

// advance 는 커서를 한 칸 전진시킨다.
// 꼬리 행 하나만 갈아 끼우면 파생 3값이 동시에 맞는다 — 빠뜨릴 곳 자체가 없어진다.
func (ix *Indexer) advance(cur *index.Cursor, seg recording.Segment, rec index.Record) {
	cur.Tail = &index.TailRow{
		Seq: rec.Seq, StartPTSMS: rec.StartPTSMS, StartWallUTC: rec.StartWallUTC,
		DurationMS: rec.DurationMS, Bytes: rec.Bytes, LocalPath: rec.LocalPath,
		UploadState: index.UploadStatePending,
	}
	cur.NextSeq = rec.Seq + 1

	if ix.indexed[seg.StreamID] == nil {
		ix.indexed[seg.StreamID] = map[string]struct{}{}
	}
	ix.indexed[seg.StreamID][rec.LocalPath] = struct{}{}

	if seg.Reason == recording.ReasonNextFile {
		ix.learn(seg.StreamID, int64(rec.DurationMS))
	}

	ix.log.Info("segment_indexed",
		"stream_id", seg.StreamID, "seq", rec.Seq, "duration_ms", rec.DurationMS,
		"start_pts_ms", rec.StartPTSMS, "is_discontinuity", rec.IsDiscontinuity,
		"bytes", rec.Bytes, "path", rec.LocalPath)
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
	// 확인 2: 아직 안 올렸는가. 이미 업로드된 파일의 메타를 고치면 실물과 기록이 불일치한다.
	if cur.Tail.UploadState != index.UploadStatePending {
		ix.log.Warn("regrow_after_upload_ignored",
			"stream_id", seg.StreamID, "seq", cur.Tail.Seq, "path", seg.Path)
		return nil
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
