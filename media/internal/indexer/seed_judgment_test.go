package indexer

// buildSeed(ⓐ 비시간 항 판정)의 매핑 검증 — POK-168 M2 · ADR-062.
// 채널 접기와 적격 사유 집합이 계약(CHECK 값)과 어긋나면 여기서 잡힌다.
// (go test 전용 — 기존 fixture 재사용, 공개 API 무영향. 지시 = POK-193 M2.)

import (
	"errors"
	"fmt"
	"log/slog"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxstate"
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
		// 관측 없음(널 오브젝트) + 갓 들어온 조각 = ⓐ1 축만 성립하는 국면이다.
		got, _ := f.ix.buildSeed(seg, index.SessionObservation{}, baseWall)
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
		// 관측이 신선해도(ⓐ2 자격 성립) 플래그가 꺼져 있으면 주조를 시도하지 않는다.
		got, _ := f.ix.buildSeed(seg, publishingObs(baseWall, baseWall), baseWall)
		if got.Eligible {
			t.Errorf("OFF 인데 %v 가 적격이다", r)
		}
	}
}

// publishingObs 는 송출 중 스냅샷이다(tier ⓘ — 에폭 여유 0).
// 폴러가 산출하는 유일한 등급이라 여유를 값으로 받는 자리에 0 이 들어간다.
func publishingObs(observedAt, epochStartedAt time.Time) index.SessionObservation {
	return index.SessionObservation{
		Publishing:     true,
		ObservedAt:     observedAt.UTC(),
		EpochStartedAt: epochStartedAt.UTC(),
		EpochKnown:     true,
	}
}

// ⓐ1 → ⓐ2 폴백(계획 4.4) — 채널 × 관측 상태 → 앵커 쌍 · 적격 · ⓐ2 자격.
//
// **한 표로 두는 이유**: 이 판정은 "주조에 어느 방증 쌍을 쓸 것인가"와 "세션을 열 권한을
// 줄 것인가"를 한 자리에서 정한다(계획 4.4 — 이중 판정 금지). 둘을 갈라 적으면 같은
// 조각이 주조 축과 세션 축에서 서로 다른 방증으로 판정되는 국면이 조용히 생긴다.
func TestBuildSeedFallsBackToStateObservation(t *testing.T) {
	f := newFixture(t)
	f.opt.SeedEnabled = true
	f.reload()

	now := baseWall.Add(time.Hour)
	// 갓 들어온 조각(앵커 신선) / 앵커가 늙어 가는 조각(50초 = LIVE_FRESH − TxnDeadline).
	freshWall := now.Add(-5 * time.Second)
	agingWall := now.Add(-55 * time.Second)

	cases := []struct {
		name string
		// 유입
		reason recording.CompletionReason
		wall   time.Time
		obs    index.SessionObservation
		// 기대
		eligible     bool
		seedReason   index.SeedReason
		anchor       time.Time
		freshness    time.Duration
		channel      index.SeedChannel
		corroborated bool
	}{{
		name:   "워처 유입은 관측 없이도 ⓐ1 쌍으로 적격",
		reason: recording.ReasonNextFile, wall: freshWall, obs: index.SessionObservation{},
		eligible: true, seedReason: index.SeedReasonLiveIngress,
		anchor: freshWall, freshness: liveFresh, channel: index.SeedChannelWatcher,
	}, {
		name:   "훅 유입도 같다 — 채널만 다르다",
		reason: recording.ReasonHook, wall: freshWall, obs: index.SessionObservation{},
		eligible: true, seedReason: index.SeedReasonLiveIngress,
		anchor: freshWall, freshness: liveFresh, channel: index.SeedChannelHook,
	}, {
		name:   "스캔 유입 + 관측 없음 = 어느 방증도 없다(비적격 · 세션도 못 연다)",
		reason: recording.ReasonScan, wall: freshWall, obs: index.SessionObservation{},
		eligible: false, seedReason: index.SeedReasonLiveIngress,
		anchor: freshWall, freshness: liveFresh, channel: index.SeedChannelScan,
	}, {
		name:   "스캔 유입 + 신선한 송출 관측 = ⓐ2 쌍(state_obs · 앵커는 관측 시각)",
		reason: recording.ReasonScan, wall: freshWall,
		obs:      publishingObs(now.Add(-2*time.Second), now.Add(-10*time.Minute)),
		eligible: true, seedReason: index.SeedReasonStateObs,
		anchor: now.Add(-2 * time.Second), freshness: 30 * time.Second,
		channel: index.SeedChannelScan, corroborated: true,
	}, {
		name:   "송출 중이 아니면 ⓐ2 는 성립하지 않는다",
		reason: recording.ReasonScan, wall: freshWall,
		obs:      index.SessionObservation{ObservedAt: now.Add(-2 * time.Second), EpochKnown: true},
		eligible: false, seedReason: index.SeedReasonLiveIngress,
		anchor: freshWall, freshness: liveFresh, channel: index.SeedChannelScan,
	}, {
		name:   "에폭을 모르면 ⓐ2 는 fail-closed 다",
		reason: recording.ReasonScan, wall: freshWall,
		obs:      index.SessionObservation{Publishing: true, ObservedAt: now.Add(-2 * time.Second)},
		eligible: false, seedReason: index.SeedReasonLiveIngress,
		anchor: freshWall, freshness: liveFresh, channel: index.SeedChannelScan,
	}, {
		name:   "백로그 머리(관측보다 OBS_BACKFILL 이상 과거)는 ⓐ2 를 못 쓴다",
		reason: recording.ReasonScan, wall: now.Add(-90 * time.Second),
		obs:      publishingObs(now.Add(-2*time.Second), now.Add(-10*time.Minute)),
		eligible: false, seedReason: index.SeedReasonLiveIngress,
		anchor: now.Add(-90 * time.Second), freshness: liveFresh, channel: index.SeedChannelScan,
	}, {
		name:   "에폭 하한 미만(여유 0)은 ⓐ2 를 못 쓴다 — 옛 방송의 잔존물이다",
		reason: recording.ReasonScan, wall: now.Add(-30 * time.Second),
		obs:      publishingObs(now.Add(-2*time.Second), now.Add(-10*time.Second)),
		eligible: false, seedReason: index.SeedReasonLiveIngress,
		anchor: now.Add(-30 * time.Second), freshness: liveFresh, channel: index.SeedChannelScan,
	}, {
		// 자격(4항)과 쌍 선택(로컬 신선도)은 다른 축이다 — 세션은 열리되 주조는 안 한다.
		name:   "관측이 로컬 예비판정을 못 넘기면 쌍은 못 고르지만 ⓐ2 자격은 남는다",
		reason: recording.ReasonScan, wall: freshWall,
		obs:      publishingObs(now.Add(-25*time.Second), now.Add(-10*time.Minute)),
		eligible: false, seedReason: index.SeedReasonLiveIngress,
		anchor: freshWall, freshness: liveFresh, channel: index.SeedChannelScan, corroborated: true,
	}, {
		name:   "재성장은 관측이 신선해도 ⓐ2 를 시도하지 않는다",
		reason: recording.ReasonRegrown, wall: freshWall,
		obs:      publishingObs(now.Add(-2*time.Second), now.Add(-10*time.Minute)),
		eligible: false, seedReason: index.SeedReasonLiveIngress,
		anchor: freshWall, freshness: liveFresh, channel: index.SeedChannelWatcher,
	}, {
		name:   "사유 미상도 같다",
		reason: recording.ReasonUnknown, wall: freshWall,
		obs:      publishingObs(now.Add(-2*time.Second), now.Add(-10*time.Minute)),
		eligible: false, seedReason: index.SeedReasonLiveIngress,
		anchor: freshWall, freshness: liveFresh, channel: index.SeedChannelWatcher,
	}, {
		name:   "ⓐ1 앵커가 늙어 가면 워처 유입도 ⓐ2 쌍으로 갈아탄다(채널은 유입 그대로)",
		reason: recording.ReasonNextFile, wall: agingWall,
		obs:      publishingObs(now.Add(-2*time.Second), now.Add(-10*time.Minute)),
		eligible: true, seedReason: index.SeedReasonStateObs,
		anchor: now.Add(-2 * time.Second), freshness: 30 * time.Second,
		channel: index.SeedChannelWatcher, corroborated: true,
	}, {
		name:   "ⓐ1 앵커가 늙었어도 ⓐ2 가 불성립이면 ⓐ1 쌍 그대로다(나빠지지 않는다)",
		reason: recording.ReasonNextFile, wall: agingWall, obs: index.SessionObservation{},
		eligible: true, seedReason: index.SeedReasonLiveIngress,
		anchor: agingWall, freshness: liveFresh, channel: index.SeedChannelWatcher,
	}}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			seg := recording.Segment{StreamID: "s1", Path: "/p", StartWall: c.wall, Reason: c.reason}
			got, corroborated := f.ix.buildSeed(seg, c.obs, now)

			if got.Eligible != c.eligible {
				t.Errorf("eligible = %v, want %v", got.Eligible, c.eligible)
			}
			if got.Reason != c.seedReason {
				t.Errorf("seed_reason = %q, want %q", got.Reason, c.seedReason)
			}
			if !got.AnchorUTC.Equal(c.anchor) {
				t.Errorf("anchor = %v, want %v", got.AnchorUTC, c.anchor)
			}
			if got.Freshness != c.freshness {
				t.Errorf("freshness = %v, want %v", got.Freshness, c.freshness)
			}
			if got.Channel != c.channel {
				t.Errorf("seed_channel = %q, want %q — 채널은 언제나 유입 그대로다", got.Channel, c.channel)
			}
			if corroborated != c.corroborated {
				t.Errorf("ⓐ2 자격 = %v, want %v", corroborated, c.corroborated)
			}
		})
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

// 유입 사유 × ⓐ2 자격 → 세션 결정 연산(설계 3.3·5.4 유입 표 · 계획 4.4).
//
// **자격을 인자로 받는 것이 계약이다**: CurrentOrOpenIfCorroborated 는 "관측이 보증했다"를
// 전제한 연산이라(session/registry.go 의 호출자 계약), 자격 없이 지정하면 레지스트리가
// 다시 재지 않는 항(백로그 하한)을 아무도 안 본 채 세션이 열린다 — 교차 방송 오귀속이다.
func TestSessionOpForIngressReasonAndCorroboration(t *testing.T) {
	for _, c := range []struct {
		reason       recording.CompletionReason
		corroborated bool
		want         index.SessionOp
	}{
		// 파일·훅 유입 자체가 라이브 방증이다 — 관측 없이도 연다.
		{recording.ReasonNextFile, false, index.SessionOpenOrCurrent},
		{recording.ReasonIdle, false, index.SessionOpenOrCurrent},
		{recording.ReasonHook, false, index.SessionOpenOrCurrent},
		// 스캔은 옛 잔존물일 수 있어 관측이 대신 보증할 때만 연다.
		{recording.ReasonScan, true, index.SessionCurrentOrOpenIfCorroborated},
		{recording.ReasonScan, false, index.SessionCurrentOnly},
		// 재성장·사유 미상은 열지 않는다 — 현 세션이 있으면 귀속만 한다(계획 9절).
		{recording.ReasonRegrown, true, index.SessionCurrentOnly},
		{recording.ReasonUnknown, true, index.SessionCurrentOnly},
	} {
		if got := sessionOp(c.reason, c.corroborated); got != c.want {
			t.Errorf("%v(corroborated=%v): 연산 = %d, want %d", c.reason, c.corroborated, got, c.want)
		}
	}
}

// Handle 관통 — 판정한 연산이 그대로 store 에 도달한다(배선 검증).
// 관측이 비어 있으면 EpochKnown=false 라 ⓐ2 가 fail-closed 된다 — 스캔 유입이 세션을
// 여는 일이 없다는 뜻이다.
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
		t.Fatalf("관측이 비었는데 EpochKnown 이 참이다: %+v", got.Obs)
	}
}

// Handle 관통 — 폴러가 배선되면 스캔 유입이 ⓐ2 쌍과 개시 권한을 함께 얻는다.
// 주조 축(Seed)과 세션 축(SessionSource)이 **같은 스냅샷**을 본다는 것이 요점이다.
func TestHandleCarriesStateObservationToBothAxes(t *testing.T) {
	f := newFixture(t, 4000)
	f.opt.SeedEnabled = true
	f.reload()

	// 조각의 벽시계는 파일명이 정하므로 관측 시각을 그 근방으로 맞춘다.
	seg := f.segment("s1", segName(time.Now().Add(-4*time.Second), 0), 1000, recording.ReasonScan)
	observedAt := seg.StartWall.Add(2 * time.Second)
	f.observer.set("s1", mtxstate.Observation{
		Publishing: true, ObservedAt: observedAt,
		EpochStartedAt: seg.StartWall.Add(-time.Minute),
		EpochKnown:     true, Tier: mtxstate.TierOnlineTime,
	})

	if err := f.handle(seg); err != nil {
		t.Fatal(err)
	}

	gotSeed := f.store.lastSeed
	if !gotSeed.Eligible || gotSeed.Reason != index.SeedReasonStateObs {
		t.Fatalf("스캔 유입이 ⓐ2 로 적격이 되지 않았다: %+v", gotSeed)
	}
	if !gotSeed.AnchorUTC.Equal(observedAt.UTC()) {
		t.Fatalf("ⓐ2 앵커가 관측 시각이 아니다: %v (want %v)", gotSeed.AnchorUTC, observedAt.UTC())
	}
	if gotSeed.Channel != index.SeedChannelScan {
		t.Fatalf("seed_channel 이 유입 채널이 아니다: %q", gotSeed.Channel)
	}

	gotSrc := f.store.lastSource
	if gotSrc.Op != index.SessionCurrentOrOpenIfCorroborated {
		t.Fatalf("ⓐ2 자격이 성립했는데 연산 = %d", gotSrc.Op)
	}
	if !gotSrc.Obs.EpochKnown || !gotSrc.Obs.ObservedAt.Equal(observedAt.UTC()) {
		t.Fatalf("세션 결정이 받은 관측이 주조 판정과 다르다: %+v", gotSrc.Obs)
	}
	// tier ⓘ 는 여유 0 이다 — 등급을 값으로 접는 자리가 여기다.
	if gotSrc.Obs.EpochSlack != 0 {
		t.Fatalf("tier ⓘ 의 에폭 여유가 0 이 아니다: %v", gotSrc.Obs.EpochSlack)
	}
}

// 주조 신호가 **실제로 채택된 방증**을 싣는다(설계 9.1 f0~f3 의 seed_reason 단언).
// 라벨이 리터럴로 굳어 있으면 장부에는 state_obs 가, 로그에는 live_ingress 가 남아
// "어느 방증으로 주조됐나"를 로그만 보고는 알 수 없게 된다.
func TestSeedSignalCarriesAdoptedCorroboration(t *testing.T) {
	f := newFixture(t, 4000)
	f.opt.SeedEnabled = true
	f.reload()

	seg := f.segment("s1", segName(time.Now().Add(-4*time.Second), 0), 1000, recording.ReasonScan)
	f.observer.set("s1", mtxstate.Observation{
		Publishing: true, ObservedAt: seg.StartWall.Add(2 * time.Second),
		EpochStartedAt: seg.StartWall.Add(-time.Minute),
		EpochKnown:     true, Tier: mtxstate.TierOnlineTime,
	})
	f.store.seeds = true

	if err := f.handle(seg); err != nil {
		t.Fatal(err)
	}
	attrs := f.logs.attrs("cutoff_seeded")
	if attrs == nil {
		t.Fatal("주조했는데 cutoff_seeded 신호가 없다")
	}
	if attrs["reason"] != string(index.SeedReasonStateObs) {
		t.Errorf("reason 라벨 = %v, want %q", attrs["reason"], index.SeedReasonStateObs)
	}
	if attrs["channel"] != string(index.SeedChannelScan) {
		t.Errorf("channel 라벨 = %v, want %q", attrs["channel"], index.SeedChannelScan)
	}
}

// 관측 스냅샷은 조각당 **1회** 채취한다(계획 단계 4 · Seed 재사용 규약과 같은 근거).
// 재시도마다 다시 읽으면 같은 조각의 판정이 시도 순서에 따라 달라진다.
func TestObservationSnapshotTakenOncePerSegment(t *testing.T) {
	f := newFixture(t, 4000)
	f.opt.SeedEnabled = true
	f.reload()

	seg := f.segment("s1", segName(time.Now().Add(-4*time.Second), 0), 1000, recording.ReasonScan)
	f.observer.set("s1", mtxstate.Observation{
		Publishing: true, ObservedAt: seg.StartWall.Add(2 * time.Second),
		EpochStartedAt: seg.StartWall.Add(-time.Minute),
		EpochKnown:     true, Tier: mtxstate.TierOnlineTime,
	})
	contended := fmt.Errorf("%w: 대역", index.ErrLockContended)
	f.store.insertErrs = []error{contended, contended}

	if err := f.handle(seg); err != nil {
		t.Fatal(err)
	}
	if got := f.store.insertCallCount(); got != 3 {
		t.Fatalf("재시도가 3회가 아니다: %d — 이 케이스의 전제가 깨졌다", got)
	}
	if got := f.observer.callCount(); got != 1 {
		t.Fatalf("관측 채취가 %d회다(1회 기대) — 재시도마다 다시 읽으면 판정이 갈린다", got)
	}
}

// 세션 개시 경합(ErrSessionContended)도 락 경합과 같은 처분이다 — H9 백오프 재시도.
//
// **이 케이스가 지키는 것은 배타성이다**: store 는 sentinel 과 23505 PgError 를 한
// 연쇄에 함께 감싸므로(index/store.go sessionError), 한 오류가 "경합"과 "무결성 위반
// (=fateFatal)" 두 갈래에 동시에 해당한다. 경합 갈래를 먼저 읽지 않으면 정상 동시성이
// 즉시 종료로 처분돼 크래시루프가 된다.
func TestSessionContentionRetriesUntilExhaustion(t *testing.T) {
	f := newFixture(t, 4000)
	f.opt.SeedEnabled = true
	f.reload()

	seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
	// 실제 형상 그대로 — sentinel 로 감싼 바깥과 23505 PgError 인 안쪽 둘 다 있어야
	// 두 갈래 동시 해당이 재현된다.
	contended := fmt.Errorf("%w: stream_id=%q seq=%d: %w",
		index.ErrSessionContended, "s1", int64(0),
		&pgconn.PgError{Code: "23505", ConstraintName: "stream_sessions_one_live_uq"})
	errs := make([]error, f.opt.InsertRetryMax) // 상한만큼 전부 좌초
	for i := range errs {
		errs[i] = contended
	}
	f.store.insertErrs = errs

	err := f.handle(seg)

	// ⑴ 경합 신호로 기록된다(일반 재시도·즉시 종료와 구분되는 자기 이름).
	if n := f.logs.count(slog.LevelWarn, "session_open_contended"); n != f.opt.InsertRetryMax {
		t.Fatalf("session_open_contended = %d건, want %d — 경합이 다른 갈래로 새 나갔다",
			n, f.opt.InsertRetryMax)
	}
	// ⑵ 즉시 fatal 이 아니다. 23505 를 무결성 위반으로 읽으면 첫 시도에서 끝난다.
	if got := f.store.insertCallCount(); got != f.opt.InsertRetryMax {
		t.Fatalf("Insert 시도 = %d, want %d — 경합이 무결성 위반으로 처분됐다",
			got, f.opt.InsertRetryMax)
	}
	// ⑶ 상한을 소진해야 비로소 에러가 올라간다(기존 D8 처분에 합류).
	if err == nil {
		t.Fatal("지속 경합이 상한 소진 에러로 올라오지 않았다")
	}
	if !errors.Is(err, index.ErrSessionContended) {
		t.Fatalf("에러 연쇄에 ErrSessionContended 가 없다: %v", err)
	}
}
