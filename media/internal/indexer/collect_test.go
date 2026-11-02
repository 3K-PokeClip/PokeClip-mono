package indexer

// 수집 비동기화(f6j·f6l 계열)와 완주 판정(f6p ⓒ 재료)의 단위 검증 — POK-168 r15a 6.5.3 ⑴.

import (
	"context"
	"log/slog"
	"os"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// f6l — 단일 비행: 앞 수집이 안 끝났으면 발사를 건너뛰고 WARN 1건. 결과 채택 후 재발사된다.
func TestStartCollectSingleFlight(t *testing.T) {
	f := newFixture(t)
	ctx := context.Background()

	if !f.ix.StartCollect(ctx, f.root) {
		t.Fatal("첫 발사가 거부됐다")
	}
	// 결과가 채널에 있어도 ApplyCollect 전이면 여전히 in-flight 다(내리는 곳은 한 곳뿐).
	if f.ix.StartCollect(ctx, f.root) {
		t.Fatal("in-flight 중 재발사가 허용됐다")
	}
	if n := f.logs.count(slog.LevelWarn, "scan_collect_skipped_inflight"); n != 1 {
		t.Fatalf("scan_collect_skipped_inflight WARN 이 %d건이다(1건 기대)", n)
	}

	res := <-f.ix.CollectDone()
	if _, err := f.ix.ApplyCollect(ctx, f.root, res); err != nil {
		t.Fatalf("ApplyCollect 실패: %v", err)
	}
	if !f.ix.StartCollect(ctx, f.root) {
		t.Fatal("채택 후 재발사가 거부됐다")
	}
}

// f6k 판정부 — CollectOverdue 는 경과 > 예산 × k 에서 한 시도에 한 번만 참이다.
func TestCollectOverdueOncePerAttempt(t *testing.T) {
	f := newFixture(t)
	f.opt.ScanCollectBudget = time.Millisecond
	f.reload()
	ctx := context.Background()

	f.ix.StartCollect(ctx, f.root)
	time.Sleep(10 * time.Millisecond) // 예산 × k(=2) 초과

	if !f.ix.CollectOverdue(2) {
		t.Fatal("예산 초과인데 Overdue 가 거짓이다")
	}
	if f.ix.CollectOverdue(2) {
		t.Fatal("한 시도에 stalled 판정이 두 번 났다")
	}

	// 결과 채택(회복) 후 새 시도는 다시 판정 가능해야 한다 — stalled→truncated 정상 서열.
	res := <-f.ix.CollectDone()
	if _, err := f.ix.ApplyCollect(ctx, f.root, res); err != nil {
		t.Fatalf("ApplyCollect 실패: %v", err)
	}
	f.ix.StartCollect(ctx, f.root)
	time.Sleep(10 * time.Millisecond)
	if !f.ix.CollectOverdue(2) {
		t.Fatal("새 시도의 Overdue 판정이 막혀 있다")
	}
}

// f6j — 절단된 결과: scan_collect_truncated ERROR 1건 + 부분 결과가 실제로 처리된다.
// SIGTERM 대조군: 부모 ctx 가 취소된 상태에서는 truncated ERROR 가 뜨지 않는다.
func TestApplyCollectTruncatedPartialProcessed(t *testing.T) {
	f := newFixture(t, 4000)
	seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonScan)

	res := collectResult{
		byStream:  map[string][]recording.Segment{"s1": {seg}},
		truncated: true,
	}
	first, err := f.ix.ApplyCollect(context.Background(), f.root, res)
	if err != nil {
		t.Fatalf("ApplyCollect 실패: %v", err)
	}
	if first {
		t.Fatal("절단 주기가 첫 완주로 판정됐다(f6p ⓒ 위반)")
	}
	if n := f.logs.count(slog.LevelError, "scan_collect_truncated"); n != 1 {
		t.Fatalf("scan_collect_truncated ERROR 가 %d건이다(1건 기대)", n)
	}
	if got := len(f.store.records("s1")); got != 1 {
		t.Fatalf("부분 결과가 처리되지 않았다: %d행(1행 기대)", got)
	}

	// 대조군 — 취소된 ctx 에서는 절단 ERROR 를 울리지 않는다.
	f2 := newFixture(t)
	cancelled, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := f2.ix.ApplyCollect(cancelled, f2.root, collectResult{truncated: true}); err != nil {
		t.Fatalf("취소 ctx 의 ApplyCollect 이 에러다: %v", err)
	}
	if n := f2.logs.count(slog.LevelError, "scan_collect_truncated"); n != 0 {
		t.Fatalf("취소 국면에서 truncated ERROR 가 %d건 났다(0 기대)", n)
	}
}

// 완주 판정 — 깨끗한 주기의 첫 채택만 firstComplete 이고 두 번째부터는 아니다.
func TestApplyCollectFirstCompleteOnce(t *testing.T) {
	f := newFixture(t)
	ctx := context.Background()

	first, err := f.ix.ApplyCollect(ctx, f.root, collectResult{byStream: map[string][]recording.Segment{}})
	if err != nil || !first {
		t.Fatalf("깨끗한 첫 주기가 완주가 아니다: first=%v err=%v", first, err)
	}
	first, _ = f.ix.ApplyCollect(ctx, f.root, collectResult{byStream: map[string][]recording.Segment{}})
	if first {
		t.Fatal("두 번째 완주도 firstComplete 다(arm 은 한 번이어야 한다)")
	}
}

// f6p ⓒ(마지막 스트림 구멍) — 마지막 스트림 처리 중 래치가 트립되면 루프 종료 후
// 재검사(⑤)가 잡아 완주가 아니다. 스트림이 하나뿐인 국면이 정확히 그 함정이다.
func TestApplyCollectLastStreamTripNotComplete(t *testing.T) {
	f := newFixture(t, 4000)
	seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonScan)

	// scanStream 의 stat(최신 1개 판정)부터 정지시킨다 — ③ Reset 은 실제 root 프로브라
	// 통과하고, 트립은 스트림 루프 안에서 일어난다.
	realStat := f.ix.statFn
	f.ix.statFn = func(p string) (os.FileInfo, error) {
		if p == seg.Path {
			return nil, stalledErr(p)
		}
		return realStat(p)
	}

	first, err := f.ix.ApplyCollect(context.Background(),
		f.root, collectResult{byStream: map[string][]recording.Segment{"s1": {seg}}})
	if err != nil {
		t.Fatalf("트립 국면 ApplyCollect 이 에러다(⒠ 대칭 위반): %v", err)
	}
	if first {
		t.Fatal("마지막 스트림 트립 주기가 완주로 판정됐다 — 커서 미완인데 스위퍼가 열린다")
	}
	if !f.ix.fsLatch.Tripped() {
		t.Fatal("주입한 정지가 래치를 세우지 않았다")
	}
}
