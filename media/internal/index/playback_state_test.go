package index

import "testing"

// 국면 번호는 M5 Drain 변환표가 기대는 값이다(Unknown 1 · Live 2 · Ending 3 · VOD 4 · Ended 5).
// 번호가 하나라도 밀리면 Drain 이 Ending 을 VOD 로 읽거나 영값 0 을 유효한 국면으로 받는다 —
// 그래서 값 자체를 표로 못 박는다.
func TestPlaybackPhaseValues(t *testing.T) {
	cases := []struct {
		name string
		got  PlaybackPhase
		want int
	}{
		{"PhaseUnknown", PhaseUnknown, 1},
		{"PhaseLive", PhaseLive, 2},
		{"PhaseEnding", PhaseEnding, 3},
		{"PhaseVOD", PhaseVOD, 4},
		{"PhaseEnded", PhaseEnded, 5},
	}
	for _, c := range cases {
		if int(c.got) != c.want {
			t.Errorf("%s = %d, want %d", c.name, int(c.got), c.want)
		}
	}
}
