package indexer

// 조각 시각 판독 배선(POK-196 PDT 생산자 c5 · 계획 4절 c5 · 설계 r7 2.2 · 2.14 · 4절)의 시험이다. 판독은 timingFn 주입으로
// 대신하고(파일 I/O 없이 순서 · 칸만 잰다), 실물 조각을 fsop 관문으로 읽는 배선은 chain_test 의
// TestChainReadsRecorderMtxiThroughFsopGate 가 본다.

import (
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math"
	"os"
	"reflect"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// noTimingFacts 는 「머리말에 mtxi 도 영상도 없는 조각」 을 돌려주는 판독기다 — 결정적 부재라 조각은 그대로 커밋된다.
// 픽스처의 조각 파일은 0 바이트라 실물 판독(playback.ReadTiming)이면 모두 일시 실패(막힘)가 되므로, 판독과 무관한 시험의
// New 는 이것을 넘긴다.
func noTimingFacts(io.ReadSeeker) (playback.SegmentTiming, error) {
	return playback.SegmentTiming{}, nil
}

// errTransient 는 주입한 판독 일시 실패다.
var errTransient = fmt.Errorf("%w: 주입", playback.ErrTransientRead)

// failingReads 는 경로별로 남은 실패 횟수만큼 일시 실패를 내고, 그 뒤로는 ok 를 돌려준다. 음수면 늘 실패한다. tooLarge 인
// 경로는 크기 상한 초과(기다리지 않고 포기하는 실패)다.
type failingReads struct {
	fails    map[string]int
	tooLarge map[string]bool
	ok       playback.SegmentTiming
	calls    map[string]int
}

func (f *fixture) injectFailingReads() *failingReads {
	r := &failingReads{fails: map[string]int{}, tooLarge: map[string]bool{}, calls: map[string]int{}}
	f.ix.timingFn = func(p string) (playback.SegmentTiming, error) {
		r.calls[p]++
		if r.tooLarge[p] {
			return playback.SegmentTiming{}, fmt.Errorf("%w: %w: 주입", playback.ErrTransientRead, playback.ErrInputTooLarge)
		}
		switch n := r.fails[p]; {
		case n < 0:
			return playback.SegmentTiming{}, errTransient
		case n > 0:
			r.fails[p] = n - 1
			return playback.SegmentTiming{}, errTransient
		}
		return r.ok, nil
	}
	return r
}

// paths 는 스트림 장부 행의 경로를 seq 순으로 돌려준다.
func (f *fixture) paths(streamID string) []string {
	var out []string
	for _, r := range f.store.records(streamID) {
		out = append(out, r.LocalPath)
	}
	return out
}

// 계획 c5 시험 1 — 판독 일시 실패는 다른 답이 아니라 늦은 답이다(설계 2.14). 실패한 조각과 같은 스트림의 뒤 조각은
// 커밋하지도 Adopt 하지도 않고 미기록으로 두며, 다른 스트림은 계속 돈다. 다음 재스캔이 실패 조각을 먼저 다시 읽어
// 커밋하고 뒤 조각을 잇는다. 잡는 결함(m-R2): 뒤 조각을 먼저 커밋하면 다시 읽은 조각이 H3 늦은 조각 검사에서 영구히 빠진다.
func TestTransientReadHoldsStreamUntilRescanAndKeepsOrder(t *testing.T) {
	f := newFixture(t, 4000)
	r := f.injectFailingReads()
	first := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
	r.fails[first.Path] = 1

	f.mustHandle(first)
	later := f.segment("s1", segName(baseWall, 4*time.Second), 1000, recording.ReasonNextFile)
	f.mustHandle(later)
	f.mustHandle(f.segment("s2", segName(baseWall, 0), 1000, recording.ReasonNextFile))

	if n := len(f.store.records("s1")); n != 0 {
		t.Fatalf("막힌 스트림의 행 = %d, want 0 — 실패 조각 · 뒤 조각 모두 미기록이어야 한다", n)
	}
	if n := len(f.store.records("s2")); n != 1 {
		t.Fatalf("다른 스트림의 행 = %d, want 1 — 막힘은 그 스트림에만 선다", n)
	}
	if n := f.adopter.count(); n != 0 {
		t.Fatalf("Adopt %d회 — 판독 실패는 Adopt 없이 미기록으로 둔다(m3c)", n)
	}
	if n := f.logs.count(slog.LevelWarn, "timing_read_deferred"); n != 1 {
		t.Errorf("timing_read_deferred = %d건, want 1", n)
	}
	if n := f.logs.count(slog.LevelInfo, "timing_read_blocked"); n != 1 {
		t.Errorf("timing_read_blocked = %d건, want 1(뒤 조각 하나)", n)
	}

	f.scan()

	if got, want := f.paths("s1"), []string{first.Path, later.Path}; !reflect.DeepEqual(got, want) {
		t.Fatalf("재스캔 뒤 장부 = %v, want %v(실패 조각이 먼저)", got, want)
	}
	if n := f.logs.count(slog.LevelError, "late_segment_skipped"); n != 0 {
		t.Errorf("late_segment_skipped = %d건, want 0", n)
	}
	if n := f.logs.count(slog.LevelWarn, "timing_facts_partial"); n != 2*3 {
		// 성공한 판독은 mtxi · 영상이 없는 주입 값이라 행마다 no_mtxi · no_video 두 줄이다(세 행).
		t.Errorf("timing_facts_partial = %d건, want 6 — read_failed 가 끼면 안 된다", n)
	}
}

// 계획 c5 시험 2 — 다시 읽기 상한은 재스캔 한 번이다. 그때도 실패하면 칸 NULL 로 커밋한다(c5 (ㄱ)): w 는 파일명 시각(설계
// 2.2 mtxi 없음 식)이고 나머지 칸은 NULL 이며, 신호는 timing_facts_partial(까닭 read_failed)이다. 뒤 조각은 이어 커밋된다.
func TestTransientReadGivesUpWithNullFactsAfterOneRescan(t *testing.T) {
	f := newFixture(t, 4000)
	r := f.injectFailingReads()
	first := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
	r.fails[first.Path] = -1
	f.mustHandle(first)
	later := f.segment("s1", segName(baseWall, 4*time.Second), 1000, recording.ReasonNextFile)
	f.mustHandle(later)

	f.scan()

	rows := f.store.records("s1")
	if got, want := f.paths("s1"), []string{first.Path, later.Path}; !reflect.DeepEqual(got, want) {
		t.Fatalf("상한 뒤 장부 = %v, want %v", got, want)
	}
	if want := (index.TimingFacts{Wall: baseWall}); !reflect.DeepEqual(rows[0].Timing.Facts, want) {
		t.Errorf("포기한 행의 칸 = %+v, want %+v(w = 파일명 시각 · 나머지 NULL)", rows[0].Timing.Facts, want)
	}
	if got := rows[0].Timing.LegacyDurationMS; got != 4000 {
		t.Errorf("포기한 행의 mvhd 길이 = %d, want 4000", got)
	}
	if r.calls[first.Path] != 2 {
		t.Errorf("실패 조각 판독 %d회, want 2(처음 · 재스캔 한 번)", r.calls[first.Path])
	}
	partial := f.logs.attrsAll("timing_facts_partial")
	if len(partial) == 0 || partial[0]["reason"] != "read_failed" || partial[0]["w_source"] != "filename" {
		t.Errorf("첫 timing_facts_partial = %v, want reason read_failed · w_source filename", partial)
	}
}

// 재스캔이 아닌 재전달(워처 · 훅)에서 다시 실패해도 포기하지 않는다 — 상한은 재스캔 한 번이다(계획 c5 (ㄱ)). 재전달에서
// 성공하면 그때 커밋하고 표지를 내린다(멈춤이 짧아질 뿐 순서는 그대로 — 계획 c5 착수 체크 2).
func TestTransientReadRedeliveryRetriesWithoutGivingUp(t *testing.T) {
	f := newFixture(t, 4000)
	r := f.injectFailingReads()
	first := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonHook)
	r.fails[first.Path] = 2

	f.mustHandle(first)
	f.mustHandle(first)
	if n := len(f.store.records("s1")); n != 0 {
		t.Fatalf("재전달 실패 뒤 행 = %d, want 0 — 재스캔 전에는 포기하지 않는다", n)
	}
	f.mustHandle(first)
	if got := f.paths("s1"); !reflect.DeepEqual(got, []string{first.Path}) {
		t.Fatalf("재전달 성공 뒤 장부 = %v, want [%s]", got, first.Path)
	}
	f.mustHandle(f.segment("s1", segName(baseWall, 4*time.Second), 1000, recording.ReasonHook))
	if n := len(f.store.records("s1")); n != 2 {
		t.Errorf("표지가 내려간 뒤 뒤 조각 행 = %d, want 2", n)
	}
}

// 크기 상한 초과(ErrInputTooLarge)는 파일이 자라기만 하므로 다시 읽어도 같은 답이다 — 기다리지 않고 칸 NULL 로 커밋한다
// (이연 c2 cc L1 처분). 잡는 결함: 기다리면 큰 조각마다 재스캔 한 주기(약 5분)씩 장부가 밀린다.
func TestInputTooLargeCommitsNullFactsWithoutWaiting(t *testing.T) {
	f := newFixture(t, 4000)
	f.ix.timingFn = func(string) (playback.SegmentTiming, error) {
		return playback.SegmentTiming{}, fmt.Errorf("%w: %w: 17000000바이트", playback.ErrTransientRead, playback.ErrInputTooLarge)
	}
	seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)

	f.mustHandle(seg)

	rows := f.store.records("s1")
	if len(rows) != 1 {
		t.Fatalf("행 = %d, want 1 — 크기 상한 초과는 막지 않는다", len(rows))
	}
	if want := (index.TimingFacts{Wall: baseWall}); !reflect.DeepEqual(rows[0].Timing.Facts, want) {
		t.Errorf("칸 = %+v, want %+v", rows[0].Timing.Facts, want)
	}
	if got := f.logs.attrs("timing_facts_partial"); got["reason"] != "input_too_large" {
		t.Errorf("timing_facts_partial 까닭 = %v, want input_too_large", got["reason"])
	}
	if n := f.logs.count(slog.LevelWarn, "timing_read_deferred"); n != 0 {
		t.Errorf("timing_read_deferred = %d건, want 0", n)
	}
}

// 상한 뒤 포기한 스트림에서 다음 판독도 실패하면 기다리지 않는다 — 머리말 배치가 바뀐 녹화기(mtxi 44B 아님 등 다시 읽어도
// 같은 실패)면 조각마다 재스캔 한 주기씩 장부가 밀리기 때문이다. 판독이 한 번 성공하면 다시 기다리는 규칙으로 돌아온다.
func TestReadFailureAfterGiveUpCommitsWithoutWaiting(t *testing.T) {
	f := newFixture(t, 4000)
	r := f.injectFailingReads()
	first := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
	r.fails[first.Path] = -1
	f.mustHandle(first)
	f.scan() // 포기 — 칸 NULL 커밋

	second := f.segment("s1", segName(baseWall, 4*time.Second), 1000, recording.ReasonNextFile)
	r.fails[second.Path] = -1
	f.mustHandle(second)
	if n := len(f.store.records("s1")); n != 2 {
		t.Fatalf("포기 뒤 연이은 실패의 행 = %d, want 2 — 기다리지 않고 칸 NULL 로 커밋한다", n)
	}

	f.mustHandle(f.segment("s1", segName(baseWall, 8*time.Second), 1000, recording.ReasonNextFile)) // 성공
	fourth := f.segment("s1", segName(baseWall, 12*time.Second), 1000, recording.ReasonNextFile)
	r.fails[fourth.Path] = -1
	f.mustHandle(fourth)
	if n := len(f.store.records("s1")); n != 3 {
		t.Errorf("성공 뒤 실패의 행 = %d, want 3 — 성공이 끼면 다시 재스캔까지 기다린다", n)
	}
}

// 막힌 조각 파일이 사라지면(janitor · recordDeleteAfter) 재스캔이 표지를 내린다 — 내리지 않으면 그 스트림이 영구히 선다.
func TestRescanDropsBlockWhenBlockedFileVanishes(t *testing.T) {
	f := newFixture(t, 4000)
	r := f.injectFailingReads()
	first := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
	r.fails[first.Path] = -1
	f.mustHandle(first)
	later := f.segment("s1", segName(baseWall, 4*time.Second), 1000, recording.ReasonNextFile)
	f.mustHandle(later)
	if err := os.Remove(first.Path); err != nil {
		t.Fatalf("막힌 조각 지우기 실패: %v", err)
	}

	f.scan()

	if got := f.paths("s1"); !reflect.DeepEqual(got, []string{later.Path}) {
		t.Fatalf("표지를 내린 뒤 장부 = %v, want [%s]", got, later.Path)
	}
	if n := f.logs.count(slog.LevelWarn, "timing_read_block_dropped"); n != 1 {
		t.Errorf("timing_read_block_dropped = %d건, want 1", n)
	}
}

// 막힘 표지보다 이른 조각은 막지 않는다 — 순서를 지키는 데 필요한 것은 「표지 뒤 조각을 먼저 커밋하지 않는다」 뿐이다.
func TestBlockHoldsOnlySegmentsAfterBlockedOne(t *testing.T) {
	f := newFixture(t, 4000)
	r := f.injectFailingReads()
	blocked := f.segment("s1", segName(baseWall, 4*time.Second), 1000, recording.ReasonHook)
	r.fails[blocked.Path] = 1
	f.mustHandle(blocked)
	earlier := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonHook)
	f.mustHandle(earlier)
	if got := f.paths("s1"); !reflect.DeepEqual(got, []string{earlier.Path}) {
		t.Fatalf("이른 조각 처리 뒤 장부 = %v, want [%s]", got, earlier.Path)
	}
}

// 다른 조각의 판독 결과는 막힘 표지를 내리지 않는다(c5 검수 H1). X(4초) 막힘 → 이른 E(0초)가 늦게 도착해(워처 onAdopt
// deferSegment) 성공 · 포기 커밋 · 실패 뒤 성공 → 뒤 Y(8초)는 여전히 막힘 → X 재전달 성공 뒤 Y 가 잇는다. 잡는 결함: E 의
// 결과가 스트림 표지를 통째로 지우면 Y 가 먼저 커밋되고, 다시 읽은 X 는 H3 에서 영구히 빠진다.
func TestOtherSegmentResultKeepsBlock(t *testing.T) {
	tests := []struct {
		name  string
		early func(r *failingReads, f *fixture, e recording.Segment)
	}{
		{name: "이른 조각 성공", early: func(_ *failingReads, f *fixture, e recording.Segment) { f.mustHandle(e) }},
		{name: "이른 조각 포기 커밋", early: func(r *failingReads, f *fixture, e recording.Segment) {
			r.tooLarge[e.Path] = true
			f.mustHandle(e)
		}},
		{name: "이른 조각 실패 뒤 성공", early: func(r *failingReads, f *fixture, e recording.Segment) {
			r.fails[e.Path] = 1
			f.mustHandle(e)
			f.mustHandle(e)
		}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f := newFixture(t, 4000)
			r := f.injectFailingReads()
			x := f.segment("s1", segName(baseWall, 4*time.Second), 1000, recording.ReasonHook)
			r.fails[x.Path] = 1
			f.mustHandle(x)
			e := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonHook)
			tt.early(r, f, e)
			y := f.segment("s1", segName(baseWall, 8*time.Second), 1000, recording.ReasonHook)
			f.mustHandle(y)
			if got := f.paths("s1"); !reflect.DeepEqual(got, []string{e.Path}) {
				t.Fatalf("이른 조각 처리 뒤 장부 = %v, want [%s] — Y 는 X 표지에 막혀야 한다", got, e.Path)
			}

			f.mustHandle(x)
			f.mustHandle(y)
			if got, want := f.paths("s1"), []string{e.Path, x.Path, y.Path}; !reflect.DeepEqual(got, want) {
				t.Fatalf("X 재전달 성공 뒤 장부 = %v, want %v", got, want)
			}
			if n := f.logs.count(slog.LevelError, "late_segment_skipped"); n != 0 {
				t.Errorf("late_segment_skipped = %d건, want 0", n)
			}
		})
	}
}

// 표지 뒤에 시작하지 않은(낡은) 수집 결과는 표지를 내리지 않는다(c5 검수 H2). 수집 워커가 디렉터리를 읽은 뒤 X 가 생겨
// 막혔으면, 그 결과의 미기록 목록에 X 가 없어도 X 는 살아 있다 — 목록 부재는 파일 소멸이 아니다. 잡는 결함: 목록 부재만으로
// 표지를 내리면 뒤 Y 가 먼저 커밋되어 X 가 영구히 빠진다.
func TestStaleCollectKeepsBlockOfLiveFile(t *testing.T) {
	f := newFixture(t, 4000)
	r := f.injectFailingReads()
	x := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
	r.fails[x.Path] = 1
	f.mustHandle(x)
	y := f.segment("s1", segName(baseWall, 4*time.Second), 1000, recording.ReasonNextFile)

	// X 를 읽기 전에 디렉터리를 훑은 수집 결과의 적용 — 목록에는 Y 만 있다.
	y.Reason = recording.ReasonScan
	if err := f.ix.scanStream(t.Context(), f.root, "s1", []recording.Segment{y}); err != nil {
		t.Fatalf("scanStream 실패: %v", err)
	}
	if n := len(f.store.records("s1")); n != 0 {
		t.Fatalf("낡은 수집 적용 뒤 행 = %d, want 0 — 살아 있는 X 의 표지가 Y 를 막아야 한다", n)
	}
	if n := f.logs.count(slog.LevelWarn, "timing_read_block_dropped"); n != 0 {
		t.Errorf("timing_read_block_dropped = %d건, want 0", n)
	}

	f.scan()
	if got, want := f.paths("s1"), []string{x.Path, y.Path}; !reflect.DeepEqual(got, want) {
		t.Fatalf("온전한 재스캔 뒤 장부 = %v, want %v", got, want)
	}
}

// 막힌 경로가 이미 장부에 있으면(다른 경로로 등재 — 재적재 · 다른 쓰기자) 재스캔이 표지를 내린다. 파일은 살아 있어 stat 으로는
// 내릴 수 없으므로, 내리지 않으면 그 스트림이 영구히 선다.
func TestRescanDropsBlockWhenBlockedPathIsInLedger(t *testing.T) {
	f := newFixture(t, 4000)
	r := f.injectFailingReads()
	x := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
	r.fails[x.Path] = -1
	f.mustHandle(x)
	f.store.seed(index.Record{StreamID: "s1", Seq: 0, StartWallUTC: x.StartWall, DurationMS: 4000, LocalPath: x.Path,
		UploadState: index.UploadStatePending, Bytes: 1000})
	later := f.segment("s1", segName(baseWall, 4*time.Second), 1000, recording.ReasonNextFile)

	f.scan()

	if got, want := f.paths("s1"), []string{x.Path, later.Path}; !reflect.DeepEqual(got, want) {
		t.Fatalf("장부에 있는 막힌 경로의 표지를 내린 뒤 장부 = %v, want %v", got, want)
	}
	if n := f.logs.count(slog.LevelWarn, "timing_read_block_dropped"); n != 1 {
		t.Errorf("timing_read_block_dropped = %d건, want 1", n)
	}
}

// 판독 일시 실패와 막힘은 Adopt 를 늘리지 않는다 — 실패 조각 · 뒤 조각 · 재스캔 처리 모두에서 Adopt 0(m3c).
func TestReadDeferralNeverAdopts(t *testing.T) {
	f := newFixture(t, 4000)
	r := f.injectFailingReads()
	first := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
	r.fails[first.Path] = -1
	f.mustHandle(first)
	f.mustHandle(f.segment("s1", segName(baseWall, 4*time.Second), 1000, recording.ReasonIdle))
	f.scan()
	if n := f.adopter.count(); n != 0 {
		t.Errorf("Adopt %d회, want 0", n)
	}
}

// 계획 c5 시험 5 — 커밋 직후 메모리 커서 = 장부를 다시 읽은 커서(판 NULL). drift 가 톨러런스를 넘어도(6초) 꼬리 위치 ·
// 길이는 store 가 정한 커밋 값이고 IsDiscontinuity 는 참이다(위치는 인덱서가 정하지 않는다 — 계획 0절 6 · 설계 7.1).
// 잡는 결함: advance 가 rec 값(drift 가산)으로 전진하면 메모리 꼬리가 장부 꼬리와 갈라져 다음 행의 판단이 재기동 여부에
// 따라 달라진다.
func TestCommittedCursorEqualsReloadedCursor(t *testing.T) {
	f := newFixture(t, 4000, 4000)
	f.ix.timingFn = func(p string) (playback.SegmentTiming, error) { return sampleTiming(), nil }
	f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile))
	f.mustHandle(f.segment("s1", segName(baseWall, 10*time.Second), 1000, recording.ReasonNextFile))

	rows := f.store.records("s1")
	if !rows[1].IsDiscontinuity {
		t.Error("6초 drift 행의 is_discontinuity = false, want true")
	}
	reloaded, err := f.store.LoadCursor(t.Context(), "s1")
	if err != nil {
		t.Fatalf("LoadCursor 실패: %v", err)
	}
	if got := *f.ix.cursors["s1"]; !reflect.DeepEqual(got, reloaded) {
		t.Fatalf("메모리 커서 = %+v · 꼬리 %+v\n장부 커서 = %+v · 꼬리 %+v", got, *got.Tail, reloaded, *reloaded.Tail)
	}
	if got := reloaded.Tail.StartPTSMS; got != 4000 {
		t.Errorf("꼬리 위치 = %d, want 4000(store 판 NULL 위치 = 바로 앞 행 끝 — drift 를 더하지 않는다)", got)
	}
}

// sampleTiming 은 mtxi · 영상 · 소리가 다 있는 조각의 판독값이다(녹화기 A · 번호 7 · 누적 DTS 60초 · 영상 0.02초부터 4초).
func sampleTiming() playback.SegmentTiming {
	return playback.SegmentTiming{
		Mtxi:     &playback.Mtxi{RecorderID: recA, SegmentNumber: 7, DTS: time.Minute, NTP: baseWall.Add(250 * time.Microsecond)},
		Video:    &playback.TrackSpan{Start: 20 * time.Millisecond, End: 4020 * time.Millisecond},
		Audio:    &playback.TrackSpan{Start: 0, End: 4010 * time.Millisecond},
		Head:     playback.Track{ID: 2},
		HeadLead: 20 * time.Millisecond,
	}
}

func durPtr(d time.Duration) *time.Duration { return &d }

// 계획 c5 「TimingInput 을 만든다」 · 설계 2.2 w 식 · 2.3 NULL 규칙 · 2.6 영상 길이 — 판독값에서 장부 재료로 옮기는 표.
func TestTimingInputOf(t *testing.T) {
	// 파일명 시각은 mtxi NTP(baseWall + 0.25ms)와 ms 자리가 다르다 — w 의 출처를 바꾸는 변이가 값으로 드러나게.
	seg := recording.Segment{StreamID: "s1", Path: "/r/s1/x.mp4", StartWall: baseWall.Add(3*time.Millisecond + 400*time.Microsecond)}
	full := sampleTiming()
	rec, num := recA, int64(7)

	noMtxi := sampleTiming()
	noMtxi.Mtxi = nil

	noVideo := sampleTiming()
	noVideo.Video, noVideo.HeadLead, noVideo.Head = nil, 0, playback.Track{ID: 2}

	overflow := sampleTiming()
	overflow.Mtxi.SegmentNumber = math.MaxInt64 + 1

	tiny := sampleTiming()
	tiny.Video = &playback.TrackSpan{Start: 20 * time.Millisecond, End: 20*time.Millisecond + 11*time.Microsecond}

	headVideo := sampleTiming()
	headVideo.Head, headVideo.HeadLead = playback.Track{ID: 1, Video: true}, 0

	tests := []struct {
		name        string
		read        timingRead
		wantFacts   index.TimingFacts
		wantOwnMS   int32
		wantHead    bool
		wantSource  string
		wantReasons []string
	}{
		{
			name: "mtxi 영상 있음", read: timingRead{st: &full},
			wantFacts: index.TimingFacts{
				Wall: baseWall.Add(20 * time.Millisecond), Recorder: &rec, Number: &num,
				VideoFirst: durPtr(time.Minute + 20*time.Millisecond), VideoEnd: durPtr(time.Minute + 4020*time.Millisecond),
			},
			wantOwnMS: 4000, wantSource: "mtxi",
		},
		{
			name: "mtxi 없음 — 파일명 시각 + m", read: timingRead{st: &noMtxi},
			wantFacts: index.TimingFacts{Wall: baseWall.Add(23 * time.Millisecond)}, wantOwnMS: 4000,
			wantSource: "filename", wantReasons: []string{"no_mtxi"},
		},
		{
			name: "영상 없음 — mtxi NTP 내림", read: timingRead{st: &noVideo},
			wantFacts:  index.TimingFacts{Wall: baseWall, Recorder: &rec, Number: &num},
			wantSource: "mtxi", wantReasons: []string{"no_video"},
		},
		{
			name: "번호 넘침 — 네 칸 NULL · w 는 mtxi 식", read: timingRead{st: &overflow},
			wantFacts: index.TimingFacts{Wall: baseWall.Add(20 * time.Millisecond)}, wantOwnMS: 4000,
			wantSource: "mtxi", wantReasons: []string{"number_overflow"},
		},
		{
			name: "영상 1ms 미만 — 영상 길이 모름", read: timingRead{st: &tiny},
			wantFacts: index.TimingFacts{
				Wall: baseWall.Add(20 * time.Millisecond), Recorder: &rec, Number: &num,
				VideoFirst: durPtr(time.Minute + 20*time.Millisecond), VideoEnd: durPtr(time.Minute + 20*time.Millisecond + 11*time.Microsecond),
			},
			wantSource: "mtxi", wantReasons: []string{"under_1ms"},
		},
		{
			name: "영상 머리", read: timingRead{st: &headVideo},
			wantFacts: index.TimingFacts{
				Wall: baseWall.Add(20 * time.Millisecond), Recorder: &rec, Number: &num,
				VideoFirst: durPtr(time.Minute + 20*time.Millisecond), VideoEnd: durPtr(time.Minute + 4020*time.Millisecond),
			},
			wantOwnMS: 4000, wantHead: true, wantSource: "mtxi",
		},
		{
			name: "판독 포기 — 파일명 시각만", read: timingRead{failure: "read_failed", err: errTransient},
			wantFacts:  index.TimingFacts{Wall: baseWall.Add(3 * time.Millisecond)},
			wantSource: "filename", wantReasons: []string{"read_failed"},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			in, source, reasons := timingInputOf(seg, tt.read, 3990)
			if !reflect.DeepEqual(in.Facts, tt.wantFacts) {
				t.Errorf("칸 = %+v, want %+v", in.Facts, tt.wantFacts)
			}
			if in.VideoOwnMS != tt.wantOwnMS || in.HeadVideo != tt.wantHead || in.LegacyDurationMS != 3990 {
				t.Errorf("영상 길이 · 머리 · mvhd = %d · %v · %d, want %d · %v · 3990",
					in.VideoOwnMS, in.HeadVideo, in.LegacyDurationMS, tt.wantOwnMS, tt.wantHead)
			}
			if source != tt.wantSource || !reflect.DeepEqual(reasons, tt.wantReasons) {
				t.Errorf("w 출처 · 까닭 = %s · %v, want %s · %v", source, reasons, tt.wantSource, tt.wantReasons)
			}
		})
	}
}

// 판독 사실은 장부 행의 칸으로 그대로 실린다 — 인덱서가 Record.Timing 을 채운다(c4a 중간 상태를 닫는다).
func TestCommitCarriesTimingInputToLedger(t *testing.T) {
	f := newFixture(t, 4000)
	f.ix.timingFn = func(string) (playback.SegmentTiming, error) { return sampleTiming(), nil }
	seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)

	f.mustHandle(seg)

	want, _, _ := timingInputOf(seg, timingRead{st: ptrTiming(sampleTiming())}, 4000)
	if got := f.store.records("s1")[0].Timing; !reflect.DeepEqual(got, want) {
		t.Errorf("장부 행 Timing = %+v, want %+v", got, want)
	}
}

func ptrTiming(st playback.SegmentTiming) *playback.SegmentTiming { return &st }

// segment_indexed 의 판 NULL 몫 속성(설계 8절 · 계획 14절 c5) — 판 · 갈래 · 이어 계산 회차 · 머리 트랙 · 머리 앞섬 · w · 귀속.
func TestSegmentIndexedCarriesTimingAttrs(t *testing.T) {
	f := newFixture(t, 4000)
	f.store.scriptSessions("S-1")
	f.ix.timingFn = func(string) (playback.SegmentTiming, error) { return sampleTiming(), nil }

	f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile))

	got := f.logs.attrs("segment_indexed")
	want := map[string]any{
		"timing_version": "NULL", "timing_branch": "", "round_id": "S-1", "head_track": "audio",
		"head_lead_ms": int64(20), "wall_ms": baseWall.Add(20 * time.Millisecond).UnixMilli(), "attributed": true,
	}
	for k, v := range want {
		if got[k] != v {
			t.Errorf("segment_indexed[%s] = %v (%T), want %v (%T)", k, got[k], got[k], v, v)
		}
	}
}

// 머리 앞섬 m 이 1초를 넘으면 head_lead_over_1s(Warn — m · 인스턴스 첫 조각 여부)를 남긴다(설계 8절).
func TestHeadLeadOverOneSecondSignals(t *testing.T) {
	f := newFixture(t, 4000, 4000)
	st := sampleTiming()
	st.HeadLead = 1001 * time.Millisecond
	f.ix.timingFn = func(string) (playback.SegmentTiming, error) { return st, nil }

	f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile))
	st.Mtxi = &playback.Mtxi{RecorderID: recA, SegmentNumber: 8, DTS: time.Minute + 4*time.Second, NTP: baseWall.Add(4 * time.Second)}
	f.mustHandle(f.segment("s1", segName(baseWall, 4*time.Second), 1000, recording.ReasonNextFile))

	all := f.logs.attrsAll("head_lead_over_1s")
	if len(all) != 2 {
		t.Fatalf("head_lead_over_1s = %d건, want 2", len(all))
	}
	if all[0]["head_lead_ms"] != int64(1001) || all[0]["instance_first"] != true || all[1]["instance_first"] != false {
		t.Errorf("head_lead_over_1s 속성 = %v, want m 1001 · 첫 조각 true → false", all)
	}
}

// 계획 c5 시험 6 — 꼬리 교정은 판독 한 번으로 길이와 영상 끝을 함께 넘기고(설계 2.14), 메모리 꼬리의 영상 끝도 따라간다.
func TestCorrectTailPassesVideoEnd(t *testing.T) {
	f := newFixture(t, 4000)
	st := sampleTiming()
	f.ix.timingFn = func(string) (playback.SegmentTiming, error) { return st, nil }
	seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
	f.mustHandle(seg)

	f.probe.vals, f.probe.calls = []int64{6000}, 0
	f.makeFile("s1", segName(baseWall, 0), 2500)
	st.Video = &playback.TrackSpan{Start: 20 * time.Millisecond, End: 6020 * time.Millisecond}
	seg.Reason = recording.ReasonRegrown
	f.mustHandle(seg)

	if len(f.store.updateTail) != 1 {
		t.Fatalf("UpdateTail 호출 = %d, want 1", len(f.store.updateTail))
	}
	want := int64(time.Minute + 6020*time.Millisecond)
	if got := f.store.updateTail[0].videoEndNS; got == nil || *got != want {
		t.Fatalf("넘긴 영상 끝 = %v, want %d", got, want)
	}
	if got := f.ix.cursors["s1"].Tail.Timing.VideoEnd; got == nil || int64(*got) != want {
		t.Errorf("메모리 꼬리 영상 끝 = %v, want %d", got, want)
	}
	reloaded, err := f.store.LoadCursor(t.Context(), "s1")
	if err != nil {
		t.Fatalf("LoadCursor 실패: %v", err)
	}
	if !reflect.DeepEqual(*f.ix.cursors["s1"].Tail, *reloaded.Tail) {
		t.Errorf("교정 뒤 메모리 꼬리 = %+v, 장부 꼬리 = %+v", *f.ix.cursors["s1"].Tail, *reloaded.Tail)
	}
}

// 꼬리 교정이 영상 끝을 넘기지 않는 갈래 — 옛 영상 끝이 NULL(설계 2.14 「옛 영상 끝이 NULL 인 꼬리」) · 번호 넘침(네 칸
// NULL) · 줄어든 값(불변 트리거가 문장 전체를 거부해 길이 교정까지 잃는다) · 판독 실패(길이만 고친다).
func TestCorrectTailWithholdsVideoEnd(t *testing.T) {
	tests := []struct {
		name  string
		first func(*playback.SegmentTiming)
		grown func(*playback.SegmentTiming) error
	}{
		{name: "옛 값 NULL", first: func(st *playback.SegmentTiming) { st.Video = nil }},
		{name: "번호 넘침", grown: func(st *playback.SegmentTiming) error { st.Mtxi.SegmentNumber = math.MaxUint64; return nil }},
		{name: "줄어든 값", grown: func(st *playback.SegmentTiming) error {
			st.Video = &playback.TrackSpan{Start: 20 * time.Millisecond, End: 4019 * time.Millisecond}
			return nil
		}},
		{name: "판독 실패", grown: func(*playback.SegmentTiming) error { return errTransient }},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f := newFixture(t, 4000)
			st := sampleTiming()
			var readErr error
			f.ix.timingFn = func(string) (playback.SegmentTiming, error) { return st, readErr }
			if tt.first != nil {
				tt.first(&st)
			}
			seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
			f.mustHandle(seg)

			st = sampleTiming()
			if tt.grown != nil {
				readErr = tt.grown(&st)
			}
			f.probe.vals, f.probe.calls = []int64{6000}, 0
			f.makeFile("s1", segName(baseWall, 0), 2500)
			seg.Reason = recording.ReasonRegrown
			f.mustHandle(seg)

			if len(f.store.updateTail) != 1 {
				t.Fatalf("UpdateTail 호출 = %d, want 1(길이 교정은 그대로 한다)", len(f.store.updateTail))
			}
			if call := f.store.updateTail[0]; call.videoEndNS != nil || call.durationMS != 6000 {
				t.Errorf("UpdateTail(dur %d · 영상 끝 %v), want 6000 · nil", call.durationMS, call.videoEndNS)
			}
		})
	}
}

// 판독 관문 오류가 ErrTransientRead 가 아니어도(열기 실패 등 원 오류) 일시 실패로 다룬다 — 「오류면 커밋」 으로 새면 막힘이
// 뚫린다.
func TestNonSentinelReadErrorAlsoDefers(t *testing.T) {
	f := newFixture(t, 4000)
	f.ix.timingFn = func(string) (playback.SegmentTiming, error) {
		return playback.SegmentTiming{}, errors.New("open: 권한 없음(주입)")
	}
	f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile))
	if n := len(f.store.records("s1")); n != 0 {
		t.Errorf("행 = %d, want 0", n)
	}
}
