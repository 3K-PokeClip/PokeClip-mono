package indexer

import (
	"context"
	"log/slog"
	"path/filepath"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxhook"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// ---------------------------------------------------------------------------
// 훅 픽스처 — fixture(fixture_test.go)를 훅 진입점으로 확장한다.
// ---------------------------------------------------------------------------

// hookAt 은 baseWall 로부터 offset 만큼 떨어진 세션 훅 이벤트를 만든다.
func hookAt(kind mtxhook.Kind, streamID string, offset time.Duration, source string) mtxhook.Event {
	return mtxhook.Event{
		Kind:     kind,
		StreamID: streamID,
		SourceID: source,
		At:       baseWall.Add(offset),
	}
}

func (f *fixture) handleHook(ev mtxhook.Event) {
	f.t.Helper()
	if err := f.ix.HandleHook(context.Background(), ev); err != nil {
		f.t.Fatalf("HandleHook 실패: %v", err)
	}
}

// arm 은 offline→online 한 쌍을 먹여 경계 1건을 무장시킨다.
func (f *fixture) arm(streamID string, offlineAt, onlineAt time.Duration) {
	f.t.Helper()
	f.handleHook(hookAt(mtxhook.KindOffline, streamID, offlineAt, "src-off"))
	f.handleHook(hookAt(mtxhook.KindOnline, streamID, onlineAt, "src-on"))
}

// newHookFixture 는 훅 테스트용 픽스처다. ToSegment 기준 루트를 픽스처 루트로 맞춘다.
func newHookFixture(t *testing.T, probeVals ...int64) *fixture {
	t.Helper()
	f := newFixture(t, probeVals...)
	f.opt.SegmentRoot = f.root
	f.reload()
	return f
}

// ---------------------------------------------------------------------------
// 짝짓기 상태 기계 (H7)
// ---------------------------------------------------------------------------

func TestArmsBreakOnOfflineThenOnline(t *testing.T) {
	f := newHookFixture(t)
	f.arm("demo", 0, 900*time.Millisecond)

	q := f.ix.breaks["demo"]
	if len(q) != 1 {
		t.Fatalf("breaks 길이 = %d, want 1", len(q))
	}
	if !q[0].OfflineAt.Equal(baseWall) || !q[0].OnlineAt.Equal(baseWall.Add(900*time.Millisecond)) {
		t.Errorf("경계 시각이 어긋난다: %+v", q[0])
	}
	if f.logs.count(slog.LevelInfo, "hook_break_armed") != 1 {
		t.Error("hook_break_armed 가 없다")
	}
	// 짝지어진 offline 은 소비된다. 남겨 두면 다음 online 이 옛 offline 과 또 짝짓는다.
	if _, ok := f.ix.pendingOffline["demo"]; ok {
		t.Error("무장 후에도 pendingOffline 이 남아 있다")
	}
}

// T11 — online 이 offline 보다 이른 시각인 쌍은 순서 역전이므로 신뢰하지 않는다.
// 역순 입력에 대한 선택은 일관되게 "오탐보다 미탐"이다.
func TestInvertedPairDoesNotArm(t *testing.T) {
	f := newHookFixture(t)
	f.handleHook(hookAt(mtxhook.KindOffline, "demo", 900*time.Millisecond, "src-off"))
	f.handleHook(hookAt(mtxhook.KindOnline, "demo", 0, "src-on"))

	if n := len(f.ix.breaks["demo"]); n != 0 {
		t.Errorf("breaks 길이 = %d, want 0 — 역전된 쌍으로 무장했다", n)
	}
	if f.logs.count(slog.LevelInfo, "hook_online_unpaired") != 1 {
		t.Error("hook_online_unpaired 가 없다")
	}
}

// T12 — offline 없이 온 online 은 무장하지 않는다(무장 조건 = 쌍).
func TestUnpairedOnlineDoesNotArm(t *testing.T) {
	f := newHookFixture(t)
	f.handleHook(hookAt(mtxhook.KindOnline, "demo", time.Second, "src-on"))

	if n := len(f.ix.breaks["demo"]); n != 0 {
		t.Errorf("breaks 길이 = %d, want 0", n)
	}
	if f.logs.count(slog.LevelInfo, "hook_online_unpaired") != 1 {
		t.Error("hook_online_unpaired 가 없다")
	}
}

// T21 — watermark 는 짝 성립 여부와 무관하게 **모든** online 에서 전진해야 한다.
// 그러지 않으면 유실된 offline₂ 자리에 옛 offline₁ 이 끼어 엉뚱한 경계가 무장된다.
func TestOnlineWatermarkAdvancesAndDropsStaleOffline(t *testing.T) {
	f := newHookFixture(t)

	// online₂ 선착 — 짝이 없어 무장은 안 되지만 watermark 는 전진해야 한다.
	f.handleHook(hookAt(mtxhook.KindOnline, "demo", 2*time.Second, "src-on2"))
	if got := f.ix.lastOnlineAt["demo"]; !got.Equal(baseWall.Add(2 * time.Second)) {
		t.Fatalf("watermark = %v, want %v — 짝 없는 online 에서 전진하지 않았다",
			got, baseWall.Add(2*time.Second))
	}

	// 늦게 도착한 offline₁ 은 watermark 이전이므로 stale 이다.
	f.handleHook(hookAt(mtxhook.KindOffline, "demo", time.Second, "src-off1"))
	if _, ok := f.ix.pendingOffline["demo"]; ok {
		t.Error("watermark 이전 offline 이 pendingOffline 에 남았다")
	}
	if f.logs.count(slog.LevelDebug, "hook_offline_stale") != 1 {
		t.Error("hook_offline_stale 이 없다")
	}

	// offline₂ 는 유실됐다고 본다. 그러면 online₃ 은 옛 offline 과 짝지어지지 않아야 한다.
	f.handleHook(hookAt(mtxhook.KindOnline, "demo", 3*time.Second, "src-on3"))
	if n := len(f.ix.breaks["demo"]); n != 0 {
		t.Errorf("breaks 길이 = %d, want 0 — 옛 offline 과 짝지었다", n)
	}
}

// 같은 시각대에 offline 이 여러 번 오면 더 늦은 것만 남는다.
func TestOnlyLatestPendingOfflineIsKept(t *testing.T) {
	f := newHookFixture(t)
	f.handleHook(hookAt(mtxhook.KindOffline, "demo", 2*time.Second, "late"))
	f.handleHook(hookAt(mtxhook.KindOffline, "demo", time.Second, "early"))

	if got := f.ix.pendingOffline["demo"].Source; got != "late" {
		t.Errorf("pendingOffline.Source = %q, want %q", got, "late")
	}
	if f.logs.count(slog.LevelDebug, "hook_offline_stale") != 1 {
		t.Error("더 이른 offline 에 대한 hook_offline_stale 이 없다")
	}
}

// SOURCE_ID 는 판정이 아니라 관측용이다. 같아도 무장은 유지하고 경고만 남긴다.
func TestSameSourceWarnsButStillArms(t *testing.T) {
	f := newHookFixture(t)
	f.handleHook(hookAt(mtxhook.KindOffline, "demo", 0, "same"))
	f.handleHook(hookAt(mtxhook.KindOnline, "demo", time.Second, "same"))

	if n := len(f.ix.breaks["demo"]); n != 1 {
		t.Errorf("breaks 길이 = %d, want 1 — 같은 source 로 무장을 거부했다", n)
	}
	if f.logs.count(slog.LevelWarn, "hook_break_same_source") != 1 {
		t.Error("hook_break_same_source 가 없다")
	}
}

// 스트림별로 완전히 분리돼야 한다. 하나라도 전역으로 뭉개면 다중 방송에서 조용히 틀린다.
func TestBreaksAreIsolatedPerStream(t *testing.T) {
	f := newHookFixture(t)
	f.arm("alpha", 0, time.Second)

	if len(f.ix.breaks["alpha"]) != 1 {
		t.Error("alpha 가 무장되지 않았다")
	}
	if len(f.ix.breaks["bravo"]) != 0 {
		t.Error("bravo 에 경계가 새어 들어갔다")
	}
}

// ---------------------------------------------------------------------------
// 경계 큐 (H2)
// ---------------------------------------------------------------------------

// T22a — 큐 상한 초과 시 정확히 가장 오래된 1건만 버린다.
func TestBreakQueueOverflowDropsOldest(t *testing.T) {
	f := newHookFixture(t)

	for i := range breakQueueMax + 1 {
		off := time.Duration(i) * 10 * time.Second
		f.arm("demo", off, off+time.Second)
	}

	q := f.ix.breaks["demo"]
	if len(q) != breakQueueMax {
		t.Fatalf("breaks 길이 = %d, want %d", len(q), breakQueueMax)
	}
	// 가장 오래된 것(i=0)이 빠졌으므로 맨 앞은 i=1 의 경계다.
	wantOldest := baseWall.Add(10*time.Second + time.Second)
	if !q[0].OnlineAt.Equal(wantOldest) {
		t.Errorf("맨 앞 OnlineAt = %v, want %v", q[0].OnlineAt, wantOldest)
	}
	if f.logs.count(slog.LevelWarn, "hook_break_queue_overflow") != 1 {
		t.Error("hook_break_queue_overflow 가 없다")
	}
}

// T22b — enqueueBreak 는 **정렬 삽입**이어야 한다. 단순 append 면 역순으로 성립한 경계에서
// peekBreak 의 순회 break 가 뒤 경계를 못 본다.
// 공개 상태 기계로는 watermark 단조성 때문에 역순이 재현되지 않으므로 큐를 직접 주입한다.
func TestEnqueueBreakInsertsInOnlineAtOrder(t *testing.T) {
	f := newHookFixture(t)
	mk := func(offset time.Duration) sessionBreak {
		return sessionBreak{
			OfflineAt: baseWall.Add(offset - 100*time.Millisecond),
			OnlineAt:  baseWall.Add(offset),
		}
	}
	// 이미 뒤 경계가 들어 있는 큐에 더 이른 경계가 도착하는 상황.
	f.ix.breaks["demo"] = []sessionBreak{mk(3 * time.Second), mk(5 * time.Second)}

	f.ix.enqueueBreak("demo", mk(4*time.Second))
	f.ix.enqueueBreak("demo", mk(time.Second))

	q := f.ix.breaks["demo"]
	want := []time.Duration{time.Second, 3 * time.Second, 4 * time.Second, 5 * time.Second}
	if len(q) != len(want) {
		t.Fatalf("breaks 길이 = %d, want %d", len(q), len(want))
	}
	for i, w := range want {
		if !q[i].OnlineAt.Equal(baseWall.Add(w)) {
			t.Errorf("q[%d].OnlineAt = %v, want %v", i, q[i].OnlineAt, baseWall.Add(w))
		}
	}
}

// ---------------------------------------------------------------------------
// segcomplete 경로 (T3 후반)
// ---------------------------------------------------------------------------

// 훅 세그먼트는 판정 없이 그대로 Handle 로 들어간다(ADR-026 1항).
func TestSegmentCompleteHookIndexesSegment(t *testing.T) {
	f := newHookFixture(t)
	path := f.makeFile("demo", segName(baseWall, 0), 1000)

	f.handleHook(mtxhook.Event{
		Kind:        mtxhook.KindSegmentComplete,
		StreamID:    "demo",
		At:          baseWall,
		SegmentPath: path,
	})

	recs := f.store.records("demo")
	if len(recs) != 1 {
		t.Fatalf("행 수 = %d, want 1", len(recs))
	}
	if recs[0].LocalPath != path {
		t.Errorf("local_path = %q, want %q", recs[0].LocalPath, path)
	}
}

// T3(후반) — 정본화에 실패한 경로는 WARN 을 남기고 그 이벤트만 버린다. INSERT 는 0이다.
func TestRejectedSegmentPathWarnsAndInsertsNothing(t *testing.T) {
	f := newHookFixture(t)

	f.handleHook(mtxhook.Event{
		Kind:        mtxhook.KindSegmentComplete,
		StreamID:    "demo",
		At:          baseWall,
		SegmentPath: filepath.Join(f.root, "..", "elsewhere", "demo", segName(baseWall, 0)),
	})

	if f.store.insertCalls != 0 {
		t.Errorf("Insert 호출 = %d회, want 0", f.store.insertCalls)
	}
	if f.logs.count(slog.LevelWarn, "hook_segment_path_rejected") != 1 {
		t.Error("hook_segment_path_rejected 가 없다")
	}
}

// ---------------------------------------------------------------------------
// 키 계약 · 무장 직후 reconcile
// ---------------------------------------------------------------------------

// T23 — breaks 의 키(MTX_PATH 원문)와 소비 측 조회 키(seg.StreamID)가 바이트 동일해야 한다.
// 어긋나면 무장은 되지만 영원히 소비되지 않는다 = 조용한 미탐 + 큐 누수.
//
// 확인 대상은 "훅 무장 → **워처 경로 세그먼트**가 소비"다. 훅 세그먼트로는 이 등식이
// 검증되지 않는다 — 같은 ToSegment 경로를 지나므로 키가 어긋나도 함께 어긋난다.
func TestHookBreakKeyMatchesWatcherStreamID(t *testing.T) {
	// 대문자·언더바·하이픈이 **전부** 들어간 값을 쓴다.
	// "demo" 는 ToLower·TrimSpace·Clean 어느 것에도 불변이라, 결정 ①이 금지한 변형을
	// 하나라도 넣는 변경을 통과시켜 버린다(테스트가 아무것도 지키지 못한다).
	const streamID = "Demo_Stream-01"

	f := newHookFixture(t)

	// 꼬리를 만든다(seq 0 에 불연속은 무의미하므로).
	first := f.segment(streamID, segName(baseWall, 0), 1000, recording.ReasonNextFile)
	f.mustHandle(first)

	// 세션 훅의 MTX_PATH 로 무장한다.
	f.arm(streamID, 4*time.Second, 4900*time.Millisecond)

	// 워처 경로로 만든 세그먼트가 그 무장을 소비해야 한다.
	watched := f.segment(streamID, segName(baseWall, 4900*time.Millisecond), 1000, recording.ReasonNextFile)
	f.mustHandle(watched)

	var got bool
	for _, r := range f.store.records(streamID) {
		if r.LocalPath == watched.Path {
			got = r.IsDiscontinuity
		}
	}
	if !got {
		t.Errorf("워처 경로 세그먼트가 훅 무장을 소비하지 못했다 — breaks 키가 어긋났다"+
			" (무장 키 %q vs 조회 키 %q)", streamID, watched.StreamID)
	}
	if n := len(f.ix.breaks[streamID]); n != 0 {
		t.Errorf("breaks[%q] 길이 = %d, want 0", streamID, n)
	}
}

// 결정 ② — 커서가 아직 적재되지 않은 스트림에서는 무장 직후 reconcile 을 건너뛴다.
// 훅 이벤트가 DB I/O 를 유발하면 안 되고(에러 계약), 커서가 없다는 것은 "그 스트림의
// 세그먼트를 아직 한 건도 처리하지 않았다" = 폐기할 근거가 없다는 뜻이다.
// 정확성은 Handle 진입부의 reconcile 이 담당한다.
func TestArmWithoutCursorDoesNotReconcile(t *testing.T) {
	f := newHookFixture(t)
	f.arm("demo", 0, time.Second)

	if n := len(f.ix.breaks["demo"]); n != 1 {
		t.Errorf("breaks 길이 = %d, want 1 — 무장이 유지되지 않았다", n)
	}
	if f.store.loadCursorCalls != 0 {
		t.Errorf("LoadCursor 호출 = %d회, want 0회 — 훅 이벤트가 DB I/O 를 일으켰다",
			f.store.loadCursorCalls)
	}
}

// 알 수 없는 종류가 정상 경로로 흘러들면 사고다. 조용히 넘기지 않는다.
func TestUnknownKindIsLoggedAndDropped(t *testing.T) {
	f := newHookFixture(t)

	f.handleHook(mtxhook.Event{Kind: mtxhook.KindUnknown, StreamID: "demo", At: baseWall})

	if f.store.insertCalls != 0 {
		t.Errorf("Insert 호출 = %d회, want 0", f.store.insertCalls)
	}
	if f.logs.count(slog.LevelError, "hook_unknown_kind") != 1 {
		t.Error("hook_unknown_kind 가 없다")
	}
}

// 미존재 경로를 그대로 Handle 에 넣으면 state() 가 LoadCursor·ExistingPaths 로 DB 를
// 두 번 왕복하고 커서 맵에 그 스트림을 등재한다. 스풀에 쓰레기 경로를 흘리는 것만으로
// DB 부하와 맵 증식을 유발할 수 있으므로, 훅 경로는 Handle 전에 실재를 확인한다.
func TestMissingSegmentFileIsDroppedBeforeTouchingDB(t *testing.T) {
	f := newHookFixture(t)

	f.handleHook(mtxhook.Event{
		Kind: mtxhook.KindSegmentComplete, StreamID: "demo", At: baseWall,
		SegmentPath: filepath.Join(f.root, "demo", segName(baseWall, 0)), // 파일을 만들지 않았다
	})

	if f.store.loadCursorCalls != 0 {
		t.Errorf("LoadCursor 호출 = %d회, want 0회 — 미존재 경로가 DB 를 건드렸다", f.store.loadCursorCalls)
	}
	if f.store.insertCalls != 0 {
		t.Errorf("Insert 호출 = %d회, want 0회", f.store.insertCalls)
	}
	if _, ok := f.ix.cursors["demo"]; ok {
		t.Error("미존재 경로가 커서 맵에 스트림을 등재했다")
	}
	if n := f.logs.count(slog.LevelWarn, "hook_segment_missing"); n != 1 {
		t.Errorf("hook_segment_missing = %d회, want 1회", n)
	}
}

// 큐가 비면 맵에서 키 자체를 지운다. 빈 슬라이스를 남기면 방송이 끝난 스트림마다
// 항목이 쌓여 장기 실행에서 맵이 단조 증가한다.
func TestEmptyBreakQueueIsRemovedFromMap(t *testing.T) {
	f := newHookFixture(t)
	f.indexOne("demo", 0)
	f.arm("demo", seg4s, seg4s+900*time.Millisecond)

	seg := f.segment("demo", segName(baseWall, seg4s+900*time.Millisecond), 1000, recording.ReasonNextFile)
	f.mustHandle(seg)

	if _, ok := f.ix.breaks["demo"]; ok {
		t.Error("소비 후에도 breaks 에 빈 항목이 남았다")
	}
}
