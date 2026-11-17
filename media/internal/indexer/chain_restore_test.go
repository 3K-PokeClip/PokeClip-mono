package indexer

import (
	"log/slog"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// ③ 사슬 모름 상태(POK-196 PDT 계획 4절 c10b · 설계 r6 2.10 「인덱서 쪽」)를 잰다 — 사슬을 모르는 행은 도장을 짓지 않고 위치
// 모름으로 요청하고, 업로더의 복원 링크가 모르는 마지막 행에 닿으면 그 링크에서 다시 잇는다.

// 재기동 뒤 이미 행이 있는 회차의 첫 행은 사슬을 모른다 — offset 0 으로 다시 열지 않는다(E18 — 그러면 겹침 · 틈이 난다).
// 복원 링크가 늦으면(앞 행) 계속 모름이고, 모르는 마지막 행에 닿으면 그 링크의 보정값으로 잇는다. 잡는 결함(음성 대조 — 부모
// 커밋): 재기동 뒤 첫 행이 자기 mtxi 로 사슬을 다시 열어 앞 회차 도장과 겹친다.
func TestRestartedChainIsUnknownUntilRestoredLinkCatchesUp(t *testing.T) {
	f := newFixture(t, 4000, 4000, 4000, 4000, 4000)
	r := f.injectReads()
	f.store.scriptSessions("S1", "S1", "S1", "S1", "S1")
	f.row(r, 0, mtxiOf(recA, 10*time.Second))

	f.reload()
	r = f.injectReads()
	f.row(r, 4*time.Second, mtxiOf(recA, 14*time.Second))
	f.row(r, 8*time.Second, mtxiOf(recA, 18*time.Second))
	f.upload.setRestored("S1", playback.Link{Seq: 1, SessionID: "S1"})
	f.row(r, 12*time.Second, mtxiOf(recA, 22*time.Second))
	n3 := int64(3)
	f.upload.setRestored("S1", playback.Link{Seq: 3, SessionID: "S1", Recorder: &recA, Number: &n3,
		Mtxi: 22 * time.Second, Dur: 4 * time.Second, Pos: 23 * time.Second, Offset: time.Second})
	f.row(r, 16*time.Second, mtxiOf(recA, 26*time.Second))

	for _, seq := range []int64{1, 2, 3} {
		if got := f.playbackOf(seq); !got.PosUnknown || got.PlaybackPos != 0 || got.StitchOffset != 0 {
			t.Errorf("seq %d = %+v, want 위치 모름 · 도장 칸 빈 값", seq, got)
		}
	}
	if got := f.playbackOf(4); got.PosUnknown || got.PlaybackPos != 27*time.Second || got.StitchOffset != time.Second {
		t.Errorf("seq 4 = %+v, want 복원 링크에서 이은 pos 27s · offset 1s", got)
	}
	if a := f.logs.attrs("chain_unknown"); a["reason"] != "restart" || a["seq"] != int64(1) {
		t.Errorf("chain_unknown = %v, want reason restart · seq 1", a)
	}
	if n := f.logs.count(slog.LevelInfo, "chain_unknown"); n != 1 {
		t.Errorf("chain_unknown = %d줄, want 모름이 시작된 행에서 1줄", n)
	}
	if a := f.logs.attrs("chain_resumed"); a["seq"] != int64(3) || a["unknown_rows"] != int64(3) {
		t.Errorf("chain_resumed = %v, want seq 3 · 모름이었던 행 3", a)
	}
}

// 판독 포기(read_failed — c5 (ㄱ) 상한 뒤 칸 NULL 커밋) 행은 일시 실패라 도장을 고정하지 않고 위치 모름이다(설계 r6 2.9 「일시
// 실패는 고정하지 않는다」 — 복원이 정상 판독으로 정한다). 입력 상한 초과(input_too_large)는 파일이 자라기만 해 결정적이라
// 지금처럼 기대값에 고정한다(복원도 같은 규칙으로 고정한다). 잡는 결함: 일시 실패 고정이 뒤에 정상으로 읽힌 ② 로 되살린
// 사슬과 바이트가 갈린다(r1 cx-2 반례).
func TestTransientGiveUpIsPosUnknownButTooLargeIsPinned(t *testing.T) {
	for _, tt := range []struct {
		failure     string
		wantUnknown bool
	}{
		{partialReadFailed, true},
		{partialInputTooLarge, false},
	} {
		f := newFixture(t, 4000)
		r := f.injectReads()
		f.store.scriptSessions("S1")
		f.row(r, 0, mtxiOf(recA, 10*time.Second))
		prev := f.ix.cursors["s1"].Tail
		rec := index.Record{StreamID: "s1", Seq: 1, LocalPath: prev.LocalPath + ".next", Bytes: 1000}
		res := index.InsertCommit{SessionID: "S1", DurationMS: 4000, PlaybackS3Key: "dvr/s1/seg/000001.m4s"}

		got, pinned := f.ix.advanceChain("s1", prev, rec, res, timingRead{failure: tt.failure})

		if got == nil || got.PosUnknown != tt.wantUnknown || pinned == tt.wantUnknown {
			t.Errorf("%s: ③ 작업 = %+v · 고정 %v, want 위치 모름 %v", tt.failure, got, pinned, tt.wantUnknown)
		}
		if !tt.wantUnknown && got != nil && got.PlaybackPos != 14*time.Second {
			t.Errorf("%s: 고정 도장 = %v, want 앞 pos + 길이 = 14s", tt.failure, got.PlaybackPos)
		}
	}
}

// 모름인 동안 새 회차가 열리면 그 회차는 자기 첫 행에서 offset 0 으로 연다 — 모름은 회차에 묶인다.
func TestNewSessionOpensFreshWhileOldSessionUnknown(t *testing.T) {
	f := newFixture(t, 4000, 4000, 4000)
	r := f.injectReads()
	f.store.scriptSessions("S1", "S1", "S2")
	f.row(r, 0, mtxiOf(recA, 10*time.Second))

	f.reload()
	r = f.injectReads()
	f.row(r, 4*time.Second, mtxiOf(recA, 14*time.Second))
	f.row(r, 8*time.Second, mtxiOf(recB, 3*time.Second))

	if got := f.playbackOf(2); got.PosUnknown || got.PlaybackPos != 3*time.Second || got.StitchOffset != 0 {
		t.Errorf("새 회차 첫 행 = %+v, want pos 3s · offset 0", got)
	}
}

// 회차를 연 행에 mtxi 가 없으면(결정적 부재 — ④ⓑ) 지금처럼 다음 정상 판독 행이 사슬을 연다 — 모름이 아니다(이 프로세스가
// 회차의 시작을 봤다).
func TestOpeningRowWithoutMtxiStillLetsNextRowOpenChain(t *testing.T) {
	f := newFixture(t, 4000, 4000)
	r := f.injectReads()
	f.store.scriptSessions("S1", "S1")

	f.row(r, 0, nil)
	f.row(r, 4*time.Second, mtxiOf(recA, 14*time.Second))

	if got := f.playbackOf(1); got.PosUnknown || got.PlaybackPos != 14*time.Second {
		t.Errorf("seq 1 = %+v, want pos 14s(자기 mtxi) — 모름이 아니다", got)
	}
	if n := f.logs.count(slog.LevelInfo, "chain_unknown"); n != 0 {
		t.Errorf("chain_unknown = %d줄, want 0", n)
	}
}
