package indexer

// buildSeed(ⓐ 비시간 항 판정)의 매핑 검증 — POK-168 M2 · ADR-062.
// 채널 접기와 적격 사유 집합이 계약(CHECK 값)과 어긋나면 여기서 잡힌다.
// (go test 전용 — 기존 fixture 재사용, 공개 API 무영향. 지시 = POK-193 M2.)

import (
	"errors"
	"fmt"
	"testing"

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

// 락 상한 이중 초과(ErrLockContended) 처분 ⑴ — 일과성 경합은 H9 백오프 재시도가
// 그 자리에서 흡수한다. 단일 호출자(D10)가 조각을 물고 있는 동안은 후속 조각의 seq
// 선점이 불가능하므로, 경합이 풀리면 유실도 순서 어긋남도 없다(리뷰 r4 처분).
func TestLockContentionRetriesInPlace(t *testing.T) {
	f := newFixture(t, 4000)
	f.opt.SeedEnabled = true
	f.reload()

	segA := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
	contended := fmt.Errorf("%w: 대역", index.ErrLockContended)
	f.store.insertErrs = []error{contended, contended} // 두 번 좌초 후 해소

	if err := f.handle(segA); err != nil {
		t.Fatalf("일과성 경합이 에러로 올라왔다 — 즉시 종료는 재기동 경쟁으로 유실을 만든다: %v", err)
	}
	if got := f.store.insertCallCount(); got != 3 {
		t.Fatalf("재시도 횟수가 다르다: got %d, want 3(좌초 2 + 성공 1)", got)
	}
	if got := len(f.store.records("s1")); got != 1 {
		t.Fatalf("경합 해소 후 조각이 기록되지 않았다: %d행", got)
	}
	if f.ix.cursors["s1"].NextSeq != 1 {
		t.Fatalf("커서가 전진하지 않았다: %d", f.ix.cursors["s1"].NextSeq)
	}
}

// 락 상한 이중 초과 처분 ⑵ — 지속 경합은 재시도 상한 소진으로 기존 D8 처분(에러 반환 →
// main exit → 재기동)에 합류한다. 무기록·커서 불변이 그대로 유지되어야 재기동 Scan 이
// 이 조각을 같은 seq 로 회수할 수 있다.
func TestLockContentionExhaustionIsFatal(t *testing.T) {
	f := newFixture(t, 4000)
	f.opt.SeedEnabled = true
	f.reload()

	segA := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
	contended := fmt.Errorf("%w: 대역", index.ErrLockContended)
	errs := make([]error, f.opt.InsertRetryMax) // 상한만큼 전부 좌초
	for i := range errs {
		errs[i] = contended
	}
	f.store.insertErrs = errs

	err := f.handle(segA)
	if err == nil {
		t.Fatal("지속 경합이 상한 소진 에러로 올라오지 않았다")
	}
	if !errors.Is(err, index.ErrLockContended) {
		t.Fatalf("에러 연쇄에 ErrLockContended 가 없다: %v", err)
	}
	if got := len(f.store.records("s1")); got != 0 {
		t.Fatalf("좌초 국면에서 %d행이 들어갔다", got)
	}
	if f.ix.cursors["s1"].NextSeq != 0 {
		t.Fatalf("커서가 전진했다: %d", f.ix.cursors["s1"].NextSeq)
	}
}

// 유입 사유 → 세션 결정 연산(설계 3.3·5.4 유입 표 · 계획 4.4).
// 채널 접기(buildSeed)와 나란히 두는 이유는 둘 다 "유입 사유를 무엇으로 접는가"이고,
// 어긋나면 같은 조각이 주조 축과 세션 축에서 다른 유입으로 취급되기 때문이다.
func TestSessionOpForIngressReason(t *testing.T) {
	for _, c := range []struct {
		reason recording.CompletionReason
		want   index.SessionOp
	}{
		// 파일·훅 유입 자체가 라이브 방증이다 — 관측 없이도 연다.
		{recording.ReasonNextFile, index.SessionOpenOrCurrent},
		{recording.ReasonIdle, index.SessionOpenOrCurrent},
		{recording.ReasonHook, index.SessionOpenOrCurrent},
		// 스캔은 옛 잔존물일 수 있어 관측이 대신 보증할 때만 연다.
		{recording.ReasonScan, index.SessionCurrentOrOpenIfCorroborated},
		// 재성장·사유 미상은 열지 않는다 — 현 세션이 있으면 귀속만 한다(계획 9절).
		{recording.ReasonRegrown, index.SessionCurrentOnly},
		{recording.ReasonUnknown, index.SessionCurrentOnly},
	} {
		if got := sessionOp(c.reason); got != c.want {
			t.Errorf("%v: 연산 = %d, want %d", c.reason, got, c.want)
		}
	}
}

// Handle 관통 — 판정한 연산이 그대로 store 에 도달한다(배선 검증).
// 관측은 폴러 배선(단계 4) 전까지 널 오브젝트이며, 영값은 EpochKnown=false 라
// ⓐ2 가 fail-closed 된다 — 스캔 유입이 세션을 여는 일이 아직 없다는 뜻이다.
func TestHandleCarriesSessionOpToStore(t *testing.T) {
	f := newFixture(t, 4000)

	seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
	if err := f.handle(seg); err != nil {
		t.Fatal(err)
	}
	got := f.store.lastSource
	if got.Op != index.SessionOpenOrCurrent {
		t.Fatalf("store 에 도달한 연산 = %d, want %d", got.Op, index.SessionOpenOrCurrent)
	}
	if got.Obs.EpochKnown {
		t.Fatalf("관측이 배선되지 않았는데 EpochKnown 이 참이다: %+v", got.Obs)
	}
}
