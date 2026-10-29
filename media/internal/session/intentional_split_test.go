package session

// planSplit 의 갈래(설계 M6 3.4 분리 가능 조건 · kty ⑪ (가)) — DB 없이 회차 행만으로 정한다.

import (
	"testing"
	"time"
)

// 합류 세션 B 가 없으면 noop(뗄 세션 없음)이고 blocked 가 아니다. 회차를 가로지르는 간선의 한 끝이라도 닫힌
// 사슬(vod_state ≠ none)이면 blocked 다 — 자식 쪽 · 부모 쪽 두 갈래. 양 끝이 열려 있으면 분리하고 B 의 계승
// 해제를 요청한다. 변이 = B 부재를 blocked 로 되돌림 → noop 행 FAIL · 부모 쪽 검사 제거 → 부모 행 FAIL.
func TestPlanSplitBranches(t *testing.T) {
	row := func(id, vod, inherits string, at time.Duration) roundRow {
		return roundRow{id: id, state: "ending", vodState: vod, inherits: inherits, startedAt: stopAt.Add(at)}
	}
	tests := []struct {
		name                 string
		rows                 []roundRow
		noop, blocked        bool
		wantHead, wantRevoke string
	}{
		{
			name: "B 부재",
			rows: []roundRow{row("S-T", "none", "", -time.Hour), row("S-L", "none", "", -30*time.Minute)},
			noop: true,
		},
		{
			name:       "B 쪽 사슬이 동결 중",
			rows:       []roundRow{row("S-T", "none", "", -time.Hour), row("S-B", "freezing", "S-T", 20*time.Second)},
			blocked:    true,
			wantHead:   "S-B",
			wantRevoke: "S-B", // 막히면 쓰이지 않는다 — 계산만 같다
		},
		{
			name:       "부모 쪽 사슬이 동결됨",
			rows:       []roundRow{row("S-T", "frozen", "", -time.Hour), row("S-B", "none", "S-T", 20*time.Second)},
			blocked:    true,
			wantHead:   "S-B",
			wantRevoke: "S-B", // 막히면 쓰이지 않는다 — 계산만 같다
		},
		{
			name:       "양 끝 열림",
			rows:       []roundRow{row("S-T", "none", "", -time.Hour), row("S-B", "none", "S-T", 20*time.Second)},
			wantHead:   "S-B",
			wantRevoke: "S-B",
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			p := planSplit(tt.rows, stopAt)

			if p.noop != tt.noop || p.blocked != tt.blocked || p.head.id != tt.wantHead || p.revoke != tt.wantRevoke {
				t.Errorf("planSplit = (noop %v, blocked %v, head %q, revoke %q), want (%v, %v, %q, %q)",
					p.noop, p.blocked, p.head.id, p.revoke, tt.noop, tt.blocked, tt.wantHead, tt.wantRevoke)
			}
		})
	}
}
