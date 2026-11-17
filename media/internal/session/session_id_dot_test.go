package session

import (
	"math"
	"strings"
	"testing"
	"time"
)

// session_id_has_no_dot(POK-549 설계 r4 4절 D1 · R6) — 세션 id 에는 점이 없고 길이는 102자를 넘지 않는다. 회차 VOD 키의
// 둘째 성분 {round_id}.round(publish vodRoundKey)는 점이 들어 어떤 세션 id(사슬 VOD 키의 둘째 성분 — 머리 세션)와도 같을
// 수 없고, 가장 길어도 108자(엣지 128 안)다 — 그 기대를 여기서 고정한다. 스트림 id 는 허용 문자 [A-Za-z0-9_-]{1,64} 의
// 끝값이고(recording/name.go streamIDRe) seq 는 0 이상이다.
func TestSessionIDHasNoDot(t *testing.T) {
	longest := strings.Repeat("Az0_-", 13)[:64]
	tests := []struct {
		name   string
		stream string
		start  time.Time
		seq    int64
	}{
		{name: "보통", stream: "demo", start: wall, seq: 4},
		{name: "가장_긴_스트림_seq", stream: longest, start: time.Date(9999, 12, 31, 23, 59, 59, 999999999, time.UTC),
			seq: math.MaxInt64},
		{name: "한_글자_스트림_seq_0", stream: "a", start: time.Unix(0, 0), seq: 0},
		{name: "UTC_밖_시각", stream: "s", start: time.Date(2026, 11, 4, 14, 11, 0, 0, time.FixedZone("KST", 9*3600)), seq: 1627},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			id := sessionID(tt.stream, tt.start, tt.seq)
			if strings.Contains(id, ".") || len(id) > 102 {
				t.Errorf("sessionID = %q(%d자), want 점 없음 · 102자 이하", id, len(id))
			}
		})
	}
}
