package indexer

import (
	"context"
	"log/slog"
	"math"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// 케이스1 — 신규 스트림은 seq 0, start_pts_ms 0 에서 시작한다.
func TestCase01NewStreamStartsAtSeqZero(t *testing.T) {
	f := newFixture(t, 4000)
	seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)

	f.mustHandle(seg)

	rows := f.store.records("s1")
	if len(rows) != 1 {
		t.Fatalf("행 수 = %d, want 1", len(rows))
	}
	if rows[0].Seq != 0 || rows[0].StartPTSMS != 0 {
		t.Fatalf("seq/pts = %d/%d, want 0/0", rows[0].Seq, rows[0].StartPTSMS)
	}
	if rows[0].DurationMS != 4000 {
		t.Errorf("duration = %d, want 4000", rows[0].DurationMS)
	}
	if rows[0].IsDiscontinuity {
		t.Error("첫 세그먼트는 불연속이 아니다")
	}
	if rows[0].UploadState != index.UploadStatePending {
		t.Errorf("upload_state = %q, want pending", rows[0].UploadState)
	}
	if rows[0].S3Key == "" {
		t.Error("s3_key 가 비었다 — 예약 문자열은 채워 둔다")
	}
}

// 케이스2 — 재기동 시 DB 의 MAX(seq)+1 에서 이어 간다(G3).
func TestCase02ResumesFromMaxSeqPlusOne(t *testing.T) {
	f := newFixture(t, 4000)
	f.store.seed(index.Record{
		StreamID: "s1", Seq: 7, StartPTSMS: 28000, StartWallUTC: baseWall,
		DurationMS: 4000, LocalPath: "/이미/기록된.mp4", UploadState: index.UploadStatePending, Bytes: 10,
	})

	seg := f.segment("s1", segName(baseWall, 4*time.Second), 1000, recording.ReasonNextFile)
	f.mustHandle(seg)

	rows := f.store.records("s1")
	last := rows[len(rows)-1]
	if last.Seq != 8 {
		t.Fatalf("seq = %d, want 8 (= MAX+1)", last.Seq)
	}
	if last.StartPTSMS != 32000 {
		t.Fatalf("start_pts_ms = %d, want 32000 (= 28000 + 4000)", last.StartPTSMS)
	}
}

// 케이스3 — H2(경로 중복 확인)가 H3(늦은 세그먼트 검사)보다 먼저다.
// Scan 과 워처가 같은 파일을 둘 다 집는 것은 설계상 허용이며, 이때 late_segment_skipped 가
// 뜨면 진짜 사고 신호가 소음에 묻힌다(리뷰 지적 X1).
func TestCase03DuplicatePathIsCheckedBeforeLateSegment(t *testing.T) {
	f := newFixture(t, 4000)
	early := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonScan)
	later := f.segment("s1", segName(baseWall, 4*time.Second), 1000, recording.ReasonNextFile)

	f.mustHandle(early)
	f.mustHandle(later)

	// 커서는 later 의 wall 에 가 있다. 이제 early 가 다시 도착한다 = 허용된 중복.
	f.mustHandle(early)

	if n := f.logs.count(slog.LevelError, "late_segment_skipped"); n != 0 {
		t.Fatalf("late_segment_skipped = %d건, want 0 — 이미 기록된 파일이 늦은 세그먼트로 오인됐다", n)
	}
	if n := f.logs.count(slog.LevelDebug, "duplicate_path_skipped"); n != 1 {
		t.Fatalf("duplicate_path_skipped = %d건, want 1", n)
	}
	if n := len(f.store.records("s1")); n != 2 {
		t.Fatalf("행 수 = %d, want 2", n)
	}
}

// 케이스4 — 진짜 늦은 세그먼트는 INSERT 하지 않고 ERROR 를 남긴다(D12).
func TestCase04TrulyLateSegmentIsSkippedWithError(t *testing.T) {
	f := newFixture(t, 4000)
	first := f.segment("s1", segName(baseWall, 4*time.Second), 1000, recording.ReasonNextFile)
	f.mustHandle(first)

	// 커서보다 이른 wall 인데 DB 에도 없는 파일 = 워처가 이벤트를 놓쳤다는 뜻.
	late := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
	f.mustHandle(late)

	if n := f.logs.count(slog.LevelError, "late_segment_skipped"); n != 1 {
		t.Fatalf("late_segment_skipped = %d건, want 1", n)
	}
	if n := len(f.store.records("s1")); n != 1 {
		t.Fatalf("행 수 = %d, want 1 — 늦은 세그먼트를 INSERT 했다", n)
	}
}

// 케이스5 — Adopt 로 되돌린 파일이 다시 도착해도 X1 검사(늦은 세그먼트)에 걸리지 않는다.
// 되돌리는 동안 커서가 전진하지 않기 때문이다.
func TestCase05ReadoptedSegmentIsNotFlaggedLate(t *testing.T) {
	f := newFixture(t, 4000)
	seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonIdle)
	// 방금 쓰인 파일로 만든다 → H4 가 되돌린다.
	f.touch(seg.Path, time.Now())

	f.mustHandle(seg)
	if f.adopter.count() != 1 {
		t.Fatalf("Adopt 호출 = %d, want 1", f.adopter.count())
	}
	if n := len(f.store.records("s1")); n != 0 {
		t.Fatalf("행 수 = %d, want 0 — 되돌린 파일을 INSERT 했다", n)
	}

	// 유휴 시간이 지나 후속 파일 생성으로 다시 도착한다.
	f.touch(seg.Path, time.Now().Add(-time.Hour))
	seg.Reason = recording.ReasonNextFile
	f.mustHandle(seg)

	if n := f.logs.count(slog.LevelError, "late_segment_skipped"); n != 0 {
		t.Fatalf("late_segment_skipped = %d건, want 0", n)
	}
	if n := len(f.store.records("s1")); n != 1 {
		t.Fatalf("행 수 = %d, want 1", n)
	}
}

// 케이스6 — ReasonRegrown 은 UpdateTail 경로로 가고, 성공 후 파생값이 새 duration 으로 교정된다.
func TestCase06RegrownCorrectsTailAndDerivedValues(t *testing.T) {
	f := newFixture(t, 4000)
	seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
	f.mustHandle(seg)

	// 파일이 더 자랐다. 프로브도 더 큰 값을 낸다.
	f.probe.vals = []int64{6000}
	f.probe.calls = 0
	f.makeFile("s1", segName(baseWall, 0), 2500)

	seg.Reason = recording.ReasonRegrown
	f.mustHandle(seg)

	if len(f.store.updateTail) != 1 {
		t.Fatalf("UpdateTail 호출 = %d, want 1", len(f.store.updateTail))
	}
	call := f.store.updateTail[0]
	if call.durationMS != 6000 || call.bytes != 2500 {
		t.Fatalf("UpdateTail(duration=%d, bytes=%d), want 6000 / 2500", call.durationMS, call.bytes)
	}

	cur := f.ix.cursors["s1"]
	if cur.Tail.DurationMS != 6000 {
		t.Fatalf("커서 꼬리 duration = %d, want 6000", cur.Tail.DurationMS)
	}
	if got := cur.NextPTSMS(); got != 6000 {
		t.Errorf("NextPTSMS = %d, want 6000 — 파생값이 교정되지 않았다", got)
	}
	if got, want := cur.ExpectedNextWall(), baseWall.Add(6*time.Second); !got.Equal(want) {
		t.Errorf("ExpectedNextWall = %v, want %v", got, want)
	}
	if n := f.logs.count(slog.LevelInfo, "tail_corrected"); n != 1 {
		t.Errorf("tail_corrected = %d건, want 1", n)
	}
}

// 케이스8 — ReasonUnknown(zero value)은 정상 경로에 존재할 수 없다. ERROR 후 skip(H0).
func TestCase08UnknownReasonIsRejected(t *testing.T) {
	f := newFixture(t, 4000)
	seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonUnknown)

	f.mustHandle(seg)

	if n := f.logs.count(slog.LevelError, "unknown_completion_reason"); n != 1 {
		t.Fatalf("unknown_completion_reason = %d건, want 1", n)
	}
	if n := len(f.store.records("s1")); n != 0 {
		t.Fatalf("행 수 = %d, want 0", n)
	}
}

// 케이스9 — drift 가 톨러런스를 넘으면 그 공백만큼 타임라인을 건너뛰고 불연속으로 표시한다(H8).
func TestCase09ReanchorsWhenDriftExceedsTolerance(t *testing.T) {
	f := newFixture(t, 4000)
	f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile))

	// 기대 시각은 base+4s 인데 실제는 base+10s → drift = +6000ms > 1500ms
	gap := f.segment("s1", segName(baseWall, 10*time.Second), 1000, recording.ReasonNextFile)
	f.mustHandle(gap)

	rows := f.store.records("s1")
	last := rows[len(rows)-1]
	if !last.IsDiscontinuity {
		t.Fatal("is_discontinuity = false, want true")
	}
	if want := int64(4000 + 6000); last.StartPTSMS != want {
		t.Fatalf("start_pts_ms = %d, want %d (= NextPTS + drift)", last.StartPTSMS, want)
	}
}

// 케이스10 — drift 가 음수로 크게 벌어져도 start_pts_ms 가 역행하지 않는다(G8) + ERROR 를 남긴다.
func TestCase10NegativeDriftDoesNotRewindPTS(t *testing.T) {
	f := newFixture(t, 4000, 4000)
	f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile))

	// 기대 시각은 base+4s 인데 실제는 base+1s → drift = -3000ms
	back := f.segment("s1", segName(baseWall, time.Second), 1000, recording.ReasonNextFile)
	f.mustHandle(back)

	rows := f.store.records("s1")
	if len(rows) != 2 {
		t.Fatalf("행 수 = %d, want 2", len(rows))
	}
	last := rows[1]
	if last.StartPTSMS < rows[0].StartPTSMS {
		t.Fatalf("start_pts_ms 가 역행했다: %d -> %d", rows[0].StartPTSMS, last.StartPTSMS)
	}
	if last.StartPTSMS != 4000 {
		t.Fatalf("start_pts_ms = %d, want 4000 (max(0, drift) 로 눌러야 한다)", last.StartPTSMS)
	}
	if n := f.logs.count(slog.LevelError, "negative_drift"); n != 1 {
		t.Fatalf("negative_drift = %d건, want 1", n)
	}
}

// 케이스11 — 학습은 기대 길이를 올리기만 하고 초기값 아래로 내리지 않는다(X10 학습 오염 방지).
func TestCase11LearningNeverLowersExpectedBelowInitial(t *testing.T) {
	f := newFixture(t)
	// 짧은 값만 계속 들어와도 기대 길이는 4000 아래로 내려가면 안 된다.
	f.probe.vals = []int64{2000}
	// 승격이 헛돌지 않게 재프로브를 꺼 둔다(짧은 세그먼트가 실재하는 상황).
	f.opt.ReprobeMaxAttempts = 0
	f.reload()

	for i := range 6 {
		seg := f.segment("s1", segName(baseWall, time.Duration(i)*2*time.Second), 1000, recording.ReasonNextFile)
		f.mustHandle(seg)
	}

	if got := f.ix.expectedMS("s1"); got < f.opt.ExpectedDurationMS {
		t.Fatalf("학습된 기대 길이 = %d, want >= %d — 짧은 표본이 기준을 끌어내렸다", got, f.opt.ExpectedDurationMS)
	}
	if got := f.ix.suspectBelowMS("s1"); got < f.opt.SuspectBelowMS {
		t.Fatalf("의심 하한 = %d, want >= %d", got, f.opt.SuspectBelowMS)
	}
}

// 케이스12 — 재프로브 승격에 성공하면 failStreak 가 0으로 리셋된다(브레이크 리셋).
func TestCase12PromotionSuccessResetsFailStreak(t *testing.T) {
	f := newFixture(t)
	f.ix.failStreak["s1"] = 2

	// 1회차 프로브는 짧게, 재프로브에서 제 길이가 나온다 → 승격 성공.
	f.probe.vals = []int64{1000, 4000}
	seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
	f.mustHandle(seg)

	if got := f.ix.failStreak["s1"]; got != 0 {
		t.Fatalf("failStreak = %d, want 0", got)
	}
	rows := f.store.records("s1")
	if rows[0].DurationMS != 4000 {
		t.Fatalf("duration = %d, want 4000 — 승격된 값이 채택되지 않았다", rows[0].DurationMS)
	}
}

// 케이스13 — InsertDuplicatePath 는 정상 멱등이므로 커서를 전진시키지 않는다.
// 전진시키면 다음 세그먼트가 번호를 하나 건너뛴다.
func TestCase13DuplicatePathDoesNotAdvanceCursor(t *testing.T) {
	f := newFixture(t, 4000)
	seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
	f.mustHandle(seg)

	before := *f.ix.cursors["s1"]
	// 메모리 이력을 비워 H2 를 통과시키고 DB 의 UNIQUE 만 남긴다.
	delete(f.ix.indexed["s1"], seg.Path)
	f.mustHandle(seg)
	after := *f.ix.cursors["s1"]

	if after.NextSeq != before.NextSeq {
		t.Fatalf("NextSeq = %d, want %d (전진 금지)", after.NextSeq, before.NextSeq)
	}
	if n := len(f.store.records("s1")); n != 1 {
		t.Fatalf("행 수 = %d, want 1", n)
	}
}

// 케이스14 — InsertSeqConflict 는 LoadCursor 재적재 후 1회 재시도해 성공한다.
func TestCase14SeqConflictReloadsCursorAndSucceeds(t *testing.T) {
	f := newFixture(t, 4000)
	// 다른 쓰기자가 이미 seq 0 을 써 버린 상황을 DB 에 심는다.
	f.store.seed(index.Record{
		StreamID: "s1", Seq: 0, StartPTSMS: 0, StartWallUTC: baseWall,
		DurationMS: 4000, LocalPath: "/다른/쓰기자.mp4", UploadState: index.UploadStatePending, Bytes: 10,
	})
	// 커서만 낡은 상태로 만든다(seq 0 을 다음 번호로 알고 있다).
	f.ix.cursors["s1"] = &index.Cursor{NextSeq: 0}
	f.ix.indexed["s1"] = map[string]struct{}{}

	seg := f.segment("s1", segName(baseWall, 4*time.Second), 1000, recording.ReasonNextFile)
	f.mustHandle(seg)

	rows := f.store.records("s1")
	if len(rows) != 2 {
		t.Fatalf("행 수 = %d, want 2", len(rows))
	}
	if rows[1].Seq != 1 {
		t.Fatalf("재시도 seq = %d, want 1 (= 재적재한 커서의 MAX+1)", rows[1].Seq)
	}
}

// 케이스15 — 재적재 후에도 seq 가 충돌하면 ERROR 후 에러를 올린다(프로세스 종료 경로).
// 조용한 무한 재시도로 구현되면 G3 가 깨진다.
func TestCase15SeqConflictTwiceReturnsFatalError(t *testing.T) {
	f := newFixture(t, 4000)
	// 재적재해도 다른 쓰기자가 계속 seq 를 선점하는 상황.
	f.store.alwaysSeqConflict = true

	seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
	err := f.handle(seg)

	if err == nil {
		t.Fatal("에러를 기대했으나 nil — 프로세스 종료 경로가 타지 않았다")
	}
	if n := f.logs.count(slog.LevelError, "seq_conflict_unrecoverable"); n != 1 {
		t.Fatalf("seq_conflict_unrecoverable = %d건, want 1", n)
	}
	// 재시도는 정확히 1회여야 한다. 반복 재시도는 seq 경합이며 G3 를 위협한다.
	if f.store.insertCalls != 2 {
		t.Fatalf("Insert 호출 = %d, want 2 (최초 1회 + 재적재 후 1회)", f.store.insertCalls)
	}
	if f.store.loadCursorCalls < 2 {
		t.Fatalf("LoadCursor 호출 = %d, want >= 2 (재적재가 일어나야 한다)", f.store.loadCursorCalls)
	}
}

// 케이스18 — 폭 가드는 정문(H7)과 뒷문(correctTail) 양쪽에 있어야 한다.
func TestCase18WidthGuardOnBothPaths(t *testing.T) {
	invalid := []struct {
		name string
		val  int64
	}{
		{"영", 0},
		{"음수", -1},
		{"int32_초과", int64(math.MaxInt32) + 1},
	}

	for _, tc := range invalid {
		t.Run("정문_H7_"+tc.name, func(t *testing.T) {
			f := newFixture(t)
			f.probe.vals = []int64{tc.val}
			f.opt.ReprobeMaxAttempts = 0
			f.reload()

			f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile))

			if n := f.logs.count(slog.LevelError, "invalid_duration"); n != 1 {
				t.Fatalf("invalid_duration = %d건, want 1", n)
			}
			if n := len(f.store.records("s1")); n != 0 {
				t.Fatalf("행 수 = %d, want 0 — 잘못된 길이가 INSERT 됐다", n)
			}
		})

		t.Run("뒷문_correctTail_"+tc.name, func(t *testing.T) {
			f := newFixture(t, 4000)
			seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
			f.mustHandle(seg)

			f.probe.vals = []int64{tc.val}
			f.probe.calls = 0
			f.makeFile("s1", segName(baseWall, 0), 2500)

			seg.Reason = recording.ReasonRegrown
			f.mustHandle(seg)

			if n := f.logs.count(slog.LevelError, "tail_probe_invalid"); n != 1 {
				t.Fatalf("tail_probe_invalid = %d건, want 1", n)
			}
			if len(f.store.updateTail) != 0 {
				t.Fatalf("UpdateTail 호출 = %d, want 0 — 뒷문에 검문소가 없다", len(f.store.updateTail))
			}
		})
	}
}

// 케이스19 — 두 스트림의 커서·학습·failStreak·reprobeDisabled 가 서로 독립이다.
// 전역 변수 하나로 뭉갠 구현도 단일 스트림 테스트는 전부 통과하므로 여기서 따로 찌른다.
func TestCase19IndexerStateIsPerStream(t *testing.T) {
	f := newFixture(t, 4000)

	// 교차 처리: s1 두 개, s2 한 개를 번갈아 넣는다.
	f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile))
	f.mustHandle(f.segment("s2", segName(baseWall, 30*time.Second), 1000, recording.ReasonNextFile))
	f.mustHandle(f.segment("s1", segName(baseWall, 4*time.Second), 1000, recording.ReasonNextFile))

	s1 := f.store.records("s1")
	s2 := f.store.records("s2")
	if len(s1) != 2 || len(s2) != 1 {
		t.Fatalf("행 수 s1=%d s2=%d, want 2/1", len(s1), len(s2))
	}
	if s1[0].Seq != 0 || s1[1].Seq != 1 {
		t.Fatalf("s1 seq = %d,%d, want 0,1", s1[0].Seq, s1[1].Seq)
	}
	if s2[0].Seq != 0 {
		t.Fatalf("s2 seq = %d, want 0 — 다른 스트림의 커서를 나눠 썼다", s2[0].Seq)
	}
	if s2[0].StartPTSMS != 0 {
		t.Fatalf("s2 start_pts_ms = %d, want 0", s2[0].StartPTSMS)
	}
	if s2[0].IsDiscontinuity {
		t.Error("s2 의 첫 세그먼트가 s1 커서와 비교돼 불연속 판정을 받았다")
	}

	// 학습·브레이크 상태도 스트림별이어야 한다.
	f.ix.failStreak["s1"] = 3
	f.ix.reprobeDisabled["s1"] = true
	if f.ix.reprobeDisabled["s2"] {
		t.Error("reprobeDisabled 가 스트림별이 아니다")
	}
	if f.ix.failStreak["s2"] != 0 {
		t.Error("failStreak 가 스트림별이 아니다")
	}
	if f.ix.expectedMS("s2") != f.opt.ExpectedDurationMS {
		t.Error("학습값이 스트림별이 아니다")
	}
}

// H5 상한 소진 — 계속 자라는 파일은 커밋하지 않고 워처에 되돌린다.
// 잘린 값을 INSERT 하면 교정망으로도 되돌릴 수 없는 구간이 생긴다.
func TestHandleUnsettledGivesUpAndReadopts(t *testing.T) {
	f := newFixture(t, 4000)
	seg := f.segment("s1", segName(baseWall, 0), 100, recording.ReasonNextFile)
	f.probe.growTo = seg.Path // 프로브가 불릴 때마다 파일이 자란다

	f.mustHandle(seg)

	if n := f.logs.count(slog.LevelError, "unsettled_giving_up"); n != 1 {
		t.Fatalf("unsettled_giving_up = %d건, want 1", n)
	}
	if f.adopter.count() != 1 {
		t.Fatalf("Adopt 호출 = %d, want 1", f.adopter.count())
	}
	if n := len(f.store.records("s1")); n != 0 {
		t.Fatalf("행 수 = %d, want 0", n)
	}
}

// H9 재시도 — DB 오류가 상한까지 이어지면 에러를 올린다(D8: 죽고 재기동해 Scan 으로 복구).
func TestHandleInsertRetryExhaustionReturnsError(t *testing.T) {
	f := newFixture(t, 4000)
	f.store.insertErrs = []error{errStoreDown, errStoreDown, errStoreDown, errStoreDown, errStoreDown}

	err := f.handle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile))
	if err == nil {
		t.Fatal("에러를 기대했으나 nil")
	}
	if f.store.insertCalls != f.opt.InsertRetryMax {
		t.Fatalf("Insert 호출 = %d, want %d", f.store.insertCalls, f.opt.InsertRetryMax)
	}
}

// correctTail 이 꼬리가 아닌 행에는 손대지 않는다.
func TestCorrectTailRejectsNonTail(t *testing.T) {
	f := newFixture(t, 4000)
	f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile))

	stale := f.segment("s1", segName(baseWall, 4*time.Second), 2000, recording.ReasonRegrown)
	f.mustHandle(stale)

	if n := f.logs.count(slog.LevelError, "regrow_not_tail"); n != 1 {
		t.Fatalf("regrow_not_tail = %d건, want 1", n)
	}
	if len(f.store.updateTail) != 0 {
		t.Fatalf("UpdateTail 호출 = %d, want 0", len(f.store.updateTail))
	}
}

// correctTail 이 이미 업로드된 행에는 손대지 않는다.
func TestCorrectTailIgnoresUploadedTail(t *testing.T) {
	f := newFixture(t, 4000)
	path := f.makeFile("s1", segName(baseWall, 0), 2500)
	f.store.seed(index.Record{
		StreamID: "s1", Seq: 0, StartWallUTC: baseWall, DurationMS: 4000,
		LocalPath: path, UploadState: index.UploadState("uploaded"), Bytes: 1000,
	})

	seg, err := recording.ParseSegmentPath(f.root, path)
	if err != nil {
		t.Fatalf("ParseSegmentPath 실패: %v", err)
	}
	seg.Reason = recording.ReasonRegrown
	f.mustHandle(seg)

	if n := f.logs.count(slog.LevelWarn, "regrow_after_upload_ignored"); n != 1 {
		t.Fatalf("regrow_after_upload_ignored = %d건, want 1", n)
	}
	if len(f.store.updateTail) != 0 {
		t.Fatalf("UpdateTail 호출 = %d, want 0", len(f.store.updateTail))
	}
}

// UpdateTail 이 DB 가드에 걸려 거부되면 ERROR 를 남긴다.
func TestCorrectTailLogsWhenDatabaseRejects(t *testing.T) {
	f := newFixture(t, 4000)
	seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
	f.mustHandle(seg)

	rejected := false
	f.store.updateTailOK = &rejected
	f.probe.vals = []int64{6000}
	f.probe.calls = 0
	f.makeFile("s1", segName(baseWall, 0), 2500)

	seg.Reason = recording.ReasonRegrown
	f.mustHandle(seg)

	if n := f.logs.count(slog.LevelError, "tail_update_rejected"); n != 1 {
		t.Fatalf("tail_update_rejected = %d건, want 1", n)
	}
}

// Handle 은 단일 호출자 규약(D10)을 전제한다. 이 테스트는 정상 경로에서 ERROR 가
// 한 건도 나지 않는다는 것 — 곧 AC4 의 "침묵 실패가 아님"의 단위 테스트 판 — 을 확인한다.
func TestHappyPathProducesNoErrorLogs(t *testing.T) {
	f := newFixture(t, 4000)
	for i := range 5 {
		seg := f.segment("s1", segName(baseWall, time.Duration(i)*4*time.Second), 1000, recording.ReasonNextFile)
		f.mustHandle(seg)
	}
	if n := f.logs.errorCount(); n != 0 {
		t.Fatalf("ERROR 로그 = %d건, want 0", n)
	}
	rows := f.store.records("s1")
	for i, r := range rows {
		if r.Seq != int64(i) {
			t.Fatalf("seq[%d] = %d", i, r.Seq)
		}
		if want := int64(i) * 4000; r.StartPTSMS != want {
			t.Fatalf("start_pts_ms[%d] = %d, want %d", i, r.StartPTSMS, want)
		}
	}
}

var _ = context.Background

// ---------------------------------------------------------------------------
// 검수 지적 회귀 테스트
// ---------------------------------------------------------------------------

// 지적5 — poison pill INSERT.
// 데이터·무결성·한도 오류는 몇 번을 다시 넣어도 같은 결과다. 재시도하면 그 세그먼트 하나가
// 파이프라인 전체를 30초씩 붙잡고, 재시도 상한을 소진하면 프로세스까지 죽인다.
// 한 번만 시도하고 그 세그먼트만 격리한 뒤 다음 것으로 넘어가야 한다.
func TestFixPoisonInsertIsIsolatedWithoutRetry(t *testing.T) {
	poison := []struct {
		name string
		code string
	}{
		{"클래스22_데이터오류", "22021"},
		{"클래스23_무결성위반_23505_제외", "23514"},
		{"클래스54_한도초과", "54000"},
	}

	for _, tc := range poison {
		t.Run(tc.name, func(t *testing.T) {
			f := newFixture(t, 4000)
			f.store.insertErrs = []error{&pgconn.PgError{Code: tc.code, Message: "테스트용"}}

			seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
			if err := f.handle(seg); err != nil {
				t.Fatalf("프로세스를 죽이면 안 된다: %v", err)
			}

			if f.store.insertCalls != 1 {
				t.Fatalf("Insert 호출 = %d, want 1 (재시도 금지)", f.store.insertCalls)
			}
			if n := f.logs.count(slog.LevelError, "insert_poisoned"); n != 1 {
				t.Fatalf("insert_poisoned = %d건, want 1", n)
			}
			if cur := f.ix.cursors["s1"]; cur.Tail != nil || cur.NextSeq != 0 {
				t.Fatalf("커서가 전진했다: NextSeq=%d Tail=%v", cur.NextSeq, cur.Tail)
			}
		})
	}
}

// 지적5 — 연결·자원 계열과 비-PgError 는 종전대로 재시도한다.
func TestFixTransientInsertErrorsStillRetry(t *testing.T) {
	transient := []struct {
		name string
		err  error
	}{
		{"클래스08_연결", &pgconn.PgError{Code: "08006"}},
		{"클래스53_자원부족", &pgconn.PgError{Code: "53300"}},
		{"클래스57_운영자개입", &pgconn.PgError{Code: "57P01"}},
		{"비PgError", errStoreDown},
	}

	for _, tc := range transient {
		t.Run(tc.name, func(t *testing.T) {
			f := newFixture(t, 4000)
			// 두 번 실패한 뒤 성공한다.
			f.store.insertErrs = []error{tc.err, tc.err}

			seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
			if err := f.handle(seg); err != nil {
				t.Fatalf("재시도로 회복했어야 한다: %v", err)
			}
			if f.store.insertCalls != 3 {
				t.Fatalf("Insert 호출 = %d, want 3", f.store.insertCalls)
			}
			if n := len(f.store.records("s1")); n != 1 {
				t.Fatalf("행 수 = %d, want 1", n)
			}
		})
	}
}

// 지적5 — 그 밖의 PgError(문법·권한 등)는 설정이 잘못됐다는 뜻이므로 재시도하지 않고 올린다.
func TestFixUnexpectedPgErrorIsFatalWithoutRetry(t *testing.T) {
	f := newFixture(t, 4000)
	f.store.insertErrs = []error{&pgconn.PgError{Code: "42601", Message: "syntax error"}}

	err := f.handle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile))
	if err == nil {
		t.Fatal("에러를 기대했으나 nil")
	}
	if f.store.insertCalls != 1 {
		t.Fatalf("Insert 호출 = %d, want 1 (재시도 금지)", f.store.insertCalls)
	}
}

// 지적8 — 음수 drift 는 톨러런스 이내여도 흔적을 남긴다.
//
// 다만 ERROR 가 아니라 DEBUG 다. 실측 근거: 60초 방송 1회에서 세그먼트 간 drift 14건이
// 전부 음수였고 그중 13건이 -1 ~ -22ms 였다. duration 은 미디어 타임라인에서, wall 은
// 파일명에서 오므로 작은 음수는 상시 발생하는 측정 입도 차이다. 이것을 ERROR 로 올리면
// 60초마다 14건이 쏟아져 "ERROR = 사람이 봐야 함"이라는 신호가 무너진다.
// 톨러런스를 넘는 음수 drift 는 종전대로 ERROR 다.
func TestFixNegativeDriftWithinToleranceIsLogged(t *testing.T) {
	f := newFixture(t, 4000, 4000)
	f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile))

	// 기대 시각은 base+4s, 실제는 base+3.98s -> drift = -20ms (톨러런스 이내)
	f.mustHandle(f.segment("s1", segName(baseWall, 3980*time.Millisecond), 1000, recording.ReasonNextFile))

	if n := f.logs.count(slog.LevelDebug, "negative_drift_within_tolerance"); n != 1 {
		t.Fatalf("negative_drift_within_tolerance = %d건, want 1", n)
	}
	if n := f.logs.errorCount(); n != 0 {
		t.Fatalf("ERROR = %d건, want 0 — 상시 발생하는 작은 음수 drift 가 ERROR 로 올라갔다", n)
	}
	rows := f.store.records("s1")
	if rows[1].IsDiscontinuity {
		t.Error("톨러런스 이내인데 불연속 판정을 받았다")
	}
	if rows[1].StartPTSMS != 4000 {
		t.Errorf("start_pts_ms = %d, want 4000", rows[1].StartPTSMS)
	}
}

// 지적2 — H4 되돌리기 경로가 무음이면 "왜 안 들어오지"를 아무도 모른다.
func TestFixIdleReadoptIsLogged(t *testing.T) {
	f := newFixture(t, 4000)
	seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonIdle)
	f.touch(seg.Path, time.Now())

	f.mustHandle(seg)

	if n := f.logs.count(slog.LevelDebug, "idle_readopted"); n != 1 {
		t.Fatalf("idle_readopted = %d건, want 1", n)
	}
}

// 지적12 — 상향 학습이 실제로 반영되는지 값으로 단정한다.
// 이전 테스트는 "내려가지 않는다"만 봐서 학습이 상수 반환이어도 통과했다.
func TestFixLearningRaisesThresholdAndIsPerStream(t *testing.T) {
	f := newFixture(t)
	f.probe.vals = []int64{6000}

	// s1 에만 6000ms 표본을 LearnSampleCount 만큼 넣는다.
	for i := range f.opt.LearnSampleCount {
		f.mustHandle(f.segment("s1", segName(baseWall, time.Duration(i)*6*time.Second), 1000, recording.ReasonNextFile))
	}

	if got := f.ix.expectedMS("s1"); got != 6000 {
		t.Fatalf("s1 학습된 기대 길이 = %d, want 6000 — 상향 학습이 반영되지 않았다", got)
	}
	margin := f.opt.ExpectedDurationMS - f.opt.SuspectBelowMS
	if got, want := f.ix.suspectBelowMS("s1"), int64(6000)-margin; got != want {
		t.Fatalf("s1 의심 하한 = %d, want %d", got, want)
	}

	// s2 는 아무 표본도 없으므로 초기값 그대로여야 한다.
	if got := f.ix.expectedMS("s2"); got != f.opt.ExpectedDurationMS {
		t.Fatalf("s2 기대 길이 = %d, want %d — 학습이 스트림 경계를 넘었다", got, f.opt.ExpectedDurationMS)
	}
	if got := f.ix.suspectBelowMS("s2"); got != f.opt.SuspectBelowMS {
		t.Fatalf("s2 의심 하한 = %d, want %d", got, f.opt.SuspectBelowMS)
	}
}

// LOW — 재프로브가 필요 없을 만큼 정상인 세그먼트도 failStreak 를 리셋한다.
// 그러지 않으면 과거의 짧은 조각 몇 개 때문에 승격이 영구 비활성화된 채 남는다.
func TestFixHealthySegmentResetsFailStreak(t *testing.T) {
	f := newFixture(t, 4000)
	f.ix.failStreak["s1"] = 2

	f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile))

	if got := f.ix.failStreak["s1"]; got != 0 {
		t.Fatalf("failStreak = %d, want 0", got)
	}
}

// LOW — 기본 백오프 간격의 총합이 계약의 약 30초와 맞는지 확인한다.
func TestFixDefaultBackoffTotalsAboutThirtySeconds(t *testing.T) {
	opt := DefaultOptions()
	total := time.Duration(0)
	backoff := opt.InsertRetryBase
	for range opt.InsertRetryMax - 1 { // 시도 사이의 간격 수 = 시도 수 - 1
		total += backoff
		backoff *= 2
	}
	if total != 30*time.Second {
		t.Fatalf("백오프 총합 = %v, want 30s", total)
	}
}
