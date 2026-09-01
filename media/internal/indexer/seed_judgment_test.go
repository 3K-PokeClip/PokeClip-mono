package indexer

// buildSeed(ⓐ 비시간 항 판정)의 매핑 검증 — POK-168 M2 · ADR-062.
// 채널 접기와 적격 사유 집합이 계약(CHECK 값)과 어긋나면 여기서 잡힌다.
// (go test 전용 — 기존 fixture 재사용, 공개 API 무영향. 지시 = POK-193 M2.)

import (
	"context"
	"fmt"
	"log/slog"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

func TestBuildSeedEligibilityAndChannel(t *testing.T) {
	f := newFixture(t)
	f.opt.SeedEnabled = true
	f.reload()

	cases := []struct {
		reason   recording.CompletionReason
		eligible bool
		channel  index.SeedChannel
	}{
		{recording.ReasonNextFile, true, index.SeedChannelWatcher},
		{recording.ReasonIdle, true, index.SeedChannelWatcher},
		{recording.ReasonHook, true, index.SeedChannelHook},
		// 스캔 유입은 ⓐ2(상태 방증 — mtxstate)가 필요하다: M3 배선 전까지 비적격이다.
		// 과거 잔존물이 컷오프가 되는 것을 막는 안전 방향(S2·S3).
		{recording.ReasonScan, false, index.SeedChannelScan},
		// 재성장은 주조하지 않는다(s3_unknown_declines).
		{recording.ReasonRegrown, false, index.SeedChannelWatcher},
	}
	for _, c := range cases {
		seg := recording.Segment{StreamID: "s1", Path: "/p", StartWall: baseWall, Reason: c.reason}
		got := f.ix.buildSeed(seg)
		if got.Eligible != c.eligible || got.Channel != c.channel {
			t.Errorf("%v: eligible=%v channel=%v (기대 %v/%v)",
				c.reason, got.Eligible, got.Channel, c.eligible, c.channel)
		}
		if got.Reason != index.SeedReasonLiveIngress || got.Freshness != liveFresh {
			t.Errorf("%v: reason/freshness 가 ⓐ1 형상이 아니다: %+v", c.reason, got)
		}
		if !got.AnchorUTC.Equal(baseWall) {
			t.Errorf("%v: anchor 가 start_wall 이 아니다: %v", c.reason, got.AnchorUTC)
		}
	}
}

// 플래그 OFF — 어떤 사유도 적격이 아니다(c1 의 판정부).
func TestBuildSeedDisabled(t *testing.T) {
	f := newFixture(t) // 기본값: SeedEnabled=false
	for _, r := range []recording.CompletionReason{
		recording.ReasonNextFile, recording.ReasonIdle, recording.ReasonHook, recording.ReasonScan,
	} {
		seg := recording.Segment{StreamID: "s1", Path: "/p", StartWall: baseWall, Reason: r}
		if f.ix.buildSeed(seg).Eligible {
			t.Errorf("OFF 인데 %v 가 적격이다", r)
		}
	}
}

// Handle 관통 — 플래그 ON 의 적격 유입이 store 에 Eligible seed 를 실어 보낸다(배선 검증).
func TestHandleCarriesSeedToStore(t *testing.T) {
	f := newFixture(t, 4000)
	f.opt.SeedEnabled = true
	f.reload()

	seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
	if err := f.handle(seg); err != nil {
		t.Fatal(err)
	}
	got := f.store.lastSeed
	if !got.Eligible || got.Channel != index.SeedChannelWatcher || got.Reason != index.SeedReasonLiveIngress {
		t.Fatalf("store 에 도달한 seed 가 판정과 다르다: %+v", got)
	}
}

// cx 차단 1 회귀 고정 — 락 경합 순서 장벽: 건너뛴 조각의 seq 를 후속 조각이 차지해
// H3 영구 거절로 이어지는 경로를 막는다. 장벽 중 유입은 보류되고, Scan 의 오름차순
// 재처리가 두 조각을 원래 순서로 회수하며 장벽을 내린다.
func TestLockContentionOrderBarrier(t *testing.T) {
	f := newFixture(t, 4000, 4000)
	f.opt.SeedEnabled = true
	f.reload()

	segA := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
	segB := f.segment("s1", segName(baseWall, 4*time.Second), 1000, recording.ReasonNextFile)

	// A 의 INSERT 가 락 경합으로 좌초한다(즉시 재시도 포함 실패의 대역).
	f.store.insertErrs = []error{fmt.Errorf("%w: 대역", index.ErrLockContended)}
	if err := f.handle(segA); err != nil {
		t.Fatalf("경합 좌초가 에러다(프로세스 사망 방향): %v", err)
	}
	callsAfterA := f.store.insertCallCount()

	// B 는 장벽에 걸려 INSERT 시도조차 없어야 한다 — 여기서 B 가 seq 0 을 차지하면
	// A 는 영구 거절된다(cx 가 실증한 경로).
	if err := f.handle(segB); err != nil {
		t.Fatalf("장벽 보류가 에러다: %v", err)
	}
	if got := f.store.insertCallCount(); got != callsAfterA {
		t.Fatalf("장벽 중 INSERT 가 %d회 더 나갔다", got-callsAfterA)
	}
	if n := f.logs.count(slog.LevelWarn, "insert_hold_active"); n != 1 {
		t.Fatalf("insert_hold_active WARN 이 %d건이다(1건 기대)", n)
	}

	// Scan 이 오름차순으로 회수하며 장벽을 내린다 — A=seq0, B=seq1.
	if err := f.ix.Scan(context.Background(), f.root); err != nil {
		t.Fatalf("회수 Scan 실패: %v", err)
	}
	recs := f.store.records("s1")
	if len(recs) != 2 || recs[0].Seq != 0 || recs[1].Seq != 1 {
		t.Fatalf("오름차순 회수 실패: %+v", recs)
	}
	if recs[0].LocalPath != segA.Path || recs[1].LocalPath != segB.Path {
		t.Fatalf("순서가 뒤집혔다: %s / %s", recs[0].LocalPath, recs[1].LocalPath)
	}
	if f.ix.insertHold["s1"] {
		t.Fatal("Scan 후에도 장벽이 남아 있다")
	}
}
