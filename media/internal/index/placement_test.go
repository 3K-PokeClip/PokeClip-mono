package index

import (
	"testing"
	"time"
)

// placementBase 는 위치 식 표 시험의 기준 PDT 다 — ms 아래 자리가 없다.
var placementBase = time.Date(2026, 11, 3, 9, 0, 0, 0, time.UTC)

// legacyStart 는 판 NULL 위치 식(r6 I23 승계 · ε 항 0)이다.
//
//	PDT(k) 있음:  max(j′.start + ⌊PDT(k)⌋ − ⌊j′.PDT⌋, q.start + q.dur)
//	PDT(k) 없음:  q.start + q.dur
//	스트림 첫 행: 0
func TestLegacyStartFollowsPlacementFormula(t *testing.T) {
	ms := func(n int64) time.Duration { return time.Duration(n) * time.Millisecond }
	at := func(d time.Duration) *time.Time { v := placementBase.Add(d); return &v }
	cases := []struct {
		name   string
		q      *lastRow
		anchor *anchorRow
		pdt    *time.Time
		want   int64
	}{
		{"스트림 첫 행", nil, nil, at(0), 0},
		{"스트림 첫 행 · PDT 없음", nil, nil, nil, 0},
		{"PDT 없음 → q 끝", &lastRow{StartPTSMS: 8000, DurationMS: 4000}, nil, nil, 12000},
		{"앵커 없음 → q 끝", &lastRow{StartPTSMS: 8000, DurationMS: 4000}, nil, at(ms(30000)), 12000},
		{"앵커 = q · 잇닿음", &lastRow{StartPTSMS: 8000, DurationMS: 4000},
			&anchorRow{StartPTSMS: 8000, PDT: placementBase}, at(ms(4000)), 12000},
		{"앵커 = q · 벽시계 앞뜀 → 앞 항이 이김", &lastRow{StartPTSMS: 8000, DurationMS: 4000},
			&anchorRow{StartPTSMS: 8000, PDT: placementBase}, at(ms(10000)), 18000},
		{"앵커가 NULL 꼬리 너머 · 앞 항 = 뒤 항", &lastRow{StartPTSMS: 16000, DurationMS: 4000},
			&anchorRow{StartPTSMS: 8000, PDT: placementBase}, at(ms(12000)), 20000},
		{"벽시계가 뒤로 → 뒤 항이 이김", &lastRow{StartPTSMS: 8000, DurationMS: 4000},
			&anchorRow{StartPTSMS: 8000, PDT: placementBase.Add(ms(100000))}, at(ms(3000)), 12000},
		// ⌊⌋ 은 항마다 한 번이다: ⌊T+4000.6⌋ − ⌊T+0.4⌋ = 4000 — 반올림이면 4001, 차의 내림이면 4000 이지만
		// ⌊T+4000.3⌋ − ⌊T+0.6⌋ 은 4000 이고 차의 내림은 3999 다(아래 행).
		{"µs 앵커 · 내림 둘", &lastRow{StartPTSMS: 0, DurationMS: 1},
			&anchorRow{StartPTSMS: 0, PDT: placementBase.Add(400 * time.Microsecond)}, at(ms(4000) + 600*time.Microsecond), 4000},
		{"µs 앵커 · 차의 내림과 다름", &lastRow{StartPTSMS: 0, DurationMS: 1},
			&anchorRow{StartPTSMS: 0, PDT: placementBase.Add(600 * time.Microsecond)}, at(ms(4000) + 300*time.Microsecond), 4000},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := legacyStart(c.q, c.anchor, c.pdt); got != c.want {
				t.Errorf("legacyStart = %d, want %d", got, c.want)
			}
		})
	}
}

// 판 NULL 귀속 PDT 는 max(j*.PDT + j*.dur + U, wall) 이다(r6 309–310 승계 · 계약 6-6-3 「비귀속 행 뒤 첫 귀속 행의 PDT 는
// 그 사이 비귀속 행 길이 합만큼 더 간다」). 기저 행이 없거나 그 행의 PDT 가 NULL 이면 벽시계다.
func TestLegacyPDTAddsUnattributedLength(t *testing.T) {
	wall := placementBase.Add(time.Second)
	pdt := placementBase
	cases := []struct {
		name string
		base *baseRow
		want time.Time
	}{
		{"기저 없음", nil, wall},
		{"기저 PDT NULL", &baseRow{DurationMS: 4000, UnattributedMS: 8000}, wall},
		{"누적 · U 0", &baseRow{PDT: &pdt, DurationMS: 4000}, placementBase.Add(4 * time.Second)},
		{"누적 · U 8000", &baseRow{PDT: &pdt, DurationMS: 4000, UnattributedMS: 8000}, placementBase.Add(12 * time.Second)},
		{"벽시계가 이김", &baseRow{PDT: &pdt, DurationMS: 400, UnattributedMS: 200}, wall},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := legacyPDT(c.base, wall); !got.Equal(c.want) {
				t.Errorf("legacyPDT = %v, want %v", got, c.want)
			}
		})
	}
}
