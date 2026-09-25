package rewind_test

import (
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
)

// 끊김 표시 술어(계획 4.2-R RF)의 단위 검증 — 렌더와 커밋 ⑤ 축출 증분이 이 함수 하나를 부르므로
// 술어의 항을 여기서 하나씩 가른다. 회차 S 는 P 를 계승했고 장부에서 가장 작은 seq 가 13 이다.
//
//	DISCONTINUITY(k) ⟺ k = 자기 회차의 첫 조각 ∧ 그 회차의 inherits_session ≠ NULL
//	회차 첫 조각 = max(회차 최소 seq, cutoff)
func TestHasDiscontinuityTag(t *testing.T) {
	inherited := rewind.Session{ID: "S", InheritsSession: "P", MinSeq: 13}
	fresh := rewind.Session{ID: "S", MinSeq: 13}
	tests := []struct {
		name    string
		seq     int64
		session rewind.Session
		cutoff  int64
		want    bool
	}{
		{"계승_회차_첫_조각", 13, inherited, 0, true},
		{"계승_회차_둘째_조각", 14, inherited, 0, false},
		// 계승하지 않은 회차는 첫 조각에도 표시하지 않는다 — 앞에 이어 붙는 다른 회차가 없다.
		{"비계승_회차_첫_조각", 13, fresh, 0, false},
		{"컷오프가_회차_앞", 13, inherited, 5, true},
		// 컷오프가 회차 중간이면 회차 첫 조각은 컷오프 행이다. 컷오프 아래 행은 목록에 실리지 않으므로
		// 재기동(컷오프 이상만 다시 싣는다) 전후로 같은 행이 같은 판정을 받으려면 이래야 한다.
		{"컷오프가_회차_중간_첫_조각은_컷오프_행", 14, inherited, 14, true},
		{"컷오프가_회차_중간_최소_seq_행은_아님", 13, inherited, 14, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := rewind.HasDiscontinuityTag(tt.seq, tt.session, tt.cutoff)

			if got != tt.want {
				t.Errorf("HasDiscontinuityTag(%d, %+v, cutoff=%d) = %v, want %v",
					tt.seq, tt.session, tt.cutoff, got, tt.want)
			}
		})
	}
}
