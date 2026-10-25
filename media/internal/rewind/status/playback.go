package status

import (
	"fmt"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// FromPlaybackState 가 값을 옮기지 못한 사유다 — rewind_final_invalid 의 reason 칸 값이다.
const (
	ReasonOutOfRange    = "out_of_range"   // 국면이 영값이거나 범위 밖
	ReasonVODIncomplete = "vod_incomplete" // VOD 인데 키 · 범위 가운데 빈 칸이 있다
)

// InvalidError 는 FromPlaybackState 가 옮길 수 없는 M6 상태다. Drain 은 그 값을 버리고 WARN 을, 회차 경로는
// 503 을 낸다.
type InvalidError struct {
	Reason string
	Phase  index.PlaybackPhase
}

func (e *InvalidError) Error() string {
	return fmt.Sprintf("status: 국면 %d 의 상태를 응답으로 옮길 수 없다(%s)", e.Phase, e.Reason)
}

// FromPlaybackState 는 M6 상태 한 건을 응답 하나로 옮긴다. Drain 변환과 RoundLookup 결과 변환이 이 함수 하나를 쓴다
// (보완 2 ㉠ — 국면 → 응답 매핑은 여기 한 곳에만 있다). baseURL 은 REWIND_PUBLIC_BASE_URL 이다.
//
//	Unknown  → (_, NotFound, nil)
//	Live     → (ready:false · mode:live · url · 범위 null, Found, nil) — 회차 경로 전용(응답 계약 ⑤). Drain 은 부르기
//	           전에 거른다(「Live 무시 + WARN」은 Drain 자리의 규칙이다)
//	Ending   → (ready:false · mode:ending, Found, nil)
//	Ended    → (ready:false · mode:ended, Found, nil)
//	VOD      → (ready:true · mode:vod · url = baseURL + "/" + VODKey · 범위 = FirstPDT · HeadPDT, Found, nil)
//	VOD 인데 키 · 두 PDT 가운데 빈 칸 → *InvalidError(vod_incomplete) · 영값 · 범위 밖 국면 → *InvalidError(out_of_range)
func FromPlaybackState(st index.PlaybackState, baseURL string) (Status, Answer, error) {
	switch st.Phase {
	case index.PhaseUnknown:
		return Status{}, NotFound, nil
	case index.PhaseLive:
		return Status{Mode: ModeLive}, Found, nil
	case index.PhaseEnding:
		return Status{Mode: ModeEnding}, Found, nil
	case index.PhaseEnded:
		return Status{Mode: ModeEnded}, Found, nil
	case index.PhaseVOD:
		if st.VODKey == "" || st.FirstPDT.IsZero() || st.HeadPDT.IsZero() {
			return Status{}, 0, &InvalidError{Reason: ReasonVODIncomplete, Phase: st.Phase}
		}
		return Status{Ready: true, Mode: ModeVOD, URL: baseURL + "/" + st.VODKey, FirstPDT: st.FirstPDT, HeadPDT: st.HeadPDT}, Found, nil
	}
	return Status{}, 0, &InvalidError{Reason: ReasonOutOfRange, Phase: st.Phase}
}
