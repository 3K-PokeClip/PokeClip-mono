package indexer

import (
	"log/slog"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxhook"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// ---------------------------------------------------------------------------
// 이 파일의 테스트는 전부 ix.Handle / ix.HandleHook 진입점으로만 구동한다.
// commit 을 직접 부르면 H2 조기 반환 같은 도달성 결함을 가리는 거짓 그린이 된다.
//
// 시각 설계 규칙(중요): 세그먼트 간 벽시계 간격은 항상 DriftToleranceMS(1500ms) 이내로
// 잡는다. 간격을 크게 잡으면 기존 드리프트 판정만으로 is_discontinuity 가 참이 되어
// "훅 덕분에 참"인지 구분할 수 없는 거짓 그린이 된다 — 실제 0.9s 재접속이 현행 판정을
// 통과한다는 것이 이 기능의 존재 이유다.
// ---------------------------------------------------------------------------

const seg4s = 4 * time.Second

// indexOne 은 세그먼트 1개를 워처 경로(ReasonNextFile)로 넣는다.
func (f *fixture) indexOne(streamID string, offset time.Duration) recording.Segment {
	f.t.Helper()
	seg := f.segment(streamID, segName(baseWall, offset), 1000, recording.ReasonNextFile)
	f.mustHandle(seg)
	return seg
}

// discont 는 그 경로 행의 is_discontinuity 를 돌려준다.
func (f *fixture) discont(streamID, path string) bool {
	f.t.Helper()
	for _, r := range f.store.records(streamID) {
		if r.LocalPath == path {
			return r.IsDiscontinuity
		}
	}
	f.t.Fatalf("행을 찾지 못했다: %s", path)
	return false
}

// T4 — SeqConflict 재적재 후 재시도에서도 무장 판정은 재계산하지 않고 그대로 쓴다.
// 재계산하면 재적재로 바뀐 커서 때문에 판정이 흔들린다.
func TestSeqConflictRetryKeepsBreakFlagAndConsumesOnce(t *testing.T) {
	f := newHookFixture(t)
	f.indexOne("demo", 0)

	// 다른 쓰기자가 seq 1 을 선점한 상황을 만든다(= 메모리 커서가 DB 보다 뒤처짐).
	f.store.seed(index.Record{
		StreamID: "demo", Seq: 1, StartPTSMS: 500, StartWallUTC: baseWall.Add(500 * time.Millisecond),
		DurationMS: 4000, LocalPath: "/다른/쓰기자/행.mp4", UploadState: index.UploadStatePending,
	})

	f.arm("demo", seg4s, seg4s+900*time.Millisecond)
	seg := f.segment("demo", segName(baseWall, seg4s+900*time.Millisecond), 1000, recording.ReasonNextFile)
	f.mustHandle(seg)

	if !f.discont("demo", seg.Path) {
		t.Error("재시도 후 행의 is_discontinuity 가 false 다 — 무장 판정이 재계산됐다")
	}
	if n := len(f.ix.breaks["demo"]); n != 0 {
		t.Errorf("breaks 길이 = %d, want 0 — 경계가 해제되지 않았다", n)
	}
	if n := f.logs.count(slog.LevelInfo, "hook_break_consumed"); n != 1 {
		t.Errorf("hook_break_consumed = %d회, want 1회(재시도해도 소비는 1회)", n)
	}
}

// T5 — poison 은 무장이 메모리에 잔존하는 대표 경로다(잔존 경로 셋의 목록은
// commit 의 poisoned 분기 주석 참조 — seq 충돌 재적재 뒤의 poison·H3 재검 물러남 포함).
// 재시도 소진은 commit 이 err 를 반환해 프로세스가 끝나므로 메모리 무장도 함께 소멸한다.
//
// 목이 돌려주는 에러는 SQLSTATE class 22(데이터 예외)여야 한다 — class 23(제약 위반)은
// fateFatal 로 분류돼 프로세스를 끝내므로 이 케이스가 아니다(indexer.go classifyInsertError).
func TestPoisonKeepsBreakArmedForNextSegment(t *testing.T) {
	f := newHookFixture(t)
	// 1번째 Insert(S1)는 통과, 2번째(S2)는 poison.
	f.store.insertErrs = []error{nil, &pgconn.PgError{Code: "22001", Message: "값이 너무 길다"}}

	f.indexOne("demo", 0)
	f.arm("demo", seg4s, seg4s+900*time.Millisecond)

	poisoned := f.segment("demo", segName(baseWall, seg4s+900*time.Millisecond), 1000, recording.ReasonNextFile)
	f.mustHandle(poisoned)

	if n := len(f.ix.breaks["demo"]); n != 1 {
		t.Fatalf("poison 후 breaks 길이 = %d, want 1(무장 잔존)", n)
	}

	// 다음 세그먼트가 소비한다. 아예 미표시보다 늦은 표시가 낫다.
	next := f.segment("demo", segName(baseWall, seg4s+1900*time.Millisecond), 1000, recording.ReasonNextFile)
	f.mustHandle(next)

	if !f.discont("demo", next.Path) {
		t.Error("다음 세그먼트가 무장을 소비하지 않았다")
	}
	if n := len(f.ix.breaks["demo"]); n != 0 {
		t.Errorf("breaks 길이 = %d, want 0", n)
	}
}

// T6 — 시각 조건을 충족했는데 꼬리가 없으면 그 조각은 seq 0 이라 불연속 표시가 무의미하다.
// 플래그 없이 **해제**한다. 소비도 해제도 안 하면 무장이 이월돼 다음 조각에 오탐이 붙는다.
func TestNilTailDiscardsBreakWithoutFlag(t *testing.T) {
	f := newHookFixture(t)
	f.arm("demo", -time.Second, -100*time.Millisecond)

	first := f.segment("demo", segName(baseWall, 0), 1000, recording.ReasonNextFile)
	f.mustHandle(first)

	if f.discont("demo", first.Path) {
		t.Error("첫 세그먼트(seq 0)에 불연속이 붙었다")
	}
	if n := len(f.ix.breaks["demo"]); n != 0 {
		t.Fatalf("breaks 길이 = %d, want 0 — 무장이 이월됐다", n)
	}
	if n := f.logs.count(slog.LevelInfo, "hook_break_discarded"); n != 1 {
		t.Errorf("hook_break_discarded = %d회, want 1회", n)
	}

	// 이월이 없었는지 다음 조각으로 확인한다.
	second := f.segment("demo", segName(baseWall, seg4s), 1000, recording.ReasonNextFile)
	f.mustHandle(second)
	if f.discont("demo", second.Path) {
		t.Error("다음 조각에 무장이 이월돼 오탐이 붙었다")
	}
}

// T7 — 연쇄 재접속. 단일 포인터였다면 경계₂ 가 경계₃ 에 덮여 영구 미탐이 된다.
func TestChainedBreaksMatchTheirOwnSegments(t *testing.T) {
	f := newHookFixture(t)
	f.indexOne("demo", 0)

	// 세션2 경계: offline @4s → online @4.9s / 세션3 경계: offline @8.9s → online @9.8s
	f.arm("demo", seg4s, seg4s+900*time.Millisecond)
	f.arm("demo", 8900*time.Millisecond, 9800*time.Millisecond)
	if n := len(f.ix.breaks["demo"]); n != 2 {
		t.Fatalf("무장 경계 = %d건, want 2건", n)
	}

	s2 := f.segment("demo", segName(baseWall, seg4s+900*time.Millisecond), 1000, recording.ReasonNextFile)
	f.mustHandle(s2)
	s3 := f.segment("demo", segName(baseWall, 9800*time.Millisecond), 1000, recording.ReasonNextFile)
	f.mustHandle(s3)

	if !f.discont("demo", s2.Path) {
		t.Error("세션2 첫 조각이 경계₂ 를 소비하지 못했다")
	}
	if !f.discont("demo", s3.Path) {
		t.Error("세션3 첫 조각이 경계₃ 를 소비하지 못했다")
	}
	if n := len(f.ix.breaks["demo"]); n != 0 {
		t.Errorf("breaks 길이 = %d, want 0", n)
	}
	if n := f.logs.count(slog.LevelInfo, "hook_break_dropped"); n != 0 {
		t.Errorf("hook_break_dropped = %d회, want 0회 — 멀쩡한 경계를 버렸다", n)
	}
}

// T8 — 세그먼트가 한 건도 오지 않은 세션의 경계는 다음 소비 때 함께 정리된다.
// 정리하지 않으면 큐가 새고, 나중 조각에 옛 경계가 잘못 붙는다.
func TestOlderUnconsumedBreaksAreDroppedOnConsume(t *testing.T) {
	f := newHookFixture(t)
	f.indexOne("demo", 0)

	f.arm("demo", seg4s, seg4s+900*time.Millisecond)            // 세그먼트가 오지 않을 세션
	f.arm("demo", 8900*time.Millisecond, 9800*time.Millisecond) // 실제로 조각이 오는 세션

	s3 := f.segment("demo", segName(baseWall, 9800*time.Millisecond), 1000, recording.ReasonNextFile)
	f.mustHandle(s3)

	if !f.discont("demo", s3.Path) {
		t.Error("가장 최신 경계를 소비하지 못했다")
	}
	if n := len(f.ix.breaks["demo"]); n != 0 {
		t.Errorf("breaks 길이 = %d, want 0 — 큐가 샜다", n)
	}
	if n := f.logs.count(slog.LevelInfo, "hook_break_dropped"); n != 1 {
		t.Errorf("hook_break_dropped = %d회, want 1회", n)
	}
}

// T9 — 정상 종료 시 이전 세션의 마지막 partial 조각도 offline 직후 complete 가 발화한다.
// StartWall > OfflineAt 조건이 없으면 그 조각이 새 세션의 표시를 가로챈다.
func TestPreviousSessionPartialDoesNotStealTheFlag(t *testing.T) {
	f := newHookFixture(t)
	f.indexOne("demo", 0)
	f.arm("demo", 8*time.Second, 8900*time.Millisecond)

	partial := f.segment("demo", segName(baseWall, seg4s), 1000, recording.ReasonNextFile)
	f.mustHandle(partial)

	if f.discont("demo", partial.Path) {
		t.Error("이전 세션 partial 조각이 불연속으로 표시됐다")
	}
	if n := len(f.ix.breaks["demo"]); n != 1 {
		t.Errorf("breaks 길이 = %d, want 1 — 경계가 잘못 소비됐다", n)
	}
}

// T10 — Guard(20ms)는 파일명 시각 해상도와 훅 기록 시각의 미세 차이를 흡수한다.
// 실측 짝짓기 오차 ±2ms 를 포함하되 그 이상의 의미는 없다.
func TestGuardAbsorbsSmallEarlyOffsetButNotLargeOnes(t *testing.T) {
	tests := []struct {
		name        string
		onlineAt    time.Duration
		wantDiscont bool
	}{
		// 조각(+4.010s)이 online 보다 15ms 이르다 → Guard 20ms 안 → 소비.
		{"15ms 이른 조각", 4025 * time.Millisecond, true},
		// 조각이 online 보다 90ms 이르다 → Guard 밖 → 소비하지 않는다.
		{"90ms 이른 조각", 4100 * time.Millisecond, false},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f := newHookFixture(t)
			f.indexOne("demo", 0)
			f.arm("demo", seg4s, tc.onlineAt)

			seg := f.segment("demo", segName(baseWall, 4010*time.Millisecond), 1000, recording.ReasonNextFile)
			f.mustHandle(seg)

			if got := f.discont("demo", seg.Path); got != tc.wantDiscont {
				t.Errorf("is_discontinuity = %v, want %v", got, tc.wantDiscont)
			}
		})
	}
}

// T20 — 워처가 먼저 INSERT 한 뒤 무장되면 그 경계는 이미 지나간 것이다.
// 다음 조각에 넘기면 **영속 오표시**가 되므로 already_passed 로 폐기하고 미탐으로 강등한다.
//
// 반드시 Handle 전체 경로로 구동한다(H2 조기 반환 포함). commit 직접 호출은 거짓 그린이다.
func TestBreakArmedAfterSegmentIsDiscardedAsAlreadyPassed(t *testing.T) {
	f := newHookFixture(t)
	f.indexOne("demo", 0)

	// ① 워처가 세션2 첫 조각을 먼저 장부에 올린다(훅보다 빠른 경우).
	s2 := f.segment("demo", segName(baseWall, seg4s+900*time.Millisecond), 1000, recording.ReasonNextFile)
	f.mustHandle(s2)

	// ② 뒤늦게 세션 훅 쌍이 도착해 무장된다 → 무장 직후 reconcile 이 already_passed 로 폐기.
	f.arm("demo", seg4s, seg4s+900*time.Millisecond)

	if n := len(f.ix.breaks["demo"]); n != 0 {
		t.Fatalf("breaks 길이 = %d, want 0 — 지나간 경계가 남았다", n)
	}
	if n := f.logs.count(slog.LevelInfo, "hook_break_discarded"); n != 1 {
		t.Errorf("hook_break_discarded = %d회, want 1회", n)
	}

	// ③ 같은 조각이 훅 채널로도 도착한다 → H2 경로 중복으로 조기 반환.
	f.handleHook(mtxhook.Event{
		Kind: mtxhook.KindSegmentComplete, StreamID: "demo",
		At: baseWall.Add(seg4s + 900*time.Millisecond), SegmentPath: s2.Path,
	})

	// ④ 다음 조각은 표시되지 않아야 한다.
	s2next := f.segment("demo", segName(baseWall, 8900*time.Millisecond), 1000, recording.ReasonNextFile)
	f.mustHandle(s2next)

	if f.discont("demo", s2next.Path) {
		t.Error("지나간 경계가 다음 조각에 붙었다(영속 오표시)")
	}
	if n := len(f.store.records("demo")); n != 3 {
		t.Errorf("행 수 = %d, want 3 — 훅 중복이 흡수되지 않았다", n)
	}
}

// 무장이 없으면 판정은 기존 드리프트 규칙 그대로다(훅 유실 시 자연 강등, GH4).
func TestWithoutBreakDriftRuleIsUnchanged(t *testing.T) {
	f := newHookFixture(t)
	f.indexOne("demo", 0)

	// 0.9s 공백은 tolerance(1500ms) 이내라 현행 판정으로는 검출되지 않는다.
	// 이 미검출이 바로 훅 채널이 존재하는 이유다.
	seg := f.segment("demo", segName(baseWall, seg4s+900*time.Millisecond), 1000, recording.ReasonNextFile)
	f.mustHandle(seg)

	if f.discont("demo", seg.Path) {
		t.Error("무장이 없는데 불연속이 붙었다 — 드리프트 판정이 바뀌었다")
	}
}

// segment_indexed 의 reason 필드는 채널별 기여도(훅 vs 파일)를 재는 유일한 창이다.
// 이 필드가 없으면 훅 채널이 무징후로 죽어도 알 수 없다.
func TestSegmentIndexedLogCarriesReason(t *testing.T) {
	f := newHookFixture(t)
	path := f.makeFile("demo", segName(baseWall, 0), 1000)

	f.handleHook(mtxhook.Event{
		Kind: mtxhook.KindSegmentComplete, StreamID: "demo",
		At: baseWall, SegmentPath: path,
	})

	got := f.logs.attrs("segment_indexed")
	if got == nil {
		t.Fatal("segment_indexed 로그가 없다")
	}
	if got["reason"] != recording.ReasonHook {
		t.Errorf("segment_indexed.reason = %v(%T), want %d", got["reason"], got["reason"], recording.ReasonHook)
	}
}

// 항목 7 — **Handle 진입부** reconcileBreaks 가 정확성의 담당자임을 고정한다.
//
// 커서를 미리 적재해 두면 무장 직후 reconcile(조기 최적화)이 다 처리해 버려서,
// 진입부 배선을 지워도 전건 그린이 된다. 커서 미적재 상태에서 시작해야 진입부만이
// 유일한 방어선이 되고 그 배선이 실제로 검증된다.
func TestEntryPointReconcileDiscardsPassedBreakWithoutPreloadedCursor(t *testing.T) {
	f := newHookFixture(t)

	// DB 에만 행을 심는다 — 메모리 커서는 아직 이 스트림을 모른다.
	passedPath := f.makeFile("demo", segName(baseWall, seg4s+900*time.Millisecond), 1000)
	f.store.seed(index.Record{
		StreamID: "demo", Seq: 0, StartPTSMS: 0,
		StartWallUTC: baseWall.Add(seg4s + 900*time.Millisecond),
		DurationMS:   4000, LocalPath: passedPath, UploadState: index.UploadStatePending,
	})

	// 무장한다. 커서가 없으므로 결정 ②에 따라 무장 직후 reconcile 은 건너뛴다.
	f.arm("demo", seg4s, seg4s+900*time.Millisecond)
	if n := len(f.ix.breaks["demo"]); n != 1 {
		t.Fatalf("무장 직후 breaks = %d건, want 1건(커서 미적재라 정리를 건너뛴다)", n)
	}

	// 이미 장부에 오른 그 조각이 도착한다 → 진입부 reconcile 이 돌고, H2 로 조기 반환한다.
	seg, err := recording.ParseSegmentPath(f.root, passedPath)
	if err != nil {
		t.Fatalf("ParseSegmentPath 실패: %v", err)
	}
	seg.Reason = recording.ReasonNextFile
	f.mustHandle(seg)

	if n := len(f.ix.breaks["demo"]); n != 0 {
		t.Fatalf("breaks = %d건, want 0건 — 진입부 reconcile 이 돌지 않았다", n)
	}
	if n := f.logs.count(slog.LevelInfo, "hook_break_discarded"); n != 1 {
		t.Errorf("hook_break_discarded = %d회, want 1회", n)
	}

	// 지나간 경계가 다음 조각에 붙으면 영속 오표시다.
	// 꼬리(wall 4.9s, dur 4s)의 기대 시각과 가깝게 잡아 드리프트 판정이 끼어들지 않게 한다.
	next := f.segment("demo", segName(baseWall, 8900*time.Millisecond), 1000, recording.ReasonNextFile)
	f.mustHandle(next)
	if f.discont("demo", next.Path) {
		t.Error("지나간 경계가 다음 조각에 붙었다(영속 오표시)")
	}
}

// 항목 9 — Guard 부등호의 경계를 정확히 못 박는다.
// 소비 조건은 StartWall >= OnlineAt - Guard 이며 **경계값 포함**이다.
func TestGuardBoundaryIsInclusiveAtExactlyGuard(t *testing.T) {
	const segAt = 4010 * time.Millisecond // 드리프트 10ms — tolerance 안이라 훅만이 판정 근거다

	tests := []struct {
		name     string
		onlineAt time.Duration
		want     bool
	}{
		{"19ms 이른 조각(Guard 안)", segAt + 19*time.Millisecond, true},
		{"정확히 20ms 이른 조각(경계 포함)", segAt + 20*time.Millisecond, true},
		{"21ms 이른 조각(Guard 밖)", segAt + 21*time.Millisecond, false},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f := newHookFixture(t)
			f.indexOne("demo", 0)
			f.arm("demo", seg4s, tc.onlineAt)

			seg := f.segment("demo", segName(baseWall, segAt), 1000, recording.ReasonNextFile)
			f.mustHandle(seg)

			if got := f.discont("demo", seg.Path); got != tc.want {
				t.Errorf("is_discontinuity = %v, want %v", got, tc.want)
			}
		})
	}
}

// StartWall == OfflineAt 인 조각은 **이전 세션의 것**이다(엄격 부등호).
// 등호를 허용하면 정상 종료 직전의 마지막 partial 조각이 새 세션의 표시를 가로챈다.
func TestSegmentExactlyAtOfflineTimeIsNotConsumed(t *testing.T) {
	const segAt = 4010 * time.Millisecond

	f := newHookFixture(t)
	f.indexOne("demo", 0)
	f.arm("demo", segAt, segAt+5*time.Millisecond) // OfflineAt == 조각의 StartWall

	seg := f.segment("demo", segName(baseWall, segAt), 1000, recording.ReasonNextFile)
	f.mustHandle(seg)

	if f.discont("demo", seg.Path) {
		t.Error("StartWall == OfflineAt 인 조각이 소비됐다 — 이전 세션 조각을 가로챈다")
	}
	if n := len(f.ix.breaks["demo"]); n != 1 {
		t.Errorf("breaks = %d건, want 1건(소비되지 않아야 한다)", n)
	}
}

// 항목 11 — DuplicatePath 로 돌아온 경우의 해제 분기.
// 그 조각의 행이 무장 전에 이미 존재했다는 뜻이므로, 다음 조각에 넘기면 영속 오표시다.
func TestDuplicatePathReleasesBreakAsDiscarded(t *testing.T) {
	f := newHookFixture(t)
	f.indexOne("demo", 0) // 커서·이력 맵 적재

	// 메모리 이력이 모르는 행을 DB 에만 심는다 → H2 를 통과해 commit 까지 간다.
	dupPath := f.makeFile("demo", segName(baseWall, seg4s+900*time.Millisecond), 1000)
	f.store.seed(index.Record{
		StreamID: "demo", Seq: 9, StartPTSMS: 9000,
		StartWallUTC: baseWall.Add(seg4s + 900*time.Millisecond),
		DurationMS:   4000, LocalPath: dupPath, UploadState: index.UploadStatePending,
	})

	f.arm("demo", seg4s, seg4s+900*time.Millisecond)

	seg, err := recording.ParseSegmentPath(f.root, dupPath)
	if err != nil {
		t.Fatalf("ParseSegmentPath 실패: %v", err)
	}
	seg.Reason = recording.ReasonNextFile
	f.mustHandle(seg)

	if n := len(f.ix.breaks["demo"]); n != 0 {
		t.Fatalf("breaks = %d건, want 0건 — DuplicatePath 에서 해제하지 않았다", n)
	}
	got := f.logs.attrs("hook_break_discarded")
	if got == nil {
		t.Fatal("hook_break_discarded 가 없다")
	}
	if got["reason"] != "duplicate_path" {
		t.Errorf("hook_break_discarded.reason = %v, want duplicate_path", got["reason"])
	}

	// 다음 조각에 이월되지 않아야 한다.
	//
	// DuplicatePath 는 커서를 전진시키지 않으므로 꼬리는 여전히 첫 조각(wall 0, dur 4s)이다.
	// 다음 조각을 기대 시각(+4s) 근처로 잡아야 드리프트 판정이 끼어들어 참을 만드는
	// 거짓 실패를 피한다 — 이 테스트가 보려는 것은 오직 무장 이월 여부다.
	next := f.segment("demo", segName(baseWall, 5*time.Second), 1000, recording.ReasonNextFile)
	f.mustHandle(next)
	if f.discont("demo", next.Path) {
		t.Error("놓친 경계가 다음 조각에 붙었다(영속 오표시)")
	}
}

// 항목 14 — consumed 로그의 seq 는 실제로 표시된 행의 seq 와 같아야 한다.
// 이 등식이 깨지면 운영 검증에서 "어느 조각이 표시됐는지"를 로그로 짚을 수 없다.
func TestConsumedLogCarriesFlaggedRowSeq(t *testing.T) {
	f := newHookFixture(t)
	f.indexOne("demo", 0)
	f.arm("demo", seg4s, seg4s+900*time.Millisecond)

	seg := f.segment("demo", segName(baseWall, seg4s+900*time.Millisecond), 1000, recording.ReasonNextFile)
	f.mustHandle(seg)

	var flaggedSeq int64 = -1
	for _, r := range f.store.records("demo") {
		if r.IsDiscontinuity {
			flaggedSeq = r.Seq
		}
	}
	if flaggedSeq < 0 {
		t.Fatal("표시된 행이 없다")
	}
	got := f.logs.attrs("hook_break_consumed")
	if got == nil {
		t.Fatal("hook_break_consumed 가 없다")
	}
	if got["seq"] != flaggedSeq {
		t.Errorf("hook_break_consumed.seq = %v, want %d(표시된 행)", got["seq"], flaggedSeq)
	}
	if got["path"] != seg.Path {
		t.Errorf("hook_break_consumed.path = %v, want %q", got["path"], seg.Path)
	}
}

// 항목 12 — ReasonHook 은 승격(H6) 대상이 아니다.
// 훅 시점 파일은 이미 최종 크기라 승격이 방어할 위험이 없고, 방송 마지막 partial 조각도
// 같은 사유로 오므로 포함시키면 매 방송 재프로브가 헛돌아 reprobe_disabled 가 켜진다.
func TestReasonHookIsExcludedFromPromotion(t *testing.T) {
	f := newHookFixture(t, 3000) // suspect(3850) 미만 — ReasonNextFile 이면 재프로브가 돈다
	path := f.makeFile("demo", segName(baseWall, 0), 1000)

	f.handleHook(mtxhook.Event{
		Kind: mtxhook.KindSegmentComplete, StreamID: "demo",
		At: baseWall, SegmentPath: path,
	})

	if n := f.logs.count(slog.LevelWarn, "short_segment_after_reprobe"); n != 0 {
		t.Errorf("short_segment_after_reprobe = %d회, want 0회 — 훅 세그먼트가 승격 대상이 됐다", n)
	}
	if f.ix.failStreak["demo"] != 0 {
		t.Errorf("failStreak = %d, want 0", f.ix.failStreak["demo"])
	}
}

// ReasonHook 은 학습(기대 길이 상향) 표본에서도 빠진다.
func TestReasonHookIsExcludedFromLearning(t *testing.T) {
	f := newHookFixture(t, 5000) // 기대치(4000)보다 길다 — ReasonNextFile 이면 학습된다
	for i := range DefaultOptions().LearnSampleCount {
		path := f.makeFile("demo", segName(baseWall, time.Duration(i)*seg4s), 1000)
		f.handleHook(mtxhook.Event{
			Kind: mtxhook.KindSegmentComplete, StreamID: "demo",
			At: baseWall.Add(time.Duration(i) * seg4s), SegmentPath: path,
		})
	}

	if len(f.store.records("demo")) != DefaultOptions().LearnSampleCount {
		t.Fatalf("행 수 = %d — 의도한 경로가 아니다", len(f.store.records("demo")))
	}
	if got, ok := f.ix.learnedMS["demo"]; ok {
		t.Errorf("learnedMS = %d(설정됨), want 미설정 — 훅 세그먼트가 학습 표본에 들어갔다", got)
	}
	if len(f.ix.samples["demo"]) != 0 {
		t.Errorf("samples = %d건, want 0건", len(f.ix.samples["demo"]))
	}
}
