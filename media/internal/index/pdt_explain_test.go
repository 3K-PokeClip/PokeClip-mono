package index

import (
	"fmt"
	"math"
	"slices"
	"testing"
	"time"
)

// 시험 오라클 explainV2(설계 2.15) — 시험 코드이고 제품 API 가 아니다. MixV2 와 따로 쓴 검사로, 장부
// 행마다 그 p · q 를 장부에서 다시 찾아 「저장 PDT · 위치 · 길이가 원래 사실로 설명되는가」 를 본다.
// 「PDT = E」 라는 값만으로 갈래(join)를 추론하지 않는다 — 겹침 식의 하한 항이 E 에 닿을 수 있어서다
// (r3 cx H4). 섞기 식을 부르지 않고 설계 문장을 그대로 다시 쓴다.

// explainRow 는 오라클이 읽는 장부 한 행이다(seq 순 입력).
type explainRow struct {
	PrevRow
	Round *string // timing_round_id · nil: 회차 없음
	// LegacyMS 는 그 조각의 mvhd 길이다 — 장부에 칸이 없어 재생 입력에서만 채운다(0: 모름 · 영상 길이를 모르는
	// 행의 길이를 보지 않는다). 그래서 0 인 행에서는 범위 밖인데 양수로 감긴 길이(2^32 + 1 → 1)를 오라클이 못
	// 잡는다. 범위 밖 영상을 넣는 곳은 채운다(재생 입력 · TestExplainV2OverlongVideo) — 장부에서 읽는 PG 시험
	// (ledgerExplainRows)은 0 이지만 그 영상은 범위 안이다(c3b r2 cc L — 한계로 둔다).
	LegacyMS int32
}

// explainV2 는 rows 의 판 2 행마다 설명되지 않는 칸을 문장으로 돌려준다. 비어 있으면 모두 설명된다.
func explainV2(rows []explainRow, theta time.Duration) []string {
	var bad []string
	for i, k := range rows {
		if k.Version == nil || *k.Version != versionV2 {
			continue
		}
		if msg := explainRowV2(rows[:i], k, theta.Milliseconds()); msg != "" {
			bad = append(bad, fmt.Sprintf("seq %d: %s", k.Seq, msg))
		}
	}
	return bad
}

// explainRowV2 는 앞 행들 before(seq 순) 뒤에 온 판 2 행 k 하나를 본다.
func explainRowV2(before []explainRow, k explainRow, theta int64) string {
	if k.PDTMS == nil {
		return "판 2 인데 PDT 없음"
	}
	pdt, w := *k.PDTMS, k.Facts.Wall.UnixMilli()
	var q *explainRow
	if len(before) > 0 {
		q = &before[len(before)-1]
	}
	p := explainPrev(before, k.Round)
	own, known := explainVideoLength(p, k)
	// 칸이 있는데 영상 길이가 범위 밖(1ms 미만 · int32 ms 초과)이면 mvhd 다. 칸이 없으면(mtxi 없음) 파일 안
	// 영상 길이라 장부만으로 다시 낼 수 없다.
	hasCols := k.Facts.VideoFirst != nil && k.Facts.VideoEnd != nil
	switch {
	case known && int64(k.DurationMS) != own:
		return fmt.Sprintf("길이 %d ≠ 칸에서 다시 낸 영상 길이 %d", k.DurationMS, own)
	case hasCols && !known && k.LegacyMS != 0 && k.DurationMS != max(k.LegacyMS, 1):
		return fmt.Sprintf("영상 길이 범위 밖(%dms)인데 길이 %d ≠ mvhd %d", own, k.DurationMS, k.LegacyMS)
	}
	if k.DurationMS < 1 {
		return fmt.Sprintf("길이 %d < 1", k.DurationMS)
	}

	// 1항 — 같은 회차 판 2 앞 행이 없으면 PDT = w · start = q 끝(회차 칸 NULL 이면 q.start 도 받는다).
	if p == nil {
		if pdt != w {
			return fmt.Sprintf("회차 첫 행 PDT %d ≠ w %d", pdt, w)
		}
		var qStart, qEnd int64
		if q != nil {
			qStart, qEnd = q.StartPTSMS, q.StartPTSMS+int64(q.DurationMS)
		}
		if k.StartPTSMS == qEnd || (k.Round == nil && k.StartPTSMS == qStart) {
			return ""
		}
		return fmt.Sprintf("회차 첫 행 start %d ∉ {q.start %d(회차 없음만), q 끝 %d}", k.StartPTSMS, qStart, qEnd)
	}

	// 2항 — p 가 있으면 Δ = w − E 로 견준 값을 계산한다.
	s := *p.PDTMS
	e := s + int64(p.DurationMS)
	compared := explainComparedValue(w-e, theta, w, s, e, k.DurationMS)
	// 3항 · 4항 — 칸으로 견줄 조각이 확실한 행은 정확히 그 값, 그 밖(같은 녹화기 · 영상 앎 — 머리를
	// 칸으로 모름)은 E(continue) 또는 그 값이다.
	definite := !SameInstance(p.Facts, k.Facts) || !known
	switch {
	case definite && pdt != compared:
		return fmt.Sprintf("견줄 조각 PDT %d ≠ 견준 값 %d(E %d · w %d)", pdt, compared, e, w)
	case !definite && pdt != compared && pdt != e:
		return fmt.Sprintf("PDT %d ∉ {E %d, 견준 값 %d}", pdt, e, compared)
	}
	// 위치 보폭 = PDT 보폭.
	if want := p.StartPTSMS + pdt - s; k.StartPTSMS != want {
		return fmt.Sprintf("start %d ≠ p.start + PDT − S = %d", k.StartPTSMS, want)
	}
	return ""
}

// explainPrev 는 k 의 회차 round 에서 가장 늦은 앞 행이 판 2 이면 그 행이다. 회차가 없거나 그 행이
// 없거나 판 2 가 아니면 nil(회차 첫 행)이다.
func explainPrev(before []explainRow, round *string) *explainRow {
	if round == nil {
		return nil
	}
	for i := len(before) - 1; i >= 0; i-- {
		r := &before[i]
		if r.Round == nil || *r.Round != *round {
			continue
		}
		if r.Version == nil || *r.Version != versionV2 || r.PDTMS == nil {
			return nil
		}
		return r
	}
	return nil
}

// explainVideoLength 는 칸에서 다시 낸 영상 길이(넓은 정수 — 좁혀 감긴 값을 통과시키지 않는다)와 그것이
// 1ms 이상 · int32 ms 이하인가(영상 앎)다. 칸에 영상이 없으면(mtxi 없음 · 영상 트랙 없음) 길이를 다시 낼
// 수 없어 known 은 거짓이다.
func explainVideoLength(p *explainRow, k explainRow) (int64, bool) {
	f := k.Facts
	if f.VideoFirst == nil || f.VideoEnd == nil {
		return 0, false
	}
	from := *f.VideoFirst
	if p != nil && Contiguous(p.Facts, f) {
		from = *p.Facts.VideoEnd
	}
	ms := explainFloorMS(*f.VideoEnd) - explainFloorMS(from)
	return ms, ms >= 1 && ms <= math.MaxInt32
}

// explainFloorMS 는 d 를 ms 로 내린다 — 음수는 0 쪽이 아니라 아래로(설계 2.6 「ms 내림」). B 프레임 송출의
// 녹화기 인스턴스 첫 조각은 영상 첫 DTS 가 음수다(T-ovl 2차 vtb 첫 조각 a = −0.011ms).
func explainFloorMS(d time.Duration) int64 {
	return int64(math.Floor(float64(d) / float64(time.Millisecond)))
}

// explainComparedValue 는 견줬을 때의 PDT 다 — Δ > θ 면 w, Δ < −θ 면 겹침 식(K1 (나)), 그 밖 E.
func explainComparedValue(delta, theta, w, s, e int64, dur int32) int64 {
	switch {
	case delta > theta:
		return w
	case delta >= -theta:
		return e
	}
	return slices.Max([]int64{w, e - 999, e - int64(dur) + 1, s + 1})
}

// 오라클 자기 시험 둘(설계 2.15 끝). cx 반례: 앞 [0, 4000) · 새 녹화기 · w = 3500 · dur = 1 → 겹침 식
// max(3500, 3001, 4000, 1) = 4000 = E — 「PDT = E 면 join」 으로 추론하면 견줄 조각인데 E 라 헷갈리지만
// 식 값과 같으므로 통과해야 한다. PDT 를 E − 999 로 바꾼 행은 실패해야 한다.
func TestExplainV2SelfCheck(t *testing.T) {
	// p 는 스트림 첫 행(PDT = w · start 0)이라 오라클 1항도 지난다.
	p := explainRow{PrevRow: *mixPrev(4000, mixPrevFacts), Round: &mixRound}
	p.StartPTSMS, p.Facts.Wall = 0, time.UnixMilli(mixP)
	newRec := withVideo(marked(recorderY, 0), 0, time.Millisecond)
	newRec.Wall = time.UnixMilli(mixP + 3500)
	e := mixP + 4000
	k := explainRow{PrevRow: PrevRow{Seq: 11, StartPTSMS: p.StartPTSMS + 4000, DurationMS: 1, PDTMS: &e,
		Version: new(versionV2), Facts: newRec}, Round: &mixRound}
	if bad := explainV2([]explainRow{p, k}, MixThreshold); len(bad) != 0 {
		t.Errorf("explainV2(cx 반례 · PDT = E) = %q, want 통과", bad)
	}
	early := e - 999
	k.PDTMS, k.StartPTSMS = &early, p.StartPTSMS+4000-999
	if bad := explainV2([]explainRow{p, k}, MixThreshold); len(bad) == 0 {
		t.Error("explainV2(PDT = E − 999) 통과, want 실패")
	}
}

// 오라클은 영상 길이를 넓은 정수로 다시 낸다(cx H1). 영상 구간 2^32 + 1ms 는 길이를 모르는 행이라 mvhd 가
// 맞고, int32 로 좁혀 감긴 1ms 는 실패해야 한다.
func TestExplainV2OverlongVideo(t *testing.T) {
	p := explainRow{PrevRow: *mixPrev(4000, mixPrevFacts), Round: &mixRound}
	p.StartPTSMS, p.Facts.Wall = 0, time.UnixMilli(mixP)
	long := withVideo(marked(recorderY, 0), 0, (1<<32+1)*time.Millisecond)
	long.Wall = time.UnixMilli(mixP + 4000)
	e := mixP + 4000
	k := explainRow{PrevRow: PrevRow{Seq: 11, StartPTSMS: 4000, DurationMS: 4010, PDTMS: &e, Version: new(versionV2),
		Facts: long}, Round: &mixRound, LegacyMS: 4010}
	if bad := explainV2([]explainRow{p, k}, MixThreshold); len(bad) != 0 {
		t.Errorf("explainV2(영상 2^32+1ms · 길이 mvhd) = %q, want 통과", bad)
	}
	k.DurationMS = 1
	if bad := explainV2([]explainRow{p, k}, MixThreshold); len(bad) == 0 {
		t.Error("explainV2(영상 2^32+1ms · 길이 1 — int32 감김) 통과, want 실패")
	}
}
