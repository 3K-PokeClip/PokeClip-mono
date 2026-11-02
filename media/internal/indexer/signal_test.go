package indexer

// s4 — 무징후 금지(설계 S4 · 9.1 `s4_signal_present`·`s4_signal_grades`)의 **M3 몫**.
//
// 재는 것은 두 가지다: 열화 국면에 신호가 **뜨는가**, 그리고 그 신호의 **등급·라벨이
// 설계대로인가**. 건수만 세면 사유 라벨이 뒤바뀌어도 통과하고, 등급을 안 보면 운영자가
// 볼 수 없는 레벨로 내려가도 통과한다.
//
// **이 파일이 재는 범위는 indexer 패키지가 발화하는 신호뿐이다.** 나머지 M3 신설 신호는
// 발화 지점이 있는 패키지의 테스트가 같은 규율로 잰다(중복 픽스처를 만들지 않는다):
//
//	session_floor_rejected  — internal/session/registry_test.go
//	                          TestDecideRejectsAttributionBelowSessionFloor(발화·경계값)
//	                          TestSessionSignalsCarryWarnGradeAndActionableLabels(등급·라벨)
//	session_epoch_ambiguous — internal/session/registry_test.go
//	                          TestDecideFoldsToCurrentOnlyWhenCorroborationFails(발화·경계값)
//	                          TestSessionSignalsCarryWarnGradeAndActionableLabels(등급·라벨)
//	mtxstate_poll_failed    — internal/mtxstate/state_test.go
//	                          사유별 4국면 + TestPollFailureSignalsAreWarnGrade(등급)
//	mtxstate_disabled·started — cmd/segment-indexer/boot_test.go TestStartObserver* 3종
//	cutoff_seeded(reason·channel) — seed_judgment_test.go
//	                          TestSeedSignalCarriesAdoptedCorroboration
//
// **s4 의 완결은 M4 다**(계획 6절): 13종 중 `rewind_cutoff_absent` 가 M4 이고,
// M1 이 착지시킨 신호들(`watcher_degraded`·`watcher_stopped`·`scan_collect_stalled`·
// `fs_degraded`)의 등급 단언도 그 완결에 함께 든다.

import (
	"log/slog"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// seed_declined — 주조되지 않은 열화 국면이 **관측 가능**해야 한다(S4).
//
// 잡는 결함: 이 갈래가 Debug 로 내려가거나(평시 `seed_skipped` 와 뭉개지면) 사유 라벨이
// 빠지면, "왜 되감기가 안 열리나"를 로그로 되짚을 수 없다 — 미주조는 조용한 고장이라
// 신호가 유일한 흔적이다. 두 어휘를 따로 확인하는 이유는 원인이 서로 다르기 때문이다
// (carrier 부재 vs 방증 낡음).
func TestDeclinedSeedIsSignalledWithItsReason(t *testing.T) {
	for _, decline := range []index.SeedDecline{
		index.DeclineNotSettleable,
		index.DeclineStaleCorroboration,
	} {
		t.Run(string(decline), func(t *testing.T) {
			f := newFixture(t)
			f.opt.SeedEnabled = true
			f.reload()
			f.store.declineAs = decline

			seg := f.segment("s1", segName(baseWall, 0), 4000, recording.ReasonNextFile)
			if err := f.handle(seg); err != nil {
				t.Fatal(err)
			}

			if n := f.logs.count(slog.LevelInfo, "seed_declined"); n != 1 {
				t.Fatalf("seed_declined INFO 가 %d건이다(1건 기대) — 미주조가 무징후다", n)
			}
			attrs := f.logs.attrs("seed_declined")
			if attrs["reason"] != string(decline) {
				t.Errorf("reason 라벨 = %v, want %q", attrs["reason"], decline)
			}
			if attrs["stream_id"] != "s1" {
				t.Errorf("stream_id 라벨 = %v, want s1 — 어느 스트림인지 없으면 조치가 안 된다", attrs["stream_id"])
			}
		})
	}
}

// 평시(승계·비적격)는 신호가 아니다 — 점멸 금지의 반대편 단언이다.
//
// 잡는 결함: `seed_declined` 를 모든 미주조에 붙이면 플래그 OFF·비적격 유입이 상시
// 발화해 신호가 배경 소음이 되고, 진짜 열화가 그 안에 묻힌다.
func TestOrdinaryNonSeedingIsNotSignalled(t *testing.T) {
	f := newFixture(t)
	f.opt.SeedEnabled = true
	f.reload()
	f.store.declineAs = index.DeclineSkipped // 기존 컷오프 승계 — 예외가 아니다(d5)

	seg := f.segment("s1", segName(baseWall, 0), 4000, recording.ReasonNextFile)
	if err := f.handle(seg); err != nil {
		t.Fatal(err)
	}

	if n := f.logs.count(slog.LevelInfo, "seed_declined"); n != 0 {
		t.Errorf("승계 국면에서 seed_declined 가 %d건 떴다 — 평시가 신호가 되면 안 된다", n)
	}
}
