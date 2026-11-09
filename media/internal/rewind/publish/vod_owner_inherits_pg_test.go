package publish

// 소유 세션의 계승 지움 PG 시험이다(POK-549 계획 R17 · 변이 M16 교정). 렌더는 첫 행의 세션(소유)의 계승을 지운다
// (vodJob.playlistInput) — 그래야 문서 첫 조각 앞에 끊김 표시가 서지 않고 발행 전 검사 S5 (b)(끊김 표시 ⟺ 세션 경계)를
// 지난다. 기존 픽스처의 소유 세션은 모두 비계승이라 그 지움을 빼는 변이 M16 이 살아남았다 — 소유 세션이 계승을 가진 세
// 경우로 고정한다. 픽스처는 vod_round_negctl_pg_test.go 의 것을 쓴다.

import (
	"strings"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// owner_inherits_cleared(R17 · M16) — 문서 첫 행의 세션이 계승을 가져도 사슬 범위로 frozen 확정하고 첫 조각 앞에 끊김
// 표시가 없다. 세 경우:
//
//	머리가 다른 회차 세션을 계승       S 가 회차 P 의 세션 P 를 계승하나 회차가 달라 간선이 끊긴다(chainEdgeSQL) · 사슬 [S]
//	M6 전 부모 · 컷오프 = 계승 첫 행   M6 전 세션 P 를 계승해 새 회차 S 를 연 S — 옛 간선이 이어 사슬 P ← S · 컷오프 5 —
//	                                   P 의 행이 모두 컷오프 아래라 첫 렌더 행이 S(되감기를 켜기 전 세션을 이은 첫 송출)
//	컷오프 = 계승 세션 첫 행           같은 회차 사슬 P ← S · 컷오프 5 — P 의 행이 모두 컷오프 아래라 첫 렌더 행이 S
//
// 계승은 부모 조각의 종료 시각이 있어야 서므로(session/registry.go joinable) 부모 P 는 모두 행을 가진다.
// M6 전 세션을 계승한 머리는 장부에서 사슬 [S] 하나가 되지 못한다 — 부모 round_id 가 NULL 이면 옛 규칙대로 잇는다.
// 그래서 「사슬 밖 세션 계승」은 회차가 다른 부모로 만든다.
//
// 변이 M16(소유 세션 계승 지움 제거)은 첫 조각 앞에 끊김 표시가 서 발행 전 검사 S5 (b)에서 FAIL.
func TestFreezeVODOwnerSessionDropsInherits(t *testing.T) {
	tests := []struct {
		name        string
		ss          []roundSess
		sessions    string // seq 0.. 의 세션(한 글자씩)
		cutoff      int64
		chain       index.Chain
		key         string
		first, last int64
	}{
		{name: "머리가_다른_회차_세션을_계승", ss: []roundSess{
			{id: "P", round: "P", td: 6, vod: ResultFrozen, doc: true},
			{id: "S", round: "S", inherits: "P", td: 6, vod: "freezing"},
		}, sessions: "PPPSSSSS", chain: chainOf("S", "S"), key: "vod/str/S/index.m3u8", first: 3, last: 7},
		{name: "M6_전_부모_컷오프가_계승_세션_첫_행", ss: []roundSess{
			{id: "P", td: 6, vod: "freezing"},
			{id: "S", round: "S", inherits: "P", td: 6, vod: "freezing"},
		}, sessions: "PPPPPSSSSS", cutoff: 5, chain: chainOf("S", "P", "S"), key: "vod/str/P/index.m3u8", first: 5, last: 9},
		{name: "컷오프가_계승_세션_첫_행", ss: []roundSess{
			{id: "P", round: "P", td: 6, vod: "freezing"},
			{id: "S", round: "P", inherits: "P", td: 6, vod: "freezing"},
		}, sessions: "PPPPPSSSSS", cutoff: 5, chain: chainOf("P", "P", "S"), key: "vod/str/P/index.m3u8", first: 5, last: 9},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			rows := make([]vodRow, len(tt.sessions))
			for i, c := range tt.sessions {
				rows[i] = vodRow{session: string(c), state: "uploaded"}
			}
			p, store := newRoundVOD(t, tt.ss, rows)
			exec(t, p.pool, `UPDATE stream_cutoffs SET cutoff_seq = $2 WHERE stream_id = $1`, fxStream, tt.cutoff)

			out := p.FreezeVOD(t.Context(), tt.chain, false)

			wantOutcome(t, out, AxisVOD, ResultFrozen, false)
			if out.Scope != ScopeChain || out.Fallback != "" {
				t.Errorf("동결 = %+v, want Scope chain · 물러섬 없음", out)
			}
			body, _ := stored(store, tt.key)
			if body == nil {
				t.Fatalf("문서 %s 가 없다", tt.key)
			}
			lines := vodLines(body)
			if len(lines) != int(tt.last-tt.first+1) {
				t.Fatalf("문서 조각 %d 개, want %d(seq %d–%d):\n%s", len(lines), tt.last-tt.first+1, tt.first, tt.last, body)
			}
			if strings.HasPrefix(lines[0], "#EXT-X-DISCONTINUITY ") {
				t.Errorf("첫 조각 줄 %q — 소유 세션의 계승을 지우지 않아 끊김 표시가 섰다", lines[0])
			}
			for _, id := range tt.chain.SessionIDs {
				if v := readVOD(t, p, id); strOrNil(v.key) != tt.key || v.first == nil || *v.first != tt.first || v.last == nil || *v.last != tt.last {
					t.Errorf("%s VOD 열 = %+v(키 %s), want %s · 범위 %d–%d", id, v, strOrNil(v.key), tt.key, tt.first, tt.last)
				}
			}
		})
	}
}
