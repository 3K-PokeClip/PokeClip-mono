package indexer

// c8 cc M1(deferred_ml.md 34행) — 고정 행 다음 영상 행의 거짓 구멍.
//
// 고치기 전에는 판독 실패로 고정한 행 k−1 이 영상 칸을 비웠다(NextLink 고정 갈래). 그래서 다음 행 k 는 mtxi 축
// (기대 mtxi + 장부 길이)으로 쟀다. 장부 길이가 영상 길이(판 2)인데 k−2 가 소리 머리 조각이면, 기대 mtxi 가 영상 끝보다
// 머리 앞섬 m(k−2)만큼 이르러 d = m(k−2) − m(k) 의 거짓 구멍(여기 226ms)이 생기고, 구멍 갈래가 k 를 앞 pos + 장부
// 길이에 이어 영상 도장이 226ms 뒤로 간다(k−1 의 ③ 영상은 [14.226s, 18.226s) 인데 k 의 ③ 영상이 18.0s 에서 시작).
// 지금은 고정 행이 기대 영상 끝(앞 영상 끝 + 장부 길이)을 들어 k 를 영상 축으로 잰다(playback.NextLink).
//
// 조각 0: mtxi 10s · 소리 머리 · 영상 [226ms, 4226ms) → b = 14.226s · 장부 길이 4s(영상 길이).
// 조각 1: 판독 실패(실제로는 mtxi 14.0s · 소리 머리 · 영상 [14.226s, 18.226s)) → 고정 pos 14s.
// 조각 2: 영상 머리 · mtxi = a = 18.226s — 조각 1 영상 끝에 맞닿음. 옳은 도장 = 18.226s(offset 0).
import (
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

func TestPinnedRowThenVideoRowHasNoFalseHole(t *testing.T) {
	f := newFixture(t, 4000, 4000, 4000)
	r := f.injectReads()
	f.store.scriptSessions("S1", "S1", "S1")
	f.rowWithVideo(r, 0, mtxiOf(recA, 10*time.Second), playback.TrackSpan{Start: 226 * time.Millisecond, End: 4_226 * time.Millisecond})
	f.row(r, 4*time.Second, nil) // 판독 실패 — 고정
	f.rowWithVideo(r, 8*time.Second, mtxiOf(recA, 18_226*time.Millisecond), playback.TrackSpan{End: 4 * time.Second})

	if got := f.playbackOf(1); got.PlaybackPos != 14*time.Second || !got.PosPinned {
		t.Fatalf("고정 행 = pos %v · 고정 %v, want 14s · 고정(전제)", got.PlaybackPos, got.PosPinned)
	}
	if got := f.playbackOf(2); got.PlaybackPos != 18_226*time.Millisecond || got.StitchOffset != 0 {
		t.Errorf("고정 행 다음 영상 행 도장 = pos %v · offset %v, want pos 18.226s · offset 0 — 거짓 구멍 %v",
			got.PlaybackPos, got.StitchOffset, 18_226*time.Millisecond-got.PlaybackPos)
	}
}
