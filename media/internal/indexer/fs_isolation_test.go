package indexer

// 처리 FS 격리(m2 · f6m 계열)의 단위 검증이다 — POK-168 r15a 설계 6.5.3 ⑵.
// hung FS 는 실제 파일시스템이 아니라 statFn·probeFn 주입으로 재현한다:
// fsop.ErrStalled 를 돌려주는 주입이 곧 "워커가 상한을 넘겼다"의 대역이다.

import (
	"fmt"
	"log/slog"
	"os"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/fsop"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxhook"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

func stalledErr(path string) error {
	return fmt.Errorf("%w: op=stat path=%q", fsop.ErrStalled, path)
}

// f6m ⓔⓕ — 트립 상태의 Handle·HandleHook 진입은 nil 로 조기 반환하고
// fs_latch_early_return WARN 을 남긴다. 프로세스를 끝낼 에러가 아니다.
func TestLatchTrippedEntryReturnsNil(t *testing.T) {
	f := newFixture(t, 4000)
	seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)

	f.ix.fsLatch.Trip(seg.Path, "measure")

	if err := f.handle(seg); err != nil {
		t.Fatalf("트립 조기 반환이 에러다(프로세스 사망 방향): %v", err)
	}
	if got := len(f.store.records("s1")); got != 0 {
		t.Fatalf("트립 상태에서 INSERT 가 났다: %d행", got)
	}
	if n := f.logs.count(slog.LevelWarn, "fs_latch_early_return"); n != 1 {
		t.Fatalf("fs_latch_early_return WARN 이 %d건이다(1건 기대)", n)
	}
	if site := f.logs.attrs("fs_latch_early_return")["site"]; site != "handle" {
		t.Fatalf("site 라벨이 %v 다(handle 기대)", site)
	}

	// 세그먼트 훅(FS 를 탄다)만 조기 반환한다.
	if err := f.ix.HandleHook(t.Context(), mtxhook.Event{
		Kind: mtxhook.KindSegmentComplete, StreamID: "s1",
		At: baseWall, SegmentPath: seg.Path,
	}); err != nil {
		t.Fatalf("HandleHook 트립 조기 반환이 에러다: %v", err)
	}
	if site := f.logs.attrs("fs_latch_early_return")["site"]; site != "hook" {
		t.Fatalf("hook 진입의 site 라벨이 %v 다", site)
	}

	// 세션 경계 훅(offline·online)은 FS 무접촉이라 트립과 무관하게 처리된다 —
	// 버리면 재접속 경계 메타데이터가 영구 소실된다(cx 리뷰 차단 2의 회귀 고정).
	if err := f.ix.HandleHook(t.Context(), mtxhook.Event{Kind: mtxhook.KindOnline, StreamID: "s1", At: baseWall}); err != nil {
		t.Fatalf("트립 국면 online 훅 처리가 에러다: %v", err)
	}
	if got := f.ix.lastOnlineAt["s1"]; !got.Equal(baseWall) {
		t.Fatalf("트립 국면에서 online watermark 가 전진하지 않았다: %v", got)
	}
}

// f6m ⓓ — stat 타임아웃(ErrStalled)이 site 라벨과 함께 fs_op_stalled ERROR 와
// fsLatch.Trip 으로 연결된다. 정상 실패(부재)는 래치를 건드리지 않는다.
func TestStatStalledTripsLatch(t *testing.T) {
	f := newFixture(t, 4000)
	seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)

	f.ix.statFn = func(p string) (os.FileInfo, error) { return nil, stalledErr(p) }

	if err := f.handle(seg); err != nil {
		t.Fatalf("stat 정지 국면의 Handle 이 에러다: %v", err)
	}
	if !f.ix.fsLatch.Tripped() {
		t.Fatal("ErrStalled 가 래치를 세우지 않았다(f6m ⓓ)")
	}
	if n := f.logs.count(slog.LevelError, "fs_op_stalled"); n == 0 {
		t.Fatal("fs_op_stalled ERROR 가 없다")
	}
	if op := f.logs.attrs("fs_op_stalled")["op"]; op != "stat" {
		t.Fatalf("op 라벨이 %v 다(stat 기대)", op)
	}

	// 대조군: 정상 실패는 트립이 아니다.
	f2 := newFixture(t, 4000)
	f2.ix.statFn = func(p string) (os.FileInfo, error) { return nil, os.ErrNotExist }
	seg2 := f2.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
	if err := f2.handle(seg2); err != nil {
		t.Fatalf("정상 stat 실패의 Handle 이 에러다: %v", err)
	}
	if f2.ix.fsLatch.Tripped() {
		t.Fatal("정상 실패가 래치를 세웠다 — ErrStalled 구분 실패")
	}
}

// f6m ⓒ — 프로브 정지도 유계로 돌아오고(주입 대역) 0 + fs_op_stalled{op=probe}가 된다.
func TestProbeStalledReturnsZeroAndTrips(t *testing.T) {
	f := newFixture(t, 4000)
	seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)

	f.ix.probeFn = func(p string) (int64, error) { return 0, fmt.Errorf("%w: op=probe", fsop.ErrStalled) }

	if err := f.handle(seg); err != nil {
		t.Fatalf("프로브 정지 국면의 Handle 이 에러다: %v", err)
	}
	if got := len(f.store.records("s1")); got != 0 {
		t.Fatalf("길이 0 조각이 INSERT 됐다: %d행", got)
	}
	if op := f.logs.attrs("fs_op_stalled")["op"]; op != "probe" {
		t.Fatalf("op 라벨이 %v 다(probe 기대)", op)
	}
	if !f.ix.fsLatch.Tripped() {
		t.Fatal("프로브 정지가 래치를 세우지 않았다")
	}
}

// m2 ⓑ — measure 의 남은 라운드가 래치를 확인한다: settle 도중 트립되면
// site=measure 로 조기 반환하고 커밋하지 않는다.
func TestMeasureRemainingRoundsCheckLatch(t *testing.T) {
	f := newFixture(t, 4000, 4000, 4000)
	seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)

	// 호출 서열로 국면을 강제한다: ①size0 정상 → ②size1 직전에 파일을 키워 라운드 진입
	// → ③settle 내부 stat 정지(트립) → 라운드 머리 래치 확인이 잡는다.
	real := func(p string) (os.FileInfo, error) { return fsop.StatT(p, time.Second) }
	calls := 0
	f.ix.statFn = func(p string) (os.FileInfo, error) {
		calls++
		switch calls {
		case 1: // size0
			return real(p)
		case 2: // size1 — 커진 크기를 보여 준다
			if err := os.WriteFile(p, make([]byte, 1512), 0o600); err != nil {
				t.Fatal(err)
			}
			f.touch(p, time.Now().Add(-time.Hour))
			return real(p)
		default: // settle 내부 — 정지
			return nil, stalledErr(p)
		}
	}

	if err := f.handle(seg); err != nil {
		t.Fatalf("measure 정지 국면의 Handle 이 에러다: %v", err)
	}
	if !f.ix.fsLatch.Tripped() {
		t.Fatal("settle 내부 정지가 래치를 세우지 않았다")
	}
	if got := len(f.store.records("s1")); got != 0 {
		t.Fatalf("정지 국면에서 INSERT 가 났다: %d행", got)
	}
}

// 래치 해제 — Reset 은 root 프로브 1회로 풀리고, 풀린 뒤에는 정상 처리가 재개된다.
func TestLatchResetRestoresProcessing(t *testing.T) {
	f := newFixture(t, 4000)
	f.ix.fsLatch.Trip("/p", "measure")

	if !f.ix.fsLatch.Reset(f.root, time.Second) {
		t.Fatal("응답하는 root 에서 Reset 이 실패했다")
	}
	seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
	if err := f.handle(seg); err != nil {
		t.Fatalf("해제 후 Handle 실패: %v", err)
	}
	if got := len(f.store.records("s1")); got != 1 {
		t.Fatalf("해제 후 INSERT 가 %d행이다(1행 기대)", got)
	}
}
