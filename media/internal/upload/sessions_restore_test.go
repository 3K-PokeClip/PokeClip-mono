package upload

import (
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// 회차 메모리의 복원 몫(POK-196 PDT 계획 4절 c10b · 설계 r6 2.10)을 잰다 — 끊김 줄 · 복원 줄 · 복원 링크 · 깨움 · 세션 단위
// 백오프. 네트워크 · 장부는 없다(sessionMemory 만).

func newMemoryForTest() *sessionMemory {
	return newSessionMemory(DefaultOptions(nil, ""))
}

// unknownRequest 는 인덱서가 사슬을 모를 때 보내는 ③ 요청이다 — 도장 칸이 비어 있다.
func unknownRequest(seq int64) index.UploadTarget {
	return index.UploadTarget{StreamID: "demo", Axis: index.AxisPlayback, Seq: seq, SessionID: "S-1", PosUnknown: true}
}

// 위치 모름 요청은 그 seq 부터 표를 끊는다 — 앞 줄의 보정값이 뒤 조각에 새지 않는다. 잡는 결함: 끊지 않으면 재기동 · 판독
// 포기 뒤 조각을 스위퍼가 낡은 보정값으로 만든다(겹침 · 틈).
func TestUnknownRequestBreaksOffsetTable(t *testing.T) {
	m := newMemoryForTest()
	now := time.Unix(1_000, 0)
	m.recordLive(index.UploadTarget{SessionID: "S-1", Seq: 10, StitchOffset: stitch}, now)

	m.recordLive(unknownRequest(12), now)

	if off, ok := m.offsetAt("S-1", 11); !ok || off != stitch {
		t.Errorf("seq 11 = (%v, %v), want (5s, 참) — 끊김 앞은 그대로", off, ok)
	}
	for _, seq := range []int64{12, 13} {
		if _, ok := m.offsetAt("S-1", seq); ok {
			t.Errorf("seq %d 에 보정값이 있다 — 끊김 줄 뒤는 표가 없다", seq)
		}
	}
}

// 복원 줄은 표가 모르는 seq 에만 적힌다 — 실시간이 이미 적은 줄은 덮지 않는다(실시간 값이 정본이다). 고정 링크는 고정 줄도
// 적는다. 같은 seq 의 끊김 줄은 복원 줄이 이긴다.
func TestRestoredLinesFillOnlyUnknownSeqs(t *testing.T) {
	m := newMemoryForTest()
	now := time.Unix(1_000, 0)
	m.recordLive(index.UploadTarget{SessionID: "S-1", Seq: 10, StitchOffset: stitch}, now)
	m.recordLive(unknownRequest(12), now)

	m.recordRestored("S-1", []playback.Link{
		{Seq: 11, Offset: time.Second},
		{Seq: 12, Offset: 2 * time.Second},
		{Seq: 13, Offset: 3 * time.Second, Pos: 40 * time.Second, Pinned: true},
	}, now)

	want := map[int64]time.Duration{11: stitch, 12: 2 * time.Second, 13: 3 * time.Second, 14: 3 * time.Second}
	for seq, off := range want {
		if got, ok := m.offsetAt("S-1", seq); !ok || got != off {
			t.Errorf("seq %d = (%v, %v), want (%v, 참)", seq, got, ok, off)
		}
	}
	if pos, ok := m.pinnedPos("S-1", 13); !ok || pos != 40*time.Second {
		t.Errorf("고정 줄 seq 13 = (%v, %v), want (40s, 참)", pos, ok)
	}
}

// 복원 링크는 회차마다 마지막 하나를 든다 — 인덱서가 비차단으로 읽는다.
func TestRestoredLinkIsLatestPerSession(t *testing.T) {
	m := newMemoryForTest()
	if _, ok := m.restoredLink("S-1"); ok {
		t.Fatal("복원 전인데 링크가 있다")
	}

	m.setRestored("S-1", restoredLink{link: playback.Link{Seq: 7, SessionID: "S-1"}, bytes: 64})
	m.setRestored("S-1", restoredLink{link: playback.Link{Seq: 9, SessionID: "S-1"}, bytes: 64})

	if got, ok := m.restoredLink("S-1"); !ok || got.link.Seq != 9 {
		t.Errorf("링크 = (%+v, %v), want seq 9", got, ok)
	}
}

// 위치 모름 요청은 그 회차의 복원을 깨운다 — 깨움은 회차마다 하나로 합쳐지고, 실패한 회차는 백오프가 끝나기 전에는 꺼내지
// 않는다(SweepEvery · 2^n · 상한 BackoffMax). 성공하면 백오프가 풀린다.
func TestRestoreWakeMergesPerSessionAndBacksOff(t *testing.T) {
	m := newMemoryForTest()
	now := time.Unix(1_000, 0)
	m.recordLive(unknownRequest(12), now)
	m.recordLive(unknownRequest(13), now)
	select {
	case <-m.wakeCh():
	default:
		t.Fatal("깨움 신호가 없다")
	}

	got := m.takeWakes(now)
	if len(got) != 1 || got[0] != (restoreWake{streamID: "demo", sessionID: "S-1"}) {
		t.Fatalf("깨움 = %+v, want S-1 하나", got)
	}
	m.restoreFailed("S-1", now)
	m.wake("demo", "S-1")
	if got := m.takeWakes(now.Add(defaultSweepEvery - time.Second)); len(got) != 0 {
		t.Errorf("백오프 안 깨움 = %+v, want 없음", got)
	}
	m.wake("demo", "S-1")
	if got := m.takeWakes(now.Add(defaultSweepEvery)); len(got) != 1 {
		t.Errorf("백오프 뒤 깨움 = %+v, want S-1", got)
	}
	m.restoreFailed("S-1", now)
	m.restoreSucceeded("S-1")
	m.wake("demo", "S-1")
	if got := m.takeWakes(now); len(got) != 1 {
		t.Errorf("성공 뒤 깨움 = %+v, want 바로 S-1", got)
	}
}

// 같은 입력(구간 · 닻 · 대조 행 · 행 크기들)의 불일치 판정은 다시 하지 않는다(설계 r6 2.10 「끝맺음」 — r3 cc-P2). 입력이
// 바뀌면(꼬리 교정으로 크기가 바뀜) 다시 판정한다.
func TestMismatchMemoryKeyedByInputs(t *testing.T) {
	m := newMemoryForTest()
	span := holeSpan{from: 10, to: 12, anchor: 9, check: 13}
	m.recordMismatch("S-1", span, "64,64,64")

	if !m.knownMismatch("S-1", span, "64,64,64") {
		t.Error("같은 입력인데 다시 판정한다")
	}
	if m.knownMismatch("S-1", span, "64,80,64") {
		t.Error("입력이 바뀌었는데 지난 판정을 쓴다")
	}
}

// 수명이 지난 회차는 복원 실패 이력도 함께 치운다 — 남기면 그 항목이 맵에서 영영 안 지워진다(c10b cc M1 · cx M).
func TestEvictDropsRestoreFailureHistory(t *testing.T) {
	m := newMemoryForTest()
	now := time.Unix(1_000, 0)
	m.recordLive(unknownRequest(12), now)
	m.restoreFailed("S-1", now)
	m.forget("S-1")

	m.evict(now.Add(defaultSessionTTL + time.Second))

	if n := len(m.byID); n != 0 {
		t.Errorf("수명 뒤 회차 항목 %d개, want 0", n)
	}
}
