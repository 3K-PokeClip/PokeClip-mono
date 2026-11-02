package cache_test

// PDTRange(제어면 상태의 firstPdt · headPdt 재료 — 설계 M5 2.1 · 4.3 · 7절 cache 행)의 단위 검증이다 —
// POK-200 ②r. 읽기만 하는 질의라 뷰를 바꾸지 않고, 「모른다」(행 없음 · PDT NULL · 적재 중 · 모르는 스트림)는
// 영값이 아니라 거짓으로 알린다 — 상태 쪽이 그때 ready 를 거짓으로 둔다(설계 4.3).

import (
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/cache"
)

// rangeSession 은 PDTRange 픽스처의 회차다.
var rangeSession = index.RewindSession{SessionID: "S1", State: "live", TargetDuration: 4, FirstPDT: at(0), MinSeq: 10}

// rangeCache 는 컷오프 10 · seq 10..14(4초 조각 · 끝 행만 2.5초)를 든 캐시다. 끝 행의 길이를 다르게 둬
// head 가 그 행의 실제 길이를 더하는지 가린다.
func rangeCache(t *testing.T) *cache.Cache {
	t.Helper()
	rows := ledgerRun("S1", 10, 14, at(0))
	rows[len(rows)-1].DurationMS = 2500
	c := &cache.Cache{}
	load(t, c, stream, ledgerOf(10, rows, rangeSession))
	return c
}

// first 는 from 행의 PDT, head 는 to 행의 PDT + 그 행의 길이다.
func TestPDTRangeReadsFirstPDTAndHeadEnd(t *testing.T) {
	c := rangeCache(t)
	for _, tc := range []struct {
		name            string
		from, to        int64
		first, wantHead time.Time
	}{
		{"전체", 10, 14, at(0), at(16*time.Second + 2500*time.Millisecond)},
		{"가운데", 11, 13, at(4 * time.Second), at(16 * time.Second)},
		{"한_행", 12, 12, at(8 * time.Second), at(12 * time.Second)},
	} {
		first, head, ok := c.PDTRange(stream, tc.from, tc.to)
		if !ok || !first.Equal(tc.first) || !head.Equal(tc.wantHead) {
			t.Errorf("%s: PDTRange(%d, %d) = (%v, %v, %v), want (%v, %v, true)",
				tc.name, tc.from, tc.to, first, head, ok, tc.first, tc.wantHead)
		}
	}
}

// 범위의 행이 뷰에 없으면(뷰 하한 아래 · 머리 위 · 거꾸로 된 범위) 거짓이다.
func TestPDTRangeFalseWhenRowsMissing(t *testing.T) {
	c := rangeCache(t)
	for _, tc := range []struct {
		name     string
		from, to int64
	}{
		{"하한_아래", 9, 12},
		{"머리_위", 12, 15},
		{"거꾸로", 13, 12},
	} {
		if first, head, ok := c.PDTRange(stream, tc.from, tc.to); ok {
			t.Errorf("%s: PDTRange(%d, %d) = (%v, %v, true), want 거짓", tc.name, tc.from, tc.to, first, head)
		}
	}
}

// 양끝 행의 PDT 가 NULL(영값)이면 잴 수 없으므로 거짓이다.
func TestPDTRangeFalseWhenEndpointPDTIsNull(t *testing.T) {
	for _, tc := range []struct {
		name string
		null int // PDT 를 비울 행의 자리(seq 10 이 0)
	}{
		{"첫_행", 1},
		{"끝_행", 3},
	} {
		rows := ledgerRun("S1", 10, 14, at(0))
		rows[tc.null].PlaybackPDT = time.Time{}
		c := &cache.Cache{}
		load(t, c, stream, ledgerOf(10, rows, rangeSession))
		if first, head, ok := c.PDTRange(stream, 11, 13); ok {
			t.Errorf("%s PDT NULL: PDTRange(11, 13) = (%v, %v, true), want 거짓", tc.name, first, head)
		}
	}
}

// 적재 중 · 모르는 스트림 · nil 캐시는 「모른다」 — 거짓이다(적재 중 읽기는 「모른다」 — 캐시 규약).
func TestPDTRangeFalseWhenViewUnreadable(t *testing.T) {
	c := rangeCache(t)
	c.BeginLoad(stream, nil)
	if first, head, ok := c.PDTRange(stream, 10, 14); ok {
		t.Errorf("적재 중 PDTRange = (%v, %v, true), want 거짓", first, head)
	}
	if first, head, ok := rangeCache(t).PDTRange("없는스트림", 10, 14); ok {
		t.Errorf("모르는 스트림 PDTRange = (%v, %v, true), want 거짓", first, head)
	}
	var nilCache *cache.Cache
	if first, head, ok := nilCache.PDTRange(stream, 10, 14); ok {
		t.Errorf("nil 캐시 PDTRange = (%v, %v, true), want 거짓", first, head)
	}
}
