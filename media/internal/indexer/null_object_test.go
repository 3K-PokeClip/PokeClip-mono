package indexer

// 널 오브젝트(m3a·m3b)와 강등 국면 되돌림(f6e)의 거동 검증 — POK-168 r15a · ADR-063 결정 2.

import (
	"context"
	"log/slog"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// degradedFixture 는 어댑터 없이(nil) 인덱서를 만든다 — 워처 강등 국면의 재현이다.
func degradedFixture(t *testing.T, probeVals ...int64) *fixture {
	t.Helper()
	f := newFixture(t, probeVals...)
	f.ix = New(f.store, f.probe.fn, nil, nil, f.opt, slog.New(f.logs))
	return f
}

// m3a — New 가 nil Adopter 를 널 오브젝트로 편다: 되돌림 경로가 panic 없이 돌고
// adopt_dropped_degraded 로 계수된다.
func TestNewNilAdopterBecomesNullObject(t *testing.T) {
	f := degradedFixture(t, 4000)
	if _, ok := f.ix.adopt.(noAdopter); !ok {
		t.Fatalf("nil Adopter 가 널 오브젝트로 패딩되지 않았다: %T", f.ix.adopt)
	}

	// H4 되돌림 경로 — 방금 쓰인(유휴 아님) 파일의 Idle 판정은 되돌린다.
	seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonIdle)
	f.touch(seg.Path, time.Now())
	if err := f.handle(seg); err != nil {
		t.Fatalf("강등 국면 H4 되돌림이 에러다: %v", err)
	}
	if n := f.logs.count(slog.LevelWarn, "adopt_dropped_degraded"); n != 1 {
		t.Fatalf("adopt_dropped_degraded 가 %d건이다(1건 기대)", n)
	}
	if got := len(f.store.records("s1")); got != 0 {
		t.Fatalf("되돌린 조각이 기록됐다: %d행", got)
	}
}

// m3b — SetAdopter(nil)도 널 오브젝트로 편다(plain nil 한정). 실어댑터 재장착도 반영된다.
func TestSetAdopterNilGuard(t *testing.T) {
	f := newFixture(t)

	f.ix.SetAdopter(nil)
	if _, ok := f.ix.adopt.(noAdopter); !ok {
		t.Fatalf("SetAdopter(nil)이 널 오브젝트로 패딩되지 않았다: %T", f.ix.adopt)
	}

	f.ix.SetAdopter(f.adopter) // 재장착(f6f 의 SetAdopter 절반)
	if f.ix.adopt != Adopter(f.adopter) {
		t.Fatalf("재장착이 반영되지 않았다: %T", f.ix.adopt)
	}
}

// f6e — 강등 국면의 Scan 은 "최신 1개"를 아무에게도 넘기지 못하고(널 오브젝트가 계수하며
// 버림) 그 파일은 미기록으로 남되, 성장이 멈춘 다음 주기에 정상 처리된다(유실 0,
// duration 안 잘림).
func TestDegradedScanLatestRecoversNextCycle(t *testing.T) {
	f := degradedFixture(t, 4000, 4000)
	seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonScan)
	f.touch(seg.Path, time.Now()) // 방금 쓰인 파일 — Scan(d)가 되돌림을 시도한다

	if err := f.ix.Scan(context.Background(), f.root); err != nil {
		t.Fatalf("강등 국면 Scan 실패: %v", err)
	}
	if n := f.logs.count(slog.LevelWarn, "adopt_dropped_degraded"); n != 1 {
		t.Fatalf("최신 1개 되돌림 계수가 %d건이다(1건 기대)", n)
	}
	if got := len(f.store.records("s1")); got != 0 {
		t.Fatalf("성장 중일 수 있는 파일이 기록됐다: %d행", got)
	}

	// 성장이 멈췄다(mtime 이 유휴 기준을 넘겼다) — 다음 주기에 정상 회수된다.
	f.touch(seg.Path, time.Now().Add(-time.Hour))
	if err := f.ix.Scan(context.Background(), f.root); err != nil {
		t.Fatalf("회수 주기 Scan 실패: %v", err)
	}
	recs := f.store.records("s1")
	if len(recs) != 1 {
		t.Fatalf("다음 주기 회수가 안 됐다: %d행(1행 기대) — 유실", len(recs))
	}
	if recs[0].DurationMS != 4000 {
		t.Fatalf("duration 이 잘렸다: %d(4000 기대)", recs[0].DurationMS)
	}
}
