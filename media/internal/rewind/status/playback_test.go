package status_test

// FromPlaybackState(M6 상태 한 건 → 응답 하나 — 설계 M5 2.1 · 보완 2 ㉠ · 보완 2 정정 ㉠ · M5 보충)의 표 시험이다.
// 이 함수는 Drain 변환과 회차 경로 RoundLookup 이 함께 쓰는 하나뿐인 매핑이다 — 국면 → 응답이 두 곳에서
// 갈리면 같은 회차를 두 경로가 다르게 답한다. 그래서 국면마다 Status · Answer · 오류 사유를 한 표로 못 박는다
// (R37 HeadPDT · R39 영값 · 빈 VOD · R50 변환).

import (
	"errors"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/status"
)

const base = "https://media.example/rewind"

var (
	first = time.Date(2026, 10, 25, 12, 0, 0, 0, time.UTC)
	head  = time.Date(2026, 10, 25, 13, 0, 4, 0, time.UTC)
)

func TestFromPlaybackStateMapsEveryPhase(t *testing.T) {
	vod := index.PlaybackState{
		Phase: index.PhaseVOD, RoundID: "R1", LatestSessionID: "S2",
		VODKey: "vod/str/S1/index.m3u8", FirstPDT: first, HeadPDT: head,
	}
	for _, c := range []struct {
		name       string
		in         index.PlaybackState
		want       status.Status
		wantAnswer status.Answer
	}{
		{"Unknown_은_NotFound", index.PlaybackState{Phase: index.PhaseUnknown}, status.Status{}, status.NotFound},
		// 회차 경로 전용(응답 계약 ⑤) — url · 범위는 null 이다.
		{"Live_는_미준비_live", index.PlaybackState{Phase: index.PhaseLive, RoundID: "R1"},
			status.Status{Mode: status.ModeLive}, status.Found},
		{"Ending_은_미준비_ending", index.PlaybackState{Phase: index.PhaseEnding, RoundID: "R1"},
			status.Status{Mode: status.ModeEnding}, status.Found},
		{"Ended_는_미준비_ended", index.PlaybackState{Phase: index.PhaseEnded, RoundID: "R1"},
			status.Status{Mode: status.ModeEnded}, status.Found},
		// R37: headPdt 는 VOD 문서 마지막 조각의 끝(배타적)을 그대로 싣는다 — 바꾸거나 빼지 않는다.
		{"VOD_는_준비_vod_url_범위", vod,
			status.Status{Ready: true, Mode: status.ModeVOD, URL: base + "/" + vod.VODKey, FirstPDT: first, HeadPDT: head},
			status.Found},
	} {
		got, answer, err := status.FromPlaybackState(c.in, base)
		if err != nil {
			t.Errorf("%s: FromPlaybackState 오류 = %v, want 없음", c.name, err)
			continue
		}
		if got != c.want || answer != c.wantAnswer {
			t.Errorf("%s: FromPlaybackState = (%+v, %v), want (%+v, %v)", c.name, got, answer, c.want, c.wantAnswer)
		}
	}
}

// 옮길 수 없는 값은 오류다 — Drain 은 무시 + WARN, 회차 경로는 503 이다(R39). 사유는 로그 칸 값이라 이름을
// 못 박는다. 영값(Phase 0)을 Unknown 으로 접으면 M6 의 무효 값 하나가 종료 항목을 지운다(R39 변이).
func TestFromPlaybackStateRejectsInvalid(t *testing.T) {
	for _, c := range []struct {
		name   string
		in     index.PlaybackState
		reason string
	}{
		{"영값", index.PlaybackState{}, status.ReasonOutOfRange},
		{"범위_밖", index.PlaybackState{Phase: index.PhaseEnded + 1}, status.ReasonOutOfRange},
		{"VOD_키_없음", index.PlaybackState{Phase: index.PhaseVOD, FirstPDT: first, HeadPDT: head}, status.ReasonVODIncomplete},
		{"VOD_첫_PDT_없음", index.PlaybackState{Phase: index.PhaseVOD, VODKey: "vod/k", HeadPDT: head}, status.ReasonVODIncomplete},
		{"VOD_머리_PDT_없음", index.PlaybackState{Phase: index.PhaseVOD, VODKey: "vod/k", FirstPDT: first}, status.ReasonVODIncomplete},
	} {
		_, _, err := status.FromPlaybackState(c.in, base)
		var invalid *status.InvalidError
		if !errors.As(err, &invalid) {
			t.Errorf("%s: FromPlaybackState 오류 = %v, want *status.InvalidError", c.name, err)
			continue
		}
		if invalid.Reason != c.reason {
			t.Errorf("%s: 사유 = %q, want %q", c.name, invalid.Reason, c.reason)
		}
	}
}
